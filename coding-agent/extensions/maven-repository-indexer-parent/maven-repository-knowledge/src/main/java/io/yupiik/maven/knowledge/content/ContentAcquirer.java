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
package io.yupiik.maven.knowledge.content;

import io.yupiik.htmltomarkdown.HtmlLightParser;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.bytecode.BytecodeAnalyzer;
import io.yupiik.maven.knowledge.bytecode.ClassApiCodeGraphs;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;

/**
 * Acquires documentation content for an artifact following the locked fallback chain:
 * <ol>
 *     <li>{@code -sources.jar} - extracted source text (preferred).</li>
 *     <li>{@code -javadoc.jar} - extracted Javadoc/HTML text.</li>
 *     <li>pom {@code <url>} - bounded webfetch, skipped when the URL points at a git/source-hosting repo.</li>
 *     <li>bytecode - the class listing is handed to the LLM (strategy B).</li>
 *     <li>{@code NONE}.</li>
 * </ol>
 */
public class ContentAcquirer {
    private static final Pattern CONTENT_CLASS = Pattern.compile(".*class\\s*=\\s*\"[^\"]*content[^\"]*\".*");
    private static final Pattern NAV_CLASS = Pattern.compile(".*class\\s*=\\s*\"[^\"]*nav[^\"]*\".*");

    private final LocalRepository repository;
    private final WebFetch webFetch;
    private final KnowledgeConfig config;
    private final DocumentBuilderFactory documentBuilderFactory;
    private final HtmlLightParser htmlParser;
    private final BytecodeAnalyzer bytecode;
    private static final Set<String> GIT_HOSTS = Set.of("github.com", "gitlab.com", "bitbucket.org", "codeberg.org");

    /** Source parts are chunked to at most this many chars each before writing. */
    private static final int SOURCES_CHUNK_CHARS = 60_000;

    /** Whether a Javadoc jar entry is a class page worth extracting (frames/nav/package indexes are skipped). */
    private static boolean isClassPage(final String name) {
        if (!name.endsWith(".html")) {
            return false;
        }
        final var lower = name.toLowerCase(Locale.ROOT);
        return !lower.endsWith("-summary.html")
                && !lower.endsWith("index.html")
                && !lower.contains("index-")
                && !lower.contains("overview")
                && !lower.contains("allclasses")
                && !lower.contains("constant-values")
                && !lower.contains("serialized-form")
                && !lower.contains("help-doc")
                && !lower.contains("deprecated-list")
                && !lower.contains("module-summary")
                && !lower.contains("doc-files/")
                && !lower.endsWith("package-tree.html")
                && !lower.endsWith("package-use.html");
    }

    public ContentAcquirer(final LocalRepository repository, final WebFetch webFetch, final KnowledgeConfig config) {
        this.repository = repository;
        this.webFetch = webFetch;
        this.config = config;
        this.documentBuilderFactory = secureDocumentBuilderFactory();
        this.htmlParser = new HtmlLightParser();
        this.bytecode = new BytecodeAnalyzer();
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() {
        final var factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (final ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return factory;
    }

    public ContentAcquired acquire(final Gav gav) {
        final var artifact = repository.resolve(gav);
        if (!artifact.present()) {
            return ContentAcquired.none(gav);
        }
        final var main = artifact.mainJar();
        if (main != null && Files.isRegularFile(main)) {
            final var plugin = readDescriptor(main, "META-INF/maven/plugin.xml");
            if (plugin != null) {
                return ContentAcquired.of(gav, ContentKind.PLUGIN, plugin);
            }
            for (final var extensionPath : new String[] {"META-INF/maven/extension.xml", "META-INF/extension.xml"}) {
                final var extension = readDescriptor(main, extensionPath);
                if (extension != null) {
                    return ContentAcquired.of(gav, ContentKind.EXTENSION, extension);
                }
            }
        }
        final var sources = artifact.sourcesJar();
        if (sources != null && Files.isRegularFile(sources)) {
            return ContentAcquired.of(gav, ContentKind.SOURCES, fromSourcesJar(sources, gav));
        }
        final var javadoc = artifact.javadocJar();
        if (javadoc != null && Files.isRegularFile(javadoc)) {
            return ContentAcquired.of(gav, ContentKind.JAVADOC, fromJavadocJar(javadoc));
        }
        final var pom = artifact.pom();
        if (pom != null) {
            final var doc = readDoc(pom, gav);
            if (doc != null) {
                return ContentAcquired.of(gav, ContentKind.DOC, doc);
            }
        }
        final var classes = artifact.mainJar();
        if (classes != null && Files.isRegularFile(classes)) {
            return ContentAcquired.of(gav, ContentKind.CLASSES, bytecodeIndex(classes));
        }
        return ContentAcquired.none(gav);
    }

    /**
     * Deterministic entry-point classes for an artifact, derived from its main jar bytecode (no LLM, no ASM).
     * Returns an empty list when no main jar is present or nothing usable is found; the caller may combine
     * this with descriptor-derived entries (a plugin's mojo implementations) if needed.
     */
    public List<String> entryPoints(final Gav gav) {
        final var artifact = repository.resolve(gav);
        final var main = artifact == null ? null : artifact.mainJar();
        if (main == null || !Files.isRegularFile(main)) {
            return List.of();
        }
        try {
            return bytecode.entryPoints(main);
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Deterministic public-API markdown for an artifact, derived from its main jar bytecode (no LLM, no ASM).
     * Always present while a main jar exists, so the generated {@code api} knowledge file is substantive even
     * when the artifact ships neither sources, javadoc nor a doc page. Empty when no main jar is available.
     */
    public String api(final Gav gav) {
        final var artifact = repository.resolve(gav);
        final var main = artifact == null ? null : artifact.mainJar();
        if (main == null || !Files.isRegularFile(main)) {
            return "";
        }
        try {
            return bytecodeIndex(main);
        } catch (final RuntimeException e) {
            return "";
        }
    }

    /**
     * Compact binary code-graph for an artifact, derived from its main jar bytecode (no LLM, no ASM): one
     * {@link io.yupiik.codegraph.index.IndexSymbol} per public/protected top-level class and per public method,
     * plus {@code EXTENDS}/{@code IMPLEMENTS} edges. Serialized with the shared {@code code-graph-index} format
     * so the runtime can mmap it; empty array when no main jar is present. Kind/type ordinals follow the shared
     * scheme ({@code TYPE=0, METHOD=1, ENUM=4, INTERFACE=6, ANNOTATION=7}; {@code EXTENDS=2, IMPLEMENTS=3}).
     */
    public byte[] codeGraph(final Gav gav) {
        final var artifact = repository.resolve(gav);
        final var main = artifact == null ? null : artifact.mainJar();
        if (main == null || !Files.isRegularFile(main)) {
            return new byte[0];
        }
        try {
            return ClassApiCodeGraphs.toBytes(bytecode.analyze(main), null);
        } catch (final RuntimeException e) {
            return new byte[0];
        }
    }

    /**
     * Acquires the artifact's knowledge as named parts following part of the {@link #acquire(Gav)} fallback, but
     * suited to the flat {@code -knowledge-<short>.md} file layout: sources are chunked ({@code sources-N}), javadoc
     * becomes one part per class page ({@code javadoc-<qualifiedClass>}), a pom doc becomes one {@code doc} part, and
     * the bytecode-only fallback yields no part (its surface lives in the {@code api} file). Plugin/extension
     * descriptors are NOT emitted as siblings - their config is folded into the knowledge index instead (see
     * {@link #moduleConfig(Gav)}). The caller combines this with {@link #api(Gav)} and {@link #entryPoints(Gav)}.
     */
    public List<ContentPart> contentParts(final Gav gav) {
        final var artifact = repository.resolve(gav);
        if (artifact == null || !artifact.present()) {
            return List.of();
        }
        final var results = new ArrayList<ContentPart>();
        final var main = artifact.mainJar();
        if (main != null && Files.isRegularFile(main)) {
            // plugin/extension descriptors are folded into the index via moduleConfig(gav), not a sibling file
            if (readDescriptor(main, "META-INF/maven/plugin.xml") != null) {
                return results;
            }
            for (final var extensionPath : new String[] {"META-INF/maven/extension.xml", "META-INF/extension.xml"}) {
                if (readDescriptor(main, extensionPath) != null) {
                    return results;
                }
            }
        }
        final var sources = artifact.sourcesJar();
        if (sources != null && Files.isRegularFile(sources)) {
            final var content = fromSourcesJar(sources, gav);
            return chunkParts("sources", content, SOURCES_CHUNK_CHARS);
        }
        final var javadoc = artifact.javadocJar();
        if (javadoc != null && Files.isRegularFile(javadoc)) {
            return fromJavadocPages(javadoc);
        }
        final var pom = artifact.pom();
        if (pom != null) {
            final var doc = readDoc(pom, gav);
            if (doc != null) {
                return List.of(ContentPart.of("doc", doc));
            }
        }
        return results; // bytecode-only artifacts contribute no part; the api file carries their surface
    }

    /**
     * Compact markdown describing a plugin's mojos and their configuration (parameter name, system-property
     * override when any, default when any and description) or an extension's descriptor, folded into the knowledge
     * index. Empty for artifacts that are neither plugins nor extensions.
     */
    public String moduleConfig(final Gav gav) {
        final var artifact = repository.resolve(gav);
        final var main = artifact == null ? null : artifact.mainJar();
        if (main == null || !Files.isRegularFile(main)) {
            return "";
        }
        final var plugin = readDescriptor(main, "META-INF/maven/plugin.xml");
        if (plugin != null) {
            return new PluginXmlToMarkdown(documentBuilderFactory).apply(plugin);
        }
        for (final var extensionPath : new String[] {"META-INF/maven/extension.xml", "META-INF/extension.xml"}) {
            final var extension = readDescriptor(main, extensionPath);
            if (extension != null) {
                return new ExtensionXmlToMarkdown(documentBuilderFactory).apply(extension);
            }
        }
        return "";
    }

    /** Chunks a body of content into {@code <prefix>-N} parts of at most {@code maxChars} each. */
    private static List<ContentPart> chunkParts(final String prefix, final String content, final int maxChars) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        final var parts = new ArrayList<ContentPart>();
        var index = 0;
        var i = 0;
        while (i < content.length()) {
            final var end = Math.min(content.length(), i + maxChars);
            parts.add(ContentPart.of(prefix + "-" + index, content.substring(i, end)));
            index++;
            i = end;
        }
        return parts;
    }

    /**
     * Extracts one knowledge part per javadoc class page, keyed by the page's qualified class name (inner classes
     * and nested types included), so a consumer can read a single class's doc without pulling the whole jar.
     */
    private List<ContentPart> fromJavadocPages(final Path jar) {
        final var parts = new ArrayList<ContentPart>();
        try (final var zip = new ZipFile(jar.toFile())) {
            final var pages = zip.stream()
                    .filter(e -> !e.isDirectory() && isClassPage(e.getName()))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .limit(config.maxJavadocPages())
                    .toList();
            for (final var entry : pages) {
                final var extracted = extractClassPage(readAll(zip, entry));
                if (extracted == null) {
                    continue;
                }
                final var className = entry.getName()
                        .substring(0, entry.getName().length() - ".html".length())
                        .replace('/', '.');
                parts.add(ContentPart.of("javadoc-" + className, extracted));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return parts;
    }

    /**
     * Structural, markdown-ish summary of the public API of a jar derived from bytecode (modifiers,
     * supertype, interfaces, annotations, method signatures per top-level class). Used when neither sources,
     * javadoc nor a pom doc is present, so the knowledge doc is still substantive instead of a bare class list.
     */
    private String bytecodeIndex(final Path jar) {
        final var sb = new StringBuilder();
        sb.append("Artifact API:\n");
        for (final var api : bytecode.analyze(jar)) {
            sb.append('\n').append("### ").append(api.className()).append('\n');
            sb.append("- kind: ").append(api.kind()).append('\n');
            if (!api.modifiers().isBlank()) {
                sb.append("- modifiers: ").append(api.modifiers()).append('\n');
            }
            if (api.superclass() != null) {
                sb.append("- superclass: ").append(api.superclass()).append('\n');
            }
            if (!api.interfaces().isEmpty()) {
                sb.append("- interfaces: ")
                        .append(String.join(", ", api.interfaces()))
                        .append('\n');
            }
            if (!api.annotations().isEmpty()) {
                sb.append("- annotations: ")
                        .append(String.join(", ", api.annotations()))
                        .append('\n');
            }
            if (!api.methods().isEmpty()) {
                sb.append("- methods:\n");
                for (final var m : api.methods()) {
                    sb.append("  - `")
                            .append(m.name())
                            .append('(')
                            .append(String.join(", ", m.parameterTypes()))
                            .append(") : ")
                            .append(m.returnType())
                            .append("`")
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String readDescriptor(final Path jar, final String descriptorPath) {
        try (final var zip = new ZipFile(jar.toFile())) {
            final var entry = zip.getEntry(descriptorPath);
            if (entry == null) {
                return null;
            }
            try (final var in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (final IOException e) {
            return null;
        }
    }

    private String readDoc(final Path pom, final Gav gav) {
        try {
            final var doc = documentBuilderFactory.newDocumentBuilder().parse(pom.toFile());
            final var url = firstText(doc, "url");
            if (url == null || url.isBlank() || !isDocHost(url) || config.boundedFetch() == null) {
                return null;
            }
            try {
                final var raw = webFetch.fetch(
                        url,
                        config.boundedFetch().timeoutMs(),
                        config.boundedFetch().maxBytes());
                final String markdown;
                try {
                    markdown = markdownifyDoc(raw);
                } catch (final Exception e) {
                    return null;
                }
                return markdown == null || markdown.isBlank() ? null : markdown;
            } catch (final IOException e) {
                return null;
            }
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Turns a fetched documentation page into compact markdown: keeps only the main/content/body region and drops
     * chrome (nav, header, footer, script, style and any element with an id/class marker for chrome) before
     * converting, so no raw HTML or menu noise ever reaches the LLM.
     */
    private String markdownifyDoc(final String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return null;
        }
        final var region = docRegion(rawHtml);
        final var cleaned = stripChrome(region);
        final var markdown = htmlParser.toMarkdown(cleaned).strip();
        return markdown.isBlank() ? null : markdown;
    }

    /** Limits the HTML to the {@code <main>} / content-classed element / {@code <body>} region, in that order. */
    private static String docRegion(final String html) {
        final var main = regionBetween(html, "<main", "</main>");
        if (main != null) {
            return main;
        }
        final var content = contentElement(html);
        if (content != null) {
            return content;
        }
        final var body = regionBetween(html, "<body", "</body>");
        return body == null ? html : body;
    }

    private static String contentElement(final String html) {
        var idx = 0;
        while (true) {
            final var open = html.indexOf('<', idx);
            if (open < 0) {
                return null;
            }
            final var gt = html.indexOf('>', open);
            if (gt < 0) {
                return null;
            }
            final var tag = html.substring(open + 1, gt);
            final var lower = tag.toLowerCase(Locale.ROOT);
            if (CONTENT_CLASS.matcher(lower).matches()) {
                final var closeTag = findBlockEnd(html, open, gt, tag);
                return closeTag < 0 ? html.substring(open) : html.substring(open, closeTag);
            }
            idx = gt + 1;
        }
    }

    /** Finds an opening tag (given its content after {@code <}) and returns the index just after its matching close. */
    private static int findBlockEnd(final String html, final int open, final int gt, final String openTagContent) {
        final var tagName = parseTagName(openTagContent);
        final var close = "</" + tagName + ">";
        final var end = html.indexOf(close, gt + 1);
        return end < 0 ? -1 : end + close.length();
    }

    private static String parseTagName(final String openTagContent) {
        final var trimmed = openTagContent.strip();
        final var sp = trimmed.indexOf(' ');
        return sp < 0 ? trimmed : trimmed.substring(0, sp);
    }

    private static String regionBetween(final String html, final String openPrefix, final String closeTag) {
        final var start = html.indexOf(openPrefix);
        if (start < 0) {
            return null;
        }
        final var gt = html.indexOf('>', start);
        if (gt < 0) {
            return null;
        }
        final var end = html.indexOf(closeTag, gt + 1);
        return end < 0 ? html.substring(gt + 1) : html.substring(gt + 1, end);
    }

    /** Drops chrome subtrees and their tags from an HTML region, keeping only content-relevant nodes. */
    private static String stripChrome(final String html) {
        final var sb = new StringBuilder();
        var idx = 0;
        while (idx < html.length()) {
            final var open = html.indexOf('<', idx);
            if (open < 0) {
                sb.append(html, idx, html.length());
                break;
            }
            // keep any text between the previous tag and this one
            if (open > idx) {
                sb.append(html, idx, open);
            }
            final var gt = html.indexOf('>', open);
            if (gt < 0) {
                sb.append(html, open, html.length());
                break;
            }
            final var opening = html.substring(open + 1, gt);
            if (isChromeOpening(opening)) {
                final var name = parseTagName(opening);
                final var close = "</" + name + ">";
                final var end = html.indexOf(close, gt + 1);
                idx = end < 0 ? gt + 1 : end + close.length();
                continue;
            }
            sb.append(html, open, gt + 1);
            idx = gt + 1;
        }
        return sb.toString();
    }

    private static boolean isChromeOpening(final String opening) {
        final var lower = opening.toLowerCase(Locale.ROOT);
        for (final var tag : new String[] {"nav", "header", "footer", "script", "style"}) {
            final var name = parseTagName(lower);
            if (name.equals(tag)) {
                return true;
            }
        }
        return lower.contains("id=\"footer\"")
                || lower.contains("id=\"header\"")
                || NAV_CLASS.matcher(lower).matches();
    }

    private static boolean isDocHost(final String url) {
        try {
            final var host = URI.create(url).getHost();
            return host != null && GIT_HOSTS.stream().noneMatch(host::contains);
        } catch (final Exception e) {
            return false;
        }
    }

    private static String firstText(final Document doc, final String tag) {
        final var list = doc.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : list.item(0).getTextContent();
    }

    private String fromSourcesJar(final Path jar, final Gav gav) {
        final var sb = new StringBuilder();
        sb.append("Artifact: ").append(gav.coordinate()).append('\n');
        final int budget = config.maxContentBytes() > 0 ? config.maxContentBytes() : Integer.MAX_VALUE;
        final var seen = new HashSet<String>();
        try (final var zip = new ZipFile(jar.toFile())) {
            final var entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> e.getName().endsWith(".java"))
                    .filter(ContentAcquirer::isWorthScanning)
                    .limit(config.maxSourceFiles())
                    .toList();
            for (final var entry : entries) {
                if (sb.length() >= budget) {
                    sb.append('\n').append("<!-- source content truncated to content budget -->\n");
                    break;
                }
                final var chunk = readChunk(zip, entry, config.maxSourceFileChars());
                if (chunk == null) {
                    sb.append("### ").append(entry.getName()).append('\n').append("<unreadable>\n");
                    continue;
                }
                final var chunk2 = stripComments(chunk);
                if (chunk2.isBlank()) {
                    continue;
                }
                // dedupe identical content across files belonging to the same artifact
                if (!seen.add(
                        Integer.toHexString(chunk2.hashCode()) + chunk2.substring(0, Math.min(64, chunk2.length())))) {
                    continue;
                }
                sb.append("### ").append(entry.getName()).append('\n');
                // hard per-artifact cap: truncate this file to the remaining budget so the total never exceeds it
                final var free = budget - sb.length();
                if (free <= 0) {
                    sb.append('\n').append("<!-- source content truncated to content budget -->\n");
                    break;
                }
                sb.append(chunk2, 0, Math.min(chunk2.length(), free)).append('\n');
                if (sb.length() >= budget) {
                    sb.append('\n').append("<!-- source content truncated to content budget -->\n");
                    break;
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }

    static String stripComments(final String source) {
        final var sb = new StringBuilder(source.length());
        final var length = source.length();
        var i = 0;
        while (i < length) {
            final var c = source.charAt(i);
            final var next = i + 1 < length ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                // line comment: drop until end of line
                while (i < length && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                // block comment (including license headers and javadoc): drop until its end
                i += 2;
                while (i + 1 < length && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else if (c == '"' || c == '\'') {
                // string/char literal: keep it intact (comments inside are not comments)
                sb.append(c);
                i++;
                while (i < length) {
                    final var sc = source.charAt(i);
                    sb.append(sc);
                    if (sc == '\\' && i + 1 < length) {
                        sb.append(source.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    i++;
                    if (sc == c) {
                        break;
                    }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isWorthScanning(final ZipEntry entry) {
        final var name = entry.getName();
        if (name.startsWith("META-INF/")) {
            return false;
        }
        final var fileName = name.substring(name.lastIndexOf('/') + 1);
        if (fileName.equals("module-info.java") || fileName.equals("package-info.java")) {
            return false;
        }
        final var lower = name.toLowerCase(Locale.ROOT);
        return !lower.contains("/generated/")
                && !lower.contains("/srcgen/")
                && !lower.contains("target/generated")
                && !lower.equals("module-info.java")
                && !lower.endsWith("/module-info.java");
    }

    private String fromJavadocJar(final Path jar) {
        final int budget = config.maxContentBytes() > 0 ? config.maxContentBytes() : Integer.MAX_VALUE;
        final var sb = new StringBuilder();
        try (final var zip = new ZipFile(jar.toFile())) {
            final var pages = zip.stream()
                    .filter(e -> !e.isDirectory() && isClassPage(e.getName()))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .limit(config.maxJavadocPages())
                    .toList();
            for (final var entry : pages) {
                if (sb.length() >= budget) {
                    sb.append('\n').append("<!-- javadoc content truncated to content budget -->\n");
                    break;
                }
                final var html = readAll(zip, entry);
                final var extracted = extractClassPage(html);
                if (extracted == null) {
                    continue;
                }
                final var free = budget - sb.length();
                if (free <= 0) {
                    sb.append('\n').append("<!-- javadoc content truncated to content budget -->\n");
                    break;
                }
                sb.append(extracted, 0, Math.min(extracted.length(), free)).append('\n');
                if (sb.length() >= budget) {
                    sb.append('\n').append("<!-- javadoc content truncated to content budget -->\n");
                    break;
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }

    /**
     * Extracts a compact, HTML-free markdown body from one Javadoc class page: the class/interface name, a short
     * class description (first paragraph, if any) and, under {@code ## Members}, each member signature followed by
     * its short description (if any). All chrome (frames, nav, package lists, assets) is discarded.
     */
    private String extractClassPage(final String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        final var sb = new StringBuilder();
        var snippetCount = 0;

        // class/interface name + first sentence of the type description
        final var h1 = blockAfter("<h1", "</h1>", html, 0);
        final var typeName = plain(h1);
        sb.append("### ").append(typeName.isBlank() ? "type" : typeName).append('\n');

        var searchFrom = 0;
        while (snippetCount < 64) {
            final var open = indexOfTag(html, "pre", searchFrom);
            if (open < 0) {
                break;
            }
            final var close = html.indexOf("</pre>", open);
            if (close < 0) {
                break;
            }
            final var signature = plain(html.substring(open, close));
            if (!signature.isBlank()) {
                sb.append("- `").append(signature).append("`");
                // a following <div class="block"> holds the member description (if any)
                final var doc = followingDoc(html, close);
                if (doc != null && !doc.isBlank()) {
                    sb.append(" - ").append(doc);
                }
                sb.append('\n');
                snippetCount++;
            }
            searchFrom = close + "</pre>".length();
        }

        if (sb.chars().filter(c -> c == '\n').count() <= 1) {
            // nothing useful extracted (e.g. a bare/exported page)
            return null;
        }
        return sb.toString().stripTrailing();
    }

    /** Returns text between the first {@code <h1 ...>} and its {@code </h1>}, or empty. */
    private static String blockAfter(final String open, final String close, final String html, final int from) {
        final var start = html.indexOf(open, from);
        if (start < 0) {
            return "";
        }
        final var contentStart = html.indexOf('>', start);
        if (contentStart < 0) {
            return "";
        }
        final var end = html.indexOf(close, contentStart);
        return end < 0 ? "" : html.substring(contentStart + 1, end);
    }

    /** Finds the opening tag {@code <pre ...>} (any attributes), starting the search at {@code from}. */
    private static int indexOfTag(final String html, final String tag, final int from) {
        var idx = from;
        while (true) {
            final var open = html.indexOf('<', idx);
            if (open < 0) {
                return -1;
            }
            final var gt = html.indexOf('>', open);
            if (gt < 0) {
                return -1;
            }
            final var raw = html.substring(open + 1, gt).strip();
            if (raw.equalsIgnoreCase(tag)
                    || raw.toLowerCase(Locale.ROOT).startsWith(tag + " ")
                    || raw.toLowerCase(Locale.ROOT).startsWith(tag + "\n")) {
                return open;
            }
            idx = gt + 1;
        }
    }

    /** Finds a {@code <div ...class="...block..."...>} description shortly after {@code after}, if any. */
    private String followingDoc(final String html, final int after) {
        final var blockOpen = html.indexOf("<div", after);
        if (blockOpen < 0 || blockOpen > after + 10_000) {
            return null;
        }
        final var tagEnd = html.indexOf('>', blockOpen);
        if (tagEnd < 0 || tagEnd > blockOpen + 500) {
            return null;
        }
        final var closer = html.indexOf("</div>", tagEnd);
        if (closer < 0 || closer > tagEnd + 40_000) {
            return null;
        }
        final var md = htmlParser.toText(html.substring(tagEnd + 1, closer));
        final var singleLine = md.replace('\n', ' ').strip();
        return singleLine.isEmpty() ? null : singleLine.substring(0, Math.min(512, singleLine.length()));
    }

    /** Strips markup from a raw snippet into plain text via the shared {@link HtmlLightParser}. */
    private String plain(final String raw) {
        return htmlParser.toText(raw).replace('\n', ' ').strip();
    }

    private static String readAll(final ZipFile zip, final ZipEntry entry) throws IOException {
        try (final var in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readChunk(final ZipFile zip, final ZipEntry entry, final int cap) throws IOException {
        try (final var in = zip.getInputStream(entry)) {
            final var bytes = in.readNBytes(cap);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
