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
package io.yupiik.ia.agent.web;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.List;

/**
 * Sessions the {@code yca web} command was asked to restore out-of-the-box via its
 * {@code --session} option(s). The {@link WebCommand} sets them at startup (before the browser
 * connects); the frontend asks for them through {@code sessions.restore} and opens a tab for each,
 * so `yca web --session a --session b` boots with those sessions' tabs already restored. Unknown ids
 * are dropped by the RPC (only existing sessions are returned), keeping a stale/deleted id from
 * creating a phantom tab.
 */
@ApplicationScoped
public class WebRestoreSessions {
    private volatile List<String> sessions = List.of();

    public List<String> sessions() {
        return sessions;
    }

    public void sessions(final List<String> sessions) {
        this.sessions = sessions == null ? List.of() : sessions;
    }
}
