/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox.helper;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Standalone JDK-only entry point, run exclusively in a disposable child process. */
public final class HelperMain {
    private static final Pattern ENVIRONMENT_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_ENVIRONMENT_BYTES = 1024 * 1024;

    /** Exact bytecode resources needed for a standalone helper JAR; no generated nested classes. */
    public static final List<String> CLASS_RESOURCES = List.of(
            "io/yupiik/ia/agent/core/sandbox/helper/HelperMain.class",
            "io/yupiik/ia/agent/core/sandbox/helper/LinuxNative.class",
            "io/yupiik/ia/agent/core/sandbox/helper/SandboxBridge.class");

    private HelperMain() {
        // No instances.
    }

    public static void main(final String[] args) {
        Process bridge = null;
        try {
            if (args.length == 1 && "--probe".equals(args[0])) {
                LinuxNative.exec(List.of("/bin/true"), System.getenv());
                throw new IllegalStateException("execve unexpectedly returned");
            }
            if (args.length == 0) {
                throw new IllegalArgumentException("Missing helper arguments");
            }
            final var bridgeMode = "--bridge".equals(args[0]);
            if (bridgeMode && args.length < 3) {
                throw new IllegalArgumentException("Missing bridge socket or parent PID");
            }
            if (!Path.of(args[bridgeMode ? 1 : 0]).isAbsolute()) {
                throw new IllegalArgumentException("Policy socket must be absolute");
            }
            final var bridges = new LinkedHashMap<Integer, String>();
            Path environmentFile = null;
            var index = bridgeMode ? 3 : 1;
            while (index < args.length && "--socket-bridge".equals(args[index])) {
                if (index + 2 >= args.length) {
                    throw new IllegalArgumentException("Missing socket bridge port or path");
                }
                final var port = Integer.parseInt(args[index + 1]);
                final var path = args[index + 2];
                if (port < 1024
                        || port > 65535
                        || port == 3128
                        || !Path.of(path).isAbsolute()
                        || bridges.putIfAbsent(port, path) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate socket bridge: " + port);
                }
                index += 3;
            }
            if (bridgeMode) {
                if (index != args.length) {
                    throw new IllegalArgumentException("Invalid bridge option");
                }
                SandboxBridge.run(args[1], Long.parseLong(args[2]), bridges);
                return;
            }
            if (index < args.length && "--command-environment".equals(args[index])) {
                if (++index >= args.length || !Path.of(args[index]).isAbsolute()) {
                    throw new IllegalArgumentException("Command environment file must be absolute");
                }
                environmentFile = Path.of(args[index++]);
            }
            if (index >= args.length || !"--".equals(args[index++]) || index >= args.length) {
                throw new IllegalArgumentException("usage: sandbox-helper SOCKET "
                        + "[--socket-bridge PORT ABSOLUTE_SOCKET]* "
                        + "[--command-environment ABSOLUTE_ENV_FILE] -- COMMAND...");
            }
            final var commandStart = index;
            final var commandEnvironment = environmentFile == null ? System.getenv() : readEnvironment(environmentFile);
            final var prefix = new ArrayList<>(selfPrefix());
            prefix.addAll(List.of(
                    "--bridge", args[0], Long.toString(ProcessHandle.current().pid())));
            for (final var entry : bridges.entrySet()) {
                prefix.addAll(List.of("--socket-bridge", entry.getKey().toString(), entry.getValue()));
            }
            // This is the ONLY subprocess start. It must happen before seccomp installation.
            final var builder = new ProcessBuilder(prefix).redirectError(ProcessBuilder.Redirect.INHERIT);
            // Only retain startup-safe settings. Command overrides never enter this OS environment.
            builder.environment()
                    .keySet()
                    .retainAll(List.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "LC_CTYPE", "TZ"));
            bridge = builder.start();
            final var started = bridge;
            final var ready = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(started.getInputStream().readNBytes(6), StandardCharsets.US_ASCII);
                } catch (final IOException failure) {
                    throw new IllegalStateException("Cannot read sandbox bridge readiness", failure);
                }
            });
            if (!"READY\n".equals(ready.get(10, TimeUnit.SECONDS)) || !bridge.isAlive()) {
                throw new IllegalStateException("Sandbox bridge did not become ready");
            }
            bridge.getInputStream().close();
            LinuxNative.exec(Arrays.asList(args).subList(commandStart, args.length), commandEnvironment);
            throw new IllegalStateException("execve unexpectedly returned");
        } catch (final Throwable failure) {
            if (bridge != null) {
                bridge.destroyForcibly();
            }
            System.err.println("Sandbox helper failed: " + failure);
            System.exit(125);
        }
    }

    private static Map<String, String> readEnvironment(final Path file) throws IOException {
        final byte[] bytes;
        try (final var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_ENVIRONMENT_BYTES + 1);
        }
        if (bytes.length > MAX_ENVIRONMENT_BYTES) {
            throw new IllegalArgumentException("Command environment exceeds 1 MiB");
        }
        final var environment = new LinkedHashMap<String, String>();
        try (final var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            final var count = input.readInt();
            if (count < 0 || count > 4096) {
                throw new IllegalArgumentException("Invalid command environment entry count");
            }
            for (var i = 0; i < count; i++) {
                final var key = input.readUTF();
                final var value = input.readUTF();
                if (!ENVIRONMENT_KEY.matcher(key).matches()
                        || value.contains("\0")
                        || environment.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate command environment key");
                }
            }
            if (input.read() != -1) {
                throw new IllegalArgumentException("Trailing command environment bytes");
            }
        }
        return environment;
    }

    /** JVM helper JARs use this class directly; native binaries use early launcher dispatch. */
    public static List<String> selfPrefix() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            return List.of(ProcessHandle.current()
                    .info()
                    .command()
                    .orElseThrow(() -> new IllegalStateException("Cannot locate native sandbox helper")));
        }
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                HelperMain.class.getName());
    }
}
