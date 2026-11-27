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
package io.yupiik.ia.agent.core.testutil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Warms the in-process {@code javac} (JDK compiler) once per JVM so that test
 * classes which compile Java sources during their tests do not each pay the
 * cold-start cost of loading the compiler toolchain. All callers share the same
 * {@code javax.tools} compiler instance and the same warmup output directory.
 */
public final class JavacWarmup {
    /** Shared compiler instance: created lazily on first warmup. */
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();

    private static final AtomicBoolean WARMED = new AtomicBoolean();

    private JavacWarmup() {
        // no-op
    }

    /**
     * Runs a trivial warmup compilation exactly once per JVM. Idempotent and
     * thread-safe: concurrent callers (classes run in parallel in the same fork)
     * all block on the single warmup and none re-compiles.
     */
    public static void warm() {
        if (!WARMED.compareAndSet(false, true)) {
            return;
        }
        final var out = Path.of("target", "javac-warmup");
        try {
            Files.createDirectories(out);
            final var unit = new SimpleJavaFileObject(URI.create("string:///Warmup.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                    return "public class Warmup { }";
                }
            };
            final var task = COMPILER.getTask(null, null, null, List.of("-d", out.toString()), null, List.of(unit));
            if (!task.call()) {
                throw new IllegalStateException("javac warmup compilation failed");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The shared JVM-wide compiler instance, for callers which compile in tests. */
    public static JavaCompiler compiler() {
        return COMPILER;
    }
}
