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

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.context.SlackActionRegistry;
import io.yupiik.ia.agent.slack.context.SlackContextService;
import io.yupiik.ia.agent.slack.model.*;
import io.yupiik.ia.agent.slack.support.ControlledHttpClient;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import io.yupiik.ia.agent.slack.support.SlackJsonMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** In the transport package solely to reuse the real HTTP fixture without widening production visibility. */
@FusionSupport(modules = SlackContextServiceTest.ContextModule.class)
class SlackContextServiceTest {
    private static final long NOW = 1800000000L;
    private static final String TS = "1799999900.000001";
    private static final String LINK = "https://example.slack.com/archives/C1/p1799999900000001";

    @Test
    void permanentActionRejectionRevokesAllOperationsWithoutAnUpstreamReplayLatch(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        for (final var code : List.of("invalid_action_token", "action_token_expired", "expired_action_token")) {
            try (final var fixture = new ContextFixture(configuration)) {
                fixture.reply("{\"ok\":false,\"error\":\"" + code + "\"}");
                assertThrows(
                        SlackWebApi.SlackApiException.class, () -> fixture.service.call(Map.of("operation", "search")));
                fixture.http.take(fixture.http.requests);
                assertNull(fixture.grant.get());
                assertEquals("authorization_expired", fixture.grant.status());
                for (final var operation : List.of(
                        Map.<String, Object>of("operation", "summary"),
                        Map.<String, Object>of("operation", "search"),
                        Map.<String, Object>of("operation", "resolve_user", "userId", "U1"))) {
                    assertThrows(IllegalStateException.class, () -> fixture.service.call(operation));
                }
                assertEquals(1, fixture.http.extended.getRequestCount());
                assertTrue(fixture.http.requests.isEmpty());
            }
        }
    }

    @Test
    void workerStartCannotRenewAdmissionDeadlineAndMemberOnlySummaryHasNoSearchAuthority(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(
                new SlackContextConfiguration(
                        configuration.enabled(),
                        configuration.resolveMentions(),
                        false,
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
                        configuration.thread(),
                        configuration.allowContainerLogs()),
                null)) {
            fixture.clock.advance(400);
            try (final var delayed =
                    new SlackContextService(fixture.configuration, fixture.api, fixture.grant, fixture.clock)) {
                final var summary = delayed.call(Map.of("operation", "summary"));
                assertEquals(NOW + 600, summary.get("expiresAt"));
                assertEquals(false, summary.get("searchEnabled"));
                assertEquals(List.of("U1", "U2", "W3", "U4"), summary.get("grantedUserIds"));
                fixture.reply(member("U1", "T1", false, false));
                assertEquals(
                        "U1",
                        delayed.call(Map.of("operation", "resolve_user", "userId", "U1"))
                                .get("userId"));
                fixture.http.take(fixture.http.requests);
                fixture.clock.advance(200);
                assertThrows(IllegalStateException.class, () -> delayed.call(Map.of("operation", "summary")));
                assertThrows(
                        IllegalStateException.class,
                        () -> delayed.call(Map.of("operation", "resolve_user", "userId", "U1")));
                assertTrue(
                        fixture.http.requests.isEmpty(), "Even cached member lookups require live admission authority");
            }
        }
    }

    @Test
    void maintenanceCancelsAControlledUpstreamFutureWithoutPollingAndDropsLateCompletion(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        for (final var cancellable : List.of(true, false)) {
            final var upstream = new ControlledHttpClient(cancellable);
            final var clock = new MutableClock();
            try (final var json = SlackJsonMapper.create();
                    final var actions = new SlackActionRegistry(configuration, clock);
                    final var http = upstream) {
                final var task = task("controlled-expiry", "U1", TaskStatus.running, clock.millis());
                actions.admitted(task, "action-secret", task.thread(), clock.millis());
                final var grant = actions.claim(task, 0, clock.millis()).orElseThrow();
                final var api = new SlackWebApi(
                        json,
                        http,
                        new SlackWebApi.Settings(
                                URI.create("https://slack.invalid/api/"), Duration.ofSeconds(10), 131072, 131072),
                        () -> "xapp-secret",
                        () -> "xoxb-secret");
                try (final var service = new SlackContextService(configuration, api, grant, clock)) {
                    final var result = CompletableFuture.supplyAsync(
                            () -> service.call(Map.of("operation", "resolve_user", "userId", "U1")));
                    assertTrue(upstream.invoked.await(5, TimeUnit.SECONDS));
                    clock.advance(600);
                    actions.expire(clock.millis(), () -> Set.of(task.id()));
                    assertTrue(upstream.cancelled.await(5, TimeUnit.SECONDS));
                    assertEquals(cancellable, upstream.exchange().isCancelled());
                    // The service future must settle even when the HTTP future refuses cancellation.
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                    upstream.completeBody(member("U1", "T1", false, false));
                    assertEquals(cancellable, upstream.exchange().isCancelled());
                    assertTrue(upstream.exchange().isDone());
                    assertEquals(cancellable, upstream.exchange().isCompletedExceptionally());
                    assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                    assertThrows(IllegalStateException.class, () -> service.call(Map.of("operation", "summary")));
                    assertThrows(
                            IllegalStateException.class,
                            () -> service.call(Map.of("operation", "resolve_user", "userId", "U1")));
                    Assertions.assertDoesNotThrow(actions::close);
                    Assertions.assertDoesNotThrow(service::close);
                    assertNull(grant.get());
                }
            }
        }
    }

    @Test
    void grantsOnlyRequesterAndExplicitMentionsAndCachesOnlyMinimalNames(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
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
            fixture.reply(member("U2", "T1", false, false));
            assertEquals(
                    List.of("U1", "U2", "W3"),
                    fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds"));
            assertThrows(IllegalArgumentException.class, () -> fixture.resolve("U4"));
            assertThrows(IllegalArgumentException.class, () -> fixture.resolve("U9"));
            final var resolved = fixture.resolve("U2");
            assertEquals(
                    Map.of("userId", "U2", "displayName", "Display", "realName", "Real", "untrusted", true), resolved);
            final var request = fixture.http.take(fixture.http.requests);
            assertEquals("/api/users.info", request.path());
            assertEquals("user=U2", request.body());
            assertTrue(request.headers().get("content-type").startsWith("application/x-www-form-urlencoded"));
            assertEquals(resolved, fixture.resolve("U2"));
            assertTrue(fixture.http.requests.isEmpty());
            assertFalse(resolved.toString().contains("private@example.invalid"));
            fixture.reply(searchBody(List.of(message("U5", "T1", "C1", "safe", LINK)), null));
            assertEquals(
                    1,
                    messages(fixture.service.call(Map.of("operation", "search")))
                            .size());
            assertThrows(IllegalArgumentException.class, () -> fixture.resolve("U5"));
        }
    }

    @Test
    void rejectsForeignDeletedBotMismatchedAndIncompleteMembers(@Fusion final SlackContextConfiguration configuration)
            throws Exception {
        try (final var fixture = new ContextFixture(configuration)) {
            for (final var body : List.of(
                    member("U2", "T2", false, false),
                    member("U9", "T1", false, false),
                    member("U2", "T1", true, false),
                    member("U2", "T1", false, true),
                    "{\"ok\":true,\"user\":{\"id\":\"U2\",\"team_id\":\"T1\"}}")) {
                fixture.reply(body);
                final var error = assertThrows(IllegalStateException.class, () -> fixture.resolve("U2"));
                assertNull(error.getCause());
                fixture.http.take(fixture.http.requests);
            }
        }
    }

    @Test
    void requestScopeIsPublicMessagesOnlyWithLiteralTermsAndNoContextExpansion(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(configuration)) {
            fixture.reply(searchBody(List.of(message("U2", "T1", "C1", "hello <@W3>", LINK)), null));
            final var result = fixture.service.call(Map.of(
                    "operation",
                    "search",
                    "authorUserId",
                    "U2",
                    "mentionedUserId",
                    "W3",
                    "terms",
                    List.of("hello world"),
                    "limit",
                    4));
            assertEquals(1, messages(result).size());
            final var request = fixture.http.take(fixture.http.requests);
            assertEquals("/api/assistant.search.context", request.path());
            final var body = fixture.json.fromString(SlackJson.SearchRequest.class, request.body());
            assertEquals("in:<#C1> from:<@U2> \"<@W3>\" \"hello world\"", body.query());
            assertEquals("action-secret", body.action_token());
            assertEquals(List.of("public_channel"), body.channel_types());
            assertEquals(List.of("messages"), body.content_types());
            assertEquals("C1", body.context_channel_id());
            assertEquals(false, body.include_context_messages());
            assertEquals(false, body.include_message_blocks());
            assertEquals(false, body.include_bots());
            assertEquals(true, body.disable_semantic_search());
            assertEquals(NOW - 30 * 86400, body.after());
            assertEquals(NOW, body.before());
            assertEquals(4, body.limit());
            assertFalse(result.toString().contains("action-secret"));
        }
    }

    @Test
    void rejectsQuerySyntaxScopeOverridesUnknownUsersAndOutOfWindowRangesBeforeHttp(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(configuration)) {
            for (final var term : List.of(
                    "in:C2",
                    "foo\" OR in:C2",
                    "OR",
                    "and",
                    "NOT foo",
                    "a\nb",
                    "a\\b",
                    "<@U9>",
                    "foo*",
                    "(foo)",
                    "foo|bar",
                    "-foo")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.service.call(Map.of("operation", "search", "terms", List.of(term))),
                        term);
            }
            for (final var arguments : List.of(
                    Map.<String, Object>of("operation", "search", "authorUserId", "U9"),
                    Map.<String, Object>of("operation", "search", "channel", "C2"),
                    Map.<String, Object>of("operation", "search", "query", "in:C2"),
                    Map.<String, Object>of("operation", "search", "after", NOW - 31 * 86400),
                    Map.<String, Object>of("operation", "search", "before", NOW + 1),
                    Map.<String, Object>of("operation", "search", "after", NOW),
                    Map.<String, Object>of("operation", "search", "limit", 1.5))) {
                assertThrows(IllegalArgumentException.class, () -> fixture.service.call(arguments));
            }
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void validatesEveryResultAndOnlyReturnedAuthorsGainGrants(@Fusion final SlackContextConfiguration configuration)
            throws Exception {
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                6,
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            final var candidates = new ArrayList<Map<String, Object>>();
            candidates.add(message("U9", "T2", "C1", "foreign", LINK));
            candidates.add(message("U9", "T1", "C2", "other", LINK));
            for (final var link : List.of(
                    "https://evil.invalid/archives/C1/p1799999900000001",
                    "http://example.slack.com/archives/C1/p1799999900000001",
                    "https://slack.com.evil.invalid/archives/C1/p1799999900000001",
                    "https://evil@team.slack.com/archives/C1/p1799999900000001",
                    "https://team.slack.com/archives/C2/p1799999900000001",
                    "https://team.slack.com/archives/C1/p1799999900000002",
                    LINK + "?url=https://evil.invalid",
                    LINK + "#fragment")) {
                candidates.add(message("U9", "T1", "C1", "bad link", link));
            }
            final var old = message("U9", "T1", "C1", "old", LINK);
            old.put("message_ts", "1.000001");
            candidates.add(old);
            candidates.add(message("U5", "T1", "C1", "safe", LINK));
            fixture.reply(searchBody(candidates, null));
            final var result = fixture.service.call(Map.of("operation", "search"));
            assertEquals(1, messages(result).size());
            assertEquals("U5", messages(result).getFirst().get("authorUserId"));
            final var grants =
                    fixture.service.call(Map.of("operation", "summary")).get("grantedUserIds");
            assertEquals(List.of("U1", "U2", "W3", "U4", "U5"), grants);
            assertThrows(IllegalArgumentException.class, () -> fixture.resolve("U9"));
        }
    }

    @Test
    void authorAndMentionFiltersAreAlsoCheckedOnReturnedMessages(@Fusion final SlackContextConfiguration configuration)
            throws Exception {
        try (final var fixture = new ContextFixture(configuration)) {
            fixture.reply(searchBody(
                    List.of(
                            message("U5", "T1", "C1", "<@W3>", LINK),
                            message("U2", "T1", "C1", "missing mention", LINK),
                            message("U2", "T1", "C1", "hello <@W3|Name>", LINK)),
                    null));
            assertEquals(
                    1,
                    messages(fixture.service.call(
                                    Map.of("operation", "search", "authorUserId", "U2", "mentionedUserId", "W3")))
                            .size());
        }
    }

    @Test
    void paginationIsOpaqueSingleUseExactQueryBoundAndExpiresWithTheAttempt(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
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
                10,
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            fixture.reply(searchBody(List.of(message("U2", "T1", "C1", "safe", LINK)), "upstream-secret"));
            final var first = fixture.service.call(Map.of("operation", "search", "terms", List.of("safe"), "limit", 1));
            fixture.http.take(fixture.http.requests);
            final var pageToken = (String) first.get("pageToken");
            assertNotNull(pageToken);
            assertNotEquals("upstream-secret", pageToken);
            assertFalse(first.toString().contains("upstream-secret"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of(
                            "operation", "search", "terms", List.of("different"), "limit", 1, "pageToken", pageToken)));
            fixture.clock.advance(1);
            final var second = fixture.service.call(
                    Map.of("operation", "search", "terms", List.of("safe"), "limit", 1, "pageToken", pageToken));
            final var body = fixture.json.fromString(
                    SlackJson.SearchRequest.class,
                    fixture.http.take(fixture.http.requests).body());
            assertEquals("upstream-secret", body.cursor());
            assertEquals(NOW, body.before());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.service.call(Map.of(
                            "operation", "search", "terms", List.of("safe"), "limit", 1, "pageToken", pageToken)));
            fixture.clock.advance(9);
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.service.call(Map.of(
                            "operation",
                            "search",
                            "terms",
                            List.of("safe"),
                            "limit",
                            1,
                            "pageToken",
                            second.get("pageToken"))));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void independentAttemptsCannotUseEachOthersPageTokens(@Fusion final SlackContextConfiguration configuration)
            throws Exception {
        try (final var one = new ContextFixture(configuration);
                final var two = new ContextFixture(configuration)) {
            one.reply(searchBody(List.of(), "cursor"));
            final var token = one.service.call(Map.of("operation", "search")).get("pageToken");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> two.service.call(Map.of("operation", "search", "pageToken", token)));
            assertTrue(two.http.requests.isEmpty());
        }
    }

    @Test
    void disabledFeaturesAndUnlistedSourcesNeverCallSlack(@Fusion final SlackContextConfiguration configuration)
            throws Exception {
        for (final var overrides : List.of(
                new SlackContextConfiguration(
                        configuration.enabled(),
                        configuration.resolveMentions(),
                        false,
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
                        configuration.thread(),
                        configuration.allowContainerLogs()),
                new SlackContextConfiguration(
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
            try (final var fixture = new ContextFixture(overrides)) {
                assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "search")));
                assertTrue(fixture.http.requests.isEmpty());
            }
        }
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
                configuration.enabled(),
                false,
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
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            assertThrows(IllegalArgumentException.class, () -> fixture.resolve("U1"));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void requestFailuresConsumeBudgetAndNeverLeakUpstreamErrorsOrCredentials(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
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
            fixture.reply("{\"ok\":false,\"error\":\"action-secret\"}");
            final var error = assertThrows(IllegalStateException.class, () -> fixture.resolve("U1"));
            assertNull(error.getCause());
            assertFalse(error.toString().contains("secret"));
            fixture.http.take(fixture.http.requests);
            assertThrows(IllegalStateException.class, () -> fixture.resolve("U2"));
            assertTrue(fixture.http.requests.isEmpty());
        }
        try (final var fixture = new ContextFixture(configuration, null)) {
            final var error = assertThrows(
                    IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "search")));
            assertNull(error.getCause());
            assertFalse(error.toString().contains("secret"));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void resultAndByteBudgetsPreventUnboundedOutputAndGrants(@Fusion final SlackContextConfiguration configuration)
            throws Exception {
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                1,
                configuration.maxResponseBytes(),
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            fixture.reply(searchBody(
                    List.of(message("U5", "T1", "C1", "one", LINK), message("U6", "T1", "C1", "two", LINK)), "cursor"));
            final var result = fixture.service.call(Map.of("operation", "search"));
            assertEquals(1, messages(result).size());
            assertFalse(result.containsKey("pageToken"));
            fixture.http.take(fixture.http.requests);
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "search")));
            assertThrows(IllegalArgumentException.class, () -> fixture.resolve("U6"));
            assertTrue(fixture.http.requests.isEmpty());
        }
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                4096,
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            fixture.reply(searchBody(List.of(message("U5", "T1", "C1", "x".repeat(10000), LINK)), null));
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "search")));
            assertThrows(IllegalArgumentException.class, () -> fixture.resolve("U5"));
        }
    }

    @Test
    void expiryDuringAnUpstreamCallDiscardsTheResponseAndRevokesCachedCapabilities(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
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
                2,
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            fixture.http.respond(request -> {
                fixture.clock.advance(2);
                return new SlackTransportFixture.Response(200, Map.of(), member("U1", "T1", false, false));
            });
            assertThrows(IllegalStateException.class, () -> fixture.resolve("U1"));
            fixture.http.take(fixture.http.requests);
            assertThrows(IllegalStateException.class, () -> fixture.service.call(Map.of("operation", "summary")));
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void repeatedCachedResponsesStillConsumeTheCumulativeByteBudget(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                1024,
                configuration.timeoutSeconds(),
                configuration.maxConcurrent(),
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
            fixture.reply(member("U1", "T1", false, false));
            fixture.resolve("U1");
            fixture.http.take(fixture.http.requests);
            var exhausted = false;
            for (var index = 0; index < 10; index++) {
                try {
                    fixture.resolve("U1");
                } catch (final IllegalStateException expected) {
                    exhausted = true;
                    break;
                }
            }
            assertTrue(exhausted);
            assertTrue(fixture.http.requests.isEmpty());
        }
    }

    @Test
    void closeCancelsInFlightWorkAndRejectsLateResponsesWithoutGrantingAuthors(
            @Fusion final SlackContextConfiguration configuration) throws Exception {
        try (final var fixture = new ContextFixture(configuration)) {
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
                return new SlackTransportFixture.Response(
                        200, Map.of(), searchBody(List.of(message("U9", "T1", "C1", "late", LINK)), null));
            });
            final var result = CompletableFuture.supplyAsync(() -> fixture.service.call(Map.of("operation", "search")));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                fixture.service.close();
                assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> fixture.resolve("U9"));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void enforcesPerServiceConcurrencyAndCancelsTimeouts(@Fusion final SlackContextConfiguration configuration)
            throws Exception {
        try (final var fixture = new ContextFixture(new SlackContextConfiguration(
                configuration.enabled(),
                configuration.resolveMentions(),
                configuration.searchEnabled(),
                configuration.sourceChannels(),
                configuration.lookbackDays(),
                configuration.maxMembers(),
                configuration.maxRequests(),
                configuration.maxResults(),
                configuration.maxResponseBytes(),
                1,
                1,
                configuration.actionRetentionSeconds(),
                configuration.allowSwapBackedTmpfs(),
                configuration.thread(),
                configuration.allowContainerLogs()))) {
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
                return new SlackTransportFixture.Response(200, Map.of(), member("U1", "T1", false, false));
            });
            final var result = CompletableFuture.supplyAsync(() -> fixture.resolve("U1"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> fixture.resolve("U2"));
                assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertEquals(1, fixture.http.extended.getRequestCount());
            } finally {
                release.countDown();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(final Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("messages");
    }

    private static Map<String, Object> message(
            final String author, final String team, final String channel, final String content, final String link) {
        return new HashMap<>(Map.of(
                "author_user_id",
                author,
                "team_id",
                team,
                "channel_id",
                channel,
                "message_ts",
                TS,
                "content",
                content,
                "permalink",
                link));
    }

    private static String searchBody(final List<Map<String, Object>> messages, final String cursor) {
        try (final var json = SlackJsonMapper.create()) {
            return json.toString(Map.of(
                    "ok",
                    true,
                    "results",
                    Map.of("messages", messages),
                    "response_metadata",
                    cursor == null ? Map.of() : Map.of("next_cursor", cursor)));
        }
    }

    private static String member(final String id, final String team, final boolean deleted, final boolean bot) {
        return "{\"ok\":true,\"user\":{\"id\":\"" + id + "\",\"team_id\":\"" + team
                + "\",\"name\":\"Fallback\",\"real_name\":\"Fallback Real\",\"deleted\":" + deleted
                + ",\"is_bot\":" + bot + ",\"profile\":{\"display_name\":\"Display\",\"real_name\":\"Real\","
                + "\"email\":\"private@example.invalid\"}}}";
    }

    public static class ContextModule extends SlackConfigurationTestModule {
        public ContextModule() {
            super(Map.of(
                    "slack.context.enabled",
                    "true",
                    "slack.context.searchEnabled",
                    "true",
                    "slack.context.sourceChannels",
                    "C1"));
        }
    }

    private static final class ContextFixture implements AutoCloseable {
        private final SlackTransportFixture http = new SlackTransportFixture();
        private final JsonMapper json = SlackJsonMapper.create();
        private final MutableClock clock = new MutableClock();
        private final SlackContextService service;
        private final SlackActionRegistry actions;
        private final SlackActionRegistry.Grant grant;
        private final SlackContextConfiguration configuration;
        private final SlackWebApi api;

        private ContextFixture(final SlackContextConfiguration configuration) throws Exception {
            this(configuration, "action-secret");
        }

        private ContextFixture(final SlackContextConfiguration configuration, final String token) throws Exception {
            this.configuration = configuration;
            api = new SlackWebApi(
                    json,
                    http.extended,
                    new SlackWebApi.Settings(http.base(), Duration.ofSeconds(10), 131072, 131072),
                    () -> "xapp-secret",
                    () -> "xoxb-secret");
            final var task = json.fromString(SlackTask.class, """
                    {"id":"task", "workspace":"T1", "channel":"C1", "thread":"1.000001", "requester":"U1",
                     "request":"Ask <@U2>, <@W3|Name>, <@U4>. Not U9 or <@bad>.", "status":"running"}
                    """);
            actions = new SlackActionRegistry(configuration, clock);
            actions.admitted(task, token, NOW + ".000100", clock.millis());
            grant = actions.claim(task, 0, clock.millis()).orElseThrow();
            service = new SlackContextService(configuration, api, grant, clock);
        }

        private void reply(final String body) {
            http.respond(ignored -> new SlackTransportFixture.Response(200, Map.of(), body));
        }

        private Map<String, Object> resolve(final String user) {
            return service.call(Map.of("operation", "resolve_user", "userId", user));
        }

        @Override
        public void close() {
            service.close();
            actions.close();
            json.close();
            http.close();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(NOW));

        private void advance(final long seconds) {
            now.updateAndGet(instant -> instant.plusSeconds(seconds));
        }

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
