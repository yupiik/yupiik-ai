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
package io.yupiik.ia.agent.bot.runtime;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Private, provider-neutral attempt handoff; no task, prompt, repository or broker policy. */
public final class AttemptPreparation {
    private static final Pattern DIRECTORY_PREFIX = Pattern.compile("[A-Za-z0-9_-]{3,80}");

    private final JsonMapper json;

    public AttemptPreparation(final JsonMapper json) {
        this.json = json;
    }

    public Path prepare(final Path configuration, final Path attemptRoot, final String directoryPrefix)
            throws IOException {
        if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Trusted agent configuration must be a regular non-symbolic file");
        }
        if (directoryPrefix == null
                || !DIRECTORY_PREFIX.matcher(directoryPrefix).matches()) {
            throw new IllegalArgumentException("A plain attempt directory prefix is required");
        }
        final var permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        if (attemptRoot != null) {
            Path current = attemptRoot.toAbsolutePath().normalize().getRoot();
            for (final var segment : attemptRoot.toAbsolutePath().normalize()) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException("Attempt root must not contain symlinks");
                }
            }
            Files.createDirectories(attemptRoot, permissions);
        }
        final var directory = attemptRoot == null
                ? Files.createTempDirectory(directoryPrefix, permissions)
                : Files.createTempDirectory(attemptRoot, directoryPrefix, permissions);
        try {
            Files.writeString(directory.resolve(".prepared-only"), "yca-prepared-v1\n", StandardOpenOption.CREATE_NEW);
            for (final var name : List.of("storage", "workspace", "output")) {
                Files.createDirectory(directory.resolve(name));
            }
            final var document = json.fromString(AgentConfigurationDocument.class, Files.readString(configuration));
            final var trusted = document.configuration();
            final var sanitized = new LinkedHashMap<String, Object>();
            for (final var key : List.of("provider", "model", "llm", "compaction", "pruning", "compression", "todos")) {
                if (trusted.containsKey(key)) {
                    sanitized.put(key, trusted.get(key));
                }
            }
            // Only the trusted operator document is read here, never workspace/project configuration.
            for (final var key : List.of("memory", "skills", "subagents", "mcp", "notifications", "tools")) {
                if (trusted.get(key) != null) {
                    sanitized.put(key, trusted.get(key));
                }
            }
            sanitized.putIfAbsent("memory", Map.of("enabled", false));
            sanitized.putIfAbsent("skills", Map.of("enabled", false));
            sanitized.putIfAbsent("subagents", Map.of("enabled", false));
            sanitized.put("permissions", Map.of("mode", "YOLO"));
            sanitized.putIfAbsent("mcp", Map.of("enabled", false, "servers", List.of()));
            sanitized.put("hooks", Map.of());
            sanitized.putIfAbsent("notifications", Map.of("enabled", false));
            sanitized.put("home", "/attempt/storage");
            sanitized.put("storageHome", "/attempt/storage");
            sanitized.put("workingDir", "/attempt/workspace");
            sanitized.put("mode", "BUILD");
            final var file = Files.createFile(
                    directory.resolve("configuration.json"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(
                    file, json.toString(new AgentConfigurationDocument(sanitized, document.completionSettings())));
            return directory;
        } catch (final IOException | RuntimeException e) {
            try {
                delete(directory);
            } catch (final IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    public static void delete(final Path directory) throws IOException {
        if (directory == null || Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // walk does not follow symbolic links; malicious links never remove host files.
        try (final var paths = Files.walk(directory)) {
            for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
