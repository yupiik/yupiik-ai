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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tolerant single-pass scanner for the in-band tool-call markup that models leak through the
 * text channel even when structured tool calls are declared. It replaces the fixed-spelling
 * regex pair with a cursor-based reader so the exact marker spelling (fullwidth or ASCII
 * vertical bar, with or without the literal {@code DSML} tag, slash-first or slash-last
 * closers) no longer decides whether an argument survives.
 *
 * <p>Recognised families, each mapped to the same {@link ToolCall} shape so the execution
 * path is identical to native calls:</p>
 *
 * <ul>
 *   <li><b>DeepSeek DSML</b> ({@code <｜DSML｜tool_calls>} or {@code <｜tool_calls>} and their
 *       ASCII versions): one or more {@code <invoke name="fn">} blocks each holding
 *       {@code <parameter name="k" string="true|false">v</parameter>} arguments. The
 *       {@code string} flag is optional - when absent or not {@code true} the value is parsed
 *       as JSON and falls back to the literal text. A parameter ends at its closer, at the
 *       next parameter opener, at the invoke/block closer or at the end of input, so a mangled
 *       or truncated closer never silently drops the argument (the root cause of
 *       {@code path is required}).</li>
 *   <li><b>DeepSeek V3.1 / legacy</b> ({@code <｜tool＿calls＿begin｜> ... ＿end｜>}): the same
 *       invoke/parameter grammar inside the legacy wrapper.</li>
 *   <li><b>Qwen / Hermes</b> ({@code <tool_call>{"name": ..., "arguments": {...}}</tool_call>}):
 *       the arguments object is preserved verbatim, empty when absent.</li>
 *   <li><b>GLM kv</b> ({@code <tool_call>name<arg_key>k</arg_key><arg_value>v</arg_value></tool_call>}):
 *       every value is marshalled as a JSON string.</li>
 *   <li><b>Mistral</b> ({@code [TOOL_CALLS]} followed by a JSON array of
 *       {@code {"name": ..., "arguments": {...}}} objects).</li>
 * </ul>
 *
 * <p>The scanner is stateless and pure over its input, so a single instance is shared safely.
 * It drives both consumers of the same stream: {@link #parse} (extract calls) and
 * {@link #strip} (remove the markup, keeping surrounding prose).</p>
 */
@ApplicationScoped
public class MarkupToolCallScanner {
    private static final Pattern EXCESS_NEWLINES = Pattern.compile("\n{3,}");

    /** Fullwidth vertical bar used by DeepSeek markers. */
    private static final char FULLWIDTH_PIPE = '\uFF5C';
    /** Lower one eighth block used by the V3.1 markers. */
    private static final char LOW_LINE = '\u2581';

    private static final String TOOL_CALLS = "tool_calls";
    private static final String INVOKE = "invoke";
    private static final String PARAMETER = "parameter";

    /**
     * Parses the in-band tool calls from the raw model content, empty when none are present.
     *
     * @param raw the assistant content as received, may be {@code null}.
     * @return the extracted tool calls, empty when no markup is present.
     */
    public List<ToolCall> parse(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        final var out = new ArrayList<ToolCall>();
        var i = 0;
        final var len = raw.length();
        while (i < len) {
            int c = raw.charAt(i);
            if (c == '<') {
                final var dsml = dsmlTag(raw, i);
                if (dsml != null) {
                    final var res = parseDsmlBlock(raw, dsml, out);
                    i = res;
                } else if (startsWith(raw, i, "<tool_call>")) {
                    final var res = parseJsonOrGlmTag(raw, i, out);
                    i = res;
                } else if (isLegacyBlockOpen(raw, i)) {
                    i = parseLegacyBlock(raw, i, out);
                } else {
                    i++;
                }
            } else if (c == '[' && startsWith(raw, i, "[TOOL_CALLS]")) {
                i = parseMistralArray(raw, i, out);
            } else {
                i++;
            }
        }
        return out;
    }

    /**
     * Whether the raw text carries any of the in-band tool-call markers this scanner knows.
     * Structure-aware and cheap: probes the literal marker families, not arbitrary prose.
     */
    public boolean containsMarkup(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        if (raw.indexOf("[TOOL_CALLS]") >= 0 || raw.indexOf("<tool_call>") >= 0) {
            return true;
        }
        if (raw.indexOf("tool" + LOW_LINE + "calls" + LOW_LINE + "begin") >= 0) {
            return true;
        }
        for (var i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '<' && dsmlTag(raw, i) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes every tool-call block from the raw model output, preserving surrounding prose.
     *
     * @param raw the model message content as received, may be {@code null}.
     * @return the text without any tool-call markup.
     */
    public String strip(final String raw) {
        if (raw == null || raw.isEmpty() || !containsMarkup(raw)) {
            return raw == null ? "" : raw;
        }
        final var sb = new StringBuilder(raw.length());
        var i = 0;
        final var len = raw.length();
        while (i < len) {
            final char c = raw.charAt(i);
            if (c == '<') {
                final var dsml = dsmlTag(raw, i);
                if (dsml != null) {
                    final var res = skipDsmlBlock(raw, dsml);
                    i = res == i ? i + 1 : res;
                    continue;
                }
                if (startsWith(raw, i, "<tool_call>")) {
                    final var close = raw.indexOf("</tool_call>", i);
                    i = close < 0 ? len : close + "</tool_call>".length();
                    continue;
                }
                if (isLegacyBlockOpen(raw, i)) {
                    i = skipLegacyBlock(raw, i);
                    continue;
                }
                if (isOrphanDsmlClose(raw, i)) {
                    i = skipOrphanDsmlClose(raw, i);
                    continue;
                }
                sb.append(c);
                i++;
            } else if (c == '[' && startsWith(raw, i, "[TOOL_CALLS]")) {
                final var close = findMistralArrayEnd(raw, i);
                i = close < 0 ? len : close;
            } else {
                sb.append(c);
                i++;
            }
        }
        // collapse 3+ blank lines, mirroring the previous stripper
        final var out = EXCESS_NEWLINES.matcher(sb.toString()).replaceAll("\n\n");
        return out.strip();
    }

    /**
     * Describes one DSML marker found at {@code i}: the keyword it opens/closes and how many
     * chars the tag header spans. Returns {@code null} when the position is not a DSML marker.
     */
    private static DsmlMarker dsmlTag(final String raw, final int i) {
        if (i + 1 >= raw.length() || raw.charAt(i) != '<') {
            return null;
        }
        var p = i + 1;
        // optional closing slash (slash-first closer: </.....>)
        boolean closed = false;
        if (p < raw.length() && raw.charAt(p) == '/') {
            closed = true;
            p++;
        }
        if (p < raw.length() && isPipe(raw.charAt(p))) {
            p++;
        } else {
            return null;
        }
        // optional literal "DSML" + pipe (DSML-branded form vs vLLM <pipe>tool_calls> form)
        if (regionEquals(raw, p, "DSML")) {
            p += 4;
            if (p < raw.length() && isPipe(raw.charAt(p))) {
                p++;
            } else {
                return null;
            }
        }
        // optional slash-last closer (closing tag <pipe>/keyword> vs </pipe>keyword>)
        if (p < raw.length() && raw.charAt(p) == '/') {
            closed = true;
            p++;
        }
        for (final var keyword : new String[] {TOOL_CALLS, INVOKE, PARAMETER}) {
            if (regionEquals(raw, p, keyword)) {
                var end = p + keyword.length();
                // only a real tag boundary counts: end of input or a non-word char
                if (end >= raw.length() || !isWord(raw.charAt(end))) {
                    return new DsmlMarker(keyword, closed, end);
                }
            }
        }
        return null;
    }

    private static boolean isPipe(final char c) {
        return c == '|' || c == FULLWIDTH_PIPE;
    }

    private static boolean isWord(final char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-';
    }

    private static boolean startsWith(final String raw, final int i, final String literal) {
        return i + literal.length() <= raw.length() && raw.regionMatches(i, literal, 0, literal.length());
    }

    private static boolean regionEquals(final String raw, final int off, final String literal) {
        return off + literal.length() <= raw.length() && raw.regionMatches(off, literal, 0, literal.length());
    }

    /** Reads the DSML block starting at the {@code tool_calls} opener, appends calls. */
    private static int parseDsmlBlock(final String raw, final DsmlMarker opener, final List<ToolCall> out) {
        final var len = raw.length();
        var gt = raw.indexOf('>', opener.pos);
        if (gt < 0) {
            return len; // never closed header: rest is consumed
        }
        var p = gt + 1;
        final var blockEnd = len;
        String currentName = null;
        final var currentArgs = new ArrayList<String[]>();
        var id = out.size() + 1;
        while (p < blockEnd) {
            final var m = dsmlTag(raw, p);
            if (m == null) {
                p++;
                continue;
            }
            if (TOOL_CALLS.equals(m.keyword())) {
                if (m.closed()) {
                    return p + markerLength(raw, p); // block closed
                }
                p += markerLength(raw, p); // nested opener ignore
            } else if (INVOKE.equals(m.keyword())) {
                if (m.closed()) {
                    // close current invoke
                    if (currentName != null) {
                        out.add(new ToolCall(
                                "btw-" + id++,
                                "function",
                                new ToolCall.FunctionCall(currentName, marshalArgs(currentArgs)),
                                null));
                        currentName = null;
                        currentArgs.clear();
                    }
                    p += markerLength(raw, p);
                } else {
                    final var name = readElementAttribute(raw, p, "name");
                    // an unclosed previous invoke is flushed before starting a new one
                    if (currentName != null) {
                        out.add(new ToolCall(
                                "btw-" + id++,
                                "function",
                                new ToolCall.FunctionCall(currentName, marshalArgs(currentArgs)),
                                null));
                        currentArgs.clear();
                    }
                    currentName = name == null || name.isBlank() ? legacyName(raw, p) : name;
                    p = skipToTagEnd(raw, p);
                }
            } else if (PARAMETER.equals(m.keyword()) && !m.closed()) {
                final var name = readElementAttribute(raw, p, "name");
                final var stringFlag = readElementAttribute(raw, p, "string");
                final var valueStart = skipToTagEnd(raw, p);
                final var valueEnd = findParameterEnd(raw, valueStart);
                if (name != null && !name.isBlank()) {
                    currentArgs.add(new String[] {
                        name, stringFlag, raw.substring(valueStart, valueEnd).strip()
                    });
                }
                p = valueEnd;
            } else {
                p += markerLength(raw, p);
            }
        }
        // flush a trailing unclosed invoke
        if (currentName != null) {
            out.add(new ToolCall(
                    "btw-" + id, "function", new ToolCall.FunctionCall(currentName, marshalArgs(currentArgs)), null));
        }
        return blockEnd;
    }

    private static int skipDsmlBlock(final String raw, final DsmlMarker opener) {
        final var gt = raw.indexOf('>', opener.pos);
        if (gt < 0) {
            return raw.length();
        }
        final var close = indexOfDsmlMarker(raw, gt + 1, TOOL_CALLS, true);
        if (close >= 0) {
            return close;
        }
        if (TOOL_CALLS.equals(opener.keyword()) && !opener.closed()) {
            // a tool_calls block may close with <|DSML|end_tools> (DeepSeek V3)
            final var endTools = indexOfEndTools(raw, gt + 1);
            if (endTools >= 0) {
                final var etClose = raw.indexOf('>', endTools);
                return etClose < 0 ? raw.length() : etClose + 1;
            }
        }
        if (!opener.closed()) {
            // a bare invoke (no outer wrapper) closes with its own invoke closer, an end_tools
            // marker, or EOF
            final var invokeClose = indexOfDsmlMarker(raw, gt + 1, INVOKE, true);
            if (invokeClose >= 0) {
                return invokeClose;
            }
            final var endTools = indexOfEndTools(raw, gt + 1);
            if (endTools < 0) {
                return raw.length();
            }
            final var etClose = raw.indexOf('>', endTools);
            return etClose < 0 ? raw.length() : etClose + 1;
        }
        // an orphan closer: skip just the tag
        return gt + 1;
    }

    private static int indexOfDsmlMarker(final String raw, final int from, final String keyword, final boolean closed) {
        for (var i = from; i < raw.length(); i++) {
            final var m = dsmlTag(raw, i);
            if (m != null && m.closed() == closed && keyword.equals(m.keyword())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfEndTools(final String raw, final int from) {
        for (var i = from; i < raw.length(); i++) {
            if (raw.charAt(i) == '<' && isEndTools(raw, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEndTools(final String raw, final int i) {
        // <|DSML|end_tools> or <｜DSML｜end_tools>
        var p = i + 1;
        if (p < raw.length() && isPipe(raw.charAt(p))) {
            p++;
        } else {
            return false;
        }
        if (regionEquals(raw, p, "DSML")) {
            p += 4;
            if (p < raw.length() && isPipe(raw.charAt(p))) {
                p++;
            } else {
                return false;
            }
        }
        return regionEquals(raw, p, "end_tools");
    }

    /** V3.1 legacy markers: {@code <｜tool＿calls＿begin｜>} through {@code ＿calls＿end｜>}. */
    private static boolean isLegacyBlockOpen(final String raw, final int i) {
        return startsWith(raw, i, "<\uFF5Ctool" + LOW_LINE + "calls" + LOW_LINE + "begin\uFF5C>")
                || startsWith(raw, i, "<|tool_calls|>");
    }

    private static int skipLegacyBlock(final String raw, final int i) {
        final var close = raw.indexOf("tool" + LOW_LINE + "calls" + LOW_LINE + "end\uFF5C>", i);
        return close < 0 ? raw.length() : close + ("tool" + LOW_LINE + "calls" + LOW_LINE + "end\uFF5C>").length();
    }

    /**
     * DeepSeek V3.1 legacy wrapper: {@code <｜tool＿call＿begin｜>name=fn<｜parameter …>…<｜tool＿call＿end｜>}
     * inside a plural {@code tool＿calls＿begin} ... {@code _calls＿end} span. The call name rides
     * on a leading {@code name=…} line, parameters use the same DSML tags.
     */
    private static int parseLegacyBlock(final String raw, final int i, final List<ToolCall> out) {
        final var blockEnd = skipLegacyBlock(raw, i);
        final var begin = raw.indexOf("tool" + LOW_LINE + "call" + LOW_LINE + "begin", i);
        final var endMarker = "tool" + LOW_LINE + "call" + LOW_LINE + "end\uFF5C>";
        final var callEnd = raw.indexOf(endMarker, i);
        final var bodyEnd = callEnd < 0 ? blockEnd : callEnd;
        if (begin < 0 || begin >= bodyEnd) {
            return blockEnd;
        }
        final var callBeginClose = raw.indexOf('>', begin);
        if (callBeginClose < 0 || callBeginClose >= bodyEnd) {
            return blockEnd;
        }
        // name rides on a leading name=... line inside the call body (before any parameter tag)
        final var callBody = raw.substring(callBeginClose + 1, bodyEnd);
        final var name = legacyBodyName(callBody);
        final var args = new ArrayList<String[]>();
        for (var p = callBeginClose + 1; p < bodyEnd; ) {
            final var m = dsmlTag(raw, p);
            if (m == null) {
                p++;
                continue;
            }
            if (PARAMETER.equals(m.keyword()) && !m.closed()) {
                final var pName = readElementAttribute(raw, p, "name");
                final var flag = readElementAttribute(raw, p, "string");
                final var vs = skipToTagEnd(raw, p);
                final var ve = findParameterEnd(raw, vs);
                if (pName != null && !pName.isBlank()) {
                    args.add(new String[] {pName, flag, raw.substring(vs, ve).strip()});
                }
                p = ve;
            } else {
                p += markerLength(raw, p);
            }
        }
        if (name != null && !name.isBlank()) {
            out.add(new ToolCall("btw-1", "function", new ToolCall.FunctionCall(name, marshalArgs(args)), null));
        }
        return blockEnd;
    }

    /** A bare {@code </｜…>} tool_calls/invoke closer with no opener in sight. */
    private static boolean isOrphanDsmlClose(final String raw, final int i) {
        final var m = dsmlTag(raw, i);
        if (m == null || !m.closed()) {
            return false;
        }
        return TOOL_CALLS.equals(m.keyword()) || INVOKE.equals(m.keyword());
    }

    private static int skipOrphanDsmlClose(final String raw, final int i) {
        final var gt = raw.indexOf('>', i);
        return gt < 0 ? raw.length() : gt + 1;
    }

    /**
     * A parameter body ends at the first of: its closer, the next parameter opener, an invoke
     * close, the tool_calls close, or end of input. Never silently drops the argument.
     */
    private static int findParameterEnd(final String raw, final int start) {
        for (var i = start; i < raw.length(); i++) {
            final var m = dsmlTag(raw, i);
            if (m == null) {
                continue;
            }
            if (PARAMETER.equals(m.keyword())) {
                // a parameter closer ends the value; a subsequent parameter opener also ends it
                // (locale-terminated, matching vLLM - a mangled closer must never swallow the
                // following parameter)
                if (m.closed() || i > start) {
                    return i;
                }
            }
            if (INVOKE.equals(m.keyword()) && m.closed()) {
                return i;
            }
            if (TOOL_CALLS.equals(m.keyword()) && m.closed()) {
                return i;
            }
        }
        return raw.length();
    }

    private static int skipToTagEnd(final String raw, final int from) {
        final var gt = raw.indexOf('>', from);
        return gt < 0 ? raw.length() : gt + 1;
    }

    private static int markerLength(final String raw, final int i) {
        final var gt = raw.indexOf('>', i);
        return gt < 0 ? raw.length() - i : gt - i + 1;
    }

    /** Reads a single {@code key} attribute from the open tag starting at {@code i}. */
    private static String readElementAttribute(final String raw, final int i, final String key) {
        final var gt = raw.indexOf('>', i);
        if (gt < 0) {
            return null;
        }
        final var header = raw.substring(i, gt);
        // skip the leading '<' and the tag name token ("invoke", "parameter", ...)
        var p = 0;
        if (p < header.length() && header.charAt(p) == '<') {
            p++;
        }
        while (p < header.length() && !Character.isWhitespace(header.charAt(p)) && header.charAt(p) != '>') {
            p++;
        }
        while (p < header.length()) {
            // skip whitespace
            while (p < header.length() && Character.isWhitespace(header.charAt(p))) {
                p++;
            }
            if (p >= header.length() || header.charAt(p) == '>') {
                break;
            }
            final var eq = header.indexOf('=', p);
            if (eq < 0) {
                break;
            }
            final var attr = header.substring(p, eq).strip();
            var vp = eq + 1;
            // skip spaces before value
            while (vp < header.length() && Character.isWhitespace(header.charAt(vp))) {
                vp++;
            }
            final String value;
            if (vp < header.length() && header.charAt(vp) == '"') {
                final var close = header.indexOf('"', vp + 1);
                value = close < 0 ? header.substring(vp + 1) : header.substring(vp + 1, close);
                p = close < 0 ? header.length() : close + 1;
            } else {
                var e = vp;
                while (e < header.length() && !Character.isWhitespace(header.charAt(e)) && header.charAt(e) != '>') {
                    e++;
                }
                value = header.substring(vp, e);
                p = e;
            }
            if (key.equals(attr)) {
                return value;
            }
        }
        return null;
    }

    /** V3.1 style carried the tool name as a leading {@code name=...} line inside the invoke body. */
    private static String legacyName(final String raw, final int invokeStart) {
        final var gt = raw.indexOf('>', invokeStart);
        if (gt < 0) {
            return null;
        }
        final int end;
        // look for a 'name=' before the next newline or DSML marker
        final var nl = raw.indexOf('\n', gt + 1);
        final var nextClose = indexOfNextMarker(raw, gt + 1);
        end = nl < 0
                ? (nextClose < 0 ? raw.length() : nextClose)
                : Math.min(nl, nextClose < 0 ? Integer.MAX_VALUE : nextClose);
        final var body = raw.substring(gt + 1, end);
        final var idx = body.indexOf("name=");
        return idx < 0 ? null : body.substring(idx + 5).strip();
    }

    private static int indexOfNextMarker(final String raw, final int from) {
        for (var i = from; i < raw.length(); i++) {
            if (dsmlTag(raw, i) != null) {
                return i;
            }
        }
        return -1;
    }

    /** Extracts the tool name from a V3.1 {@code name=fn} leading line (before any parameter tag). */
    private static String legacyBodyName(final String body) {
        final var first = body.indexOf('<');
        final var head = first < 0 ? body : body.substring(0, first);
        final var idx = head.indexOf("name=");
        if (idx < 0) {
            return null;
        }
        return head.substring(idx + 5).strip();
    }

    /** Qwen/Hermes JSON-in-tag or GLM kv inside a {@code <tool_call>...</tool_call>}. */
    private static int parseJsonOrGlmTag(final String raw, final int i, final List<ToolCall> out) {
        final var bodyStart = i + "<tool_call>".length();
        final var close = raw.indexOf("</tool_call>", bodyStart);
        final var bodyEnd = close < 0 ? raw.length() : close;
        final var inner = raw.substring(bodyStart, bodyEnd).strip();
        if (inner.contains("<arg_key>") && inner.contains("<arg_value>")) {
            parseGlmKv(inner, out);
        } else {
            parseJsonTag(inner, out);
        }
        return close < 0 ? raw.length() : close + "</tool_call>".length();
    }

    private static void parseJsonTag(final String inner, final List<ToolCall> out) {
        if (inner.isEmpty()) {
            return;
        }
        final var nameM = findJsonKeyValue(inner, "name");
        if (nameM == null || nameM.isBlank()) {
            return;
        }
        final var name = unquote(nameM);
        final var argsStart = indexOfKey(inner, "arguments");
        String args = "{}";
        if (argsStart >= 0) {
            final var colon = inner.indexOf(':', argsStart);
            if (colon >= 0) {
                final var open = inner.indexOf('{', colon);
                if (open >= 0) {
                    final var closeBrace = closingBrace(inner, open);
                    if (closeBrace > open) {
                        args = inner.substring(open, closeBrace + 1);
                    }
                }
            }
        }
        out.add(new ToolCall("btw-1", "function", new ToolCall.FunctionCall(name, args), null));
    }

    private static void parseGlmKv(final String inner, final List<ToolCall> out) {
        final var args = new LinkedHashMap<String, String>();
        var name = inner;
        var p = 0;
        while (p < inner.length()) {
            final var ki = inner.indexOf("<arg_key>", p);
            if (ki < 0) {
                break;
            }
            final var ke = inner.indexOf("</arg_key>", ki + "<arg_key>".length());
            final var vi = inner.indexOf("<arg_value>", ke < 0 ? ki : ke + "</arg_key>".length());
            final var veEnd = vi < 0 ? -1 : inner.indexOf("</arg_value>", vi + "<arg_value>".length());
            if (ke >= 0 && vi >= 0 && veEnd >= 0) {
                final var k = inner.substring(ki + "<arg_key>".length(), ke).strip();
                final var v =
                        inner.substring(vi + "<arg_value>".length(), veEnd).strip();
                args.put(k, v);
                p = veEnd + "</arg_value>".length();
            } else {
                break;
            }
        }
        if (args.isEmpty()) {
            return;
        }
        // the tool name precedes the first <arg_key>
        final var firstKey = inner.indexOf("<arg_key>");
        final var n = (firstKey <= 0 ? inner : inner.substring(0, firstKey)).strip();
        if (n.isEmpty()) {
            return;
        }
        out.add(new ToolCall("btw-1", "function", new ToolCall.FunctionCall(n, marshalStringMap(args)), null));
    }

    /** Mistral {@code [TOOL_CALLS] } followed by a JSON array of {name, arguments} objects. */
    private static int parseMistralArray(final String raw, final int i, final List<ToolCall> out) {
        final var markerEnd = i + "[TOOL_CALLS]".length();
        final var openBracket = raw.indexOf('[', markerEnd);
        if (openBracket < 0) {
            return raw.length();
        }
        final var end = closingBracket(raw, openBracket);
        final var arrEnd = end < 0 ? raw.length() : end + 1;
        final var arr = raw.substring(openBracket, arrEnd);
        var idx = 0;
        var id = 0;
        while (idx < arr.length()) {
            final var ob = arr.indexOf('{', idx);
            if (ob < 0) {
                break;
            }
            final var cb = closingBrace(arr, ob);
            if (cb < 0) {
                break;
            }
            final var obj = arr.substring(ob, cb + 1);
            final var name = unquote(findJsonKeyValue(obj, "name"));
            final var argsStart = indexOfKey(obj, "arguments");
            String args = "{}";
            if (argsStart >= 0) {
                final var colon = obj.indexOf(':', argsStart);
                if (colon >= 0) {
                    final var open = obj.indexOf('{', colon);
                    if (open >= 0) {
                        final var closeBrace = closingBrace(obj, open);
                        if (closeBrace > open) {
                            args = obj.substring(open, closeBrace + 1);
                        }
                    }
                }
            }
            if (name != null && !name.isBlank()) {
                out.add(new ToolCall("btw-" + (++id), "function", new ToolCall.FunctionCall(name, args), null));
            }
            idx = cb + 1;
        }
        return arrEnd;
    }

    private static int findMistralArrayEnd(final String raw, final int i) {
        final var markerEnd = i + "[TOOL_CALLS]".length();
        final var open = raw.indexOf('[', markerEnd);
        if (open < 0) {
            return raw.length();
        }
        final var end = closingBracket(raw, open);
        return end < 0 ? raw.length() : end + 1;
    }

    private static int closingBracket(final String s, final int open) {
        var depth = 0;
        var inString = false;
        for (var i = open; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int closingBrace(final String s, final int open) {
        var depth = 0;
        var inString = false;
        for (var i = open; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Finds a JSON {@code "key":} and returns the position just after the key (the value start). */
    private static int indexOfKey(final String json, final String key) {
        final var pattern = "\"" + key + "\"";
        final var i = json.indexOf(pattern);
        return i < 0 ? -1 : i + pattern.length();
    }

    /** Returns the unquoted value of a top-level JSON {@code "key": value} pair, or the value token. */
    private static String findJsonKeyValue(final String json, final String key) {
        final var at = indexOfKey(json, key);
        if (at < 0) {
            return null;
        }
        final var colon = json.indexOf(':', at);
        if (colon < 0) {
            return null;
        }
        var v = colon + 1;
        while (v < json.length() && Character.isWhitespace(json.charAt(v))) {
            v++;
        }
        if (v < json.length() && json.charAt(v) == '"') {
            final var sb = new StringBuilder();
            var i = v + 1;
            while (i < json.length() && json.charAt(i) != '"') {
                final char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(i + 1));
                    i += 2;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return '"' + sb.toString() + '"';
        }
        // non-string value: capture the token
        final var sb = new StringBuilder();
        final var e = json.length();
        while (v < e) {
            final char c = json.charAt(v);
            if (c == ',' || c == '}') {
                break;
            }
            sb.append(c);
            v++;
        }
        return sb.toString().strip();
    }

    private static String unquote(final String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            return raw;
        }
        return raw.substring(1, raw.length() - 1);
    }

    /** Marshals DSML parameters into a JSON object string preserving order. */
    private static String marshalArgs(final List<String[]> args) {
        final var sb = new StringBuilder("{");
        var first = true;
        for (final var a : args) {
            final var name = a[0];
            final var flag = a[1];
            final var rawValue = a[2];
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(name)).append("\":");
            if (flag == null || !flag.trim().equalsIgnoreCase("true")) {
                final var json = tryJson(rawValue);
                if (json != null) {
                    sb.append(json);
                } else {
                    sb.append('"').append(escape(rawValue)).append('"');
                }
            } else {
                sb.append('"').append(escape(rawValue)).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String marshalStringMap(final Map<String, String> args) {
        final var sb = new StringBuilder("{");
        var first = true;
        for (final var e : args.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"')
                    .append(escape(e.getKey()))
                    .append("\":\"")
                    .append(escape(e.getValue()))
                    .append('"');
        }
        return sb.append('}').toString();
    }

    private static String tryJson(final String raw) {
        final var t = raw.strip();
        if (t.isEmpty()) {
            return "null";
        }
        if ("true".equals(t) || "false".equals(t) || "null".equals(t)) {
            return t;
        }
        final char d = t.charAt(0);
        if (d == '[' || d == '{') {
            // keep as raw JSON (structural chars already balanced by scanner tolerance)
            return t;
        }
        // numeric?
        try {
            if (t.contains(".")) {
                Double.parseDouble(t);
            } else {
                Long.parseLong(t);
            }
            return t;
        } catch (final NumberFormatException e) {
            // not JSON: keep as a quoted string
            return null;
        }
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record DsmlMarker(String keyword, boolean closed, int pos) {}
}
