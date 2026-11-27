/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import io.yupiik.ia.agent.core.configuration.SandboxEnvironmentConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxUnixSocketBridgeConfiguration;
import io.yupiik.ia.agent.core.sandbox.helper.HelperMain;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

/** Linux namespace launcher. A missing dependency or unsupported policy is fatal, never a fallback. */
public final class LinuxSandbox {
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final Path helper;
    private final Path control;
    private final boolean nativeHelper;

    public LinuxSandbox(final Path control, final String configuredHelper) throws IOException {
        this.control = control;
        nativeHelper = !configuredHelper.isBlank() || System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        helper = control.resolve(nativeHelper ? "sandbox-helper" : "sandbox-helper.jar");
        if (nativeHelper) {
            final var source = configuredHelper.isBlank()
                    ? Path.of(ProcessHandle.current()
                                    .info()
                                    .command()
                                    .orElseThrow(() -> new IOException("Cannot locate native YCA executable")))
                            .resolveSibling("sandbox-helper")
                    : Path.of(configuredHelper);
            if (!source.isAbsolute()
                    || !source.equals(source.normalize())
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isExecutable(source)
                    || Files.getPosixFilePermissions(source).stream()
                            .anyMatch(p ->
                                    p == PosixFilePermission.GROUP_WRITE || p == PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("Unsafe or missing trusted sandbox helper executable: " + source);
            }
            Files.copy(source, helper);
            Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("r-x------"));
        } else {
            try (final var jar = new JarOutputStream(Files.newOutputStream(helper))) {
                for (final var resource : HelperMain.CLASS_RESOURCES) {
                    try (final var bytes = LinuxSandbox.class.getClassLoader().getResourceAsStream(resource)) {
                        if (bytes == null) {
                            throw new IOException("Packaged FFM sandbox helper class is missing: " + resource);
                        }
                        jar.putNextEntry(new JarEntry(resource));
                        bytes.transferTo(jar);
                        jar.closeEntry();
                    }
                }
            }
            Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("r--------"));
        }
        final var settings = control.resolve("maven-settings.xml");
        Files.writeString(settings, """
        <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
          <proxies><proxy><id>yca-sandbox</id><active>true</active><protocol>http</protocol>
            <host>127.0.0.1</host><port>3128</port>
          </proxy></proxies>
        </settings>
        """);
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("r--------"));
    }

    public Sandbox.Command launch(
            final ProcessBuilder original,
            final SessionSandbox.Snapshot snapshot,
            final Path scratch,
            final Runnable released)
            throws IOException {
        return launch(
                original,
                snapshot,
                scratch,
                released,
                new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                List.of());
    }

    /** Explicit socket peers are trusted capabilities outside the filesystem/network policy boundary. */
    public Sandbox.Command launch(
            final ProcessBuilder original,
            final SessionSandbox.Snapshot snapshot,
            final Path scratch,
            final Runnable released,
            final SandboxEnvironmentConfiguration environment,
            final List<SandboxUnixSocketBridgeConfiguration> bridges)
            throws IOException {
        final var tools = toolchains(original.environment());
        final var env = environment(original.environment(), scratch, tools);
        final var commandEnvironment = commandEnvironment(env, original.environment(), environment);
        validateBridges(bridges);
        final var implicit = new ArrayList<Path>();
        for (final var system : List.of(
                "/usr",
                "/bin",
                "/sbin",
                "/lib",
                "/lib64",
                "/etc/ld.so.cache",
                "/etc/alternatives",
                "/etc/ssl/certs",
                "/etc/passwd",
                "/etc/group",
                "/etc/nsswitch.conf",
                "/etc/localtime")) {
            if (Files.exists(Path.of(system))) {
                implicit.add(Path.of(system));
            }
        }
        implicit.addAll(tools);
        validateMounts(snapshot, implicit);
        final var proxyPath =
                control.resolve("p-" + UUID.randomUUID().toString().substring(0, 8));
        final var proxy = new SandboxProxy(proxyPath, new SandboxDestinations(snapshot.domains()));
        Path environmentFile = null;
        try {
            environmentFile = Files.createTempFile(
                    control,
                    "command-",
                    ".env",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(environmentFile, commandEnvironment);
            Files.setPosixFilePermissions(environmentFile, PosixFilePermissions.fromString("r--------"));
            final var home = scratch.resolve("home");
            Files.createDirectories(home);
            for (final var writable : snapshot.write()) {
                if (!Files.exists(writable)) {
                    Files.createDirectories(writable);
                }
            }
            final var args = new ArrayList<>(List.of(
                    "/usr/bin/bwrap",
                    "--unshare-all",
                    "--unshare-user",
                    "--disable-userns",
                    "--die-with-parent",
                    "--new-session",
                    "--cap-drop",
                    "ALL",
                    "--clearenv",
                    "--proc",
                    "/proc",
                    "--dev",
                    "/dev",
                    "--tmpfs",
                    "/tmp"));
            for (final var system : implicit) {
                bind(args, system, system.toString(), false);
            }
            final var mounts = new ArrayList<Mount>();
            snapshot.read().forEach(p -> mounts.add(new Mount(p, false)));
            snapshot.write().forEach(p -> mounts.add(new Mount(p, true)));
            // Parent mounts first, then narrower restrictions; a broad write must not hide a narrow read.
            mounts.sort(Comparator.comparingInt((Mount m) -> m.path().getNameCount())
                    .thenComparing(Mount::write));
            for (final var mount : mounts) {
                final var root = mount.path();
                if (!Files.exists(root)) {
                    if (mount.write()) {
                        Files.createDirectories(root);
                    } else {
                        continue;
                    }
                }
                requireUnaliased(root);
                final var actual = SandboxPaths.canonical(root);
                bind(args, actual, root.toString(), mount.write());
            }
            for (final var denial : snapshot.denyWrite()) {
                if (Files.exists(denial, LinkOption.NOFOLLOW_LINKS)) {
                    for (final var writable : snapshot.write()) {
                        // A denial above a grant must narrow that grant, never expose its siblings.
                        final var restricted = writable.startsWith(denial) ? writable : denial;
                        if (restricted.startsWith(writable) && Files.exists(restricted, LinkOption.NOFOLLOW_LINKS)) {
                            bind(args, restricted, restricted.toString(), false);
                        }
                    }
                }
            }
            for (final var denial : snapshot.denyRead()) {
                if (Files.exists(denial, LinkOption.NOFOLLOW_LINKS)
                        && (covered(denial, snapshot.read()) || covered(denial, snapshot.write()))) {
                    mask(args, denial);
                }
            }
            // Controller resources never become accessible through a broad workspace grant.
            mask(args, control);
            args.addAll(List.of(
                    "--dir",
                    "/yca",
                    "--ro-bind",
                    helper.toString(),
                    nativeHelper ? "/yca/helper" : "/yca/helper.jar",
                    "--ro-bind",
                    proxyPath.toString(),
                    "/yca/proxy"));
            if (!nativeHelper) {
                args.addAll(List.of(
                        "--ro-bind",
                        SandboxPaths.canonical(Path.of(System.getProperty("java.home")))
                                .toString(),
                        "/yca/jdk"));
            }
            args.addAll(
                    List.of("--ro-bind", control.resolve("maven-settings.xml").toString(), "/yca/maven-settings.xml"));
            bind(args, environmentFile, "/yca/command.env", false);
            for (var index = 0; index < bridges.size(); index++) {
                bind(args, Path.of(bridges.get(index).path()), "/yca/unix-" + index + ".sock", false);
            }
            env.forEach((key, value) -> args.addAll(List.of("--setenv", key, value)));
            args.addAll(List.of("--chdir", original.directory().toString(), "--"));
            if (nativeHelper) {
                args.add("/yca/helper");
            } else {
                args.addAll(List.of(
                        "/yca/jdk/bin/java",
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        "/yca/helper.jar",
                        HelperMain.class.getName()));
            }
            args.add("/yca/proxy");
            for (var index = 0; index < bridges.size(); index++) {
                args.addAll(List.of(
                        "--socket-bridge",
                        Integer.toString(bridges.get(index).port()),
                        "/yca/unix-" + index + ".sock"));
            }
            args.addAll(List.of("--command-environment", "/yca/command.env"));
            args.add("--");
            args.addAll(original.command());
            final var builder = new ProcessBuilder(args).redirectErrorStream(original.redirectErrorStream());
            builder.environment().clear();
            builder.environment().put("PATH", "/usr/bin:/bin");
            final var ownedEnvironmentFile = environmentFile;
            final var handle = new SandboxCommand(builder.start(), () -> {
                try {
                    proxy.close();
                } finally {
                    try {
                        deleteEnvironmentFile(ownedEnvironmentFile);
                    } finally {
                        released.run();
                    }
                }
            });
            handle.process().onExit().thenRun(handle::close);
            return handle;
        } catch (final IOException | RuntimeException failure) {
            proxy.close();
            deleteEnvironmentFile(environmentFile);
            throw failure;
        }
    }

    private static void deleteEnvironmentFile(final Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (final IOException error) {
                throw new UncheckedIOException("Cannot remove private command environment", error);
            }
        }
    }

    private static byte[] commandEnvironment(
            final Map<String, String> base,
            final Map<String, String> ambient,
            final SandboxEnvironmentConfiguration configured)
            throws IOException {
        final var reserved = new LinkedHashSet<>(base.keySet());
        // JAVA_HOME is launcher-owned even if the ambient JDK was absent. Aliases must not
        // redirect temporary storage or bypass the proxy policy either.
        reserved.addAll(List.of("JAVA_HOME", "TMP", "TEMP", "ALL_PROXY", "all_proxy"));
        final var result = new LinkedHashMap<>(base);
        for (final var name : configured.inherit()) {
            validateEnvironmentName(name, reserved);
            final var value = ambient.get(name);
            if (value != null) {
                validateEnvironmentValue(value);
                result.put(name, value);
            }
        }
        configured.set().forEach((name, value) -> {
            validateEnvironmentName(name, reserved);
            validateEnvironmentValue(value);
            result.put(name, value);
        });
        if (result.size() > 4096) {
            throw new IllegalArgumentException("Command environment exceeds 4096 entries");
        }
        final var bytes = new ByteArrayOutputStream();
        try (final var output = new DataOutputStream(bytes)) {
            output.writeInt(result.size());
            for (final var entry : result.entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeUTF(entry.getValue());
                if (bytes.size() > 1024 * 1024) {
                    throw new IllegalArgumentException("Command environment exceeds 1 MiB");
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void validateEnvironmentName(final String name, final Set<String> reserved) {
        if (name == null || !ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid command environment variable name: " + name);
        }
        if (reserved.contains(name)) {
            throw new IllegalArgumentException("Launcher-owned command environment variable: " + name);
        }
    }

    private static void validateEnvironmentValue(final String value) {
        if (value == null || value.contains("\0")) {
            throw new IllegalArgumentException("Command environment values must be non-null and contain no NUL");
        }
    }

    private static void validateBridges(final List<SandboxUnixSocketBridgeConfiguration> bridges) throws IOException {
        if (bridges.size() > 32) {
            throw new IllegalArgumentException("At most 32 Unix socket bridges are supported");
        }
        final var ports = new LinkedHashSet<Integer>();
        final var paths = new LinkedHashSet<Path>();
        for (final var bridge : bridges) {
            if (bridge.port() < 1024 || bridge.port() > 65535 || bridge.port() == 3128 || !ports.add(bridge.port())) {
                throw new IllegalArgumentException("Invalid or duplicate Unix socket bridge port: " + bridge.port());
            }
            final var path = Path.of(bridge.path());
            requireUnaliased(path);
            if (!paths.add(path)) {
                throw new IllegalArgumentException("Duplicate Unix socket bridge path: " + path);
            }
            // S_IFMT / S_IFSOCK rejects regular files, devices and absent paths.
            final var mode = (int) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            if ((mode & 0170000) != 0140000) {
                throw new SecurityException("Configured bridge endpoint is not a Unix socket: " + path);
            }
            // A socket inode alone does not distinguish stream from datagram/seqpacket.
            // Probe using a nonblocking stream connect, without sending any application data.
            try (final var socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                socket.configureBlocking(false);
                socket.connect(UnixDomainSocketAddress.of(path));
            }
        }
    }

    private static void validateMounts(final SessionSandbox.Snapshot snapshot, final List<Path> implicit) {
        for (final var roots : List.of(snapshot.read(), snapshot.write(), snapshot.denyRead(), snapshot.denyWrite())) {
            roots.forEach(LinuxSandbox::requireUnaliased);
        }
        for (final var roots : List.of(snapshot.read(), snapshot.write(), implicit)) {
            for (final var root : roots) {
                final var actual = SandboxPaths.canonical(root);
                if (actual.equals(Path.of("/"))
                        || actual.equals(Path.of("/home"))
                        || actual.equals(SandboxPaths.canonical(Path.of(System.getProperty("user.home"))))) {
                    throw new SecurityException("Broad sandbox mount is forbidden: " + root);
                }
            }
        }
        for (final var root : snapshot.write()) {
            if (implicit.stream()
                    .map(SandboxPaths::canonical)
                    .anyMatch(p -> root.startsWith(p) || p.startsWith(root))) {
                throw new SecurityException("Writable root intersects a trusted runtime distribution: " + root);
            }
            if (List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/proc", "/sys", "/dev", "/run").stream()
                            .map(Path::of)
                            .anyMatch(root::startsWith)
                    || List.of("/tmp", "/var", "/opt", "/srv", "/mnt", "/media").contains(root.toString())) {
                throw new SecurityException("Sandbox system roots are never writable: " + root);
            }
        }
        // Implicit distributions can have aliases such as /bin -> /usr/bin. Until
        // every exposure is mediated, reject intersections instead of skipping denies.
        for (final var denials : List.of(snapshot.denyRead())) {
            for (final var denial : denials) {
                if (covered(denial, implicit)
                        || covered(denial, List.of(Path.of("/proc"), Path.of("/dev"), Path.of("/yca")))
                        || implicit.stream()
                                .map(SandboxPaths::canonical)
                                .anyMatch(root -> denial.startsWith(root) || root.startsWith(denial))) {
                    throw new SecurityException("Denial intersects an implicit sandbox mount: " + denial);
                }
            }
        }
    }

    private static void requireUnaliased(final Path root) {
        if (!root.isAbsolute() || !root.equals(root.normalize()) || !root.equals(SandboxPaths.canonical(root))) {
            throw new SecurityException("Symlink or noncanonical sandbox policy root is unsupported: " + root);
        }
    }

    private static boolean covered(final Path denial, final List<Path> roots) {
        return roots.stream().anyMatch(root -> denial.startsWith(root) || root.startsWith(denial));
    }

    private static void mask(final List<String> args, final Path path) {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            args.addAll(List.of("--tmpfs", path.toString(), "--remount-ro", path.toString()));
        } else {
            args.addAll(List.of("--ro-bind", "/dev/null", path.toString()));
        }
    }

    private static void bind(final List<String> args, final Path source, final String target, final boolean write) {
        args.addAll(List.of(write ? "--bind" : "--ro-bind", source.toString(), target));
    }

    private static Set<Path> toolchains(final Map<String, String> inherited) {
        final var roots = new LinkedHashSet<Path>();
        final var javaHome = inherited.get("JAVA_HOME");
        if (javaHome != null && Files.isDirectory(Path.of(javaHome))) {
            roots.add(SandboxPaths.canonical(Path.of(javaHome)));
        }
        // Version-manager installations are read-only executable distributions, not their configuration
        // roots.
        final var path = inherited.getOrDefault("PATH", "");
        for (final var component : path.split(":")) {
            if (component.isBlank()) {
                continue;
            }
            final var bin = Path.of(component);
            if (!bin.isAbsolute() || !Files.isDirectory(bin)) {
                continue;
            }
            final var real = SandboxPaths.canonical(bin);
            final var text = real.toString();
            if (text.contains("/.sdkman/candidates/java/")
                    || text.contains("/.sdkman/candidates/maven/")
                    || text.contains("/.nvm/versions/node/")) {
                roots.add(real.getParent());
            }
        }
        return roots;
    }

    private static Map<String, String> environment(
            final Map<String, String> inherited, final Path scratch, final Set<Path> tools) {
        final var env = new LinkedHashMap<String, String>();
        final var path = new StringBuilder();
        tools.forEach(root -> path.append(root.resolve("bin")).append(':'));
        env.put("PATH", path + "/usr/local/bin:/usr/bin:/bin");
        env.put("HOME", scratch.resolve("home").toString());
        env.put("TMPDIR", scratch.toString());
        env.put("YCA_TEMPDIR", scratch.toString());
        env.put("XDG_CACHE_HOME", scratch.resolve("cache").toString());
        env.put("GRADLE_USER_HOME", scratch.resolve("gradle").toString());
        env.put("npm_config_cache", scratch.resolve("npm").toString());
        env.put("CARGO_HOME", scratch.resolve("cargo").toString());
        env.put("GOCACHE", scratch.resolve("go-build").toString());
        env.put("GOMODCACHE", scratch.resolve("go-mod").toString());
        env.put("PIP_CACHE_DIR", scratch.resolve("pip").toString());
        env.put("HTTPS_PROXY", "http://127.0.0.1:3128");
        env.put("https_proxy", "http://127.0.0.1:3128");
        env.put("HTTP_PROXY", "http://127.0.0.1:3128");
        env.put("http_proxy", "http://127.0.0.1:3128");
        env.put("MAVEN_ARGS", "--settings /yca/maven-settings.xml");
        env.put("LANG", "C.UTF-8");
        env.put(
                "MAVEN_OPTS",
                "-Duser.home=" + scratch.resolve("home")
                        + " -Dmaven.repo.local=" + scratch.resolve("maven-repository")
                        + " -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128 -Dhttp.proxyHost=127.0.0.1"
                        + " -Dhttp.proxyPort=3128");
        final var javaHome = inherited.get("JAVA_HOME");
        if (javaHome != null && Files.isDirectory(Path.of(javaHome))) {
            env.put("JAVA_HOME", SandboxPaths.canonical(Path.of(javaHome)).toString());
        }
        return env;
    }

    private record Mount(Path path, boolean write) {}
}
