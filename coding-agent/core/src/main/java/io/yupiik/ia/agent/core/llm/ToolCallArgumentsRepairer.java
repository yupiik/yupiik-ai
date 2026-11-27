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
import io.yupiik.fusion.json.JsonMapper;
import java.io.StringReader;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Best-effort repair of tool call arguments emitted by the model before they are exposed and
 * stored. Some OpenAI compatible backends (llama.cpp / ollama style) reject whole turns with an
 * HTTP 500 when the generated arguments carry unescaped control characters (literal tabs /
 * newlines inside string values) - the "Failed to parse tool call arguments as JSON:
 * [json.exception.parse_error.101]" signature, typically under a heavy context - or a dangling
 * truncated tail. This helper sanitizes the payload conservatively:
 *
 * <ul>
 *   <li>arguments without raw control characters are returned untouched (zero cost path);</li>
 *   <li>raw 0x00-0x1F control characters are escaped ({@code \\uXXXX}) only inside string values
 *       (JSON whitespace between tokens is preserved) and the result validated by a strict parse;</li>
 *   <li>if the repaired payload still does not parse, the original is returned: we never invent
 *       values or drop arguments, a corrupted call must stay visible.</li>
 * </ul>
 */
@ApplicationScoped
public class ToolCallArgumentsRepairer {

    private final JsonMapper mapper;

    public ToolCallArgumentsRepairer(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Repair the arguments when {@code enabled} is true. Returns the (possibly repaired) arguments.
     *
     * <p>Two repair passes are applied, in order, only when the payload is not already valid JSON:</p>
     * <ol>
     *   <li>raw 0x00-0x1F control characters inside string values are escaped ({@code \\uXXXX});</li>
     *   <li>when the payload still does not parse (eg. a dangling truncated tail or trailing
     *       garbage after the closing brace - the "unexpected '{'; expected end of input"
     *       signature), the trailing bytes after the first complete JSON value are dropped.</li>
     * </ol>
     * Each pass re-validates with a strict parse; when nothing recovers a parseable payload the
     * original is returned untouched: a corrupted call must stay visible, never silently changed.
     *
     * @param arguments raw arguments string emitted by the model.
     * @param enabled   whether the repair is active for the calling model.
     * @param model     effective model name, used only for the FINE log.
     */
    public String repair(final String arguments, final boolean enabled, final String model) {
        if (arguments == null || arguments.isBlank() || !enabled || isValidJson(mapper, arguments)) {
            // blank/enabled-off/valid JSON are all zero-cost paths; a valid payload is never touched
            return arguments;
        }
        var repaired = escapeControlChars(arguments);
        if (!repaired.equals(arguments) && isValidJson(mapper, repaired)) {
            logRepair(model, arguments, repaired, "control-char escape");
            return repaired;
        }
        final var trimmed = trimTailGarbage(repaired);
        if (!trimmed.equals(repaired) && isValidJson(mapper, trimmed)) {
            logRepair(model, arguments, trimmed, "tail trim");
            return trimmed;
        }
        // escaping did not change anything or no fix helped: keep the original so the corruption
        // remains visible instead of silently changing the model intent
        return arguments;
    }

    private static void logRepair(final String model, final String original, final String repaired, final String pass) {
        LOGGER.fine(() -> "repaired tool call arguments for " + model + " (" + pass + "): " + preview(original) + " -> "
                + preview(repaired));
    }

    /**
     * Drops any trailing garbage after the first complete JSON value: a second JSON value
     * ({@code {...}} / {@code [...]}), free text, or an incomplete re-emitted opening brace /
     * bracket - the "expected end of input" and "trailing characters" signatures produced by a
     * truncated tool-call blob the model kept writing after it already closed the object. A
     * complete JSON document is a single value, so nothing structurally valid follows a closed
     * top-level object (only JSON whitespace): any non-whitespace tail is garbage and safe to
     * drop. {@link #repair} re-validates the cut with a strict parse and falls back to the
     * original when it does not itself parse, so a genuinely corrupted call stays visible. When
     * only whitespace follows the completed value (or no complete value was found) the input is
     * returned unchanged. The prefix scan tracks string/escape depth so a {@code }} / {@code ]}
     * inside a string value is never mistaken for the value end.
     */
    static String trimTailGarbage(final String json) {
        var inString = false;
        var depth = 0;
        var firstComplete = -1; // index just past the first brace/bracket that closed a value
        for (var i = 0; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // escaped char: skip both
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth == 0 && firstComplete < 0) {
                        firstComplete = i + 1;
                    }
                }
                default -> {
                    // structural JSON whitespace is ignored; anything else inside a value
                    // (letters/digits of a literal) keeps the current depth open
                }
            }
        }
        if (firstComplete <= 0 || firstComplete >= json.length()) {
            return json;
        }
        var j = firstComplete;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
            j++;
        }
        if (j >= json.length()) {
            // trailing whitespace only: nothing to cut
            return json;
        }
        // any non-whitespace after the closed first value is trailing garbage (a second JSON value,
        // free text or a dangling continuation): a complete JSON document is a single value, so the
        // tail cannot belong to the call. Drop it; repair() re-validates the result with a strict
        // parse and falls back to the original when the cut does not itself parse.
        return json.substring(0, firstComplete);
    }

    /**
     * Strict validity: the payload must be one complete JSON document with no trailing content.
     * The fusion mapper (and many others) tolerate trailing garbage after the first value
     * (MapJsonCodec stops at the closing brace without asserting EOF), so a plain
     * {@code mapper.read} would accept {@code {"a":1}[1,2,3]} - exactly the payload llama.cpp /
     * ollama reject with {@code parse_error.101}. This structural check requires the single
     * complete value to end the input (modulo whitespace) and supplements the parse.
     */
    private static boolean isValidJson(final JsonMapper mapper, final String value) {
        try (final var reader = new StringReader(value)) {
            mapper.read(Map.class, reader);
        } catch (final RuntimeException e) {
            return false;
        }
        final var trim = value.strip();
        return trim.isEmpty() || isSingleCompleteDocument(trim);
    }

    /**
     * Whether the string is a single complete JSON document: the structural scanner reaches
     * depth 0 at a single value exactly at the end (whitespace allowed). Mirrors
     * {@link #trimTailGarbage}'s scan so both agree on what "complete" means.
     */
    private static boolean isSingleCompleteDocument(final String json) {
        var inString = false;
        var depth = 0;
        var closedAt = -1; // index of the char that closed the first complete value
        for (var i = 0; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // escaped char: skip both
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (closedAt >= 0) {
                // after the first value closed at depth 0, only whitespace may follow;
                // any non-whitespace is a second value (or trailing garbage)
                if (!Character.isWhitespace(c)) {
                    return false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth == 0) {
                        closedAt = i;
                    }
                }
                default -> {
                    // structural JSON whitespace is ignored; anything else inside a value
                    // (letters/digits of a literal) keeps the current depth open
                }
            }
        }
        return closedAt >= 0 && depth == 0;
    }

    /** Whether the payload contains a raw control character (0x00-0x1F) inside a string value. */
    static boolean hasBareControlChar(final String json) {
        var inString = false;
        for (int i = 0; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (inString && c == '\\') {
                i++; // skip the escaped char (a raw control char after a backslash is an escape)
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString && c < 0x20) {
                return true;
            }
        }
        return false;
    }

    /**
     * Escape raw control characters (0x00-0x1F) found inside JSON string values. JSON whitespace
     * outside strings ({@code \t \n \r}) is preserved, so a structurally valid payload with pretty
     * printing stays valid. Already-escaped sequences (backslash + any char) are never touched.
     */
    static String escapeControlChars(final String json) {
        final var sb = new StringBuilder(json.length() + 16);
        walk(json, sb);
        return sb.toString();
    }

    private static void walk(final String json, final StringBuilder out) {
        var inString = false;
        for (int i = 0; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (inString && c == '\\') {
                // keep the escape and its escaped character as-is (never double-escape)
                out.append(c);
                if (i + 1 < json.length()) {
                    out.append(json.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                continue;
            }
            if (inString && c < 0x20) {
                appendEscape(out, c);
                continue;
            }
            out.append(c);
        }
    }

    private static void appendEscape(final StringBuilder sb, final char c) {
        switch (c) {
            case '\b' -> sb.append("\\b");
            case '\t' -> sb.append("\\t");
            case '\n' -> sb.append("\\n");
            case '\f' -> sb.append("\\f");
            case '\r' -> sb.append("\\r");
            default -> appendUnicodeEscape(sb, c);
        }
    }

    private static void appendUnicodeEscape(final StringBuilder sb, final char c) {
        sb.append("\\u");
        sb.append(HEX[(c >> 12) & 0xF]);
        sb.append(HEX[(c >> 8) & 0xF]);
        sb.append(HEX[(c >> 4) & 0xF]);
        sb.append(HEX[c & 0xF]);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String preview(final String value) {
        final var v = value.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return v.length() <= 80 ? v : v.substring(0, 77) + "...";
    }

    private static final Logger LOGGER = Logger.getLogger(ToolCallArgumentsRepairer.class.getName());
}
