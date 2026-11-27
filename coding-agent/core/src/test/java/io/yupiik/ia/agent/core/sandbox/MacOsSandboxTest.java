/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Portable compiler tests plus native smoke tests, not qualification of macOS enforcement. */
class MacOsSandboxTest {
    private static final String RUNTIME = """
            (allow process-fork)
            (allow sysctl-read
              (sysctl-name "kern.ostype")
              (sysctl-name "kern.osrelease")
              (sysctl-name "kern.osversion")
              (sysctl-name "hw.machine")
              (sysctl-name "hw.ncpu")
              (sysctl-name "hw.pagesize")
              (sysctl-name "hw.memsize"))
            (allow mach-lookup (global-name "com.apple.system.logger"))
            """;

    @TempDir
    Path root;

    @Test
    void exactProfileSubtractsDenialsFromEveryGrant() {
        final var sandbox = new MacOsSandbox(
                Path.of("/work"),
                Path.of("/project"),
                Path.of("/scratch"),
                List.of("/runtime", "${projectdir}"),
                List.of("${projectdir}", "${tempdir}"),
                List.of("glob:${projectdir}/**/.env*", "/control"),
                List.of("${projectdir}/readonly"),
                List.of());
        assertEquals("""
                (version 1)
                (deny default)
                (allow file-read*
                  (require-all
                    (require-any
                      (subpath "/runtime")
                      (subpath "/project")
                      (subpath "/scratch"))
                    (require-not (require-any
                      (regex "^/project/.*/\\\\.env[^/]*(/[^/]+)*$")
                      (subpath "/control")))))
                (allow file-map-executable process-exec
                  (require-all
                    (require-any
                      (subpath "/runtime")
                      (subpath "/project")
                      (subpath "/scratch"))
                    (require-not (require-any
                      (regex "^/project/.*/\\\\.env[^/]*(/[^/]+)*$")
                      (subpath "/control")))))
                (allow file-write*
                  (require-all
                    (require-any
                      (subpath "/project")
                      (subpath "/scratch"))
                    (require-not (require-any
                      (regex "^/project/.*/\\\\.env[^/]*(/[^/]+)*$")
                      (subpath "/control")
                      (subpath "/project/readonly")))))
                """ + RUNTIME, sandbox.profile());
    }

    @Test
    void emptyPolicyHasOnlyNarrowRuntimePrivilegesAndNoFilesystemOrNetworkAccess() {
        final var profile = policy(List.of(), List.of(), List.of(), List.of()).profile();
        assertEquals("(version 1)\n(deny default)\n" + RUNTIME, profile);
    }

    @Test
    void globTranslationAgreesWithJvmAncestorMatchingForFuturePaths() {
        final var suffixes = List.of(
                ".env",
                ".env.local",
                "x.env",
                "nested/.env",
                "a/b/.env.production",
                "a/b/.env/child",
                "a/b/.env/child\nname",
                "a/b/secret/key",
                "a/secret/key",
                "secret/key",
                "secrets/key",
                "future/a.txt",
                "future/a.txt/child",
                "future/ab.txt",
                "literal*/child",
                "literal?/child",
                "literal[one]/child",
                "literal{one}/child",
                "a+(b)|c.txt",
                "a+(b)|c.txt/child",
                "neighbor/.env2");
        for (final var glob : List.of(
                ".env*",
                "**/.env*",
                "**/secret",
                "*/secret",
                "future/?.txt",
                "future/*.txt",
                "literal\\*",
                "literal\\?",
                "literal\\[one\\]",
                "literal\\{one\\}",
                "a+(b)|c.txt")) {
            final var absolute = root + "/" + glob;
            final var profile = policy(List.of(), List.of(), List.of("glob:" + absolute), List.of());
            // No read grant => no emitted deny filter. Give a grant so we inspect actual output.
            final var rendered = new MacOsSandbox(
                            root,
                            root,
                            root,
                            List.of(root.toString()),
                            List.of(),
                            List.of("glob:" + absolute),
                            List.of(),
                            List.of())
                    .profile();
            final var regex = Pattern.compile(regexes(rendered).getFirst());
            final var matcher = FileSystems.getDefault().getPathMatcher("glob:" + absolute);
            for (final var suffix : suffixes) {
                final var candidate = root.resolve(suffix);
                var expected = false;
                for (var ancestor = candidate; ancestor != null; ancestor = ancestor.getParent()) {
                    if (matcher.matches(ancestor)) {
                        expected = true;
                        break;
                    }
                }
                assertEquals(expected, regex.matcher(candidate.toString()).matches(), glob + " => " + suffix);
            }
            assertEquals("(version 1)\n(deny default)\n" + RUNTIME, profile.profile());
        }
    }

    @Test
    void recursiveDenyDoesNotExpandExistingFilesOrMakeDoubleStarSlashOptional() throws Exception {
        final var project = root.resolve("not-created-yet");
        final var sandbox = new MacOsSandbox(
                root,
                project,
                root,
                List.of("${projectdir}"),
                List.of("${projectdir}"),
                List.of("glob:${projectdir}/**/.env*"),
                List.of(),
                List.of());
        final var before = sandbox.profile();
        final var regex = Pattern.compile(regexes(before).getFirst());
        assertFalse(regex.matcher(project.resolve(".env").toString()).matches());
        assertTrue(regex.matcher(project.resolve("future/nested/.env/child").toString())
                .matches());
        Files.createDirectories(project.resolve("future/nested"));
        Files.writeString(project.resolve("future/nested/.env.local"), "secret");
        assertEquals(before, sandbox.profile());
    }

    @Test
    void anchorsAreLiteralAndStableAndRelativeRulesUseProject() {
        final var project = root.resolve("project[one]*?{two}");
        final var sandbox = new MacOsSandbox(
                root.resolve("work"),
                project,
                root.resolve("scratch"),
                List.of("relative", "${workdir}"),
                List.of("${tempdir}"),
                List.of("glob:${projectdir}/**/.env*"),
                List.of(),
                List.of());
        final var profile = sandbox.profile();
        assertTrue(profile.contains("(subpath \"" + project + "/relative\")"));
        final var regex = Pattern.compile(regexes(profile).getFirst());
        assertTrue(
                regex.matcher(project.resolve("future/.env.local").toString()).matches());
        assertFalse(
                regex.matcher(root.resolve("projectoXYZtwo/future/.env.local").toString())
                        .matches());
    }

    @Test
    void literalMetacharactersAndProfileInjectionAreQuotedNotExecuted() {
        final var value = root + "/literal*?[a]{b}\\\") (allow default) ;";
        final var profile =
                policy(List.of(value), List.of(), List.of(), List.of()).profile();
        assertTrue(profile.contains("literal*?[a]{b}\\\\\\\") (allow default) ;\""));
        assertTrue(regexes(profile).isEmpty());
        assertFalse(profile.contains("\n(allow default)"));
    }

    @Test
    void rejectsUnsupportedAndAmbiguousSyntaxRatherThanDroppingRules() {
        for (final var rule : List.of(
                "",
                " ",
                "glob:",
                "${unknown}",
                "${projectdir",
                "glob:[abc]",
                "glob:{a,b}",
                "glob:***/secret",
                "glob:foo/../bar",
                "glob:foo/./bar",
                "glob:foo//bar",
                "glob:foo/",
                "glob:foo\\",
                "glob:foo\\q",
                "glob:\u00e9*",
                "literal\npath",
                "literal\u0000path")) {
            for (var position = 0; position < 4; position++) {
                final var rules = new ArrayList<>(
                        List.of(List.<String>of(), List.<String>of(), List.<String>of(), List.<String>of()));
                rules.set(position, List.of(rule));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> policy(rules.get(0), rules.get(1), rules.get(2), rules.get(3)),
                        rule);
            }
        }
        assertThrows(SecurityException.class, () -> policy(List.of("/"), List.of(), List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MacOsSandbox(
                        Path.of("relative"), root, root, List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    @Test
    void listsAreSnapshottedAndOutputIsDeterministic() {
        final var reads = new ArrayList<>(List.of("${projectdir}", "${projectdir}"));
        final var sandbox = policy(reads, List.of(), List.of(), List.of());
        final var before = sandbox.profile();
        reads.add("/untrusted");
        assertEquals(before, sandbox.profile());
        assertEquals(2, before.lines().filter(line -> line.contains("(subpath")).count());
    }

    @Test
    void networkGrantsFailClosedEvenForLoopbackAndNominallyAllowedDomains() {
        for (final var domain : List.of("repo.maven.apache.org:443", "*.npmjs.org:443", "127.0.0.1:3128")) {
            final var error = assertThrows(
                    SecurityException.class,
                    () -> new MacOsSandbox(
                            root, root, root, List.of(), List.of(), List.of(), List.of(), List.of(domain)));
            assertTrue(error.getMessage().contains("pinned CONNECT-only"));
        }
        final var profile = policy(List.of(root.toString()), List.of(root.toString()), List.of(), List.of())
                .profile();
        assertFalse(profile.contains("network"));
        assertTrue(profile.endsWith(RUNTIME));
        assertFalse(profile.contains("(allow mach-lookup)"));
        assertFalse(profile.contains("(allow sysctl-read)"));
        assertFalse(profile.contains("sysctl-write"));
    }

    @Test
    void launchCannotBypassQualificationOrRunAnUnsandboxedCommand() {
        final var marker = root.resolve("must-not-exist");
        final var released = new AtomicBoolean();
        final var original = new ProcessBuilder("/usr/bin/touch", marker.toString());
        final var error = assertThrows(
                IOException.class,
                () -> policy(List.of(root.toString()), List.of(root.toString()), List.of(), List.of())
                        .launch(original, () -> released.set(true)));
        assertTrue(error.getMessage().contains("no fallback"));
        assertFalse(Files.exists(marker));
        assertFalse(released.get(), "Failed launch does not own the caller's release callback");
        assertEquals(List.of("/usr/bin/touch", marker.toString()), original.command());
        assertThrows(
                IOException.class,
                () -> policy(List.of(), List.of(), List.of(), List.of()).launch(original, () -> {}, false));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.WINDOWS})
    void experimentalOptInDoesNotRunHostCommandsOnOtherPlatforms() {
        final var marker = root.resolve("must-not-exist");
        final var released = new AtomicBoolean();
        final var original = new ProcessBuilder("/usr/bin/touch", marker.toString());
        final var environment = Map.copyOf(original.environment());
        final var error = assertThrows(
                IOException.class,
                () -> policy(List.of(), List.of(), List.of(), List.of())
                        .launch(original, () -> released.set(true), true));
        assertTrue(error.getMessage().contains("no fallback"));
        assertFalse(Files.exists(marker));
        assertFalse(released.get());
        assertEquals(List.of("/usr/bin/touch", marker.toString()), original.command());
        assertEquals(environment, original.environment());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void nativeProfileCompilesAndExecutesBaselineWithExplicitOptIn() throws Exception {
        final var released = new AtomicInteger();
        try (final var command = nativePolicy()
                .launch(
                        new ProcessBuilder("/usr/bin/true").directory(root.toFile()),
                        released::incrementAndGet,
                        true)) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, command.process().exitValue(), () -> "Native candidate profile failed: " + output(command));
        }
        assertEquals(1, released.get());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void nativeLaunchPreservesArgumentsSanitizesEnvironmentAndRestrictsFiles() throws Exception {
        final var forbidden = Files.writeString(root.resolve("secret"), "host-secret");
        final var deniedWrite = root.resolve("forbidden-write");
        final var literal = "literal ; $(touch must-not-exist) ' \"";
        final var original = new ProcessBuilder(
                        "/bin/sh", "-c", """
                printf '%s\\n' "$1" "$HOME" "$XDG_CACHE_HOME" "$TMPDIR" \
                  "${YCA_HOST_SECRET-unset}" "${DYLD_INSERT_LIBRARIES-unset}" \
                  "${JAVA_TOOL_OPTIONS-unset}" "${HTTPS_PROXY-unset}"
                /usr/bin/touch "$HOME/created" || exit 90
                if /bin/cat "$2"; then exit 91; fi
                if /usr/bin/touch "$3"; then exit 92; fi
                printf 'restricted-ok\\n'
                """, "sandbox-test", literal, forbidden.toString(), deniedWrite.toString())
                .directory(root.toFile())
                .redirectErrorStream(true);
        original.environment().put("YCA_HOST_SECRET", "must-not-leak");
        original.environment().put("DYLD_INSERT_LIBRARIES", "/not-a-library");
        original.environment().put("JAVA_TOOL_OPTIONS", "-Dhost.secret=must-not-leak");
        original.environment().put("HTTPS_PROXY", "http://127.0.0.1:1");
        final var before = List.copyOf(original.command());
        final var envBefore = Map.copyOf(original.environment());
        try (final var command = nativePolicy().launch(original, () -> {}, true)) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            final var output = output(command);
            assertEquals(0, command.process().exitValue(), output);
            final var lines = output.lines().toList();
            assertEquals(literal, lines.getFirst());
            for (final var path : lines.subList(1, 4)) {
                assertTrue(Path.of(path).startsWith(root.resolve("scratch")), output);
            }
            assertTrue(Files.exists(Path.of(lines.get(1)).resolve("created")));
            assertEquals(List.of("unset", "unset", "unset", "unset"), lines.subList(4, 8));
            assertTrue(output.contains("restricted-ok"), output);
            assertFalse(output.contains("host-secret"), output);
        }
        assertFalse(Files.exists(deniedWrite));
        assertFalse(Files.exists(root.resolve("must-not-exist")));
        assertEquals(before, original.command());
        assertEquals(envBefore, original.environment());
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void nativeFailedExecNeverRetriesOnHost() throws Exception {
        final var marker = root.resolve("must-not-exist");
        // No exec/read grants for /usr/bin/touch: even a valid host command must fail restricted.
        final var scratch = Files.createDirectory(root.resolve("scratch"));
        final var sandbox = new MacOsSandbox(
                root, root, scratch, List.of(), List.of(scratch.toString()), List.of(), List.of(), List.of());
        try (final var command = sandbox.launch(
                new ProcessBuilder("/usr/bin/touch", marker.toString()).directory(root.toFile()), () -> {}, true)) {
            assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, command.process().exitValue());
        }
        assertFalse(Files.exists(marker));
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void nativeOfflineProfileDoesNotAllowEvenLoopbackConnections() throws Exception {
        try (final var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(250);
            try (final var command = nativePolicy()
                    .launch(
                            new ProcessBuilder(
                                            "/usr/bin/curl",
                                            "--silent",
                                            "--show-error",
                                            "--noproxy",
                                            "*",
                                            "--max-time",
                                            "2",
                                            "http://127.0.0.1:" + server.getLocalPort() + "/")
                                    .directory(root.toFile()),
                            () -> {},
                            true)) {
                assertTrue(command.process().waitFor(10, TimeUnit.SECONDS));
                assertNotEquals(0, command.process().exitValue(), output(command));
            }
            assertThrows(SocketTimeoutException.class, () -> {
                try (final var connection = server.accept()) {
                    fail("Offline sandbox unexpectedly connected to loopback: " + connection.getRemoteSocketAddress());
                }
            });
        }
    }

    private MacOsSandbox nativePolicy() throws IOException {
        final var scratch = Files.createDirectory(root.resolve("scratch"));
        return new MacOsSandbox(
                root,
                root,
                scratch,
                List.of(
                        "/bin",
                        "/usr/bin",
                        "/usr/lib",
                        "/System/Library",
                        "/System/Cryptexes/OS",
                        "/System/Volumes/Preboot/Cryptexes/OS"),
                List.of(scratch.toString()),
                List.of(root.resolve("secret").toString(), "glob:${projectdir}/**/.env*"),
                List.of(root.resolve("forbidden-write").toString()),
                List.of());
    }

    private String output(final Sandbox.Command command) {
        try {
            return new String(command.process().getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    + new String(command.process().getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private MacOsSandbox policy(
            final List<String> reads,
            final List<String> writes,
            final List<String> denyReads,
            final List<String> denyWrites) {
        return new MacOsSandbox(root, root, root, reads, writes, denyReads, denyWrites, List.of());
    }

    /** Read SBPL ordinary quoted strings, not Java's private compiler implementation. */
    private List<String> regexes(final String profile) {
        final var result = new ArrayList<String>();
        final var pattern = Pattern.compile("\\(regex \"((?:\\\\.|[^\"\\\\])*)\"\\)");
        final var matcher = pattern.matcher(profile);
        while (matcher.find()) {
            final var value = matcher.group(1);
            final var decoded = new StringBuilder();
            for (var i = 0; i < value.length(); i++) {
                if (value.charAt(i) == '\\') {
                    i++;
                }
                decoded.append(value.charAt(i));
            }
            result.add(decoded.toString());
        }
        return result;
    }
}
