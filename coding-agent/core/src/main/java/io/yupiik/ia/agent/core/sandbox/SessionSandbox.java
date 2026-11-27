/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.configuration.SandboxConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxEnvironmentConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxUnixSocketBridgeConfiguration;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Session-owned policy, grants, process and egress lifetime; no ambient/thread-local policy. */
public final class SessionSandbox implements Sandbox {
    private final boolean enabled;
    private final SandboxPaths paths;
    private final Path scratch;
    private final Path control;
    private final Path project;
    private final Path workdir;
    private final PermissionService permissions;
    private final String helperExecutable;
    private final boolean experimentalMacOs;
    private final SandboxEnvironmentConfiguration environment;
    private final List<SandboxUnixSocketBridgeConfiguration> socketBridges;
    private final List<String> domains;
    private final List<Path> reads = new ArrayList<>();
    private final List<Path> writes = new ArrayList<>();
    private final List<String> network = new ArrayList<>();
    private final List<Path> onceReads = new ArrayList<>();
    private final List<Path> onceWrites = new ArrayList<>();
    private final List<String> onceNetwork = new ArrayList<>();
    private final Set<Command> commands = ConcurrentHashMap.newKeySet();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition networkApprovalCompleted = lock.newCondition();
    private final Set<NetworkApproval> pendingNetworkApprovals = new HashSet<>();
    private LinuxSandbox linux;
    private boolean closed;
    private long generation;

    public SessionSandbox(
            final SandboxConfiguration configuration,
            final Path workdir,
            final Path projectDir,
            final PermissionService permissions,
            final Path tempDir) {
        Objects.requireNonNull(configuration, "sandbox configuration");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.project = projectDir;
        this.workdir = workdir.toAbsolutePath().normalize();
        enabled = configuration.enabled();
        helperExecutable = Objects.requireNonNull(configuration.helper().executable(), "helper.executable");
        experimentalMacOs = configuration.macOs().experimental();
        environment = Objects.requireNonNull(configuration.environment(), "environment");
        socketBridges = List.copyOf(configuration.unix().socketBridges());
        scratch = Objects.requireNonNull(tempDir, "tempDir").toAbsolutePath().normalize();
        final var fs = configuration.filesystem();
        paths = new SandboxPaths(
                fs.allowRead(), fs.allowWrite(), fs.denyRead(), fs.denyWrite(), workdir, projectDir, scratch);
        if (enabled) {
            paths.exposureRoots(true).forEach(this::rejectUnsafeWriteRoot);
        }
        domains = configuration.network().allowedDomains();
        // Validate destinations at construction, before accepting a user turn.
        new SandboxDestinations(domains);
        try {
            if (enabled) {
                if (!Files.exists(scratch.getParent(), LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(
                            scratch.getParent(),
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                }
                if (Files.isSymbolicLink(scratch.getParent())
                        || !Files.isDirectory(scratch.getParent(), LinkOption.NOFOLLOW_LINKS)
                        || !Files.getOwner(scratch.getParent())
                                .equals(Files.getOwner(Path.of(System.getProperty("user.home"))))
                        || Files.getPosixFilePermissions(scratch.getParent())
                                .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
                    throw new IOException("Unsafe sandbox scratch parent: " + scratch.getParent());
                }
                secureDirectory(scratch);
                control = Files.createTempDirectory(
                        scratch.getParent(),
                        "yca-sandbox-",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                control = null;
            }
        } catch (final IOException error) {
            throw new IllegalStateException("Cannot initialize private sandbox directories", error);
        }
    }

    @Override
    public boolean enabled() {
        return enabled(permissions);
    }

    private boolean enabled(final PermissionService approvalPermissions) {
        // A borrowed view may narrow, but must never widen, its owner's boundary.
        return enabled
                && (permissions.permissionMode() != PermissionMode.FULL_YOLO
                        || approvalPermissions.permissionMode() != PermissionMode.FULL_YOLO);
    }

    @Override
    public Path tempDir() {
        return scratch;
    }

    @Override
    public void requireRead(final Path path) {
        requirePath(path, false, permissions);
    }

    @Override
    public void requireWrite(final Path path) {
        requirePath(path, true, permissions);
    }

    private void requirePath(final Path path, final boolean write, final PermissionService approvalPermissions) {
        final long requestedGeneration;
        lock.lock();
        try {
            ensureOpen();
            if (!enabled(approvalPermissions)) {
                return;
            }
            requestedGeneration = generation;
            rejectHardDeny(path, write);
            final var canonical = SandboxPaths.canonical(path);
            if (paths.allowed(path, write)
                    || granted(canonical, write ? writes : reads)
                    || (!write && granted(canonical, writes))) {
                return;
            }
        } finally {
            lock.unlock();
        }
        final var result =
                approve("filesystem", path.toAbsolutePath().normalize().toString(), write, approvalPermissions);
        if (result == ApprovalGate.Result.ALLOW_SESSION) {
            addPath(path, write, false, requestedGeneration);
        } else {
            checkGeneration(requestedGeneration);
        }
        // ONCE authorizes this operation only, not the next tool or a subsequent shell launch.
    }

    @Override
    public void requireNetwork(final URI uri) {
        requireNetwork(uri, permissions);
    }

    private void requireNetwork(final URI uri, final PermissionService approvalPermissions) {
        final long requestedGeneration;
        final String domain;
        lock.lock();
        try {
            ensureOpen();
            if (!enabled(approvalPermissions)) {
                return;
            }
            requestedGeneration = generation;
            domain = SandboxDestinations.host(uri) + ":" + SandboxDestinations.port(uri);
        } finally {
            lock.unlock();
        }
        // DNS and user interaction must not hold the lock needed to revoke a session.
        try {
            new SandboxDestinations(List.of(domain)).resolve(uri);
        } catch (final IOException error) {
            throw new SecurityException("Sandbox destination resolution failed", error);
        }
        lock.lock();
        try {
            final var pending = new NetworkApproval(domain, requestedGeneration);
            while (true) {
                checkGeneration(requestedGeneration);
                if (new SandboxDestinations(combinedDomains(false)).allows(uri)) {
                    return;
                }
                final var rules = onceNetwork.iterator();
                while (rules.hasNext()) {
                    final var rule = rules.next();
                    if (new SandboxDestinations(List.of(rule)).allows(uri)) {
                        rules.remove();
                        return;
                    }
                }
                if (pendingNetworkApprovals.add(pending)) {
                    break;
                }
                try {
                    networkApprovalCompleted.await();
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new SecurityException("Sandbox network approval interrupted", error);
                }
            }
        } finally {
            lock.unlock();
        }
        try {
            final var result = approve("network", domain, false, approvalPermissions);
            lock.lock();
            try {
                checkGeneration(requestedGeneration);
                if (result == ApprovalGate.Result.ALLOW_SESSION) {
                    network.add(domain);
                }
                // ALLOW_ONCE authorizes only this operation, not any waiting callers.
            } finally {
                lock.unlock();
            }
        } finally {
            lock.lock();
            try {
                pendingNetworkApprovals.remove(new NetworkApproval(domain, requestedGeneration));
                networkApprovalCompleted.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private record NetworkApproval(String domain, long generation) {}

    @Override
    public void request(final String kind, final String target, final boolean write) {
        request(kind, target, write, permissions);
    }

    private void request(
            final String kind, final String target, final boolean write, final PermissionService approvalPermissions) {
        if (!List.of("filesystem", "network", "revoke").contains(kind)) {
            throw new IllegalArgumentException("Sandbox access kind must be filesystem, network or revoke");
        }
        if (!enabled(approvalPermissions)) {
            return;
        }
        if ("revoke".equals(kind)) {
            revoke();
            return;
        }
        final var requestedGeneration = currentGeneration();
        if ("filesystem".equals(kind)) {
            final var path = (Path.of(target).isAbsolute() ? Path.of(target) : project.resolve(target)).normalize();
            lock.lock();
            try {
                checkGeneration(requestedGeneration);
                rejectHardDeny(path, write);
                final var canonical = SandboxPaths.canonical(path);
                if (granted(canonical, write ? writes : reads) || (!write && granted(canonical, writes))) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            final var result = approve(kind, path.toString(), write, approvalPermissions);
            addPath(path, write, result == ApprovalGate.Result.ALLOW_ONCE, requestedGeneration);
        } else if ("network".equals(kind)) {
            final var domain = SandboxDestinations.normalizeRule(target);
            lock.lock();
            try {
                checkGeneration(requestedGeneration);
                if (network.stream()
                        .anyMatch(rule ->
                                rule.equals(domain) || (rule.startsWith("*.") && domain.endsWith(rule.substring(1))))) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            final var result = approve(kind, domain, false, approvalPermissions);
            lock.lock();
            try {
                ensureOpen();
                if (generation != requestedGeneration) {
                    throw new SecurityException("Sandbox approval expired during revocation");
                }
                (result == ApprovalGate.Result.ALLOW_ONCE ? onceNetwork : network).add(domain);
            } finally {
                lock.unlock();
            }
        } else {
            throw new IllegalArgumentException("Sandbox access kind must be filesystem, network or revoke");
        }
    }

    private ApprovalGate.Result approve(
            final String kind, final String target, final boolean write, final PermissionService approvalPermissions) {
        final var result = approvalPermissions.approveSandbox(
                "Open sandbox " + (write ? "read/write" : "read") + " access?",
                kind + ": " + target + ". Tool consent and permission bypass do not grant sandbox access.",
                Map.of("kind", kind, "target", target, "write", write));
        if (result == null || result == ApprovalGate.Result.DENY) {
            throw new SecurityException("Sandbox access denied: " + kind + ": " + target);
        }
        return result;
    }

    private void addPath(final Path path, final boolean write, final boolean once, final long requestedGeneration) {
        lock.lock();
        try {
            ensureOpen();
            if (generation != requestedGeneration) {
                throw new SecurityException("Sandbox approval expired during revocation");
            }
            rejectHardDeny(path, write);
            final var canonical = SandboxPaths.canonical(path);
            (once ? (write ? onceWrites : onceReads) : (write ? writes : reads)).add(canonical);
        } finally {
            lock.unlock();
        }
    }

    private long currentGeneration() {
        lock.lock();
        try {
            ensureOpen();
            return generation;
        } finally {
            lock.unlock();
        }
    }

    private void checkGeneration(final long expected) {
        lock.lock();
        try {
            ensureOpen();
            if (generation != expected) {
                throw new SecurityException("Sandbox approval expired during revocation");
            }
        } finally {
            lock.unlock();
        }
    }

    private void rejectUnsafeWriteRoot(final Path path) {
        final var actual = SandboxPaths.canonical(path);
        final var home = SandboxPaths.canonical(Path.of(System.getProperty("user.home")));
        if (home.startsWith(actual)
                || actual.equals(Path.of("/tmp"))
                || List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/proc", "/sys", "/dev", "/run", "/boot")
                        .stream()
                        .map(Path::of)
                        .anyMatch(actual::startsWith)) {
            throw new SecurityException("Sandbox refuses a broad home, temporary or system write root: " + path);
        }
    }

    private void rejectHardDeny(final Path path, final boolean write) {
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new SecurityException("Sandbox paths must be absolute and normalized: " + path);
        }
        final var actual = SandboxPaths.canonical(path);
        if (paths.denied(path, write)
                || (control != null && (actual.startsWith(control) || control.startsWith(actual)))) {
            throw new SecurityException("Sandbox hard deny (approval cannot override): " + path);
        }
        if (write) {
            rejectUnsafeWriteRoot(path);
        }
    }

    @Override
    public Command launch(final ProcessBuilder builder) throws IOException {
        return launch(builder, permissions);
    }

    private Command launch(final ProcessBuilder builder, final PermissionService approvalPermissions)
            throws IOException {
        final long requestedGeneration;
        lock.lock();
        try {
            ensureOpen();
            requestedGeneration = generation;
            if (!enabled(approvalPermissions)) {
                return launchUnconfined(builder);
            }
        } finally {
            lock.unlock();
        }
        requirePath(builder.directory().toPath(), false, approvalPermissions);
        lock.lock();
        try {
            ensureOpen();
            checkGeneration(requestedGeneration);
            if (!enabled(approvalPermissions)) {
                return launchUnconfined(builder);
            }
            final var snapshot = snapshot();
            final var reference = new AtomicReference<Command>();
            final Runnable released = () -> {
                final var current = reference.get();
                if (current != null) {
                    commands.remove(current);
                }
            };
            final Command handle;
            final var os = System.getProperty("os.name");
            if ("Linux".equals(os)) {
                if (linux == null) {
                    linux = new LinuxSandbox(control, helperExecutable);
                }
                handle = linux.launch(builder, snapshot, scratch, released, environment, socketBridges);
            } else if (List.of("Mac OS X", "Darwin").contains(os)) {
                if (!experimentalMacOs) {
                    throw new IOException("Native macOS sandbox requires explicit sandbox.macOs.experimental=true; "
                            + "experimental offline execution only, no host fallback");
                }
                if (!socketBridges.isEmpty()) {
                    throw new IOException("Sandbox Unix socket bridges are Linux-only; no host fallback");
                }
                if (!environment.inherit().isEmpty() || !environment.set().isEmpty()) {
                    throw new IOException(
                            "Custom sandbox command environment is not supported on macOS; no host fallback");
                }
                handle = macOs(snapshot).launch(builder, released, true);
            } else {
                throw new IOException("Native sandbox backend not qualified for " + os
                        + "; refusing execution, never falling back to the host shell");
            }
            reference.set(handle);
            commands.add(handle);
            handle.process().onExit().thenRun(() -> {
                handle.close();
                commands.remove(handle);
            });
            onceReads.clear();
            onceWrites.clear();
            onceNetwork.clear();
            return handle;
        } finally {
            lock.unlock();
        }
    }

    private MacOsSandbox macOs(final Snapshot snapshot) {
        final var rules = snapshot.rules();
        final var runtimeReads = new ArrayList<>(rules.get("read"));
        // Explicit OS runtime reads; these do not grant home directories or runtime configuration.
        for (final var runtime : List.of(
                "/bin",
                "/sbin",
                "/usr/bin",
                "/usr/sbin",
                "/usr/lib",
                "/System/Library",
                "/System/Volumes/Preboot/Cryptexes/OS/usr/lib",
                "/private/preboot/Cryptexes/OS/usr/lib",
                "/dev/null",
                "/dev/random",
                "/dev/urandom")) {
            if (Files.exists(Path.of(runtime))) {
                runtimeReads.add(runtime);
            }
        }
        final var denyReads = new ArrayList<>(rules.get("denyRead"));
        denyReads.add(control.toString());
        return new MacOsSandbox(
                workdir,
                project,
                scratch,
                runtimeReads,
                rules.get("write"),
                denyReads,
                rules.get("denyWrite"),
                snapshot.domains());
    }

    /** Called under the policy lock so a concurrent mode switch also owns this command's lifetime. */
    private Command launchUnconfined(final ProcessBuilder builder) throws IOException {
        final var handle = new SandboxCommand(builder.start(), () -> {});
        commands.add(handle);
        handle.process().onExit().thenRun(() -> {
            handle.close();
            commands.remove(handle);
        });
        return handle;
    }

    private Snapshot snapshot() {
        final var expanded = paths.mountSnapshot();
        final var read = new ArrayList<>(expanded.get("read"));
        read.addAll(reads);
        read.addAll(onceReads);
        final var write = new ArrayList<>(expanded.get("write"));
        write.addAll(writes);
        write.addAll(onceWrites);
        final var denyWrite = new ArrayList<>(expanded.get("denyWrite"));
        if (Files.exists(project.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            denyWrite.add(project.resolve(".git"));
        }
        final var rules = new java.util.HashMap<>(paths.rules());
        for (final var entry : Map.of("read", List.of(reads, onceReads), "write", List.of(writes, onceWrites))
                .entrySet()) {
            final var grantedRules = new ArrayList<>(rules.get(entry.getKey()));
            entry.getValue().forEach(grants -> grants.forEach(path -> grantedRules.add(path.toString())));
            rules.put(entry.getKey(), List.copyOf(grantedRules));
        }
        final var deniedWrites = new ArrayList<>(rules.get("denyWrite"));
        deniedWrites.add(project.resolve(".git").toString());
        rules.put("denyWrite", List.copyOf(deniedWrites));
        return new Snapshot(read, write, expanded.get("denyRead"), denyWrite, combinedDomains(true), Map.copyOf(rules));
    }

    private List<String> combinedDomains(final boolean includeOnce) {
        final var all = new ArrayList<>(domains);
        all.addAll(network);
        if (includeOnce) {
            all.addAll(onceNetwork);
        }
        return all;
    }

    private static boolean granted(final Path path, final List<Path> grants) {
        return grants.stream().anyMatch(path::startsWith);
    }

    @Override
    public void revoke() {
        lock.lock();
        try {
            generation++;
            commands.forEach(Command::close);
            commands.clear();
            reads.clear();
            writes.clear();
            network.clear();
            onceReads.clear();
            onceWrites.clear();
            onceNetwork.clear();
            networkApprovalCompleted.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            revoke();
            if (control != null) {
                try (final var files = Files.walk(control)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (final IOException ignored) {
                            // Private controller state remains inaccessible if the OS cannot remove it.
                        }
                    });
                } catch (final IOException ignored) {
                    // Already removed.
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Sandbox approvalView(final PermissionService approvalPermissions) {
        return new ApprovalView(Objects.requireNonNull(approvalPermissions, "permissions"));
    }

    /** Borrows policy, grants and command lifetime; never owns the session. */
    private final class ApprovalView implements Sandbox {
        private final PermissionService approvalPermissions;

        private ApprovalView(final PermissionService approvalPermissions) {
            this.approvalPermissions = approvalPermissions;
        }

        @Override
        public Sandbox approvalView(final PermissionService permissions) {
            return SessionSandbox.this.approvalView(permissions);
        }

        @Override
        public boolean enabled() {
            return SessionSandbox.this.enabled(approvalPermissions);
        }

        @Override
        public Path tempDir() {
            return scratch;
        }

        @Override
        public void requireRead(final Path path) {
            requirePath(path, false, approvalPermissions);
        }

        @Override
        public void requireWrite(final Path path) {
            requirePath(path, true, approvalPermissions);
        }

        @Override
        public void requireNetwork(final URI uri) {
            SessionSandbox.this.requireNetwork(uri, approvalPermissions);
        }

        @Override
        public void request(final String kind, final String target, final boolean write) {
            SessionSandbox.this.request(kind, target, write, approvalPermissions);
        }

        @Override
        public Command launch(final ProcessBuilder builder) throws IOException {
            return SessionSandbox.this.launch(builder, approvalPermissions);
        }

        @Override
        public void revoke() {
            SessionSandbox.this.revoke();
        }

        @Override
        public void close() {
            // Borrowed view: only the root session closes the boundary.
        }
    }

    private void ensureOpen() {

        if (closed) {
            throw new IllegalStateException("Sandbox session is closed");
        }
    }

    public static Path tempDirFor(final Path workdir) {
        return Path.of("/tmp/yca-work").resolve(workspaceId(workdir));
    }

    private static String workspaceId(final Path workdir) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(workdir.toAbsolutePath()
                                    .normalize()
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 24);
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void secureDirectory(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(
                    directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(directory).equals(Files.getOwner(Path.of(System.getProperty("user.home"))))
                || Files.getPosixFilePermissions(directory).stream()
                        .anyMatch(
                                p -> p.name().startsWith("OTHERS_") || p.name().startsWith("GROUP_"))) {
            throw new IOException(
                    "Sandbox scratch directory must be owned by the current user and mode 0700: " + directory);
        }
    }

    /** Immutable per-command capability snapshot; changes never widen a running command. */
    public record Snapshot(
            List<Path> read,
            List<Path> write,
            List<Path> denyRead,
            List<Path> denyWrite,
            List<String> domains,
            Map<String, List<String>> rules) {
        public Snapshot(
                final List<Path> read,
                final List<Path> write,
                final List<Path> denyRead,
                final List<Path> denyWrite,
                final List<String> domains) {
            this(
                    read,
                    write,
                    denyRead,
                    denyWrite,
                    domains,
                    Map.of(
                            "read", read.stream().map(Path::toString).toList(),
                            "write", write.stream().map(Path::toString).toList(),
                            "denyRead", denyRead.stream().map(Path::toString).toList(),
                            "denyWrite", denyWrite.stream().map(Path::toString).toList()));
        }

        public Snapshot {
            read = List.copyOf(read);
            write = List.copyOf(write);
            denyRead = List.copyOf(denyRead);
            denyWrite = List.copyOf(denyWrite);
            domains = List.copyOf(domains);
            rules = rules.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        }
    }
}
