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
package io.yupiik.ia.agent.core.codegraph;

import static io.yupiik.ia.agent.core.codegraph.GraphModel.Edge;
import static io.yupiik.ia.agent.core.codegraph.GraphModel.Symbol;

import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes the agent-readable per-source-file markdown cards under the graph store, mirroring the project source
 * layout, plus the graft-style concept-node cards and {@code INDEX.md} at the store root. Each wiring card
 * mirrors its source tree position and shows, in Graft's format: a {@code # <relPath>} header (optionally with
 * [[slug]] uplinks for the concept cards that cite the file), an optional one-line file summary, then one bullet
 * per symbol ({@code - <name> · <kind> · L<s>-L<e>}) with an optional inline one-liner from the deep enrichment.
 * There is no frontmatter and no {@code ## Symbols}/{@code ## Edges} split on wiring cards: edges live in the
 * machine-only {@code .graph/wiring.json}, matching Graft's "you can't grep a traversal".
 *
 * <p>The wiring model is heavily inspired by the Graft project ({@link https://github.com/trailhq/Graft}): the
 * per-file cards mirror the source tree and stay machine- and grep-friendly, while concept nodes group those
 * files into a curated, LLM-written set with typed links between them. Enrichment (file summaries and per-symbol
 * one-liners) is consulted lazily through the {@link Enrichment} lookup, so the same renderer is idempotent
 * whether called during the structural build or after an async deep pass completes.
 */
public class DocTreeWriter {
    private static final Pattern SOURCE_EXTENSION = Pattern.compile("\\.[^./]+$");

    private static final String FRONTMATTER_OPEN = "---";

    /** Source of tier-2 enrichment consulted when rendering cards and the wiring graph. */
    public interface Enrichment {
        String fileSummary(String relSource);

        String symbolOneLiner(String relSource, String fqn);

        Enrichment EMPTY = new Enrichment() {
            @Override
            public String fileSummary(final String relSource) {
                return null;
            }

            @Override
            public String symbolOneLiner(final String relSource, final String fqn) {
                return null;
            }
        };
    }

    private final CodingAgentConfiguration configuration;
    private final Path docsRoot;
    private volatile Enrichment enrichment = Enrichment.EMPTY;

    public DocTreeWriter(final CodingAgentConfiguration configuration, final Path docsRoot) {
        this.configuration = configuration;
        this.docsRoot = docsRoot;
    }

    /** Attach the tier-2 enrichment source (typically a {@link DocEnricher}). */
    public DocTreeWriter enrichment(final Enrichment enrichment) {
        this.enrichment = enrichment == null ? Enrichment.EMPTY : enrichment;
        return this;
    }

    public boolean enabled() {
        final var cfg = configuration.structuralCodeGraph();
        return cfg != null && cfg.docs() && docsRoot != null;
    }

    /**
     * Write (or refresh) the wiring card for a single parsed file, rendering it idempotently from the current
     * enrichment state. Returns the card file path when emitted, null when skipped (disabled).
     */
    public Path write(final String relSource, final GraphModel model) {
        if (!enabled() || model == null) {
            return null;
        }
        final var cardFile = docFile(relSource);
        if (cardFile == null) {
            return null;
        }
        final var content = render(relSource, model);
        try {
            final StringBuilder body = readBody(cardFile);
            if (body != null && content.contentEquals(body)) {
                return cardFile;
            }
            writeBody(cardFile, content);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return cardFile;
    }

    /**
     * Write (or refresh) a concept-node card at the store root. Concept cards carry a slug and typed links to
     * other concepts (by slug), matching Graft's node files. Returns the card path when emitted.
     */
    public Path writeConcept(final Path conceptsRoot, final ConceptNode node) {
        if (!enabled() || node == null || node.slug() == null || node.slug().isBlank()) {
            return null;
        }
        final var cardFile = conceptsRoot.resolve(node.slug() + ".md");
        final var content = renderConcept(node);
        try {
            final StringBuilder body = readBody(cardFile);
            if (body != null && content.contentEquals(body)) {
                return cardFile;
            }
            writeBody(cardFile, content);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return cardFile;
    }

    /**
     * Write (or refresh) the {@code INDEX.md} roster at the store root, listing the concept nodes. Per-file
     * wiring cards are not enumerated (they mirror the source tree), mirroring Graft's index.
     */
    public Path writeIndex(final Path indexRoot, final List<ConceptNode> concepts) {
        if (!enabled() || indexRoot == null) {
            return null;
        }
        final var sb = new StringBuilder("# graph — repo map\n\n");
        if (concepts != null) {
            final var sorted = new ArrayList<>(concepts);
            sorted.sort(Comparator.comparing(ConceptNode::slug));
            for (final var node : sorted) {
                sb.append("- [[")
                        .append(node.slug())
                        .append("]]: ")
                        .append(oneLine(node.summary()))
                        .append('\n');
            }
            if (sorted.isEmpty()) {
                sb.append("_(no concept nodes yet — enable 'enrich' for the curated layer)_\n");
            }
        }
        sb.append("\nPer-file wiring cards mirror the source tree and are not enumerated here.\n");
        final var index = indexRoot.resolve("INDEX.md");
        try {
            writeBody(index, sb.toString());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    /**
     * Remove a source file's wiring card (used when the source file was deleted). True when a card was removed.
     */
    public boolean delete(final String relSource) {
        if (!enabled()) {
            return false;
        }
        final var cardFile = docFile(relSource);
        if (cardFile == null || !Files.isRegularFile(cardFile)) {
            return false;
        }
        try {
            Files.delete(cardFile);
            return true;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path docFile(final String relSource) {
        if (!enabled() || relSource == null || relSource.isBlank()) {
            return null;
        }
        final var stem = SOURCE_EXTENSION.matcher(relSource).replaceFirst("");
        return docsRoot.resolve(stem + ".md");
    }

    /**
     * The structural digest of a model — the staleness key for the tier-2 LLM cache. Computed from a stable,
     * sorted serialization of symbols and edges so unchanged sources produce the same hash across runs.
     */
    public static String structuralDigest(final GraphModel model) {
        final var sb = new StringBuilder();
        final var symbols = new ArrayList<>(model.symbols());
        symbols.sort(
                Comparator.comparing(Symbol::fqn).thenComparing(Symbol::kind).thenComparing(Symbol::startLine));
        for (final var s : symbols) {
            sb.append(s.fqn())
                    .append(':')
                    .append(s.kind())
                    .append(':')
                    .append(s.file())
                    .append(':')
                    .append(s.startLine())
                    .append(':')
                    .append(s.endLine())
                    .append(':')
                    .append(s.signature())
                    .append('\n');
        }
        final var edges = new ArrayList<>(model.edges());
        edges.sort(Comparator.comparing(Edge::from).thenComparing(Edge::type).thenComparing(Edge::to));
        for (final var e : edges) {
            sb.append(e.from())
                    .append(':')
                    .append(e.type())
                    .append(':')
                    .append(e.to())
                    .append('\n');
        }
        return sha256(sb.toString());
    }

    /**
     * Render the full wiring card for a source file in Graft's format: header + optional file one-liner + one
     * bullet per symbol sorted by line then name.
     */
    private String render(final String relSource, final GraphModel model) {
        final var sb = new StringBuilder();
        sb.append("# ").append(relSource);
        for (final var slug : conceptUplinks(relSource)) {
            sb.append(" · [[").append(slug).append("]]");
        }
        sb.append('\n');
        final var fileOneLiner = firstLine(enrichment.fileSummary(relSource));
        if (fileOneLiner != null) {
            sb.append(fileOneLiner).append('\n');
        }
        final var symbols = new ArrayList<>(model.symbols());
        symbols.sort(Comparator.comparingInt(Symbol::startLine).thenComparing(Symbol::fqn));
        for (final var s : symbols) {
            sb.append("- ").append(simpleName(s.fqn()));
            if (s.kind() != null) {
                sb.append(" · ").append(s.kind().name().toLowerCase(Locale.ROOT));
            }
            if (s.startLine() > 0) {
                sb.append(" · L").append(s.startLine()).append('-').append(s.endLine());
            }
            final var oneLiner = firstLine(enrichment.symbolOneLiner(relSource, s.fqn()));
            if (oneLiner != null) {
                sb.append(" — ").append(oneLiner);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Render a concept-node card: slug/type/sources/links frontmatter plus a Summary/Related body.
     */
    private String renderConcept(final ConceptNode node) {
        final var sb = new StringBuilder();
        sb.append(FRONTMATTER_OPEN).append('\n');
        sb.append("name:").append(node.name()).append('\n');
        sb.append("slug:").append(node.slug()).append('\n');
        sb.append("type:").append(node.type()).append('\n');
        for (final var src : node.sources()) {
            sb.append("sources:").append(src).append('\n');
        }
        sb.append("sources_digest:").append(node.sourcesDigest()).append('\n');
        for (final var link : node.links()) {
            sb.append("links:")
                    .append(link.to())
                    .append(':')
                    .append(link.relation())
                    .append('\n');
        }
        sb.append("generator:1\n");
        sb.append(FRONTMATTER_OPEN).append('\n');
        sb.append("## Summary\n\n");
        sb.append(
                        node.summary() == null || node.summary().isBlank()
                                ? "_(no summary)_"
                                : node.summary().strip())
                .append('\n');
        if (!node.links().isEmpty()) {
            sb.append("\n## Related\n");
            final var links = new ArrayList<>(node.links());
            links.sort(Comparator.comparing(ConceptLink::to));
            for (final var link : links) {
                sb.append("- ")
                        .append(link.relation().replace('_', ' '))
                        .append(" [[")
                        .append(link.to())
                        .append("]]");
                if (link.description() != null && !link.description().isBlank()) {
                    sb.append(" — ").append(link.description());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Uplinks for a source file: the slugs of the concept cards (at the store root) that cite this file as a
     * source, sorted. Concept cards are identified by leading {@code ---} frontmatter, which wiring cards lack.
     */
    private List<String> conceptUplinks(final String relSource) {
        if (docsRoot == null || !Files.isDirectory(docsRoot)) {
            return List.of();
        }
        final var result = new ArrayList<String>();
        try (final var list = Files.list(docsRoot)) {
            final var files = list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("INDEX.md"))
                    .toList();
            for (final var f : files) {
                final String content;
                try {
                    content = Files.readString(f, StandardCharsets.UTF_8);
                } catch (final IOException e) {
                    continue;
                }
                final var props = parseFrontmatter(content);
                if (props == null) {
                    continue;
                }
                final var slug = props.get("slug");
                if (slug == null || slug.isBlank()) {
                    continue;
                }
                if (containsSource(props.get("sources"), relSource)) {
                    result.add(slug);
                }
            }
        } catch (final IOException e) {
            return List.of();
        }
        result.sort(String::compareTo);
        return result;
    }

    private static Map<String, String> parseFrontmatter(final String content) {
        if (!content.startsWith(FRONTMATTER_OPEN + "\n")) {
            return null;
        }
        final var end = content.indexOf("\n" + FRONTMATTER_OPEN + "\n", 4);
        if (end < 0) {
            return null;
        }
        final var header = content.substring(4, end);
        final var props = new LinkedHashMap<String, String>();
        for (final var line : header.split("\n")) {
            final var idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            final var k = line.substring(0, idx).strip();
            final var v = line.substring(idx + 1).strip();
            props.merge(k, v, (a, b) -> a + "\n" + b);
        }
        return props;
    }

    private static boolean containsSource(final String values, final String relSource) {
        if (values == null) {
            return false;
        }
        final var stem = SOURCE_EXTENSION.matcher(relSource).replaceFirst("");
        for (final var v : values.split("\n")) {
            final var c = v.strip();
            if (c.equals(relSource) || c.equals(stem)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(final String fqn) {
        var name = fqn.substring(fqn.lastIndexOf('.') + 1);
        final var idx = name.lastIndexOf('$');
        if (idx >= 0) {
            name = name.substring(idx + 1);
        }
        return name;
    }

    private static String firstLine(final String in) {
        final var v = in == null ? "" : in.strip();
        if (v.isEmpty()) {
            return null;
        }
        final var nl = v.indexOf('\n');
        final var line = (nl < 0 ? v : v.substring(0, nl)).strip();
        return line.isEmpty() ? null : line;
    }

    private static String oneLine(final String in) {
        final var v = in == null ? "" : in.strip();
        if (v.isEmpty()) {
            return "_(none)_";
        }
        return v.replace('\n', ' ').replace('\r', ' ');
    }

    private static String sha256(final String content) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            final var bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            final var sb = new StringBuilder(bytes.length * 2);
            for (final var b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private StringBuilder readBody(final Path cardFile) throws IOException {
        if (!Files.isRegularFile(cardFile)) {
            return null;
        }
        return new StringBuilder(new String(Files.readAllBytes(cardFile), StandardCharsets.UTF_8));
    }

    private void writeBody(final Path cardFile, final String content) throws IOException {
        Files.createDirectories(cardFile.getParent());
        Files.writeString(cardFile, content, StandardCharsets.UTF_8);
    }

    /**
     * A concept node grouping several source files (graft's curated node). Edges are typed links to other
     * concepts, referenced by slug.
     */
    public record ConceptNode(
            String name,
            String slug,
            String type,
            String summary,
            List<String> sources,
            String sourcesDigest,
            List<ConceptLink> links) {}

    public record ConceptLink(String to, String relation, String description) {}
}
