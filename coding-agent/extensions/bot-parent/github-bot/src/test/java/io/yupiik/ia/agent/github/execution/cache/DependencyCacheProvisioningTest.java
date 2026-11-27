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
package io.yupiik.ia.agent.github.execution.cache;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Entry;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.GeneratedFile;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Kind;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Limits;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Maintenance;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Mode;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Preset;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DependencyCacheProvisioningTest {
    private static final Maintenance MAINTENANCE = new Maintenance(30, 10L * 1024 * 1024 * 1024);
    private static final Limits LIMITS = new Limits(100000, 16L * 1024 * 1024 * 1024, 64L * 1024 * 1024, 600, 2);

    @TempDir
    Path temporary;

    @Test
    void offDoesNotEvenDereferenceAttemptOrValidateDormantPaths() throws IOException {
        final var config = new DependencyCacheConfiguration(
                Mode.OFF,
                "",
                Scope.REPOSITORY,
                "default",
                List.of(),
                List.of(new Entry("data", Kind.GENERIC, "../outside", List.of())),
                Map.of("HOME", "${unknown}"),
                List.of(new GeneratedFile("../outside", "unused")),
                300,
                MAINTENANCE,
                LIMITS);
        assertEquals(Map.of(), DependencyCacheProvisioning.provision(config, null, false));
        assertEquals(Map.of(), DependencyCacheProvisioning.provision(config, temporary.resolve("absent"), true));
        assertFalse(Files.exists(temporary.resolve("absent")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void provisionsHostOrContainerPathsAndFreshFiles(final boolean containerPaths) throws IOException {
        final var attempt = temporary.resolve("attempt-1");
        final var root = temporary.resolve("host-cache");
        final var config = new DependencyCacheConfiguration(
                Mode.READ_WRITE,
                root.toString(),
                Scope.REPOSITORY,
                "default",
                List.of(Preset.MAVEN, Preset.NPM),
                List.of(new Entry("maven", Kind.MAVEN, "caches/custom-maven", List.of())),
                Map.of("npm_config_offline", "true", "MAVEN_OPTS", "-Dmaven.repo.local=${cache.maven}"),
                List.of(new GeneratedFile("config/tools.txt", "${storage}\n${cache.maven}\n${cache.npm}\n")),
                300,
                MAINTENANCE,
                LIMITS);
        final var env = DependencyCacheProvisioning.provision(config, attempt, containerPaths);
        final var storage = attempt.toAbsolutePath().resolve("storage");
        final var rendered = containerPaths ? "/attempt/storage" : storage.toString();
        assertEquals("-Dmaven.repo.local=" + rendered + "/caches/custom-maven", env.get("MAVEN_OPTS"));
        assertEquals(rendered + "/caches/npm", env.get("npm_config_cache"));
        assertEquals("true", env.get("npm_config_offline"));
        assertEquals(3, env.size());
        assertEquals(
                rendered + "\n" + rendered + "/caches/custom-maven\n" + rendered + "/caches/npm/_cacache\n",
                Files.readString(storage.resolve("config/tools.txt")));
        assertEquals(
                "<settings><localRepository>" + rendered + "/caches/custom-maven</localRepository></settings>\n",
                Files.readString(storage.resolve(".m2/settings.xml")));
        assertFalse(Files.exists(storage.resolve("caches/custom-maven")));
        assertFalse(Files.exists(storage.resolve("caches/npm/_cacache")));
        assertFalse(Files.exists(root)); // Host persistence belongs to the store, not provisioning.
        Files.writeString(storage.resolve(".m2/settings.xml"), "stale cached secret must not survive");
        DependencyCacheProvisioning.provision(config, attempt, containerPaths);
        assertFalse(Files.readString(storage.resolve(".m2/settings.xml")).contains("secret"));
        assertThrows(UnsupportedOperationException.class, () -> env.put("HOME", "x"));
        assertEquals(env.keySet().stream().sorted().toList(), List.copyOf(env.keySet()));
    }

    @Test
    void restoreOnlyProvisionsConfigurationButLeavesUnusedEntriesAbsent() throws IOException {
        final var config = new DependencyCacheConfiguration(
                Mode.RESTORE_ONLY,
                temporary.resolve("cache").toString(),
                Scope.REPOSITORY,
                "default",
                List.of(Preset.COMMON),
                List.of(),
                Map.of(),
                List.of(),
                300,
                MAINTENANCE,
                LIMITS);
        final var env = DependencyCacheProvisioning.provision(config, temporary.resolve("attempt"), false);
        final var storage = temporary.resolve("attempt/storage");
        for (final var entry : config.resolvedEntries()) {
            assertFalse(Files.exists(storage.resolve(entry.directory())));
        }
        assertTrue(Files.isRegularFile(storage.resolve(".m2/settings.xml")));
        assertEquals(storage.resolve("caches/go/pkg/mod").toString(), env.get("GOMODCACHE"));
        assertFalse(Files.exists(storage.resolve("caches/go/bin")));
        assertFalse(Files.exists(storage.resolve("caches/cargo/bin")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "amp&ersand", "quote\"", "single'quote", "dollar$"})
    void rejectsUnsafeMavenLauncherAndXmlPathsBeforeCreatingFiles(final String name) {
        final var config = new DependencyCacheConfiguration(
                Mode.READ_WRITE,
                temporary.resolve("cache").toString(),
                Scope.REPOSITORY,
                "default",
                List.of(Preset.MAVEN),
                List.of(),
                Map.of(),
                List.of(),
                300,
                MAINTENANCE,
                LIMITS);
        final var attempt = temporary.resolve(name);
        assertThrows(IOException.class, () -> DependencyCacheProvisioning.provision(config, attempt, false));
        assertFalse(Files.exists(attempt));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "attempt",
                "attempt/storage",
                "attempt/storage/caches",
                "attempt/storage/caches/data",
                "attempt/storage/config",
                "attempt/storage/config/tools.txt",
                "host-cache"
            })
    void rejectsSymlinksAtEveryProvisioningBoundary(final String linked) throws IOException {
        final var outside = Files.createDirectory(temporary.resolve("outside"));
        final var link = temporary.resolve(linked);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        final var config = new DependencyCacheConfiguration(
                Mode.READ_WRITE,
                temporary.resolve("host-cache").toString(),
                Scope.REPOSITORY,
                "default",
                List.of(),
                List.of(new Entry("data", Kind.GENERIC, "caches/data", List.of())),
                Map.of(),
                List.of(new GeneratedFile("config/tools.txt", "fresh")),
                300,
                MAINTENANCE,
                LIMITS);
        assertThrows(
                IOException.class,
                () -> DependencyCacheProvisioning.provision(config, temporary.resolve("attempt"), false));
        try (final var children = Files.list(outside)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void replacesGeneratedHardLinksWithoutChangingTheOriginal() throws IOException {
        final var original = temporary.resolve("original");
        Files.writeString(original, "must stay unchanged");
        final var storage = Files.createDirectories(temporary.resolve("attempt/storage"));
        Files.createLink(storage.resolve("config.txt"), original);
        final var config = new DependencyCacheConfiguration(
                Mode.READ_WRITE,
                temporary.resolve("cache").toString(),
                Scope.REPOSITORY,
                "default",
                List.of(),
                List.of(),
                Map.of(),
                List.of(new GeneratedFile("config.txt", "fresh")),
                300,
                MAINTENANCE,
                LIMITS);
        DependencyCacheProvisioning.provision(config, temporary.resolve("attempt"), false);
        assertEquals("fresh", Files.readString(storage.resolve("config.txt")));
        assertEquals("must stay unchanged", Files.readString(original));
    }

    @Test
    void boundsGeneratedContentBeforeCreatingAnything() {
        final var config = new DependencyCacheConfiguration(
                Mode.READ_WRITE,
                temporary.resolve("cache").toString(),
                Scope.REPOSITORY,
                "default",
                List.of(),
                List.of(),
                Map.of(),
                List.of(new GeneratedFile("config.txt", "too large")),
                300,
                MAINTENANCE,
                new Limits(100, 2, 100, 10, 1));
        assertThrows(
                IOException.class,
                () -> DependencyCacheProvisioning.provision(config, temporary.resolve("attempt"), false));
        assertFalse(Files.exists(temporary.resolve("attempt")));
    }
}
