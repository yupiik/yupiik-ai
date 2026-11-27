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

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.llm.CodexLoginDispatcher;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.llm.client.codex.model.CodexTokenStore;

/**
 * Wires the process-wide {@link CodexLoginDispatcher} into {@link CodexAuthTemplateHelper}, making
 * {@code /codex login} optional for well-configured providers: when a {@code {{codexAuth}}}-family
 * helper needs a session that does not exist yet, the dispatcher runs the interactive browser login
 * and surfaces the authorize URL through the currently-active shell's frontend.
 *
 * <p>The dispatcher is only armed while at least one shell is running (the interactive entry point).
 * When {@link ShellRegistry} reports no active shell - e.g. a headless/background model-listing path
 * such as {@code UsageTracker}-driven {@code chatClient.models()} - the dispatcher throws, so a
 * browser login is never popped from a non-interactive thread (the historical
 * {@code CodexNotAuthenticatedException} behavior is preserved there).</p>
 */
@ApplicationScoped
public class CodexLoginDispatcherRegistrar {

    private final CodexAuthTemplateHelper templateHelper;
    private final CodexLoginService loginService;
    private final ShellRegistry shellRegistry;

    public CodexLoginDispatcherRegistrar(
            final CodexAuthTemplateHelper templateHelper,
            final CodexLoginService loginService,
            final ShellRegistry shellRegistry) {
        this.templateHelper = templateHelper;
        this.loginService = loginService;
        this.shellRegistry = shellRegistry;
    }

    public void onStart(@OnEvent final Start start) {
        templateHelper.setCodexLoginDispatcher(this::signInInteractively);
    }

    /**
     * Runs the interactive login for {@code providerName}, surfacing the authorize URL through the
     * first active shell's frontend. Throws {@link IllegalStateException} when no shell is active
     * (non-interactive context) so a browser login is never launched headlessly.
     */
    private CodexTokenStore signInInteractively(final String providerName) {
        final var env = activeShellEnv();
        if (env == null) {
            throw new IllegalStateException(
                    "Codex provider '" + providerName + "' has no session and no interactive shell to sign in; "
                            + "run /codex login " + providerName + " first");
        }
        return loginService.signIn(
                providerName,
                false,
                url -> {
                    env.frontend().openExternalUrl(url);
                    env.frontend()
                            .transcript(
                                    "<" + url + ">\n\nWaiting for authorization; you can close the tab when done...");
                },
                _ -> false);
    }

    private ShellEnv activeShellEnv() {
        for (final var sessionId : shellRegistry.sessions()) {
            final var env = shellRegistry.active(sessionId);
            if (env != null) {
                return env;
            }
        }
        return null;
    }
}
