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
package io.yupiik.ia.agent.github.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Entry;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.GeneratedFile;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Kind;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Limits;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Maintenance;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Mode;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Preset;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DependencyCacheConfigurationTest {
    private static final Maintenance MAINTENANCE = new Maintenance(30, 10L * 1024 * 1024 * 1024);
    private static final Limits LIMITS = new Limits(100000, 16L * 1024 * 1024 * 1024, 64L * 1024 * 1024, 600, 2);

    @Test
    void commonExpandsToNarrowDeterministicEntries() {
        final var config = new DependencyCacheConfiguration(
                Mode.READ_WRITE,
                "/cache",
                Scope.REPOSITORY,
                "default",
                List.of(Preset.COMMON, Preset.MAVEN),
                List.of(),
                Map.of(),
                List.of(),
                300,
                MAINTENANCE,
                LIMITS);
        assertTrue(config.enabled());
        assertEquals(
                List.of(
                        "cargo-cache",
                        "cargo-index",
                        "go",
                        "gradle",
                        "maven",
                        "npm",
                        "nuget",
                        "pip-http",
                        "pip-http-v2"),
                config.resolvedEntries().stream().map(Entry::id).toList());
        assertEquals(
                List.of(
                        "caches/cargo/registry/cache",
                        "caches/cargo/registry/index",
                        "caches/go/pkg/mod/cache/download",
                        "caches/gradle/caches/modules-2",
                        "caches/maven/repository",
                        "caches/npm/_cacache",
                        "caches/nuget/packages",
                        "caches/pip/http",
                        "caches/pip/http-v2"),
                config.resolvedEntries().stream().map(Entry::directory).toList());
        assertEquals(
                List.of("-SNAPSHOT"),
                config.resolvedEntries().stream()
                        .filter(entry -> entry.id().equals("maven"))
                        .findFirst()
                        .orElseThrow()
                        .excludeDirectoryNameSuffixes());
        final var keys = new ArrayList<>(config.resolvedEnvironment().keySet());
        assertEquals(keys.stream().sorted().toList(), keys);
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.resolvedEnvironment().put("PIP_CACHE_DIR", "x"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.resolvedEntries().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> config.resolvedFiles().clear());
    }

    @Test
    void explicitOverridesWinAndMavenSnapshotsCannotBeEnabled() {
        final var entries = new ArrayList<>(List.of(new Entry("maven", Kind.MAVEN, "custom/maven", List.of(".tmp"))));
        final var config = new DependencyCacheConfiguration(
                Mode.RESTORE_ONLY,
                "/cache",
                Scope.REPOSITORY,
                "jdk25",
                List.of(Preset.MAVEN),
                entries,
                Map.of("MAVEN_OPTS", "-Dmaven.repo.local=${cache.maven} -Xmx1g"),
                List.of(new GeneratedFile(".m2/settings.xml", "fresh ${cache.maven}")),
                300,
                MAINTENANCE,
                LIMITS);
        entries.clear();
        assertEquals("custom/maven", config.resolvedEntries().getFirst().directory());
        assertEquals(
                List.of("-SNAPSHOT", ".tmp"),
                config.resolvedEntries().getFirst().excludeDirectoryNameSuffixes());
        assertEquals(
                "-Dmaven.repo.local=${cache.maven} -Xmx1g",
                config.resolvedEnvironment().get("MAVEN_OPTS"));
        assertEquals(List.of(new GeneratedFile(".m2/settings.xml", "fresh ${cache.maven}")), config.resolvedFiles());
        assertEquals(1, config.entries().size());
    }

    @Test
    void offWithBlankRootDoesNotValidatePathsOrEnvironment() {
        final var config = new DependencyCacheConfiguration(
                Mode.OFF,
                "",
                Scope.REPOSITORY,
                "default",
                List.of(),
                List.of(new Entry("data", Kind.GENERIC, "../../outside", List.of())),
                Map.of("HOME", "${unknown}"),
                List.of(new GeneratedFile("/absolute", "${bad}")),
                300,
                MAINTENANCE,
                LIMITS);
        assertFalse(config.enabled());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        null,
                        "",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.OFF,
                        "",
                        null,
                        "default",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HOME",
                "PATH",
                "GH_TOKEN",
                "GITHUB_TOKEN",
                "YCA_CONTEXT_TOKEN",
                "DOCKER_HOST",
                "LD_PRELOAD",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "BASH_ENV",
                "NODE_OPTIONS",
                "npm_config_auth",
                "NPM_CONFIG_USERCONFIG_TOKEN",
                "CARGO_REGISTRIES_PRIVATE_TOKEN",
                "PIP_PROXY",
                "NUGET_API_KEY",
                "npm_config_key",
                "NPM_CONFIG_COOKIE"
            })
    void rejectsSecuritySensitiveEnvironment(final String name) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(),
                        Map.of(name, "value"),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "../escape",
                "/absolute",
                "a/../escape",
                "a/./data",
                "a\\data",
                ".",
                "",
                "${storage}/data",
                "C:/cache"
            })
    void rejectsUnsafeEntryAndFilePaths(final String path) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(new Entry("data", Kind.GENERIC, path, List.of())),
                        Map.of(),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(new GeneratedFile(path, "fresh")),
                        300,
                        MAINTENANCE,
                        LIMITS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"${unknown}", "${cache.missing}", "${storage", "${}", "${cache.maven}/${bad}"})
    void rejectsUnknownOrMalformedPlaceholders(final String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(Preset.MAVEN),
                        List.of(),
                        Map.of("MAVEN_OPTS", value),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(Preset.MAVEN),
                        List.of(),
                        Map.of(),
                        List.of(new GeneratedFile("config.txt", value)),
                        300,
                        MAINTENANCE,
                        LIMITS));
    }

    @Test
    void rejectsOverlapsAndDuplicateIds() {
        final var entry = new Entry("data", Kind.GENERIC, "caches/data", List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(entry, new Entry("nested", Kind.GENERIC, "caches/data/nested", List.of())),
                        Map.of(),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(entry, entry),
                        Map.of(),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
        for (final var path : List.of("caches/data/file", "caches", "caches/data")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DependencyCacheConfiguration(
                            Mode.READ_WRITE,
                            "/cache",
                            Scope.REPOSITORY,
                            "default",
                            List.of(),
                            List.of(entry),
                            Map.of(),
                            List.of(new GeneratedFile(path, "fresh")),
                            300,
                            MAINTENANCE,
                            LIMITS));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(new GeneratedFile("config", "x"), new GeneratedFile("config/nested", "y")),
                        300,
                        MAINTENANCE,
                        LIMITS));
    }

    @Test
    void rejectsUnsafeIdsAndNonpositiveLimits() {
        for (final var id : List.of("../bad", "cache/bad", "has space", "${data}", "")) {
            assertThrows(IllegalArgumentException.class, () -> new Entry(id, Kind.GENERIC, "data", List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new Entry("data", null, "data", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Entry("data", Kind.MAVEN, "data", List.of("a/b")));
        assertThrows(IllegalArgumentException.class, () -> new Maintenance(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Maintenance(30, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, 1, 1, 1, 0));
    }

    @Test
    void rejectsRootPlaceholdersAndCredentialUrls() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "${storage}/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyCacheConfiguration(
                        Mode.READ_WRITE,
                        "/cache",
                        Scope.REPOSITORY,
                        "default",
                        List.of(),
                        List.of(),
                        Map.of("PIP_INDEX_URL", "https://user:secret@example.test"),
                        List.of(),
                        300,
                        MAINTENANCE,
                        LIMITS));
    }
}
