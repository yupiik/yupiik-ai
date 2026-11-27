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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.ia.agent.core.context.ContextCompression;
import io.yupiik.ia.agent.core.llm.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamTextBufferTest {
    @Test
    void emitsOnlyCompletedLinesAndFlushesTheRemainder() {
        final var lines = new ArrayList<String>();
        final var buffer = new StreamTextBuffer(line -> {
            lines.add(line);
            return line;
        });
        assertEquals("", buffer.append("hello wor"));
        assertEquals(List.of(), lines);
        assertEquals("hello world\n", buffer.append("ld\nnext "));
        assertEquals(List.of("hello world"), lines);
        assertEquals("next \n", buffer.flush());
        assertEquals(List.of("hello world", "next "), lines);
        assertEquals("", buffer.flush());
    }

    @Test
    void multiLineChunkDrainsAllCompleteLines() {
        final var buffer = new StreamTextBuffer(line -> "[" + line + "]");
        assertEquals("[a]\n[b]\n", buffer.append("a\nb\nc"));
        assertEquals("[c]\n", buffer.flush());
    }

    @Test
    void resetDropsPendingLine() {
        final var buffer = new StreamTextBuffer(line -> line);
        buffer.append("partial");
        buffer.reset();
        assertEquals("", buffer.flush());
        assertEquals("done\n", buffer.append("done\n"));
    }

    @Test
    void nullAndEmptyChunksAreIgnored() {
        final var buffer = new StreamTextBuffer(line -> line);
        assertEquals("", buffer.append(null));
        assertEquals("", buffer.append(""));
        assertEquals("x\n", buffer.append("x\n"));
    }

    @Test
    void emptyLinesAreEmittedAsNewlines() {
        final var buffer = new StreamTextBuffer(line -> line);
        assertEquals("\n\n", buffer.append("\n\n"));
        assertEquals("", buffer.flush());
    }

    @Test
    void echoedSeamSplitAcrossChunksIsStrippedFromDrainedLine() {
        // a seam tag can split across two incoming chunks ("@5" + "35@"); when it re-forms as a
        // STANDALONE line the scrub runs on the drained complete line and removes it
        final var buffer = new StreamTextBuffer(line -> line);
        assertEquals("answer\n", buffer.append("answer\n@5"));
        assertEquals("\n", buffer.append("35@\ndone"));
        assertEquals("done\n", buffer.flush());
    }

    @Test
    void echoedWholeLineAnalogInSeamIsStripped() {
        // whole-line analog seams plus the block and priority-suffix variants are scrubbed;
        // inline-prose seams are intended content and survive
        final var buffer = new StreamTextBuffer(line -> line);
        // the whole-line seam strips to an empty line, which still drains as a newline
        assertEquals("\nreal prose\n", buffer.append("@b21@ [high]\nreal prose\n"));
        assertEquals("x @blocked@ y\n", buffer.append("x @blocked@ y\n"));
        assertEquals("kept @user\n", buffer.append("kept @user\n"));
    }

    @Test
    void bindCompressionEnablesTheEvidenceGatedGluedSeamStrip() {
        final var lines = new ArrayList<String>();
        final var buffer = new StreamTextBuffer(line -> {
            lines.add(line);
            return line;
        });
        final var compression = new ContextCompression(null);
        for (var i = 0; i < 12503; i++) {
            compression.refOf(Message.user("m" + i));
        }
        compression.refOf(Message.user("last"));
        buffer.bindCompression(compression);

        // without the bind this would render verbatim (fresh compressor has no assigned refs)
        assertEquals("EntCompactionTruncates10632\n", buffer.append("@EntCompactionTruncates10632\n"));
        assertEquals(List.of("EntCompactionTruncates10632"), lines);
        // an unbound buffer (no assigned refs) keeps the '@' verbatim
        final var fresh = new StreamTextBuffer(line -> line);
        assertEquals("@EntCompactionTruncates10632\n", fresh.append("@EntCompactionTruncates10632\n"));
    }

    @Test
    void literalNumericAtSuffixIsNotASeam() {
        final var buffer = new StreamTextBuffer(line -> line);
        assertEquals("4@\n", buffer.append("4@\n"));
        assertEquals("", buffer.flush());
    }

    @Test
    void preservesCodeAcrossChunksAndResetClearsCodeState() {
        final var buffer = new StreamTextBuffer(line -> line);
        assertEquals("```text\n", buffer.append("```text\n@"));
        assertEquals("@12@\n@b2\n4@\n```\n\n", buffer.append("12@\n@b2\n4@\n```\n@3@\n"));
        assertEquals("``code\n", buffer.append("``code\n"));
        assertEquals("@4@\nend``\n\n", buffer.append("@4@\nend``\n@5@\n"));
        buffer.append("~~~\n");
        buffer.reset();
        assertEquals("\n", buffer.append("@6@\n"));
    }

    @Test
    void flushDoesNotEmitABlankLineWhenTheOnlyContentWasWithheld() {
        // a whole-line seam drains as a blank line, but the FINAL flush of a lone withheld
        // fragment must not append an empty line
        final var buffer = new StreamTextBuffer(line -> line);
        buffer.append("@1250");
        assertEquals("", buffer.flush());
        buffer.append("@1290");
        assertEquals("", buffer.flush());
    }
}
