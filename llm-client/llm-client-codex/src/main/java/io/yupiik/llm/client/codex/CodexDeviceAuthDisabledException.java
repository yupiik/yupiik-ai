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
 * Thrown when the OpenAI issuer refuses the device-code flow because device sign-in is not enabled
 * for the client/account. Surfaced to the user so they can switch to the browser flow (or enable
 * device sign-in in their ChatGPT security settings) instead of seeing an opaque server response.
 */
public class CodexDeviceAuthDisabledException extends IllegalStateException {

    public CodexDeviceAuthDisabledException(final String message) {
        super(message);
    }

    /** Creates the exception with the underlying server/transport failure as the cause. */
    public CodexDeviceAuthDisabledException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
