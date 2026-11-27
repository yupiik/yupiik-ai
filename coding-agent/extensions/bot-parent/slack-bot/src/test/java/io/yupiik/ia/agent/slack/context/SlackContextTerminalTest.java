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

import io.yupiik.ia.agent.bot.runtime.AttemptPreparation;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackThreadConfiguration;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlackContextTerminalTest {
    @TempDir
    Path root;

    @Test
    void revokedAttemptsServeOnlyTerminalStatusesWithoutFurtherUpstreamCalls() throws Exception {
        final var configuration = new SlackContextConfiguration(
                true,
                true,
                true,
                List.of("C1"),
                30,
                8,
                4,
                5,
                32768,
                20,
                1,
                600,
                false,
                new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
                false);
        try (final var fixture = new SlackDomainFixture(root, 4, 2);
                final var dispatcher = new SlackContextAttempt.Dispatcher(configuration)) {
            for (final var reason : SlackActionRegistry.Terminal.values()) {
                try (final var actions = new SlackActionRegistry(configuration, Clock.systemUTC())) {
                    final var queued = task("terminal-" + reason, "U1", TaskStatus.queued, System.currentTimeMillis());
                    actions.admitted(queued, "action-terminal-secret", queued.thread(), System.currentTimeMillis());
                    final var grant =
                            actions.claim(queued, 0, System.currentTimeMillis()).orElseThrow();
                    final var directory = new AttemptPreparation(fixture.json)
                            .prepare(Path.of(fixture.configuration.agentConfiguration()), root, "yca-terminal-");
                    try (final var attempt = new SlackContextAttempt(
                            directory,
                            fixture.json,
                            configuration,
                            fixture.api,
                            grant,
                            Clock.systemUTC(),
                            dispatcher)) {
                        final var document = fixture.json.fromString(
                                AgentConfigurationDocument.class,
                                Files.readString(directory.resolve("configuration.json")));
                        final var binding =
                                (String) ((Map<?, ?>) document.configuration().get("slackContext")).get("channel");
                        final var mailbox = directory.resolve("storage/slack-context");
                        final var requestPath = Files.createFile(
                                mailbox.resolve("request.json"),
                                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                        grant.revoke(reason);
                        final var expected = reason == SlackActionRegistry.Terminal.REVOKED
                                ? "authorization_unavailable"
                                : "authorization_expired";
                        for (final var operation : List.of("summary", "resolve_user", "search")) {
                            final var request = new SlackContextMailbox.Request(
                                    1,
                                    binding,
                                    UUID.randomUUID().toString(),
                                    System.currentTimeMillis() + 20000,
                                    Map.of("operation", operation));
                            Files.writeString(requestPath, fixture.json.toString(request));
                            attempt.poll();
                            final var response = fixture.json.fromString(
                                    SlackContextMailbox.Response.class,
                                    Files.readString(mailbox.resolve("response.json")));
                            assertEquals(request.id(), response.id());
                            assertEquals(Map.of("status", expected), response.body());
                            assertTrue(
                                    fixture.exchanges.isEmpty(),
                                    "Terminal transport must not use Slack or cached data");
                        }
                        assertNull(grant.get());
                    } finally {
                        AttemptPreparation.delete(directory);
                    }
                }
            }
        }
    }
}
