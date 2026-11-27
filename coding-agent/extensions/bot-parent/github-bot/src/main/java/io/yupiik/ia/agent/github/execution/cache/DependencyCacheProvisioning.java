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

import io.yupiik.ia.agent.bot.runtime.ToolEnvironment;
import io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Provisions only per-attempt tool data and fresh nonsecret configuration, never host cache contents. */
public final class DependencyCacheProvisioning {
    private static final Pattern CACHE_PATH = Pattern.compile("[-A-Za-z0-9_./:]+");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    private DependencyCacheProvisioning() {}

    public static final Map<String, String> provision(
            final DependencyCacheConfiguration config, final Path attempt, final boolean containerPaths)
            throws IOException {
        if (!config.enabled()) {
            return Map.of();
        }
        final var storage = attempt.toAbsolutePath().normalize().resolve("storage");
        final var entries = config.resolvedEntries();
        final var files = config.resolvedFiles();
        // Check every existing ancestor before creating anything. Neither the attempt nor the host
        // cache root may redirect provisioning through a symbolic link.
        checkAncestors(Path.of(config.root()).toAbsolutePath().normalize());
        checkAncestors(storage);
        for (final var entry : entries) {
            checkAncestors(storage.resolve(entry.directory()));
        }
        for (final var file : files) {
            final var target = storage.resolve(file.path());
            checkAncestors(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Generated dependency configuration must be a regular file: " + target);
            }
        }
        final var substitutions = new TreeMap<String, String>();
        final var renderedStorage = containerPaths ? "/attempt/storage" : storage.toString();
        substitutions.put("storage", renderedStorage);
        for (final var entry : entries) {
            substitutions.put("cache." + entry.id(), renderedStorage + "/" + entry.directory());
        }
        // MAVEN_OPTS is tokenized by version-specific launcher scripts, not passed as one
        // ProcessBuilder argument. Do not pretend generic placeholder substitution performs
        // shell quoting or XML escaping for the built-in settings file.
        final var mavenPreset = config.presets().contains(DependencyCacheConfiguration.Preset.MAVEN)
                || config.presets().contains(DependencyCacheConfiguration.Preset.COMMON);
        for (final var entry : entries) {
            final var placeholder = "${cache." + entry.id() + "}";
            final var mavenOptions = config.resolvedEnvironment().getOrDefault("MAVEN_OPTS", "");
            if ((mavenPreset && entry.id().equals("maven"))
                    || (mavenOptions.contains("-Dmaven.repo.local=")
                            && (mavenOptions.contains(placeholder) || mavenOptions.contains("${storage}")))) {
                if (!CACHE_PATH
                        .matcher(substitutions.get("cache." + entry.id()))
                        .matches()) {
                    throw new IOException(
                            "Maven cache paths require plain paths without spaces or shell/XML metacharacters");
                }
            }
        }
        final var environment = config.resolvedEnvironment().entrySet().stream()
                .collect(
                        TreeMap<String, String>::new,
                        (result, entry) -> result.put(entry.getKey(), render(entry.getValue(), substitutions)),
                        Map::putAll);
        // Render and bound all files before performing any writes.
        final var contents = new TreeMap<Path, byte[]>();
        for (final var file : files) {
            final var content = render(file.content(), substitutions).getBytes(StandardCharsets.UTF_8);
            if (content.length > config.limits().maxFileBytes()) {
                throw new IOException("Generated dependency configuration exceeds maximum file size");
            }
            contents.put(storage.resolve(file.path()), content);
        }
        Files.createDirectories(storage);
        // Leave unused cache entries absent. Tools create their own directories on demand;
        // otherwise unused presets would become explicit empty snapshots during publication.
        for (final var file : contents.entrySet()) {
            final var target = file.getKey();
            Files.createDirectories(target.getParent());
            // Replace rather than truncate: an existing hard link must not mutate another file.
            final var temporary = Files.createTempFile(target.getParent(), ".dependency-config-", ".tmp");
            try {
                Files.write(temporary, file.getValue());
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return Collections.unmodifiableMap(environment);
    }

    public static void validateEnvironment(final Map<String, String> environment) {
        ToolEnvironment.validate(environment);
    }

    public static void validatePrivateRoot(final Path configured, final List<Path> protectedRoots) {
        if (!configured.isAbsolute()) {
            throw new IllegalArgumentException("Dependency cache requires an absolute private root");
        }
        final var root = configured.normalize();
        for (final var protectedRoot : protectedRoots) {
            final var excluded = protectedRoot.toAbsolutePath().normalize();
            if (root.startsWith(excluded) || excluded.startsWith(root)) {
                throw new IllegalArgumentException(
                        "Dependency cache must be disjoint from state, runtime and attempt storage");
            }
        }
    }

    private static String render(final String value, final Map<String, String> substitutions) {
        return PLACEHOLDER.matcher(value).replaceAll(match -> {
            final var replacement = substitutions.get(match.group(1));
            if (replacement == null) {
                throw new IllegalArgumentException("Unknown dependency cache placeholder: " + match.group(1));
            }
            return Matcher.quoteReplacement(replacement);
        });
    }

    private static void checkAncestors(final Path path) throws IOException {
        Path current = path.getRoot();
        for (final var segment : path) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Dependency provisioning paths must not contain symbolic links: " + current);
            }
        }
    }
}
