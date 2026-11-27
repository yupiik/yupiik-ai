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

import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core chat request: wire fields plus the sender's per-session selection
 * ({@link ChatSettings}). The selection is resolved by {@link ChatClient} into
 * the concrete wire values (model, sampling, extras) before translation.
 *
 * <p>The {@code settings} component is core-internal state: it never crosses
 * into {@code llm-client} and is never serialized — only {@code LlmWire}
 * maps this type to the wire request, field by field, and deliberately skips
 * {@code settings}. Keep it that way: a plain record, deliberately NOT a
 * fusion JSON model (no {@code @JsonModel}/{@code @JsonProperty}), so the
 * fusion works records-only and the selection can never leak to the wire.</p>
 *
 * @param model     explicit model reference (plain name, or {@code $provider/model}); when blank the
 *                  settings model applies, then the effective provider's configured model. An
 *                  explicit value always wins over the settings selection.
 * @param sessionId session attribution for log routing ({@link io.yupiik.ia.agent.core.wiring.SessionAwareLogRecord});
 *                  like {@code settings} it is core-internal state that never crosses into
 *                  {@code llm-client} and is never serialized to the wire - {@code LlmWire} maps this
 *                  type field by field and deliberately skips it. {@code null} means the call is not
 *                  owned by a session (background/global work).
 * @param settings  the sender's session selection; {@link ChatSettings#EMPTY} (the default) means
 *                  the process default provider and model apply
 */
public record ChatCompletionRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Double topP,
        Boolean stream,
        List<Tool> tools,
        String toolChoice,
        Integer maxTokens,
        String reasoningEffort,
        Map<String, Object> others,
        String sessionId,
        ChatSettings settings) {

    /** Process-default selection ({@link ChatSettings#EMPTY}). */
    public ChatCompletionRequest(
            String model,
            List<Message> messages,
            Double temperature,
            Double topP,
            Boolean stream,
            List<Tool> tools,
            String toolChoice,
            Integer maxTokens,
            String reasoningEffort,
            Map<String, Object> others) {
        this(
                model,
                messages,
                temperature,
                topP,
                stream,
                tools,
                toolChoice,
                maxTokens,
                reasoningEffort,
                others,
                null,
                ChatSettings.EMPTY);
    }

    /** Process-default selection, {@code topP = null}. */
    public ChatCompletionRequest(
            String model,
            List<Message> messages,
            Double temperature,
            Boolean stream,
            List<Tool> tools,
            String toolChoice,
            Integer maxTokens,
            String reasoningEffort,
            Map<String, Object> others) {
        this(
                model,
                messages,
                temperature,
                null,
                stream,
                tools,
                toolChoice,
                maxTokens,
                reasoningEffort,
                others,
                null,
                ChatSettings.EMPTY);
    }

    /** A copy carrying this sender's session selection (replacing the previous one); keeps the session id. */
    public ChatCompletionRequest withSettings(final ChatSettings value) {
        return new ChatCompletionRequest(
                model,
                messages,
                temperature,
                topP,
                stream,
                tools,
                toolChoice,
                maxTokens,
                reasoningEffort,
                others,
                sessionId,
                value);
    }

    /** A copy recording this request's retry mode under a reserved {@code others} key (see {@link io.yupiik.ia.agent.core.llm.ChatClient#RETRY_MODE_OTHERS_KEY}). */
    public ChatCompletionRequest withRetryMode(final RetryMode mode) {
        return new ChatCompletionRequest(
                model,
                messages,
                temperature,
                topP,
                stream,
                tools,
                toolChoice,
                maxTokens,
                reasoningEffort,
                putIfAbsent(others, ChatClient.RETRY_MODE_OTHERS_KEY, mode),
                sessionId,
                settings);
    }

    /** A copy carrying the given session attribution (replacing the previous one); keeps the settings. */
    public ChatCompletionRequest withSessionId(final String value) {
        return new ChatCompletionRequest(
                model,
                messages,
                temperature,
                topP,
                stream,
                tools,
                toolChoice,
                maxTokens,
                reasoningEffort,
                others,
                value,
                settings);
    }

    /**
     * Apply a background-path tuning payload (from {@code yca.model.*} compact/prune/memory)
     * put-if-absent: only fields the request does not already set (temperature, topP, maxTokens,
     * reasoningEffort, top_k, the {@code extra} object) are taken from the tuning, so the session
     * selection still wins. The {@code -1} sentinel on {@code maxTokens} (and any non-positive
     * value) means "unset": it is mapped to {@code null} so the downstream budget resolution fills
     * a real positive value and the wire never emits an invalid {@code max_tokens:-1} (rejected by
     * gateways such as freeinference with {@code 400 Invalid JSON or schema in request body}).
     */
    public ChatCompletionRequest withTuning(final ModelVariantConfiguration tuning) {
        if (tuning == null) {
            return this;
        }
        var otherMap = others;
        final var topK = tuning.topK();
        if (topK != null && topK > 0) {
            otherMap = putIfAbsent(otherMap, "top_k", topK);
        }
        final var extra = tuning.extra();
        if (extra != null && !extra.isBlank()) {
            final var parsed = parseObject(extra);
            if (!parsed.isEmpty()) {
                for (final var entry : parsed.entrySet()) {
                    otherMap = putIfAbsent(otherMap, entry.getKey(), entry.getValue());
                }
            }
        }
        final var maxTokensFromTuning =
                tuning.maxTokens() != null && tuning.maxTokens() > 0 ? tuning.maxTokens() : null;
        return new ChatCompletionRequest(
                model,
                messages,
                temperature == null ? tuning.temperature() : temperature,
                topP == null ? tuning.topP() : topP,
                stream,
                tools,
                toolChoice,
                maxTokens == null ? maxTokensFromTuning : maxTokens,
                (reasoningEffort == null || reasoningEffort.isBlank()) ? tuning.reasoningEffort() : reasoningEffort,
                otherMap,
                sessionId,
                settings);
    }

    private static Map<String, Object> putIfAbsent(
            final Map<String, Object> base, final String key, final Object value) {
        if (base != null && base.containsKey(key)) {
            return base;
        }
        final var out = base == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(base);
        out.put(key, value);
        return out;
    }

    /**
     * Minimal, safe parse of a small JSON object (the {@code yca.model.*.extra} payload, typically
     * {@code {"thinking":{"type":"enabled"}}}). Returns an empty map when the input is not a JSON
     * object or is unparseable, so a malformed {@code extra} is silently skipped rather than failing
     * the underlying LLM call. Handles nested objects/arrays/strings/numbers/booleans/null.
     */
    private static Map<String, Object> parseObject(final String json) {
        final int[] idx = {0};
        final var len = json.length();
        skipWs(json, idx);
        if (idx[0] >= len || json.charAt(idx[0]) != '{') {
            return Map.of();
        }
        final Object root;
        try {
            root = parseValue(json, idx);
        } catch (final RuntimeException e) {
            return Map.of();
        }
        return root instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Object parseValue(final String json, final int[] idx) {
        final var c = nextNonWs(json, idx);
        return switch (c) {
            case '{' -> parseObjectValue(json, idx);
            case '[' -> parseArrayValue(json, idx);
            case '"' -> parseString(json, idx);
            case 't' -> {
                expect(json, idx, "rue");
                yield Boolean.TRUE;
            }
            case 'f' -> {
                expect(json, idx, "alse");
                yield Boolean.FALSE;
            }
            case 'n' -> {
                expect(json, idx, "ull");
                yield null;
            }
            default -> parseNumber(json, idx, c);
        };
    }

    private static Map<String, Object> parseObjectValue(final String json, final int[] idx) {
        final var out = new LinkedHashMap<String, Object>();
        skipWs(json, idx); // after '{'
        if (consume(json, idx, '}')) {
            return out;
        }
        while (true) {
            skipWs(json, idx);
            final var key = parseString(json, idx);
            skipWs(json, idx);
            require(json, idx, ':');
            final var value = parseValue(json, idx);
            out.put(key, value);
            skipWs(json, idx);
            if (consume(json, idx, '}')) {
                return out;
            }
            require(json, idx, ',');
        }
    }

    private static List<Object> parseArrayValue(final String json, final int[] idx) {
        final var out = new ArrayList<Object>();
        skipWs(json, idx); // after '['
        if (consume(json, idx, ']')) {
            return out;
        }
        while (true) {
            out.add(parseValue(json, idx));
            skipWs(json, idx);
            if (consume(json, idx, ']')) {
                return out;
            }
            require(json, idx, ',');
        }
    }

    private static String parseString(final String json, final int[] idx) {
        consume(json, idx, '"');
        final var sb = new StringBuilder();
        while (idx[0] < json.length()) {
            final var c = json.charAt(idx[0]++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (idx[0] >= json.length()) {
                    throw new IllegalArgumentException("unterminated escape");
                }
                final var e = json.charAt(idx[0]++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (idx[0] + 4 > json.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(json.substring(idx[0], idx[0] + 4), 16));
                        idx[0] += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private static Object parseNumber(final String json, final int[] idx, final char first) {
        final var sb = new StringBuilder();
        sb.append(first);
        while (idx[0] < json.length()) {
            final var c = json.charAt(idx[0]);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                sb.append(c);
                idx[0]++;
            } else {
                break;
            }
        }
        final var tok = sb.toString();
        if (tok.indexOf('.') >= 0 || tok.indexOf('e') >= 0 || tok.indexOf('E') >= 0) {
            return Double.parseDouble(tok);
        }
        return Long.parseLong(tok);
    }

    private static void expect(final String json, final int[] idx, final String literal) {
        if (idx[0] + literal.length() > json.length() || !json.regionMatches(idx[0], literal, 0, literal.length())) {
            throw new IllegalArgumentException("expected " + literal);
        }
        idx[0] += literal.length();
    }

    private static char nextNonWs(final String json, final int[] idx) {
        skipWs(json, idx);
        if (idx[0] >= json.length()) {
            throw new IllegalArgumentException("unexpected end");
        }
        return json.charAt(idx[0]++);
    }

    private static void skipWs(final String json, final int[] idx) {
        while (idx[0] < json.length()) {
            final var c = json.charAt(idx[0]);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                idx[0]++;
            } else {
                break;
            }
        }
    }

    private static boolean consume(final String json, final int[] idx, final char expected) {
        if (idx[0] < json.length() && json.charAt(idx[0]) == expected) {
            idx[0]++;
            return true;
        }
        return false;
    }

    private static void require(final String json, final int[] idx, final char expected) {
        if (!consume(json, idx, expected)) {
            throw new IllegalArgumentException("expected " + expected);
        }
    }
}
