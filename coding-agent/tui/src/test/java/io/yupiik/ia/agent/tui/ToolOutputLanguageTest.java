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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ToolOutputLanguageTest {
    private static final Pattern LEGACY_FILE_REFERENCE = Pattern.compile("[A-Za-z0-9_./~+-]+\\.[A-Za-z0-9_]{1,8}\\b");

    @Test
    void commandProgramWins() {
        assertEquals("python", ToolOutputLanguage.guess("42\n", "python3 -c 'print(42)'"));
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "pip install -r requirements.txt"));
        assertEquals("javascript", ToolOutputLanguage.guess("ok\n", "node app.js"));
        assertEquals("sql", ToolOutputLanguage.guess("ok\n", "sqlite3 db.sqlite < q.sql"));
    }

    /** The model runs everything through a shell, so the interpreter behind it is what counts. */
    @Test
    void shellWrapperIsFollowed() {
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "sh -c 'python3 script.py'"));
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "cd /tmp && python3 script.py"));
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "PYTHONUNBUFFERED=1 python3 script.py"));
        assertEquals("python", ToolOutputLanguage.guess("ok\n", "/usr/bin/python3 script.py"));
    }

    /** cat/grep/sh only print: their output is not shell code, better no color than a wrong one. */
    @Test
    void printingCommandsStayUncolored() {
        assertNull(ToolOutputLanguage.guess("total 8\n", "ls -la"));
        assertEquals("plain", ToolOutputLanguage.guess("a\nb\n", "cat out.txt"));
        final var source = "return \"value\" + 42; // comment";
        final var language = ToolOutputLanguage.guess(source, "cat out.txt");
        assertEquals("plain", language);
        assertTrue(new SyntaxHighlighter()
                .highlightLines(language, source).stream()
                        .flatMap(line -> line.spans().stream())
                        .allMatch(span -> span.style().fg().isEmpty()));
        assertNull(ToolOutputLanguage.guess("[INFO] BUILD SUCCESS\n", "mvn -q test"));
    }

    /** A short command naming a known source file: the output is that file, so its extension wins. */
    @Test
    void shortCommandWithSourceFileIsGuessedByExtension() {
        assertEquals("java", ToolOutputLanguage.guess(" 1\tpublic class Foo {\n", "src/Foo.java"));
        assertEquals("java", ToolOutputLanguage.guess("public class Foo\n", "cat src/Foo.java"));
        assertEquals("python", ToolOutputLanguage.guess("print(1)\n", "cat a.py"));
        assertEquals("javascript", ToolOutputLanguage.guess("const a = 1\n", "cat app.js"));
    }

    /** Multi-token commands only paint for known interpreters: a grep of a file is not the file. */
    @Test
    void multiTokenCommandsWithFileRefsStayUncolored() {
        assertNull(ToolOutputLanguage.guess("src/Foo.java:3: x\n", "grep -n foo src/Foo.java"));
        assertNull(ToolOutputLanguage.guess("line: 1\nline: 2\n", "grep -rn foo src"));
    }

    @Test
    void shebangOfPrintedScript() {
        assertEquals("python", ToolOutputLanguage.guess("#!/usr/bin/env python3\nprint(1)\n", "cat x.py"));
        assertEquals("python", ToolOutputLanguage.guess("#!/usr/bin/python\nprint(1)\n", null));
        assertNull(ToolOutputLanguage.guess("#!/bin/awk -f x.awk\n{ print }\n", null));
    }

    @Test
    void pythonTracebackIsPython() {
        assertEquals(
                "python",
                ToolOutputLanguage.guess(
                        "Traceback (most recent call last):\n  File \"a.py\", line 3, in <module>\n    print(1/0)\nZeroDivisionError: division by zero",
                        null));
    }

    @Test
    void sqlStatementIsSql() {
        assertEquals("sql", ToolOutputLanguage.guess("SELECT id FROM users WHERE id = 1\n", null));
    }

    @Test
    void unambiguousFileExtension() {
        assertEquals("python", ToolOutputLanguage.guess("a.py:1:1: E\nb.py:2:1: E\n", null));
    }

    /** Mixed or one-off references must not paint a whole log. */
    @Test
    void ambiguousOrSingleReferencesStayUncolored() {
        assertNull(ToolOutputLanguage.guess("a.py:1\n", null));
        assertNull(ToolOutputLanguage.guess("a.py:1\nb.json:2\n", null));
        assertNull(ToolOutputLanguage.guess("see README.md\n", null));
    }

    /** Two agreeing document references paint the output as a document language. */
    @Test
    void documentReferencesAreGuessed() {
        assertEquals("asciidoc", ToolOutputLanguage.guess("a.adoc\nb.adoc\n", null));
        assertEquals("asciidoc", ToolOutputLanguage.guess("see README.adoc and CONTRIBUTING.asciidoc\n", null));
        assertEquals("markdown", ToolOutputLanguage.guess("see README.md\nand CHANGELOG.md\n", null));
    }

    @Test
    void pathPunctuationAndBoundaries() {
        for (final var separator : new String[] {":12:3", ",", ";", "'", "\"", ")", "]", "!", "\\", "\n", "\u00e9"}) {
            assertEquals("python", ToolOutputLanguage.guess("~/a+b-c_1.py" + separator + " b.py", null), separator);
        }
        for (final var suffix : new String[] {"0", "_", "wxyz", "123456789"}) {
            assertNull(ToolOutputLanguage.guess("a.py" + suffix + " b.py", null), suffix);
        }
        assertNull(ToolOutputLanguage.guess(".py .py", null));
        assertEquals("python", ToolOutputLanguage.guess("..py /.py", null));
        assertEquals("python", ToolOutputLanguage.guess("a.py/b b.py/tail", null));
        assertNull(ToolOutputLanguage.guess("a.py\u0301 b.py", null));
    }

    @Test
    void greedyPathsKeepUnknownAndAmbiguousExtensions() {
        assertNull(ToolOutputLanguage.guess("a.py.json b.py", null));
        assertNull(ToolOutputLanguage.guess("a.py.unknown b.py", null));
        assertNull(ToolOutputLanguage.guess("a.py/b.json b.py", null));
        assertEquals("python", ToolOutputLanguage.guess("a.json.py b.py", null));
        assertEquals("python", ToolOutputLanguage.guess("a.py.abcdefghi b.py", null));
        assertEquals("python", ToolOutputLanguage.guess("a.py b.py ignored.css unknown.xyz", null));
        assertNull(ToolOutputLanguage.guess("a.py b.py c.json", null));
    }

    @Test
    void extensionLengthLimits() {
        assertNull(ToolOutputLanguage.guess("a.p b.p", null));
        assertEquals("markdown", ToolOutputLanguage.guess("a.markdown b.markdown", null));
        assertNull(ToolOutputLanguage.guess("a.markdownx b.markdownx", null));
        assertNull(ToolOutputLanguage.guess("a.properties b.properties", null));
        assertEquals("asciidoc", ToolOutputLanguage.guess("a.ASCIIDOC b.asciidoc", null));
    }

    @Test
    void boundedAdversarialPathRuns() {
        assertNull(ToolOutputLanguage.guess("x".repeat(8192), null));
        assertNull(ToolOutputLanguage.guess("/".repeat(8192), null));
        assertNull(ToolOutputLanguage.guess(".".repeat(8192), null));
        assertNull(ToolOutputLanguage.guess("x." + "a".repeat(8190), null));
        assertNull(ToolOutputLanguage.guess("x.abcdefghi/".repeat(680), null));
        assertEquals("python", ToolOutputLanguage.guess("x".repeat(8179) + ".py b.py", null));
        assertNull(ToolOutputLanguage.guess("x".repeat(8192) + " a.py b.py", null));
    }

    @Test
    void scannerAgreesWithLegacyRegexOnDeterministicInputs() {
        final var random = new Random(731);
        final var fragments = new String[] {
            "a",
            ".",
            "/",
            "~",
            "+",
            "-",
            "_",
            " ",
            ":",
            ",",
            "\\",
            "\u00e9",
            "\u0301",
            ".py",
            ".json",
            ".md",
            ".markdown",
            ".properties",
            ".unknown",
            ".css",
            ".PY",
            "123456789"
        };
        for (var sample = 0; sample < 1000; sample++) {
            final var output = new StringBuilder();
            for (var part = 0; part < 20; part++) {
                output.append(fragments[random.nextInt(fragments.length)]);
            }
            final var text = output.toString();
            final var matcher = LEGACY_FILE_REFERENCE.matcher(text);
            String expected = null;
            var hits = 0;
            while (matcher.find()) {
                final var candidate = SyntaxHighlighter.guessLanguage(matcher.group());
                if (candidate == null || "css".equals(candidate)) {
                    continue;
                }
                if (expected != null && !expected.equals(candidate)) {
                    hits = 0;
                    break;
                }
                expected = candidate;
                hits++;
            }
            assertEquals(hits >= 2 ? expected : null, ToolOutputLanguage.guess(text, null), text);
        }
    }

    @Test
    void keywordDensity() {
        assertEquals("python", ToolOutputLanguage.guess("def main():\n    import os\n    print('x')\n", null));
        assertEquals(
                "javascript",
                ToolOutputLanguage.guess("const a = 1 => 1;\nfunction f() { const b = 2 => 2; }\n", null));
        assertEquals(
                "java",
                ToolOutputLanguage.guess(
                        "public class Main {\n"
                                + "  public static void main(String[] args) {\n"
                                + "    System.out.println(\"hi\");\n"
                                + "    return;\n"
                                + "  }\n"
                                + "}\n",
                        null));
    }

    @Test
    void plainLogsAreLeftAlone() {
        assertNull(ToolOutputLanguage.guess("2026-09-21 10:00:00 INFO boom\ndone", null));
        assertNull(ToolOutputLanguage.guess("", null));
        assertNull(ToolOutputLanguage.guess(null, "python3 x.py"));
    }
}
