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
import io.yupiik.ia.agent.core.events.ToolUsePreEvent;

/**
 * The built-in "default command configuration validation" stage of the {@link ToolUsePreEvent} pipeline: it runs
 * the session's existing {@link io.yupiik.ia.agent.core.permissions.PermissionService#check} (deny/allow lists,
 * plan-mode read-only families, {@code yca.permissions.bash.rules}, session grants, permission mode and the
 * approval gate) with the <em>lowest</em> priority, so it decides before the configured hook callbacks run.
 *
 * <p>When denied it mutates {@link ToolUsePreEvent#result()} to {@code forbid}, which short-circuits the
 * configured callbacks (they skip once forbidden).</p>
 */
@ApplicationScoped
public class ToolUsePermissionObserver {
    public void onToolUse(@OnEvent @Order(Integer.MIN_VALUE) final ToolUsePreEvent event) {
        if (event.result().forbidden()) {
            return;
        }
        final var permissions = event.permissions();
        if (permissions == null) {
            return; // standalone call without a session: nothing to enforce
        }
        final var decision = permissions.check(event.toolName(), event.category(), event.toolInput(), event.readonly());
        if (!decision.allowed()) {
            event.result().forbid(decision.reason());
        }
    }
}
