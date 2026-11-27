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
package io.yupiik.ia.agent.github.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Trusted, host-side dependency cache policy; no host environment or credentials are imported. */
public record DependencyCacheConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Mode.OFF",
                documentation = "Dependency caching mode: OFF, RESTORE_ONLY, or READ_WRITE.")
        Mode mode,

        @Property(defaultValue = "\"\"", documentation = "Private host cache root, required when caching is enabled.")
        String root,

        @Property(
                defaultValue = "io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Scope.REPOSITORY",
                documentation = "Repository-isolated cache scope.")
        Scope scope,

        @Property(defaultValue = "\"default\"", documentation = "Explicit compatibility namespace.")
        String compatibility,

        @Property(documentation = "Dependency tool presets.")
        List<Preset> presets,

        @Property(documentation = "Explicit entries override presets by id.")
        List<Entry> entries,

        @Property(documentation = "Explicit nonsecret tool environment overrides.")
        Map<String, String> environment,

        @Property(documentation = "Fresh nonsecret configuration files, never cached.")
        List<GeneratedFile> files,

        @Property(defaultValue = "300", documentation = "Grace period for cache publication in seconds.")
        int gracefulTimeoutSeconds,

        @Property(documentation = "Cache maintenance policy.")
        Maintenance maintenance,

        @Property(documentation = "Bounded cache operation limits.")
        Limits limits) {
    private static final Pattern ENVIRONMENT_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SENSITIVE_ENVIRONMENT_KEY =
            Pattern.compile(".*(TOKEN|PASSWORD|PASSWD|SECRET|CREDENTIAL|AUTH|CONTEXT|PROXY|KEY|COOKIE).*");
    private static final Pattern URL_WITH_CREDENTIALS = Pattern.compile("(?s).*://[^/\\s]*@.*");

    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");

    public DependencyCacheConfiguration {
        if (mode == null
                || scope == null
                || root == null
                || compatibility == null
                || !ID.matcher(compatibility).matches()
                || gracefulTimeoutSeconds < 1
                || maintenance == null
                || limits == null) {
            throw new IllegalArgumentException("Invalid dependency cache policy");
        }
        presets = presets == null ? List.of() : List.copyOf(presets);
        entries = entries == null ? List.of() : List.copyOf(entries);
        environment = environment == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(environment));
        files = files == null ? List.of() : List.copyOf(files);
        if (mode != Mode.OFF) {
            if (root.isBlank()) {
                throw new IllegalArgumentException("Enabled dependency caching requires a root");
            }
            if (root.contains("${")) {
                throw new IllegalArgumentException("Host cache root must not contain placeholders");
            }
            if (!Path.of(root).isAbsolute()) {
                throw new IllegalArgumentException("Dependency cache root must be absolute");
            }
            final var resolved = expand(presets, entries, environment, files);
            if (resolved.entries().size() > 128 || resolved.files().size() > 128) {
                throw new IllegalArgumentException("Caching supports at most 128 entries and 128 files");
            }
            validate(resolved);
        }
    }

    public boolean enabled() {
        return mode != Mode.OFF;
    }

    public List<Entry> resolvedEntries() {
        return expand(presets, entries, environment, files).entries();
    }

    public Map<String, String> resolvedEnvironment() {
        return expand(presets, entries, environment, files).environment();
    }

    public List<GeneratedFile> resolvedFiles() {
        return expand(presets, entries, environment, files).files();
    }

    public enum Mode {
        OFF,
        RESTORE_ONLY,
        READ_WRITE
    }

    public enum Scope {
        REPOSITORY
    }

    public enum Preset {
        COMMON,
        MAVEN,
        NPM,
        GRADLE,
        PIP,
        GO,
        CARGO,
        NUGET
    }

    public enum Kind {
        MAVEN,
        GENERIC
    }

    public record Entry(
            @Property(documentation = "Safe unique cache entry id.")
            String id,

            @Property(
                    defaultValue = "io.yupiik.ia.agent.github.configuration.DependencyCacheConfiguration.Kind.GENERIC",
                    documentation = "Entry kind; MAVEN always excludes SNAPSHOT directories.")
            Kind kind,

            @Property(documentation = "Storage-relative cache directory.")
            String directory,

            @Property(documentation = "Excluded directory name suffixes.")
            List<String> excludeDirectoryNameSuffixes) {
        public Entry {
            if (id == null || !ID.matcher(id).matches() || kind == null || directory == null) {
                throw new IllegalArgumentException("Invalid dependency cache entry");
            }
            final var suffixes = new TreeSet<String>();
            if (excludeDirectoryNameSuffixes != null) {
                for (final var suffix : excludeDirectoryNameSuffixes) {
                    if (suffix == null
                            || suffix.isEmpty()
                            || suffix.contains("/")
                            || suffix.contains("\\")
                            || suffix.indexOf('\0') >= 0) {
                        throw new IllegalArgumentException("Invalid excluded directory suffix");
                    }
                    suffixes.add(suffix);
                }
            }
            if (kind == Kind.MAVEN) {
                suffixes.add("-SNAPSHOT");
            }
            excludeDirectoryNameSuffixes = List.copyOf(suffixes);
        }
    }

    public record GeneratedFile(
            @Property(documentation = "Storage-relative generated configuration path.")
            String path,

            @Property(documentation = "Nonsecret content supporting storage and cache placeholders.")
            String content) {
        public GeneratedFile {
            if (path == null || content == null) {
                throw new IllegalArgumentException("Generated file path and content are required");
            }
        }
    }

    public record Maintenance(
            @Property(defaultValue = "30", documentation = "Reset entries after this many days.")
            int resetAfterDays,

            @Property(defaultValue = "10737418240L", documentation = "Maximum host cache bytes.")
            long maxSizeBytes) {
        public Maintenance {
            if (resetAfterDays < 1 || maxSizeBytes < 1) {
                throw new IllegalArgumentException("Invalid dependency cache maintenance limits");
            }
        }
    }

    public record Limits(
            @Property(defaultValue = "100000", documentation = "Maximum files per cache operation.")
            int maxFiles,

            @Property(defaultValue = "17179869184L", documentation = "Maximum individual file bytes.")
            long maxFileBytes,

            @Property(defaultValue = "67108864L", documentation = "Maximum manifest bytes.")
            long maxManifestBytes,

            @Property(defaultValue = "600", documentation = "Operation timeout in seconds.")
            int operationTimeoutSeconds,

            @Property(defaultValue = "2", documentation = "Maximum concurrent host cache operations.")
            int maxConcurrentOperations) {
        public Limits {
            if (maxFiles < 1
                    || maxFileBytes < 1
                    || maxManifestBytes < 1
                    || operationTimeoutSeconds < 1
                    || maxConcurrentOperations < 1
                    || maxConcurrentOperations > 64) {
                throw new IllegalArgumentException("Invalid dependency cache operation limits");
            }
        }
    }

    private record Resolved(List<Entry> entries, Map<String, String> environment, List<GeneratedFile> files) {}

    private static Resolved expand(
            final List<Preset> presets,
            final List<Entry> entries,
            final Map<String, String> environment,
            final List<GeneratedFile> files) {
        final var selected = new TreeSet<>(presets);
        if (selected.remove(Preset.COMMON)) {
            selected.addAll(List.of(
                    Preset.MAVEN, Preset.NPM, Preset.GRADLE, Preset.PIP, Preset.GO, Preset.CARGO, Preset.NUGET));
        }
        final var cache = new TreeMap<String, Entry>();
        final var env = new TreeMap<String, String>();
        final var generated = new TreeMap<String, GeneratedFile>();
        for (final var preset : selected) {
            switch (preset) {
                case MAVEN -> {
                    cache.put("maven", new Entry("maven", Kind.MAVEN, "caches/maven/repository", List.of()));
                    env.put("MAVEN_OPTS", "-Dmaven.repo.local=${cache.maven}");
                    generated.put(
                            ".m2/settings.xml",
                            new GeneratedFile(
                                    ".m2/settings.xml",
                                    "<settings><localRepository>${cache.maven}</localRepository></settings>\n"));
                }
                case NPM -> {
                    cache.put("npm", new Entry("npm", Kind.GENERIC, "caches/npm/_cacache", List.of()));
                    env.put("npm_config_cache", "${storage}/caches/npm");
                }
                case GRADLE -> {
                    cache.put("gradle", new Entry("gradle", Kind.GENERIC, "caches/gradle/caches/modules-2", List.of()));
                    env.put("GRADLE_USER_HOME", "${storage}/caches/gradle");
                }
                case PIP -> {
                    cache.put("pip-http", new Entry("pip-http", Kind.GENERIC, "caches/pip/http", List.of()));
                    cache.put("pip-http-v2", new Entry("pip-http-v2", Kind.GENERIC, "caches/pip/http-v2", List.of()));
                    env.put("PIP_CACHE_DIR", "${storage}/caches/pip");
                }
                case GO -> {
                    cache.put("go", new Entry("go", Kind.GENERIC, "caches/go/pkg/mod/cache/download", List.of()));
                    env.put("GOMODCACHE", "${storage}/caches/go/pkg/mod");
                }
                case CARGO -> {
                    cache.put(
                            "cargo-index",
                            new Entry("cargo-index", Kind.GENERIC, "caches/cargo/registry/index", List.of()));
                    cache.put(
                            "cargo-cache",
                            new Entry("cargo-cache", Kind.GENERIC, "caches/cargo/registry/cache", List.of()));
                    env.put("CARGO_HOME", "${storage}/caches/cargo");
                }
                case NUGET -> {
                    cache.put("nuget", new Entry("nuget", Kind.GENERIC, "caches/nuget/packages", List.of()));
                    env.put("NUGET_PACKAGES", "${cache.nuget}");
                }
                case COMMON -> throw new IllegalStateException("COMMON must be expanded");
            }
        }
        final var ids = new TreeSet<String>();
        for (final var entry : entries) {
            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException("Duplicate dependency cache entry: " + entry.id());
            }
            cache.put(entry.id(), entry);
        }
        env.putAll(environment);
        final var paths = new TreeSet<String>();
        for (final var file : files) {
            if (!paths.add(file.path())) {
                throw new IllegalArgumentException("Duplicate generated file: " + file.path());
            }
            generated.put(file.path(), file);
        }
        return new Resolved(
                List.copyOf(cache.values()), Collections.unmodifiableMap(env), List.copyOf(generated.values()));
    }

    private static void validate(final Resolved resolved) {
        final var paths = new ArrayList<Path>();
        for (final var entry : resolved.entries()) {
            final var path = relative(entry.directory());
            for (final var previous : paths) {
                if (path.startsWith(previous) || previous.startsWith(path)) {
                    throw new IllegalArgumentException("Overlapping dependency cache directories");
                }
            }
            paths.add(path);
        }
        final var filePaths = new ArrayList<Path>();
        for (final var file : resolved.files()) {
            final var path = relative(file.path());
            for (final var directory : paths) {
                if (path.startsWith(directory) || directory.startsWith(path)) {
                    throw new IllegalArgumentException("Generated files must be separate from cache directories");
                }
            }
            for (final var previous : filePaths) {
                if (path.startsWith(previous) || previous.startsWith(path)) {
                    throw new IllegalArgumentException("Overlapping generated files");
                }
            }
            filePaths.add(path);
            placeholders(file.content(), resolved.entries());
        }
        validateEnvironment(resolved.environment());
        resolved.environment().forEach((key, value) -> placeholders(value, resolved.entries()));
    }

    /** Shared with launchers: explicit tool variables, never inherited host environment. */
    public static void validateEnvironment(final Map<String, String> environment) {
        if (environment == null || environment.size() > 128) {
            throw new IllegalArgumentException("Invalid dependency cache environment");
        }
        final var reserved = Set.of(
                "HOME",
                "PATH",
                "USER",
                "LOGNAME",
                "SHELL",
                "PWD",
                "OLDPWD",
                "TMPDIR",
                "TMP",
                "TEMP",
                "ENV",
                "BASH_ENV",
                "IFS",
                "CLASSPATH",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
                "NODE_OPTIONS",
                "PYTHONPATH",
                "PYTHONHOME",
                "RUBYOPT",
                "PERL5OPT");
        environment.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("Null dependency cache environment name");
            }
            final var upper = key.toUpperCase(Locale.ROOT);
            if (!ENVIRONMENT_KEY.matcher(key).matches()
                    || reserved.contains(upper)
                    || upper.startsWith("DOCKER_")
                    || upper.startsWith("LD_")
                    || upper.startsWith("DYLD_")
                    || upper.startsWith("YCA_")
                    || upper.startsWith("GITHUB_")
                    || upper.startsWith("GH_")
                    || upper.startsWith("GIT_")
                    || upper.startsWith("JAVA_")
                    || upper.startsWith("SSH_")
                    || upper.startsWith("KUBERNETES_")
                    || SENSITIVE_ENVIRONMENT_KEY.matcher(upper).matches()) {
                throw new IllegalArgumentException("Unsafe dependency cache environment variable: " + key);
            }
            if (value == null
                    || value.length() > 65536
                    || value.indexOf('\0') >= 0
                    || value.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0
                    || URL_WITH_CREDENTIALS.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid dependency cache environment value");
            }
        });
    }

    private static Path relative(final String value) {
        if (value.isBlank() || value.contains("\\") || value.contains("${") || value.contains(":")) {
            throw new IllegalArgumentException("Expected a storage-relative path");
        }
        final var path = Path.of(value);
        if (path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IllegalArgumentException("Expected a normalized storage-relative path");
        }
        for (final var segment : path) {
            if (segment.toString().equals("..") || segment.toString().equals(".")) {
                throw new IllegalArgumentException("Path traversal is forbidden");
            }
        }
        return path;
    }

    private static void placeholders(final String value, final List<Entry> entries) {
        final var matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            final var key = matcher.group(1);
            if (!key.equals("storage") && entries.stream().noneMatch(entry -> key.equals("cache." + entry.id()))) {
                throw new IllegalArgumentException("Unknown dependency cache placeholder: " + key);
            }
        }
        if (matcher.replaceAll("").contains("${")) {
            throw new IllegalArgumentException("Malformed dependency cache placeholder");
        }
    }
}
