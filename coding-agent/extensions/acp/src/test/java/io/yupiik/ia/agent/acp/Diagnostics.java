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
package io.yupiik.ia.agent.acp;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.security.OutboundLlmRequest;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.logging.Logger;

/** Test-only subprocess diagnostics: exercises direct writes and JUL during the whole lifecycle. */
@DefaultScoped
public class Diagnostics {
    private final ProviderManager providers;

    public Diagnostics(final ProviderManager providers) {
        this.providers = providers;
    }

    public void selection(@OnEvent final OutboundLlmRequest request) {
        System.err.println("background-selection:" + providers.resolvedCompact(request.settings()) + "|"
                + providers.resolvedMemory(request.settings()) + "|" + providers.currentProviderId());
    }

    public void start(@OnEvent final Start start) {
        emit("startup");
    }

    public void stop(@OnEvent final Stop stop) {
        emit("shutdown");
    }

    public void turn(@OnEvent final AgentEvent event) {
        if (event instanceof AgentEvent.TextDelta) {
            emit("turn");
        }
    }

    private void emit(final String phase) {
        System.out.println("direct-" + phase);
        final var logger = Logger.getLogger("acp-test");
        logger.info("info-" + phase);
        logger.warning("warning-" + phase);
    }
}
