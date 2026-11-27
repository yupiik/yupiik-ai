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
package io.yupiik.ia.agent.core.tools;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Git-like ignore rules shared by the file tools (grep/glob) and the code graph: configured globs
 * merged with the root {@code .gitignore}, last-match-wins with {@code !} negation support.
 */
public final class FileIgnore {

    private static final Logger LOGGER = Logger.getLogger(FileIgnore.class.getName());

    /** An ignore rule; {@code rootAnchored} and {@code negated} mirror gitignore semantics. */
    public record Rule(boolean negated, PathMatcher matcher, boolean rootAnchored) {}

    private final Path root;
    private final List<Rule> rules;

    private FileIgnore(final Path root, final List<Rule> rules) {
        this.root = root;
        this.rules = rules;
    }

    /** Builds rules from {@code globs} first then the root {@code .gitignore} (later rules win). */
    public static FileIgnore of(final Path root, final List<String> globs) {
        Objects.requireNonNull(root, "workdir");
        final var patterns = new ArrayList<String>();
        if (globs != null) {
            patterns.addAll(globs);
        }
        appendGitignore(root, patterns);
        final var rules = new ArrayList<Rule>();
        for (final var raw : patterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            final var p = raw.trim();
            if (p.startsWith("#")) {
                continue;
            }
            final var negation = p.startsWith("!");
            final var body = negation ? p.substring(1) : p;
            if (body.isBlank()) {
                continue;
            }
            final var rootAnchored = body.startsWith("/");
            final var glob = normalizeGitignoreToGlob(rootAnchored ? body.substring(1) : body, rootAnchored);
            rules.add(new Rule(negation, FileSystems.getDefault().getPathMatcher("glob:" + glob), rootAnchored));
            // a dir-only rule like "target/**" matches paths under target but not the bare "target"
            // directory itself, so preVisitDirectory SKIP_SUBTREE would never prune it; register a
            // companion rule for the bare directory so the whole subtree is pruned fast.
            if (glob.endsWith("/**")) {
                final var dirGlob = glob.substring(0, glob.length() - 3);
                if (!dirGlob.isEmpty()) {
                    rules.add(new Rule(
                            negation, FileSystems.getDefault().getPathMatcher("glob:" + dirGlob), rootAnchored));
                }
            }
        }
        return new FileIgnore(root, List.copyOf(rules));
    }

    /** Nearest ancestor of {@code from} (or itself) holding a {@code .git} entry; fallback is {@code from}. */
    public static Path gitRoot(final Path from) {
        var current = Objects.requireNonNull(from, "workdir");
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return from;
    }

    public List<Rule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** True when {@code absolute} (resolved against the rule root) matches any rule. */
    public boolean isIgnored(final Path absolute) {
        if (rules.isEmpty() || absolute == null) {
            return false;
        }
        final String rel;
        try {
            rel = root.toAbsolutePath()
                    .normalize()
                    .relativize(absolute.toAbsolutePath().normalize())
                    .toString();
        } catch (final RuntimeException e) {
            return false;
        }
        return isIgnoredRelative(rel);
    }

    /** True when the path {@code rel} (relative to the rule root, either separator) matches any rule. */
    public boolean isIgnoredRelative(final String rel) {
        if (rules.isEmpty() || rel == null || rel.isEmpty() || "..".equals(rel)) {
            return false;
        }
        if (rel.startsWith("../") || rel.startsWith("..\\")) {
            return false; // outside the rule root: not our business
        }
        final var relP = Path.of(rel.replace('\\', '/'));
        var ignored = false;
        for (final var rule : rules) {
            if (matches(rule, relP)) {
                ignored = !rule.negated();
            }
        }
        return ignored;
    }

    private static boolean matches(final Rule rule, final Path relP) {
        if (rule.rootAnchored()) {
            return rule.matcher().matches(relP);
        }
        // unanchored pattern should match at any depth: try the relative path and, as a fallback, a synthetic
        // '/' + rel so a leading-**-style glob resolves; the glob normalized with ** already covers depth
        return rule.matcher().matches(relP) || rule.matcher().matches(Path.of("/", relP.toString()));
    }

    /** Public for reuse by the code graph index walker: last-match-wins rule evaluation for one path. */
    public static boolean matchesRule(final Rule rule, final Path relP) {
        return matches(rule, relP);
    }

    private static void appendGitignore(final Path root, final List<String> patterns) {
        final var gitignore = root.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            return;
        }
        try (final Stream<String> lines = Files.lines(gitignore)) {
            patterns.addAll(lines.toList());
        } catch (final IOException e) {
            LOGGER.fine(() -> "Cannot read " + gitignore + ": " + e.getMessage());
        }
    }

    // Convert a gitignore pattern to a single java.nio glob: a bare name (no slash) becomes '**/name' so it
    // matches at any depth; a leading '/' marks root-anchored; a trailing '/' marks directory-only.
    private static String normalizeGitignoreToGlob(final String body, final boolean rootAnchored) {
        final var trimmed = body.endsWith("/") && body.length() > 1 ? body.substring(0, body.length() - 1) : body;
        final var noSlash = !trimmed.contains("/");
        return noSlash ? "**/" + trimmed : trimmed;
    }
}
