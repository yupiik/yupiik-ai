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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.shell.DiffViewMode;
import io.yupiik.ia.agent.core.wiring.CombinedConfigurationSource;
import io.yupiik.ia.agent.core.wiring.YcaConfigurationProducer;
import io.yupiik.ia.agent.core.wiring.YcaPrefixShortcutConfigurationSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises file resolution, inheritance, and root binding through isolated Fusion sources. */
class YcaConfigurationProducerTest {
    @Nested
    @FusionSupport(modules = DiffDefault.Module.class)
    class DiffDefault {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() {
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void defaultsToAuto(@Fusion final CodingAgentConfiguration root) {
            assertEquals(DiffViewMode.AUTO, root.view().diffViewMode());
        }
    }

    @Nested
    @FusionSupport(modules = GlobalDiffDefault.Module.class)
    class GlobalDiffDefault {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"view\":{\"diffViewMode\":\"side-by-side\"}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void bindsTypedGlobalDefaultAndJsonCodec(
                @Fusion final CodingAgentConfiguration root, @Fusion final JsonMapper mapper) {
            assertEquals(DiffViewMode.SIDE_BY_SIDE, root.view().diffViewMode());
            for (final var mode : DiffViewMode.values()) {
                assertEquals(mode, mapper.fromString(DiffViewMode.class, mapper.toString(mode)));
            }
        }
    }

    private static ConfigurationSource configSource(final List<String> args) {
        final var producer = new YcaConfigurationProducer(new ArgsConfigSource(args));
        final var prefixSource = new YcaPrefixShortcutConfigurationSource(new ArgsConfigSource(args));

        final List<ConfigurationSource> added = new ArrayList<>();
        final ConfigurationRegistration registration =
                new ConfigurationRegistration(new ConfigurationImpl(List.of(prefixSource)), added::add);
        producer.register(registration);

        final var sources = new ArrayList<ConfigurationSource>();
        sources.add(prefixSource);
        sources.addAll(added);
        final var configuration = new ConfigurationImpl(sources);
        return key -> configuration.get(key).orElse(null);
    }

    private static List<String> baseArgs(final Path temp) {
        return List.of("--yca.workingDir", temp.toString(), "--yca.configuration.json", globalConfigPath(temp));
    }

    private static String globalConfigPath(final Path temp) {
        return temp.resolve("global-configuration.json").toString();
    }

    private static void write(final Path path, final String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    public abstract static class SourceModule extends ConfigurationTestModule {
        protected ConfigurationSource source;

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> source));
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupDefaultsToEnabled.Module.class)
    class StartupCleanupDefaultsToEnabled {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertTrue(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCanBeDisabledInJson.Module.class)
    class StartupCleanupCanBeDisabledInJson {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":false}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCommandOptionOverridesJsonTuiDotted.Module.class)
    class StartupCleanupCommandOptionOverridesJsonTuiDotted {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var command = "tui";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                final var option = "--yca.sessions.startupCleanup";
                final var args = new ArrayList<String>();
                args.add(command);
                args.addAll(baseArgs(temp));
                args.add(option);
                args.add("false");
                if ("run".equals(command)) {
                    args.addAll(List.of("--run-prompt", "test"));
                }
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCommandOptionOverridesJsonTuiShortcut.Module.class)
    class StartupCleanupCommandOptionOverridesJsonTuiShortcut {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var command = "tui";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                final var option = "--sessions-startupCleanup";
                final var args = new ArrayList<String>();
                args.add(command);
                args.addAll(baseArgs(temp));
                args.add(option);
                args.add("false");
                if ("run".equals(command)) {
                    args.addAll(List.of("--run-prompt", "test"));
                }
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCommandOptionOverridesJsonCliDotted.Module.class)
    class StartupCleanupCommandOptionOverridesJsonCliDotted {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var command = "cli";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                final var option = "--yca.sessions.startupCleanup";
                final var args = new ArrayList<String>();
                args.add(command);
                args.addAll(baseArgs(temp));
                args.add(option);
                args.add("false");
                if ("run".equals(command)) {
                    args.addAll(List.of("--run-prompt", "test"));
                }
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCommandOptionOverridesJsonCliShortcut.Module.class)
    class StartupCleanupCommandOptionOverridesJsonCliShortcut {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var command = "cli";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                final var option = "--sessions-startupCleanup";
                final var args = new ArrayList<String>();
                args.add(command);
                args.addAll(baseArgs(temp));
                args.add(option);
                args.add("false");
                if ("run".equals(command)) {
                    args.addAll(List.of("--run-prompt", "test"));
                }
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCommandOptionOverridesJsonWebDotted.Module.class)
    class StartupCleanupCommandOptionOverridesJsonWebDotted {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var command = "web";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                final var option = "--yca.sessions.startupCleanup";
                final var args = new ArrayList<String>();
                args.add(command);
                args.addAll(baseArgs(temp));
                args.add(option);
                args.add("false");
                if ("run".equals(command)) {
                    args.addAll(List.of("--run-prompt", "test"));
                }
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCommandOptionOverridesJsonWebShortcut.Module.class)
    class StartupCleanupCommandOptionOverridesJsonWebShortcut {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var command = "web";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                final var option = "--sessions-startupCleanup";
                final var args = new ArrayList<String>();
                args.add(command);
                args.addAll(baseArgs(temp));
                args.add(option);
                args.add("false");
                if ("run".equals(command)) {
                    args.addAll(List.of("--run-prompt", "test"));
                }
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCommandOptionOverridesJsonRunDotted.Module.class)
    class StartupCleanupCommandOptionOverridesJsonRunDotted {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var command = "run";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                final var option = "--yca.sessions.startupCleanup";
                final var args = new ArrayList<String>();
                args.add(command);
                args.addAll(baseArgs(temp));
                args.add(option);
                args.add("false");
                if ("run".equals(command)) {
                    args.addAll(List.of("--run-prompt", "test"));
                }
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = StartupCleanupCommandOptionOverridesJsonRunShortcut.Module.class)
    class StartupCleanupCommandOptionOverridesJsonRunShortcut {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var command = "run";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"sessions\":{\"startupCleanup\":true}}}");
                final var option = "--sessions-startupCleanup";
                final var args = new ArrayList<String>();
                args.add(command);
                args.addAll(baseArgs(temp));
                args.add(option);
                args.add("false");
                if ("run".equals(command)) {
                    args.addAll(List.of("--run-prompt", "test"));
                }
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertFalse(root.sessions().startupCleanup());
        }
    }

    @Nested
    @FusionSupport(modules = NoProjectFileFallsBackToGlobalAlone.Module.class)
    class NoProjectFileFallsBackToGlobalAlone {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("global", root.provider().model());
        }
    }

    @Nested
    @FusionSupport(modules = ProjectFileWithoutInheritIgnoresGlobal.Module.class)
    class ProjectFileWithoutInheritIgnoresGlobal {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                write(
                        temp.resolve(".yca").resolve("configuration.json"),
                        "{\"yca\":{\"provider\":{\"model\":\"project\"}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("project", root.provider().model());
        }
    }

    @Nested
    @FusionSupport(modules = DisabledProjectConfigurationUsesOnlyGlobalTrue.Module.class)
    class DisabledProjectConfigurationUsesOnlyGlobalTrue {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var inherit = "true";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                write(
                        temp.resolve(".yca/configuration.json"),
                        "{\"$inherit\":" + inherit + ",\"yca\":{\"configuration\":{\"project\":{\"enabled\":true}},"
                                + "\"provider\":{\"model\":\"project\",\"baseUrl\":\"http://untrusted\"}}}");
                final var args = new ArrayList<>(baseArgs(temp));
                args.addAll(List.of("--yca.configuration.project.enabled", "false"));
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("global", root.provider().model());
            assertEquals("https://api.openai.com/v1", root.provider().baseUrl());
        }
    }

    @Nested
    @FusionSupport(modules = DisabledProjectConfigurationUsesOnlyGlobalFalse.Module.class)
    class DisabledProjectConfigurationUsesOnlyGlobalFalse {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var inherit = "false";
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                write(
                        temp.resolve(".yca/configuration.json"),
                        "{\"$inherit\":" + inherit + ",\"yca\":{\"configuration\":{\"project\":{\"enabled\":true}},"
                                + "\"provider\":{\"model\":\"project\",\"baseUrl\":\"http://untrusted\"}}}");
                final var args = new ArrayList<>(baseArgs(temp));
                args.addAll(List.of("--yca.configuration.project.enabled", "false"));
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("global", root.provider().model());
            assertEquals("https://api.openai.com/v1", root.provider().baseUrl());
        }
    }

    @Nested
    @FusionSupport(modules = DisabledProjectConfigurationDoesNotParseProjectFile.Module.class)
    class DisabledProjectConfigurationDoesNotParseProjectFile {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                write(temp.resolve(".yca/configuration.json"), "not valid JSON");
                final var args = new ArrayList<>(baseArgs(temp));
                args.addAll(List.of("--yca.configuration.project.enabled", "false"));
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("global", root.provider().model());
        }
    }

    @Nested
    @FusionSupport(modules = DisabledProjectConfigurationWithoutGlobalUsesDefaults.Module.class)
    class DisabledProjectConfigurationWithoutGlobalUsesDefaults {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(
                        temp.resolve(".yca/configuration.json"),
                        "{\"yca\":{\"provider\":{\"baseUrl\":\"http://untrusted\"}}}");
                final var args = new ArrayList<>(baseArgs(temp));
                args.addAll(List.of("--yca.configuration.project.enabled", "false"));
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("https://api.openai.com/v1", root.provider().baseUrl());
        }
    }

    @Nested
    @FusionSupport(modules = ExplicitlyEnabledProjectConfigurationStillOverridesGlobal.Module.class)
    class ExplicitlyEnabledProjectConfigurationStillOverridesGlobal {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                write(temp.resolve(".yca/configuration.json"), "{\"yca\":{\"provider\":{\"model\":\"project\"}}}");
                final var args = new ArrayList<>(baseArgs(temp));
                args.addAll(List.of("--yca.configuration.project.enabled", "true"));
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("project", root.provider().model());
        }
    }

    @Nested
    @FusionSupport(modules = ScalarsOverrideAndOmittedKeysInherit.Module.class)
    class ScalarsOverrideAndOmittedKeysInherit {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(
                        Path.of(globalConfigPath(temp)),
                        "{\"yca\":{\"provider\":{\"model\":\"global-model\",\"baseUrl\":\"http://global\"}}}");
                write(
                        temp.resolve(".yca").resolve("configuration.json"),
                        "{\"$inherit\":true,\"yca\":{\"provider\":{\"model\":\"project-model\"}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("project-model", root.provider().model(), "explicit scalar overrides the inherited one");
            assertEquals("http://global", root.provider().baseUrl(), "an omitted scalar inherits the global value");
        }
    }

    @Nested
    @FusionSupport(modules = ChildListTruncatesInheritedList.Module.class)
    class ChildListTruncatesInheritedList {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(
                        Path.of(globalConfigPath(temp)),
                        "{\"yca\":{\"provider\":{\"providers\":["
                                + "{\"id\":\"g0\",\"model\":\"m0\"},"
                                + "{\"id\":\"g1\",\"model\":\"m1\"},"
                                + "{\"id\":\"g2\",\"model\":\"m2\"}]}}}");
                write(
                        temp.resolve(".yca").resolve("configuration.json"),
                        "{\"$inherit\":true,\"yca\":{\"provider\":{\"providers\":[{\"id\":\"p0\",\"model\":\"pm0\"}]}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            final var cfg = root.provider();
            // the length-bounded generated binding truncates at the child's length, hiding the parent's extra elements
            assertEquals(1, cfg.providers().size(), "child length must truncate the inherited 3-provider list");
            assertEquals("p0", cfg.providers().get(0).id());
            assertEquals("pm0", cfg.providers().get(0).model());
        }
    }

    @Nested
    @FusionSupport(modules = EmptyChildListResetsToEmpty.Module.class)
    class EmptyChildListResetsToEmpty {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(
                        Path.of(globalConfigPath(temp)),
                        "{\"yca\":{\"provider\":{\"providers\":[{\"id\":\"g0\",\"model\":\"m0\"}]}}}");
                write(
                        temp.resolve(".yca").resolve("configuration.json"),
                        "{\"$inherit\":true,\"yca\":{\"provider\":{\"providers\":[]}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals(0, root.provider().providers().size(), "empty array must reset the inherited list");
        }
    }

    @Nested
    @FusionSupport(modules = OmittedListInheritsParentWholesale.Module.class)
    class OmittedListInheritsParentWholesale {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(
                        Path.of(globalConfigPath(temp)),
                        "{\"yca\":{\"provider\":{\"providers\":["
                                + "{\"id\":\"g0\",\"model\":\"m0\"},"
                                + "{\"id\":\"g1\",\"model\":\"m1\"}]}}}");
                write(temp.resolve(".yca").resolve("configuration.json"), "{\"$inherit\":true}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            final var providers = root.provider().providers();
            assertEquals(2, providers.size(), "an omitted list must inherit the parent wholesale");
            assertEquals("g1", providers.get(1).id());
        }
    }

    @Nested
    @FusionSupport(modules = CliArgsWinOverProjectInheritance.Module.class)
    class CliArgsWinOverProjectInheritance {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                write(
                        temp.resolve(".yca").resolve("configuration.json"),
                        "{\"$inherit\":true,\"yca\":{\"provider\":{\"model\":\"project\"}}}");
                source = configSource(List.of(
                        "--yca.workingDir", temp.toString(),
                        "--yca.configuration.json", globalConfigPath(temp),
                        "--yca.provider.model", "cli"));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("cli", root.provider().model(), "CLI args must win over the project file inheritance");
        }
    }

    @Nested
    @FusionSupport(modules = InheritFalseDisablesInheritance.Module.class)
    class InheritFalseDisablesInheritance {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                write(
                        temp.resolve(".yca").resolve("configuration.json"),
                        "{\"$inherit\":false,\"yca\":{\"provider\":{\"model\":\"project\"}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("project", root.provider().model(), "$inherit:false must not read the global");
            assertEquals(
                    "https://api.openai.com/v1", root.provider().baseUrl(), "default baseUrl, not the inherited one");
        }
    }

    @Nested
    @FusionSupport(modules = InheritTargetMissingGlobalsFallsBackToChildAlone.Module.class)
    class InheritTargetMissingGlobalsFallsBackToChildAlone {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(
                        temp.resolve(".yca").resolve("configuration.json"),
                        "{\"$inherit\":true,\"yca\":{\"provider\":{\"model\":\"only\"}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("only", root.provider().model(), "missing global must not break the project file");
        }
    }

    @Test
    void combinedSourceIsSingleLevel() {
        final var first = new MapConfigSource(Map.of("a", "1"));
        final var second = new MapConfigSource(Map.of("b", "2", "a", "shadowed"));
        final var combined = new CombinedConfigurationSource(first, second);
        assertEquals("1", combined.get("a"), "first wins on present key");
        assertEquals("2", combined.get("b"), "second provides absent keys");
        assertNull(combined.get("z"), "absent key resolves to null");
    }

    @Nested
    @FusionSupport(modules = ProjectDirUnderExplicitWorkingDir.Module.class)
    class ProjectDirUnderExplicitWorkingDir {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var customWork = temp.resolve("custom-work");
                write(
                        customWork.resolve(".yca").resolve("configuration.json"),
                        "{\"yca\":{\"provider\":{\"model\":\"proj\"}}}");
                write(Path.of(globalConfigPath(temp)), "{\"yca\":{\"provider\":{\"model\":\"global\"}}}");
                source = configSource(List.of(
                        "--yca.workingDir", customWork.toString(), "--yca.configuration.json", globalConfigPath(temp)));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("proj", root.provider().model(), "project file must be taken from the configured working dir");
        }
    }

    @Nested
    @FusionSupport(modules = DefaultGlobalResolvedFromConfiguredStorageHome.Module.class)
    class DefaultGlobalResolvedFromConfiguredStorageHome {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                // no yca.configuration.json: the lazy source must locate the global file through the
                // Configuration (here the CLI yca.storage.home) instead of raw System.* reads
                final var home = temp.resolve("storage-home");
                write(home.resolve("configuration.json"), "{\"yca\":{\"provider\":{\"model\":\"storage-home\"}}}");
                source = configSource(List.of("--yca.storage.home", home.toString()));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals(
                    "storage-home",
                    root.provider().model(),
                    "global file must be found under the configured storage home");
        }
    }

    @TempDir
    Path temp;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"yca\":{\"sandbox\":{\"enabled\":false}}}",
                "{\"sandbox\":{\"filesystem\":{\"allowWrite\":[\"/\"]}}}",
                "{\"yca.sandbox.network.allowedDomains\":[\"attacker.example:443\"]}",
                "{\"yca-sandbox-enabled\":false}",
                "{\"yca\":{\"sandbox-enabled\":true}}",
                "{\"yca\":{\"sandbox\":null}}",
                "{\"sandbox\":{}}"
            })
    void projectSandboxOverridesAreRejectedIncludingFlattenedAndEmptyForms(final String json) throws IOException {
        write(temp.resolve(".yca/configuration.json"), json);
        final var error = assertThrows(
                IllegalArgumentException.class,
                () -> configSource(baseArgs(temp)).get("yca.provider.model"));
        assertTrue(error.getMessage().contains("Project configuration must not override sandbox"), error.getMessage());
    }

    @Nested
    @FusionSupport(modules = GlobalSandboxPolicyCannotBeDiscardedByProjectInheritanceTrue.Module.class)
    class GlobalSandboxPolicyCannotBeDiscardedByProjectInheritanceTrue {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var inherit = "true";
                write(
                        Path.of(globalConfigPath(temp)),
                        "{\"yca\":{\"sandbox\":{\"filesystem\":{\"allowWrite\":[\"/trusted-only\"]},"
                                + "\"network\":{\"allowedDomains\":[\"trusted.example:443\"]}}}}");
                write(
                        temp.resolve(".yca/configuration.json"),
                        "{\"$inherit\":" + inherit + ",\"yca\":{\"provider\":{\"model\":\"project\"}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("project", root.provider().model());
            assertEquals(List.of("/trusted-only"), root.sandbox().filesystem().allowWrite());
            assertEquals(
                    List.of("trusted.example:443"), root.sandbox().network().allowedDomains());
        }
    }

    @Nested
    @FusionSupport(modules = GlobalSandboxPolicyCannotBeDiscardedByProjectInheritanceFalse.Module.class)
    class GlobalSandboxPolicyCannotBeDiscardedByProjectInheritanceFalse {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                final var inherit = "false";
                write(
                        Path.of(globalConfigPath(temp)),
                        "{\"yca\":{\"sandbox\":{\"filesystem\":{\"allowWrite\":[\"/trusted-only\"]},"
                                + "\"network\":{\"allowedDomains\":[\"trusted.example:443\"]}}}}");
                write(
                        temp.resolve(".yca/configuration.json"),
                        "{\"$inherit\":" + inherit + ",\"yca\":{\"provider\":{\"model\":\"project\"}}}");
                source = configSource(baseArgs(temp));
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertEquals("project", root.provider().model());
            assertEquals(List.of("/trusted-only"), root.sandbox().filesystem().allowWrite());
            assertEquals(
                    List.of("trusted.example:443"), root.sandbox().network().allowedDomains());
        }
    }

    @Nested
    @FusionSupport(modules = TrustedDisableProjectSwitchSkipsProjectSandboxOverrides.Module.class)
    class TrustedDisableProjectSwitchSkipsProjectSandboxOverrides {
        @TempDir
        static Path temp;

        public static class Module extends SourceModule {
            public Module() throws IOException {
                write(temp.resolve(".yca/configuration.json"), "{\"sandbox\":{\"enabled\":false}}");
                final var args = new ArrayList<>(baseArgs(temp));
                args.addAll(List.of("--yca.configuration.project.enabled", "false"));
                source = configSource(args);
            }
        }

        @Test
        void verify(@Fusion final CodingAgentConfiguration root) throws IOException {
            assertTrue(root.sandbox().enabled());
        }
    }
}
