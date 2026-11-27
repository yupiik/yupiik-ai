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

import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.cli.ToolPresenter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared, frontend-local invocation correlation. Callers serialize access with event rendering. */
final class ToolInvocations {
    private final List<Call> calls = new ArrayList<>();

    Call start(final AgentEvent.ToolStarted event) {
        final var call = new Call(event.name(), event.args(), event.ordinal(), event.sessionId(), event.subagentId());
        calls.add(call);
        return call;
    }

    Call find(final String name, final long ordinal, final String session, final String actor) {
        for (final var call : calls) {
            if (Objects.equals(session, call.session)
                    && Objects.equals(actor, call.actor)
                    && (ordinal >= 0 ? ordinal == call.ordinal : Objects.equals(name, call.name))) {
                return call;
            }
        }
        final var call = new Call(name, null, ordinal, session, actor);
        calls.add(call);
        return call;
    }

    void remove(final Call call) {
        calls.remove(call);
    }

    List<Call> active() {
        return List.copyOf(calls);
    }

    static final class Call {
        private final String name;
        private final String args;
        private final long ordinal;
        private final String session;
        private final String actor;
        private final StringBuilder fragments = new StringBuilder();
        private final StringBuilder displayed = new StringBuilder();

        private Call(
                final String name, final String args, final long ordinal, final String session, final String actor) {
            this.name = name;
            this.args = args;
            this.ordinal = ordinal;
            this.session = session;
            this.actor = actor;
        }

        String args() {
            return args;
        }

        String header() {
            return (ordinal >= 0 ? "#" + ordinal + " " : "") + name;
        }

        String summary(final ToolPresenter presenter, final ToolOutputVisibility visibility) {
            if (args == null || visibility == ToolOutputVisibility.HIDDEN) {
                return "";
            }
            if ("bash".equals(name) && !visibility.shown()) {
                return " (shell execution)";
            }
            final var summary = presenter.summarize(name, args);
            return " " + (name != null && summary.startsWith(name + "(") ? summary.substring(name.length()) : summary);
        }

        List<String> append(final String fragment) {
            if (fragment != null) {
                fragments.append(fragment);
            }
            final var lines = new ArrayList<String>();
            var end = fragments.indexOf("\n");
            while (end >= 0) {
                final var line = fragments.substring(0, end);
                lines.add(line);
                displayed.append(line).append('\n');
                fragments.delete(0, end + 1);
                end = fragments.indexOf("\n");
            }
            return lines;
        }

        String flush() {
            final var rest = fragments.toString();
            displayed.append(rest);
            fragments.setLength(0);
            return rest;
        }

        String remaining(final String output) {
            if (output == null) {
                return "";
            }
            // Only an exact displayed prefix is redundant. A stream can be partial,
            // or differ from the final result (stderr, tool wrappers, diagnostics).
            final var prefix = displayed.toString();
            return output.startsWith(prefix) ? output.substring(prefix.length()) : output;
        }
    }
}
