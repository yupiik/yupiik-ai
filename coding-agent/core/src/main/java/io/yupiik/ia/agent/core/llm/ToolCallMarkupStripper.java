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

/**
 * Backward-compatible facade over the tolerant {@link MarkupToolCallScanner}. Retained so callers
 * and direct tests that reference the historical stripper keep compiling and behaving identically;
 * the real logic now lives in the shared scanner.
 *
 * @see MarkupToolCallScanner
 * @see InBandToolCallExtractor
 */
@ApplicationScoped
public class ToolCallMarkupStripper {

    private final MarkupToolCallScanner scanner = new MarkupToolCallScanner();

    public boolean containsMarkup(final String raw) {
        return scanner.containsMarkup(raw);
    }

    /**
     * Removes every tool-call block from the raw model output.
     * A {@code null} input becomes an empty string; clean text is returned unchanged.
     *
     * @param raw the model message content as received, may be {@code null}.
     * @return the text without any tool-call markup, surrounding prose preserved.
     */
    public String strip(final String raw) {
        return scanner.strip(raw);
    }
}
