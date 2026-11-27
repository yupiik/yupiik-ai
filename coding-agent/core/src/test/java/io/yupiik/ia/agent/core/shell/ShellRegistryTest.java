/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellRegistryTest {
    @TempDir
    private static Path eventWorkdir;

    @Test
    void capturesSubagentConversationWithoutSuppressingCliLiveOutput() {
        final var subagents = new SubagentRegistry(null, null);
        final var frontend = new RecordingFrontend();
        final var registry = new ShellRegistry(collecting(null), subagents);
        registry.register(env(frontend, "parent"));
        registry.onEvent(new AgentEvent.TextDelta("child response", "parent", "child-id", "reviewer", eventWorkdir));
        assertEquals(List.of("transcript:child response"), frontend.calls);
        registry.onEvent(new AgentEvent.TurnDone("child response", "parent", "child-id", "reviewer", eventWorkdir));
        assertTrue(subagents.transcriptOf("child-id").markdown("", "").contains("child response"));
        registry.bindSubagents(subagents);
        registry.onEvent(new AgentEvent.TextDelta("modal only", "parent", "child-id", "reviewer", eventWorkdir));
        registry.onEvent(new AgentEvent.TurnDone("modal only", "parent", "child-id", "reviewer", eventWorkdir));
        assertEquals(List.of("transcript:child response"), frontend.calls);
        assertTrue(subagents.transcriptOf("child-id").markdown("", "").contains("modal only"));
    }

    @Test
    void registersAndForwardsEventsToTheOwningFrontend() {
        final var frontend = new RecordingFrontend();
        final var registry = new ShellRegistry(collecting(null));
        final var env = env(frontend, "s1");

        assertNull(registry.active("s1"));
        registry.register(env);

        assertSame(env, registry.active("s1"));
        registry.onEvent(new AgentEvent.Status("working", "s1", eventWorkdir));
        registry.onEvent(
                new AgentEvent.Notice(AgentEvent.Notice.Level.WARN, "careful", null, "s1", null, null, eventWorkdir));
        registry.onEvent(new AgentEvent.Error("boom", "s1", eventWorkdir));

        assertEquals(List.of("status:working", "notice:WARN:careful", "error:boom"), frontend.calls);
    }

    @Test
    void untaggedEventsAreDroppedAndNullIdsRejected() {
        final var frontend = new RecordingFrontend();
        final var registry = new ShellRegistry(collecting(null));
        final var env = env(frontend, "s1");

        registry.register(env);
        registry.onEvent(new AgentEvent.Status("working", null, null, null, eventWorkdir));

        assertEquals(List.of(), frontend.calls);
        assertThrows(IllegalArgumentException.class, () -> registry.register(env(frontend, null)));
    }

    @Test
    void concurrentSessionsReceiveOnlyTheirOwnEvents() {
        final var first = new RecordingFrontend();
        final var second = new RecordingFrontend();
        final var registry = new ShellRegistry(collecting(null));

        registry.register(env(first, "s1"));
        registry.register(env(second, "s2"));

        registry.onEvent(new AgentEvent.Status("first working", "s1", eventWorkdir));
        registry.onEvent(new AgentEvent.Status("second working", "s2", eventWorkdir));
        registry.onEvent(new AgentEvent.TextDelta("hello", "s2", null, null, eventWorkdir));
        registry.onEvent(new AgentEvent.TextDelta("ignored", "s3", null, null, eventWorkdir));

        assertEquals(List.of("status:first working"), first.calls);
        assertEquals(List.of("status:second working", "transcript:hello"), second.calls);
        assertEquals(List.of("s1", "s2"), registry.sessions().stream().sorted().toList());
    }

    @Test
    void unregisteredStopsForwarding() {
        final var frontend = new RecordingFrontend();
        final var registry = new ShellRegistry(collecting(null));
        final var env = env(frontend, "s1");

        registry.register(env);
        registry.unregister(env);
        registry.onEvent(new AgentEvent.Status("ignored", "s1", eventWorkdir));

        assertEquals(List.of(), frontend.calls);
        assertNull(registry.active("s1"));
    }

    @Test
    void closingOneSessionKeepsTheOtherLive() {
        final var first = new RecordingFrontend();
        final var second = new RecordingFrontend();
        final var registry = new ShellRegistry(collecting(null));
        final var firstEnv = env(first, "s1");
        registry.register(firstEnv);
        registry.register(env(second, "s2"));

        registry.unregister(firstEnv);
        registry.onEvent(new AgentEvent.Status("still working", "s2", eventWorkdir));

        assertEquals(List.of(), first.calls);
        assertEquals(List.of("status:still working"), second.calls);
    }

    @Test
    void foreignUnregisterIsIgnored() {
        final var registry = new ShellRegistry(collecting(null));
        final var env = env(new RecordingFrontend(), "s1");
        registry.register(env);

        registry.unregister(env(new RecordingFrontend(), "s1"));

        assertSame(env, registry.active("s1"));
    }

    @Test
    void lifecycleEventsAreBroadcast() {
        final var events = new ArrayList<>();
        final var registry = new ShellRegistry(collecting(events));
        final var env = env(new RecordingFrontend(), "s1");

        registry.register(env);
        registry.unregister(env);

        assertEquals(List.of(new ShellRegistered(env), new ShellUnregistered(env)), events);
    }

    private static ShellEnv env(final ShellFrontend frontend, final String sessionId) {
        return new ShellEnv(frontend, null, new ShellSession().sessionId(sessionId), new ConversationTranscript());
    }

    private static Emitter collecting(final List<Object> into) {
        return new Emitter() {
            @Override
            public <T> void emit(final T event) {
                if (into != null) {
                    into.add(event);
                }
            }
        };
    }

    private static final class RecordingFrontend implements ShellFrontend {
        final List<String> calls = new ArrayList<>();

        @Override
        public void status(final String message) {
            calls.add("status:" + message);
        }

        @Override
        public void notice(final AgentEvent.Notice.Level level, final String message) {
            calls.add("notice:" + level + ":" + message);
        }

        @Override
        public void transcript(final String markdown) {
            calls.add("transcript:" + markdown);
        }

        @Override
        public void error(final String message) {
            calls.add("error:" + message);
        }

        @Override
        public void onEvent(final AgentEvent event) {
            if (event
                    instanceof
                    AgentEvent.TextDelta(var delta, var sessionId, var subagentId, var subagentName, var _)) {
                // like the real frontends, stream text deltas through the transcript
                transcript(delta);
            } else {
                ShellFrontend.super.onEvent(event);
            }
        }

        @Override
        public void showThinking(final boolean value) {
            calls.add("showThinking:" + value);
        }

        @Override
        public void showToolOutput(final ToolOutputVisibility value) {
            calls.add("showToolOutput:" + value);
        }
    }
}
