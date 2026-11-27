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

import io.yupiik.ia.agent.github.context.agent.ContextClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextHttpServerTest {
    @TempDir
    Path temporary;

    @Test
    void authenticationPrecedesParsingAndUpstreamAndCredentialsAreAudienceBound() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary);
                final var server = new ContextHttpServer(fixture.configuration(""), fixture.service(), fixture.json);
                final var client = HttpClient.newHttpClient()) {
            final var control = server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
            final var git = server.credentials().get(ContextHttpServer.GIT_TOKEN_ENV);
            assertNotEquals(control, git);
            final var rpc = local(server, server.route() + "/rpc");
            assertEquals(401, send(client, rpc, null, "{broken").statusCode());
            assertEquals(401, send(client, rpc, "Bearer invalid", "{broken").statusCode());
            assertEquals(401, send(client, rpc, "Bearer " + git, "{broken").statusCode());
            assertEquals(401, send(client, rpc, basic(control), "{broken").statusCode());
            assertEquals(
                    401,
                    send(
                                    client,
                                    local(
                                            server,
                                            server.route() + "/git/unknown.git/info/refs?service=git-upload-pack"),
                                    basic(control),
                                    "{broken")
                            .statusCode());
            assertEquals(
                    401,
                    send(
                                    client,
                                    local(
                                            server,
                                            server.route() + "/git/unknown.git/info/refs?service=git-upload-pack"),
                                    "Bearer " + control,
                                    "{broken")
                            .statusCode());
            assertEquals(List.of("/repos/acme/primary"), fixture.requests);
            final var summary = send(client, rpc, "Bearer " + control, "{\"operation\":\"summary\"}");
            assertEquals(200, summary.statusCode());
            assertEquals(
                    "no-store", summary.headers().firstValue("cache-control").orElseThrow());
            assertEquals("fixed-task", body(fixture.parse(summary.body())).get("taskId"));
            assertEquals("https://worker.example.invalid" + server.route() + "/rpc", server.endpoint());
            assertFalse(summary.body().contains(control));
            assertFalse(summary.body().contains(git));
        }
    }

    @Test
    void typedEnvelopesPreserveWireShapeAndInteroperateWithContextClient() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary);
                final var server = new ContextHttpServer(fixture.configuration(""), fixture.service(), fixture.json);
                final var http = HttpClient.newHttpClient()) {
            final var token = server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
            final var rpc = local(server, server.route() + "/rpc");
            final var client = new ContextClient(rpc, fixture.json, () -> token);
            for (final var operation : List.of("summary", "repositories")) {
                final var response = send(http, rpc, "Bearer " + token, "{\"operation\":\"" + operation + "\"}");
                assertEquals(200, response.statusCode());
                final var envelope = fixture.parse(response.body());
                assertEquals(Set.of("ok", "body"), envelope.keySet());
                assertEquals(true, envelope.get("ok"));
                final var body = envelope.get("body");
                assertEquals(
                        body instanceof List<?> ? Map.of("data", body) : body,
                        client.call(Map.of("operation", operation)));
            }
            final var rejected = send(http, rpc, "Bearer " + token, "{broken");
            assertEquals(400, rejected.statusCode());
            assertEquals(Map.of("ok", false, "error", "CONTEXT_JSON_INVALID"), fixture.parse(rejected.body()));
            assertEquals(
                    "no-store", rejected.headers().firstValue("cache-control").orElseThrow());
            assertFalse(rejected.body().contains(token));
        }
    }

    @Test
    void tokensCannotCrossAttemptsRoutesOrTaskSelectionAndBrowserOriginsAreRejected() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary);
                final var first = new ContextHttpServer(fixture.configuration(""), fixture.service(), fixture.json);
                final var second = new ContextHttpServer(fixture.configuration(""), fixture.service(), fixture.json);
                final var client = HttpClient.newHttpClient()) {
            final var auth = "Bearer " + first.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
            assertEquals(
                    401,
                    send(client, local(second, second.route() + "/rpc"), auth, "{broken")
                            .statusCode());
            assertEquals(
                    401,
                    send(client, local(first, second.route() + "/rpc"), auth, "{broken")
                            .statusCode());
            assertEquals(
                    401,
                    send(client, local(first, first.route() + "/tasks/other/rpc"), auth, "{broken")
                            .statusCode());
            assertEquals(
                    400,
                    send(
                                    client,
                                    local(first, first.route() + "/rpc"),
                                    auth,
                                    "{\"operation\":\"summary\",\"taskId\":\"other\"}")
                            .statusCode());
            final var browser = HttpRequest.newBuilder(local(first, first.route() + "/rpc"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .header("Origin", "https://browser.example.invalid")
                    .POST(HttpRequest.BodyPublishers.ofString("{broken"))
                    .build();
            assertEquals(
                    400,
                    client.send(browser, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(List.of("/repos/acme/primary", "/repos/acme/primary"), fixture.requests);
        }
    }

    @Test
    void expiredAndRevokedCredentialsFailBeforeParsingOrUpstream() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary);
                final var server = new ContextHttpServer(fixture.configuration(""), fixture.service(), fixture.json);
                final var client = HttpClient.newHttpClient()) {
            final var auth = "Bearer " + server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
            fixture.clock.advance(Duration.ofSeconds(60));
            assertEquals(
                    401,
                    send(client, local(server, server.route() + "/rpc"), auth, "{broken")
                            .statusCode());
            assertEquals(List.of("/repos/acme/primary"), fixture.requests);
        }
        try (final var fixture = new ContextTestSupport(temporary.resolve("revoked"));
                final var client = HttpClient.newHttpClient()) {
            final var service = fixture.service();
            try (final var server = new ContextHttpServer(fixture.configuration(""), service, fixture.json)) {
                final var auth = "Bearer " + server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
                service.close();
                assertEquals(
                        401,
                        send(client, local(server, server.route() + "/rpc"), auth, "{broken")
                                .statusCode());
                Assertions.assertDoesNotThrow(server::close);
                assertTrue(server.credentials().isEmpty());
                assertThrows(IllegalStateException.class, service::check);
            }
        }
    }

    @Test
    void expiryAutonomouslyKillsStalledPrivateGitPreparation() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary);
                final var client = HttpClient.newHttpClient()) {
            final var pidFile = temporary.resolve("admitted.pid");
            final var executable = launcher(temporary.resolve("StalledGit"), StalledGit.class, pidFile.toString());
            final var service = fixture.service(
                    fixture.configuration(""), new ContextBudget(4096, 16 * 1024 * 1024), PIN, executable.toString());
            try (final var server = new ContextHttpServer(fixture.configuration(""), service, fixture.json)) {
                final var auth = "Bearer " + server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
                final var pending = send(
                        client,
                        local(server, server.route() + "/rpc"),
                        auth,
                        "{\"operation\":\"prepare_source\",\"depth\":1}");
                assertEquals(200, pending.statusCode());
                assertEquals("pending", body(fixture.parse(pending.body())).get("status"));
                await(() -> Files.exists(pidFile));
                final var process = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                        .orElseThrow();
                try {
                    assertTrue(process.isAlive());
                    assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                        fixture.clock.advance(Duration.ofSeconds(60));
                        // No further HTTP request or explicit close triggers revocation/cancellation.
                        await(() -> server.credentials().isEmpty());
                        await(() -> !process.isAlive());
                    });
                    assertThrows(IllegalStateException.class, service::check);
                    await(() -> {
                        try (final var paths = Files.list(temporary.resolve("private-0"))) {
                            return paths.findAny().isEmpty();
                        } catch (final IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    });
                } finally {
                    process.destroyForcibly();
                }
            }
        }
    }

    @Test
    void rejectsUnknownFieldsOperationsUrlsMalformedPagesAndOversizedBodiesWithoutUpstream() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary);
                final var server = new ContextHttpServer(fixture.configuration(""), fixture.service(), fixture.json);
                final var client = HttpClient.newHttpClient()) {
            final var auth = "Bearer " + server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
            final var rpc = local(server, server.route() + "/rpc");
            for (final var body : List.of(
                    "{broken",
                    "{\"operation\":\"https://attacker.invalid\"}",
                    "{\"operation\":\"summary\",\"url\":\"https://attacker.invalid\"}",
                    "{\"operation\":\"issueComments\",\"page\":0}",
                    "{\"operation\":\"issueComments\",\"page\":-1}",
                    "{\"operation\":\"issueComments\",\"page\":1.5}",
                    "{\"operation\":\"issueComments\",\"page\":\"1\"}",
                    "{\"operation\":true}",
                    "{\"operation\":\"summary\",\"unknown\":null}",
                    "null",
                    "true",
                    "[]")) {
                assertEquals(400, send(client, rpc, auth, body).statusCode(), body);
            }
            final var valid = "{\"operation\":\"summary\"}";
            assertEquals(
                    200,
                    send(client, rpc, auth, valid + " ".repeat(16 * 1024 - valid.length()))
                            .statusCode());
            assertEquals(
                    409,
                    send(client, rpc, auth, valid + " ".repeat(16 * 1024 + 1 - valid.length()))
                            .statusCode());
            // Multibyte padding verifies the bound applies to bytes, not Java character count.
            final var oversized = "{\"operation\":\"summary\"}" + "\u00e9".repeat(8192);
            assertTrue(oversized.getBytes(StandardCharsets.UTF_8).length > 16 * 1024);
            assertEquals(409, send(client, rpc, auth, oversized).statusCode());
            final var chunked = HttpRequest.newBuilder(rpc)
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", auth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(
                            () -> new ByteArrayInputStream(oversized.getBytes(StandardCharsets.UTF_8))))
                    .build();
            assertEquals(
                    409,
                    client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(List.of("/repos/acme/primary"), fixture.requests);
        }
    }

    @Test
    void httpAndDirectCallsConsumeOneSharedRequestAndResponseBudget() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary);
                final var client = HttpClient.newHttpClient()) {
            final var service = fixture.service(
                    fixture.configuration(""), new ContextBudget(1, 1024 * 1024), PIN, "/not/executed/git");
            try (final var server = new ContextHttpServer(fixture.configuration(""), service, fixture.json)) {
                service.call(Map.of("operation", "summary"));
                final var auth = "Bearer " + server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
                assertEquals(
                        409,
                        send(client, local(server, server.route() + "/rpc"), auth, "{\"operation\":\"summary\"}")
                                .statusCode());
            }
            final var limited =
                    fixture.service(fixture.configuration(""), new ContextBudget(10, 1), PIN, "/not/executed/git");
            try (final var server = new ContextHttpServer(fixture.configuration(""), limited, fixture.json)) {
                final var auth = "Bearer " + server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
                assertEquals(
                        409,
                        send(client, local(server, server.route() + "/rpc"), auth, "{\"operation\":\"summary\"}")
                                .statusCode());
                assertThrows(IllegalStateException.class, () -> limited.call(Map.of("operation", "summary")));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void preparesSourceAsynchronouslyAndNativeGitFetchesOnlyThePinnedShallowGeneration() throws Exception {
        try (final var fixture = new ContextTestSupport(temporary);
                final var client = HttpClient.newHttpClient()) {
            final var repository = fixture.gitFixture();
            final var service = fixture.service(
                    fixture.configuration(""),
                    new ContextBudget(4096, 16 * 1024 * 1024),
                    repository.head(),
                    repository.executable().toString());
            try (final var server = new ContextHttpServer(fixture.configuration(""), service, fixture.json)) {
                final var control = "Bearer " + server.credentials().get(ContextHttpServer.CONTROL_TOKEN_ENV);
                final var rpc = local(server, server.route() + "/rpc");
                final var pending = send(client, rpc, control, "{\"operation\":\"prepare_source\",\"depth\":1}");
                assertEquals(200, pending.statusCode());
                final var operation = body(fixture.parse(pending.body()));
                assertEquals("pending", operation.get("status"));
                Map<String, Object> status;
                final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                do {
                    final var poll = send(
                            client,
                            rpc,
                            control,
                            fixture.json.toString(Map.of(
                                    "operation", "operation_status", "operationId", operation.get("operationId"))));
                    assertEquals(200, poll.statusCode());
                    status = body(fixture.parse(poll.body()));
                    assertTrue(System.nanoTime() < deadline, "Preparation exceeded bounded deadline");
                    if ("pending".equals(status.get("status"))) {
                        LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
                    }
                } while ("pending".equals(status.get("status")));
                assertEquals("complete", status.get("status"), fixture.json.toString(status));
                final var source = (Map<String, Object>) status.get("source");
                assertEquals(repository.head(), source.get("headSha"));
                final var destination = Files.createDirectory(temporary.resolve("consumer"));
                git(destination, "init", "--template=");
                final var gitAuth = basic(server.credentials().get(ContextHttpServer.GIT_TOKEN_ENV));
                // Explicit test client header: never persist the attempt credential in Git config or a remote URL.
                git(
                        destination,
                        "-c",
                        "http.extraHeader=Authorization: " + gitAuth,
                        "fetch",
                        "--depth=1",
                        "--no-tags",
                        "--",
                        local(server, server.route() + "/" + source.get("gitPath"))
                                .toASCIIString(),
                        "HEAD");
                assertEquals(
                        repository.head(),
                        git(destination, "rev-parse", "FETCH_HEAD").strip());
                assertEquals(
                        "1",
                        git(destination, "rev-list", "--count", "FETCH_HEAD").strip());
                assertEquals(
                        repository.head(),
                        Files.readString(destination.resolve(".git/shallow")).strip());
                assertFalse(Files.readString(destination.resolve(".git/config")).contains(gitAuth));
                // Stable repository identity is checked at discovery and on both sides of acquisition.
                assertEquals(
                        List.of("/repos/acme/primary", "/repos/acme/primary", "/repos/acme/primary"), fixture.requests);
            }
        }
    }

    private static URI local(final ContextHttpServer server, final String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static String basic(final String token) {
        return "Basic " + Base64.getEncoder().encodeToString(("context:" + token).getBytes(StandardCharsets.US_ASCII));
    }

    private static HttpResponse<String> send(
            final HttpClient client, final URI uri, final String authorization, final String body) throws Exception {
        final var request =
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
