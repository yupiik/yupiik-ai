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
package io.yupiik.llm.client.codex;

/**
 * Thrown when a Codex-flavored request needs an OAuth session but none is cached yet for the
 * provider. Frontends catch this to trigger the interactive login (browser/devool) automatically
 * instead of failing the call; {@link #provider()} identifies the provider to sign in.
 */
public class CodexNotAuthenticatedException extends IllegalStateException {

    private final String provider;

    public CodexNotAuthenticatedException(final String provider) {
        super("No cached Codex session for provider '" + provider + "'. Complete the interactive login to continue.");
        this.provider = provider;
    }

    /** The provider id whose session is missing. */
    public String provider() {
        return provider;
    }
}
