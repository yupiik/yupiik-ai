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
package io.yupiik.ia.agent.slack.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackThreadConfiguration;
import io.yupiik.ia.agent.slack.context.SlackActionRegistry;
import io.yupiik.ia.agent.slack.context.SlackContextService;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Reuses the transport package's real HTTP peer without expanding production visibility. */
@FusionSupport(modules = SlackContextThreadServiceTest.ContextModule.class)
class SlackContextThreadServiceTest {
    private static final long NOW = 1800000000L;
    private static final String ROOT = "1799827200.000001"; // Older than the default 24-hour reply window.
    private static final String CUTOFF = "1800000000.000100";

    @Test
    void initialSnapshotAndSingleUsePagesAreEarliestFirstAndIndependentOfSearchResultBudget(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new Fixture(configuration)) {
            final var initial = new ArrayList<Map<String, Object>>();
            initial.add(message(ROOT, "U2", "old root <@U3>"));
            initial.addAll(replies(1, 19));
            fixture.reply(initial, "cursor-1", true);
            final var first = fixture.service.call(Map.of("operation", "thread"));
            assertEquals(20, messages(first).size());
            assertEquals(ROOT, messages(first).getFirst().get("messageTs"));
            assertEquals("earliest_first", first.get("order"));
            assertEquals(true, first.get("truncated"));
            assertEquals(CUTOFF, first.get("cutoffTs"));
            final var request = fixture.http.take(fixture.http.requests);
            assertEquals("/api/conversations.replies", request.path());
            assertEquals(
                    "channel=C1&ts=" + ROOT + "&oldest=" + ROOT + "&latest=" + CUTOFF + "&inclusive=true&limit=20",
                    request.body());
            assertFalse(request.body().contains("action-secret"));
            assertFalse(first.toString().contains("cursor-1"));
            final var token = first.get("pageToken");
            for (final var wrong : List.of(
                    Map.<String, Object>of("operation", "search", "pageToken", token),
                    Map.<String, Object>of("operation", "thread", "limit", 21, "pageToken", token),
                    Map.<String, Object>of("operation", "thread", "threadTs", ROOT, "pageToken", token),
                    Map.<String, Object>of("operation", "thread", "threadTs", "1799999999.1", "pageToken", token))) {
                assertThrows(IllegalArgumentException.class, () -> fixture.service.call(wrong));
            }
            assertTrue(fixture.http.requests.isEmpty());
            fixture.reply(replies(20, 20), "cursor-2", true);
            final var second = fixture.service.call(Map.of("operation", "thread", "pageToken", token));
            assertEquals(20, messages(second).size());
            assertTrue(fixture.http.take(fixture.http.requests).body().endsWith("&cursor=cursor-1"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "pageToken", token)));
            fixture.reply(replies(40, 20), "cursor-3", true);
            final var third = fixture.service.call(Map.of("operation", "thread", "pageToken", second.get("pageToken")));
            assertEquals(20, messages(third).size());
            assertEquals(true, third.get("truncated"));
            assertFalse(third.containsKey("pageToken"));
            fixture.http.take(fixture.http.requests);
            final var summary = fixture.service.call(Map.of("operation", "summary"));
            assertEquals(20, summary.get("remainingResults"));
            assertEquals(ROOT, summary.get("threadRoot"));
            assertEquals(CUTOFF, summary.get("triggerTs"));
            assertEquals(60, ((Map<?, ?>) summary.get("thread")).get("messages"));
            assertEquals(3, ((Map<?, ?>) summary.get("thread")).get("requests"));
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void pageLimitsAreOneToTwentyAndTotalMessageBudgetIsIndependent(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                new SlackThreadConfiguration(
                        configuration.thread().enabled(),
                        configuration.thread().channels(),
                        configuration.thread().initialMessages(),
                        30,
                        configuration.thread().maxBytes(),
                        configuration.thread().maxRequests(),
                        configuration.thread().lookbackHours()),
                configuration.allowContainerLogs()))) {
            for (final var limit : List.of(0, 21, 60, 1.5)) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.service.call(Map.of("operation", "thread", "limit", limit)));
            }
            assertTrue(fixture.http.requests.isEmpty());
            fixture.reply(replies(1, 20), "next", true);
            final var first = fixture.service.call(Map.of("operation", "thread", "limit", 20));
            assertEquals(20, messages(first).size());
            fixture.http.take(fixture.http.requests);
            fixture.reply(replies(21, 10), "more", true);
            final var second = fixture.service.call(
                    Map.of("operation", "thread", "limit", 20, "pageToken", first.get("pageToken")));
            assertEquals(10, messages(second).size());
            assertEquals(true, second.get("truncated"));
            assertFalse(second.containsKey("pageToken"));
            assertTrue(fixture.http.take(fixture.http.requests).body().contains("&limit=10&"));
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
        }
    }

    @Test
    void rejectsPostAdmissionMessagesEditsAndForeignBindingsButAcceptsUntrustedBots(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new Fixture(configuration)) {
            final var input = new ArrayList<Map<String, Object>>();
            input.add(message(ROOT, "U2", "root <@U3>"));
            input.add(message("1799827201.000001", "U4", "old reply <@U5>"));
            input.add(changed(message("1799999900.000001", "U6", "wrong thread <@U7>"), "thread_ts", "1.1"));
            input.add(changed(message("1799999901.000001", "U8", "foreign <@U9>"), "channel", "C2"));
            input.add(changed(message("1799999902.000001", "U10", "foreign <@U11>"), "team", "T2"));
            input.add(changed(message("1799999903.000001", "U12", "edited <@U13>"), "edited", Map.of("ts", CUTOFF)));
            input.add(changed(message("1799999904.000001", "U14", "hidden <@U15>"), "hidden", true));
            final var bot = message("1799999905.000001", "U16", "bot answer <@U17>");
            bot.put("bot_id", "B1");
            bot.put("subtype", "bot_message");
            input.add(bot);
            final var userlessBot = message("1799999906.000001", "U18", "another bot");
            userlessBot.remove("user");
            userlessBot.put("bot_id", "B2");
            input.add(userlessBot);
            input.add(message(CUTOFF, "U19", "current <@U20>"));
            input.add(message("1800000001.000001", "U21", "future <@U22>"));
            fixture.reply(input, null, false);
            final var result = fixture.service.call(Map.of("operation", "thread"));
            assertEquals(3, messages(result).size());
            assertEquals(true, result.get("untrusted"));
            assertEquals(true, result.get("truncated"));
            assertEquals("B2", messages(result).getLast().get("botId"));
            assertFalse(messages(result).getLast().containsKey("authorUserId"));
            assertEquals(
                    List.of("U1", "U2", "U3", "U16", "U17"),
                    fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
            fixture.http.take(fixture.http.requests);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of("operation", "resolve_user", "userId", "U19")));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void rejectsUnorderedDuplicateAndMalformedPagesWithoutGrantExpansion(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        for (final var input : List.of(
                List.of(message("1799999902.1", "U2", "second"), message("1799999901.1", "U3", "first")),
                List.of(message("1799999901.1", "U2", "one"), message("1799999901.1", "U3", "duplicate")),
                List.of(message("not-a-timestamp", "U2", "malformed")))) {
            try (final var fixture = new Fixture(configuration)) {
                fixture.reply(input, null, false);
                assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
                assertEquals(
                        List.of("U1"),
                        fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
                fixture.http.take(fixture.http.requests);
            }
        }
        try (final var fixture = new Fixture(configuration)) {
            fixture.reply(replies(1, 1), "next", true);
            final var first = fixture.service.call(Map.of("operation", "thread"));
            fixture.http.take(fixture.http.requests);
            fixture.reply(replies(1, 1), "other", true);
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "pageToken", first.get("pageToken"))));
            fixture.http.take(fixture.http.requests);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "pageToken", first.get("pageToken"))));
            assertTrue(fixture.http.requests.isEmpty());
        }
        try (final var fixture = new Fixture(configuration)) {
            fixture.reply(replies(1, 1), "repeated", true);
            final var first = fixture.service.call(Map.of("operation", "thread"));
            fixture.http.take(fixture.http.requests);
            fixture.reply(replies(2, 1), "repeated", true);
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "pageToken", first.get("pageToken"))));
            fixture.http.take(fixture.http.requests);
        }
    }

    @Test
    void validatedOriginSearchHitAloneAllowsAlternateRootWithIndependentProgressAndSharedLimits(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        final var alternate = "1799999900.000001";
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                new SlackThreadConfiguration(
                        configuration.thread().enabled(),
                        configuration.thread().channels(),
                        1,
                        4,
                        configuration.thread().maxBytes(),
                        4,
                        configuration.thread().lookbackHours()),
                configuration.allowContainerLogs()))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "threadTs", alternate)));
            assertTrue(fixture.http.requests.isEmpty());
            fixture.raw(Map.of("ok", true, "results", Map.of("messages", List.of(searchMessage("C1", "U2", "hit")))));
            fixture.service.call(Map.of("operation", "search"));
            fixture.http.take(fixture.http.requests);

            // The admission thread advances beyond the alternate root before that root starts.
            fixture.reply(List.of(message("1799999950.000001", "U3", "origin reply")), "same-cursor", true);
            final var origin = fixture.service.call(Map.of("operation", "thread"));
            fixture.http.take(fixture.http.requests);
            fixture.reply(
                    List.of(changed(message(alternate, "U4", "alternate root"), "thread_ts", alternate)),
                    "same-cursor",
                    true);
            final var other = fixture.service.call(Map.of("operation", "thread", "threadTs", alternate));
            assertEquals(alternate, other.get("threadTs"));
            assertEquals(1, messages(other).size());
            final var request = fixture.http.take(fixture.http.requests);
            assertTrue(request.body().contains("&ts=" + alternate + "&oldest=" + alternate));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "threadTs", alternate)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "pageToken", other.get("pageToken"))));
            assertTrue(fixture.http.requests.isEmpty());

            fixture.reply(
                    List.of(changed(message("1799999901.000001", "U5", "alternate reply"), "thread_ts", alternate)),
                    null,
                    false);
            fixture.service.call(
                    Map.of("operation", "thread", "threadTs", alternate, "pageToken", other.get("pageToken")));
            fixture.http.take(fixture.http.requests);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(
                            Map.of("operation", "thread", "threadTs", alternate, "pageToken", other.get("pageToken"))));
            fixture.reply(List.of(message("1799999951.000001", "U6", "origin continuation")), "last", true);
            final var last = fixture.service.call(Map.of("operation", "thread", "pageToken", origin.get("pageToken")));
            assertFalse(last.containsKey("pageToken"));
            fixture.http.take(fixture.http.requests);
            final var summary = fixture.service.call(Map.of("operation", "summary"));
            assertEquals(ROOT, summary.get("threadRoot"));
            assertEquals(CUTOFF, summary.get("triggerTs"));
            assertEquals(4, ((Map<?, ?>) summary.get("thread")).get("messages"));
            assertEquals(4, ((Map<?, ?>) summary.get("thread")).get("requests"));
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void rootsHaveIndependentBusyStateButConcurrentResponsesShareTheMessageBudget(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        final var alternate = "1799999900.000001";
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                3,
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                new SlackThreadConfiguration(
                        configuration.thread().enabled(),
                        configuration.thread().channels(),
                        2,
                        2,
                        configuration.thread().maxBytes(),
                        configuration.thread().maxRequests(),
                        configuration.thread().lookbackHours()),
                configuration.allowContainerLogs()))) {
            fixture.raw(Map.of("ok", true, "results", Map.of("messages", List.of(searchMessage("C1", "U2", "hit")))));
            fixture.service.call(Map.of("operation", "search"));
            fixture.http.take(fixture.http.requests);
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var json = fixture.http.json.toString(Map.of(
                    "ok",
                    true,
                    "messages",
                    List.of(
                            message("1799999950.000001", "U9", "origin one"),
                            message("1799999951.000001", "U10", "origin two"))));
            fixture.http.respond(ignored -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return new SlackTransportFixture.Response(200, Map.of(), json);
            });
            final var pending =
                    CompletableFuture.supplyAsync(() -> fixture.service.call(Map.of("operation", "thread")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                fixture.http.take(fixture.http.requests);
                assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
                fixture.reply(
                        List.of(
                                changed(message(alternate, "U3", "alternate root"), "thread_ts", alternate),
                                changed(message("1799999901.000001", "U4", "alternate reply"), "thread_ts", alternate)),
                        null,
                        false);
                assertEquals(
                        2,
                        messages(fixture.service.call(Map.of("operation", "thread", "threadTs", alternate)))
                                .size());
                fixture.http.take(fixture.http.requests);
            } finally {
                release.countDown();
            }
            assertTrue(messages(pending.get(5, TimeUnit.SECONDS)).isEmpty());
            final var summary = fixture.service.call(Map.of("operation", "summary"));
            assertEquals(2, ((Map<?, ?>) summary.get("thread")).get("messages"));
            assertEquals(List.of("U1", "U2", "U3", "U4"), summary.get("grantedUserIds"));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void rejectedSearchEvidenceNeverAuthorizesRootsAndReturnedThreadMustMatchCandidate(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        for (final var invalid : List.of(
                searchMessage("C2", "U2", "other allowlisted channel"),
                changed(searchMessage("C1", "U2", "bad link"), "permalink", "https://evil.invalid/"),
                searchMessage("C1", "invalid", "bad author"),
                changed(searchMessage("C1", "U2", "future"), "message_ts", "1800000001.000001"))) {
            try (final var fixture = new Fixture(new SlackContextConfiguration(
                    configuration.enabled(),
                    configuration.resolveMentions(),
                    configuration.searchEnabled(),
                    List.of("C1", "C2"),
                    configuration.lookbackDays(),
                    configuration.maxMembers(),
                    configuration.maxRequests(),
                    configuration.maxResults(),
                    configuration.maxResponseBytes(),
                    configuration.timeoutSeconds(),
                    configuration.maxConcurrent(),
                    configuration.actionRetentionSeconds(),
                    configuration.allowSwapBackedTmpfs(),
                    new SlackThreadConfiguration(
                            configuration.thread().enabled(),
                            List.of("C1", "C2"),
                            configuration.thread().initialMessages(),
                            configuration.thread().maxMessages(),
                            configuration.thread().maxBytes(),
                            configuration.thread().maxRequests(),
                            configuration.thread().lookbackHours()),
                    configuration.allowContainerLogs()))) {
                fixture.raw(Map.of("ok", true, "results", Map.of("messages", List.of(invalid))));
                assertTrue(messages(fixture.service.call(Map.of("operation", "search")))
                        .isEmpty());
                fixture.http.take(fixture.http.requests);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.service.call(
                                Map.of("operation", "thread", "threadTs", invalid.get("message_ts"))));
                assertTrue(fixture.http.requests.isEmpty());
            }
        }
        try (final var fixture = new Fixture(configuration)) {
            final var alternate = "1799999900.000001";
            fixture.raw(Map.of("ok", true, "results", Map.of("messages", List.of(searchMessage("C1", "U2", "hit")))));
            fixture.service.call(Map.of("operation", "search"));
            fixture.http.take(fixture.http.requests);
            fixture.reply(
                    List.of(
                            message(alternate, "U9", "wrong root <@U10>"),
                            message("1799999901.000001", "U11", "wrong reply")),
                    null,
                    false);
            final var rejected = fixture.service.call(Map.of("operation", "thread", "threadTs", alternate));
            assertTrue(messages(rejected).isEmpty());
            assertEquals(true, rejected.get("truncated"));
            assertEquals(
                    List.of("U1", "U2"),
                    fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
            fixture.http.take(fixture.http.requests);
        }
    }

    @Test
    void searchCutoffStaysAtAdmissionAcrossNewSearchesAndPages(@Fusion final SlackContextConfiguration configuration)
            throws Exception {
        try (final var fixture = new Fixture(configuration)) {
            fixture.clock.now.set(Instant.ofEpochSecond(NOW + 30));
            final var future = searchMessage("C1", "U9", "future");
            future.put("message_ts", "1800000001.000001");
            future.put("permalink", "https://example.slack.com/archives/C1/p1800000001000001");
            fixture.raw(Map.of(
                    "ok",
                    true,
                    "results",
                    Map.of("messages", List.of(future)),
                    "response_metadata",
                    Map.of("next_cursor", "next")));
            final var first = fixture.service.call(Map.of("operation", "search"));
            assertTrue(messages(first).isEmpty());
            assertTrue(fixture.http.take(fixture.http.requests).body().contains("\"before\":" + NOW));
            fixture.clock.now.set(Instant.ofEpochSecond(NOW + 60));
            fixture.service.call(Map.of("operation", "search", "pageToken", first.get("pageToken")));
            assertTrue(fixture.http.take(fixture.http.requests).body().contains("\"before\":" + NOW));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of("operation", "search", "before", NOW + 1)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "threadTs", "1800000001.000001")));
            assertEquals(
                    List.of("U1"),
                    fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void originOnlySearchDiscardsOtherAllowlistedChannelsAndUnmodeledNestedContext(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                List.of("C1", "C2"),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            final var origin = searchMessage("C1", "U2", "safe");
            origin.put("context_messages", List.of(searchMessage("C2", "U3", "nested secret")));
            fixture.raw(Map.of(
                    "ok",
                    true,
                    "results",
                    Map.of("messages", List.of(origin, searchMessage("C2", "U4", "other allowed source")))));
            final var answer = fixture.service.call(Map.of("operation", "search"));
            assertEquals(1, messages(answer).size());
            assertFalse(answer.toString().contains("nested secret"));
            assertFalse(answer.toString().contains("other allowed source"));
            assertEquals(
                    List.of("U1", "U2"),
                    fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
            final var request = fixture.http.take(fixture.http.requests);
            assertTrue(request.body().contains("in:<#C1>"));
            assertFalse(request.body().contains("<#C2>"));
        }
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                List.of("C2"),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "search")));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void explicitThreadChannelPolicyAndResponseEnvelopeNeverWidenOriginScope(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        for (final var override : List.of(
                new SlackContextConfiguration(
                        configuration.enabled(),
                        configuration.resolveMentions(),
                        configuration.searchEnabled(),
                        configuration.sourceChannels(),
                        configuration.lookbackDays(),
                        configuration.maxMembers(),
                        configuration.maxRequests(),
                        configuration.maxResults(),
                        configuration.maxResponseBytes(),
                        configuration.timeoutSeconds(),
                        configuration.maxConcurrent(),
                        configuration.actionRetentionSeconds(),
                        configuration.allowSwapBackedTmpfs(),
                        new SlackThreadConfiguration(
                                false,
                                configuration.thread().channels(),
                                configuration.thread().initialMessages(),
                                configuration.thread().maxMessages(),
                                configuration.thread().maxBytes(),
                                configuration.thread().maxRequests(),
                                configuration.thread().lookbackHours()),
                        configuration.allowContainerLogs()),
                new SlackContextConfiguration(
                        configuration.enabled(),
                        configuration.resolveMentions(),
                        configuration.searchEnabled(),
                        configuration.sourceChannels(),
                        configuration.lookbackDays(),
                        configuration.maxMembers(),
                        configuration.maxRequests(),
                        configuration.maxResults(),
                        configuration.maxResponseBytes(),
                        configuration.timeoutSeconds(),
                        configuration.maxConcurrent(),
                        configuration.actionRetentionSeconds(),
                        configuration.allowSwapBackedTmpfs(),
                        new SlackThreadConfiguration(
                                configuration.thread().enabled(),
                                List.of("C2"),
                                configuration.thread().initialMessages(),
                                configuration.thread().maxMessages(),
                                configuration.thread().maxBytes(),
                                configuration.thread().maxRequests(),
                                configuration.thread().lookbackHours()),
                        configuration.allowContainerLogs()))) {
            try (final var fixture = new Fixture(override)) {
                assertEquals(
                        false,
                        fixture.service.call(Map.of("operation", "summary")).get("threadEnabled"));
                assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
                assertTrue(fixture.http.requests.isEmpty());
            }
        }
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                new SlackThreadConfiguration(
                        configuration.thread().enabled(),
                        List.of("C1", "C2"),
                        configuration.thread().initialMessages(),
                        configuration.thread().maxMessages(),
                        configuration.thread().maxBytes(),
                        configuration.thread().maxRequests(),
                        configuration.thread().lookbackHours()),
                configuration.allowContainerLogs()))) {
            for (final var bad : List.of(
                    Map.<String, Object>of("operation", "thread", "channelId", "C2"),
                    Map.<String, Object>of("operation", "thread", "threadTs", "1799999999.1"))) {
                assertThrows(IllegalArgumentException.class, () -> fixture.service.call(bad));
            }
            assertTrue(fixture.http.requests.isEmpty());
            fixture.raw(Map.of("ok", true, "channel", "C2", "messages", replies(1, 1)));
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
            fixture.http.take(fixture.http.requests);
            fixture.raw(Map.of("ok", true, "team", "T2", "messages", replies(1, 1)));
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
            fixture.http.take(fixture.http.requests);
        }
    }

    @Test
    void budgetsAreCumulativeAndFailedCallsAlsoConsumeSharedRequestAllowance(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                1,
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            fixture.raw(Map.of("ok", false, "error", "missing_scope"));
            assertThrows(
                    SlackWebApi.SlackApiException.class, () -> fixture.service.call(Map.of("operation", "thread")));
            fixture.http.take(fixture.http.requests);
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "search")));
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.call(Map.of("operation", "resolve_user", "userId", "U1")));
            assertTrue(fixture.http.requests.isEmpty());
        }
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                new SlackThreadConfiguration(
                        configuration.thread().enabled(),
                        configuration.thread().channels(),
                        configuration.thread().initialMessages(),
                        configuration.thread().maxMessages(),
                        4096,
                        configuration.thread().maxRequests(),
                        configuration.thread().lookbackHours()),
                configuration.allowContainerLogs()))) {
            fixture.reply(List.of(message("1799999900.1", "U2", "x".repeat(10000))), "next", true);
            final var result = fixture.service.call(Map.of("operation", "thread"));
            assertTrue(messages(result).isEmpty());
            assertEquals(true, result.get("truncated"));
            assertEquals(
                    List.of("U1"),
                    fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
            fixture.http.take(fixture.http.requests);
        }
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                new SlackThreadConfiguration(
                        configuration.thread().enabled(),
                        configuration.thread().channels(),
                        configuration.thread().initialMessages(),
                        configuration.thread().maxMessages(),
                        4096,
                        configuration.thread().maxRequests(),
                        configuration.thread().lookbackHours()),
                configuration.allowContainerLogs()))) {
            fixture.reply(List.of(message("1799999900.1", "U2", "x".repeat(1000))), "next", true);
            final var result = fixture.service.call(Map.of("operation", "thread"));
            assertTrue(messages(result).isEmpty());
            assertEquals(true, result.get("truncated"));
            assertFalse(result.containsKey("pageToken"));
            fixture.http.take(fixture.http.requests);
        }
    }

    @Test
    void threadBytesAndSharedResponseBytesAreCumulativeAndOnlyReturnedMembersAreGranted(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        for (final var policy : List.of(
                new SlackContextConfiguration(
                        configuration.enabled(),
                        configuration.resolveMentions(),
                        configuration.searchEnabled(),
                        configuration.sourceChannels(),
                        configuration.lookbackDays(),
                        configuration.maxMembers(),
                        configuration.maxRequests(),
                        configuration.maxResults(),
                        configuration.maxResponseBytes(),
                        configuration.timeoutSeconds(),
                        configuration.maxConcurrent(),
                        configuration.actionRetentionSeconds(),
                        configuration.allowSwapBackedTmpfs(),
                        new SlackThreadConfiguration(
                                configuration.thread().enabled(),
                                configuration.thread().channels(),
                                configuration.thread().initialMessages(),
                                configuration.thread().maxMessages(),
                                5000,
                                configuration.thread().maxRequests(),
                                configuration.thread().lookbackHours()),
                        configuration.allowContainerLogs()),
                new SlackContextConfiguration(
                        configuration.enabled(),
                        configuration.resolveMentions(),
                        configuration.searchEnabled(),
                        configuration.sourceChannels(),
                        configuration.lookbackDays(),
                        configuration.maxMembers(),
                        configuration.maxRequests(),
                        configuration.maxResults(),
                        5000,
                        configuration.timeoutSeconds(),
                        configuration.maxConcurrent(),
                        configuration.actionRetentionSeconds(),
                        configuration.allowSwapBackedTmpfs(),
                        configuration.thread(),
                        configuration.allowContainerLogs()))) {
            try (final var fixture = new Fixture(policy)) {
                fixture.reply(List.of(message("1799999900.1", "U2", "a".repeat(300))), "next", true);
                final var first = fixture.service.call(Map.of("operation", "thread"));
                assertEquals(1, messages(first).size());
                fixture.http.take(fixture.http.requests);
                fixture.reply(List.of(message("1799999901.1", "U3", "b".repeat(300))), "last", true);
                final var second =
                        fixture.service.call(Map.of("operation", "thread", "pageToken", first.get("pageToken")));
                assertTrue(messages(second).isEmpty());
                assertEquals(true, second.get("truncated"));
                assertFalse(second.containsKey("pageToken"));
                fixture.http.take(fixture.http.requests);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.service.call(Map.of("operation", "resolve_user", "userId", "U3")));
                assertTrue(fixture.http.requests.isEmpty());
            }
        }
        try (final var fixture = new Fixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                3,
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            fixture.reply(List.of(message(ROOT, "U2", "<@U3> <@U4> <@U5>")), null, false);
            fixture.service.call(Map.of("operation", "thread"));
            assertEquals(
                    List.of("U1", "U2", "U3"),
                    fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
            fixture.http.take(fixture.http.requests);
        }
    }

    @Test
    void tokensAreAttemptLocalAndResponsePageCannotExceedRequestedBound(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var first = new Fixture(configuration);
                final var second = new Fixture(configuration)) {
            first.reply(replies(1, 1), "next", true);
            final var result = first.service.call(Map.of("operation", "thread"));
            first.http.take(first.http.requests);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> second.service.call(Map.of("operation", "thread", "pageToken", result.get("pageToken"))));
            assertTrue(second.http.requests.isEmpty());
            second.reply(replies(1, 21), null, false);
            assertThrows(IllegalStateException.class, () -> second.service.call(Map.of("operation", "thread")));
            second.http.take(second.http.requests);
            assertEquals(
                    List.of("U1"),
                    second.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
        }
    }

    @Test
    void inFlightHttpDoesNotHoldStateLockAndAdmissionExpiryCancelsAndDropsLateData(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new Fixture(configuration)) {
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            fixture.http.respond(request -> {
                fixture.http.expectedDisconnectedResponses.add(request);
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return new SlackTransportFixture.Response(200, Map.of(), "{\"ok\":true,\"messages\":[]}");
            });
            final var pending =
                    CompletableFuture.supplyAsync(() -> fixture.service.call(Map.of("operation", "thread")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                final var summary = CompletableFuture.supplyAsync(
                                () -> fixture.service.call(Map.of("operation", "summary")))
                        .get(2, TimeUnit.SECONDS);
                assertEquals(true, summary.get("threadEnabled"));
                assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "thread")));
                fixture.clock.now.set(Instant.ofEpochSecond(NOW + 600));
                fixture.actions.expire(fixture.clock.millis(), () -> Set.of("task"));
                assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "summary")));
            } finally {
                release.countDown();
            }
        }
        try (final var fixture = new Fixture(configuration)) {
            fixture.reply(replies(1, 1), "next", true);
            final var result = fixture.service.call(Map.of("operation", "thread"));
            fixture.http.take(fixture.http.requests);
            fixture.service.close();
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.call(Map.of("operation", "thread", "pageToken", result.get("pageToken"))));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    private static Map<String, Object> changed(
            final Map<String, Object> message, final String key, final Object value) {
        message.put(key, value);
        return message;
    }

    private static Map<String, Object> message(final String ts, final String user, final String text) {
        final var message = new HashMap<String, Object>();
        message.put("type", "message");
        message.put("ts", ts);
        if (!ROOT.equals(ts)) {
            message.put("thread_ts", ROOT);
        }
        message.put("user", user);
        message.put("text", text);
        return message;
    }

    private static List<Map<String, Object>> replies(final int start, final int count) {
        final var replies = new ArrayList<Map<String, Object>>();
        for (int i = start; i < start + count; i++) {
            replies.add(message((NOW - 1000 + i) + ".000001", "U2", "reply"));
        }
        return replies;
    }

    private static Map<String, Object> searchMessage(final String channel, final String user, final String text) {
        return new HashMap<>(Map.of(
                "channel_id",
                channel,
                "team_id",
                "T1",
                "author_user_id",
                user,
                "message_ts",
                "1799999900.000001",
                "content",
                text,
                "permalink",
                "https://example.slack.com/archives/" + channel + "/p1799999900000001"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(final Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("messages");
    }

    public static class ContextModule extends SlackConfigurationTestModule {
        public ContextModule() {
            super(Map.of(
                    "slack.context.enabled",
                    "true",
                    "slack.context.searchEnabled",
                    "true",
                    "slack.context.sourceChannels",
                    "C1",
                    "slack.context.thread.enabled",
                    "true",
                    "slack.context.thread.channels",
                    "C1"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final SlackTransportFixture http = new SlackTransportFixture();
        private final MutableClock clock = new MutableClock();
        private final SlackActionRegistry actions;
        private final SlackContextService service;

        private Fixture(final SlackContextConfiguration configuration) throws Exception {
            actions = new SlackActionRegistry(configuration, clock);
            final var task = new SlackTask(
                    "task",
                    "T1",
                    "C1",
                    ROOT,
                    "U1",
                    "ask",
                    "conversation",
                    TaskStatus.running,
                    clock.millis(),
                    clock.millis(),
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null);
            actions.admitted(task, "action-secret", CUTOFF, clock.millis());
            service = new SlackContextService(
                    configuration,
                    http.api(131072, 131072),
                    actions.claim(task, 0, clock.millis()).orElseThrow(),
                    clock);
        }

        private void reply(final List<Map<String, Object>> messages, final String cursor, final boolean more) {
            raw(Map.of(
                    "ok",
                    true,
                    "messages",
                    messages,
                    "has_more",
                    more,
                    "response_metadata",
                    cursor == null ? Map.of() : Map.of("next_cursor", cursor)));
        }

        private void raw(final Map<String, Object> body) {
            final var json = http.json.toString(body);
            http.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), json));
        }

        @Override
        public void close() {
            service.close();
            actions.close();
            http.close();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(NOW));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
