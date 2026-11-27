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
package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.pretty.PrettyJsonMapper;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Detects the format of raw tool outputs (cards and Ctrl+O overlay) and pretty-prints
 * the formats that can be reformatted safely. Everything is best effort: any parse or
 * rendering failure falls back to the raw text, never breaking the previous rendering.
 */
@ApplicationScoped
public class ToolOutputFormat {
    public enum Format {
        MARKDOWN,
        JSON,
        XML,
        YAML,
        DIFF,
        /** A detected source language: syntax colored, never reinterpreted. */
        CODE,
        TEXT
    }

    private static final int MAX_PRETTY_LENGTH = 65536;
    private static final int MAX_DETECT_SOURCE_LENGTH = 256 * 1024;
    private static final int MAX_DETECT_CACHE_WEIGHT = 1024 * 1024;
    private static final int MAX_PRETTY_CACHE_WEIGHT = 512 * 1024;
    private static final Pattern YAML_KEY =
            Pattern.compile("^[ \\t]*[A-Za-z_][A-Za-z0-9_. -]*:(\\s|$)", Pattern.MULTILINE);
    private static final Pattern MD_ATX = Pattern.compile("^#{1,6} ", Pattern.MULTILINE);
    private static final Pattern MD_LIST = Pattern.compile("^[-*+] ", Pattern.MULTILINE);
    private static final Pattern MD_FENCE = Pattern.compile("^(?:```|~~~)", Pattern.MULTILINE);
    private static final Pattern MD_TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|");
    private static final Pattern MD_TABLE_DELIMITER = Pattern.compile("^\\s*\\|(?:\\s*:?-{1,}:?\\s*\\|)+\\s*$");
    /** Five or more lowercase words: a sentence, not an identifier list. */
    private static final Pattern MD_PROSE = Pattern.compile("\\b[a-z][a-z'-]{2,}\\b(?:\\s+\\b[a-z][a-z'-]{2,}\\b){4,}");

    /** Singleton bean: the caches are instance state, shared by every render tick. */
    private final Map<String, Format> detectCache = new LinkedHashMap<>();

    private final Map<String, String> prettyCache = new LinkedHashMap<>();
    // Character weights, guarded by the corresponding cache monitor.
    private int detectCacheWeight;
    private int prettyCacheWeight;
    private final PrettyJsonMapper pretty;
    private final JsonMapper mapper;
    private final DiffRenderer diffRenderer = new DiffRenderer();

    public ToolOutputFormat(final JsonMapper mapper) {
        this.pretty = new PrettyJsonMapper(mapper);
        this.mapper = mapper;
    }

    /**
     * Parses a JSON tool-arguments payload into a map, or {@code null} when the
     * payload is not valid JSON. Used by the tool detail preview to render the
     * meaningful arguments of write/edit/patch tools instead of the raw JSON.
     */
    public Map<String, Object> parseArguments(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.fromString(Map.class, json);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** Releases retained output on parent UI reset, theme change or disposal. */
    public void clearCaches() {
        synchronized (detectCache) {
            detectCache.clear();
            detectCacheWeight = 0;
        }
        synchronized (prettyCache) {
            prettyCache.clear();
            prettyCacheWeight = 0;
        }
    }

    /** Default construction for tests and manual wiring; the mapper must be closed by the owner. */
    public static ToolOutputFormat of(final JsonMapper mapper) {
        return new ToolOutputFormat(mapper);
    }

    /**
     * Classifies a raw tool output. Never throws: any probe failure downgrades to TEXT.
     * Results are cached (tool cards re-render on every tick).
     */
    public Format detect(final String output) {
        return detect(output, null);
    }

    /**
     * Same as {@link #detect(String)} but with the language of the output already known
     * ({@link ToolOutputLanguage#guess(String, String)}). A proven source language wins over
     * the yaml/markdown guesses: a script printing <code>key: value</code> lines or a table
     * is code, not a document, and must not be reinterpreted.
     */
    public Format detect(final String output, final String language) {
        if (output == null || output.isBlank()) {
            return Format.TEXT;
        }
        if ((long) output.length() + (language == null ? 0 : (long) language.length() + 1) > MAX_DETECT_SOURCE_LENGTH) {
            return detectUncached(output, language);
        }
        final var key = language == null ? output : language + '\n' + output;
        synchronized (detectCache) {
            final var cached = detectCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        final var format = detectUncached(output, language);
        synchronized (detectCache) {
            if (detectCache.put(key, format) == null) {
                detectCacheWeight += key.length();
            }
            final var entries = detectCache.entrySet().iterator();
            while (detectCache.size() > 128 || detectCacheWeight > MAX_DETECT_CACHE_WEIGHT) {
                detectCacheWeight -= entries.next().getKey().length();
                entries.remove();
            }
        }
        return format;
    }

    private Format detectUncached(final String output, final String language) {
        try {
            if (diffRenderer.looksLikeDiff(output)) {
                return Format.DIFF;
            }
        } catch (final RuntimeException e) {
            // fall through to the next probe
        }
        final var trimmed = output.strip();
        try {
            if ((trimmed.startsWith("{") || trimmed.startsWith("["))
                    && trimmed.length() <= MAX_PRETTY_LENGTH
                    && pretty.fromString(Object.class, trimmed) != null) {
                return Format.JSON;
            }
        } catch (final RuntimeException e) {
            // fall through to the next probe
        }
        try {
            if (trimmed.startsWith("<")
                    && !trimmed.contains("<!")
                    && trimmed.length() <= MAX_PRETTY_LENGTH
                    && parseXmlSecurely(trimmed) != null) {
                return Format.XML;
            }
        } catch (final Exception e) {
            // fall through to the next probe
        }
        if (language != null) {
            return Format.CODE;
        }
        try {
            if (looksLikeYaml(output)) {
                return Format.YAML;
            }
        } catch (final RuntimeException e) {
            // fall through to the next probe
        }
        try {
            if (looksLikeMarkdown(output)) {
                return Format.MARKDOWN;
            }
        } catch (final RuntimeException e) {
            // fall through
        }
        return Format.TEXT;
    }

    private static boolean looksLikeYaml(final String output) {
        final var trimmed = output.strip();
        if (trimmed.startsWith("---") && trimmed.length() > 3 && trimmed.charAt(3) != ':') {
            return true;
        }
        var keys = 0;
        final var matcher = YAML_KEY.matcher(output);
        while (matcher.find() && keys < 2) {
            keys++;
        }
        return keys >= 2;
    }

    /**
     * True only for output that <em>is</em> a markdown document: a terminal tool never
     * produces prose, so anything looser turns piped output (a {@code tabulate} table, a
     * log line starting with {@code -}) into a bordered grid and destroys its structure.
     * <li>a fenced code block plus a heading or a list;</li>
     * <li>a complete GFM table (header, delimiter and at least one body row) plus a heading,
     * or plus an actual prose paragraph - the latter is what separates an authored document
     * from a {@code tabulate()} dump, whose only other marker is a bullet-looking log line.</li>
     * </ul>
     */
    private static boolean looksLikeMarkdown(final String output) {
        final var fence = MD_FENCE.matcher(output).find();
        final var atx = MD_ATX.matcher(output).find();
        if (fence && (atx || MD_LIST.matcher(output).find())) {
            return true;
        }
        return hasCompleteTable(output) && (atx || hasProse(output));
    }

    /** True when a line reads like a sentence rather than like code, log or table content. */
    private static boolean hasProse(final String output) {
        for (final var line : output.split("\n", -1)) {
            if (!line.isBlank()
                    && !MD_TABLE_ROW.matcher(line).matches()
                    && MD_PROSE.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** True when the output holds a header row, a delimiter row and at least one body row. */
    private static boolean hasCompleteTable(final String output) {
        final var lines = output.split("\n", -1);
        for (var i = 0; i + 1 < lines.length; i++) {
            if (!MD_TABLE_ROW.matcher(lines[i]).matches()
                    || !MD_TABLE_DELIMITER.matcher(lines[i + 1]).matches()) {
                continue;
            }
            if (i + 2 < lines.length && MD_TABLE_ROW.matcher(lines[i + 2]).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-indents a JSON tool output (2-space), or returns the input unchanged when it is
     * not parseable JSON or too large to reformat comfortably.
     */
    public String prettyJson(final String raw) {
        return pretty(raw, this::doPrettyJson);
    }

    /** Re-indents an XML tool output through the secured JDK identity transformer. */
    public String prettyXml(final String raw) {
        return pretty(raw, this::doPrettyXml);
    }

    private String pretty(final String raw, final UnaryOperator<String> printer) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_PRETTY_LENGTH) {
            return raw;
        }
        synchronized (prettyCache) {
            final var cached = prettyCache.get(raw);
            if (cached != null) {
                return cached;
            }
        }
        String result;
        try {
            result = printer.apply(raw);
        } catch (final Throwable e) {
            result = raw;
        }
        if (result == null) {
            result = raw;
        }
        final long weight = (long) raw.length() + result.length();
        if (weight <= MAX_PRETTY_CACHE_WEIGHT) {
            synchronized (prettyCache) {
                final var previous = prettyCache.put(raw, result);
                prettyCacheWeight += (int) weight - (previous == null ? 0 : raw.length() + previous.length());
                final var entries = prettyCache.entrySet().iterator();
                while (prettyCache.size() > 16 || prettyCacheWeight > MAX_PRETTY_CACHE_WEIGHT) {
                    final var eldest = entries.next();
                    prettyCacheWeight -=
                            eldest.getKey().length() + eldest.getValue().length();
                    entries.remove();
                }
            }
        }
        return result;
    }

    private String doPrettyJson(final String raw) {
        final var trimmed = raw.strip();
        final var parsed = pretty.fromString(Object.class, trimmed);
        return parsed == null ? raw : pretty.toString(parsed);
    }

    private String doPrettyXml(final String raw) {
        try {
            final var trimmed = raw.strip();
            if (!trimmed.startsWith("<") || trimmed.contains("<!")) {
                return raw;
            }
            final var factory = DocumentBuilderFactory.newInstance();
            harden(factory);
            final var builder = factory.newDocumentBuilder();
            final Document document;
            try (var reader = new StringReader(trimmed)) {
                document = builder.parse(new InputSource(reader));
            }
            final var transformerFactory = TransformerFactory.newInstance();
            harden(transformerFactory);
            final var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            final var out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            final var prettyXml = out.toString();
            return prettyXml.isBlank() ? raw : prettyXml;
        } catch (final Exception e) {
            return raw;
        }
    }

    /** Returns the parsed XML document when the input is well-formed XML, else null (never throws). */
    private static Document parseXmlSecurely(final String raw) {
        try {
            final var factory = DocumentBuilderFactory.newInstance();
            harden(factory);
            try (var reader = new StringReader(raw)) {
                return factory.newDocumentBuilder().parse(new InputSource(reader));
            }
        } catch (final Exception e) {
            return null;
        }
    }

    /** Hardens an XML factory against XXE/external entity resolution (no remote lookups, ever). */
    private static void harden(final DocumentBuilderFactory factory) {
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (final ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    }

    private static void harden(final TransformerFactory factory) {
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (final TransformerConfigurationException e) {
            throw new IllegalStateException(e);
        }
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    }
}
