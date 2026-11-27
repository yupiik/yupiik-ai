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
package io.yupiik.ia.agent.core.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GitOutputProcessorTest {
    private final GitOutputProcessor processor = new GitOutputProcessor();

    @Test
    void supportsGitOnly() {
        assertTrue(processor.supports("git status"));
        assertTrue(processor.supports("git -c x=y diff"));
        assertFalse(processor.supports("mvn test"));
    }

    @Test
    void compactsDiff() {
        final var out = processor
                .process(
                        "git diff",
                        List.of(
                                "diff --git a/a.txt b/a.txt",
                                "index 1111111..2222222 100644",
                                "--- a/a.txt",
                                "+++ b/a.txt",
                                "@@ -1,3 +1,4 @@",
                                " one",
                                "+two",
                                " three")
                                .stream())
                .toList();

        assertTrue(out.contains("diff --git a/a.txt b/a.txt"));
        assertFalse(out.stream().anyMatch(l -> l.startsWith("index ")));
        assertTrue(out.contains("@@ -1,3 +1,4 @@"));
        assertTrue(out.contains("+two"));
        assertTrue(out.stream().anyMatch(l -> l.startsWith("[processed diff of 1 file(s)")));
    }

    @Test
    void diffPassthroughWithStat() {
        final var lines = List.of("diff --git a/a.txt b/a.txt", "index 111..222 100644");
        final var out = processor.process("git diff --stat", lines.stream()).toList();
        assertEquals(lines, out);
    }

    @Test
    void compactsLog() {
        final var out = processor
                .process(
                        "git log",
                        List.of(
                                "commit 1234567890abcdef1234567890abcdef12345678",
                                "Author: Me <me@localhost>",
                                "Date:   Fri Sep 5 10:00:00 2026 +0200",
                                "",
                                "    fix the thing",
                                "    second line",
                                "    third line",
                                "    fourth line dropped",
                                "commit aaa",
                                "Author: X <x@localhost>",
                                "",
                                "    another")
                                .stream())
                .toList();

        assertEquals(6, out.size());
        assertEquals("1234567890abc", out.get(0));
        assertEquals("  fix the thing", out.get(1));
        assertEquals("  second line", out.get(2));
        assertEquals("  third line", out.get(3));
        assertEquals("aaa", out.get(4));
        assertFalse(out.stream().anyMatch(l -> l.contains("fourth line dropped")));
        assertFalse(out.stream().anyMatch(l -> l.startsWith("Author:") || l.startsWith("Date:")));
    }

    @Test
    void filtersStatusHints() {
        final var out = processor
                .process(
                        "git status",
                        List.of(
                                "On branch main",
                                "",
                                "Changes not staged for commit:",
                                "  (use \"git add <file>...\" to update what will be committed)",
                                "    modified:   a.txt",
                                "nothing to commit, working tree clean")
                                .stream())
                .toList();

        assertEquals(3, out.size());
        assertTrue(out.contains("On branch main"));
        assertTrue(out.contains("    modified:   a.txt"));
    }

    @Test
    void statusPorcelainPassthrough() {
        final var lines = List.of("## main...origin/main", " M a.txt");
        final var out =
                processor.process("git status --porcelain -b", lines.stream()).toList();
        assertEquals(lines, out);
    }

    @Test
    void showKeepsHeaderAndDiff() {
        final var out = processor
                .process(
                        "git show",
                        List.of(
                                "commit 1234567890abcdef",
                                "Author: Me <me@localhost>",
                                "Date:   Fri Sep 5 10:00:00 2026 +0200",
                                "",
                                "    fix the thing",
                                "diff --git a/a.txt b/a.txt",
                                "index 1111111..2222222 100644",
                                "--- a/a.txt",
                                "+++ b/a.txt",
                                "@@ -1 +1 @@",
                                "-a",
                                "+b")
                                .stream())
                .toList();

        assertTrue(out.contains("commit 1234567890abcdef"));
        assertTrue(out.contains("diff --git a/a.txt b/a.txt"));
        assertTrue(out.contains("+b"));
        assertTrue(out.stream().anyMatch(l -> l.startsWith("Author:")));
    }

    @Test
    void showRawFilePassesThroughWhole() {
        final var lines =
                IntStream.rangeClosed(1, 56).mapToObj(i -> "line " + i).toList();
        final var out = processor
                .process("git show 2.17.0-rc1:core/src/main/java/Foo.java", lines.stream())
                .toList();
        assertEquals(lines, out);
    }

    @Test
    void showBlobWindowedOverBudget() {
        // 56 lines x "line N" ~ 400 chars, well under the 8192 default: full passthrough
        final var small =
                IntStream.rangeClosed(1, 56).mapToObj(i -> "line " + i).toList();
        assertEquals(
                small, processor.process("git show v1:a.txt", small.stream()).toList());

        // force a tiny budget via the typed config: window to a head + recovery hint
        final var configured = new GitOutputProcessor(64);
        final var out =
                configured.process("git show v1:big.txt", small.stream()).toList();
        assertTrue(out.size() < 10);
        assertTrue(out.stream().anyMatch(l -> l.contains("(+") && l.contains("lines truncated")));
        assertTrue(out.stream().anyMatch(l -> l.contains("git show 'v1:big.txt' | sed -n '")));
    }

    @Test
    void showWindowDisabledByZero() {
        final var configured = new GitOutputProcessor(0);
        final var lines = List.of("alpha", "beta", "gamma");
        assertEquals(
                lines, configured.process("git show v1:a.txt", lines.stream()).toList());
    }

    @Test
    void showCommitDiffWithColonInArgsStillCompacts() {
        // Regression: `-S 'a:b'` (pickaxe) and drive-relative paths carry ':' but are NOT
        // rev:path blobs. The output gate (no `diff --git`) must not be bypassed by command
        // sniffing: a real commit diff still compacts.
        final var out = processor
                .process(
                        "git show abc123 -S 'a:b'",
                        List.of(
                                "commit abc123",
                                "Author: Me <me@localhost>",
                                "Date:   Fri Sep 5 10:00:00 2026 +0200",
                                "",
                                "    fix",
                                "diff --git a/a.txt b/a.txt",
                                "index 1111111..2222222 100644",
                                "--- a/a.txt",
                                "+++ b/a.txt",
                                "@@ -1 +1 @@",
                                "-a",
                                "+b")
                                .stream())
                .toList();

        // header + The diff is compacted, not passed through whole.
        assertTrue(out.contains("commit abc123"));
        assertTrue(out.contains("diff --git a/a.txt b/a.txt"));
        assertTrue(out.contains("+b"));
    }

    @Test
    void compactsStashList() {
        final var out = processor
                .process(
                        "git stash list",
                        List.of(
                                "stash@{0}: WIP on main: abc1234 fix the thing",
                                "stash@{1}: On feature: def5678 other work")
                                .stream())
                .toList();
        assertTrue(out.get(0).startsWith("stash@{0}"));
        assertTrue(out.get(0).contains("abc1234"));
        assertTrue(out.get(1).contains("def5678"));
    }

    @Test
    void commitsConfirmWithHash() {
        final var out = processor
                .process(
                        "git commit -m 'fix'",
                        List.of("[main abc1234] fix the thing", " 1 file changed, 2 insertions(+)").stream())
                .toList();
        assertTrue(out.contains("ok abc1234"));
    }

    @Test
    void pushKeepsRemoteLine() {
        final var out = processor
                .process(
                        "git push origin main",
                        List.of(
                                "Enumerating objects: 3, done.",
                                "To github.com:me/repo.git",
                                " * [new branch]      main -> main")
                                .stream())
                .toList();
        assertTrue(out.contains("To github.com:me/repo.git"));
        assertTrue(out.stream().anyMatch(l -> l.contains("main -> main")));
    }

    @Test
    void pullSummariesConfirmations() {
        final var out = processor
                .process(
                        "git pull",
                        List.of(
                                "remote: Enumerating objects: 3, done.",
                                "From github.com:me/repo",
                                "1 file changed, 5 insertions(+), 2 deletions(-)")
                                .stream())
                .toList();
        assertTrue(out.contains("ok 1 files changed"));
        assertFalse(out.stream().anyMatch(l -> l.startsWith("remote:")));
    }
}
