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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolCardTest {
    @TempDir
    private static Path eventWorkdir;

    private final DiffRenderer diffRenderer = new DiffRenderer();

    @Test
    void parsesSuccessCard() {
        final var card = ToolCard.parse("*streaming `bash`: ls -la* (123 ms)\n\n```text\nfile1\nfile2\n```");
        assertEquals("bash", card.name());
        assertEquals("ls -la", card.args());
        assertFalse(card.failed());
        assertEquals(123, card.elapsedMs());
        assertEquals("file1\nfile2", card.output());
    }

    @Test
    void parsesOrdinalWithDuration() {
        final var card = ToolCard.parse("*streaming `bash`: ls* (#12, 123 ms)\n\n```text\nout\n```");
        assertEquals(12, card.ordinal());
        assertEquals(123, card.elapsedMs());
        assertEquals("ls", card.args());
    }

    @Test
    void parsesFailedCardWithOrdinal() {
        final var card = ToolCard.parse("> tool `bash` failed: boom (#7, 42 ms)");
        assertTrue(card.failed());
        assertEquals(7, card.ordinal());
        assertEquals(42, card.elapsedMs());
    }

    @Test
    void legacyHeaderHasNoOrdinal() {
        final var card = ToolCard.parse("*streaming `bash`: ls* (123 ms)");
        assertEquals(-1, card.ordinal());
    }

    @Test
    void escapedStarsAreUnescapedInSuccessArgs() {
        // ConversationTranscript escapes the streaming header (part_*.csv.gz + COUNT(*) in a
        // heredoc would otherwise pair into an emphasis and hide both stars); the TUI renders the
        // same header verbatim so the escapes must be undone back to the raw command
        final var card = ToolCard.parse(
                "*streaming `bash`: cat <<'EOF' plain \\* one two \\_ three EOF* (#7, 12 ms)\n\n```text\nout\n```");
        assertEquals("cat <<'EOF' plain * one two _ three EOF", card.args());
    }

    @Test
    void escapedTrailingStarSurvivesStripDuration() {
        // a command ending with a bare star: the escape keeps the duration regex intact, then the
        // unescape restores the star instead of stripDuration eating it
        final var card = ToolCard.parse("*streaming `bash`: echo \\** (123 ms)\n\n```text\n*\n```");
        assertEquals("echo *", card.args());
    }

    @Test
    void genuineBackslashIsNotUnescaped() {
        // only the escapes escapeMarkdown emits (\\* \\_ \\\\) are undone; a genuine backslash
        // before any other char is kept
        final var card = ToolCard.parse("*streaming `bash`: grep 'a\\b' \\*x* (123 ms)\n\n```text\nout\n```");
        assertEquals("grep 'a\\b' *x", card.args());
    }

    @Test
    void consecutiveEscapesAreAllUnescaped() {
        final var card = ToolCard.parse("*streaming `bash`: echo \\\\\\* \\_x* (123 ms)\n\n```text\nout\n```");
        // \\\\* is an escaped backslash followed by an escaped star -> "\*" ; \\_ -> "_"
        assertEquals("echo \\* _x", card.args());
    }

    @Test
    void noBackslashIsANoOp() {
        assertEquals("plain args", ToolCard.unescape("plain args"));
        assertEquals("COUNT(*) x", ToolCard.unescape("COUNT(*) x"));
    }

    @Test
    void failedHeaderEscapedStarsAreUnescaped() {
        // the failed header is escaped at emission too ("Error: \\* boom \\*"); the TUI restores
        // the raw first line
        final var card = ToolCard.parse("> tool `bash` failed: Error: \\* boom \\* (boom) (#7, 12 ms)");
        assertEquals("Error: * boom * (boom)", card.args());
    }

    @Test
    void transcriptHeaderRoundTripsThroughToolCard() {
        // end-to-end: a real tool with a multi-line heredoc, flattened+escaped by the transcript,
        // then parsed back by the TUI card -> the raw command is restored without backslashes and
        // without the markdown star-loss
        final var t = new ConversationTranscript();
        t.startUserTurn("hi");
        t.onEvent(new AgentEvent.ToolStarted(
                "bash",
                "{\"command\":\"cat <<'SQL'\nALTER TABLE T LOCATION ('part_*.csv.gz');\nSELECT COUNT(*) FROM T;\nSQL\"}",
                "s1",
                eventWorkdir));
        t.onEvent(new AgentEvent.ToolFinished("bash", "ok", true, "s1", eventWorkdir));

        String cardArgs = null;
        for (final var region : ConversationTranscript.regions(t.markdown("|", "busy"))) {
            if (region.tool()) {
                final var card = ToolCard.parse(region.text());
                if (!card.running()) {
                    cardArgs = card.args();
                }
            }
        }
        assertNotNull(cardArgs, "a finished tool card must be found");
        assertEquals("cat <<'SQL' ALTER TABLE T LOCATION ('part_*.csv.gz'); SELECT COUNT(*) FROM T; SQL", cardArgs);
        assertFalse(cardArgs.contains("\\"), cardArgs);
    }

    @Test
    void parsesPendingCardAsNeutralNotFailed() {
        final var card = ToolCard.parse("> tool `check_subagents` still running: {\"status\":\"pending\"} (#3)");
        assertTrue(card.pending(), "a 'still running' header parses as pending");
        assertFalse(card.failed(), "a pending tool is not a failure");
        assertTrue(card.args().contains("pending"));
    }

    @Test
    void parsesStarlessDuration() {
        final var card = ToolCard.parse("*streaming `git diff`: --stat* (5 ms)\n\n```text\n 1 file changed\n```");
        assertEquals("git diff", card.name());
        assertEquals("--stat", card.args());
        assertEquals(5, card.elapsedMs());
    }

    @Test
    void parsesFailedCard() {
        final var card = ToolCard.parse("> tool `bash` failed: boom (42 ms)");
        assertEquals("bash", card.name());
        assertTrue(card.failed());
        assertEquals("boom", card.args());
        assertEquals(42, card.elapsedMs());
        assertEquals("", card.output());
    }

    @Test
    void failedWithoutDuration() {
        final var card = ToolCard.parse("> tool `read` failed: nope");
        assertTrue(card.failed());
        assertEquals("nope", card.args());
        assertEquals(0, card.elapsedMs());
    }

    @Test
    void nameOnlyWithoutArgs() {
        final var card = ToolCard.parse("*streaming `think`* (1 ms)\n\n```text\nthought\n```");
        assertEquals("think", card.name());
        assertEquals("", card.args());
        assertEquals(1, card.elapsedMs());
    }

    @Test
    void parsesRunningCard() {
        final var card = ToolCard.parse("`| running tail -f log`\n\n```text\nstreamed line\n```");
        assertTrue(card.running());
        assertEquals("tail -f log", card.name());
        assertEquals("", card.args());
        assertFalse(card.failed());
        assertEquals(0, card.elapsedMs());
        assertEquals("streamed line", card.output());
    }

    @Test
    void parsesRunningCardWithoutCommand() {
        final var card = ToolCard.parse("`* running read`");
        assertTrue(card.running());
        assertEquals("read", card.name());
        assertEquals("", card.output());
    }

    @Test
    void parsesRunningCardOrdinal() {
        final var card = ToolCard.parse("`| running mvn -q test (#3)`\n\n```text\nbuilding\n```");
        assertTrue(card.running());
        assertEquals("mvn -q test", card.name());
        assertEquals(3, card.ordinal());
        assertEquals("building", card.output());
    }

    @Test
    void parsesRunningCardWithoutOrdinalAsNegative() {
        final var card = ToolCard.parse("`| running tail -f log`");
        assertTrue(card.running());
        assertEquals(-1, card.ordinal());
    }

    @Test
    void detectsDiff() {
        final var card = ToolCard.parse(
                "*streaming `apply_patch`* (9 ms)\n\n```text\n--- a/File.java\n+++ b/File.java\n@@ -1,3 +1,4 @@\n-old\n+new\n context\n```");
        assertTrue(card.isDiff(diffRenderer));
    }

    @Test
    void doesNotDetectPlainOutputAsDiff() {
        final var card = ToolCard.parse("*streaming `bash`* (7 ms)\n\n```text\nhello world\nfoo bar\n```");
        assertFalse(card.isDiff(diffRenderer));
    }

    @Test
    void keyCommandExtractsSignificantCommand() {
        final var card = ToolCard.parse("*streaming `bash`: cd /foo && ls -la* (#31, 3 ms)");
        assertEquals("ls -la", card.keyCommand());
    }

    @Test
    void keyCommandKeepsPlainCommand() {
        final var card = ToolCard.parse("*streaming `bash`: mvn -q test* (#1, 5 ms)");
        assertEquals("mvn -q test", card.keyCommand());
    }

    @Test
    void keyCommandTruncatesLongCommands() {
        final var card = ToolCard.parse("*streaming `bash`: echo " + "x".repeat(120) + "* (#2, 5 ms)");
        final var key = card.keyCommand();
        assertEquals(48 + "...".length(), key.length());
        assertTrue(key.endsWith("..."));
    }

    @Test
    void keyCommandCondensesRunningCommand() {
        final var card = ToolCard.parse("`| running cd /tmp && tail -f log`");
        assertTrue(card.running());
        assertEquals("tail -f log", card.keyCommand());
    }

    @Test
    void keyCommandEmptyWithoutArgs() {
        final var card = ToolCard.parse("*streaming `think`* (1 ms)");
        assertEquals("", card.keyCommand());
    }

    @Test
    void keyCommandShowsFailedReason() {
        final var card = ToolCard.parse("> tool `bash` failed: boom (#7, 42 ms)");
        assertEquals("boom", card.keyCommand());
    }
}
