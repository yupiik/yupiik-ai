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
package io.yupiik.maven.knowledge.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ai.http.server.OwnedOrBorrowedHttpServer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.BaseBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.mcp.client.MCPClient;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.maven.knowledge.test.FakeMavenRepo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Boots the real {@code maven-repository-knowledge} MCP server in-process (Fusion + the tomcat web server on the
 * classpath), drives the actual MCP wire protocol over HTTP and checks the three {@code maven/knowledge/*} tools are
 * advertised and callable against a fake {@code ~/.m2} + a fake project.
 */
@FusionSupport(modules = MavenKnowledgeToolsMCPTest.ExtendedHttpClientModule.class)
class MavenKnowledgeToolsMCPTest {

    // the same values the surefire config injects in the module pom - the test reuses them so it can build the
    // fake repository / project at the exact location the server already points at
    private static final Path REPO =
            Path.of(System.getProperty("yca.maven.localRepository", "target/mcp-test-m2/repository"));
    private static final Path PROJECT = Path.of(System.getProperty("yca.maven.projectDir", "target/mcp-test-project"));

    private static final String TOOL_INDEX = "maven/knowledge/index";
    private static final String TOOL_STATUS = "maven/knowledge/status";
    private static final String TOOL_CONTENT = "maven/knowledge/content";

    @BeforeAll
    static void seedEnvironment() throws Exception {
        // reset the fake repository + project so the test is idempotent across runs
        deleteRecursively(REPO);
        deleteRecursively(PROJECT);
        Files.createDirectories(REPO);
        Files.createDirectories(PROJECT);
        Files.writeString(PROJECT.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>sample</artifactId>
                  <version>1.0.0</version>
                </project>
                """);
    }

    private static void deleteRecursively(final Path path) throws Exception {
        if (Files.exists(path)) {
            try (final var walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
    }

    @Test
    void advertisesAndCallsTheMavenKnowledgeTools(@Fusion final RuntimeContainer container) throws Exception {
        final var http = HttpClient.newHttpClient();
        try (http) {
            final var endpoint = endpoint(container);
            try (final var client = new MCPClient(endpoint, http)) {
                // handshake: initialize + notifications/initialized, keeps the session header
                final var init = client.initialize().toCompletableFuture().join();
                assertEquals(200, init.statusCode(), init.body());

                // tools/list: all three features are advertised as real MCP tools
                final var tools =
                        client.call(2, "tools/list", "{}").toCompletableFuture().join();
                assertEquals(200, tools.statusCode(), tools.body());
                assertTrue(tools.body().contains("\"" + TOOL_INDEX + "\""), tools.body());
                assertTrue(tools.body().contains("\"" + TOOL_STATUS + "\""), tools.body());
                assertTrue(tools.body().contains("\"" + TOOL_CONTENT + "\""), tools.body());

                // status on a GAV that is not indexed yet
                final var notIndexed = callTool(client, 3, TOOL_STATUS, """
                        {"name": "%s", "arguments": {"gavs": ["com.acme:demo:1.0.0"]}}""".formatted(TOOL_STATUS));
                assertTrue(notIndexed.contains("\"indexed\":false"), notIndexed);
                assertTrue(notIndexed.contains("not indexed"), notIndexed);

                // index a fake artifact (sources jar present, no LLM) and check the marker lands in the fake repo
                try (final var repo = new FakeMavenRepo(REPO)) {
                    repo.sourcesJar(
                            "com.acme",
                            "demo",
                            "1.0.0",
                            "com/acme/demo/Hello.java",
                            "package com.acme.demo; public class Hello { public String hi() { return \"hi\"; } }");
                    final var indexed = callTool(client, 4, TOOL_INDEX, """
                            {"name": "%s", "arguments": {"gavs": ["com.acme:demo:1.0.0"], "force": false}}""".formatted(TOOL_INDEX));
                    assertTrue(indexed.contains("\"indexed\":1"), indexed);
                    assertTrue(
                            Files.isRegularFile(REPO.resolve("com/acme/demo/1.0.0/demo-1.0.0-knowledge-index.md")),
                            indexed);
                }

                // status reports the artifact as indexed now
                final var indexedStatus = callTool(client, 5, TOOL_STATUS, """
                        {"name": "%s", "arguments": {"gavs": ["com.acme:demo:1.0.0"]}}""".formatted(TOOL_STATUS));
                assertTrue(indexedStatus.contains("\"indexed\":true"), indexedStatus);
                assertTrue(indexedStatus.contains("already indexed"), indexedStatus);

                // content returns the generated knowledge index text
                final var content = callTool(client, 6, TOOL_CONTENT, """
                        {"name": "%s", "arguments": {"gav": "com.acme:demo:1.0.0"}}""".formatted(TOOL_CONTENT));
                assertFalse(content.contains("no knowledge indexed"), content);
                assertTrue(
                        content.contains("com.acme:demo:1.0.0")
                                || content.toLowerCase().contains("demo"),
                        content);
            }
        }
    }

    /** Calls a tool and returns the JSON body, failing with the body on transport errors. */
    private static String callTool(final MCPClient client, final int id, final String name, final String params) {
        final HttpResponse<String> response;
        try {
            response =
                    client.call(id, "tools/call", params).toCompletableFuture().join();
        } catch (final RuntimeException re) {
            throw new AssertionError("tools/call " + name + " failed: " + re.getMessage(), re);
        }
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    /** Local host:port the fusion web server listens on, with the /mcp suffix added. */
    private static URI endpoint(final RuntimeContainer container) {
        try (final var conf = container.lookup(OwnedOrBorrowedHttpServer.class)) {
            return URI.create("http://localhost:" + conf.instance().port() + "/mcp");
        }
    }

    /**
     * Registers an {@link ExtendedHttpClient} the tools need with a priority higher than the container-level one, so
     * the {@code MavenKnowledgeTools} lookup is unambiguous. {@code MavenKnowledgeMcpModule} only aggregates the
     * {@code @ApplicationScoped} beans so the {@code @DefaultScoped} {@code HttpClientProducer} is not picked up by a
     * non-scanning test container; this mirrors what a scanning standalone server would register - it is never
     * actually used since indexing runs with {@code useLlm=false}.
     */
    public static final class ExtendedHttpClientModule implements FusionModule {
        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.of(
                    new BaseBean<ExtendedHttpClient>(ExtendedHttpClient.class, DefaultScoped.class, 2_000, Map.of()) {
                        @Override
                        public ExtendedHttpClient create(
                                final RuntimeContainer container, final List<Instance<?>> dependents) {
                            return new ExtendedHttpClient(
                                    new ExtendedHttpClientConfiguration().setDelegate(HttpClient.newHttpClient()));
                        }

                        @Override
                        public void destroy(final RuntimeContainer container, final ExtendedHttpClient instance) {
                            // ExtendedHttpClient is AutoCloseable: close() runs its on-close listeners (and the JDK
                            // HttpClient delegate when the transport owns it), so the container must release it eagerly
                            instance.close();
                        }
                    });
        }
    }
}
