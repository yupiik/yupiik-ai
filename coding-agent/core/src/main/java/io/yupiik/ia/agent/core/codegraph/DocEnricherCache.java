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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent tier-2 LLM cache, keyed by content hashes so unchanged sources/symbols are never sent to the LLM
 * again across restarts. Serialized to {@code <storeRoot>/.graph/enrichment.json} with the same {@link JsonMapper}
 * used for the graph store ({@code @JsonModel} records, enums registered by the generated module).
 *
 * <p>Files map rel source path -> {@link FileEntry {digest, summary}}; symbols map {@code rel::fqn} ->
 * {@link SymbolEntry {digest, summary, crux}}. The digest for a file is {@link DocTreeWriter#structuralDigest}
 * of its model; the digest for a symbol is derived from that symbol's identity plus the file digest, so editing
 * the file or the symbol invalidates only that symbol's entry.
 */
public class DocEnricherCache {
    @JsonModel
    public record FileEntry(String digest, String summary) {}

    @JsonModel
    public record SymbolEntry(String digest, String summary, String crux) {}

    @JsonModel
    public record Data(Map<String, FileEntry> files, Map<String, SymbolEntry> symbols) {
        public Data {
            files = files == null ? Map.of() : files;
            symbols = symbols == null ? Map.of() : symbols;
        }
    }

    private final JsonMapper mapper;
    private final Path file;
    private final Map<String, FileEntry> files = new LinkedHashMap<>();
    private final Map<String, SymbolEntry> symbols = new LinkedHashMap<>();
    private boolean dirty;

    public DocEnricherCache(final JsonMapper mapper, final Path storeRoot) {
        this.mapper = mapper;
        this.file = WiringGraphWriter.pathFor(storeRoot).resolveSibling("enrichment.json");
        load();
    }

    public FileEntry file(final String rel) {
        return files.get(rel);
    }

    public Map<String, FileEntry> files() {
        return Map.copyOf(files);
    }

    public SymbolEntry symbol(final String key) {
        return symbols.get(key);
    }

    public void putFile(final String rel, final FileEntry entry) {
        files.put(rel, entry);
        dirty = true;
    }

    public void putSymbol(final String key, final SymbolEntry entry) {
        symbols.put(key, entry);
        dirty = true;
    }

    public void dropFile(final String rel) {
        final var removed = files.remove(rel);
        final var prefix = rel + "::";
        final var symbolsIt = symbols.keySet().iterator();
        var dropped = false;
        while (symbolsIt.hasNext()) {
            if (symbolsIt.next().startsWith(prefix)) {
                symbolsIt.remove();
                dropped = true;
            }
        }
        dirty |= removed != null || dropped;
    }

    public String symbolKey(final String rel, final String fqn) {
        return rel + "::" + fqn;
    }

    /**
     * A per-symbol cache key / body hash = the symbol's own structural digest (fqn, kind, file, start, end,
     * signature). Editing the symbol's definition changes its span/signature and therefore its hash, which
     * invalidates the cached deep enrichment and marks the wiring node {@code stale}.
     */
    public static String symbolBodyDigest(final GraphModel.Symbol symbol) {
        return DocTreeWriter.structuralDigest(new GraphModel("deep", List.of(symbol), List.of()));
    }

    public void flush() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            final var tmp = file.resolveSibling(
                    "enrichment.json." + ProcessHandle.current().pid() + ".tmp");
            Files.writeString(tmp, mapper.toString(new Data(files, symbols)), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            final var data = mapper.fromString(Data.class, Files.readString(file, StandardCharsets.UTF_8));
            if (data != null) {
                if (data.files() != null) {
                    files.putAll(data.files());
                }
                if (data.symbols() != null) {
                    symbols.putAll(data.symbols());
                }
            }
        } catch (final IOException | RuntimeException e) {
            // corrupted cache is not fatal: start empty
        }
    }
}
