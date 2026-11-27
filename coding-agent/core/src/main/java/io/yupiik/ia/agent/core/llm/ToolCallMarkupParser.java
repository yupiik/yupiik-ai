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
import java.util.List;

/**
 * Backward-compatible facade over the tolerant {@link MarkupToolCallScanner}. Retained so callers
 * and direct tests that reference the historical parser keeps compiling and behaving identically;
 * the real logic now lives in the shared scanner to avoid the fixed-spelling regex drift that used
 * to silence arguments (eg. a mangled parameter closer dropping {@code path}).
 *
 * @see MarkupToolCallScanner
 * @see InBandToolCallExtractor
 */
@ApplicationScoped
public class ToolCallMarkupParser {

    private final MarkupToolCallScanner scanner = new MarkupToolCallScanner();

    /**
     * Extracts the markup tool calls from the raw model output.
     *
     * @param raw the model message content as received, may be {@code null}.
     * @return the parsed tool calls, empty when no markup is present.
     */
    public List<ToolCall> parse(final String raw) {
        return scanner.parse(raw);
    }
}
