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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.json.configuration.JsonConfigurationSource;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.command.WorkerCommand.Options;
import io.yupiik.ia.agent.github.execution.cache.DependencyCacheProvisioning;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyCacheDocumentationTest {
    private static final Pattern JSON_EXAMPLE = Pattern.compile("(?ms)^\\[source,json]\\R----\\R(.*?)\\R----");

    @Test
    void allDocumentedExamplesHaveAnInjectedVariant() {
        assertEquals(3, examples().size(), "Add an injected variant for each new documentation example");
    }

    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @TempDir
        Path temporary;

        @Test
        void bindsAndProvisions(@Fusion final Options options) throws Exception {
            provision(options, temporary.resolve("default"));
        }
    }

    @Nested
    @FusionSupport(modules = MavenOverrideModule.class)
    class MavenOverride {
        @TempDir
        Path temporary;

        @Test
        void bindsAndProvisions(@Fusion final Options options) throws Exception {
            provision(options, temporary.resolve("maven"));
        }
    }

    @Nested
    @FusionSupport(modules = CustomCacheModule.class)
    class CustomCache {
        @TempDir
        Path temporary;

        @Test
        void bindsAndProvisions(@Fusion final Options options) throws Exception {
            provision(options, temporary.resolve("custom"));
        }
    }

    public static class DefaultsModule extends GithubConfigurationTestModule {
        public DefaultsModule() {
            super(exampleSource(0));
        }
    }

    public static class MavenOverrideModule extends GithubConfigurationTestModule {
        public MavenOverrideModule() {
            super(exampleSource(1));
        }
    }

    public static class CustomCacheModule extends GithubConfigurationTestModule {
        public CustomCacheModule() {
            super(exampleSource(2));
        }
    }

    private static ConfigurationSource exampleSource(final int index) {
        final var example = examples().get(index);
        final var json = new JsonConfigurationSource(() -> new StringReader(example), key -> key.replace('-', '.'));
        return json::get;
    }

    private static List<String> examples() {
        final var document = Path.of(
                        "../../../../documentation/src/main/minisite/content/coding-agent/github-bot/cache.adoc")
                .toAbsolutePath()
                .normalize();
        try {
            return JSON_EXAMPLE
                    .matcher(Files.readString(document))
                    .results()
                    .map(match -> match.group(1))
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void provision(final Options options, final Path attempt) throws Exception {
        final var policy = options.worker().dependencyCache();
        final var environment = DependencyCacheProvisioning.provision(policy, attempt, true);
        if (policy.enabled()) {
            assertFalse(policy.resolvedEntries().isEmpty());
            environment.forEach((key, value) -> assertFalse(value.contains("${"), key));
            for (final var file : policy.resolvedFiles()) {
                assertTrue(Files.isRegularFile(attempt.resolve("storage").resolve(file.path())));
                assertFalse(Files.readString(attempt.resolve("storage").resolve(file.path()))
                        .contains("${"));
            }
        } else {
            assertTrue(environment.isEmpty());
            assertFalse(Files.exists(attempt));
        }
    }
}
