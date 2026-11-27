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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class BashToolEventWorkdirTest {
    @Nested
    @FusionSupport(modules = SharedToolStreamsRetainEachParentAndChildInvocationRootScenarioModule.class)
    class SharedToolStreamsRetainEachParentAndChildInvocationRootScenario {
        @TempDir
        static Path directory;

        @Test
        void sharedToolStreamsRetainEachParentAndChildInvocationRoot(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var tmp = SharedToolStreamsRetainEachParentAndChildInvocationRootScenario.directory;
            Assumptions.assumeTrue(
                    !System.getProperty("os.name", "").toLowerCase().contains("win"));
            final var a = Files.createDirectories(tmp.resolve("a"));
            final var b = Files.createDirectories(tmp.resolve("b"));
            final var streams = new CopyOnWriteArrayList<AgentEvent.ToolStream>();
            final var emitter = new Emitter() {
                @Override
                public <T> void emit(final T event) {
                    if (event instanceof AgentEvent.ToolStream stream) {
                        streams.add(stream);
                    }
                }
            };
            final var processes = new ProcessService();
            final var tool = new BashTool(emitter, processes, configuration, null);
            try {
                final var roots = List.of(a, b, a, b);
                for (var i = 0; i < roots.size(); i++) {
                    final var root = roots.get(i);
                    final var session = root.getFileName().toString();
                    final var child = i < 2 ? null : "child";
                    final var context = new ToolContext(
                            session,
                            child,
                            child,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Cancellations.NEVER,
                            null,
                            null,
                            root,
                            new DisabledSandbox(root));
                    final var start = streams.size();
                    final var result =
                            tool.execute(Map.of("command", "printf stream-output", "timeout_seconds", 5), context);
                    assertTrue(result.success(), result.output());
                    final var emitted = List.copyOf(streams.subList(start, streams.size()));
                    assertEquals(
                            List.of("$ printf stream-output", "stream-output"),
                            emitted.stream()
                                    .map(AgentEvent.ToolStream::fragment)
                                    .toList());
                    for (final var event : emitted) {
                        assertSame(root, event.workdir());
                        assertEquals(session, event.sessionId());
                        assertEquals(child, event.subagentId());
                        assertEquals(child, event.subagentName());
                    }
                }
            } finally {
                processes.disposeAll();
            }
        }
    }

    public static class SharedToolStreamsRetainEachParentAndChildInvocationRootScenarioModule
            extends ConfigurationTestModule {
        public SharedToolStreamsRetainEachParentAndChildInvocationRootScenarioModule() {
            super(Map.of(
                    "yca.workingDir",
                    SharedToolStreamsRetainEachParentAndChildInvocationRootScenario.directory
                            .resolve("a")
                            .toString()));
        }
    }
}
