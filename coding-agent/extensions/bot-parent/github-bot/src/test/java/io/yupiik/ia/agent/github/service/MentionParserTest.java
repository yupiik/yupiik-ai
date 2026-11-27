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
package io.yupiik.ia.agent.github.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MentionParserTest {
    private final MentionParser parser = new MentionParser();

    @Test
    void cheapRejections() {
        assertTrue(parser.parse(null, "bot").isEmpty());
        assertTrue(parser.parse("@bot solve", null).isEmpty());
        assertTrue(parser.parse("@bot solve", " \t").isEmpty());
        assertTrue(parser.parse("", "bot").isEmpty());
        assertTrue(parser.parse("no mention", "bot").isEmpty());
        assertTrue(parser.parse("@bot solve\n<!-- yca-task:id -->", "bot").isEmpty());
    }

    @Test
    void immediateCommandsAndAsciiBoundaries() {
        for (final var text : new String[] {
            "@bot, solve",
            "@bot can you review this?",
            "@bot\nsolve",
            "@bot\r\nsolve",
            "@bot\u00a0solve",
            "@bot solve!",
            "@bot solve\u00a0request",
            "@bot \u017folve",
            "@bot f\u0131x",
            "_@bot solve",
            "/@bot solve",
            "@@bot solve",
            "@bot_ solve",
            "@bot[bot][bot] solve",
            "@bot[bot]_ solve"
        }) {
            assertTrue(parser.parse(text, "bot").isEmpty(), text);
        }
        assertEquals(
                new MentionParser.Invocation("review", "request)"),
                parser.parse("@other help\n(@BoT\t ReViEw\trequest)", "bot").orElseThrow());
        assertEquals(
                new MentionParser.Invocation("help", ""),
                parser.parse("@YCA[BOT] HELP", "yca[bot]").orElseThrow());
        assertTrue(parser.parse("@bot unknown\n@other help\n@bot solve", "bot").isEmpty());
    }

    @Test
    void preservesLineSeparatorsAndCodeState() {
        for (final var separator : new String[] {"\n", "\r", "\r\n", "\u000b", "\f", "\u0085", "\u2028", "\u2029"}) {
            assertEquals(
                    new MentionParser.Invocation("fix", "real"),
                    parser.parse("> @bot solve" + separator + "@bot fix real", "bot")
                            .orElseThrow());
        }
        assertEquals(
                new MentionParser.Invocation("help", "real"),
                parser.parse(
                                "````java\n@bot solve\n```\n@bot review\n```` trailing\n@bot fix\n`````\n@bot help real",
                                "bot")
                        .orElseThrow());
        assertEquals(
                new MentionParser.Invocation("help", "real"),
                parser.parse("``hidden `\n@bot solve\n`` @bot help real", "bot").orElseThrow());
        assertEquals(
                new MentionParser.Invocation("solve", "before        after"),
                parser.parse("@bot solve before `code` after", "bot").orElseThrow());
    }

    @Test
    void longNearMatchingLoginIsBounded() {
        final var text = "@" + "a".repeat(250_000) + "_ solve\n@bot help";
        assertEquals("help", parser.parse(text, "bot").orElseThrow().kind());
    }

    @Test
    void manyNearMatchingMentionsAreBounded() {
        final var text = "@bot-else solve @bot_ help @bot[bot]_ fix ".repeat(10_000) + "@bot review";
        assertEquals("review", parser.parse(text, "bot").orElseThrow().kind());
    }

    @Test
    void longDelimiterRunsAndUnclosedCodeAreBounded() {
        final var text = "`".repeat(100_000) + "\n@bot solve\n" + "~".repeat(100_000) + "\n@bot fix";
        assertTrue(parser.parse(text, "bot").isEmpty());
    }

    @Test
    void largeRequestAndLateMarkerAreBounded() {
        final var request = "x".repeat(250_000);
        final var text = "@bot solve " + request;
        assertEquals(request, parser.parse(text, "bot").orElseThrow().request());
        final var marked = text + "\n<!-- yca-task:late -->";
        assertTrue(parser.parse(marked, "bot").isEmpty());
    }

    @Test
    void fixIsExplicitAndUsesTheSameVisibilityAndBoundaryRules() {
        assertEquals(
                new MentionParser.Invocation("fix", "the failing test"),
                parser.parse("@bot FiX the failing test", "bot").orElseThrow());
        assertEquals(
                new MentionParser.Invocation("fix", ""),
                parser.parse("@bot fix", "bot").orElseThrow());
        for (final var text : new String[] {
            "@bot fixes",
            "@bot fixup",
            "> @bot fix",
            "`@bot fix`",
            "```\n@bot fix\n```",
            "@bot fix\n<!-- yca-task:id -->",
            "@bot unknown\n@bot fix"
        }) {
            assertTrue(parser.parse(text, "bot").isEmpty(), text);
        }
    }

    @Test
    void commands() {
        assertEquals(
                new MentionParser.Invocation("solve", "fix this"),
                parser.parse("Please @Bot solve fix this", "bot").orElseThrow());
        assertEquals(
                new MentionParser.Invocation("review", ""),
                parser.parse("@bot review", "bot").orElseThrow());
        assertEquals("help", parser.parse("@bot HELP", "bot").orElseThrow().kind());
        assertTrue(parser.parse("@bot deploy now", "bot").isEmpty());
        assertTrue(parser.parse("@bot solver", "bot").isEmpty());
        assertTrue(parser.parse("@bot-else solve", "bot").isEmpty());
        assertTrue(parser.parse("x@bot solve", "bot").isEmpty());
        assertTrue(parser.parse("@bot[bot] solve", "bot").isEmpty());
        assertTrue(parser.parse("@bot unknown\n@bot solve", "bot").isEmpty());
        assertTrue(parser.parse(null, "bot").isEmpty());
    }

    @Test
    void ignoresTaskMarkedOutputRegardlessOfMentionLocationOrMarkerKind() {
        for (final var marker : new String[] {"<!-- yca-task:task-id -->", "<!-- yca-task:task-id:review -->"}) {
            assertTrue(parser.parse(marker + "\nMention @bot review on a pull request", "bot")
                    .isEmpty());
            assertTrue(parser.parse("@bot solve fix this\n" + marker, "bot").isEmpty());
        }
        assertTrue(parser.parse("Mention `@bot review` or `@bot solve <request>`", "bot")
                .isEmpty());
    }

    @Test
    void ignoresQuotedAndCodeMentions() {
        final var text = "> @bot solve quoted\n```java\n@bot solve fenced\n```\n"
                + "~~~\n@bot solve tilde\n~~~\n`@bot solve inline` and ``@bot review code``\n"
                + "@bot review real request";
        assertEquals(
                new MentionParser.Invocation("review", "real request"),
                parser.parse(text, "bot").orElseThrow());
        assertTrue(parser.parse("```\n@bot solve", "bot").isEmpty());
        assertTrue(parser.parse("`@bot solve`", "bot").isEmpty());
        assertEquals(
                "help", parser.parse("@yca[bot] help", "yca[bot]").orElseThrow().kind());
    }
}
