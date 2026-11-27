/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.SandboxConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxEnvironmentConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxHelperConfiguration;
import io.yupiik.ia.agent.core.configuration.SandboxUnixSocketBridgeConfiguration;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("restricted")
@FusionSupport(modules = ConfigurationTestModule.class)
class LinuxSandboxAcceptanceTest {
    @TempDir
    Path root;

    private Path project;
    private Path scratch;
    private LinuxSandbox backend;

    @BeforeEach
    void prerequisites() throws Exception {
        assumeTrue("Linux".equals(System.getProperty("os.name")), "Linux backend only");
        assumeTrue(
                Files.isExecutable(Path.of("/usr/bin/bwrap")) && Files.isExecutable(Path.of("/bin/bash")),
                "bubblewrap and bash required");
        assumeTrue(
                List.of("amd64", "x86_64", "aarch64").contains(System.getProperty("os.arch")),
                "seccomp profile requires x86_64 or aarch64");
        final var java = Path.of(System.getProperty("java.home")).toRealPath().resolve("bin/java");
        assumeTrue(Files.isExecutable(java), "current JDK required");
        final var actions = Path.of("/proc/sys/kernel/seccomp/actions_avail");
        assumeTrue(Files.isReadable(actions), "kernel seccomp filter support required");
        final var supported = List.of(Files.readString(actions).trim().split("\\s+"));
        assumeTrue(
                supported.containsAll(List.of("allow", "errno", "kill_process")), "kernel seccomp actions unavailable");
        final var probe = new ProcessBuilder(
                        "/usr/bin/bwrap",
                        "--unshare-all",
                        "--unshare-user",
                        "--disable-userns",
                        "--die-with-parent",
                        "--ro-bind",
                        "/usr",
                        "/usr",
                        "--ro-bind",
                        "/bin",
                        "/bin",
                        "--ro-bind",
                        "/lib",
                        "/lib",
                        "--ro-bind",
                        "/lib64",
                        "/lib64",
                        "--",
                        "/bin/true")
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(probe.waitFor(10, TimeUnit.SECONDS), "namespace prerequisite probe timed out");
            final var diagnostic = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assumeTrue(probe.exitValue() == 0, "user/network/PID namespaces unavailable: " + diagnostic);
        } finally {
            probe.destroyForcibly();
            probe.waitFor();
        }
        final var control = Files.createDirectory(root.resolve("controller"));
        final var configuredHelper = System.getProperty("yca.test.sandbox.helper.executable", "");
        backend = new LinuxSandbox(control, configuredHelper);
        final var helperCommand = configuredHelper.isBlank()
                ? List.of(
                        java.toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-cp",
                        control.resolve("sandbox-helper.jar").toString(),
                        "io.yupiik.ia.agent.core.sandbox.helper.HelperMain",
                        "--probe")
                : List.of(control.resolve("sandbox-helper").toString(), "--probe");
        final var filter =
                new ProcessBuilder(helperCommand).redirectErrorStream(true).start();
        try {
            assertTrue(filter.waitFor(10, TimeUnit.SECONDS), "seccomp helper probe timed out");
            assertEquals(
                    0, filter.exitValue(), new String(filter.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            filter.destroyForcibly();
            filter.waitFor();
        }
        project = Files.createDirectory(root.resolve("project"));
        scratch = Files.createDirectory(root.resolve("scratch"));
    }

    @Test
    void unchangedDefaultsBuildJavaAndProtectExistingSecretsAndInstructions(
            @Fusion final CodingAgentConfiguration configuration) throws Exception {
        Files.writeString(project.resolve(".env"), "root-secret");
        final var nested = Files.createDirectories(project.resolve("nested"));
        Files.writeString(nested.resolve(".env.local"), "nested-secret");
        Files.writeString(project.resolve("AGENTS.md"), "root-instructions");
        Files.writeString(nested.resolve("AGENTS.md"), "nested-instructions");
        Files.createDirectory(project.resolve(".git"));
        Files.writeString(project.resolve(".git/config"), "git-config");
        Files.writeString(project.resolve("Main.java"), """
        public class Main {
            public static void main(String[] args) { System.out.println("default-policy-build-ok"); }
        }
        """);
        Files.writeString(root.resolve("host-secret"), "outside");
        try (final var sandbox = defaultSandbox(configuration)) {
            final var script = "set -eu; test ! -s .env; test ! -s nested/.env.local; if (echo changed > AGENTS.md)"
                    + " 2>/dev/null; then exit 91; fi; if (echo changed > nested/AGENTS.md) 2>/dev/null;"
                    + " then exit 92; fi; if (echo changed > .git/config) 2>/dev/null; then exit 93; fi;"
                    + " test ! -e ../host-secret; mkdir -p target; javac -d target Main.java; jar"
                    + " --create --file target/app.jar --main-class Main -C target Main.class; java -jar"
                    + " target/app.jar";
            assertEquals("default-policy-build-ok\n", run(sandbox, script));
            assertTrue(Files.exists(project.resolve("target/app.jar")));
            assertEquals("root-secret", Files.readString(project.resolve(".env")));
            assertEquals("nested-secret", Files.readString(nested.resolve(".env.local")));
            assertEquals("root-instructions", Files.readString(project.resolve("AGENTS.md")));
            assertEquals("nested-instructions", Files.readString(nested.resolve("AGENTS.md")));
            assertFalse(Files.exists(project.resolve(".yca")), "absent denies must not create host placeholders");
        }
    }

    @Test
    void snapshotGlobDenialsRefreshOnNextCommandRatherThanClaimingRuntimeMediation(
            @Fusion final CodingAgentConfiguration configuration) throws Exception {
        try (final var sandbox = defaultSandbox(configuration)) {
            assertEquals("created-now\n", run(sandbox, "echo created-now > .env.future; cat .env.future"));
            assertEquals("", run(sandbox, "test ! -s .env.future; ! cat .env.future 2>/dev/null"));
            assertEquals("created-now\n", Files.readString(project.resolve(".env.future")));
        }
    }

    private SessionSandbox defaultSandbox(final CodingAgentConfiguration configured) {
        final var defaults = configured.sandbox();
        final var configuration = new SandboxConfiguration(
                defaults.enabled(),
                new SandboxHelperConfiguration(System.getProperty("yca.test.sandbox.helper.executable", "")),
                defaults.filesystem(),
                defaults.network(),
                defaults.trustedIntegrations(),
                defaults.macOs(),
                defaults.environment(),
                defaults.unix());
        final var permissions = new PermissionService(project) {};
        permissions.setGate(request -> {
            throw new AssertionError("Unexpected approval under default policy: " + request);
        });
        return new SessionSandbox(configuration, project, project, permissions, root.resolve("private-scratch"));
    }

    private String run(final Sandbox sandbox, final String script) throws Exception {
        try (final var command = sandbox.launch(new ProcessBuilder("/bin/bash", "-c", script)
                .directory(project.toFile())
                .redirectErrorStream(true))) {
            assertTrue(command.process().waitFor(30, TimeUnit.SECONDS), "default-policy command timed out");
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
            return output;
        }
    }

    @Test
    void hidesHostFilesSanitizesEnvironmentAndWritesOnlyGrantedRoots() throws Exception {
        final var secret = Files.writeString(root.resolve("host-secret"), "must-not-be-visible");
        final var builder = new ProcessBuilder(
                        "/bin/bash",
                        "-c",
                        "test ! -r '" + secret
                                + "' && test -z \"$YCA_TEST_SECRET\" && printf success > result && printf '%s'"
                                + " \"$HOME\"")
                .directory(project.toFile())
                .redirectErrorStream(true);
        builder.environment().put("YCA_TEST_SECRET", "must-not-inherit");
        try (final var command = backend.launch(builder, snapshot(List.of(), List.of()), scratch, () -> {})) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
            assertEquals(scratch.resolve("home").toString(), output);
            assertEquals("success", Files.readString(project.resolve("result")));
        }
    }

    @Test
    void explicitCommandEnvironmentIsLiteralAndNeverAHelperStartupEnvironment() throws Exception {
        final var builder = new ProcessBuilder("/bin/bash", "-c", """
        set -eu
        test "$INHERITED" = ambient
        test "$OVERRIDE" = configured
        test "${MISSING+x}" != x
        test "${SECRET+x}" != x
        test "$LITERAL" = '${HOME} $(touch injected)'
        test "$JAVA_TOOL_OPTIONS" = '--invalid-helper-jvm-option'
        test "$DYLD_INSERT_LIBRARIES" = final-command-only
        test "${DOCKER_HOST+x}" != x
        test "${NO_PROXY+x}" != x
        test "${no_proxy+x}" != x
        test ! -w /yca/command.env
        test ! -e injected
        echo command-environment-ok
        """)
                .directory(project.toFile())
                .redirectErrorStream(true);
        builder.environment()
                .putAll(Map.of(
                        "INHERITED",
                        "ambient",
                        "OVERRIDE",
                        "ambient",
                        "SECRET",
                        "hidden",
                        "DOCKER_HOST",
                        "tcp://ambient:2375",
                        "NO_PROXY",
                        "*",
                        "no_proxy",
                        "*"));
        builder.environment().remove("MISSING");
        final var environment = new SandboxEnvironmentConfiguration(
                List.of("INHERITED", "OVERRIDE", "MISSING"),
                Map.of(
                        "OVERRIDE",
                        "configured",
                        "LITERAL",
                        "${HOME} $(touch injected)",
                        "JAVA_TOOL_OPTIONS",
                        "--invalid-helper-jvm-option",
                        "DYLD_INSERT_LIBRARIES",
                        "final-command-only"));
        try (final var command =
                backend.launch(builder, snapshot(List.of(), List.of()), scratch, () -> {}, environment, List.of())) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
            assertEquals("command-environment-ok\n", output);
        }
        try (final var files = Files.list(root.resolve("controller"))) {
            assertEquals(
                    List.of(),
                    files.filter(p -> p.getFileName().toString().startsWith("command-"))
                            .toList());
        }
    }

    @Test
    void genericBridgesForwardTwoConfiguredPortsWithoutInjectingDockerEnvironment() throws Exception {
        final var first = root.resolve("first.sock");
        final var second = root.resolve("second.sock");
        try (final var firstListener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var secondListener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var selector = Selector.open()) {
            firstListener.bind(UnixDomainSocketAddress.of(first));
            secondListener.bind(UnixDomainSocketAddress.of(second));
            for (final var listener : List.of(firstListener, secondListener)) {
                listener.configureBlocking(false);
                listener.register(selector, SelectionKey.OP_ACCEPT);
            }
            final var builder = new ProcessBuilder("/bin/bash", "-c", """
          set -eu
          test "${DOCKER_HOST+x}" != x
          test "${NO_PROXY+x}" != x
          test "${no_proxy+x}" != x
          for port in 12345 23456; do
              exec 3<>/dev/tcp/127.0.0.1/$port
              printf _ping >&3
              read -r response <&3
              test "$response" = OK
              exec 3>&-
          done
          echo generic-bridges-ok
          """)
                    .directory(project.toFile())
                    .redirectErrorStream(true);
            try (final var command = backend.launch(
                    builder,
                    snapshot(List.of(), List.of()),
                    scratch,
                    () -> {},
                    new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                    List.of(
                            new SandboxUnixSocketBridgeConfiguration(first.toString(), 12345),
                            new SandboxUnixSocketBridgeConfiguration(second.toString(), 23456)))) {
                // Each configured endpoint receives one empty type-validation connection
                // followed by the actual bridged command connection.
                for (var remaining = 4; remaining > 0; ) {
                    assertTrue(selector.select(10000) > 0, "Generic relay did not reach peer");
                    final var ready = List.copyOf(selector.selectedKeys());
                    selector.selectedKeys().clear();
                    for (final var key : ready) {
                        try (final var peer = ((ServerSocketChannel) key.channel()).accept()) {
                            assertNotNull(peer);
                            final var request = ByteBuffer.allocate(5);
                            while (request.hasRemaining() && peer.read(request) != -1) {
                                // Consume the small request or the empty validation connection.
                            }
                            if (request.position() > 0) {
                                assertEquals("_ping", new String(request.array(), StandardCharsets.US_ASCII));
                                final var response = ByteBuffer.wrap("OK\n".getBytes(StandardCharsets.US_ASCII));
                                while (response.hasRemaining()) {
                                    peer.write(response);
                                }
                            }
                            remaining--;
                        }
                    }
                }
                assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
                final var output =
                        new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, command.process().exitValue(), output);
                assertEquals("generic-bridges-ok\n", output);
            }
        }
    }

    @Test
    void suppliesIsolatedMavenProxySettings() throws Exception {
        try (final var command = backend.launch(
                new ProcessBuilder(
                                "/bin/bash",
                                "-c",
                                "test \"$MAVEN_ARGS\" = '--settings /yca/maven-settings.xml' && "
                                        + "grep -q '<host>127.0.0.1</host>' /yca/maven-settings.xml && "
                                        + "grep -q '<port>3128</port>' /yca/maven-settings.xml && "
                                        + "test ! -w /yca/maven-settings.xml && echo proxy-configured")
                        .directory(project.toFile())
                        .redirectErrorStream(true),
                snapshot(List.of(), List.of()),
                scratch,
                () -> {})) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
            assertTrue(output.contains("proxy-configured"), output);
        }
    }

    @Test
    void deniesDirectNetworkAndHostUnixSocketCreation() throws Exception {
        final var commandLine = "if bash -c 'echo bypass >/dev/tcp/1.1.1.1/443' 2>/dev/null; then exit 91; fi; "
                + "test ! -S /run/user/$(id -u)/bus; echo isolated";
        try (final var command = backend.launch(
                new ProcessBuilder("/bin/bash", "-c", commandLine)
                        .directory(project.toFile())
                        .redirectErrorStream(true),
                snapshot(List.of(), List.of()),
                scratch,
                () -> {})) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
            assertTrue(output.contains("isolated"), output);
        }
    }

    @Test
    void mountsReadGrantAndMasksProtectedFile() throws Exception {
        final var shared = Files.createDirectory(root.resolve("shared"));
        Files.writeString(shared.resolve("public"), "visible");
        final var secret = Files.writeString(shared.resolve("secret"), "hidden");
        final var policy = new SessionSandbox.Snapshot(
                List.of(shared), List.of(project, scratch), List.of(secret), List.of(), List.of());
        try (final var command = backend.launch(
                new ProcessBuilder(
                                "/bin/bash",
                                "-c",
                                "cat '" + shared.resolve("public") + "'; test ! -s '" + secret + "'; ! touch '"
                                        + shared.resolve("forbidden") + "'")
                        .directory(project.toFile())
                        .redirectErrorStream(true),
                policy,
                scratch,
                () -> {})) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
            assertTrue(output.contains("visible"), output);
            assertFalse(Files.exists(shared.resolve("forbidden")));
        }
    }

    @Test
    void dockerRequiresExplicitSocketAndNeverEnablesDirectUnixConnections() throws Exception {
        final var socket = root.resolve("docker.sock");
        final var probe = project.resolve("docker-probe.jar");
        final var resource = DockerProbe.class.getName().replace('.', '/') + ".class";
        try (final var jar = new JarOutputStream(Files.newOutputStream(probe));
                final var bytes = DockerProbe.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(bytes, resource);
            jar.putNextEntry(new JarEntry(resource));
            bytes.transferTo(jar);
            jar.closeEntry();
        }
        try (final var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                final var selector = Selector.open()) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            listener.configureBlocking(false);
            listener.register(selector, SelectionKey.OP_ACCEPT);
            for (final var enabled : List.of(false, true)) {
                final var builder =
                        javaCommand("-cp", probe.toString(), DockerProbe.class.getName(), Boolean.toString(enabled));
                builder.environment().put("DOCKER_HOST", "tcp://untrusted-inherited:1234");
                try (final var command = backend.launch(
                        builder,
                        snapshot(List.of(), List.of()),
                        scratch,
                        () -> {},
                        new SandboxEnvironmentConfiguration(
                                List.of(), enabled ? Map.of("DOCKER_HOST", "tcp://127.0.0.1:2375") : Map.of()),
                        enabled
                                ? List.of(new SandboxUnixSocketBridgeConfiguration(socket.toString(), 2375))
                                : List.of())) {
                    if (enabled) {
                        assertTrue(selector.select(10000) > 0, "Socket validation did not connect");
                        try (final var validation = listener.accept()) {
                            assertNotNull(validation);
                            assertEquals(-1, validation.read(ByteBuffer.allocate(1)));
                        }
                        selector.selectedKeys().clear();
                        assertTrue(selector.select(10000) > 0, "Docker relay did not connect to configured peer");
                        try (final var peer = listener.accept()) {
                            assertNotNull(peer);
                            final var request = ByteBuffer.allocate(5);
                            while (request.hasRemaining()) {
                                assertTrue(peer.read(request) > 0);
                            }
                            assertEquals("_ping", new String(request.array(), StandardCharsets.US_ASCII));
                            final var response = ByteBuffer.wrap("OK".getBytes(StandardCharsets.US_ASCII));
                            while (response.hasRemaining()) {
                                peer.write(response);
                            }
                        }
                    }
                    assertTrue(command.process().waitFor(15, TimeUnit.SECONDS), "Docker probe timed out");
                    final var output =
                            new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(0, command.process().exitValue(), output);
                }
            }
        }
    }

    @Test
    void rejectsNonSocketAndAliasedDockerEndpoints() throws Exception {
        final var socket = root.resolve("docker.sock");
        try (final var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            final var alias = Files.createSymbolicLink(root.resolve("docker-alias"), socket);
            final var directoryAlias = Files.createSymbolicLink(root.resolve("directory-alias"), root);
            final var file = Files.writeString(root.resolve("not-a-socket"), "regular");
            final var missing = root.resolve("missing");
            for (final var invalid : List.of(
                    alias,
                    directoryAlias.resolve("docker.sock"),
                    file,
                    missing,
                    Path.of("relative.sock"),
                    root.resolve("project/../docker.sock"))) {
                final Class<? extends Exception> expected =
                        invalid.equals(missing) ? IOException.class : SecurityException.class;
                assertThrows(
                        expected,
                        () -> backend.launch(
                                new ProcessBuilder("/bin/true").directory(project.toFile()),
                                snapshot(List.of(), List.of()),
                                scratch,
                                () -> {},
                                new SandboxEnvironmentConfiguration(List.of(), Map.of()),
                                List.of(new SandboxUnixSocketBridgeConfiguration(invalid.toString(), 2375))));
            }
        }
    }

    @Test
    void closingKillsNamespaceWithoutWaitingForCommandCompletion() throws Exception {
        final var command = backend.launch(
                new ProcessBuilder("/bin/bash", "-c", "read -r input")
                        .directory(project.toFile())
                        .redirectErrorStream(true),
                snapshot(List.of(), List.of()),
                scratch,
                () -> {});
        final var process = command.process();
        command.close();
        assertFalse(process.isAlive());
        command.close();
    }

    @Test
    void blocksReconnectableUnixSocketPairsButAllowsAnonymousStreams() throws Exception {
        final var probe = project.resolve("socket-probe.jar");
        final var resource = SocketProbe.class.getName().replace('.', '/') + ".class";
        try (final var jar = new JarOutputStream(Files.newOutputStream(probe));
                final var bytes = SocketProbe.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(bytes, resource);
            jar.putNextEntry(new JarEntry(resource));
            bytes.transferTo(jar);
            jar.closeEntry();
        }
        try (final var command = backend.launch(
                javaCommand("--enable-native-access=ALL-UNNAMED", "-cp", probe.toString(), SocketProbe.class.getName()),
                snapshot(List.of(), List.of()),
                scratch,
                () -> {})) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
        }
    }

    @Test
    void startsJvmWithRestrictedUnixSocketPairs() throws Exception {
        final var builder = javaCommand("-version");
        try (final var command = backend.launch(builder, snapshot(List.of(), List.of()), scratch, () -> {})) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
            assertTrue(output.contains("version"), output);
        }
    }

    @Test
    void rejectsAliasedPolicyRootsRatherThanExposingUnmaskedAliases() throws Exception {
        final var real = Files.createDirectory(root.resolve("real"));
        final var secret = Files.writeString(real.resolve("secret"), "hidden");
        final var alias = Files.createSymbolicLink(root.resolve("alias"), real);
        assertRejected(new SessionSandbox.Snapshot(
                List.of(alias), List.of(project, scratch), List.of(secret), List.of(), List.of()));
        assertRejected(new SessionSandbox.Snapshot(
                List.of(real), List.of(project, scratch), List.of(alias.resolve("secret")), List.of(), List.of()));
        assertRejected(new SessionSandbox.Snapshot(
                List.of(), List.of(alias, project, scratch), List.of(), List.of(secret), List.of()));
    }

    @Test
    void rejectsDenialsIntersectingImplicitSystemAndToolchainMounts() throws Exception {
        for (final var implicit : List.of("/usr/bin", "/proc", "/dev", "/yca")) {
            assertRejected(new SessionSandbox.Snapshot(
                    List.of(), List.of(project, scratch), List.of(Path.of(implicit)), List.of(), List.of()));
        }
        final var toolchain = Files.createDirectory(root.resolve("toolchain"));
        final var secret = Files.writeString(toolchain.resolve("secret"), "hidden");
        final var builder = new ProcessBuilder("/bin/true").directory(project.toFile());
        builder.environment().put("JAVA_HOME", toolchain.toString());
        assertThrows(
                SecurityException.class,
                () -> backend.launch(
                        builder,
                        new SessionSandbox.Snapshot(
                                List.of(), List.of(project, scratch), List.of(secret), List.of(), List.of()),
                        scratch,
                        () -> {}));
    }

    @Test
    void allowsWriteDenialsAlreadySatisfiedByReadOnlyImplicitMounts() throws Exception {
        final var toolchain = Files.createDirectory(root.resolve("readonly-toolchain"));
        final var file = Files.writeString(toolchain.resolve("probe"), "unchanged");
        final var builder = new ProcessBuilder(
                        "/bin/bash",
                        "-c",
                        "test ! -w /usr/bin && ! printf changed > '" + file + "' && cat '" + file + "'")
                .directory(project.toFile())
                .redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", toolchain.toString());
        final var policy = new SessionSandbox.Snapshot(
                List.of(), List.of(project, scratch), List.of(), List.of(Path.of("/usr/bin"), file), List.of());
        try (final var command = backend.launch(builder, policy, scratch, () -> {})) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
            assertTrue(output.contains("unchanged"), output);
            assertEquals("unchanged", Files.readString(file));
        }
    }

    @Test
    void writeDenialAboveGrantedRootsDoesNotExposeUnrelatedReadPaths() throws Exception {
        final var secret = Files.writeString(root.resolve("sibling-secret"), "outside");
        final var builder = new ProcessBuilder(
                        "/bin/bash", "-c", "test ! -e '" + secret + "' && ! touch new-file 2>/dev/null")
                .directory(project.toFile())
                .redirectErrorStream(true);
        try (final var command = backend.launch(
                builder,
                new SessionSandbox.Snapshot(List.of(), List.of(project, scratch), List.of(), List.of(root), List.of()),
                scratch,
                () -> {})) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, command.process().exitValue(), output);
        }
    }

    @Test
    void rejectsBroadAndSystemWritableMountsEvenInDirectSnapshots() {
        for (final var broad : List.of(Path.of("/"), Path.of("/home"), Path.of(System.getProperty("user.home")))) {
            assertRejected(new SessionSandbox.Snapshot(
                    List.of(broad), List.of(project, scratch), List.of(), List.of(), List.of()));
            assertRejected(new SessionSandbox.Snapshot(
                    List.of(), List.of(broad, project, scratch), List.of(), List.of(), List.of()));
        }
        for (final var system : List.of("/usr", "/etc", "/proc", "/sys", "/dev", "/run", "/tmp", "/var")) {
            assertRejected(new SessionSandbox.Snapshot(
                    List.of(), List.of(Path.of(system), project, scratch), List.of(), List.of(), List.of()));
        }
    }

    @Test
    void concurrentCloseWaitsForTerminationAndPreservesInterrupts() throws Exception {
        final var waiting = new CountDownLatch(1);
        final var terminate = new CountDownLatch(1);
        final var cleanups = new AtomicInteger();
        final var interrupted = new AtomicBoolean();
        final var process = new Process() {
            @Override
            public OutputStream getOutputStream() {
                return new ByteArrayOutputStream();
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public InputStream getErrorStream() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public Stream<ProcessHandle> descendants() {
                return Stream.empty();
            }

            @Override
            public int waitFor() throws InterruptedException {
                waiting.countDown();
                terminate.await();
                return 0;
            }

            @Override
            public int exitValue() {
                if (isAlive()) {
                    throw new IllegalThreadStateException();
                }
                return 0;
            }

            @Override
            public boolean isAlive() {
                return terminate.getCount() != 0;
            }

            @Override
            public void destroy() {}

            @Override
            public Process destroyForcibly() {
                return this;
            }
        };
        final var command = new SandboxCommand(process, cleanups::incrementAndGet);
        final var first = Thread.ofPlatform().unstarted(() -> {
            Thread.currentThread().interrupt();
            command.close();
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        final var second = Thread.ofPlatform().unstarted(command::close);
        first.start();
        try {
            waiting.await();
            second.start();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                while (second.getState() == Thread.State.RUNNABLE || second.getState() == Thread.State.NEW) {
                    Thread.onSpinWait();
                }
            });
            assertEquals(Thread.State.WAITING, second.getState(), "concurrent close must await termination");
            assertEquals(0, cleanups.get());
            assertTrue(process.isAlive());
        } finally {
            terminate.countDown();
            first.join();
            second.join();
        }
        assertFalse(process.isAlive());
        assertTrue(interrupted.get(), "interrupted close must finish termination and restore interrupt status");
        assertEquals(1, cleanups.get());
        command.close();
        assertEquals(1, cleanups.get());
    }

    private void assertRejected(final SessionSandbox.Snapshot policy) {
        assertThrows(
                SecurityException.class,
                () -> backend.launch(
                        new ProcessBuilder("/bin/true").directory(project.toFile()), policy, scratch, () -> {}));
    }

    private ProcessBuilder javaCommand(final String... arguments) throws Exception {
        final var javaHome = Path.of(System.getProperty("java.home")).toRealPath();
        final var command = new ArrayList<String>();
        command.add(javaHome.resolve("bin/java").toString());
        command.addAll(List.of(arguments));
        final var builder =
                new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", javaHome.toString());
        return builder;
    }

    /** Exercises the Docker capability from a command JVM after the filter has been installed. */
    public static final class DockerProbe {
        public static void main(final String[] arguments) throws Exception {
            final var enabled = Boolean.parseBoolean(arguments[0]);
            final var expectedHost = enabled ? "tcp://127.0.0.1:2375" : null;
            if (!Objects.equals(expectedHost, System.getenv("DOCKER_HOST"))) {
                throw new AssertionError("Docker environment not sanitized");
            }
            // Even the trusted mount must not become a directly connectable command capability.
            try (final var direct = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                throw new AssertionError("Direct Unix socket creation unexpectedly permitted: " + direct);
            } catch (final IOException expected) {
                // seccomp must deny socket(AF_UNIX), even with Docker enabled.
            }
            try (final var client = new Socket()) {
                try {
                    client.connect(new InetSocketAddress("127.0.0.1", 2375), 5000);
                } catch (final ConnectException expected) {
                    if (enabled) {
                        throw expected;
                    }
                    return;
                }
                if (!enabled) {
                    throw new AssertionError("Default sandbox exposed Docker listener");
                }
                client.setSoTimeout(5000);
                client.getOutputStream().write("_ping".getBytes(StandardCharsets.US_ASCII));
                client.shutdownOutput();
                final var response = new String(client.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                if (!"OK".equals(response)) {
                    throw new AssertionError("Unexpected daemon response: " + response);
                }
            }
        }
    }

    /** A separate JVM applies the syscall checks after the sandbox profile has been installed. */
    public static final class SocketProbe {
        public static void main(final String[] arguments) throws Throwable {
            final var linker = Linker.nativeLinker();
            final var symbols = linker.defaultLookup();
            final var socket = linker.downcallHandle(
                    symbols.find("socket").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            final var socketpair = linker.downcallHandle(
                    symbols.find("socketpair").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS));
            final var close = linker.downcallHandle(
                    symbols.find("close").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            final var errnoLocation = linker.downcallHandle(
                    symbols.find("__errno_location").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
            final var errno =
                    ((MemorySegment) errnoLocation.invokeExact()).reinterpret(ValueLayout.JAVA_INT.byteSize());
            try (final var arena = Arena.ofConfined()) {
                final var pair = arena.allocate(ValueLayout.JAVA_INT, 2);
                // AF_UNIX = 1; SOCK_STREAM = 1, SOCK_DGRAM = 2, SOCK_SEQPACKET = 5; EPERM = 1.
                for (final var flags : new int[] {0, 0x80000, 0x800, 0x80800}) {
                    final int stream = (int) socketpair.invokeExact(1, 1 | flags, 0, pair);
                    if (stream != 0) {
                        throw new AssertionError("anonymous stream socketpair: result=" + stream + ", errno="
                                + errno.get(ValueLayout.JAVA_INT, 0));
                    }
                    final int first = (int) close.invokeExact(pair.getAtIndex(ValueLayout.JAVA_INT, 0));
                    final int second = (int) close.invokeExact(pair.getAtIndex(ValueLayout.JAVA_INT, 1));
                    if (first != 0 || second != 0) {
                        throw new AssertionError("cannot close anonymous socketpair");
                    }
                }
                for (final var type : new int[] {2, 5, 2 | 0x80800, 5 | 0x80800}) {
                    errno.set(ValueLayout.JAVA_INT, 0, 0);
                    final int result = (int) socketpair.invokeExact(1, type, 0, pair);
                    if (result != -1 || errno.get(ValueLayout.JAVA_INT, 0) != 1) {
                        throw new AssertionError("socketpair type " + type + ": result=" + result + ", errno="
                                + errno.get(ValueLayout.JAVA_INT, 0));
                    }
                }
                errno.set(ValueLayout.JAVA_INT, 0, 0);
                final int result = (int) socket.invokeExact(1, 2, 0);
                if (result != -1 || errno.get(ValueLayout.JAVA_INT, 0) != 1) {
                    throw new AssertionError(
                            "socket: result=" + result + ", errno=" + errno.get(ValueLayout.JAVA_INT, 0));
                }
            }
        }
    }

    private SessionSandbox.Snapshot snapshot(final List<Path> reads, final List<String> domains) {
        return new SessionSandbox.Snapshot(reads, List.of(project, scratch), List.of(), List.of(), domains);
    }
}
