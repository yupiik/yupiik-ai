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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.llm.client.codex.model.CodexTokenStore;

/**
 * Runs the interactive Codex login from the header-render path, making {@code /codex login} optional:
 * when a {@code {{codexAuth}}}-family helper needs a session that does not exist yet, the dispatcher
 * (wired by the shell layer where a frontend and {@link CodexLoginService} are available) performs the
 * browser login and returns the freshly persisted store, which the renderer then uses directly.
 *
 * <p>The dispatcher is registered only from the interactive (user-facing) entry point. A {@code null}
 * dispatcher - headless/background contexts such as the model-listing path - keeps the historical
 * behavior of throwing {@link CodexNotAuthenticatedException}, so a browser login is never popped from
 * a non-interactive thread. The {@link CodexTokenStore} returned must already be saved so the caller's
 * single-flight lock and refresh logic agree on the persisted state.</p>
 */
public interface CodexLoginDispatcher {

    /** Interactively signs the provider in and returns the freshly persisted session. */
    CodexTokenStore signIn(String providerName);
}
