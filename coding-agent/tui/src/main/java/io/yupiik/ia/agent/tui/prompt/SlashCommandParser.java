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
package io.yupiik.ia.agent.tui.prompt;

import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import java.util.List;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;

/** Keeps literal, whole-argument command completions in one JLine replacement word. */
final class SlashCommandParser extends DefaultParser {
    private final SlashRegistry registry;

    SlashCommandParser(final SlashRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ParsedLine parse(final String line, final int cursor, final ParseContext context) {
        int commandEnd = 0;
        while (commandEnd < line.length() && !Character.isWhitespace(line.charAt(commandEnd))) {
            commandEnd++;
        }
        if (!line.startsWith("/")
                || commandEnd == line.length()
                || !registry.completionFor(line.substring(1, commandEnd)).wholeArgument()) {
            return super.parse(line, cursor, context);
        }
        int start = commandEnd;
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        if (cursor < start) {
            return super.parse(line, cursor, context);
        }
        final var argumentStart = start;
        final var command = line.substring(0, commandEnd);
        final var argument = line.substring(start);
        return new CompletingParsedLine() {
            @Override
            public String word() {
                return argument;
            }

            @Override
            public int wordCursor() {
                return cursor - argumentStart;
            }

            @Override
            public int wordIndex() {
                return 1;
            }

            @Override
            public List<String> words() {
                return List.of(command, argument);
            }

            @Override
            public String line() {
                return line;
            }

            @Override
            public int cursor() {
                return cursor;
            }

            @Override
            public CharSequence escape(final CharSequence candidate, final boolean complete) {
                // Slash actions receive the literal remainder, not shell-quoted tokens.
                return candidate;
            }

            @Override
            public int rawWordCursor() {
                return wordCursor();
            }

            @Override
            public int rawWordLength() {
                return argument.length();
            }
        };
    }
}
