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
package io.yupiik.ia.agent.core.codegraph.spawn;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * The exact {@code .class} resources needed to run the javac-backed parser in a spawned JVM: the parser, its model,
 * the shared code-graph-index format and the helper entry point. This is the single source of truth for assembling the
 * runtime parser jar - GraalVM native images cannot enumerate embedded resources at runtime, so the list must be
 * explicit and is validated by {@code SpawnedJvmGraphParserTest} (which assembles and runs the jar from exactly these
 * entries). Each path is relative to the classpath root, with no leading slash.
 */
public final class ParserClassSet {

    private ParserClassSet() {
        // static utility
    }

    /** Read the raw {@code .class} bytes of a class in {@link #CLASSES} from the classpath/native resources. */
    public static InputStream resourceStream(final String classPath) {
        final var loader = Thread.currentThread().getContextClassLoader();
        return loader == null
                ? ParserClassSet.class.getResourceAsStream('/' + classPath)
                : loader.getResourceAsStream(classPath);
    }

    /**
     * Deterministic fingerprint of the exact bytes {@link #CLASSES} contributes to the parser jar, so the
     * runtime-assembled jar under {@code /tmp} can be invalidated when it no longer matches the bundled resources
     * (e.g. after a yca upgrade; {@code SpawnedJvmGraphParser.assembleParserJar} compares it and regenerates on
     * mismatch instead of reusing a stale jar forever). A SHA-256 of the concatenated class bytes plus their path,
     * read through the same {@code classResource} seam the assembly uses so it always matches what the jar would hold.
     */
    public static String fingerprint(final Function<String, InputStream> classResource) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            for (final var piece : CLASSES) {
                try (final var in = classResource.apply(piece)) {
                    if (in == null) {
                        throw new IOException("Missing bundled parser class resource: '" + piece + "'");
                    }
                    digest.update(piece.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(in.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint the parser class resources", e);
        }
    }

    /**
     * The class-file major version (e.g. 69 for Java 25) of the bundled parser classes, read from the first
     * {@code .class} resource in {@link #CLASSES}. This is the bytecode the spawned child JVM must be able to load, and
     * therefore the hard floor for any discovered JDK. Read statically from the embedded bytes (no JVM spawn), so it
     * always reflects whatever {@code --release} the classes were actually compiled with - never a hardcoded constant.
     */
    public static int classFileMajor(final Function<String, InputStream> classResource) {
        try (final var in = classResource.apply(CLASSES.get(0))) {
            if (in == null) {
                throw new IOException("Missing bundled parser class resource: '" + CLASSES.get(0) + "'");
            }
            try (final var data = new DataInputStream(in)) {
                final int magic = data.readInt();
                if (magic != 0xCAFEBABE) {
                    throw new IOException("Not a valid class file: '" + CLASSES.get(0) + "'");
                }
                data.readUnsignedShort(); // minor version
                return data.readUnsignedShort(); // major version
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot read the parser class-file version", e);
        }
    }

    /**
     * The Java feature level (e.g. 25) required to run the parser bytecode: {@code major - 44} (class-file major 52 is
     * Java 8, 69 is Java 25). A JDK below this cannot even load the helper jar.
     */
    public static int requiredFeatureVersion(final Function<String, InputStream> classResource) {
        return classFileMajor(classResource) - 44;
    }

    /** Ordered class-file resource paths that make up the spawned-JVM parser jar. */
    public static final List<String> CLASSES = List.of(
            // core: parser + model + store
            "io/yupiik/ia/agent/core/codegraph/GraphModel.class",
            "io/yupiik/ia/agent/core/codegraph/GraphModel$Edge.class",
            "io/yupiik/ia/agent/core/codegraph/GraphModel$EdgeType.class",
            "io/yupiik/ia/agent/core/codegraph/GraphModel$Kind.class",
            "io/yupiik/ia/agent/core/codegraph/GraphModel$Symbol.class",
            "io/yupiik/ia/agent/core/codegraph/GraphParser.class",
            "io/yupiik/ia/agent/core/codegraph/GraphStore.class",
            "io/yupiik/ia/agent/core/codegraph/JavaGraphParser.class",
            "io/yupiik/ia/agent/core/codegraph/JavaGraphParser$Collector.class",
            "io/yupiik/ia/agent/core/codegraph/JavaGraphParser$Collector$1.class",
            // core: index adapter + mapping + helper entry point
            "io/yupiik/ia/agent/core/codegraph/index/CodeGraphIndex.class",
            "io/yupiik/ia/agent/core/codegraph/index/CodeGraphIndex$Direction.class",
            "io/yupiik/ia/agent/core/codegraph/index/GraphToIndex.class",
            "io/yupiik/ia/agent/core/codegraph/helper/StructuralCodeGraphHelperMain.class",
            // shared framework-free code-graph-index (reader/writer + graph model)
            "io/yupiik/codegraph/index/CodeGraphIndex.class",
            "io/yupiik/codegraph/index/CodeGraphIndex$StringPool.class",
            "io/yupiik/codegraph/index/CodeGraphIndexWriter.class",
            "io/yupiik/codegraph/index/IndexGraph.class",
            "io/yupiik/codegraph/index/IndexSymbol.class",
            "io/yupiik/codegraph/index/IndexEdge.class");
}
