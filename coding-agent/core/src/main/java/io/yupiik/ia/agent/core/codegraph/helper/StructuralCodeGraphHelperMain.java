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
package io.yupiik.ia.agent.core.codegraph.helper;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.codegraph.index.CodeGraphIndexWriter;
import io.yupiik.ia.agent.core.codegraph.GraphModel;
import io.yupiik.ia.agent.core.codegraph.GraphStore;
import io.yupiik.ia.agent.core.codegraph.JavaGraphParser;
import io.yupiik.ia.agent.core.codegraph.index.GraphToIndex;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone entry point run inside an external JVM by the native {@code SpawnedJvmGraphParser} (native images have
 * no {@code ToolProvider} compiler, so they delegate the javac-backed parse to a real JVM).
 *
 * <p>Protocol: {@code <out.bin> <file>...} on the command line. Reads every listed {@code .java} file, runs
 * {@link JavaGraphParser#parseBatch}, and writes to {@code out.bin} a length-framed sequence of per-file
 * {@code code-graph-index} payloads so the parent can re-key each parse back to its absolute source path exactly
 * (edges stay scoped to the file that produced them):
 *
 * <pre>
 *   int entryCount
 *   per entry:
 *     int nameUtf8Len ; nameUtf8                // absolute source path
 *     int indexLen    ; indexBytes              // CodeGraphIndexWriter bytes for that file's GraphStore
 * </pre>
 *
 * Exits non-zero on any failure so the parent surfaces a clear native-degradation notice instead of a silent empty
 * graph.</p>
 */
public final class StructuralCodeGraphHelperMain {

    private StructuralCodeGraphHelperMain() {
        // launcher
    }

    static void main(final String[] args) {
        final int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** Testable body; returns exit code (0 = ok). */
    static int run(final String[] args) {
        if (args.length < 2) {
            System.err.println("usage: <out.bin> <file>...");
            return 2;
        }
        final Path out = Path.of(args[0]);
        final var files = new ArrayList<Path>(args.length - 1);
        for (var i = 1; i < args.length; i++) {
            files.add(Path.of(args[i]));
        }
        try {
            final Map<Path, GraphModel> parsed = new JavaGraphParser().parseBatch(List.copyOf(files));
            try (final var data = new DataOutputStream(Files.newOutputStream(out))) {
                data.writeInt(parsed.size());
                for (final var entry : parsed.entrySet()) {
                    final var name = entry.getKey()
                            .toAbsolutePath()
                            .normalize()
                            .toString()
                            .getBytes(UTF_8);
                    data.writeInt(name.length);
                    data.write(name);
                    final var file = entry.getValue() == null ? GraphStore.empty() : toStore(entry.getValue());
                    final var bytes = CodeGraphIndexWriter.toBytes(GraphToIndex.toIndex(file));
                    data.writeInt(bytes.length);
                    data.write(bytes);
                }
                data.flush();
            }
            return 0;
        } catch (final Throwable t) {
            t.printStackTrace(System.err);
            return 1;
        }
    }

    /** A single-file store: the parse result with that file's symbols/edges (no fingerprints). */
    private static GraphStore toStore(final GraphModel model) {
        return new GraphStore(
                List.copyOf(model.symbols()),
                List.copyOf(model.edges()),
                new LinkedHashMap<>(),
                System.currentTimeMillis());
    }
}
