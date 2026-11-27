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
package io.yupiik.ia.agent.github.context;

import static io.yupiik.ia.agent.github.context.ContextTestSupport.git;
import static io.yupiik.ia.agent.github.execution.RestrictedServicesGateway.*;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.github.context.agent.AgentBrokerSource;
import io.yupiik.ia.agent.github.execution.RestrictedServicesGateway;
import io.yupiik.ia.agent.github.support.TestJava;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Actual services gateway and context listener, verified TLS and native smart HTTP Git. */
@Timeout(90)
class AgentBrokerGatewayTest {
    private static final String PASSWORD = "test-password";

    @TempDir
    private static Path shared;

    private static TlsTestSupport.Material tls;
    private static ContextTestSupport.GitFixture repository;

    @BeforeAll
    static void immutableFixtures() throws Exception {
        tls = TlsTestSupport.create(shared.resolve("tls"), PASSWORD);
        repository = ContextTestSupport.gitFixture(shared.resolve("upstream"));
        git(
                repository.directory(),
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.invalid",
                "tag",
                "-a",
                "v1",
                "-m",
                "release",
                repository.old());
    }

    @AfterAll
    static void releaseFixtures() {
        tls = null;
        repository = null;
    }

    @TempDir
    Path temporary;

    @ParameterizedTest
    // Each case launches several JVMs and native Git operations through live TLS listeners.
    // Keep the CPU/path matrix, but do not multiply its process load by concurrent invocations
    // on top of the suite's class-level and Maven reactor parallelism.
    @Execution(ExecutionMode.SAME_THREAD)
    @CsvSource({"true, 1", "false, 1", "true, 2", "false, 2", "true, 3", "false, 3"})
    void nativeGitInitializesThroughActualGatewayAndContextService(final boolean explicitPaths, final int processors)
            throws Exception {
        final var store = tls.store();
        final var certificate = tls.certificate();
        try (final var fixture = new ContextTestSupport(temporary.resolve("upstream"))) {
            final var service = fixture.service(
                    fixture.configuration(""),
                    new ContextBudget(4096, 16 * 1024 * 1024),
                    repository.head(),
                    repository.executable().toString());
            try (final var context = new ContextHttpServer(fixture.configuration(""), service, fixture.json)) {
                final var secrets = new HashMap<>(context.credentials());
                secrets.put(YCA_SERVICES_PROVIDER_TOKEN, "p".repeat(43));
                secrets.put(YCA_SERVICES_KEYSTORE_PASSWORD, PASSWORD);
                try (final var gateway = new RestrictedServicesGateway(
                        new Settings(
                                "localhost",
                                0,
                                store.toString(),
                                YCA_SERVICES_KEYSTORE_PASSWORD,
                                context.route(),
                                "http://127.0.0.1:" + context.port(),
                                "/inference/12345678-1234-1234-1234-123456789abc/chat/completions",
                                "http://127.0.0.1:"
                                        + fixture.upstream.getAddress().getPort() + "/unused",
                                60,
                                20,
                                1024,
                                16 * 1024 * 1024),
                        Configuration.of(secrets),
                        new ProviderBudgetConfiguration(null, null, null))) {
                    final var workspace = Files.createDirectory(temporary.resolve("agent"));
                    final var command = TestJava.javaCommand(
                            "-XX:ActiveProcessorCount=" + processors,
                            "-Djavax.net.ssl.trustStore=" + store,
                            "-Djavax.net.ssl.trustStorePassword=" + PASSWORD,
                            "-Djavax.net.ssl.trustStoreType=PKCS12",
                            "-cp",
                            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                            AgentBrokerSource.class.getName(),
                            "--endpoint",
                            "https://localhost:" + gateway.port() + context.route() + "/rpc",
                            "--repository",
                            "acme/primary",
                            "--head",
                            repository.head(),
                            "--max-depth",
                            "8",
                            "--max-bytes",
                            "8388608",
                            "--timeout",
                            "20",
                            "--ca-file",
                            certificate.toString());
                    final var operationFlags = List.copyOf(command);
                    command.add("init");
                    if (explicitPaths) {
                        command.add("src");
                    }
                    final var initialized = run(workspace, command, context.credentials());
                    assertEquals(0, initialized.exit(), initialized.output());
                    final var checkout = workspace.resolve("repository");
                    assertEquals(
                            repository.head(),
                            git(checkout, "rev-parse", "HEAD").strip());
                    assertEquals("pinned source\n", Files.readString(checkout.resolve("file.txt")));
                    assertEquals(
                            "true",
                            git(checkout, "rev-parse", "--is-shallow-repository")
                                    .strip());
                    final var state = new Properties();
                    try (final var input = Files.newInputStream(checkout.resolve(".git/yca-broker.properties"))) {
                        state.load(input);
                    }
                    // The CLI processor/path matrix above exercises real native Git for every case.
                    // Protocol forwarding, compression and authorization are gateway-server concerns:
                    // exhaust their transport matrix once, not once per child JVM processor count.
                    if (explicitPaths && processors == 1) {
                        verifyGitTransport(store, gateway, context, state.getProperty("gitPath"));
                    }
                    Files.writeString(checkout.resolve("file.txt"), "committed agent work\n");
                    git(checkout, "add", "file.txt");
                    git(
                            checkout,
                            "-c",
                            "user.name=Agent",
                            "-c",
                            "user.email=agent@example.invalid",
                            "commit",
                            "-m",
                            "agent work");
                    final var localHead = git(checkout, "rev-parse", "HEAD").strip();
                    Files.writeString(checkout.resolve("file.txt"), "uncommitted agent work\n");
                    Files.writeString(checkout.resolve("untracked.txt"), "untracked agent work\n");
                    for (final var operation : List.of(List.of("deepen", "1"), List.of("tags"), List.of("base"))) {
                        final var operationCommand = new ArrayList<>(operationFlags);
                        operationCommand.addAll(operation);
                        final var result = run(workspace, operationCommand, context.credentials());
                        assertEquals(0, result.exit(), operation.getFirst() + ": " + result.output());
                        assertEquals(
                                localHead, git(checkout, "rev-parse", "HEAD").strip());
                        assertEquals("uncommitted agent work\n", Files.readString(checkout.resolve("file.txt")));
                        assertEquals("untracked agent work\n", Files.readString(checkout.resolve("untracked.txt")));
                    }
                    assertEquals(
                            repository.old(),
                            git(checkout, "rev-parse", "v1^{}").strip());
                    assertEquals(
                            repository.head(),
                            git(checkout, "rev-parse", "refs/remotes/broker/BASE")
                                    .strip());
                    for (final var name : List.of("config", "yca-broker.properties")) {
                        final var contents =
                                Files.readString(checkout.resolve(".git").resolve(name));
                        for (final var token : context.credentials().values()) {
                            assertFalse(contents.contains(token));
                            assertFalse(initialized.output().contains(token));
                        }
                    }
                }
            }
        }
    }

    private void verifyGitTransport(
            final Path store,
            final RestrictedServicesGateway gateway,
            final ContextHttpServer context,
            final String gitPath)
            throws Exception {
        assertNotNull(gitPath, "Native Git must have reached a prepared generation");
        final var keys = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(store)) {
            keys.load(input, PASSWORD.toCharArray());
        }
        final var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        final var tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        final var authorization = "Basic "
                + Base64.getEncoder()
                        .encodeToString(("context:" + context.credentials().get(YCA_CONTEXT_GIT_TOKEN))
                                .getBytes(StandardCharsets.US_ASCII));
        final var direct = "http://127.0.0.1:" + context.port() + "/" + gitPath;
        final var proxied = "https://localhost:" + gateway.port() + "/" + gitPath;
        try (final var client = HttpClient.newBuilder()
                .sslContext(tls)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            // Distinct v1/v2 advertisements prove Git-Protocol reaches the actual native CGI backend.
            for (final var protocol : List.of("version=1", "version=2")) {
                final var expected = client.send(
                        HttpRequest.newBuilder(URI.create(direct + "/info/refs?service=git-upload-pack"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", authorization)
                                .header("Git-Protocol", protocol)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                final var actual = client.send(
                        HttpRequest.newBuilder(URI.create(proxied + "/info/refs?service=git-upload-pack"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", authorization)
                                .header("Git-Protocol", protocol)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, expected.statusCode());
                assertEquals(200, actual.statusCode());
                assertEquals(
                        "application/x-git-upload-pack-advertisement",
                        actual.headers().firstValue("content-type").orElseThrow());
                assertArrayEquals(expected.body(), actual.body());
                assertTrue(new String(actual.body(), StandardCharsets.US_ASCII)
                        .contains(protocol.equals("version=2") ? "version 2\n" : "# service=git-upload-pack\n"));
            }
            final var packet =
                    "0014command=ls-refs\n00010009peel\n000csymrefs\n0000".getBytes(StandardCharsets.US_ASCII);
            final var compressed = new ByteArrayOutputStream();
            try (final var gzip = new GZIPOutputStream(compressed)) {
                gzip.write(packet);
            }
            // Compare both plain and gzip POSTs with the direct backend response. Host is generated
            // for the fixed numeric upstream, not copied from the external TLS authority.
            for (final boolean gzip : List.of(false, true)) {
                final var request = HttpRequest.newBuilder()
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", authorization)
                        .header("Git-Protocol", "version=2")
                        .header("Content-Type", "application/x-git-upload-pack-request")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(gzip ? compressed.toByteArray() : packet));
                if (gzip) {
                    request.header("Content-Encoding", "gzip");
                }
                final var expected = client.send(
                        request.uri(URI.create(direct + "/git-upload-pack")).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                final var actual = client.send(
                        request.uri(URI.create(proxied + "/git-upload-pack")).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, expected.statusCode());
                assertEquals(200, actual.statusCode());
                assertEquals(
                        "application/x-git-upload-pack-result",
                        actual.headers().firstValue("content-type").orElseThrow());
                assertArrayEquals(expected.body(), actual.body());
                assertTrue(actual.body().length > 4, "ls-refs must return actual repository refs");
            }
            final var denied = client.send(
                    HttpRequest.newBuilder(URI.create(proxied + "/info/refs?service=git-upload-pack"))
                            .timeout(Duration.ofSeconds(10))
                            .header(
                                    "Authorization",
                                    "Bearer " + context.credentials().get(YCA_CONTEXT_TOKEN))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(401, denied.statusCode(), "Control credentials cannot authorize native Git");
        }
    }

    private Result run(final Path directory, final List<String> command, final Map<String, String> environment)
            throws Exception {
        final var log = Files.createTempFile(temporary, "child-", ".log");
        final var builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().clear();
        builder.environment()
                .putAll(Map.of(
                        "PATH",
                        "/usr/bin:/bin",
                        "HOME",
                        temporary.toString(),
                        "LANG",
                        "C",
                        "GIT_CONFIG_NOSYSTEM",
                        "1",
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "GIT_TERMINAL_PROMPT",
                        "0"));
        builder.environment().putAll(environment);
        final var process = builder.start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child exceeded test deadline");
            return new Result(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    private record Result(int exit, String output) {}
}
