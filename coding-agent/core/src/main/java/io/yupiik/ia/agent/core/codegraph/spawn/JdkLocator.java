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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Locates the {@code java} launcher(s) a native image spawns to run the javac-backed parse. On a JVM there is already
 * an in-process compiler so this is never used; in a native image there is none, so the launchers are resolved eagerly
 * (cached) before deciding whether the spawned parser is {@link SpawnedJvmGraphParser#available}.
 *
 * <p>Two distinct JDK roles are modelled, because a project can target a different Java than the internal parser jar
 * was compiled with (e.g. a Java 21 project while the parser runs on Java 25):</p>
 *
 * <ul>
 *   <li><b>{@link #locateJarJdk()}</b> - the JVM that runs the bundled parser jar. Its floor (V1) is the class-file
 *       level of the parser bytecode, read statically from the embedded {@code .class} bytes (see
 *       {@link ParserClassSet#requiredFeatureVersion}). A JDK below it cannot even load the helper.</li>
 *   <li><b>{@link #locateProjectJdk()}</b> - the JVM that parses project sources. Its floor (V2) is the project source
 *       level, detected best-effort from {@code yca.structuralCodeGraph.javac.jdkFeature}, a {@code .sdkmanrc}/
 *       {@code .java-version} in the project/ancestors, or the {@code maven-compiler-plugin} {@code release}/{@code
 *       source} of the effective {@code pom.xml}.</li>
 * </ul>
 *
 * <p>Because javac today runs in-process with the parser, the single child JVM spawned by the parser must satisfy both
 * roles: {@link #locate()} returns the one that covers both (the project JDK when it is newer than the parser bytecode,
 * otherwise the parser JDK). Each role selects an exact match for its floor first, then the closest <em>higher</em>
 * version, then the highest version that still clears the floor.</p>
 *
 * <p>Candidates come from {@code yca.structuralCodeGraph.javac.jdkHome} (explicit override, still validated against the
 * parser floor), {@code JAVA_HOME}, {@code PATH}, Maven toolchains (version trusted from {@code toolchains.xml}, no
 * probe), then common install roots (per-platform). Each non-toolchain candidate is probed once - a single child spawn
 * reports both compiler presence and the class-file major it can load, so a JRE or a too-old JDK is rejected.</p>
 */
public class JdkLocator {

    private static final Logger LOGGER = Logger.getLogger(JdkLocator.class.getName());
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final Pattern SDKMAN_JAVA = Pattern.compile("java\\s*=\\s*(.+)$");
    private static final Pattern COMPILER_PLUGIN = Pattern.compile(
            "<artifactId>\\s*maven-compiler-plugin\\s*</artifactId>(.*?)(?:</plugin>|$)", Pattern.DOTALL);
    private static final Pattern PLUGIN_CONFIGURATION =
            Pattern.compile("<configuration>(.*?)</configuration>", Pattern.DOTALL);
    private static final Pattern COMPILER_PROPERTY =
            Pattern.compile("<\\s*maven\\.compiler\\.(?:release|source)\\s*>\\s*([0-9]+)\\s*<");
    private static final Pattern RELEASE = Pattern.compile("<\\s*release\\s*>\\s*([0-9]+)\\s*<");
    private static final Pattern SOURCE = Pattern.compile("<\\s*source\\s*>\\s*([0-9]+)\\s*<");
    private static final Pattern JAVA_VERSION = Pattern.compile("(?:1\\.)?([0-9]+)");
    private static final Pattern INTEGER = Pattern.compile("[0-9]+");
    private static final Pattern TOOLCHAIN = Pattern.compile("<toolchain>(.*?)</toolchain>", Pattern.DOTALL);
    private static final Pattern TOOLCHAIN_TYPE = Pattern.compile("<type>(.*?)</type>", Pattern.DOTALL);
    private static final Pattern TOOLCHAIN_VERSION = Pattern.compile("<version>(.*?)</version>", Pattern.DOTALL);
    private static final Pattern TOOLCHAIN_HOME = Pattern.compile("<jdkHome>(.*?)</jdkHome>", Pattern.DOTALL);

    private final Function<String, InputStream> classResource;
    private final Settings settings;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean resolved;
    private volatile Path jarJdk;
    private volatile Path projectJdk;

    /**
     * Normalized {@code os.name}, read once (the OS does not change at runtime) for every platform test in this
     * class, rather than re-reading the system property on each call.
     */
    private final String osName;

    private final Properties probeCache = new Properties();
    private boolean probeCacheLoaded;

    /**
     * Injectable settings for the locator. {@code projectDir} anchors the {@code .sdkmanrc}/{@code .java-version} scan;
     * {@code jdkHome} is the configured {@code java} binary or JDK home; {@code pinnedFeature} is the configured
     * override for the project source level (V2).
     */
    public record Settings(Path jdkHome, Integer pinnedFeature, Path projectDir) {}

    /** A candidate {@code java} with the class-file major it can load (measured by the probe, or declared by a toolchain). */
    public record Candidate(Path path, int classFileMajor) {}

    /** Runtime defaults (no configured keys, current working directory as the project anchor). */
    public JdkLocator() {
        this(ParserClassSet::resourceStream, new Settings(null, null, Path.of(System.getProperty("user.dir", "."))));
    }

    /** Main seam for tests and the {@code CodingAgentConfiguration} injection: controls the class bytes (V1), the
     * settings and the discovery without touching the real environment. */
    public JdkLocator(final Function<String, InputStream> classResource, final Settings settings) {
        this.classResource = classResource;
        this.settings = settings;
        this.osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    /** The JVM that runs the parser jar (floor = V1), or {@code null} when none clears the parser floor. */
    public Path locateJarJdk() {
        ensureResolved();
        return jarJdk;
    }

    /** The JVM that parses project sources (floor = V2), or {@code null} when none clears the project floor. */
    public Path locateProjectJdk() {
        ensureResolved();
        return projectJdk;
    }

    /**
     * The single JVM the parser spawns: the project JDK when the project source level is higher than the parser
     * bytecode (it covers both), otherwise the parser JDK (which, being at least V1, also covers a project at or below
     * that level). Falls back to the parser JDK when no JDK reaches the (higher) project level.
     */
    public Path locate() {
        final int v1 = ParserClassSet.requiredFeatureVersion(classResource);
        final int v2 = projectFeature().orElse(v1);
        if (v2 > v1) {
            final Path project = locateProjectJdk();
            if (project != null) {
                return project;
            }
        }
        return locateJarJdk();
    }

    private void ensureResolved() {
        if (resolved) {
            return;
        }
        lock.lock();
        try {
            if (resolved) {
                return;
            }
            resolve();
            resolved = true;
        } finally {
            lock.unlock();
        }
    }

    private void resolve() {
        final int v1 = ParserClassSet.requiredFeatureVersion(classResource);
        final int v2 = projectFeature().orElse(v1);
        LOGGER.fine(() -> "Spawned-JVM parser: parser bytecode floor=" + v1 + ", project source level=" + v2);

        final var launchers = new ArrayList<Path>();
        final var seen = new LinkedHashSet<Path>();
        final var names = launcherNames();
        if (settings.jdkHome() != null) {
            addHomeJava(launchers, seen, settings.jdkHome(), names);
            addExec(launchers, seen, settings.jdkHome());
        }
        addEnvironment(launchers, seen, names);
        addCommonRoots(launchers, seen, names);

        final var probed = new ArrayList<Candidate>();
        for (final var launcher : launchers) {
            final var candidate = cachedProbe(launcher);
            if (candidate != null) {
                probed.add(candidate);
            }
        }
        addToolchains(probed, names); // toolchain versions are trusted, no probe

        final Candidate jar = bestFor(probed, v1);
        final Candidate project = bestFor(probed, v2);
        jarJdk = jar == null ? null : jar.path();
        projectJdk = project == null ? null : project.path();
        if (jarJdk == null) {
            LOGGER.warning(
                    "No JDK exposing a Java compiler new enough to run the parser was found (checked " + summary()
                            + " against the parser bytecode floor "
                            + v1 + "); the native structural code graph is disabled for this session. Parse output "
                            + "will be empty until a compatible JDK or a 'yca.structuralCodeGraph.javac.jdkHome' is provided.");
        }
    }

    private Candidate cachedProbe(final Path launcher) {
        final var normalized = launcher.toAbsolutePath().normalize();
        final var release = normalized.getParent() == null
                ? null
                : normalized.getParent().getParent().resolve("release");
        final var stamp = fileStamp(normalized) + ":" + fileStamp(release);
        lock.lock();
        try {
            if (!probeCacheLoaded) {
                probeCacheLoaded = true;
                final var cache = settings.projectDir() == null
                        ? null
                        : settings.projectDir().resolve(".yca/generated/jvm.probe.cache");
                if (cache != null && Files.isRegularFile(cache)) {
                    try (final var in = Files.newInputStream(cache)) {
                        probeCache.load(in);
                    } catch (final IOException ignored) {
                        probeCache.clear();
                    }
                }
                if (!"1".equals(probeCache.getProperty("_format"))) {
                    probeCache.clear();
                    probeCache.setProperty("_format", "1");
                }
            }
            final var key = normalized.toString();
            final var value = probeCache.getProperty(key);
            if (value != null && value.startsWith(stamp + ":")) {
                try {
                    return new Candidate(normalized, Integer.parseInt(value.substring(stamp.length() + 1)));
                } catch (final NumberFormatException ignored) {
                    probeCache.remove(key);
                }
            }
        } finally {
            lock.unlock();
        }
        final var candidate = probe(normalized);
        if (candidate != null && candidate.classFileMajor() > 0) {
            lock.lock();
            try {
                probeCache.setProperty(normalized.toString(), stamp + ":" + candidate.classFileMajor());
                probeCache.setProperty("_format", "1");
                final var cache = settings.projectDir() == null
                        ? null
                        : settings.projectDir().resolve(".yca/generated/jvm.probe.cache");
                if (cache != null) {
                    try {
                        Files.createDirectories(cache.getParent());
                        final var tmp = Files.createTempFile(cache.getParent(), "jvm-probe-", ".tmp");
                        try (final var out = Files.newOutputStream(tmp)) {
                            probeCache.store(out, "JDK probe cache");
                        }
                        try {
                            Files.move(tmp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } catch (final IOException e) {
                            Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (final IOException e) {
                        LOGGER.fine("Cannot write JDK probe cache: " + e.getMessage());
                    }
                }
            } finally {
                lock.unlock();
            }
        }
        return candidate;
    }

    private static String fileStamp(final Path file) {
        if (file == null) return "missing";
        try {
            final var metadata =
                    Files.size(file) + "-" + Files.getLastModifiedTime(file).toMillis();
            return file.getFileName().toString().equals("release")
                    ? metadata + "-"
                            + Integer.toHexString(Files.readString(file).hashCode())
                    : metadata;
        } catch (final IOException e) {
            return "missing";
        }
    }

    // ---- role selection -----------------------------------------------------

    /** Best candidate >= {@code required}: exact match first, then the closest higher version, then the highest
     * available (never below the required level). */
    private static Candidate bestFor(final List<Candidate> candidates, final int required) {
        Candidate exact = null;
        Candidate closestHigher = null;
        Candidate highest = null;
        for (final var candidate : candidates) {
            final int lvl = featureLevel(candidate.classFileMajor());
            if (lvl < required) {
                continue; // cannot serve this role - never usable
            }
            if (lvl == required && exact == null) {
                exact = candidate;
            } else if (lvl > required
                    && (closestHigher == null || lvl < featureLevel(closestHigher.classFileMajor()))) {
                closestHigher = candidate;
            }
            if (highest == null || lvl > featureLevel(highest.classFileMajor())) {
                highest = candidate;
            }
        }
        return exact != null ? exact : closestHigher != null ? closestHigher : highest;
    }

    private static int featureLevel(final int classFileMajor) {
        return classFileMajor - 44;
    }

    // ---- V2 detection (config pin, then rc files, then maven-compiler-plugin) ----

    /**
     * The project source level (V2): the configured {@code jdkFeature} pin first, then
     * {@code .sdkmanrc}/{@code .java-version} in the project/ancestors, then the effective
     * {@code maven-compiler-plugin} / compiler properties in a {@code pom.xml}. Empty when undecidable.
     */
    protected Optional<Integer> projectFeature() {
        if (settings.pinnedFeature() != null) {
            return Optional.of(settings.pinnedFeature());
        }
        return readRcFiles(settings.projectDir()).or(() -> readMavenCompilerRelease(settings.projectDir()));
    }

    private static Optional<Integer> readRcFiles(final Path projectDir) {
        for (Path dir = projectDir; dir != null; dir = dir.getParent()) {
            final var javaVersion = dir.resolve(".java-version");
            if (Files.isRegularFile(javaVersion)) {
                final var v = parseJavaVersion(readAll(javaVersion));
                if (v.isPresent()) {
                    return v;
                }
            }
            final var sdkman = dir.resolve(".sdkmanrc");
            if (Files.isRegularFile(sdkman)) {
                for (final var line : LINE_BREAK.split(readAll(sdkman))) {
                    final var m = SDKMAN_JAVA.matcher(line.trim());
                    if (m.find()) {
                        final var value = m.group(1).trim();
                        if (!value.startsWith("java.")) { // a plain number or 25.0.4-tem is a numeric level
                            final var v = parseJavaVersion(value);
                            if (v.isPresent()) {
                                return v;
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the Java source level from a {@code pom.xml} in the project/ancestors: the top-level
     * {@code <maven.compiler.release>/<maven.compiler.source>} properties, else the {@code maven-compiler-plugin}
     * {@code <release>} (preferred) or {@code <source>} configuration.
     */
    private static Optional<Integer> readMavenCompilerRelease(final Path projectDir) {
        for (Path dir = projectDir; dir != null; dir = dir.getParent()) {
            final var pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            final var content = readAll(pom);
            // top-level compiler property, e.g. <maven.compiler.release>25</...>
            final var property = tagValue(content, COMPILER_PROPERTY);
            if (property.isPresent()) {
                return property;
            }
            // inside the <maven-compiler-plugin> block, <release> wins over <source>/<target>
            final var block = pluginBlock(content);
            if (block.isPresent()) {
                final var plugin = block.get();
                final var release = tagValue(plugin, RELEASE);
                if (release.isPresent()) {
                    return release;
                }
                final var source = tagValue(plugin, SOURCE);
                if (source.isPresent()) {
                    return source;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> pluginBlock(final String content) {
        final var m = COMPILER_PLUGIN.matcher(content);
        if (!m.find()) {
            return Optional.empty();
        }
        final var rest = m.group(1);
        final var cfg = PLUGIN_CONFIGURATION.matcher(rest);
        return cfg.find() ? Optional.ofNullable(cfg.group(1)) : Optional.empty();
    }

    private static Optional<Integer> tagValue(final String content, final Pattern tag) {
        final var m = tag.matcher(content);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private static Optional<Integer> parseJavaVersion(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        final var m = JAVA_VERSION.matcher(raw.strip());
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private static String readAll(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            return "";
        }
    }

    // ---- candidate probing -------------------------------------------------

    /** Runs a minimal probe in {@code java}: compiler presence and the class-file major the JVM can load. */
    protected Candidate probe(final Path java) {
        Path probeFile = null;
        try {
            probeFile = Files.createTempFile("yca-jdkprobe-", ".java");
            final String source = "class YcaJdkProbe { public static void main(String[] a) { "
                    + "System.out.println(javax.tools.ToolProvider.getSystemJavaCompiler() == null ? -1 : 1); "
                    + "System.out.println(System.getProperty(\"java.class.version\")); } }";
            Files.writeString(probeFile, source);
            // --source runs the single-file launcher so no output dir or compile stage is needed. Every JDK with a
            // compiler supports source 21 in recent versions; an older one falls back to source 11.
            final var out21 = runProbe(java, "21", probeFile);
            if (!out21.isEmpty()) {
                return new Candidate(java, parseClassFileMajor(out21));
            }
            final var out11 = runProbe(java, "11", probeFile);
            if (!out11.isEmpty()) {
                return new Candidate(java, parseClassFileMajor(out11));
            }
            return null;
        } catch (final IOException e) {
            LOGGER.fine(() -> "Cannot probe JDK candidate " + java + ": " + e.getMessage());
            return null;
        } finally {
            try {
                if (probeFile != null) {
                    Files.deleteIfExists(probeFile);
                }
            } catch (final IOException ignored) {
                // best effort
            }
        }
    }

    /** Runs the probe at the given source level; returns the trimmed output, or the empty string when unusable. */
    private String runProbe(final Path java, final String sourceLevel, final Path probeFile) {
        try {
            final var process = new ProcessBuilder(
                            java.toAbsolutePath().toString(),
                            "--source",
                            sourceLevel,
                            probeFile.toAbsolutePath().toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            final var out = readFully(process.getInputStream()).trim();
            return out.isBlank() ? "" : out;
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    /** The probe prints compiler presence ({@code 1}) then {@code java.class.version} (e.g. {@code 69.0} on JDK 25).
     * Returns the class-file major of the JVM, or {@code -1} when there is no usable compiler. */
    private static int parseClassFileMajor(final String output) {
        final var lines = LINE_BREAK.split(output);
        if (lines.length < 2 || !"1".equals(lines[0].strip())) {
            return -1;
        }
        final var m = INTEGER.matcher(lines[1]);
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }

    private static String readFully(final InputStream in) throws IOException {
        final var buf = new ByteArrayOutputStream();
        final var chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) >= 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    // ---- candidate discovery -------------------------------------------------

    private void addEnvironment(final List<Path> out, final LinkedHashSet<Path> seen, final List<String> names) {
        final var javaHome = env("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            addHomeJava(out, seen, Path.of(javaHome), names);
        }
        final var path = env("PATH");
        if (path != null) {
            for (final var dir : path.split(File.pathSeparator)) {
                if (dir != null && !dir.isBlank()) {
                    addExec(out, seen, Path.of(dir, "java"));
                }
            }
        }
    }

    /** Maven toolchain JDKs, declared with a version, so no probe is needed (the class-file major is derived). */
    private void addToolchains(final List<Candidate> out, final List<String> names) {
        for (final var toolchain : toolchainHomes()) {
            final var javaBin = jdkBin(toolchain.path(), names);
            if (javaBin.isPresent()) {
                out.add(new Candidate(javaBin.get().normalize(), toolchain.classFileMajor()));
            }
        }
    }

    private void addCommonRoots(final List<Path> out, final LinkedHashSet<Path> seen, final List<String> names) {
        for (final var root : commonRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (final var children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .sorted(Comparator.reverseOrder())
                        .forEach(sub -> addHomeJava(out, seen, sub, names));
            } catch (final IOException ignored) {
                // best effort
            }
        }
    }

    /** Adds the {@code java} launcher for a JDK home (a directory), using the platform's launcher names. */
    private static void addHomeJava(
            final List<Path> out, final LinkedHashSet<Path> seen, final Path home, final List<String> names) {
        if (home == null) {
            return;
        }
        for (final var name : names) {
            final var bin = home.resolve("bin").resolve(name);
            if (Files.isRegularFile(bin) && Files.isExecutable(bin)) {
                addExec(out, seen, bin);
                return;
            }
        }
    }

    /** Registers an executable {@code java} binary (de-duplicated by absolute path). */
    private static void addExec(final List<Path> out, final LinkedHashSet<Path> seen, final Path bin) {
        final var abs = bin.toAbsolutePath().normalize();
        if (Files.isRegularFile(abs) && Files.isExecutable(abs) && seen.add(abs)) {
            out.add(abs);
        }
    }

    private static Optional<Path> jdkBin(final Path home, final List<String> names) {
        for (final var name : names) {
            final var bin = home.resolve("bin").resolve(name);
            if (Files.isRegularFile(bin)) {
                return Optional.of(bin);
            }
        }
        return Optional.empty();
    }

    /** The {@code java} launcher file names for the current platform: {@code ["java"]} elsewhere, {@code ["java",
     * "java.cmd", "java.exe"]} on Windows (sdkman/Installer produce {@code java.exe}, older setups {@code java.cmd}). */
    protected List<String> launcherNames() {
        return isWindows() ? List.of("java", "java.cmd", "java.exe") : List.of("java");
    }

    /**
     * Common JDK install roots, selected per platform so a non-Windows OS never scans Windows paths and vice versa.
     * The {@code HOME}-relative user roots ({@code ~/.sdkman}, {@code ~/.jdks}) apply on any non-Windows OS; the
     * absolute system roots differ between macOS ({@code /Library/Java/JavaVirtualMachines}) and Linux
     * ({@code /usr/lib/jvm}, {@code /usr/java}, {@code /opt}).
     */
    protected List<Path> commonRoots() {
        final var roots = new ArrayList<Path>();
        if (isWindows()) {
            roots.add(Path.of("C:\\Program Files\\Java"));
            roots.add(Path.of("C:\\Program Files\\Eclipse Adoptium"));
            roots.add(Path.of("C:\\Program Files\\Microsoft"));
            roots.add(Path.of("C:\\Program Files\\Amazon Corretto"));
            return roots;
        }
        final var home = env("HOME");
        if (home != null) {
            roots.add(Path.of(home, ".sdkman", "candidates", "java"));
            roots.add(Path.of(home, ".jdks"));
        }
        if (isMac()) {
            roots.add(Path.of("/Library/Java/JavaVirtualMachines"));
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/usr/java"));
            roots.add(Path.of("/opt"));
        }
        return roots;
    }

    protected boolean isWindows() {
        return osName.contains("win");
    }

    protected boolean isMac() {
        return osName.contains("mac") || osName.contains("darwin");
    }

    /** Environment seam, overridable so tests do not depend on the machine's environment. */
    protected String env(final String name) {
        return System.getenv(name);
    }

    // ---- Maven toolchains ----------------------------------------------------

    /**
     * Parses {@code toolchains.xml} for {@code <type>jdk</type>} toolchains, returning each declared
     * {@code <jdkHome>} mapped to a class-file major derived from its declared {@code <version>}. Looks in the
     * user home ({@code ~/.m2/toolchains.xml}) then the project ({@code .mvn/toolchains.xml}).
     */
    protected List<Toolchain> toolchainHomes() {
        final var toolchains = new ArrayList<Path>();
        final var home = env("HOME");
        if (home != null) {
            toolchains.add(Path.of(home, ".m2", "toolchains.xml"));
        }
        if (settings.projectDir() != null) {
            toolchains.add(settings.projectDir().resolve(".mvn").resolve("toolchains.xml"));
        }
        final var out = new ArrayList<Toolchain>();
        for (final var file : toolchains) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            parseToolchains(readAll(file), out);
        }
        return out;
    }

    /** A Maven toolchain JDK: its home plus the class-file major derived from its declared version. */
    public record Toolchain(Path path, int classFileMajor) {}

    private static void parseToolchains(final String xml, final List<Toolchain> out) {
        final var toolchain = TOOLCHAIN.matcher(xml);
        while (toolchain.find()) {
            final var block = toolchain.group(1);
            final var type = first(rawGroup(block, TOOLCHAIN_TYPE));
            if (!"jdk".equalsIgnoreCase(type)) {
                continue;
            }
            final var version = first(rawGroup(block, TOOLCHAIN_VERSION));
            final var home = first(rawGroup(block, TOOLCHAIN_HOME));
            if (version == null || home == null) {
                continue;
            }
            final var feature = parseJavaVersion(version).orElse(-1);
            if (feature < 0) {
                continue;
            }
            out.add(new Toolchain(Path.of(home), feature + 44));
        }
    }

    private static String rawGroup(final String xml, final Pattern tag) {
        final var m = tag.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String first(final String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String summary() {
        return "JAVA_HOME, PATH, Maven toolchains and common install locations";
    }
}
