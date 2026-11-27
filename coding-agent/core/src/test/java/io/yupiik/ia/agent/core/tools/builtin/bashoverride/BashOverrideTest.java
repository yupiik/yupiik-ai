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
package io.yupiik.ia.agent.core.tools.builtin.bashoverride;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.BashOverridesConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashOverrideTest {
    private static final List<String> COMMANDS =
            List.of("cat", "ls", "head", "tail", "echo", "pwd", "cd", "sed", "wc", "grep", "find", "rg");

    private static final ExecutorService SHARED_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static BashOverride override(final int cap) {
        final var commands = List.of(
                new JavaCat(),
                new JavaLs(),
                new JavaHead(),
                new JavaTail(),
                new JavaEcho(),
                new JavaPwd(),
                new JavaSed(),
                new JavaWc(),
                new JavaGrep(),
                new JavaRg(),
                new JavaFind());
        final var config = new BashOverridesConfiguration(true, cap, COMMANDS);
        return new BashOverride(new JavaCommandRegistry(commands), config, null, SHARED_EXECUTOR);
    }

    private static void write(final Path base, final String rel, final String content) {
        try {
            final var p = base.resolve(rel);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rewritesASimpleCatChain(@TempDir final Path dir) {
        write(dir, "a.txt", "hello\nworld\n");
        write(dir, "b.txt", "foo\n");
        assertEquals(Optional.of("hello\nworld\nfoo\n"), override(0).tryRewrite("cat a.txt && cat b.txt", dir));
    }

    @Test
    void rewritesLsWithoutRecursion(@TempDir final Path dir) {
        write(dir, "x.txt", "x");
        write(dir, "y.txt", "y");
        final var out = override(0).tryRewrite("ls *.txt", dir).orElse("");
        assertTrue(out.contains("x.txt\n") && out.contains("y.txt\n"), out);
    }

    @Test
    void findReusesNamePatternAcrossRecursionButNotAcrossOperations(@TempDir final Path dir) {
        write(dir, "root/a[1].txt", "x");
        write(dir, "root/a1.txt", "x");
        write(dir, "root/nested/a[1].txt", "x");
        write(dir, "root/nested/b[1].txt", "x");
        final var find = new JavaFind();
        final var out = new StringBuilder();
        assertTrue(find.run(dir, List.of("root", "-name", "a[?].txt", "-type", "f"), out));
        final var result = out.toString();
        assertTrue(result.contains("/root/a[1].txt\n"), result);
        assertTrue(result.contains("/root/nested/a[1].txt\n"), result);
        assertFalse(result.contains("/a1.txt\n"), result);
        assertFalse(result.contains("/b[1].txt\n"), result);

        out.setLength(0);
        assertTrue(find.run(dir, List.of("root", "-name", "b[?].txt", "-maxdepth", "0"), out));
        assertEquals("", out.toString());
        assertTrue(find.run(dir, List.of("root", "-name", "b[?].txt"), out));
        assertTrue(out.toString().endsWith("/root/nested/b[1].txt\n"), out.toString());
    }

    @Test
    void rewritesHeadTailAndSedRange(@TempDir final Path dir) {
        write(dir, "log.txt", "l1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\nl10\n");
        final var o = override(0);
        assertEquals("l1\nl2\nl3\n", o.tryRewrite("head -3 log.txt", dir).orElse(""));
        assertEquals("l9\nl10\n", o.tryRewrite("tail -2 log.txt", dir).orElse(""));
        assertEquals("l4\nl5\n", o.tryRewrite("sed -n '4,5p' log.txt", dir).orElse(""));
        assertEquals("      10 log.txt\n", o.tryRewrite("wc -l log.txt", dir).orElse(""));
    }

    @Test
    void echoesAndPwd(@TempDir final Path dir) {
        final var o = override(0);
        assertEquals(Optional.of("---\n"), o.tryRewrite("echo ---", dir));
        assertEquals(Optional.of(dir.toAbsolutePath().normalize() + "\n"), o.tryRewrite("pwd", dir));
    }

    @Test
    void tracksCdThroughTheChain(@TempDir final Path dir) {
        write(dir, "sub/inner.txt", "inner\n");
        assertEquals(Optional.of("inner\n"), override(0).tryRewrite("cd sub && cat inner.txt", dir));
    }

    @Test
    void delegatesGrepWithLineNumbers(@TempDir final Path dir) {
        write(dir, "app.log", "one\nMATCH two\nthree\nMATCH four\n");
        assertEquals(
                "2:MATCH two\n4:MATCH four\n",
                override(0).tryRewrite("grep -n MATCH app.log", dir).orElse(""));
    }

    @Test
    void honorsUnbalancedParenGrepPattern(@TempDir final Path dir) {
        // a stray literal '(' would break both Java regex and the real grep; the shared repair must
        // single it out so the override honors the pattern instead of falling back to an erroring shell
        write(dir, "app.log", "handleKey( final int a\nother line\n");
        assertEquals(
                "handleKey( final int a\n",
                override(0)
                        .tryRewrite("grep -h 'handleKey( final int a' app.log", dir)
                        .orElse(""));
    }

    @Test
    void capsOutputAtMaxCharsPerCommand(@TempDir final Path dir) {
        final var big = new StringBuilder();
        for (var i = 0; i < 40; i++) {
            big.append("0123456789abcdefghij\n");
        }
        write(dir, "big.txt", big.toString());
        final var out = override(20).tryRewrite("cat big.txt", dir).orElse("");
        // the live content is capped at 20 chars; only the per-command truncation marker may exceed the budget
        assertTrue(out.endsWith("(cat truncated at 20 chars)"), out);
        assertTrue(out.startsWith("0123456789abcdefghij"), "content capped at 20 chars, got: " + out);
    }

    @Test
    void perCommandCapKeepsLaterCommands(@TempDir final Path dir) {
        // a huge first command must not swallow a later survey segment's output
        final var big = new StringBuilder();
        for (var i = 0; i < 400; i++) {
            big.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\n");
        }
        write(dir, "big.txt", big.toString());
        write(dir, "tail.txt", "KEEP\n");
        final var out =
                override(64).tryRewrite("cat big.txt && cat tail.txt", dir).orElse("");
        assertTrue(out.contains("KEEP\n"), "later command output must survive, got: " + out);
        assertTrue(out.contains("(cat truncated at 64 chars)"), out);
    }

    @Test
    void fallsBackOnPipesSubstitutionAndUnknownCommands(@TempDir final Path dir) {
        final var o = override(0);
        write(dir, "a.txt", "x\n");
        assertEquals(Optional.empty(), o.tryRewrite("cat a.txt | head -1", dir)); // pipe
        assertEquals(Optional.empty(), o.tryRewrite("echo $(ls)", dir)); // substitution
        assertEquals(Optional.empty(), o.tryRewrite("cat a.txt > out.txt", dir)); // write redirect
        assertEquals(Optional.empty(), o.tryRewrite("unknowncmd a.txt", dir)); // not overridden
    }

    @Test
    void fallsBackWhenDisabled(@TempDir final Path dir) {
        write(dir, "a.txt", "x\n");
        final var commands = List.<JavaCommand>of(new JavaCat());
        final var disabled = new BashOverride(
                new JavaCommandRegistry(commands),
                new BashOverridesConfiguration(false, 0, List.of("cat")),
                null,
                SHARED_EXECUTOR);
        assertEquals(Optional.empty(), disabled.tryRewrite("cat a.txt", dir));
    }

    @AfterAll
    static void closeExecutor() {
        SHARED_EXECUTOR.close();
    }
}
