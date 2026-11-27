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
package io.yupiik.ia.agent.slack.context;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackThreadConfiguration;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import io.yupiik.ia.agent.slack.support.SlackJsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackContextAttemptTest {
    private static final String TOKEN = "action-attempt-private-token";

    @TempDir
    Path root;

    @Test
    void injectsOnlyBoundCapabilityAndReportsMissingSearchAuthorityWithoutHttp() throws Exception {
        final var config = configuration();
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var json = SlackJsonMapper.create();
                final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                final var dispatcher = new SlackContextAttempt.Dispatcher(config)) {
            final var path = prepare(json);
            final var queued = task("no-search-token", "U1", TaskStatus.queued, System.currentTimeMillis());
            actions.admitted(queued, null, queued.thread(), System.currentTimeMillis());
            try (final var attempt = new SlackContextAttempt(
                    path,
                    json,
                    config,
                    fixture.api,
                    actions.claim(queued, 0, System.currentTimeMillis()).orElseThrow(),
                    Clock.systemUTC(),
                    dispatcher)) {
                final var document = json.fromString(
                        AgentConfigurationDocument.class, Files.readString(path.resolve("configuration.json")));
                assertEquals("fake", document.configuration().get("provider"));
                final var injected = (Map<?, ?>) document.configuration().get("slackContext");
                assertEquals(true, injected.get("enabled"));
                assertEquals("/attempt/storage/slack-context", injected.get("directory"));
                final var binding = (String) injected.get("channel");
                assertTrue(SlackContextMailbox.validChannel(binding));
                assertNotEquals(queued.channel(), binding, "Mailbox binding is not upstream channel authority");
                final var mailbox = path.resolve("storage/slack-context");
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(mailbox));
                final var summary = exchange(attempt, mailbox, json, binding, Map.of("operation", "summary"));
                assertEquals("ok", summary.get("status"));
                assertEquals("authorization_unavailable", summary.get("searchAuthorization"));
                assertEquals(List.of("U1"), summary.get("grantedUserIds"));
                final var denied = exchange(
                        attempt, mailbox, json, binding, Map.of("operation", "search", "terms", List.of("release")));
                assertEquals(Map.of("status", "authorization_unavailable"), denied);
                assertTrue(fixture.exchanges.isEmpty(), "Missing action authority must never dispatch search");
                assertRedacted(Files.readString(path.resolve("configuration.json")));
                assertRedacted(json.toString(summary));
                assertRedacted(json.toString(denied));
            }
        }
    }

    @Test
    void duplicateRequestReplaysResponseWithoutRepeatingUpstreamAndMalformedSlotsGrantNothing() throws Exception {
        final var config = configuration();
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var json = SlackJsonMapper.create();
                final var actions = new SlackActionRegistry(config, Clock.systemUTC());
                final var dispatcher = new SlackContextAttempt.Dispatcher(config)) {
            final var path = prepare(json);
            final var queued = task("replay", "U1", TaskStatus.queued, System.currentTimeMillis());
            actions.admitted(queued, TOKEN, queued.thread(), System.currentTimeMillis());
            fixture.response = exchange -> {
                assertEquals("/api/users.info", exchange.request().uri().getPath());
                return """
                        {"ok":true,"user":{"id":"U1","team_id":"T1","deleted":false,"is_bot":false,
                        "profile":{"display_name":"Ada","real_name":"Ada Lovelace"}}}
                        """;
            };
            try (final var attempt = new SlackContextAttempt(
                    path,
                    json,
                    config,
                    fixture.api,
                    actions.claim(queued, 0, System.currentTimeMillis()).orElseThrow(),
                    Clock.systemUTC(),
                    dispatcher)) {
                final var document = json.fromString(
                        AgentConfigurationDocument.class, Files.readString(path.resolve("configuration.json")));
                final var binding =
                        (String) ((Map<?, ?>) document.configuration().get("slackContext")).get("channel");
                final var mailbox = path.resolve("storage/slack-context");
                write(mailbox.resolve("request.json"), "{not json");
                assertDoesNotThrow(attempt::poll);
                assertFalse(Files.exists(mailbox.resolve("response.json")));
                assertTrue(fixture.exchanges.isEmpty());
                final var name =
                        exchange(attempt, mailbox, json, binding, Map.of("operation", "resolve_user", "userId", "U1"));
                assertEquals("Ada", name.get("displayName"));
                assertEquals("ok", name.get("status"));
                final var original = Files.readString(mailbox.resolve("response.json"));
                Files.delete(mailbox.resolve("response.json"));
                attempt.poll();
                assertEquals(original, Files.readString(mailbox.resolve("response.json")));
                assertEquals(1, fixture.exchanges.size());
                assertRedacted(Files.readString(mailbox.resolve("request.json")));
                assertRedacted(original);
                assertRedacted(Files.readString(path.resolve("configuration.json")));
                final var denied = exchange(
                        attempt, mailbox, json, binding, Map.of("operation", "resolve_user", "userId", "UOTHER"));
                assertEquals(Map.of("status", "denied"), denied);
                assertEquals(1, fixture.exchanges.size(), "Unmentioned/unreturned member must not reach users.info");
            }
            assertTrue(
                    actions.claim(queued, 0, System.currentTimeMillis()).isEmpty(),
                    "Attempt teardown must not replenish authority");
        }
    }

    private Path prepare(final JsonMapper json) throws IOException {
        final var path = Files.createDirectory(root.resolve("attempt"));
        Files.createDirectory(path.resolve("storage"));
        Files.writeString(
                path.resolve("configuration.json"),
                json.toString(new AgentConfigurationDocument(Map.of("provider", "fake"), Map.of())));
        return path;
    }

    private Map<String, Object> exchange(
            final SlackContextAttempt attempt,
            final Path mailbox,
            final JsonMapper json,
            final String binding,
            final Map<String, Object> arguments)
            throws Exception {
        Files.deleteIfExists(mailbox.resolve("response.json"));
        final var request = new SlackContextMailbox.Request(
                1, binding, UUID.randomUUID().toString(), System.currentTimeMillis() + 10000, arguments);
        write(mailbox.resolve("request.json"), json.toString(request));
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(mailbox.resolve("response.json")) && System.nanoTime() < deadline) {
            attempt.poll();
            Thread.onSpinWait();
        }
        assertTrue(Files.exists(mailbox.resolve("response.json")), "Attempt must settle the bounded request");
        final var response =
                json.fromString(SlackContextMailbox.Response.class, Files.readString(mailbox.resolve("response.json")));
        assertEquals(request.id(), response.id());
        assertEquals(binding, response.channel());
        return response.body();
    }

    private void write(final Path file, final String content) throws IOException {
        Files.deleteIfExists(file);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, content);
    }

    private static SlackContextConfiguration configuration() {
        return new SlackContextConfiguration(
                true,
                true,
                true,
                List.of("C1"),
                30,
                32,
                12,
                20,
                131072,
                20,
                2,
                600,
                false,
                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                false);
    }

    private static void assertRedacted(final String value) {
        for (final var secret : List.of(TOKEN, "xoxb-test", "xapp-test")) {
            assertFalse(value.contains(secret), "Host credentials must remain outside the capability documents");
        }
    }
}
