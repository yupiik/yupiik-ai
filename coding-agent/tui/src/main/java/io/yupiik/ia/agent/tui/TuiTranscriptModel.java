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

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Terminal UI view state on top of the shared {@link ConversationTranscript}:
 * only the scroll offset used by the renderer lives here, all conversation
 * state (transcript, streaming buffers, status, usage counters) is inherited.
 */
public class TuiTranscriptModel extends ConversationTranscript {
    public List<AgentEvent.UsageUpdated.Quota.Window> quotaWindows() {
        return super.quotaWindows();
    }

    private volatile int anchorRow;

    /** Last known max scroll offset (total rows - viewport height), set by the renderer each frame. */
    private volatile int maxViewOffset;

    /** The splitter is injected by Fusion and shared; each transcript gets its own stream parser. */
    public TuiTranscriptModel(final ThinkTagSplitter thinkTags) {
        super(thinkTags);
    }

    private volatile boolean userScrolled;

    @Override
    public synchronized void onEvent(final AgentEvent event) {
        super.onEvent(event);
        if (event instanceof AgentEvent.TextDelta
                || event instanceof AgentEvent.ThinkingDelta
                || event instanceof AgentEvent.ToolStarted
                || event instanceof AgentEvent.ToolFinished
                || event instanceof AgentEvent.PromptIntegrated
                || event instanceof AgentEvent.TurnDone
                || event instanceof AgentEvent.TurnCancelled
                || event instanceof AgentEvent.Error) {
            // auto-follow only while the user has not scrolled away: streamed events
            // must never yank the viewport while reasoning/tool history is being read
            if (!userScrolled) {
                anchorRow = maxViewOffset;
            }
        }
    }

    @Override
    public synchronized void clearConversation() {
        super.clearConversation();
        userScrolled = false;
        anchorRow = 0;
        maxViewOffset = 0;
    }

    @Override
    public synchronized void startUserTurn(final String text) {
        startUserTurn(text, true);
    }

    /**
     * Starts a new user turn, optionally re-pinning the view to the live tail.
     * Explicit submissions ({@code pinToBottom=true}) jump to the bottom and re-arm
     * auto-follow. Queued/continuation turns ({@code pinToBottom=false}) leave the
     * scroll position untouched: while the user is browsing, streamed events keep the
     * view where it is, and auto-follow only resumes once the user scrolls back to the
     * bottom.
     */
    public synchronized void startUserTurn(final String text, final boolean pinToBottom) {
        super.startUserTurn(text);
        if (pinToBottom) {
            userScrolled = false;
            anchorRow = maxViewOffset;
        }
    }

    @Override
    public synchronized void reset() {
        super.reset();
        userScrolled = false;
        anchorRow = 0;
        maxViewOffset = 0;
    }

    /**
     * A plain (non thought/tool) markdown segment belonging to one speaker.
     *
     * @param user {@code true} for a user turn, {@code false} for an assistant turn
     * @param labelled {@code true} when the segment was opened by an explicit
     *        {@code **You**}/{@code **Assistant**} marker. Leading content with no
     *        marker (the welcome block) is a plain assistant segment and must not
     *        draw a speaker header.
     * @param label the speaker label as written in the opening marker (fences
     *        stripped, any {@code [HH:mm:ss]} timestamp kept), {@code null} for
     *        unlabelled content - the marker is rendered once when the turn starts,
     *        so this label carries the turn's real time even after the current
     *        speaker labels moved on.
     */
    public record TurnSegment(String text, boolean user, boolean labelled, String label) {}

    /**
     * Splits plain transcript markdown into speaker turns. A line holding nothing but a
     * {@code **<userLabel>**} or {@code **<assistantLabel>**} heading opens a new segment
     * ({@code user} tells them apart) and marks it {@code labelled}. The heading is the
     * boundary on its own: {@link io.yupiik.ia.agent.core.shell.ConversationTranscript}
     * writes the user marker after a {@code ---} thematic break but the assistant marker
     * without one, so requiring the break would leave every assistant answer glued to the
     * user turn that preceded it. A {@code ---} introducing a heading (blank padding
     * included) is swallowed with the boundary; standalone breaks stay inside the current
     * segment. Content before the first heading is assistant and is <em>not</em> labelled.
     * Code fences never reach this method (extracted before rendering).
     *
     * <p>Speaker labels may embed a {@code {{formatDate}}} timestamp, so a marker written
     * when the turn started (e.g. {@code **[10:11:26] You**}) and the same label rendered
     * again later can differ by their leading {@code [time]} token; matching strips that
     * token so a marker still opens its turn. Labels without a timestamp keep strict
     * equality.</p>
     *
     * @param userMarker       the user marker line ({@code **label**}), default {@code **You**}
     * @param assistantMarker  the assistant marker line ({@code **label**}), default {@code **Assistant**}
     */
    public static List<TurnSegment> splitTurns(
            final String text, final String userMarker, final String assistantMarker) {
        final var lines = text.split("\n", -1);
        final var out = new ArrayList<TurnSegment>();
        var start = 0;
        var user = false;
        String label = null;
        // only a segment opened by a real marker gets a speaker header: the leading
        // welcome block has none and must not be labelled
        var labelled = false;
        for (var i = 0; i < lines.length; i++) {
            final var speaker = lines[i].strip();
            final var isUser = markerMatches(speaker, userMarker);
            final var isAssistant = markerMatches(speaker, assistantMarker);
            if (!isUser && !isAssistant) {
                continue;
            }
            // the thematic break introducing this heading is chrome for the same boundary,
            // never content: look back over the blank lines that pad it and drop it
            var last = i - 1;
            while (last >= start && lines[last].isBlank()) {
                last--;
            }
            var from = last >= start && "---".equals(lines[last].strip()) ? last : i;
            // trailing padding would render as rail-only rows just above the next header
            while (from > start && lines[from - 1].isBlank()) {
                from--;
            }
            if (from > start) {
                out.add(new TurnSegment(
                        String.join("\n", Arrays.asList(lines).subList(start, from)), user, labelled, label));
            }
            user = isUser;
            label = markerLabel(speaker);
            start = i;
            labelled = true;
        }
        if (start < lines.length) {
            out.add(new TurnSegment(
                    String.join("\n", Arrays.asList(lines).subList(start, lines.length)), user, labelled, label));
        }
        return out;
    }

    /** {@link #splitTurns(String, String, String)} with the historic default markers. */
    public static List<TurnSegment> splitTurns(final String text) {
        return splitTurns(text, "**You**", "**Assistant**");
    }

    /**
     * Marker comparison: exact equality is the fast path used by labels without a timestamp;
     * when that fails, both sides are compared after stripping the {@code **} fences and any
     * leading {@code [time]} token, so a marker written as {@code **[10:11:26] You**} still
     * opens a turn looked up as {@code **[10:11:38] You**}.
     */
    private static boolean markerMatches(final String line, final String marker) {
        if (line == null || marker == null) {
            return false;
        }
        return line.equals(marker) || normalizedMarker(line).equals(normalizedMarker(marker));
    }

    /** Strips the {@code **} fences and any leading {@code [time]} token of a marker. */
    private static String normalizedMarker(final String marker) {
        final var inner = unwrap(marker);
        final var timeEnd = timePrefixEnd(inner);
        return timeEnd < 0 ? inner : inner.substring(timeEnd).stripLeading();
    }

    /** The speaker label as written in the marker line (fences stripped, timestamp kept). */
    private static String markerLabel(final String line) {
        return unwrap(line == null ? "" : line.strip());
    }

    /** Strips the {@code **} fences of a marker line; returns the raw line when not fenced. */
    private static String unwrap(final String marker) {
        if (marker == null || marker.length() < 4 || !marker.startsWith("**") || !marker.endsWith("**")) {
            return marker;
        }
        return marker.substring(2, marker.length() - 2);
    }

    /** Index just past a leading {@code [..]} bracket token, or {@code -1} when none leads the label. */
    private static int timePrefixEnd(final String label) {
        if (label == null || !label.startsWith("[")) {
            return -1;
        }
        final var end = label.indexOf(']');
        return end < 0 ? -1 : end + 1;
    }

    public int anchorRow() {
        return anchorRow;
    }

    public void anchorRow(final int value) {
        this.anchorRow = value;
    }

    public int maxViewOffset() {
        return maxViewOffset;
    }

    public void maxViewOffset(final int value) {
        this.maxViewOffset = value;
    }

    /**
     * Sticky-scrollback flag: {@code true} while the user has explicitly moved the view
     * away from the bottom (wheel, PgUp/PgDn, Ctrl+Home/End, tool/search jumps). While
     * set, streamed events no longer re-pin the view to the bottom; it clears when the
     * view returns to the bottom or an explicit user turn starts.
     */
    public boolean userScrolled() {
        return userScrolled;
    }

    public void userScrolled(final boolean value) {
        this.userScrolled = value;
    }
}
