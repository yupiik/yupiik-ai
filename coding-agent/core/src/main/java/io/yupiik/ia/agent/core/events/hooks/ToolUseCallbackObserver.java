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
package io.yupiik.ia.agent.core.events.hooks;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.events.HookResponse;
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;
import java.nio.file.Path;

/**
 * The "user callback" stage of the {@link ToolUsePreEvent} pipeline: it runs the {@code yca.hooks.ToolUsePreEvent}
 * external callbacks concurrently (on {@link CallbackRunner}'s virtual threads) and lets any {@code forbid} ack
 * veto the tool call. Runs <em>after</em> the built-in {@link ToolUsePermissionObserver} (higher priority), and
 * skips entirely once the built-in rules already forbade.
 *
 * <p>The inherited callback timeout defaults to the bash command's {@code timeout_seconds} when this is a bash
 * call, else a sensible default; a callback may still override it via its own {@code timeoutSeconds}.</p>
 */
@ApplicationScoped
public class ToolUseCallbackObserver {
    private static final int DEFAULT_INHERITED_TIMEOUT_SECONDS = 120;

    private final CallbackRunner runner;

    public ToolUseCallbackObserver(final CallbackRunner runner) {
        this.runner = runner;
    }

    public void onToolUse(@OnEvent @Order(2000) final ToolUsePreEvent event) {
        if (event.result().forbidden()) {
            return; // built-in rules already denied: no need to run user callbacks
        }
        final var inherited = inheritedTimeout(event);
        final var responses = runner.run(
                event.id(),
                event.payload(),
                inherited,
                Path.of(event.eventContext().cwd()));
        for (final var response : responses) {
            if (response.decision() == HookResponse.HookResponseDecision.forbid) {
                event.result().forbid(response.reason() == null ? "forbidden by hook" : response.reason());
            }
        }
    }

    private static int inheritedTimeout(final ToolUsePreEvent event) {
        if (event.toolInput() != null && event.toolInput().get("timeout_seconds") instanceof final Number n) {
            final var v = n.intValue();
            return v > 0 ? v : DEFAULT_INHERITED_TIMEOUT_SECONDS;
        }
        return DEFAULT_INHERITED_TIMEOUT_SECONDS;
    }
}
