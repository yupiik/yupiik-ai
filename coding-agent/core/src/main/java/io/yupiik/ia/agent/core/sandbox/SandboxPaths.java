/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiled literal/explicit JVM glob rules, anchored once, never to a command's cwd. */
public final class SandboxPaths {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");
    private final List<Rule> allowRead;
    private final List<Rule> allowWrite;
    private final List<Rule> denyRead;
    private final List<Rule> denyWrite;

    public SandboxPaths(
            final List<String> allowRead,
            final List<String> allowWrite,
            final List<String> denyRead,
            final List<String> denyWrite,
            final Path workdir,
            final Path projectDir,
            final Path tempDir) {
        final var anchors = Map.of("workdir", workdir, "projectdir", projectDir, "tempdir", tempDir);
        this.allowRead = compile(allowRead, anchors, projectDir);
        this.allowWrite = compile(allowWrite, anchors, projectDir);
        this.denyRead = compile(denyRead, anchors, projectDir);
        this.denyWrite = compile(denyWrite, anchors, projectDir);
    }

    public boolean denied(final Path path, final boolean write) {
        final var lexical = path.toAbsolutePath().normalize();
        final var actual = canonical(lexical);
        return matches(denyRead, lexical)
                || matches(denyRead, actual)
                || (write && (matches(denyWrite, lexical) || matches(denyWrite, actual)));
    }

    public boolean allowed(final Path path, final boolean write) {
        final var lexical = path.toAbsolutePath().normalize();
        final var actual = canonical(lexical);
        return !denied(path, write)
                && matches(write ? allowWrite : allowRead, lexical)
                && matches(write ? allowWrite : allowRead, actual);
    }

    public List<Path> readRoots() {
        return roots(allowRead);
    }

    public List<Path> writeRoots() {
        return roots(allowWrite);
    }

    public List<Path> readDenials() {
        return roots(denyRead);
    }

    public List<Path> writeDenials() {
        return roots(denyWrite);
    }

    /** Fully anchored rules, independent of a command's working directory. */
    public Map<String, List<String>> rules() {
        return Map.of(
                "read", allowRead.stream().map(Rule::value).toList(),
                "write", allowWrite.stream().map(Rule::value).toList(),
                "denyRead", denyRead.stream().map(Rule::value).toList(),
                "denyWrite", denyWrite.stream().map(Rule::value).toList());
    }

    /** Conservative roots used for configuration validation, never as glob grants. */
    public List<Path> exposureRoots(final boolean write) {
        return (write ? allowWrite : allowRead)
                .stream()
                        .map(rule ->
                                rule.literal() == null ? globRoot(rule.value().substring(5)) : rule.literal())
                        .distinct()
                        .toList();
    }

    private static Path globRoot(final String glob) {
        var escaped = false;
        var slash = 0;
        final var literal = new StringBuilder();
        for (var i = 0; i < glob.length(); i++) {
            final var c = glob.charAt(i);
            if (!escaped && c == '\\') {
                escaped = true;
                continue;
            }
            if (!escaped && "*?[{".contains(String.valueOf(c))) {
                return Path.of(literal.substring(0, Math.max(1, slash))).normalize();
            }
            literal.append(c);
            if (c == '/') {
                slash = literal.length() - 1;
            }
            escaped = false;
        }
        return Path.of(literal.toString()).normalize();
    }

    /**
     * Bubblewrap's explicit snapshot contract: expand today's matches without following symlinks.
     * New matches are considered by the next command, not by the already-running namespace.
     */
    public Map<String, List<Path>> mountSnapshot() {
        return Map.of(
                "read",
                expand(allowRead),
                "write",
                expand(allowWrite),
                "denyRead",
                expand(denyRead),
                "denyWrite",
                expand(denyWrite));
    }

    private static List<Path> expand(final List<Rule> rules) {
        final var result = new LinkedHashSet<Path>();
        final var globs = new LinkedHashMap<Path, List<Rule>>();
        for (final var rule : rules) {
            if (rule.literal() != null) {
                result.add(rule.literal());
            } else {
                globs.computeIfAbsent(globRoot(rule.value().substring(5)), ignored -> new ArrayList<>())
                        .add(rule);
            }
        }
        for (final var entry : globs.entrySet()) {
            final var root = entry.getKey();
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            final Path directory, final BasicFileAttributes attributes) {
                        if (entry.getValue().stream()
                                .anyMatch(rule -> rule.matcher().matches(directory))) {
                            result.add(directory);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
                        if (entry.getValue().stream()
                                .anyMatch(rule -> rule.matcher().matches(file))) {
                            result.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (final IOException error) {
                throw new SecurityException("Cannot snapshot sandbox glob below " + root, error);
            }
        }
        return List.copyOf(result);
    }

    public static Path canonical(final Path path) {
        if (path.isAbsolute() && !path.equals(path.normalize())) {
            throw new SecurityException("Sandbox paths must be normalized before resolution: " + path);
        }
        final var absolute = path.toAbsolutePath().normalize();
        var ancestor = absolute;
        final var suffix = new ArrayList<Path>();
        try {
            while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                suffix.add(ancestor.getFileName());
                ancestor = ancestor.getParent();
                if (ancestor == null) {
                    throw new SecurityException("Cannot resolve sandbox path: " + path);
                }
            }
            var resolved = ancestor.toRealPath();
            for (final var part : suffix.reversed()) {
                resolved = resolved.resolve(part);
            }
            return resolved.normalize();
        } catch (final IOException error) {
            throw new SecurityException("Cannot resolve sandbox path: " + path, error);
        }
    }

    private static List<Path> roots(final List<Rule> rules) {
        return rules.stream()
                .filter(r -> r.matcher() == null)
                .map(Rule::literal)
                .distinct()
                .toList();
    }

    private static boolean matches(final List<Rule> rules, final Path path) {
        for (final var rule : rules) {
            for (var current = path; current != null; current = current.getParent()) {
                if (rule.matcher() == null
                        ? current.equals(rule.literal())
                        : rule.matcher().matches(current)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Rule> compile(final List<String> entries, final Map<String, Path> anchors, final Path project) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> {
                    if (entry == null || entry.isBlank()) {
                        throw new IllegalArgumentException("Empty sandbox path rule");
                    }
                    final var glob = entry.startsWith("glob:");
                    var value = glob ? entry.substring(5) : entry;
                    final var variables = VARIABLE.matcher(value);
                    value = variables.replaceAll(match -> {
                        final var replacement = anchors.get(match.group(1));
                        if (replacement == null) {
                            throw new IllegalArgumentException("Unknown sandbox anchor: " + match.group());
                        }
                        return Matcher.quoteReplacement(
                                glob ? escapeGlob(replacement.toString()) : replacement.toString());
                    });
                    if (value.contains("${")) {
                        throw new IllegalArgumentException("Malformed sandbox anchor: " + entry);
                    }
                    if (value.startsWith("~/")) {
                        final var home = System.getProperty("user.home");
                        value = (glob ? escapeGlob(home) : home) + value.substring(1);
                    }
                    if (!Path.of(value).isAbsolute()) {
                        value = (glob ? escapeGlob(project.toString()) : project.toString()) + "/" + value;
                    }
                    if (glob) {
                        return new Rule(
                                null, FileSystems.getDefault().getPathMatcher("glob:" + value), "glob:" + value);
                    }
                    final var literal = Path.of(value).normalize();
                    return new Rule(literal, null, literal.toString());
                })
                .toList();
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

    private record Rule(Path literal, PathMatcher matcher, String value) {}
}
