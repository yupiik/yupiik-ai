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
package io.yupiik.maven.knowledge.llm;

import java.util.regex.Pattern;

/**
 * Small helpers shared by every {@link KnowledgeLlm} implementation to normalise the raw LLM text
 * (a runtime content blob, or a possibly markdown-fenced answer) before it is parsed.
 */
public final class LlmText {
    private static final Pattern TRAILING_CODE_FENCE = Pattern.compile("```\\s*$");

    private LlmText() {
        // no-op
    }

    /**
     * Serialise an LLM message content into a string, tolerating a null payload.
     *
     * @param content the raw {@code content} field of an LLM message.
     * @return the content as a string, or an empty string when {@code null}.
     */
    public static String rawContent(final Object content) {
        return content == null ? "" : String.valueOf(content);
    }

    /**
     * Strip a leading {@code ```json} markdown fence a client may have wrapped the answer in.
     *
     * @param text the raw LLM answer.
     * @return the text without a leading {@code ```json} fence and any trailing {@code ```}.
     */
    public static String trimJson(final String text) {
        final var t = text.strip();
        if (t.startsWith("```json")) {
            final var nl = t.indexOf('\n');
            return nl > 0
                    ? TRAILING_CODE_FENCE
                            .matcher(t.substring(nl + 1))
                            .replaceAll("")
                            .strip()
                    : t;
        }
        return t;
    }

    /**
     * Strip a leading {@code ```} / {@code ```lang} fence line and a trailing {@code ```} line a model may have
     * wrapped the generated file in.
     *
     * @param text the raw LLM answer.
     * @return the text without an opening markdown fence line nor a trailing closing fence.
     */
    public static String stripMarkdownFence(final String text) {
        final var stripped = text.strip();
        if (stripped.startsWith("```")) {
            final var nl = stripped.indexOf('\n');
            var body = nl > 0 ? stripped.substring(nl + 1) : "";
            if (body.endsWith("```")) {
                body = body.substring(0, body.length() - 3);
            }
            return body.strip();
        }
        return stripped;
    }
}
