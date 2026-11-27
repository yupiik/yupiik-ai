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
package io.yupiik.ia.agent.core.tools;

/** Carries already sanitized UI detail separately from the model-safe failure message. */
public final class ToolDiagnosticException extends RuntimeException {
    private final String detail;

    public ToolDiagnosticException(final String message, final String detail) {
        super(message, null, false, false);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }

    /** Keeps unexpected exception details out of model-visible tool results. */
    public static ToolDiagnosticException fromFailure(final String tool, final RuntimeException failure) {
        if (failure instanceof ToolDiagnosticException diagnostic) {
            return diagnostic;
        }
        return new ToolDiagnosticException("Tool " + tool + " failed.", failure.toString());
    }
}
