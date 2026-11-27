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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.security.SecretRegistry;
import io.yupiik.llm.client.codex.model.CodexTokenStore;

@ApplicationScoped
public class CodexSecretRegistration {
    private final SecretRegistry registry;

    public CodexSecretRegistration(final SecretRegistry registry) {
        this.registry = registry;
    }

    public void register(final CodexTokenStore store) {
        if (registry == null || store == null) {
            return;
        }
        final var identity = store.agentIdentity();
        registry.registerValues(
                store.accessToken(),
                store.refreshToken(),
                store.idToken(),
                store.openaiApiKey(),
                identity == null ? null : identity.agentPrivateKeyPkcs8Base64());
    }
}
