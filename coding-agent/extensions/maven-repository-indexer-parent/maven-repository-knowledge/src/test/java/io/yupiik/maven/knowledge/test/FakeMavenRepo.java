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
package io.yupiik.maven.knowledge.test;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Test helper that builds a fake local Maven repository in a {@code java.nio.file.Path} and optionally a
 * JRE {@link HttpServer} standing in for the remote repo / LLM, per the "fake .m2 + local http server"
 * test strategy.
 */
public final class FakeMavenRepo implements AutoCloseable {

    private final Path root;
    private final AtomicInteger httpHits = new AtomicInteger();
    private final Map<String, String> httpRoutes = new ConcurrentHashMap<>();
    private HttpServer server;

    public FakeMavenRepo(final Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path artifactDir(final String groupId, final String artifactId, final String version) {
        return root.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version);
    }

    public FakeMavenRepo pom(final String groupId, final String artifactId, final String version, final String url) {
        final var dir = artifactDir(groupId, artifactId, version);
        write(
                dir,
                artifactId + "-" + version + ".pom",
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>" + groupId + "</groupId>\n"
                        + "  <artifactId>" + artifactId + "</artifactId>\n"
                        + "  <version>" + version + "</version>\n"
                        + (url == null ? "" : "  <url>" + url + "</url>\n")
                        + "</project>\n");
        return this;
    }

    public FakeMavenRepo sourcesJar(
            final String groupId,
            final String artifactId,
            final String version,
            final String fileName,
            final String javaSource) {
        return sourcesJar(groupId, artifactId, version, Map.of(fileName, javaSource));
    }

    public FakeMavenRepo sourcesJar(
            final String groupId, final String artifactId, final String version, final Map<String, String> entries) {
        final var dir = artifactDir(groupId, artifactId, version);
        final var jar = dir.resolve(artifactId + "-" + version + "-sources.jar");
        writeZip(jar, entries);
        return this;
    }

    public FakeMavenRepo javadocJar(
            final String groupId,
            final String artifactId,
            final String version,
            final String fileName,
            final String html) {
        return javadocJar(groupId, artifactId, version, Map.of(fileName, html));
    }

    public FakeMavenRepo javadocJar(
            final String groupId, final String artifactId, final String version, final Map<String, String> entries) {
        final var dir = artifactDir(groupId, artifactId, version);
        final var jar = dir.resolve(artifactId + "-" + version + "-javadoc.jar");
        writeZip(jar, entries);
        return this;
    }

    public FakeMavenRepo classesJar(
            final String groupId, final String artifactId, final String version, final String className) {
        final var dir = artifactDir(groupId, artifactId, version);
        final var jar = dir.resolve(artifactId + "-" + version + ".jar");
        final var entry = className.replace('.', '/') + ".class";
        writeZip(jar, Map.of(entry, "x"));
        return this;
    }

    public FakeMavenRepo pluginJar(
            final String groupId, final String artifactId, final String version, final String pluginXml) {
        final var dir = artifactDir(groupId, artifactId, version);
        final var jar = dir.resolve(artifactId + "-" + version + ".jar");
        writeZip(jar, Map.of("META-INF/maven/plugin.xml", pluginXml));
        return this;
    }

    public FakeMavenRepo extensionJar(
            final String groupId,
            final String artifactId,
            final String version,
            final String descriptorPath,
            final String extensionXml) {
        final var dir = artifactDir(groupId, artifactId, version);
        final var jar = dir.resolve(artifactId + "-" + version + ".jar");
        writeZip(jar, Map.of(descriptorPath, extensionXml));
        return this;
    }

    /** Idempotent marker writing necessary for the {@code -knowledge-index.md} presence check in {@code KnowledgeWriter}. */
    public void markIndexed(final String groupId, final String artifactId, final String version, final String name) {
        final var dir = artifactDir(groupId, artifactId, version);
        write(
                dir,
                artifactId + "-" + version + "-knowledge-index.md",
                "---\nname: " + name + "\ndescription: existing\n---\npreexisting\n");
    }

    /**
     * Start a JRE http server replying to anything under {@code pathPrefix} with the registered body.
     */
    public FakeMavenRepo startHttp(final String pathPrefix, final String body) {
        return startHttp(Map.of(pathPrefix, body));
    }

    public FakeMavenRepo startHttp(final Map<String, String> routes) {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                httpHits.incrementAndGet();
                final var path = exchange.getRequestURI().getPath();
                final var body = httpRoutes.get(path);
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    final var bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });
            httpRoutes.putAll(routes);
            server.start();
            return this;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        if (server == null) {
            throw new IllegalStateException("http server not started");
        }
        return "http://localhost:" + server.getAddress().getPort();
    }

    public int httpHits() {
        return httpHits.get();
    }

    private static void write(final Path dir, final String name, final String content) {
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve(name), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeZip(final Path jar, final Map<String, String> entries) {
        try {
            Files.createDirectories(jar.getParent());
            try (final var zos = new ZipOutputStream(Files.newOutputStream(jar))) {
                for (final var entry : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }
}
