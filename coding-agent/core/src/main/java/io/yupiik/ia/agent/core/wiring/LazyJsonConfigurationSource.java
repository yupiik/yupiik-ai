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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.io.ReaderSupplier;
import io.yupiik.fusion.json.configuration.JsonConfigurationSource;
import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * Lazily-built {@link ConfigurationSource} that backs the global {@code ~/.yupiik/coding-agent/
 * configuration.json} and the project-local {@code .yca/configuration.json}, honoring the project
 * file's {@code "$inherit": true} merge semantics.
 * Set {@code yca.configuration.project.enabled=false} in trusted CLI/environment/system configuration
 * to ignore the project-local file entirely and load only the global configuration.
 *
 * <p>Everything is deferred to the first {@link #get(String)} call because that is the earliest point
 * at which the captured {@link Configuration} - the live one every bean reads from - is fully settled
 * (CLI args, environment, system properties and every source registered before the container started).
 * The file locations are then resolved through it rather than through raw
 * {@link System#getProperty}/{@link System#getenv} reads, so CLI/env/system-property driven values win
 * instead of being duplicated or ignored.</p>
 *
 * <p>The captured Configuration is the very one whose source list contains this instance: its
 * {@code get(...)} would otherwise re-enter {@link #get(String)} before {@link #inner} is assigned and
 * overflow the stack. A {@code building} flag makes such a nested {@link #get(String)} return
 * {@code null}, so the path-resolution lookups ({@code yca.configuration.json},
 * {@code yca.storage.home}, {@code user.home}, {@code yca.workingDir}) fall through to the base sources
 * that own them - the JSON file never produces those keys (its typed keys are camel-cased, e.g.
 * {@code yca.storageHome}), so falling through is always correct.</p>
 */
public class LazyJsonConfigurationSource implements ConfigurationSource {
    private static final String PREFIX = "yca.";
    private static final String INHERIT = "$inherit";
    private static final String GLOBAL_CONFIG_KEY = PREFIX + "configuration.json";
    private static final String STORAGE_HOME_KEY = PREFIX + "storage.home";
    private static final String WORKING_DIR_KEY = PREFIX + "workingDir";
    private static final String USER_HOME_KEY = "user.home";
    private static final Function<String, String> KEY_NORMALIZER = key -> {
        final var stripped = key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
        return stripped.replace('-', '.');
    };

    private final Configuration configuration;

    /** Resolved and built on first {@link #get(String)}; still {@code null} before that and while building. */
    private volatile ConfigurationSource inner;

    /** Guards the build step so a re-entrant {@link #get(String)} does not recurse into {@link #build()}. */
    private boolean building;

    public LazyJsonConfigurationSource(final Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    public String get(final String key) {
        final var current = inner;
        if (current == null) {
            final var built = build();
            return built == null ? null : built.get(key);
        }
        return current.get(key);
    }

    private synchronized ConfigurationSource build() {
        var current = inner;
        if (current != null) {
            return current;
        }
        if (building) {
            // re-entrancy guard: a path-resolution lookup on the live Configuration triggered this
            // before inner was assigned - fall through to the base sources owning those keys
            return null;
        }
        building = true;
        try {
            current = createSource(configuration);
            inner = current;
            return current;
        } finally {
            building = false;
        }
    }

    private static ConfigurationSource createSource(final Configuration configuration) {
        final var globalPath = resolveGlobalConfigurationFile(configuration);
        // Typed configuration depends on this source; use the already supplied base configuration
        // for this bootstrap-only flag to avoid a circular dependency during source initialization.
        if (!configuration
                .get("yca.configuration.project.enabled")
                .map(Boolean::parseBoolean)
                .orElse(true)) {
            return loadJson(globalPath);
        }
        final var projectPath = projectConfigurationFile(configuration);
        final var child = loadJson(projectPath, true);
        final var parent = loadJson(globalPath);
        if (child == null) {
            return parent;
        }
        final var ordinary = "true".equals(child.get(INHERIT)) && parent != null
                ? new CombinedConfigurationSource(child, parent)
                : child;
        // Project $inherit:false must not discard a restrictive trusted sandbox policy.
        return key -> isSandboxKey(key) ? (parent == null ? null : parent.get(key)) : ordinary.get(key);
    }

    private static Path projectConfigurationFile(final Configuration configuration) {
        final var workingDir = configuration.get(WORKING_DIR_KEY).orElse(".");
        return Path.of(workingDir).toAbsolutePath().normalize().resolve(".yca").resolve("configuration.json");
    }

    /** Trusted global configuration location, shared with the sandbox initialization command. */
    public static Path resolveGlobalConfigurationFile(final Configuration configuration) {
        return Path.of(configuration.get(GLOBAL_CONFIG_KEY).orElseGet(() -> defaultConfigurationFile(configuration)));
    }

    /**
     * Global default location, resolved from the {@link Configuration} (which already abstracts
     * system properties, environment and CLI args) instead of raw {@code System.*} reads.
     */
    private static String defaultConfigurationFile(final Configuration configuration) {
        final var storageHome = configuration
                .get("yca.storageHome")
                .or(() -> configuration.get(STORAGE_HOME_KEY))
                .orElse("")
                .trim();
        if (storageHome.isEmpty()) {
            final var userHome = configuration.get(USER_HOME_KEY).orElse(".");
            return Path.of(userHome, ".yupiik", "coding-agent", "configuration.json")
                    .toString();
        }
        final var expanded = storageHome.startsWith("~/")
                ? configuration.get(USER_HOME_KEY).orElse(".") + storageHome.substring(1)
                : storageHome;
        return Path.of(expanded, "configuration.json").toString();
    }

    private static boolean isSandboxKey(final String key) {
        final var normalized = KEY_NORMALIZER.apply(key);
        return ("." + normalized + ".").contains(".sandbox.");
    }

    private static void rejectProjectSandbox(final Object value, final String prefix, final Path path) {
        if (value instanceof Map<?, ?> map) {
            for (final var entry : map.entrySet()) {
                final var key = KEY_NORMALIZER.apply(
                        prefix.isEmpty() ? entry.getKey().toString() : prefix + "." + entry.getKey());
                if (isSandboxKey(key)) {
                    throw new IllegalArgumentException("Project configuration must not override sandbox settings ('"
                            + key + "' in " + path + "). Use trusted global configuration or --sandbox true|false.");
                }
                rejectProjectSandbox(entry.getValue(), key, path);
            }
        }
    }

    private static ConfigurationSource loadJson(final Path path) {
        return loadJson(path, false);
    }

    private static ConfigurationSource loadJson(final Path path, final boolean project) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (final var in = Files.newInputStream(path)) {
            final byte[] bytes;
            // Do not inject the application mapper here: it itself depends on configuration.
            try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
                final var document =
                        mapper.fromString(Object.class, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (project) {
                    rejectProjectSandbox(document, "", path);
                }
                bytes = mapper.toString(prepareCommandEnvironment(document, "")).getBytes(StandardCharsets.UTF_8);
            }
            final ReaderSupplier supplier =
                    () -> new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
            return new JsonConfigurationSource(supplier, KEY_NORMALIZER);
        } catch (final IOException e) {
            throw new UncheckedIOException("Invalid configuration file: '" + path + "'", e);
        }
    }

    private static Object prepareCommandEnvironment(final Object value, final String prefix) throws IOException {
        if (!(value instanceof Map<?, ?> map)) {
            return value;
        }
        if ("sandbox.environment.set".equals(KEY_NORMALIZER.apply(prefix))) {
            // Fusion configuration factories consume string maps as Java properties, whereas
            // ordinary JSON objects are flattened as nested configuration. Preserve literal
            // variable names/values and accept natural JSON without requiring a $asList marker.
            final var properties = new Properties();
            for (final var entry : map.entrySet()) {
                if (!(entry.getValue() instanceof String text)) {
                    throw new IllegalArgumentException("Sandbox environment.set values must be strings");
                }
                properties.setProperty(entry.getKey().toString(), text);
            }
            try (final var writer = new StringWriter()) {
                properties.store(writer, null);
                return writer.toString();
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        for (final var entry : map.entrySet()) {
            final var key = entry.getKey().toString();
            result.put(
                    key,
                    prepareCommandEnvironment(
                            entry.getValue(), KEY_NORMALIZER.apply(prefix.isEmpty() ? key : prefix + "." + key)));
        }
        return result;
    }
}
