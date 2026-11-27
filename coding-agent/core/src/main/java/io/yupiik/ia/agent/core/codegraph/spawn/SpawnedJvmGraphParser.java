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

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.codegraph.GraphModel;
import io.yupiik.ia.agent.core.codegraph.GraphParser;
import io.yupiik.ia.agent.core.codegraph.GraphStore;
import io.yupiik.ia.agent.core.codegraph.index.CodeGraphIndex;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.JavacConfiguration;
import io.yupiik.ia.agent.core.configuration.StructuralCodeGraphConfiguration;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link GraphParser} for the native image: no usable javac exists in-process (the runtime APIs throw even though
 * {@code ToolProvider} may report a compiler), so parsing is delegated to an external JVM that runs the javac-backed
 * {@code io.yupiik.ia.agent.core.codegraph.helper.StructuralCodeGraphHelperMain} against a JDK discovered from the
 * environment. The produced model is identical to the in-process {@code JavaGraphParser} (it is literally the same
 * parser, run on a real JVM).
 *
 * <p>The parser classes are shipped into the native image as {@code .class} byte resources (not jars) - embedded only
 * for the classes in {@link ParserClassSet} - and assembled at runtime into a throwaway jar under the scratch dir under
 * a process lock, so the child JVM can load them from a plain {@code -cp}. Only used when there is no in-process
 * compiler (native). {@link #available()} resolves once and caches: a JVM returns {@code false} forever (the in-process
 * javac parser wins); in native it locates + probes a JDK and assembles the parser jar, returning {@code false} (and
 * logging a single warning) when no usable JDK is found - which disables the Java graph feature for the session rather
 * than serving a silently-empty graph.</p>
 */
@ApplicationScoped
public class SpawnedJvmGraphParser implements GraphParser {

    private static final Logger LOGGER = Logger.getLogger(SpawnedJvmGraphParser.class.getName());
    private static final String HELPER_MAIN = "io.yupiik.ia.agent.core.codegraph.helper.StructuralCodeGraphHelperMain";
    private static final String FINGERPRINT_ATTRIBUTE = "X-Yca-Parser-Checksum";
    private static final String PARSER_JAR_NAME = "parser.jar";

    private final JdkLocator jdkLocator;
    private final Path scratchDir;
    private final Function<String, InputStream> classResource;
    private final long spawnTimeoutSeconds;
    private final String mainClass;
    private final List<String> classPieces;
    private List<String> javacOptions;

    private final ReentrantLock availabilityLock = new ReentrantLock();
    private final boolean hasCompilerInProcess;
    private boolean availabilityResolved;
    private boolean availableValue;
    private boolean warnedNoJdk;

    public SpawnedJvmGraphParser() {
        this(new JdkLocator());
    }

    /** DI constructor: config (via {@code yca.structural-code-graph.*}) drives the JDK override/pin. */
    public SpawnedJvmGraphParser(final CodingAgentConfiguration configuration) {
        this(locator(configuration == null ? null : configuration.structuralCodeGraph()));
        final var javac = configuration == null
                ? null
                : configuration.structuralCodeGraph().javac();
        this.javacOptions = javac == null ? JavacConfiguration.DEFAULT_JAVAC_OPTIONS : javac.effectiveJavacOptions();
    }

    private SpawnedJvmGraphParser(final JdkLocator jdkLocator) {
        this.jdkLocator = jdkLocator;
        this.scratchDir = defaultScratchDir();
        this.classResource = ParserClassSet::resourceStream;
        this.spawnTimeoutSeconds = 60L;
        this.mainClass = HELPER_MAIN;
        this.classPieces = ParserClassSet.CLASSES;
        this.javacOptions = JavacConfiguration.DEFAULT_JAVAC_OPTIONS;
        // On a JVM the in-process javac wins; in a native image the javac runtime APIs fail even when ToolProvider
        // reports a compiler, so engage the spawned-JVM fallback (see the imagecode property, as in OpenBrowser).
        this.hasCompilerInProcess = System.getProperty("org.graalvm.nativeimage.imagecode") == null;
    }

    private static JdkLocator locator(final StructuralCodeGraphConfiguration scg) {
        final var javac = scg == null ? null : scg.javac();
        final var jdkHome =
                javac == null || javac.jdkHome() == null || javac.jdkHome().isBlank() ? null : Path.of(javac.jdkHome());
        final Integer pin = javac == null ? null : javac.jdkFeature();
        return new JdkLocator(
                ParserClassSet::resourceStream,
                new JdkLocator.Settings(jdkHome, pin, Path.of(System.getProperty("user.dir", "."))));
    }

    @Override
    public Set<String> extensions() {
        return Set.of("java");
    }

    @Override
    public boolean jdkRequired() {
        return false;
    }

    @Override
    public boolean available() {
        if (!availabilityResolved) {
            final var lock = availabilityLock;
            lock.lock();
            try {
                if (!availabilityResolved) {
                    if (hasCompilerInProcess) {
                        // a JVM already has javac in-process; the JavaGraphParser handles it
                        availableValue = false;
                    } else {
                        final var javaLauncher = jdkLocator.locate();
                        if (javaLauncher == null) {
                            warnNoJdk();
                            availableValue = false;
                        } else {
                            try {
                                assembleParserJar();
                                availableValue = true;
                            } catch (final IOException e) {
                                LOGGER.log(
                                        Level.WARNING,
                                        e,
                                        () -> "Cannot assemble the spawned-JVM parser jar; Java graph disabled: "
                                                + e.getMessage());
                                availableValue = false;
                            }
                        }
                    }
                    availabilityResolved = true;
                }
            } finally {
                lock.unlock();
            }
        }
        return availableValue;
    }

    private void warnNoJdk() {
        if (!warnedNoJdk) {
            warnedNoJdk = true;
            LOGGER.warning("No JDK exposing a Java compiler new enough to run the parser was found (checked JAVA_HOME, "
                    + "PATH and common install locations against the parser bytecode level); the native "
                    + "structural code graph is disabled for this session. Parse output will be empty until "
                    + "a compatible JDK is provided (set codegraph.jdkPath).");
        }
    }

    @Override
    public GraphModel parse(final Path file) {
        return parseBatch(List.of(file)).getOrDefault(file, GraphModel.empty("java"));
    }

    @Override
    public Map<Path, GraphModel> parseBatch(final List<Path> files) {
        if (files.isEmpty()) {
            return Map.of();
        }
        if (!available()) {
            return empty(files);
        }
        try {
            final var javaLauncher = jdkLocator.locate();
            if (javaLauncher == null) {
                return empty(files);
            }
            for (var attempt = 0; attempt < 2; attempt++) {
                try {
                    return parseBatchOnce(files, javaLauncher);
                } catch (final IOException e) {
                    // Scratch files can be removed while availability remains cached. Only check after failure.
                    if (attempt != 0 || !Files.notExists(scratchDir.resolve(PARSER_JAR_NAME))) {
                        throw e;
                    }
                    assembleParserJar();
                }
            }
            return empty(files);
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.WARNING, e, () -> "Spawned-JVM parser failed: " + e.getMessage());
            return empty(files);
        }
    }

    private Map<Path, GraphModel> parseBatchOnce(final List<Path> files, final Path javaLauncher)
            throws IOException, InterruptedException {
        final var tmpOut = Files.createTempFile(scratchDir, "scg-", ".bin");
        try {
            final var command = new ArrayList<String>();
            command.add(javaLauncher.toAbsolutePath().toString());
            command.addAll(javacOptions); // JVM startup flags before -cp (short-lived child: cut boot cost)
            command.add("-cp");
            command.add(scratchDir.resolve(PARSER_JAR_NAME).toString());
            command.add(mainClass);
            command.add(tmpOut.toString());
            for (final var file : files) {
                command.add(file.toAbsolutePath().toString());
            }
            final var process = new ProcessBuilder(command).start();
            final boolean done = process.waitFor(spawnTimeoutSeconds, TimeUnit.SECONDS);
            final String err = new String(process.getErrorStream().readAllBytes(), UTF_8);
            if (!done) {
                process.destroyForcibly();
                warnSkipped("timed out after " + spawnTimeoutSeconds + "s" + suffix(err));
                return empty(files);
            }
            if (process.exitValue() != 0) {
                throw new IOException("the parser JVM exited with " + process.exitValue() + suffix(err));
            }
            if (!Files.isRegularFile(tmpOut) || Files.size(tmpOut) == 0) {
                throw new IOException("the parser JVM produced no output" + suffix(err));
            }
            return readOutput(tmpOut);
        } finally {
            Files.deleteIfExists(tmpOut);
        }
    }

    private static String suffix(final String err) {
        final var t = err == null ? "" : err.strip();
        return t.isEmpty() ? "" : "; child stderr: " + t;
    }

    private void warnSkipped(final String reason) {
        LOGGER.warning(
                "Java parser sub-process unavailable (" + reason + "); serving an empty Java graph for the batch.");
    }

    /** Reads the length-framed per-file output written by {@code StructuralCodeGraphHelperMain}. */
    private Map<Path, GraphModel> readOutput(final Path out) throws IOException {
        final var result = new LinkedHashMap<Path, GraphModel>();
        try (final var in = new DataInputStream(Files.newInputStream(out))) {
            final int count = in.readInt();
            for (var i = 0; i < count; i++) {
                final var nameBytes = new byte[in.readInt()];
                in.readFully(nameBytes);
                final var name = Path.of(new String(nameBytes, UTF_8));
                final var indexBytes = new byte[in.readInt()];
                in.readFully(indexBytes);
                final GraphStore store = CodeGraphIndex.load(indexBytes).toStore();
                result.put(name, new GraphModel("java", store.symbols(), store.edges()));
            }
        }
        return result;
    }

    /** Assembles the throwaway parser jar from the bundled {@code .class} resources, under the generation lock. */
    private void assembleParserJar() throws IOException {
        Files.createDirectories(scratchDir);
        final var lockFile = scratchDir.resolve("generate.scgn.lock");
        try (final var ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                final var lock = ch.lock()) {
            final var target = scratchDir.resolve(PARSER_JAR_NAME);
            final var current = ParserClassSet.fingerprint(classResource);
            if (isUpToDate(target, current)) {
                return;
            }
            final var tmp = target.resolveSibling(target.getFileName() + ".tmp");
            final var manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue(FINGERPRINT_ATTRIBUTE, current);
            try (final var out = new JarOutputStream(Files.newOutputStream(tmp), manifest)) {
                for (final var piece : classPieces) {
                    try (final var from = classResource.apply(piece)) {
                        if (from == null) {
                            throw new IOException("Missing bundled parser class resource: '" + piece + "'");
                        }
                        final var data = from.readAllBytes();
                        out.putNextEntry(new JarEntry(piece));
                        out.write(data);
                        out.closeEntry();
                    }
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * True when {@code parser.jar} already exists, is non-empty, and its manifest carries a {@value #FINGERPRINT_ATTRIBUTE}
     * matching the current resource fingerprint - i.e. it was assembled from exactly the class bytes we would assemble
     * now. Any yca upgrade or class-set change produces a different fingerprint and forces a regeneration, preventing a
     * stale (older-build) jar from being reused.
     */
    private boolean isUpToDate(final Path target, final String current) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) == 0) {
            return false;
        }
        try (final var jar = new JarFile(target.toFile())) {
            final var manifest = jar.getManifest();
            return manifest != null
                    && current.equals(manifest.getMainAttributes().getValue(FINGERPRINT_ATTRIBUTE));
        }
    }

    private static Path defaultScratchDir() {
        final var base = System.getenv("TMPDIR");
        return Path.of(base == null || base.isBlank() ? "/tmp" : base, "yca", "codegraph-parser");
    }

    private static Map<Path, GraphModel> empty(final List<Path> files) {
        final var out = new HashMap<Path, GraphModel>(files.size());
        for (final var file : files) {
            out.put(file, GraphModel.empty("java"));
        }
        return out;
    }
}
