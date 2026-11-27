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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPI for structural parsers. Implementations are collected by the container (Fusion) and
 * aggregated by {@link StructuralCodeGraph}, which routes each source file to the parser that
 * handles its extension. Adding a new language only requires a new container bean implementing
 * this interface.
 */
public interface GraphParser {

    /**
     * @return the file extensions (without the leading dot) this parser handles, eg. {@code java}.
     */
    Set<String> extensions();

    /**
     * @return {@code true} when this parser needs a full JDK to operate (removing the check for a
     *         plain JRE), which the tooling must surface to the user.
     */
    default boolean jdkRequired() {
        return false;
    }

    /**
     * @return {@code true} when the parser can run in the current runtime. A parser may degrade
     *         gracefully (eg. return false when only a JRE is present) and be skipped.
     */
    default boolean available() {
        return true;
    }

    /**
     * Parse a single source file into a language-neutral model. Should be fast enough to call for a
     * single file during incremental re-indexing.
     */
    GraphModel parse(Path file);

    /**
     * Parse several source files into language-neutral models, batched for implementations that can
     * amortise work across files (eg. a compiler task that warms up once). The default routes to
     * {@link #parse(Path)} per file; parsers that support true batching (javac's {@code JavacTask},
     * a spawned-JVM helper) override it. The returned map must key on the exact input {@link Path}
     * and may omit files it could not parse.
     */
    default Map<Path, GraphModel> parseBatch(final List<Path> files) {
        final var out = new HashMap<Path, GraphModel>(files.size());
        for (final var file : files) {
            out.put(file, parse(file));
        }
        return out;
    }
}
