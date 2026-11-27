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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.builtin.MemoryWriteTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryStoreTest {

    @Nested
    @FusionSupport(modules = WritesAreIsolatedByExplicitRootAndGlobalStorageIsSharedModule.class)
    class WritesAreIsolatedByExplicitRootAndGlobalStorageIsShared {
        @TempDir
        static Path home;

        @Test
        void writesAreIsolatedByExplicitRootAndGlobalStorageIsShared(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var configuredRoot = home.resolve("configured-workdir");
            final var storage = home.resolve("shared-storage");
            final var store = new MemoryStore(configuration);
            final var alpha = home.resolve("alpha");
            final var beta = home.resolve("beta");
            assertEquals(configuredRoot.resolve(".yca/memory/MEMORY.md"), MemoryStore.projectFile(configuredRoot));
            assertEquals(storage.resolve("memory/MEMORY.md"), store.globalFile());

            store.append(false, List.of("alpha architecture"), alpha);
            store.appendText(false, "beta deployment\n\nbeta operations", beta);
            assertThrows(NullPointerException.class, () -> store.appendText(false, "rejected fact", null));
            final var alphaOriginal = store.readProject(alpha);
            final var betaOriginal = store.readProject(beta);
            store.append(true, List.of("shared user preference"), alpha);
            store.appendText(true, "another global preference", beta);
            assertEquals(alphaOriginal, store.readProject(alpha));
            assertEquals(betaOriginal, store.readProject(beta));
            assertFalse(alphaOriginal.contains("beta"));
            assertFalse(betaOriginal.contains("alpha"));
            assertFalse(Files.exists(MemoryStore.projectFile(configuredRoot)));
            assertTrue(store.readGlobal().contains("shared user preference"));
            assertTrue(store.readGlobal().contains("another global preference"));

            store.replace(false, List.of("alpha rewritten"), alpha);
            assertEquals(
                    alphaOriginal,
                    Files.readString(MemoryStore.projectFile(alpha).resolveSibling("MEMORY.md.bak")));
            assertEquals(betaOriginal, store.readProject(beta));
            assertFalse(store.readProject(alpha).contains("architecture"));
            assertThrows(NullPointerException.class, () -> store.replace(false, List.of("rejected replacement"), null));
            store.replace(true, List.of("global replacement"), beta);
            assertTrue(store.readGlobal().contains("global replacement"));
            assertEquals(betaOriginal, store.readProject(beta));
            assertTrue(store.promptSection(null, 2000, alpha).contains("alpha rewritten"));
            assertFalse(store.promptSection(null, 2000, alpha).contains("beta deployment"));
        }
    }

    @Nested
    @FusionSupport(modules = PromptSectionFiltersHeaderAndDeduplicatesModule.class)
    class PromptSectionFiltersHeaderAndDeduplicates {
        @TempDir
        static Path home;

        @Test
        void promptSectionFiltersHeaderAndDeduplicates(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var store = new MemoryStore(configuration);
            Files.createDirectories(store.globalFile().getParent());
            // a memory file as written by the store itself: header + one duplicated bullet
            Files.writeString(
                    store.globalFile(),
                    "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                            + "- fact one\n"
                            + "- fact one\n"
                            + "- fact two\n");
            final var section = store.promptSection(null, 2000, home);
            assertFalse(section.contains("Durable facts"), "the file header must not leak into the prompt: " + section);
            assertEquals(1, count(section, "- fact one"), "duplicated bullets must be deduplicated: " + section);
            assertEquals(1, count(section, "- fact two"), "distinct bullets must be kept: " + section);
        }
    }

    @Nested
    @FusionSupport(modules = VersionIsStableThenBumpsOnAppendModule.class)
    class VersionIsStableThenBumpsOnAppend {
        @TempDir
        static Path home;

        @Test
        void versionIsStableThenBumpsOnAppend(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var store = new MemoryStore(configuration);
            final var v1 = store.version(home);
            assertEquals(v1, store.version(home), "version must be stable while memory is untouched");
            store.append(true, List.of("a new durable fact"), home);
            final var v2 = store.version(home);
            assertTrue(v2 != v1, "a write must bump the content version");
            assertEquals(v2, store.version(home), "the new version must be stable too");
        }
    }

    @Nested
    @FusionSupport(modules = AppendDeduplicatesIdenticalFactsModule.class)
    class AppendDeduplicatesIdenticalFacts {
        @TempDir
        static Path home;

        @Test
        void appendDeduplicatesIdenticalFacts(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var store = new MemoryStore(configuration);
            final var first = store.append(true, List.of("the same fact"), home);
            final var second = store.append(true, List.of("the same fact"), home);
            assertEquals(1, first.size());
            assertTrue(second.isEmpty(), "a repeated append of the same fact must be a no-op");
            assertEquals(1, count(store.promptSection(null, 2000, home), "- the same fact"));
        }
    }

    @Nested
    @FusionSupport(modules = PromptSectionHonorsExplicitBudgetModule.class)
    class PromptSectionHonorsExplicitBudget {
        @TempDir
        static Path home;

        @Test
        void promptSectionHonorsExplicitBudget(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var store = new MemoryStore(configuration);
            Files.createDirectories(store.globalFile().getParent());
            Files.writeString(
                    store.globalFile(),
                    "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                            + "- alpha beta gamma delta epsilon zeta\n"
                            + "- a b c d e f g h i j k l\n");
            assertTrue(store.promptSection(null, 0, home).isEmpty(), "budget 0 must disable the section");
            assertTrue(store.promptSection(null, 5, home).isEmpty(), "tiny budget must disable the section");
            assertTrue(store.promptSection(null, 120, home).isEmpty(), "budget below the 500 floor is auto-off");
            final var small = store.promptSection(null, 700, home);
            // the global (only 2 bullets, ~65 chars) fits in the budget: the section keeps content
            assertTrue(small.contains("- alpha"), "budgeted section must keep content: " + small);
            assertTrue(small.length() <= 900, "budgeted section must stay small: " + small.length());
        }
    }

    @Nested
    @FusionSupport(modules = AppendReformulationReplacesTheOldBulletModule.class)
    class AppendReformulationReplacesTheOldBullet {
        @TempDir
        static Path home;

        @Test
        void appendReformulationReplacesTheOldBullet(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var store = new MemoryStore(configuration);
            store.append(
                    true,
                    List.of("FileSessionStore and InMemorySessionStore must round-trip Message.others() on load and"
                            + " replace"),
                    home);
            store.append(
                    true,
                    List.of("FileSessionStore and InMemorySessionStore must round-trip Message.others in load() and"
                            + " replace()"),
                    home);
            final var section = store.promptSection(null, 2000, home);
            assertEquals(
                    1,
                    count(section, "FileSessionStore and InMemorySessionStore must round-trip"),
                    "a reformulated near-duplicate must replace the old bullet: " + section);
            assertTrue(
                    section.contains("round-trip Message.others in load()"), "the newest wording must win: " + section);
        }
    }

    @Nested
    @FusionSupport(modules = AppendKeepsDistinctBulletsEvenWhenTokensOverlapModule.class)
    class AppendKeepsDistinctBulletsEvenWhenTokensOverlap {
        @TempDir
        static Path home;

        @Test
        void appendKeepsDistinctBulletsEvenWhenTokensOverlap(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var store = new MemoryStore(configuration);
            store.append(true, List.of("the agent prefers tabs for indentation"), home);
            store.append(true, List.of("the agent prefers spaces in documentation files"), home);
            final var section = store.promptSection(null, 2000, home);
            assertEquals(1, count(section, "the agent prefers tabs"), "distinct fact must be kept: " + section);
            assertEquals(1, count(section, "the agent prefers spaces"), "distinct fact must be kept: " + section);
        }
    }

    @Nested
    @FusionSupport(modules = WithMetaRoundTripsAndIsStrippedFromPromptModule.class)
    class WithMetaRoundTripsAndIsStrippedFromPrompt {
        @TempDir
        static Path home;

        @Test
        void withMetaRoundTripsAndIsStrippedFromPrompt(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var store = new MemoryStore(configuration);
            final var stored =
                    MemoryStore.withMeta(5, List.of("maven", "build"), "src/main/App.java", "always run mvn -o");
            assertTrue(stored.startsWith("- 5|"), "stored bullet carries the p5 envelope: " + stored);
            store.append(true, List.of(stored), home);
            final var section = store.promptSection(null, 2000, home);
            assertTrue(section.contains("- always run mvn -o"), "fact text must be injected: " + section);
            assertFalse(section.contains("5|"), "the envelope must never leak into the prompt: " + section);
            assertFalse(section.contains("maven|build"), "topic must not leak: " + section);
            // the stored bullet must keep its metadata on read
            assertEquals(5, MemoryStore.metaOf(stored).p(), "priority must survive the round-trip");
            assertEquals("src/main/App.java", MemoryStore.metaOf(stored).path(), "path anchor must survive");
            assertEquals(List.of("maven", "build"), MemoryStore.metaOf(stored).topic(), "topic must survive");
        }
    }

    @Nested
    @FusionSupport(modules = PlainHandWrittenLineIsCompatibleModule.class)
    class PlainHandWrittenLineIsCompatible {
        @TempDir
        static Path home;

        @Test
        void plainHandWrittenLineIsCompatible(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var store = new MemoryStore(configuration);
            Files.createDirectories(store.globalFile().getParent());
            final var original = "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                    + "- user prefers tabs\n"
                    + "- a hand-written line with | pipe characters in it\n";
            Files.writeString(store.globalFile(), original);
            // reading must not rewrite or touch a compat line
            final var section = store.promptSection(null, 2000, home);
            assertTrue(section.contains("- user prefers tabs"), "plain line must be injected: " + section);
            assertTrue(
                    section.contains("- a hand-written line with | pipe characters in it"),
                    "pipe-heavy prose is plain content, not an envelope: " + section);
            assertEquals(original, Files.readString(store.globalFile()), "a read must not rewrite compat lines");
            // the compat line is treated as p2 plain metadata
            assertEquals(2, MemoryStore.metaOf("- user prefers tabs").p(), "a plain line defaults to p2");
        }
    }

    @Nested
    @FusionSupport(modules = KeepSurvivesMaxLinesEvictionWhilePlainPeersDropModule.class)
    class KeepSurvivesMaxLinesEvictionWhilePlainPeersDrop {
        @TempDir
        static Path home;

        @Test
        void keepSurvivesMaxLinesEvictionWhilePlainPeersDrop(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var store = new MemoryStore(configuration);
            // two keep (p5) + two plain (p2) - over the 3-line cap
            store.append(
                    true,
                    List.of(
                            MemoryStore.withMeta(5, null, null, "never edit generated files"),
                            MemoryStore.withMeta(5, null, null, "always run mvn -o"),
                            "an ordinary observation one",
                            "an ordinary observation two"),
                    home);
            final var file = Files.readString(store.globalFile());
            assertTrue(file.contains("never edit generated files"), "keep bullets must survive eviction: " + file);
            assertTrue(file.contains("always run mvn -o"), "keep bullets must survive eviction: " + file);
            // only one plain bullet may remain (2 keep + 1 plain = 3 = maxLines)
            long plain = file.lines()
                    .filter(l -> l.startsWith("- "))
                    .filter(l -> MemoryStore.metaOf(l).p() == 2)
                    .count();
            assertTrue(plain <= 1, "plain low-priority bullets drop first: " + file);
        }
    }

    @Nested
    @FusionSupport(modules = KeepIsDemotedPastMaxKeepLinesModule.class)
    class KeepIsDemotedPastMaxKeepLines {
        @TempDir
        static Path home;

        @Test
        void keepIsDemotedPastMaxKeepLines(@Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var store = new MemoryStore(configuration);
            store.append(
                    true,
                    List.of(
                            MemoryStore.withMeta(5, null, null, "first directive"),
                            MemoryStore.withMeta(5, null, null, "second directive")),
                    home);
            final var file = Files.readString(store.globalFile());
            // with maxKeepLines=1 and two keep bullets, one keep is demoted to p4 (kept, not dropped)
            assertEquals(1, keepCount(file, 5), "exactly one keep bullet may remain: " + file);
            assertEquals(1, keepCount(file, 4), "the other keep must be demoted to p4: " + file);
            assertTrue(
                    file.contains("first directive") && file.contains("second directive"),
                    "neither directive is dropped: " + file);
        }
    }

    @Nested
    @FusionSupport(modules = PathAnchoredBulletIsBoostedForCurrentPathModule.class)
    class PathAnchoredBulletIsBoostedForCurrentPath {
        @TempDir
        static Path home;

        @Test
        void pathAnchoredBulletIsBoostedForCurrentPath(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var store = new MemoryStore(configuration);
            Files.createDirectories(MemoryStore.projectFile(home).getParent());
            Files.writeString(
                    MemoryStore.projectFile(home),
                    "# Memory\n\nDurable facts, decisions and preferences. One bullet per line.\n\n"
                            + MemoryStore.withMeta(
                                    2, null, "src/main/CartTotals.java", "rounding lives in CartTotals.total")
                            + "\n"
                            + MemoryStore.withMeta(2, null, null, "the sky is blue unrelated fact")
                            + "\n");
            // a prompt about rounding, with the current path present: the anchored bullet must surface
            final var section =
                    store.promptSection("refactor rounding", 1200, home, List.of("src/main/CartTotals.java"));
            assertTrue(
                    section.contains("CartTotals"),
                    "the path-anchored bullet must be retrieved for the current path: " + section);
        }
    }

    private static long keepCount(final String content, final int priority) {
        return content.lines()
                .filter(l -> l.startsWith("- "))
                .filter(l -> MemoryStore.metaOf(l).p() == priority)
                .count();
    }

    @Nested
    @FusionSupport(modules = MemoryWriteToolStoresAnExplicitKeepEnvelopeByDefaultModule.class)
    class MemoryWriteToolStoresAnExplicitKeepEnvelopeByDefault {
        @TempDir
        static Path home;

        @Test
        void memoryWriteToolStoresAnExplicitKeepEnvelopeByDefault(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var store = new MemoryStore(configuration);
            final var tool = new MemoryWriteTool(store);
            final var result = tool.execute(
                    Map.of("fact", "always use tabs", "scope", "global"),
                    ToolContext.empty(home, new DisabledSandbox(home)));
            assertTrue(result.output().contains("Remembered"), "the write must report success: " + result.output());
            final var file = Files.readString(store.globalFile());
            // memory_write defaults to an explicit keep directive (p5) with no path/topic, stamped today
            final var storedKeep = file.lines()
                    .filter(l -> MemoryStore.metaOf(l).p() == 5)
                    .findFirst()
                    .orElse(null);
            assertNotNull(storedKeep, "a memory_write fact must be stored as a keep (p5) directive: " + file);
            assertEquals("always use tabs", MemoryStore.textOf(storedKeep), "the fact text is preserved");
        }
    }

    @Nested
    @FusionSupport(modules = MemoryWriteToolHonorsKeepFalseAndPathTopicModule.class)
    class MemoryWriteToolHonorsKeepFalseAndPathTopic {
        @TempDir
        static Path home;

        @Test
        void memoryWriteToolHonorsKeepFalseAndPathTopic(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var store = new MemoryStore(configuration);
            final var tool = new MemoryWriteTool(store);
            tool.execute(
                    Map.of(
                            "fact", "the discount rule",
                            "scope", "global",
                            "keep", "false",
                            "path", "src/main/CartTotals.java",
                            "topic", "rounding discount"),
                    ToolContext.empty(home, new DisabledSandbox(home)));
            final var file = Files.readString(store.globalFile());
            final var line = file.lines()
                    .filter(l -> MemoryStore.textOf(l).equals("the discount rule"))
                    .findFirst()
                    .orElse(null);
            assertNotNull(line, "the fact must be stored: " + file);
            assertEquals(2, MemoryStore.metaOf(line).p(), "keep=false stores an ordinary p2 fact");
            assertEquals("src/main/CartTotals.java", MemoryStore.metaOf(line).path(), "path anchor must be stored");
            assertEquals(
                    List.of("rounding", "discount"), MemoryStore.metaOf(line).topic(), "topic must be stored");
        }
    }

    private static int count(final String haystack, final String needle) {
        var out = 0;
        var from = 0;
        while (true) {
            final var i = haystack.indexOf(needle, from);
            if (i < 0) {
                return out;
            }
            out++;
            from = i + needle.length();
        }
    }

    private static Map<String, String> configSettings(final Path storageHome, final Map<String, String> overrides) {
        final var map = new HashMap<String, String>();
        map.put("yca.storageHome", storageHome.toString());
        map.put("yca.workingDir", ".");
        map.putAll(overrides);
        return map;
    }

    public static class WritesAreIsolatedByExplicitRootAndGlobalStorageIsSharedModule extends ConfigurationTestModule {
        public WritesAreIsolatedByExplicitRootAndGlobalStorageIsSharedModule() {
            super(configSettings(
                    WritesAreIsolatedByExplicitRootAndGlobalStorageIsShared.home,
                    Map.of(
                            "yca.workingDir",
                                    WritesAreIsolatedByExplicitRootAndGlobalStorageIsShared.home
                                            .resolve("configured-workdir")
                                            .toString(),
                            "yca.projectDir",
                                    WritesAreIsolatedByExplicitRootAndGlobalStorageIsShared.home
                                            .resolve("different-project-config")
                                            .toString(),
                            "yca.storageHome",
                                    WritesAreIsolatedByExplicitRootAndGlobalStorageIsShared.home
                                            .resolve("shared-storage")
                                            .toString())));
        }
    }

    public static class PromptSectionFiltersHeaderAndDeduplicatesModule extends ConfigurationTestModule {
        public PromptSectionFiltersHeaderAndDeduplicatesModule() {
            super(configSettings(PromptSectionFiltersHeaderAndDeduplicates.home, Map.of()));
        }
    }

    public static class VersionIsStableThenBumpsOnAppendModule extends ConfigurationTestModule {
        public VersionIsStableThenBumpsOnAppendModule() {
            super(configSettings(VersionIsStableThenBumpsOnAppend.home, Map.of()));
        }
    }

    public static class AppendDeduplicatesIdenticalFactsModule extends ConfigurationTestModule {
        public AppendDeduplicatesIdenticalFactsModule() {
            super(configSettings(AppendDeduplicatesIdenticalFacts.home, Map.of()));
        }
    }

    public static class PromptSectionHonorsExplicitBudgetModule extends ConfigurationTestModule {
        public PromptSectionHonorsExplicitBudgetModule() {
            super(configSettings(PromptSectionHonorsExplicitBudget.home, Map.of("yca.memory.maxChars", "8000")));
        }
    }

    public static class AppendReformulationReplacesTheOldBulletModule extends ConfigurationTestModule {
        public AppendReformulationReplacesTheOldBulletModule() {
            super(configSettings(AppendReformulationReplacesTheOldBullet.home, Map.of()));
        }
    }

    public static class AppendKeepsDistinctBulletsEvenWhenTokensOverlapModule extends ConfigurationTestModule {
        public AppendKeepsDistinctBulletsEvenWhenTokensOverlapModule() {
            super(configSettings(AppendKeepsDistinctBulletsEvenWhenTokensOverlap.home, Map.of()));
        }
    }

    public static class WithMetaRoundTripsAndIsStrippedFromPromptModule extends ConfigurationTestModule {
        public WithMetaRoundTripsAndIsStrippedFromPromptModule() {
            super(configSettings(WithMetaRoundTripsAndIsStrippedFromPrompt.home, Map.of()));
        }
    }

    public static class PlainHandWrittenLineIsCompatibleModule extends ConfigurationTestModule {
        public PlainHandWrittenLineIsCompatibleModule() {
            super(configSettings(PlainHandWrittenLineIsCompatible.home, Map.of()));
        }
    }

    public static class KeepSurvivesMaxLinesEvictionWhilePlainPeersDropModule extends ConfigurationTestModule {
        public KeepSurvivesMaxLinesEvictionWhilePlainPeersDropModule() {
            super(configSettings(
                    KeepSurvivesMaxLinesEvictionWhilePlainPeersDrop.home,
                    Map.of("yca.memory.maxLines", "3", "yca.memory.maxKeepLines", "2")));
        }
    }

    public static class KeepIsDemotedPastMaxKeepLinesModule extends ConfigurationTestModule {
        public KeepIsDemotedPastMaxKeepLinesModule() {
            super(configSettings(
                    KeepIsDemotedPastMaxKeepLines.home,
                    Map.of("yca.memory.maxLines", "10", "yca.memory.maxKeepLines", "1")));
        }
    }

    public static class PathAnchoredBulletIsBoostedForCurrentPathModule extends ConfigurationTestModule {
        public PathAnchoredBulletIsBoostedForCurrentPathModule() {
            super(configSettings(PathAnchoredBulletIsBoostedForCurrentPath.home, Map.of()));
        }
    }

    public static class MemoryWriteToolStoresAnExplicitKeepEnvelopeByDefaultModule extends ConfigurationTestModule {
        public MemoryWriteToolStoresAnExplicitKeepEnvelopeByDefaultModule() {
            super(configSettings(MemoryWriteToolStoresAnExplicitKeepEnvelopeByDefault.home, Map.of()));
        }
    }

    public static class MemoryWriteToolHonorsKeepFalseAndPathTopicModule extends ConfigurationTestModule {
        public MemoryWriteToolHonorsKeepFalseAndPathTopicModule() {
            super(configSettings(MemoryWriteToolHonorsKeepFalseAndPathTopic.home, Map.of()));
        }
    }
}
