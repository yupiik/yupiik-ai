/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Candidate Seatbelt profile compiler, not a runtime-qualified native boundary.
 *
 * <p>The original strings are required: a list of existing paths cannot represent future glob
 * matches. Rules are anchored to the session, not the command's working directory. Only literals
 * and a deliberately small subset of JVM glob syntax (*, **, ?, and escaped glob metacharacters)
 * are accepted. There is no filesystem scan,
 * expansion of today's matches, shell interpolation, or network exception.
 *
 * <p>Seatbelt's resolved-vnode filters alone do not establish SandboxPaths' lexical AND canonical
 * allow / lexical OR canonical deny semantics. In particular, a symlink under a denied lexical
 * name can refer to an otherwise allowed vnode. Scanning current symlinks would not fix future
 * aliases. Also, enumerating ProcessHandle descendants is not strict macOS process-tree revocation.
 * The two-argument launch refuses every command. The explicit experimental opt-in can run this
 * weaker, unqualified boundary; it is NOT a secure equivalent of the Linux backend. Descendants
 * inherit Seatbelt restrictions, but reparenting can escape ProcessHandle-based revocation.
 *
 * <p>SBPL forms follow Chromium's maintained
 * https://github.com/chromium/chromium/blob/main/sandbox/policy/mac/common.sb (version 1,
 * require-all/require-any/require-not and path regex filters). Seatbelt is private/deprecated;
 * source-level profile tests are not evidence of macOS kernel enforcement or regex equivalence.
 */
public final class MacOsSandbox {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private final List<String> read;
    private final List<String> write;
    private final List<String> denyRead;
    private final List<String> denyWrite;
    private final Path tempDir;

    /**
     * Compile an immutable candidate from original configured strings plus explicit session grants.
     * The caller must include any intended runtime/toolchain reads and scratch writes in the allow
     * lists, and controller paths in denyRead; no ambient home, /tmp, system or toolchain grant is
     * silently added. Write grants imply read access, matching the native launch policy.
     * Nonempty domain grants are rejected: proxy environment variables are not a network boundary.
     */
    public MacOsSandbox(
            final Path workdir,
            final Path projectDir,
            final Path tempDir,
            final List<String> allowRead,
            final List<String> allowWrite,
            final List<String> denyRead,
            final List<String> denyWrite,
            final List<String> domains) {
        final var anchors =
                Map.of("workdir", anchor(workdir), "projectdir", anchor(projectDir), "tempdir", anchor(tempDir));
        this.tempDir = tempDir;
        if (!List.copyOf(Objects.requireNonNull(domains, "domains")).isEmpty()) {
            throw new SecurityException("macOS sandbox network grants are unsupported: a pinned CONNECT-only "
                    + "transport and non-bypassable kernel boundary have not been qualified; no network fallback");
        }
        read = compile(allowRead, anchors, projectDir);
        write = compile(allowWrite, anchors, projectDir);
        this.denyRead = compile(denyRead, anchors, projectDir);
        this.denyWrite = compile(denyWrite, anchors, projectDir);
    }

    /**
     * Render the deterministic experimental candidate for inspection and native qualification.
     * Denials are subtracted inside EVERY grant rather than relying on SBPL rule ordering.
     * A read denial also excludes writes and execution. A write denial does not exclude reads.
     * Fork is needed for shell pipelines. Read-only sysctls expose only basic OS/CPU sizing used
     * by libc and command runtimes. The sole Mach lookup is libc's system logger, not arbitrary
     * launchd services. No sockets, extension-consumption or ambient filesystem grants are emitted.
     */
    public String profile() {
        final var result = new StringBuilder("(version 1)\n(deny default)\n");
        final var reads = union(read, write);
        allow(result, "file-read*", reads, denyRead);
        allow(result, "file-map-executable process-exec", reads, denyRead);
        allow(result, "file-write*", write, union(denyRead, denyWrite));
        result.append("""
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
                """);
        return result.toString();
    }

    /**
     * Fail closed, including on hosts with sandbox-exec installed. Merely finding sandbox-exec or
     * compiling SBPL does not qualify alias mediation, process lifetime, or regex semantics.
     * Use the explicit experimental overload to accept the documented weaker guarantees.
     */
    public Sandbox.Command launch(final ProcessBuilder original, final Runnable released) throws IOException {
        return launch(original, released, false);
    }

    /**
     * Explicitly opt in to unqualified, offline-only native execution. No host retry is performed,
     * even if sandbox-exec is missing, rejects the profile or exits immediately. A successful
     * process start is not evidence that the profile or its enforcement has been qualified.
     *
     * <p>The caller supplies runtime read grants (typically /bin, /usr/bin, /usr/lib and
     * /System/Library, the OS dyld cryptex paths on recent macOS, plus explicitly selected
     * toolchains), temp writes, and controller denials
     * through the constructor. No paths are implicitly allowed here. HOME, XDG locations and
     * temporary files use a private directory under tempDir; the session owns its cleanup.
     * The original builder is not modified. Only stderr merging is copied, not redirects that
     * would let the controller open host files outside the profile on the child's behalf.
     * The release callback transfers to the returned command only after a successful start.
     */
    public Sandbox.Command launch(final ProcessBuilder original, final Runnable released, final boolean experimental)
            throws IOException {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(released, "released");
        if (!experimental) {
            throw new IOException("Native macOS sandbox launch is not qualified: Seatbelt lexical/canonical alias "
                    + "mediation and strict process-tree revocation are unresolved; command refused (no fallback). "
                    + "Explicit experimental macOS opt-in is required");
        }
        if (!List.of("Mac OS X", "Darwin").contains(System.getProperty("os.name"))) {
            throw new IOException("Experimental macOS sandbox requires a macOS host; no fallback");
        }
        if (original.command().isEmpty()) {
            throw new IOException("Empty experimental macOS sandbox command; no fallback");
        }
        if (!Files.isDirectory(tempDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(tempDir)) {
            throw new IOException("Experimental macOS sandbox requires a private temp directory: " + tempDir);
        }
        final var privateDir = Files.createTempDirectory(
                tempDir, "macos-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var home = Files.createDirectory(privateDir.resolve("home"));
        final var cache = Files.createDirectory(privateDir.resolve("cache"));
        final var config = Files.createDirectory(privateDir.resolve("config"));
        final var data = Files.createDirectory(privateDir.resolve("data"));
        final var state = Files.createDirectory(privateDir.resolve("state"));
        final var tmp = Files.createDirectory(privateDir.resolve("tmp"));
        Files.createDirectories(home.resolve("Library/Caches"));
        final var args = new ArrayList<>(List.of("/usr/bin/sandbox-exec", "-p", profile(), "--"));
        args.addAll(List.copyOf(original.command()));
        final var builder = new ProcessBuilder(args)
                .directory(original.directory())
                .redirectErrorStream(original.redirectErrorStream());
        final var env = builder.environment();
        env.clear();
        env.putAll(Map.ofEntries(
                Map.entry("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"),
                Map.entry("LANG", "C"),
                Map.entry("LC_ALL", "C"),
                Map.entry("HOME", home.toString()),
                Map.entry("CFFIXED_USER_HOME", home.toString()),
                Map.entry("XDG_CACHE_HOME", cache.toString()),
                Map.entry("XDG_CONFIG_HOME", config.toString()),
                Map.entry("XDG_DATA_HOME", data.toString()),
                Map.entry("XDG_STATE_HOME", state.toString()),
                Map.entry("TMPDIR", tmp + "/"),
                Map.entry("TMP", tmp.toString()),
                Map.entry("TEMP", tmp.toString())));
        final var handle = new SandboxCommand(builder.start(), released);
        handle.process().onExit().thenRun(handle::close);
        return handle;
    }

    private static Path anchor(final Path path) {
        Objects.requireNonNull(path, "anchor");
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("macOS sandbox anchors must be absolute and normalized: " + path);
        }
        return path;
    }

    private static List<String> compile(
            final List<String> entries, final Map<String, Path> anchors, final Path project) {
        return Objects.requireNonNull(entries, "rules").stream()
                .map(entry -> filter(entry, anchors, project))
                .distinct()
                .toList();
    }

    private static String filter(final String entry, final Map<String, Path> anchors, final Path project) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException("Empty macOS sandbox path rule");
        }
        final var glob = entry.startsWith("glob:");
        var value = glob ? entry.substring(5) : entry;
        value = VARIABLE.matcher(value).replaceAll(match -> {
            final var replacement = anchors.get(match.group(1));
            if (replacement == null) {
                throw new IllegalArgumentException("Unknown macOS sandbox anchor: " + match.group());
            }
            return Matcher.quoteReplacement(glob ? escapeGlob(replacement.toString()) : replacement.toString());
        });
        if (value.isBlank() || value.contains("${")) {
            throw new IllegalArgumentException("Malformed macOS sandbox path rule: " + entry);
        }
        if (value.startsWith("~/")) {
            final var home = anchor(Path.of(System.getProperty("user.home"))).toString();
            value = (glob ? escapeGlob(home) : home) + value.substring(1);
        }
        if (!value.startsWith("/")) {
            value = (glob ? escapeGlob(project.toString()) : project.toString()) + "/" + value;
        }
        requirePrintable(value);
        if (!glob) {
            final var path = Path.of(value).normalize();
            // A root subpath would be an ambient whole-machine grant.
            if (path.getParent() == null) {
                throw new SecurityException("Whole-filesystem macOS sandbox rules are unsupported");
            }
            return "(subpath " + quote(path.toString()) + ")";
        }
        return "(regex " + quote(globRegex(value)) + ")";
    }

    private static String globRegex(final String glob) {
        // Empty/dot components and trailing slashes have path-normalization ambiguities.
        for (final var component : glob.substring(1).split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException("Non-normalized macOS sandbox glob: " + glob);
            }
        }
        final var regex = new StringBuilder("^");
        for (var i = 0; i < glob.length(); i++) {
            final var c = glob.charAt(i);
            if (c > 0x7e) {
                throw new IllegalArgumentException("Non-ASCII macOS sandbox globs require native regex qualification");
            }
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        if (i + 2 < glob.length() && glob.charAt(i + 2) == '*') {
                            throw new IllegalArgumentException("Ambiguous star sequence in macOS sandbox glob");
                        }
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '\\' -> {
                    if (++i == glob.length() || "\\*?[]{}".indexOf(glob.charAt(i)) < 0) {
                        throw new IllegalArgumentException("Unsupported escape in macOS sandbox glob");
                    }
                    regexLiteral(regex, glob.charAt(i));
                }
                case '[', ']', '{', '}' ->
                    throw new IllegalArgumentException(
                            "Character classes and alternation are unsupported in macOS sandbox globs");
                default -> regexLiteral(regex, c);
            }
        }
        // JVM policy matches the path AND each ancestor. A matching directory denies its subtree.
        // In JVM glob semantics **/ includes a slash: it is not gitignore's optional directory.
        return regex.append("(/[^/]+)*$").toString();
    }

    private static void regexLiteral(final StringBuilder out, final char c) {
        if (".\\+*?[^]$(){}|".contains(String.valueOf(c))) {
            out.append('\\');
        }
        out.append(c);
    }

    private static String escapeGlob(final String value) {
        final var out = new StringBuilder();
        for (final var c : value.toCharArray()) {
            if ("\\*?[]{}".contains(String.valueOf(c))) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void requirePrintable(final String value) {
        for (final var c : value.toCharArray()) {
            if (Character.isISOControl(c) || Character.isSurrogate(c)) {
                throw new IllegalArgumentException("Control characters and surrogate code units in sandbox rule");
            }
        }
    }

    private static String quote(final String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static List<String> union(final List<String> first, final List<String> second) {
        final var all = new ArrayList<>(first);
        all.addAll(second);
        return all.stream().distinct().toList();
    }

    private static void allow(
            final StringBuilder result,
            final String operations,
            final List<String> grants,
            final List<String> denials) {
        if (grants.isEmpty()) {
            return;
        }
        result.append("(allow ").append(operations).append("\n  (require-all\n    (require-any");
        grants.forEach(filter -> result.append("\n      ").append(filter));
        result.append(')');
        if (!denials.isEmpty()) {
            result.append("\n    (require-not (require-any");
            denials.forEach(filter -> result.append("\n      ").append(filter));
            result.append("))");
        }
        result.append("))\n");
    }
}
