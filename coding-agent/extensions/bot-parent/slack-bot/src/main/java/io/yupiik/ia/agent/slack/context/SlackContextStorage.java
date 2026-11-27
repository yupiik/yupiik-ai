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
package io.yupiik.ia.agent.slack.context;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Startup-only storage checks and ancillary-integration isolation for context-bearing attempts. */
public class SlackContextStorage {
    /**
     * Validates an existing root, without creating it or changing permissions.
     * Call only for context-enabled startup, before opening Socket Mode.
     * State/admission disjointness remains the responsibility of the existing caller checks.
     */
    public Path validate(
            final ExecutorConfiguration executor, final boolean allowSwapBackedTmpfs, final boolean allowContainerLogs)
            throws IOException {
        if (!"Linux".equals(operatingSystem()) || executor.backend() != ExecutorConfiguration.Backend.DOCKER) {
            throw new IllegalArgumentException("Slack context requires Linux Docker execution");
        }
        final var docker = executor.docker();
        if (!"hidden".equals(executor.showToolOutput()) || docker == null || docker.services() == null) {
            throw new IllegalArgumentException("Slack context requires hidden tool output and attempt services");
        }
        if (!docker.disableContainerLogs() && !allowContainerLogs) {
            throw new IllegalArgumentException(
                    "Slack context requires disabled Docker logs unless slack.context.allowContainerLogs explicitly accepts persistent context logging");
        }
        if (executor.attemptRoot() == null || executor.attemptRoot().isBlank()) {
            throw new IllegalArgumentException("Slack context requires an explicit attempt root");
        }
        final var root = Path.of(executor.attemptRoot());
        if (!root.isAbsolute() || !root.equals(root.normalize())) {
            throw new IllegalArgumentException("Slack context attempt root must be normalized and absolute");
        }
        Path current = root.getRoot();
        for (final var segment : root) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Slack context attempt root must not contain symbolic links");
            }
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.equals(root.toRealPath())
                || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))
                || !Files.getAttribute(root, "unix:uid")
                        .equals(Files.getAttribute(Path.of("/proc/self"), "unix:uid"))) {
            throw new IOException("Slack context attempt root must be an existing private owned directory");
        }
        validateMount(root, mountInfo(), allowSwapBackedTmpfs);
        if (!docker.disableContainerLogs()) {
            Logger.getLogger(SlackContextStorage.class.getName())
                    .warning(
                            "Slack context Docker logging explicitly accepted: agent stdout/stderr may persist Slack context in Docker logs");
        }
        return root;
    }

    /** Actual controller mount namespace, including bind-mount restrictions. */
    protected List<String> mountInfo() throws IOException {
        return Files.readAllLines(Path.of("/proc/self/mountinfo"));
    }

    protected String operatingSystem() {
        return System.getProperty("os.name");
    }

    private void validateMount(final Path root, final List<String> lines, final boolean allowSwapBackedTmpfs)
            throws IOException {
        final var mounts = new ArrayList<Mount>();
        try {
            for (final var line : lines) {
                final var fields = line.split(" ");
                int separator = 6;
                while (separator < fields.length && !"-".equals(fields[separator])) {
                    separator++;
                }
                if (separator + 4 != fields.length) {
                    throw new IllegalArgumentException();
                }
                final var point = Path.of(unescape(fields[4]));
                if (!point.isAbsolute() || !point.equals(point.normalize())) {
                    throw new IllegalArgumentException();
                }
                mounts.add(new Mount(
                        point,
                        fields[separator + 1],
                        Set.of(fields[5].split(",")),
                        Set.of(fields[separator + 3].split(","))));
            }
        } catch (final RuntimeException invalid) {
            throw new IOException("Invalid controller mount metadata");
        }
        Mount selected = null;
        for (final var mount : mounts) {
            if (mount.point().startsWith(root) && !mount.point().equals(root)) {
                throw new IOException("Slack context attempt root must not contain nested mounts");
            }
            if (root.startsWith(mount.point())
                    && (selected == null
                            || mount.point().getNameCount() > selected.point().getNameCount()
                            || mount.point().equals(selected.point()))) {
                if (selected != null && mount.point().equals(selected.point())) {
                    throw new IOException("Ambiguous controller attempt mount");
                }
                selected = mount;
            }
        }
        // noswap is a superblock property; nodev/nosuid/noexec must be on the selected mount,
        // not merely a different mount of the same tmpfs (a bind can override these flags).
        if (selected == null
                || !"tmpfs".equals(selected.type())
                || !selected.options().containsAll(Set.of("rw", "nodev", "nosuid", "noexec"))) {
            throw new IOException("Slack context requires a nodev,nosuid,noexec tmpfs attempt root");
        }
        if (!selected.superOptions().contains("noswap")) {
            if (!allowSwapBackedTmpfs) {
                throw new IOException(
                        "Slack context requires noswap tmpfs unless slack.context.allowSwapBackedTmpfs explicitly accepts host swap retention");
            }
            Logger.getLogger(SlackContextStorage.class.getName())
                    .warning(
                            "Slack context swap-backed tmpfs explicitly accepted: context data may persist in host swap; noswap protection is unavailable");
        }
    }

    private String unescape(final String value) {
        final var decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            if (c != '\\') {
                decoded.append(c);
            } else {
                if (i + 3 >= value.length()) {
                    throw new IllegalArgumentException();
                }
                final var escape = value.substring(i + 1, i + 4);
                decoded.append(
                        switch (escape) {
                            case "040" -> ' ';
                            case "011" -> '\t';
                            case "012" -> '\n';
                            case "134" -> '\\';
                            default -> throw new IllegalArgumentException();
                        });
                i += 3;
            }
        }
        return decoded.toString();
    }

    /** Rewrite the prepared private configuration, before provider retargeting and context injection. */
    public void sanitize(final Path configuration, final JsonMapper json) throws IOException {
        if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Context attempt configuration must be a regular non-symbolic file");
        }
        final var document = json.fromString(AgentConfigurationDocument.class, Files.readString(configuration));
        final var settings = new LinkedHashMap<>(document.configuration());
        for (final var key : List.of("memory", "skills", "subagents", "notifications")) {
            settings.put(key, Map.of("enabled", false));
        }
        settings.putIfAbsent("mcp", Map.of("enabled", false, "servers", List.of()));
        settings.put("hooks", Map.of());
        settings.put("bot", Map.of("feedback", Map.of("enabled", false)));
        settings.put("httpClient", Map.of("debug", false));
        // Keep the operator's effective MCP/web-search policy, including ordinary tool defaults.
        // Network access still requires the unchanged, explicitly approved destination policy.
        Files.writeString(
                configuration, json.toString(new AgentConfigurationDocument(settings, document.completionSettings())));
    }

    private record Mount(Path point, String type, Set<String> options, Set<String> superOptions) {}
}
