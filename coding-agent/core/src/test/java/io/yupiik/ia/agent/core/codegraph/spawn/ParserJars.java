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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Test-only factory that mirrors {@link SpawnedJvmGraphParser}'s runtime assembly: a {@code classResource} seam which
 * serves each {@code .class} path in {@link ParserClassSet#CLASSES} from the compiled {@code target/classes} trees, so
 * the spawned-JVM path can be exercised on a JVM build without a native image. Keeping the exact same class set as
 * production also makes {@code SpawnedJvmGraphParserTest} the guard that {@code ParserClassSet} is complete: if a class
 * the helper needs is missing from the set, the child JVM cannot load it and the test fails.
 */
final class ParserJars {

    private ParserJars() {}

    /** Returns a {@code classResource} seam reading {@code ParserClassSet.CLASSES} from the repo's compiled classes. */
    static Function<String, InputStream> classResource(final Path coreClasses, final Path cgiClasses) {
        return path -> {
            final Path base = path.startsWith("io/yupiik/codegraph/") ? cgiClasses : coreClasses;
            final var file = base.resolve(path);
            if (!Files.isRegularFile(file)) {
                throw new UncheckedIOException(new IOException("Missing compiled class " + path + " under " + base
                        + "; build the project first (mvn install)"));
            }
            try {
                return Files.newInputStream(file);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /** The curated class list used by both tests and the runtime assembly. */
    static List<String> classes() {
        return ParserClassSet.CLASSES;
    }
}
