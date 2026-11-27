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
package io.yupiik.htmltomarkdown;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tiny lenient HTML parser producing plain text or a light markdown representation.
 * No dependency, tolerant of malformed markup (char-by-char scanning). Handles common
 * structural elements (headings, paragraphs, lists, links, images, pre/code, blockquote,
 * table) and skips script/style/template/svg content, decoding basic entities.
 */
public class HtmlLightParser {
    private static final Pattern TRAILING_LINE_WHITESPACE = Pattern.compile("(?m)[ \t]+\n");
    private static final Pattern EXCESS_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern TRAILING_BLANK_LINE = Pattern.compile("(?m)\\s+\n$");

    private static final Set<String> SKIP_CONTENT =
            Set.of("script", "style", "noscript", "template", "title", "head", "textarea", "svg", "iframe", "noframes");
    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track",
            "wbr");
    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"),
            Map.entry("lt", "<"),
            Map.entry("gt", ">"),
            Map.entry("quot", "\""),
            Map.entry("apos", "'"),
            Map.entry("nbsp", " "),
            Map.entry("copy", "(c)"),
            Map.entry("reg", "(r)"),
            Map.entry("trade", "(tm)"),
            Map.entry("hellip", "..."),
            Map.entry("mdash", "-"),
            Map.entry("ndash", "-"));

    public String toMarkdown(final String html) {
        return parse(html, true);
    }

    public String toText(final String html) {
        return parse(html, false);
    }

    private String parse(final String html, final boolean markdown) {
        final var source = html == null ? "" : html;
        final var sb = new StringBuilder();
        final var links = new ArrayDeque<String>();
        var inOl = false;
        var olCount = 0;
        var i = 0;
        final int n = source.length();
        while (i < n) {
            final char c = source.charAt(i);
            if (c == '<') {
                final int tagEnd = tagEnd(source, i);
                if (tagEnd < 0) {
                    appendText(sb, source.substring(i), links);
                    break;
                }
                final var lt = i;
                final var rawTag = source.substring(i + 1, tagEnd);
                i = tagEnd + 1;
                if (rawTag.startsWith("!--")) {
                    // a comment ends at "-->", not at its first '>' (which may already be the
                    // comment's own closing '>' when it contains no internal tag). Search from
                    // the comment start so we never overshoot into later content.
                    final int close = source.indexOf("-->", lt + 3);
                    i = close < 0 ? n : close + 3;
                    continue;
                }
                final var tag = rawTag.strip();
                if (tag.startsWith("/")) {
                    final var name = tagName(tag.substring(1));
                    if (name.equals("a") && !links.isEmpty()) {
                        closeLink(sb, links.pop(), markdown);
                    } else if (name.equals("ol") || name.equals("ul")) {
                        inOl = false;
                    } else if (name.equals("q") && markdown) {
                        sb.append('"');
                        ensureSpace(sb);
                    }
                    continue;
                }
                final var selfClose = tag.endsWith("/");
                final var head = (selfClose ? tag.substring(0, tag.length() - 1) : tag).strip();
                final int nameEnd = head.indexOf(' ');
                final var name = nameEnd < 0 ? tagName(head) : tagName(head.substring(0, nameEnd));
                final var attrs = nameEnd < 0 ? "" : head.substring(nameEnd + 1);
                if (name.isEmpty()) {
                    continue;
                }
                if (SKIP_CONTENT.contains(name)) {
                    if (name.equals("head") || name.equals("title")) {
                        i = firstOf(source, i, "</" + name, "<body");
                    } else {
                        i = findClosingTag(source, i, name);
                    }
                    continue;
                }
                if (name.equals("ol")) {
                    inOl = true;
                    olCount = 0;
                    openTag(sb, name, attrs, links, markdown);
                    continue;
                }
                if (name.equals("ul")) {
                    inOl = false;
                    openTag(sb, name, attrs, links, markdown);
                    continue;
                }
                if (name.equals("li")) {
                    blockBreak(sb);
                    if (markdown) {
                        if (inOl) {
                            sb.append(++olCount).append(". ");
                        } else {
                            sb.append("- ");
                        }
                    }
                    continue;
                }
                if (selfClose || VOID_ELEMENTS.contains(name)) {
                    handleVoid(sb, name, attrs, links, markdown);
                    continue;
                }
                openTag(sb, name, attrs, links, markdown);
            } else {
                final int next = source.indexOf('<', i);
                final int end = next < 0 ? n : next;
                appendText(sb, source.substring(i, end), links);
                i = end == i ? i + 1 : end;
            }
        }
        while (!links.isEmpty()) {
            closeLink(sb, links.pop(), markdown);
        }
        return normalize(sb);
    }

    private void openTag(
            final StringBuilder sb,
            final String lower,
            final String attrs,
            final Deque<String> links,
            final boolean markdown) {
        switch (lower) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                blockBreak(sb);
                if (markdown) {
                    sb.append("#".repeat(lower.charAt(1) - '0')).append(' ');
                }
            }
            case "p",
                    "div",
                    "section",
                    "article",
                    "header",
                    "footer",
                    "main",
                    "figure",
                    "details",
                    "table",
                    "ul",
                    "ol",
                    "dl",
                    "form",
                    "aside",
                    "nav",
                    "address",
                    "fieldset",
                    "center",
                    "caption",
                    "figcaption",
                    "dt",
                    "dd",
                    "summary",
                    "legend" -> blockBreak(sb);
            case "option", "optgroup" -> blockBreak(sb);
            case "br" -> lineBreak(sb);
            case "hr" -> {
                blockBreak(sb);
                if (markdown) {
                    sb.append("---");
                }
                blockBreak(sb);
            }
            case "tr" -> blockBreak(sb);
            case "td", "th" -> sb.append(' ');
            case "blockquote" -> {
                blockBreak(sb);
                if (markdown) {
                    sb.append("> ");
                }
            }
            case "pre" -> {
                blockBreak(sb);
                if (markdown) {
                    sb.append("```");
                }
                sb.append('\n');
            }
            case "a" -> {
                final var href = attr(attrs, "href");
                links.push(href == null ? "" : href.strip());
            }
            case "q" -> {
                if (markdown) {
                    ensureSpace(sb);
                    sb.append('"');
                }
            }
            case "img" -> {
                final var alt = attr(attrs, "alt");
                if (alt != null && !alt.isBlank()) {
                    ensureSpace(sb);
                    if (markdown) {
                        sb.append("[img:").append(alt).append(']');
                    } else {
                        sb.append(alt);
                    }
                    ensureSpace(sb);
                }
            }
            default -> {
                // ignore unknown elements
            }
        }
    }

    private void handleVoid(
            final StringBuilder sb,
            final String lower,
            final String attrs,
            final Deque<String> links,
            final boolean markdown) {
        if (lower.equals("img")) {
            final var alt = attr(attrs, "alt");
            if (alt != null && !alt.isBlank()) {
                ensureSpace(sb);
                if (markdown) {
                    sb.append("[img:").append(alt).append(']');
                } else {
                    sb.append(alt);
                }
                ensureSpace(sb);
            }
        } else if (lower.equals("br")) {
            lineBreak(sb);
        } else if (lower.equals("hr")) {
            blockBreak(sb);
            if (markdown) {
                sb.append("---");
            }
            blockBreak(sb);
        }
    }

    private void closeLink(final StringBuilder sb, final String href, final boolean markdown) {
        if (!markdown || href == null || href.isBlank()) {
            return;
        }
        ensureSpace(sb);
        sb.append('(').append(href).append(')');
        ensureSpace(sb);
    }

    private void appendText(final StringBuilder sb, final String raw, final Deque<String> links) {
        final var cleaned = decode(raw);
        var idx = 0;
        final int len = cleaned.length();
        while (idx < len) {
            if (Character.isWhitespace(cleaned.charAt(idx))) {
                while (idx < len && Character.isWhitespace(cleaned.charAt(idx))) {
                    idx++;
                }
                ensureSpace(sb);
                continue;
            }
            final var start = idx;
            while (idx < len && !Character.isWhitespace(cleaned.charAt(idx))) {
                idx++;
            }
            sb.append(cleaned, start, idx);
        }
    }

    private static void ensureSpace(final StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n' && sb.charAt(sb.length() - 1) != ' ') {
            sb.append(' ');
        }
    }

    private static void lineBreak(final StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static void blockBreak(final StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
    }

    private static String normalize(final StringBuilder sb) {
        final var trimmed = sb.toString().strip();
        final var withoutTrailingSpaces =
                TRAILING_LINE_WHITESPACE.matcher(trimmed).replaceAll("\n");
        final var withoutExcessNewlines =
                EXCESS_NEWLINES.matcher(withoutTrailingSpaces).replaceAll("\n\n");
        return TRAILING_BLANK_LINE.matcher(withoutExcessNewlines).replaceAll("\n");
    }

    private static int findClosingTag(final String html, final int from, final String name) {
        final var close = "</" + name;
        final int n = html.length();
        final int found = html.indexOf(close, from);
        if (found < 0) {
            return n;
        }
        return found;
    }

    private static int firstOf(final String html, final int from, final String... needles) {
        final int n = html.length();
        var best = n;
        for (final var needle : needles) {
            final int found = html.indexOf(needle, from);
            if (found >= 0 && found < best) {
                best = found;
            }
        }
        return best;
    }

    private static int tagEnd(final String html, final int from) {
        var quote = 0;
        for (var j = from + 1; j < html.length(); j++) {
            final char c = html.charAt(j);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return j;
            }
        }
        return -1;
    }

    private static String tagName(final String s) {
        final var in = s.strip();
        if (in.isEmpty()) {
            return "";
        }
        final var sb = new StringBuilder();
        for (final char c : in.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            } else {
                break;
            }
        }
        return sb.toString();
    }

    private static String attr(final String attrs, final String name) {
        final var lower = attrs.toLowerCase(Locale.ROOT);
        final var marker = name + "=";
        var idx = lower.indexOf(marker);
        while (idx >= 0) {
            if (idx == 0 || Character.isWhitespace(lower.charAt(idx - 1))) {
                final int start = idx + marker.length();
                if (start >= attrs.length()) {
                    return "";
                }
                final char quote = attrs.charAt(start);
                if (quote == '"' || quote == '\'') {
                    final int end = attrs.indexOf(quote, start + 1);
                    return end < 0 ? attrs.substring(start + 1) : attrs.substring(start + 1, end);
                }
                final var endIdx = attrs.indexOf(' ', start);
                return decode(attrs.substring(start, endIdx < 0 ? attrs.length() : endIdx));
            }
            idx = lower.indexOf(marker, idx + 1);
        }
        return null;
    }

    private static String decode(final String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        final var sb = new StringBuilder(s.length());
        for (var i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                continue;
            }
            final int semi = s.indexOf(';', i);
            final int end = semi < 0 || semi - i > 12 ? -1 : semi;
            if (end > i) {
                final var token = s.substring(i + 1, end);
                if (token.length() > 1 && (token.startsWith("#") || ENTITIES.containsKey(token))) {
                    if (token.startsWith("#")) {
                        final var num = token.substring(1);
                        try {
                            final int cp = num.startsWith("x") || num.startsWith("X")
                                    ? Integer.parseInt(num.substring(1), 16)
                                    : Integer.parseInt(num);
                            sb.appendCodePoint(cp);
                        } catch (final NumberFormatException e) {
                            sb.append('&').append(token).append(';');
                        }
                    } else {
                        sb.append(ENTITIES.getOrDefault(token, token));
                    }
                    i = end;
                } else {
                    sb.append('&');
                }
            } else {
                sb.append('&');
            }
        }
        return sb.toString();
    }
}
