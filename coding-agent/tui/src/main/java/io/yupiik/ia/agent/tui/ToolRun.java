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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A maximal consecutive sequence of finished tool cards rendered as one
 * collapsible group (the "tool run").
 *
 * <p>Runs are formed at render time from the flat transcript regions: a run
 * starts at the first tool region, extends over the next tool regions as long
 * as they are adjacent (no prose/thought/log/turn boundary between them) and
 * each card is finished (never {@code running}, so a streaming card breaks the
 * run instead of shifting it under the viewport). The run identity is the
 * ordinal of its first member, which is stable across transcript trims since
 * ordinals are never recycled.</p>
 */
public final class ToolRun {

    /** A member card plus the raw transcript region it was parsed from. */
    public record Member(ToolCard card, String regionText) {}

    private final List<Member> members;

    private ToolRun(final List<Member> members) {
        this.members = List.copyOf(members);
    }

    /** Creates an immutable run from the given (consecutive, finished) members. */
    public static ToolRun of(final List<Member> members) {
        return new ToolRun(members);
    }

    /** Opens a fresh run accumulator with the first member. */
    public static Builder builder(final Member first) {
        return new Builder(first);
    }

    public List<Member> members() {
        return members;
    }

    /** Number of member cards. */
    public int size() {
        return members.size();
    }

    /** The first member. */
    public Member first() {
        return members.get(0);
    }

    /**
     * Stable run identity: the ordinal of the first member, or {@code -1} when
     * that card carries no ordinal (never the case for real transcript cards but
     * kept defensive for hand-built cards).
     */
    public long id() {
        return first().card().ordinal();
    }

    /** Counts of member cards per command (via {@link ToolCard#keyCommand()}), sorted by command. */
    public Map<String, Integer> commandCounts() {
        final var counts = new TreeMap<String, Integer>();
        for (final var member : members) {
            final var card = member.card();
            final var key = card.keyCommand();
            counts.merge(key.isBlank() ? card.name() : key, 1, Integer::sum);
        }
        return counts;
    }

    /** Number of failed member cards. */
    public int failures() {
        int n = 0;
        for (final var member : members) {
            if (member.card().failed()) {
                n++;
            }
        }
        return n;
    }

    /** Sum of member wall-clock durations in milliseconds (0 when unknown). */
    public long elapsedMs() {
        long total = 0;
        for (final var member : members) {
            total += Math.max(0, member.card().elapsedMs());
        }
        return total;
    }

    /** True when this card can extend the run: it must be finished (never running). */
    public boolean canExtend(final ToolCard card) {
        return card != null && !card.running();
    }

    /** Mutable accumulator used by the renderer's region loop. */
    public static final class Builder {
        private final List<Member> members = new ArrayList<>();

        private Builder(final Member first) {
            members.add(first);
        }

        public void add(final Member member) {
            members.add(member);
        }

        public Member last() {
            return members.get(members.size() - 1);
        }

        public boolean isEmpty() {
            return members.isEmpty();
        }

        public ToolRun build() {
            return new ToolRun(members);
        }
    }
}
