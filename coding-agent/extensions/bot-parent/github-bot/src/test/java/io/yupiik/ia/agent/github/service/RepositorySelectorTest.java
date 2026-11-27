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
package io.yupiik.ia.agent.github.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RepositorySelectorTest {
    @Test
    void excludesArchivedAndForksEvenWhenExplicitlyIncluded() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final var name = exchange.getRequestURI().getPath().substring("/repos/".length());
            final var body = ("{\"full_name\":\"" + name + "\",\"archived\":" + name.endsWith("old") + ",\"fork\":"
                            + name.endsWith("fork") + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var client = new GitHubClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        json,
                        () -> "token",
                        BotTestConfiguration.CLOCK)) {
            final var selected = new RepositorySelector(
                            client,
                            List.of(),
                            List.of("o/old", "o/fork", "o/live"),
                            List.of(),
                            Set.of(),
                            Duration.ZERO,
                            Clock.systemUTC(),
                            true,
                            true)
                    .select();
            assertEquals(
                    List.of("o/live"),
                    selected.stream()
                            .map(RepositorySelector.Repository::fullName)
                            .toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void typedDiscoveryExactAddsGlobsFilterThenExclusionVisibilityAndTtl() throws Exception {
        final var calls = new AtomicInteger();
        final var orgCalls = new AtomicInteger();
        final var userCalls = new AtomicInteger();
        final var now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        final var clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            final var path = exchange.getRequestURI().getPath();
            final String body;
            if (path.equals("/orgs/acme/repos")) {
                orgCalls.incrementAndGet();
                if (exchange.getRequestURI().getQuery().contains("page=2")) {
                    body = "[{\"full_name\":\"acme/project-hidden\",\"visibility\":\"private\"}]";
                } else {
                    exchange.getResponseHeaders().add("Link", "<" + base + "/orgs/acme/repos?page=2>; rel=\"next\"");
                    body = "[{\"full_name\":\"acme/project-one\",\"visibility\":\"public\"},"
                            + "{\"full_name\":\"acme/project-skip\",\"visibility\":\"public\"},"
                            + "{\"full_name\":\"acme/other\",\"visibility\":\"public\"}]";
                }
            } else if (path.equals("/users/alice/repos")) {
                userCalls.incrementAndGet();
                body = "[{\"full_name\":\"alice/tool\",\"visibility\":\"public\"}]";
            } else if (path.equals("/repos/extra/pinned")) {
                body = "{\"full_name\":\"extra/pinned\",\"visibility\":\"public\"}";
            } else if (path.equals("/repos/extra/secret")) {
                body = "{\"full_name\":\"extra/secret\",\"visibility\":\"internal\"}";
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            final var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (final var json = BotJsonMapper.create(key -> Optional.empty());
                final var client = new GitHubClient(base, json, () -> "token", BotTestConfiguration.CLOCK)) {
            final var selector = new RepositorySelector(
                    client,
                    List.of(
                            new RepositorySelector.Source(RepositorySelector.SourceKind.ORGANIZATION, "acme"),
                            new RepositorySelector.Source(RepositorySelector.SourceKind.USER, "alice")),
                    List.of("acme/project-*", "alice/*", "extra/pinned", "extra/secret"),
                    List.of("*/project-skip"),
                    Set.of(RepositorySelector.Visibility.PUBLIC),
                    Duration.ofMinutes(5),
                    clock);
            final var expected = List.of("acme/project-one", "alice/tool", "extra/pinned");
            assertEquals(
                    expected,
                    selector.select().stream()
                            .map(RepositorySelector.Repository::fullName)
                            .toList());
            assertEquals(5, calls.get());
            assertEquals(2, orgCalls.get());
            assertEquals(1, userCalls.get());
            assertEquals(
                    expected,
                    selector.select().stream()
                            .map(RepositorySelector.Repository::fullName)
                            .toList());
            assertEquals(5, calls.get());
            now.set(now.get().plusSeconds(301));
            assertEquals(
                    expected,
                    selector.select().stream()
                            .map(RepositorySelector.Repository::fullName)
                            .toList());
            assertEquals(10, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
