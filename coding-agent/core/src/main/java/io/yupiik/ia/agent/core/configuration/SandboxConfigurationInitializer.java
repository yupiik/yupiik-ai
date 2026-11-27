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

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Persists the injected effective sandbox policy without replacing existing user choices. */
@DefaultScoped
public class SandboxConfigurationInitializer {
    private final JsonMapper mapper;
    private final SandboxConfiguration configuredSandbox;

    public SandboxConfigurationInitializer(final JsonMapper mapper, final CodingAgentConfiguration configuration) {
        this.mapper = mapper;
        this.configuredSandbox = configuration.sandbox();
    }

    /** Preview never writes; apply backs up an existing file before an atomic replacement. */
    public Result initialize(final Path location, final boolean apply) throws IOException {
        final var path = location.toAbsolutePath().normalize();
        validate(path);
        final var existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        final var original = existed ? Files.readAllBytes(path) : new byte[0];
        final var root = existed
                ? object(mapper.fromString(Object.class, new String(original, StandardCharsets.UTF_8)), "configuration")
                : new LinkedHashMap<String, Object>();
        rejectAlternateSandboxKeys(root, "");
        final var defaults = configuredSandbox;
        final var filesystem = defaults.filesystem();
        final Map<String, Object> desired = Map.of(
                "enabled", defaults.enabled(),
                "helper", Map.of("executable", defaults.helper().executable()),
                "macOs", Map.of("experimental", defaults.macOs().experimental()),
                "environment",
                        Map.of(
                                "inherit",
                                defaults.environment().inherit(),
                                "set",
                                defaults.environment().set()),
                "unix",
                        Map.of(
                                "socketBridges",
                                defaults.unix().socketBridges().stream()
                                        .map(bridge -> Map.of("path", bridge.path(), "port", bridge.port()))
                                        .toList()),
                "filesystem",
                        Map.of(
                                "allowRead",
                                filesystem.allowRead(),
                                "allowWrite",
                                filesystem.allowWrite(),
                                "denyRead",
                                filesystem.denyRead(),
                                "denyWrite",
                                filesystem.denyWrite()),
                "network", Map.of("allowedDomains", defaults.network().allowedDomains()),
                "trustedIntegrations", defaults.trustedIntegrations());
        final var defaultsJson = mapper.toString(desired);
        final var before = mapper.toString(root);
        final var yca = root.containsKey("yca") ? object(root.get("yca"), "yca") : new LinkedHashMap<String, Object>();
        root.put("yca", yca);
        final var sandbox = yca.containsKey("sandbox")
                ? object(yca.get("sandbox"), "yca.sandbox")
                : new LinkedHashMap<String, Object>();
        yca.put("sandbox", sandbox);
        mergeDefaults(sandbox, desired, "yca.sandbox");
        final var updated = mapper.toString(root);
        final var changed = !before.equals(updated);
        if (!apply || !changed) {
            return new Result(path, null, changed, defaultsJson);
        }

        createDirectories(path.getParent());
        validate(path);
        // A private, exclusively-created lock serializes cooperating init invocations.
        final var lock = path.resolveSibling(path.getFileName() + ".sandbox-init.lock");
        var acquired = false;
        try (final var ignored = Files.newByteChannel(
                lock,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                privateAttributes(path, false))) {
            acquired = true;
            Path temporary = null;
            Path backup = null;
            try {
                verifyUnchanged(path, existed, original);
                if (existed) {
                    backup = Files.createTempFile(
                            path.getParent(), path.getFileName() + ".backup-", ".json", privateAttributes(path, false));
                    writeAndForce(backup, original);
                }
                temporary = Files.createTempFile(
                        path.getParent(), path.getFileName() + ".init-", ".json", privateAttributes(path, false));
                writeAndForce(temporary, (updated + "\n").getBytes(StandardCharsets.UTF_8));
                verifyUnchanged(path, existed, original);
                // Deliberately no non-atomic fallback: a failed move leaves the original untouched.
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                temporary = null;
                return new Result(path, backup, true, defaultsJson);
            } finally {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            }
        } finally {
            if (acquired) {
                Files.deleteIfExists(lock);
            }
        }
    }

    private void verifyUnchanged(final Path path, final boolean existed, final byte[] original) throws IOException {
        validate(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) != existed
                || (existed && !Arrays.equals(original, Files.readAllBytes(path)))) {
            throw new IOException("Configuration changed during sandbox init: " + path);
        }
    }

    private void writeAndForce(final Path path, final byte[] bytes) throws IOException {
        try (final var channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            final var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private void mergeDefaults(
            final Map<String, Object> existing, final Map<String, Object> defaults, final String prefix) {
        for (final var entry : defaults.entrySet()) {
            final var key = entry.getKey();
            if (!existing.containsKey(key)) {
                existing.put(key, entry.getValue());
            } else if (entry.getValue() instanceof Map<?, ?>) {
                final var child = object(existing.get(key), prefix + "." + key);
                existing.put(key, child);
                mergeDefaults(child, object(entry.getValue(), prefix + "." + key), prefix + "." + key);
            } else if (!Objects.equals(existing.get(key), entry.getValue())) {
                throw new IllegalArgumentException("Conflicting existing sandbox setting: " + prefix + "." + key
                        + ". Review it manually; sandbox init does not replace existing choices.");
            }
        }
    }

    private Map<String, Object> object(final Object value, final String label) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object at " + label + ". No configuration was changed.");
        }
        final var copy = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> copy.put(key.toString(), item));
        return copy;
    }

    private void rejectAlternateSandboxKeys(final Map<String, Object> map, final String prefix) {
        for (final var entry : map.entrySet()) {
            final var raw = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            final var normalized = raw.replace('-', '.');
            final var stripped = normalized.startsWith("yca.") ? normalized.substring(4) : normalized;
            if (("sandbox".equals(stripped) || stripped.startsWith("sandbox."))
                    && (!raw.startsWith("yca.sandbox")
                            || entry.getKey().contains(".")
                            || entry.getKey().contains("-"))) {
                throw new IllegalArgumentException("Conflicting sandbox key representation: " + raw
                        + ". Use the nested yca.sandbox JSON object before running sandbox init.");
            }
            if (entry.getValue() instanceof Map<?, ?>) {
                rejectAlternateSandboxKeys(object(entry.getValue(), raw), raw);
            }
        }
    }

    private void createDirectories(final Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        createDirectories(path.getParent());
        Files.createDirectory(path, privateAttributes(path, true));
    }

    private FileAttribute<?>[] privateAttributes(final Path path, final boolean directory) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))
                }
                : new FileAttribute<?>[0];
    }

    private void validate(final Path path) throws IOException {
        for (var component = path; component != null; component = component.getParent()) {
            if (!Files.exists(component, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(component)) {
                throw new IOException("Refusing symbolic link in configuration path: " + component);
            }
            if (component.equals(path)
                    ? !Files.isRegularFile(component, LinkOption.NOFOLLOW_LINKS)
                    : !Files.isDirectory(component, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Unsafe configuration path (expected regular file/directories): " + component);
            }
            if (component.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                final var permissions = Files.getPosixFilePermissions(component, LinkOption.NOFOLLOW_LINKS);
                final var writable = permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE);
                final var stickyDirectory = !component.equals(path)
                        && component
                                .getFileSystem()
                                .supportedFileAttributeViews()
                                .contains("unix")
                        && (((Number) Files.getAttribute(component, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue()
                                        & 01000)
                                != 0;
                if (writable && !stickyDirectory) {
                    throw new IOException("Refusing group/other-writable configuration path: " + component);
                }
            }
            if (component.equals(path)
                    && component.getFileSystem().supportedFileAttributeViews().contains("unix")
                    && ((Number) Files.getAttribute(component, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue()
                            != 1) {
                throw new IOException("Refusing multiply-linked configuration file: " + component);
            }
        }
    }

    public record Result(Path path, Path backup, boolean changed, String defaultsJson) {}
}
