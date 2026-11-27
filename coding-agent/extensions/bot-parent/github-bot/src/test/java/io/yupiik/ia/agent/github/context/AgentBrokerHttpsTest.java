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

import static io.yupiik.ia.agent.github.context.ContextTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.yupiik.ia.agent.github.context.agent.AgentBrokerSource;
import io.yupiik.ia.agent.github.support.TestJava;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real TLS and Git boundary test; no fake Git, permissive trust manager or TLS-verification bypass. */
class AgentBrokerHttpsTest {
    private static final Pattern FETCH_FAILURE = Pattern.compile(
            "Broker source operation failed: stage=git_fetch code=GIT_EXIT processors=[0-9]+ commonPoolParallelism=[0-9]+\\n");
    private static final Pattern VALIDATION_FAILURE = Pattern.compile(
            "Broker source operation failed: stage=validation code=REJECTED processors=[0-9]+ commonPoolParallelism=[0-9]+\\n");
    private static final String STORE_PASSWORD = "changeit";

    @TempDir
    private static Path shared;

    private static TlsTestSupport.Material material;
    private static ContextTestSupport.GitFixture repository;

    @BeforeAll
    static void immutableFixtures() throws Exception {
        material = TlsTestSupport.create(shared.resolve("tls"), STORE_PASSWORD);
        repository = ContextTestSupport.sourceFixture(shared.resolve("upstream with 'quotes'"));
    }

    @AfterAll
    static void releaseFixtures() {
        material = null;
        repository = null;
    }

    @Test
    void exportedCertificateMatchesKeyStore() throws Exception {
        final var keys = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(material.store())) {
            keys.load(input, STORE_PASSWORD.toCharArray());
        }
        assertTrue(keys.isKeyEntry("localhost"));
        try (final var input = Files.newInputStream(material.certificate())) {
            final var certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            assertArrayEquals(keys.getCertificate("localhost").getEncoded(), certificate.getEncoded());
            certificate.verify(certificate.getPublicKey());
        }
    }

    @TempDir
    Path temporary;

    @Test
    void independentHelperPreservesAgentWorkAcrossVerifiedHttpsSourceOperations() throws Exception {
        final var store = material.store();
        final var certificate = material.certificate();
        final var keys = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(store)) {
            keys.load(input, STORE_PASSWORD.toCharArray());
        }
        final var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, STORE_PASSWORD.toCharArray());
        final var tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        final var proxy = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setHttpsConfigurator(new HttpsConfigurator(tls));
        final var requests = new CopyOnWriteArrayList<String>();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                final var fixture = new ContextTestSupport(temporary.resolve("upstream"));
                final var forward = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build()) {
            final var head = repository.head();
            final var tag =
                    git(repository.directory(), "rev-parse", "refs/tags/v1").strip();
            final var service = fixture.service(
                    fixture.configuration(""),
                    new ContextBudget(4096, 16 * 1024 * 1024),
                    head,
                    repository.executable().toString());
            try (final var server = new ContextHttpServer(fixture.configuration(""), service, fixture.json)) {
                proxy.setExecutor(executor);
                proxy.createContext("/", exchange -> {
                    try (exchange) {
                        requests.add(exchange.getRequestURI().toString());
                        final var request = HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + server.port() + exchange.getRequestURI()))
                                .timeout(Duration.ofSeconds(15));
                        exchange.getRequestHeaders().forEach((name, values) -> {
                            if (!List.of(
                                            "host",
                                            "connection",
                                            "content-length",
                                            "expect",
                                            "upgrade",
                                            "transfer-encoding")
                                    .contains(name.toLowerCase(Locale.ROOT))) {
                                values.forEach(value -> request.header(name, value));
                            }
                        });
                        request.method(
                                exchange.getRequestMethod(),
                                HttpRequest.BodyPublishers.ofByteArray(
                                        exchange.getRequestBody().readAllBytes()));
                        final HttpResponse<byte[]> response;
                        try {
                            response = forward.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                        response.headers().map().forEach((name, values) -> {
                            if (!List.of("content-length", "connection", "transfer-encoding")
                                    .contains(name.toLowerCase(Locale.ROOT))) {
                                exchange.getResponseHeaders().put(name, values);
                            }
                        });
                        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                        exchange.getResponseBody().write(response.body());
                    }
                });
                proxy.start();
                final var endpoint = "https://localhost:" + proxy.getAddress().getPort() + server.route() + "/rpc";
                final var workspace = Files.createDirectory(temporary.resolve("agent with 'quotes'"));
                final var environment = Map.of(
                        ContextHttpServer.CONTROL_TOKEN_ENV,
                        server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV),
                        ContextHttpServer.GIT_TOKEN_ENV,
                        server.credentials().get(ContextHttpServer.GIT_TOKEN_ENV),
                        "GH_TOKEN",
                        "must-not-leak-upstream-token");
                final var flags = List.of(
                        "--endpoint",
                        endpoint,
                        "--repository",
                        "acme/primary",
                        "--head",
                        head,
                        "--max-depth",
                        "8",
                        "--max-bytes",
                        "8388608",
                        "--timeout",
                        "20",
                        "--ca-file",
                        certificate.toString());
                final var withoutNativeCa = flags.subList(0, flags.size() - 2);
                final var untrusted = run(
                        Files.createDirectory(temporary.resolve("untrusted")),
                        helper(store, withoutNativeCa, "init", "src"),
                        environment,
                        "");
                assertNotEquals(0, untrusted.exit());
                assertTrue(FETCH_FAILURE.matcher(untrusted.output()).matches(), untrusted.output());
                assertTrue(
                        requests.stream().noneMatch(path -> path.contains("/git/")),
                        "Untrusted native TLS must not reach Git HTTP");
                assertEquals(
                        List.of("/repos/acme/primary", "/repos/acme/primary", "/repos/acme/primary"), fixture.requests);
                fixture.requests.clear();
                requests.clear();
                final var initialized = run(workspace, helper(store, flags, "init", "src"), environment, "");
                assertEquals(0, initialized.exit(), initialized.output());
                assertEquals(List.of("/repos/acme/primary", "/repos/acme/primary"), fixture.requests);
                assertTrue(requests.stream().anyMatch(path -> path.equals(server.route() + "/rpc")));
                assertTrue(requests.stream().anyMatch(path -> path.contains("/git/")));

                final var repo = workspace.resolve("repository");
                assertEquals(head, git(repo, "rev-parse", "HEAD").strip());
                assertEquals("1", git(repo, "rev-list", "--count", "HEAD").strip());
                assertEquals(
                        "true",
                        git(repo, "rev-parse", "--is-shallow-repository").strip());
                assertEquals("source\n", Files.readString(repo.resolve("src/code.txt")));
                assertFalse(Files.exists(repo.resolve("docs/guide.txt")));
                assertFalse(Files.exists(repo.resolve(".git/objects/info/alternates")));
                assertEquals("", git(repo, "tag", "--list"));
                // Committed and uncommitted agent work must survive every network operation.
                Files.writeString(repo.resolve("src/code.txt"), "committed agent work\n");
                git(repo, "add", "src/code.txt");
                git(
                        repo,
                        "-c",
                        "user.name=Agent",
                        "-c",
                        "user.email=agent@example.invalid",
                        "commit",
                        "-m",
                        "agent work");
                final var localHead = git(repo, "rev-parse", "HEAD").strip();
                Files.writeString(repo.resolve("src/code.txt"), "uncommitted agent work\n");
                Files.writeString(repo.resolve("untracked.txt"), "untracked agent work\n");
                for (final var operation : List.of(List.of("deepen", "1"), List.of("tags"), List.of("base"))) {
                    final var result =
                            run(workspace, helper(store, flags, operation.toArray(String[]::new)), environment, "");
                    assertEquals(0, result.exit(), () -> operation.getFirst() + ": " + result.output());
                    assertEquals(localHead, git(repo, "rev-parse", "HEAD").strip());
                    assertEquals("uncommitted agent work\n", Files.readString(repo.resolve("src/code.txt")));
                    assertEquals("untracked agent work\n", Files.readString(repo.resolve("untracked.txt")));
                }
                assertEquals("3", git(repo, "rev-list", "--count", "HEAD").strip());
                assertEquals(tag, git(repo, "rev-parse", "refs/tags/v1").strip());
                assertEquals(repository.old(), git(repo, "rev-parse", "v1^{}").strip());
                assertTrue(git(repo, "describe", "HEAD").strip().startsWith("v1-2-g"));
                assertEquals(
                        head, git(repo, "rev-parse", "refs/remotes/broker/BASE").strip());
                final var deniedExpansion = run(workspace, helper(store, flags, "expand", "docs"), environment, "");
                assertNotEquals(0, deniedExpansion.exit());
                assertFalse(Files.exists(repo.resolve("docs/guide.txt")));
                assertEquals("uncommitted agent work\n", Files.readString(repo.resolve("src/code.txt")));
                git(repo, "add", "src/code.txt", "untracked.txt");
                git(
                        repo,
                        "-c",
                        "user.name=Agent",
                        "-c",
                        "user.email=agent@example.invalid",
                        "commit",
                        "-m",
                        "more agent work");
                final var committedHead = git(repo, "rev-parse", "HEAD").strip();
                Files.createDirectories(repo.resolve("docs"));
                Files.writeString(repo.resolve("docs/guide.txt"), "collision\n");
                assertNotEquals(
                        0,
                        run(workspace, helper(store, flags, "expand", "docs"), environment, "")
                                .exit());
                assertEquals("collision\n", Files.readString(repo.resolve("docs/guide.txt")));
                Files.delete(repo.resolve("docs/guide.txt"));
                final var expanded = run(workspace, helper(store, flags, "expand", "docs"), environment, "");
                assertEquals(0, expanded.exit(), expanded.output());
                assertEquals("guide\n", Files.readString(repo.resolve("docs/guide.txt")));
                assertEquals("uncommitted agent work\n", Files.readString(repo.resolve("src/code.txt")));
                assertEquals(committedHead, git(repo, "rev-parse", "HEAD").strip());
                assertEquals("", git(repo, "status", "--porcelain"));
                final var state = new Properties();
                try (final var input = Files.newInputStream(repo.resolve(".git/yca-broker.properties"))) {
                    state.load(input);
                }
                final var gitPath = state.getProperty("gitPath");
                assertNotNull(gitPath, "Helper reached the native Git fetch after successful HTTPS preparation");
                final var credential =
                        "protocol=https\nhost=localhost:" + proxy.getAddress().getPort() + "\npath=" + gitPath + "\n\n";
                final var credentialCommand = helper(store, flags, "credential", "get");
                final var accepted = run(repo, credentialCommand, environment, credential);
                assertEquals(0, accepted.exit());
                assertEquals(
                        "username=context\npassword=" + environment.get(ContextHttpServer.GIT_TOKEN_ENV) + "\n\n",
                        accepted.output());
                for (final var invalid : List.of(
                        credential.replace("protocol=https", "protocol=http"),
                        credential.replace("host=localhost:", "host=attacker.invalid:"),
                        credential.replace(gitPath, gitPath + "/other"))) {
                    final var denied = run(repo, credentialCommand, environment, invalid);
                    assertNotEquals(0, denied.exit());
                    assertTrue(VALIDATION_FAILURE.matcher(denied.output()).matches(), denied.output());
                }
                for (final var file :
                        List.of(repo.resolve(".git/config"), repo.resolve(".git/yca-broker.properties"))) {
                    final var contents = Files.readString(file);
                    for (final var secret : List.of(
                            environment.get(ContextHttpServer.CONTROL_TOKEN_ENV),
                            environment.get(ContextHttpServer.GIT_TOKEN_ENV),
                            environment.get("GH_TOKEN"))) {
                        assertFalse(contents.contains(secret), file.toString());
                        assertFalse(initialized.output().contains(secret));
                        assertFalse(expanded.output().contains(secret));
                    }
                }
            }
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void nativeFixtureTransportsPreserveQuotedArgumentsAndExactRemoteMapping() throws Exception {
        final var fork = launcher(
                temporary.resolve("fork with 'quotes'"),
                ContextServiceTest.ForkTransport.class,
                repository.directory().toUri().toASCIIString());
        int index = 0;
        for (final var transport : List.of(
                List.of(repository.executable().toString(), "https://github.com/acme/primary.git"),
                List.of(fork.toString(), "https://github.com/acme/primary.git"),
                List.of(fork.toString(), "https://github.com/acme/extra.git"))) {
            final var target = temporary.resolve("clone 'quoted' " + index++);
            final var cloned = run(
                    temporary,
                    List.of(transport.getFirst(), "clone", "--template=", transport.getLast(), target.toString()),
                    Map.of(),
                    "");
            assertEquals(0, cloned.exit(), cloned.output());
            assertEquals(repository.head(), git(target, "rev-parse", "HEAD").strip());
            assertEquals(repository.old(), git(target, "rev-parse", "v1^{}").strip());
            assertEquals("tag", git(target, "cat-file", "-t", "v1").strip());
            assertEquals("source\n", Files.readString(target.resolve("src/code.txt")));
            assertEquals("guide\n", Files.readString(target.resolve("docs/guide.txt")));
            // Spaces, quotes, shell syntax and URL substrings must remain one literal argument.
            final var literal = "https://github.com/acme/primary.git 'quoted' ; $(touch unexpected)";
            final var configured = run(
                    temporary,
                    List.of(transport.getFirst(), "-C", target.toString(), "config", "test.literal", literal),
                    Map.of(),
                    "");
            assertEquals(0, configured.exit(), configured.output());
            assertEquals(literal, git(target, "config", "test.literal").strip());
            assertFalse(Files.exists(temporary.resolve("unexpected")));
        }
    }

    private static List<String> helper(final Path store, final List<String> flags, final String... operation) {
        final var command = TestJava.javaCommand(
                "-Djavax.net.ssl.trustStore=" + store,
                "-Djavax.net.ssl.trustStorePassword=" + STORE_PASSWORD,
                "-Djavax.net.ssl.trustStoreType=PKCS12",
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                AgentBrokerSource.class.getName());
        command.addAll(flags);
        command.addAll(List.of(operation));
        return command;
    }

    private Result run(
            final Path directory, final List<String> command, final Map<String, String> environment, final String input)
            throws Exception {
        final var log = Files.createTempFile(temporary, "process-", ".log");
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
            try (final var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Child exceeded test deadline");
            return new Result(process.exitValue(), Files.readString(log));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }

    private record Result(int exit, String output) {}
}
