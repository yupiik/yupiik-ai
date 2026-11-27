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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.cli.CliVerbosity;
import io.yupiik.ia.agent.core.cli.MarkdownColorizer;
import io.yupiik.ia.agent.core.cli.Symbols;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import io.yupiik.ia.agent.core.todo.TodoItem;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// Ordinary JUL writers do not acquire the stream resource locks. This class captures the
// JVM-global streams, so even unrelated log output would corrupt its exact-output assertions.
@Isolated("Captures System.out/System.err, including output from unrelated JUL writers")
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ExtendWith(TestJsonMapper.class)
class AnsiShellFrontendTest {
    private static final Pattern FIRST_MODEL_HEADER = Pattern.compile("\\Q[1000] model\\E");
    private static final Pattern SECOND_MODEL_HEADER = Pattern.compile("\\Q[2000] model\\E");
    private static final Pattern PARENT_DELTA = Pattern.compile(".*P\\d+ .*");
    private static final Pattern CHILD_DELTA = Pattern.compile(".*C\\d+ .*");
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;]*m");

    @TempDir
    private static Path eventWorkdir;

    @Test
    void eachResponseStartPrintsOneConfiguredHeaderNotOnePerDelta() {
        final var frontend = frontend(false, false);
        frontend.feedbackLabel(at -> "[" + at + "] model");
        frontend.beginTurn();
        frontend.onEvent(new AgentEvent.FeedbackStarted("text", 1000, "s1", null, null, eventWorkdir));
        frontend.onEvent(new AgentEvent.TextDelta("first", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.TextDelta(" tail", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.FeedbackStarted("text", 2000, "s1", null, null, eventWorkdir));
        frontend.onEvent(new AgentEvent.TextDelta("last", "s1", eventWorkdir));
        frontend.endTurn();
        final var rendered = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, FIRST_MODEL_HEADER.split(rendered, -1).length - 1, rendered);
        assertEquals(1, SECOND_MODEL_HEADER.split(rendered, -1).length - 1, rendered);
        assertTrue(rendered.indexOf("first tail") < rendered.indexOf("[2000] model"), rendered);
    }

    @Test
    void splitThinkingSeamsAreHiddenButMultilineCodeAndProseSurvive() {
        final var frontend = frontend(true, false);
        frontend.beginTurn();
        frontend.onEvent(new AgentEvent.ThinkingDelta("@bl", "s1", eventWorkdir));
        assertFalse(err.toString().contains("@bl"));
        frontend.onEvent(new AgentEvent.ThinkingDelta("ocked@\n``literal\n@1", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.ThinkingDelta("@\nend``\nuse @2@ literally\n", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.TextDelta("~~~\n@3", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.TextDelta("@\n~~~\n@4@\n", "s1", eventWorkdir));
        frontend.endTurn();
        assertFalse(err.toString().contains("@blocked@"));
        assertTrue(err.toString().contains("@1@"));
        assertTrue(err.toString().contains("use @2@ literally"));
        assertTrue(out.toString().contains("@3@"));
        assertFalse(out.toString().contains("@4@"));
    }

    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private JsonMapper mapper;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private AnsiShellFrontend frontend(final boolean showThinking, final boolean showToolOutput) {
        return frontend(false, showThinking, ToolOutputVisibility.fromBoolean(showToolOutput));
    }

    private AnsiShellFrontend frontend(
            final boolean tty, final boolean showThinking, final ToolOutputVisibility showToolOutput) {
        return frontend(new MarkdownColorizer(false), tty, showThinking, showToolOutput);
    }

    private AnsiShellFrontend frontend(final boolean tty, final boolean showThinking, final boolean showToolOutput) {
        return frontend(
                new MarkdownColorizer(false), tty, showThinking, ToolOutputVisibility.fromBoolean(showToolOutput));
    }

    private AnsiShellFrontend frontend(
            final MarkdownColorizer colorizer,
            final boolean tty,
            final boolean showThinking,
            final ToolOutputVisibility showToolOutput) {
        return new AnsiShellFrontend(
                colorizer,
                null,
                new Symbols(false),
                new ToolPresenter(mapper),
                CliVerbosity.COMPACT,
                tty,
                showThinking,
                showToolOutput);
    }

    private AnsiShellFrontend frontend(final CliVerbosity verbosity, final boolean tty, final boolean showThinking) {
        return new AnsiShellFrontend(
                new MarkdownColorizer(false),
                null,
                new Symbols(false),
                new ToolPresenter(mapper),
                verbosity,
                tty,
                showThinking,
                ToolOutputVisibility.SHOWN);
    }

    @Test
    void suspensionBuffersEventsAndResumeReplaysThem() {
        final var frontend = frontend(false, true);
        final var interrupt = new AgentEvent.Status("mid-gate status", "s1", eventWorkdir);

        frontend.suspendOutput();
        frontend.onEvent(new AgentEvent.ThinkingDelta("hidden", "s1", eventWorkdir));
        frontend.onEvent(interrupt);
        assertEquals("", out.toString());
        assertEquals("", err.toString());

        frontend.resumeOutput();
        assertTrue(out.toString().isEmpty());
        assertTrue(err.toString().contains("mid-gate status"));

        frontend.onEvent(new AgentEvent.Status("after", "s1", eventWorkdir));
        assertTrue(err.toString().contains("after"));
    }

    @Test
    void subagentStartedAndCompletedRenderReadableLines() {
        final var frontend = frontend(false, true, true);
        frontend.beginTurn();

        frontend.onEvent(new AgentEvent.SubagentStarted("do the thing", "s1", "sub-1", "child", eventWorkdir));
        assertTrue(
                err.toString(StandardCharsets.UTF_8).contains("[child] [subagent] started: do the thing"),
                "started must render a one-line status: " + err);

        frontend.onEvent(new AgentEvent.SubagentCompleted(
                "all done\n- item one\n- item two", true, false, "s1", "sub-1", "child", "gpt-5.5", eventWorkdir));
        final var out = err.toString(StandardCharsets.UTF_8);
        assertTrue(
                out.contains("[child] [subagent] completed (gpt-5.5) — all done"),
                "completed must render with model + first line: " + out);
    }

    @Test
    void subagentFailedAndCancelledRenderDistinctLines() {
        final var frontend = frontend(false, true, true);
        frontend.beginTurn();

        frontend.onEvent(new AgentEvent.SubagentCompleted(
                "boom", false, false, "s1", "sub-1", "child", "gpt-5.5", eventWorkdir));
        assertTrue(
                err.toString(StandardCharsets.UTF_8).contains("[child] [subagent] failed (gpt-5.5) — boom"),
                "failed must render an error line: " + err);

        frontend.onEvent(new AgentEvent.SubagentCompleted(
                "Sub-agent cancelled", false, true, "s1", "sub-2", "other", null, eventWorkdir));
        assertTrue(
                err.toString(StandardCharsets.UTF_8).contains("[other] [subagent] cancelled — Sub-agent cancelled"),
                "cancelled must render a dim line: " + err);
    }

    @Test
    void suspendTerminatesOpenThoughtLineBeforeCard() {
        final var frontend = frontend(false, true, true);
        frontend.beginTurn();

        // a thought delta with no trailing newline leaves an open line
        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning", "s1", eventWorkdir));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("reasoning"));

        // suspending for an interaction must terminate that line so the stdout card
        // is never drawn onto the same terminal row
        frontend.suspendOutput();
        assertTrue(
                err.toString(StandardCharsets.UTF_8).endsWith("\n"),
                "open thought line must terminate on suspend: " + err);

        // events buffered while suspended stay silent until resume
        frontend.onEvent(new AgentEvent.ThinkingDelta("hidden", "s1", eventWorkdir));
        assertEquals("", out.toString(StandardCharsets.UTF_8));

        frontend.resumeOutput();
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("hidden"));
    }

    @Test
    void interactionResumeSeparatesCardAndRestartsThinking() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // nested/sequential resumes of one interaction must emit a single separation
        // newline so the thinking line cannot overwrite the card's last row
        frontend.suspendOutput();
        assertFalse(frontend.spinnerRunning());
        System.out.print("answer> "); // card's last prompt row, no trailing newline
        frontend.resumeOutput(); // separation newline + thinking restart
        assertTrue(frontend.spinnerRunning());
        assertEquals(1, newlines(out.toString(StandardCharsets.UTF_8)));
        frontend.resumeOutput(); // deduplicated: no second newline
        assertEquals(1, newlines(out.toString(StandardCharsets.UTF_8)));
        assertTrue(frontend.spinnerRunning());

        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void resumeDoesNotRestartThinkingAfterBufferedTurnDone() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // the interaction suspends output; while blocked the turn completes and its
        // TurnDone is buffered. On resume the buffered TurnDone stops the spinner,
        // and resumeOutput must NOT re-arm it (otherwise it would spin forever over
        // the freshly drawn prompt once the answer is ready).
        frontend.suspendOutput();
        assertFalse(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.TurnDone("answer", "s1", eventWorkdir));
        frontend.resumeOutput();
        assertFalse(frontend.spinnerRunning(), "spinner must stay off after a buffered TurnDone");

        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    private static long newlines(final String text) {
        return text.codePoints().filter(c -> c == '\n').count();
    }

    @Test
    void streamsAnswerToStdoutAndCountsTools() {
        final var frontend = frontend(false, false);

        frontend.onEvent(new AgentEvent.TextDelta("hel", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.TextDelta("lo", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolStarted("read", "{}", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolFinished("read", "out", true, "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.TurnDone("hello", "s1", eventWorkdir));
        frontend.endTurn();

        assertEquals("hello\n", out.toString(StandardCharsets.UTF_8));
        assertEquals(1, frontend.stats().toolsRun());
    }

    @Test
    void hiddenThinkingTriggersActivityOnly() {
        final var frontend = frontend(false, false);

        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.UsageUpdated(null, 100, 5.0, "s1", eventWorkdir));

        assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty());
        assertEquals(100, frontend.stats().lastUsage().estimatedContextTokens());
    }

    @Test
    void statusNoticeAndErrorGoToStderr() {
        final var frontend = frontend(false, false);

        frontend.status("busy");
        frontend.notice(AgentEvent.Notice.Level.WARN, "watch out");
        frontend.error("boom");

        final var stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("busy"), stderr);
        assertTrue(stderr.contains("watch out"), stderr);
        assertTrue(stderr.contains("boom"), stderr);
    }

    @Test
    void togglesAreReflected() {
        final var frontend = frontend(true, true);

        assertTrue(frontend.showThinking());
        assertEquals(ToolOutputVisibility.SHOWN, frontend.showToolOutput());
        frontend.showThinking(false);
        frontend.showToolOutput(ToolOutputVisibility.COLLAPSED);
        assertFalse(frontend.showThinking());
        assertEquals(ToolOutputVisibility.COLLAPSED, frontend.showToolOutput());
    }

    @Test
    void activePromptOutputRoutesWritesAboveThePrompt() {
        final var seen = new ArrayList<String>();
        final var frontend = new AnsiShellFrontend(
                new MarkdownColorizer(false),
                null,
                new Symbols(false),
                new ToolPresenter(mapper),
                CliVerbosity.COMPACT,
                true,
                true,
                ToolOutputVisibility.SHOWN,
                new PromptOutput() {
                    @Override
                    public boolean active() {
                        return true;
                    }

                    @Override
                    public void print(final String text) {
                        seen.add(text);
                    }
                });

        frontend.status("live status");
        frontend.transcript("answer line\n");
        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning", "s1", eventWorkdir));
        frontend.endTurn();
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "{}", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "out", true, "s1", eventWorkdir));

        assertFalse(seen.isEmpty(), "writes landing while a prompt is live must go through the sink");
        assertEquals("", out.toString(StandardCharsets.UTF_8));
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        assertTrue(seen.stream().anyMatch(t -> t.contains("live status")), "status must reach the sink: " + seen);
        assertTrue(seen.stream().anyMatch(t -> t.contains("reasoning")), "thinking must reach the sink: " + seen);
        assertTrue(seen.stream().anyMatch(t -> t.contains("answer line")), "answer must reach the sink: " + seen);
    }

    @Test
    void theFrontendNeverForksOnActiveTheSinkOwnsTheDecision() {
        // The frontend used to do `if (sink.active()) printAbove else raw`: a
        // check-then-act race, where the turn cleanup or a late sub-agent could land on
        // the row jline had just painted and leave the prompt invisible until the next
        // keystroke. It now delegates every chunk to the sink, which is the only place
        // that knows whether a read is live - atomically with the write.
        final var seen = new ArrayList<String>();
        final var frontend = new AnsiShellFrontend(
                new MarkdownColorizer(false),
                null,
                new Symbols(false),
                new ToolPresenter(mapper),
                CliVerbosity.COMPACT,
                true,
                true,
                ToolOutputVisibility.SHOWN,
                new PromptOutput() {
                    @Override
                    public boolean active() {
                        return false;
                    }

                    @Override
                    public void print(final String text) {
                        seen.add(text);
                    }
                });

        frontend.status("status chunk");
        frontend.transcript("answer chunk\n");
        frontend.endTurn();

        assertTrue(seen.stream().anyMatch(t -> t.contains("status chunk")), "status must reach the sink: " + seen);
        assertTrue(seen.stream().anyMatch(t -> t.contains("answer chunk")), "answer must reach the sink: " + seen);
        assertEquals(
                "", out.toString(StandardCharsets.UTF_8), "the frontend must not write raw behind the sink's back");
        assertEquals(
                "", err.toString(StandardCharsets.UTF_8), "the frontend must not write raw behind the sink's back");
    }

    @Test
    void nullPromptOutputFallsBackToTheRawStreams() {
        // the plain-stdin backend has no in-place display: a null sink must not make
        // the whole frontend silent
        final var frontend = new AnsiShellFrontend(
                new MarkdownColorizer(false),
                null,
                new Symbols(false),
                new ToolPresenter(mapper),
                CliVerbosity.COMPACT,
                true,
                true,
                ToolOutputVisibility.SHOWN,
                null);

        frontend.status("fallback status");
        frontend.transcript("fallback answer\n");
        frontend.endTurn();

        assertTrue(err.toString(StandardCharsets.UTF_8).contains("fallback status"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("fallback answer"));
    }

    @Test
    void lateAsyncEventFromAnotherThreadNeverTouchesTheRawStreams() throws Exception {
        // A sub-agent or the memory extraction emits on a virtual thread long after the
        // turn ended, while the REPL already painted the next prompt. Routing it through
        // the sink is what keeps it off the prompt row: a raw write there is invisible
        // to the line editor's display bookkeeping, so the prompt only came back once
        // the user typed.
        final var seen = new ArrayList<String>();
        final var promptLive = new AtomicBoolean();
        final var painted = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var frontend = new AnsiShellFrontend(
                new MarkdownColorizer(false),
                null,
                new Symbols(false),
                new ToolPresenter(mapper),
                CliVerbosity.COMPACT,
                true,
                true,
                ToolOutputVisibility.SHOWN,
                new PromptOutput() {
                    @Override
                    public boolean active() {
                        return promptLive.get();
                    }

                    @Override
                    public void print(final String text) {
                        seen.add(text);
                    }
                });

        frontend.beginTurn();
        frontend.onEvent(new AgentEvent.TextDelta("the answer\n", "s1", eventWorkdir));
        frontend.endTurn();

        // the REPL is now painting the next prompt
        promptLive.set(true);
        painted.countDown();
        assertTrue(painted.await(5, TimeUnit.SECONDS));

        final var late = new Thread(
                () -> {
                    try {
                        frontend.onEvent(new AgentEvent.TextDelta("late sub-agent delta\n", "s1", eventWorkdir));
                        frontend.onEvent(new AgentEvent.Notice(
                                AgentEvent.Notice.Level.WARN, "memory extraction failed", "s1", eventWorkdir));
                    } finally {
                        release.countDown();
                    }
                },
                "late-event");
        late.start();
        assertTrue(release.await(5, TimeUnit.SECONDS), "the late event never completed");
        late.join(5000);

        assertTrue(
                seen.stream().anyMatch(t -> t.contains("late sub-agent delta")),
                "the late delta must be routed above the prompt: " + seen);
        assertTrue(
                seen.stream().anyMatch(t -> t.contains("memory extraction failed")),
                "the late notice must be routed above the prompt: " + seen);
        assertEquals("", out.toString(StandardCharsets.UTF_8), "a late event must never reach stdout raw");
        assertEquals("", err.toString(StandardCharsets.UTF_8), "a late event must never reach stderr raw");
    }

    @Test
    void renderTodosMarksStates() {
        final var frontend = frontend(false, false);

        final var lines = frontend.renderTodos(List.of(
                new TodoItem("write", "completed"),
                new TodoItem("test", "in_progress"),
                new TodoItem("ship", "pending")));

        assertEquals(4, lines.size());
        assertTrue(lines.get(3).contains("1/3 done"));
        assertTrue(lines.get(0).contains("write"));
    }

    @Test
    void shownThinkingPrintsDimAndNeverSpins() {
        final var frontend = frontend(true, true, true);
        frontend.beginTurn();
        assertFalse(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning", "s1", eventWorkdir));
        assertTrue(err.toString().contains("reasoning"));
        assertFalse(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "ls", "s1", eventWorkdir));
        assertFalse(frontend.spinnerRunning(), "tool activity must remain append-only");
        frontend.onEvent(new AgentEvent.ToolFinished("bash", "out", true, "s1", eventWorkdir));
        assertFalse(frontend.spinnerRunning());
        frontend.endTurn();
    }

    @Test
    void shownThinkingScrubsEchoedSeamsFromStderr() {
        final var frontend = frontend(true, true, true);
        frontend.beginTurn();
        frontend.onEvent(new AgentEvent.ThinkingDelta("pondering\n@535@\nreasoning @24", "s1", eventWorkdir));
        frontend.endTurn();
        final var rendered = err.toString();
        assertTrue(rendered.contains("pondering"), rendered);
        assertTrue(rendered.contains("reasoning @24"), "non-live/plain '@24' is real content and stays: " + rendered);
        assertFalse(
                rendered.contains("@535@"),
                "standalone echoed seam line must be scrubbed from the thinking dump: " + rendered);
    }

    @Test
    void hiddenThinkingSpinsDuringTurn() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());
        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void lateAsyncEventsAfterEndTurnCannotRestartSpinner() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // the turn finishes and stops the spinner...
        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());

        // ...then a background subagent (emitting on the shared bus) sends its own
        // thinking/tool deltas and finally its completion; none of those must re-arm
        // the spinner now that the turn is over, otherwise it would spin forever
        frontend.onEvent(new AgentEvent.ThinkingDelta("subagent pondering", null, "sub-1", "researcher", eventWorkdir));
        assertFalse(frontend.spinnerRunning(), "late thinking delta must not restart the spinner");
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "ls", -1L, null, "sub-1", "researcher", eventWorkdir));
        assertFalse(frontend.spinnerRunning(), "late tool start must not restart the spinner");
        frontend.onEvent(
                new AgentEvent.ThinkingDelta("subagent ponder again", null, "sub-1", "researcher", eventWorkdir));
        assertFalse(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.TurnDone("subagent answer", null, "sub-1", "researcher", eventWorkdir));
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void turnDoneStopsSpinnerEvenBeforeEndTurn() {
        final var frontend = frontend(true, false, true);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // the actor completes (main answer streamed, then TurnDone): the spinner must
        // already be gone before endTurn, so no dangling spin is ever visible at the prompt
        frontend.onEvent(new AgentEvent.TurnDone("answer", "s1", eventWorkdir));
        assertFalse(frontend.spinnerRunning());
        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void toolStreamStopsTheSpinnerInsteadOfInterleaving() {
        final var frontend = frontend(CliVerbosity.VERBOSE, true, false);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());

        // streamed tool output owns the terminal line: the spinner must be gone
        // rather than animating frames between the output fragments
        frontend.onEvent(new AgentEvent.ToolStream("bash", "streamed line\n", "s1", eventWorkdir));
        assertFalse(frontend.spinnerRunning(), "streamed output must stop the spinner");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("streamed line"));
        frontend.onEvent(new AgentEvent.ToolStream("bash", "second fragment", "s1", eventWorkdir));
        assertFalse(frontend.spinnerRunning(), "spinner restarted between stream fragments");

        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    @Test
    void rawToolStreamStopsTheSpinnerInsteadOfInterleaving() {
        final var frontend = frontend(CliVerbosity.RAW, true, false);
        frontend.beginTurn();
        assertTrue(frontend.spinnerRunning());
        frontend.onEvent(new AgentEvent.ToolStream("bash", "streamed line\n", "s1", eventWorkdir));
        assertFalse(frontend.spinnerRunning(), "streamed output must stop the spinner");
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("streamed line\n"));
        frontend.endTurn();
        assertFalse(frontend.spinnerRunning());
    }

    /**
     * Regression test for the forever-spinner: event threads (subagents, bash
     * stream workers) can race the turn teardown. The spinner lifecycle and the
     * {@code turnActive} flip are serialized by the frontend, so whichever side
     * wins, no spinner may survive {@code endTurn()}.
     */
    @Test
    void racingEventsCannotLeaveTheSpinnerRunningAfterEndTurn() throws InterruptedException {
        final var frontend = frontend(true, false, ToolOutputVisibility.COLLAPSED);
        for (int i = 0; i < 200; i++) {
            frontend.beginTurn();
            final var barrier = new CyclicBarrier(2);
            final var racing = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                } catch (final Exception e) {
                    return;
                }
                // a background actor emitting while the turn ends: each tool
                // finish stops then re-arms the spinner (hidden thinking),
                // probing the teardown window on every single event
                for (int j = 0; j < 50; j++) {
                    frontend.onEvent(new AgentEvent.ToolFinished("bash", "done " + j, true, "s1", eventWorkdir));
                    frontend.onEvent(new AgentEvent.ThinkingDelta("late thought " + j, "s1", eventWorkdir));
                }
            });
            try {
                barrier.await();
            } catch (final BrokenBarrierException e) {
                // the racing thread died before the rendezvous: endTurn still cleans up
            }
            frontend.endTurn();
            racing.join();
            assertFalse(frontend.spinnerRunning(), "iteration " + i + " left the spinner running after the turn");
        }
    }

    @Test
    void thoughtWithoutNewlineIsTerminatedBeforeToolLine() {
        final var frontend = frontend(false, true, true);
        frontend.beginTurn();

        // a thought delta that does not end with a newline
        frontend.onEvent(new AgentEvent.ThinkingDelta("reasoning", "s1", eventWorkdir));
        // a following tool line must not be glued onto the thought line
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "ls", "s1", eventWorkdir));

        final var stderr = err.toString(StandardCharsets.UTF_8);
        // the thought delta is ANSI-wrapped (\u001b[38;5;240m...\u001b[0m); the guard must
        // insert a newline right after it so the tool line is not glued onto the thought line
        assertTrue(
                stderr.contains("reasoning\u001b[0m\n"),
                "thought must terminate with a newline before the tool line: " + stderr);
    }

    @Test
    void anotherSubagentThoughtStartsItsOwnLineAndHeader() {
        final var frontend = frontend(false, true, true);
        frontend.beginTurn();

        // the parent turn and a spawned sub-agent reason concurrently: their deltas
        // must not be spliced into a single line, and the second voice needs its
        // own 'thinking...' header (the first one must not suppress it)
        frontend.onEvent(new AgentEvent.ThinkingDelta("parent reasoning", "s1", eventWorkdir));
        frontend.onEvent(new AgentEvent.ThinkingDelta("child reasoning", "s1", "sub-1", "kid", eventWorkdir));

        final var lines = stripAnsi(err.toString(StandardCharsets.UTF_8)).split("\n", -1);
        assertEquals(4, lines.length, () -> "unexpected stderr: " + String.join(" | ", lines));
        assertEquals("thinking...", lines[0]);
        assertEquals("parent reasoning", lines[1]);
        assertEquals("[kid] thinking...", lines[2]);
        assertEquals("[kid] child reasoning", lines[3]);
    }

    /**
     * Regression test for garbled reasoning: a spawned sub-agent streams on its own
     * thread while the parent turn keeps going, and thought deltas are printed
     * without a trailing newline. The stderr line is therefore owned by one actor
     * at a time: a delta from another actor starts below it instead of in it.
     */
    @Test
    void concurrentThoughtsNeverShareALine() throws InterruptedException {
        final var frontend = frontend(false, true, true);
        frontend.beginTurn();
        for (int i = 0; i < 200; i++) {
            final var barrier = new CyclicBarrier(2);
            final var parent = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                } catch (final Exception e) {
                    return;
                }
                for (int j = 0; j < 20; j++) {
                    frontend.onEvent(new AgentEvent.ThinkingDelta("P" + j + " ", "s1", eventWorkdir));
                }
            });
            final var child = Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                } catch (final Exception e) {
                    return;
                }
                for (int j = 0; j < 20; j++) {
                    frontend.onEvent(new AgentEvent.ThinkingDelta("C" + j + " ", "s1", "sub-1", "kid", eventWorkdir));
                }
            });
            parent.join();
            child.join();
        }

        final var stderr = stripAnsi(err.toString(StandardCharsets.UTF_8));
        final var lines = stderr.split("\n", -1);
        assertTrue(lines.length > 2, "the race produced no reasoning to check: " + stderr);
        for (final var line : lines) {
            // P*/C* are the per-actor delta tokens, one line may only carry one voice
            final var parent = PARENT_DELTA.matcher(line).matches();
            final var child = CHILD_DELTA.matcher(line).matches();
            assertFalse(parent && child, () -> "two thoughts spliced into one line: \"" + line + "\" in: " + stderr);
            // and a round header is never written on top of another voice's thought
            assertFalse(
                    line.contains("thinking...") && (parent || child),
                    () -> "a 'thinking...' header glued to a thought line: \"" + line + "\" in: " + stderr);
        }
    }

    private static String stripAnsi(final String text) {
        return ANSI.matcher(text).replaceAll("");
    }

    @Test
    void replayedTranscriptDimsThoughtAndToolRegions() {
        final var frontend = frontend(new MarkdownColorizer(true), false, true, ToolOutputVisibility.SHOWN);
        frontend.transcript(ConversationTranscript.THOUGHT_BEGIN + "\n**Thinking**\n\nsecret\n"
                + ConversationTranscript.THOUGHT_END
                + "\nplain answer\n\n"
                + ConversationTranscript.TOOL_BEGIN + "\n```text\nout\n```\n"
                + ConversationTranscript.TOOL_END);
        final var stdout = out.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.startsWith("\u001b[38;5;240m"), "thought region must lead in thinking grey: " + stdout);
        assertTrue(stdout.contains("plain answer"), stdout);
        assertTrue(stdout.contains("\u001b[2m"), "tool region must be dimmed: " + stdout);
    }

    @Test
    void autoOpenBrowserDefaultsToEnabledAndCanBeDisabled() {
        final var frontend = frontend(false, true);
        assertTrue(frontend.autoOpenBrowser(), "autoOpenBrowser must default to true");
        frontend.autoOpenBrowser(false);
        // disabling must make openExternalUrl a pure no-op (no browser spawn, no output)
        frontend.openExternalUrl("https://example.com/login");
        assertEquals("", out.toString());
    }

    @Test
    void requestedChangesHaveActualOrdinalsScopeAndNoInventedWriteDiff() {
        final var frontend = frontend(CliVerbosity.VERBOSE, false, false);
        frontend.onEvent(new AgentEvent.ToolStarted(
                "edit_file",
                "{\"path\":\"a\",\"oldString\":\" \\n\",\"newString\":\"  \\n\",\"replaceAll\":true}",
                41,
                "s",
                null,
                null,
                eventWorkdir));
        frontend.onEvent(
                new AgentEvent.ToolFinished("edit_file", "edited", true, false, 41, "s", null, null, eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolStarted(
                "write_file", "{\"path\":\"b\",\"content\":\"new\"}", 42, "s", null, null, eventWorkdir));
        final var stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("#41 edit_file started"), stderr);
        assertTrue(stderr.contains("requested replacement: a (all matches)"), stderr);
        assertTrue(stderr.contains("-  "), stderr);
        assertTrue(stderr.contains("+   "), stderr);
        assertTrue(stderr.contains("requested file content (not a filesystem diff)"), stderr);
        assertFalse(stderr.contains("+ new"), stderr);
        assertFalse(stderr.contains("Bash("), stderr);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void interleavedCallsKeepFinalBodiesAndPendingIsNotFailure() {
        final var frontend = frontend(CliVerbosity.VERBOSE, false, false);
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "{}", 4, "s", null, null, eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolStarted("bash", "{}", 5, "s", null, null, eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolStream("bash", "  partial", 4, "s", null, null, eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolStream("bash", "  \n", 4, "s", null, null, eventWorkdir));
        frontend.onEvent(
                new AgentEvent.ToolFinished("bash", "other body", true, false, 5, "s", null, null, eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolFinished(
                "bash", "  partial  \nextra diagnostic", false, false, 4, "s", null, null, eventWorkdir));
        frontend.onEvent(new AgentEvent.ToolFinished(
                "check_subagents", "still working", false, true, 6, "s", null, null, eventWorkdir));
        final var stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("#4 bash output:   partial  "), stderr);
        assertTrue(stderr.contains("other body"), stderr);
        assertTrue(stderr.contains("extra diagnostic"), stderr);
        assertTrue(stderr.contains("#6 check_subagents pending"), stderr);
        assertFalse(stderr.contains("#6 check_subagents failed"), stderr);
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void toolVisibilityHidesSuccessfulBodiesAndHiddenAlsoHidesLifecycle() {
        for (final var visibility : List.of(ToolOutputVisibility.HIDDEN, ToolOutputVisibility.COLLAPSED)) {
            err.reset();
            final var frontend = frontend(false, false, visibility);
            frontend.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"secret request\"}", 7, "s", null, null, eventWorkdir));
            frontend.onEvent(new AgentEvent.ToolStream("bash", "secret stream\n", 7, "s", null, null, eventWorkdir));
            frontend.onEvent(new AgentEvent.ToolFinished(
                    "bash", "secret result", true, false, 7, "s", null, null, eventWorkdir));
            final var stderr = err.toString(StandardCharsets.UTF_8);
            assertFalse(stderr.contains("secret"), stderr);
            if (visibility == ToolOutputVisibility.HIDDEN) {
                assertEquals("", stderr);
            } else {
                assertTrue(stderr.contains("#7 bash done"), stderr);
            }
        }
    }

    @Test
    void failureBodiesRemainDiagnosticAndHiddenPendingStaysHidden() {
        for (final var visibility : ToolOutputVisibility.values()) {
            err.reset();
            final var frontend = frontend(false, false, visibility);
            frontend.onEvent(new AgentEvent.ToolStarted(
                    "bash", "{\"command\":\"private command\"}", 7, "s", null, null, eventWorkdir));
            frontend.onEvent(new AgentEvent.ToolStream("bash", "error body\n", 7, "s", null, null, eventWorkdir));
            frontend.onEvent(new AgentEvent.ToolFinished(
                    "bash",
                    "error body\n" + "diagnostic\n".repeat(40) + "last diagnostic",
                    false,
                    false,
                    7,
                    "s",
                    null,
                    null,
                    eventWorkdir));
            final var stderr = err.toString(StandardCharsets.UTF_8);
            assertTrue(stderr.contains("#7 bash failed"), stderr);
            assertTrue(stderr.contains("error body"), stderr);
            assertTrue(stderr.contains("last diagnostic"), stderr);
            if (!visibility.shown()) {
                assertFalse(stderr.contains("private command"), stderr);
            }
            err.reset();
            frontend.onEvent(new AgentEvent.ToolFinished(
                    "bash", "pending body\nExit code: 7", false, true, 8, "s", null, null, eventWorkdir));
            final var pending = err.toString(StandardCharsets.UTF_8);
            assertFalse(pending.contains("failed"), pending);
            assertFalse(pending.contains("execution exit code"), pending);
            if (visibility == ToolOutputVisibility.HIDDEN) {
                assertEquals("", pending);
            }
        }
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void compactAndCollapsedActivityKeepSummaryWithoutRepeatingName() {
        for (final var visibility : List.of(ToolOutputVisibility.SHOWN, ToolOutputVisibility.COLLAPSED)) {
            err.reset();
            final var frontend = frontend(false, false, visibility);
            frontend.onEvent(
                    new AgentEvent.ToolStarted("read", "{\"path\":\"pom.xml\"}", 3, "s", null, null, eventWorkdir));
            final var stderr = err.toString(StandardCharsets.UTF_8);
            assertTrue(stderr.contains("#3 read started (pom.xml)"), stderr);
            assertFalse(stderr.contains("read(pom.xml)"), stderr);
        }
    }

    @Test
    void missingDiffFallsBackToRequestedArguments() {
        final var frontend = frontend(false, false, ToolOutputVisibility.SHOWN);
        for (final var name : List.of("edit_file", "apply_patch")) {
            frontend.onEvent(new AgentEvent.ToolStarted(name, "{bad", "s", eventWorkdir));
            frontend.onEvent(new AgentEvent.ToolStarted(name, "{\"unexpected\":\"keep me\"}", "s", eventWorkdir));
        }
        final var stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("requested arguments (raw):"), stderr);
        assertTrue(stderr.contains("{bad"), stderr);
        assertTrue(stderr.contains("unexpected: keep me"), stderr);
    }

    @Test
    void nonzeroExecutionStatusIsDiagnosticWithoutChangingToolSuccess() {
        for (final var visibility : ToolOutputVisibility.values()) {
            err.reset();
            final var frontend = frontend(false, false, visibility);
            frontend.onEvent(new AgentEvent.ToolFinished(
                    "bash", "output\nExit code: 7", true, false, 4, "s", null, null, eventWorkdir));
            final var stderr = err.toString(StandardCharsets.UTF_8);
            assertTrue(stderr.contains("#4 bash execution exit code: 7"), stderr);
            assertFalse(stderr.contains("failed"), stderr);
            if (visibility != ToolOutputVisibility.HIDDEN) {
                assertTrue(stderr.contains("#4 bash done"), stderr);
            }
        }
    }
}
