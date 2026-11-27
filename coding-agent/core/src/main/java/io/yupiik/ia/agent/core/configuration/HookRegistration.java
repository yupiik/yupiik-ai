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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.core.events.YcaEvent;
import java.util.List;

/**
 * Hook callback registration under {@code yca.hooks}. Each entry binds an {@code event} (the {@link
 * YcaEvent#id()} — its simple class name, eg. {@code ToolUsePreEvent}) to an external program run as a
 * callback for that event.
 *
 * <p>The callback contract is event-based: the event {@code @JsonModel} record is piped to the program on
 * stdin as camelCase JSON, and the program may print a {@code HookResponse} JSON on stdout to drive a
 * decision (see the event documentation). Only a valid JSON output on exit 0 — or an empty one (no action) —
 * is "ok"; invalid JSON, a non-zero exit or a timeout all raise an error for that callback.</p>
 */
@JsonModel
public record HookRegistration(
        @Property(
                required = true,
                documentation = "Event id this callback is bound to — the simple class name of the event, eg. "
                        + "`ToolUsePreEvent`, `UserPromptSubmit`, `PostToolUse`. A callback executes only"
                        + " when this event fires.")
        String event,

        @Property(
                required = true,
                documentation =
                        "External program (and arguments) run for the event. A `List<String>`; the CLI form splits on"
                                + " the default separator `,` (eg. `node,.yca/hooks/pre.js`). When a separator appears"
                                + " inside an argument, override it with the fusion `sep=` prefix (single character,"
                                + " eg. `sep=|cmd|node|.yca/hooks/pre.js`) so the program name and its arguments are not"
                                + " mis-split. The event JSON is piped on stdin; a non-empty valid JSON on stdout is the"
                                + " ack.")
        List<String> command,

        @Property(
                documentation = "Working directory for the callback process; defaults to the agent process directory. "
                        + "Set it to root the callback (eg. a repository-local hook script) in a specific "
                        + "directory.")
        String dir,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Timeout in seconds for this callback; `<= 0` inherits the triggering event's own budget"
                                + " (eg. a bash command's `timeout_seconds`). On timeout the callback raises an error.")
        int timeoutSeconds) {

    /** A normalized copy with a blank timeout meaning "inherit". */
    public int effectiveTimeoutSeconds(final int inherited) {
        return timeoutSeconds > 0 ? timeoutSeconds : inherited;
    }
}
