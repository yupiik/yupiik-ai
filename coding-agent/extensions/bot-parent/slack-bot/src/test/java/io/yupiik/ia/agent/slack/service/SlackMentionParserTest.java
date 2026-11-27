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
package io.yupiik.ia.agent.slack.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SlackMentionParserTest {
    private final SlackMentionParser parser = new SlackMentionParser();

    @Test
    void naturalLanguageDoesNotRequireACommandKeyword() {
        assertEquals(
                "Could you explain this error?",
                parser.parse("<@UBOT> Could you explain this error?", "UBOT", 100)
                        .orElseThrow());
        assertEquals(
                "Please  explain this",
                parser.parse("Please <@UBOT> explain this", "UBOT", 100).orElseThrow());
    }

    @Test
    void mentionsAreExactProtocolLiterals() {
        for (final var input : new String[] {
            "@UBOT help", "<@UBOT2> help", "<@UBOT|name> help", "<@U.*> help", "no mention", "<@UBOT> \n\t"
        }) {
            assertTrue(parser.parse(input, "UBOT", 100).isEmpty(), input);
        }
        assertTrue(parser.parse("<@U.*> help", "U.*", 100).isEmpty());
        assertTrue(parser.parse(null, "UBOT", 100).isEmpty());
        assertTrue(parser.parse("<@UBOT> help", null, 100).isEmpty());
    }

    @Test
    void exactBoundsAndCheapRejectPaths() {
        final var input = "<@UBOT> x";
        assertEquals("x", parser.parse(input, "UBOT", input.length()).orElseThrow());
        assertTrue(parser.parse(input, "UBOT", input.length() - 1).isEmpty());
        assertTrue(parser.parse("<".repeat(100000), "UBOT", 100).isEmpty());
        assertTrue(parser.parse("<@".repeat(50000), "UBOT", 100000).isEmpty());
        assertTrue(parser.parse("<@UBOT> x", "U" + "A".repeat(100000), 100).isEmpty());
        assertTrue(parser.parse("<@UBOT> x", "U" + "A".repeat(62) + "!", 100).isEmpty());
    }
}
