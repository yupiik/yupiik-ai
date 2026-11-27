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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;

/**
 * Pushes each running shell's quota right when it registers (TUI tab creation, web session attach,
 * {@code /resume} rebind), so the sidebar gauge is populated before the first turn ran - the regular
 * pushes only happen on a provider change ({@code AgentLoop.updateChatSettings}) or during a turn.
 * The push is a no-op when unaware the shell has no runtime or the provider does not enable Codex
 * usage (the quota store short-circuits the fetch and the event carries an empty quota the UIs hide).
 */
@ApplicationScoped
public class ShellQuotaInitializer {
    public void onShellRegistered(@OnEvent final ShellRegistered event) {
        final var env = event.env();
        final var session = env == null ? null : env.session();
        final var runtime = session == null ? null : session.runtime();
        final var loop = runtime == null ? null : runtime.loop();
        if (loop != null) {
            loop.refreshUsage();
        }
    }
}
