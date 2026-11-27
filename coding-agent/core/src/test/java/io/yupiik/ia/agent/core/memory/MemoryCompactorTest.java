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
package io.yupiik.ia.agent.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Usage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryCompactorTest {

    @Nested
    @FusionSupport(modules = RootAwareCompactionKeepsBackupsAndStateWithTheirProjectModule.class)
    class RootAwareCompactionKeepsBackupsAndStateWithTheirProject {
        @TempDir
        static Path home;

        @Test
        void rootAwareCompactionKeepsBackupsAndStateWithTheirProject(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var alpha = home.resolve("alpha");
            final var beta = home.resolve("beta");
            final var original = MemoryStore.HEADER + "- old project facts and repeated observations\n".repeat(10);
            for (final var root : List.of(home, alpha, beta)) {
                final var file = MemoryStore.projectFile(root);
                Files.createDirectories(file.getParent());
                Files.writeString(file, original);
            }
            final var global = store.append(true, List.of("global preference"), config.resolveWorkingDir());
            final var compactor = new MemoryCompactor(new FakeChatClient(), config, store, null, null);
            assertTrue(compactor.compactNow(alpha).startsWith("memory compacted"));
            assertEquals(original, store.readProject(beta));
            assertEquals(original, store.readProject(config.resolveWorkingDir()));
            assertNotNull(compactor.state(alpha));
            assertNull(compactor.state(beta));
            assertNull(compactor.state(config.resolveWorkingDir()));
            assertEquals(
                    original, Files.readString(MemoryStore.projectFile(alpha).resolveSibling("MEMORY.md.bak")));
            assertTrue(compactor.compactNow(beta).startsWith("memory compacted"));
            assertNotNull(compactor.state(beta));
            assertEquals(
                    original, Files.readString(MemoryStore.projectFile(beta).resolveSibling("MEMORY.md.bak")));
            assertTrue(compactor.compactNow(config.resolveWorkingDir()).startsWith("memory compacted"));
            assertNotNull(compactor.state(config.resolveWorkingDir()));
            assertThrows(NullPointerException.class, () -> compactor.state(null));
            assertTrue(store.readGlobal().contains(MemoryStore.textOf(global.getFirst())));
        }
    }

    @Nested
    @FusionSupport(modules = AutomaticCompactionChecksTheSessionRootInsteadOfTheConfiguredFileModule.class)
    class AutomaticCompactionChecksTheSessionRootInsteadOfTheConfiguredFile {
        @TempDir
        static Path home;

        @Test
        void automaticCompactionChecksTheSessionRootInsteadOfTheConfiguredFile(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            final var root = home.resolve("session-project");
            final var original = MemoryStore.HEADER + "- old durable project observation\n".repeat(2400);
            final var file = MemoryStore.projectFile(root);
            Files.createDirectories(file.getParent());
            Files.writeString(file, original);
            final var compactor = new MemoryCompactor(new FakeChatClient(), config, store, null, null);
            assertNotNull(compactor.maybeCompact(ChatSettings.EMPTY, root));
            assertEquals(original, Files.readString(file.resolveSibling("MEMORY.md.bak")));
            assertNotNull(compactor.state(root));
            assertNull(compactor.state(config.resolveWorkingDir()));
            assertTrue(store.readProject(config.resolveWorkingDir()).isBlank());
        }
    }

    @Nested
    @FusionSupport(modules = CompactRewritesAndBacksUpModule.class)
    class CompactRewritesAndBacksUp {
        @TempDir
        static Path home;

        @Test
        void compactRewritesAndBacksUp(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            Files.createDirectories(
                    MemoryStore.projectFile(config.resolveWorkingDir()).getParent());
            final var original = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                    + "- the agent prefers tabs for indentation\n"
                    + "- the agent prefers tabs for indentation (reformulated)\n"
                    + "- in /tmp/bench one-off observation that is stale now\n";
            Files.writeString(MemoryStore.projectFile(config.resolveWorkingDir()), original);

            final var compactor = new MemoryCompactor(new FakeChatClient(), config, store, null, null);
            final var result = compactor.compactNow(config.resolveWorkingDir());
            assertTrue(result.startsWith("memory compacted"), result);

            final var kept = Files.readString(MemoryStore.projectFile(config.resolveWorkingDir()));
            assertTrue(kept.contains("the agent prefers tabs"), kept);
            assertTrue(
                    Files.exists(
                            MemoryStore.projectFile(config.resolveWorkingDir()).resolveSibling("MEMORY.md.bak")),
                    "backup must exist");
            final var baked = Files.readString(
                    MemoryStore.projectFile(config.resolveWorkingDir()).resolveSibling("MEMORY.md.bak"));
            assertEquals(original, baked, "backup must hold the pre-rewrite content");
            assertNotNull(compactor.state(config.resolveWorkingDir()), "compaction state must be recorded");
        }
    }

    @Nested
    @FusionSupport(modules = CompactDoesNotRunBelowThresholdModule.class)
    class CompactDoesNotRunBelowThreshold {
        @TempDir
        static Path home;

        @Test
        void compactDoesNotRunBelowThreshold(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            Files.createDirectories(
                    MemoryStore.projectFile(config.resolveWorkingDir()).getParent());
            Files.writeString(
                    MemoryStore.projectFile(config.resolveWorkingDir()),
                    "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n- small\n");

            final var compactor = new MemoryCompactor(new FakeChatClient(), config, store, null, null);
            assertNull(
                    compactor.maybeCompact(ChatSettings.EMPTY, config.resolveWorkingDir()),
                    "below threshold nothing must run");
            assertNull(compactor.state(config.resolveWorkingDir()), "no state must be recorded");
        }
    }

    @Nested
    @FusionSupport(modules = RewriteFailureEmitsAWarningNoticeModule.class)
    class RewriteFailureEmitsAWarningNotice {
        @TempDir
        static Path home;

        @Test
        void rewriteFailureEmitsAWarningNotice(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            Files.createDirectories(
                    MemoryStore.projectFile(config.resolveWorkingDir()).getParent());
            final var original = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                    + "- the agent prefers tabs for indentation\n"
                    + "- another durable fact about the project that pushes the size over the threshold\n";
            Files.writeString(MemoryStore.projectFile(config.resolveWorkingDir()), original);
            final List<AgentEvent> events = new ArrayList<>();
            final var sessionId = "s-test";
            final var compactor = new MemoryCompactor(
                    new FakeChatClient(true),
                    config,
                    store,
                    null,
                    null,
                    new Emitter() {
                        @Override
                        public <T> void emit(final T event) {
                            if (event instanceof AgentEvent agentEvent) {
                                events.add(agentEvent);
                            }
                        }
                    },
                    sessionId);
            final var result = compactor.compactNow(config.resolveWorkingDir());

            assertTrue(
                    result.contains("left untouched"),
                    "the rewrite failure must surface as a no-output result: " + result);
            assertEquals(1, events.size(), "exactly one warning notice expected");
            assertInstanceOf(AgentEvent.Notice.class, events.get(0), "a Notice event expected");
            final var notice = (AgentEvent.Notice) events.get(0);
            assertEquals(AgentEvent.Notice.Level.WARN, notice.level());
            assertEquals(sessionId, notice.sessionId());
            assertTrue(
                    notice.message().contains("memory compaction failed"),
                    "the notice must carry the failure context: " + notice.message());
            assertTrue(
                    notice.message().contains("json was malformed"),
                    "the notice must keep the technical detail: " + notice.message());
            assertTrue(
                    notice.message().contains("/memory compact"),
                    "the notice must include the recovery hint: " + notice.message());
            assertNotNull(notice.cause(), "the notice must carry the failing IOException for the Alt+E stack");
            assertTrue(
                    notice.cause().getMessage().contains("json was malformed"),
                    "the cause must be the thrown IOException: " + notice.cause());
        }
    }

    @Nested
    @FusionSupport(modules = CompactDiscardsReductionBelowTwentyPercentModule.class)
    class CompactDiscardsReductionBelowTwentyPercent {
        @TempDir
        static Path home;

        @Test
        void compactDiscardsReductionBelowTwentyPercent(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var config = configuration;
            final var store = new MemoryStore(config);
            Files.createDirectories(
                    MemoryStore.projectFile(config.resolveWorkingDir()).getParent());
            // a file whose rewrite (same content) would not shrink by 20%
            final var original = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                    + "- fact number one about the project\n"
                    + "- fact number two about the project\n";
            Files.writeString(MemoryStore.projectFile(config.resolveWorkingDir()), original);

            final var compactor = new MemoryCompactor(new FakeChatClient(), config, store, null, null);
            final var result = compactor.compactNow(config.resolveWorkingDir());
            assertTrue(result.contains("kept the file"), result);
            assertEquals(
                    original,
                    Files.readString(MemoryStore.projectFile(config.resolveWorkingDir())),
                    "the file must be untouched");
        }
    }

    private static Map<String, String> configSettings(final Path home) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", home.toString());
        map.put("yca.workingDir", home.toString());
        map.put("yca.projectDir", home.toString());
        map.put("yca.model.compact.name", "compact-model");
        return map;
    }

    /** Returns a fixed shortened memory, as a compact model would. */
    private static final class FakeChatClient extends ChatClient {
        private final boolean fail;

        private FakeChatClient() {
            this(false);
        }

        private FakeChatClient(final boolean fail) {
            this.fail = fail;
        }

        @Override
        public ChatResult chat(final ChatCompletionRequest request) throws IOException {
            if (fail) {
                throw new IOException(
                        "LLM request failed (400): {\"error\":{\"type\":\"invalid_request_error\",\"message\":\"json"
                                + " was malformed\"}}");
            }
            return new ChatResult(
                    Message.assistant(
                            "- the agent prefers tabs for indentation\n"
                                    + "- in /tmp/bench one-off observation that is stale now\n",
                            null),
                    new Usage(0, 0, 0));
        }
    }

    public static class RootAwareCompactionKeepsBackupsAndStateWithTheirProjectModule extends ConfigurationTestModule {
        public RootAwareCompactionKeepsBackupsAndStateWithTheirProjectModule() {
            super(configSettings(RootAwareCompactionKeepsBackupsAndStateWithTheirProject.home));
        }
    }

    public static class AutomaticCompactionChecksTheSessionRootInsteadOfTheConfiguredFileModule
            extends ConfigurationTestModule {
        public AutomaticCompactionChecksTheSessionRootInsteadOfTheConfiguredFileModule() {
            super(configSettings(AutomaticCompactionChecksTheSessionRootInsteadOfTheConfiguredFile.home));
        }
    }

    public static class CompactRewritesAndBacksUpModule extends ConfigurationTestModule {
        public CompactRewritesAndBacksUpModule() {
            super(configSettings(CompactRewritesAndBacksUp.home));
        }
    }

    public static class CompactDoesNotRunBelowThresholdModule extends ConfigurationTestModule {
        public CompactDoesNotRunBelowThresholdModule() {
            super(configSettings(CompactDoesNotRunBelowThreshold.home));
        }
    }

    public static class RewriteFailureEmitsAWarningNoticeModule extends ConfigurationTestModule {
        public RewriteFailureEmitsAWarningNoticeModule() {
            super(configSettings(RewriteFailureEmitsAWarningNotice.home));
        }
    }

    public static class CompactDiscardsReductionBelowTwentyPercentModule extends ConfigurationTestModule {
        public CompactDiscardsReductionBelowTwentyPercentModule() {
            super(configSettings(CompactDiscardsReductionBelowTwentyPercent.home));
        }
    }
}
