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
package io.yupiik.ia.agent.slack.context.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.slack.configuration.AgentSlackContextConfiguration;
import io.yupiik.ia.agent.slack.context.SlackContextMailbox;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class SlackContextToolTest {
    @TempDir
    private static Path testWorkdir;

    @TempDir
    Path home;

    @Test
    void disabledAndInvalidCapabilitiesFailClosedWithoutCreatingDirectories() throws Exception {
        try (final var json = json()) {
            final var missing = home.resolve("missing").toString();
            for (final var configuration : List.of(
                    new AgentSlackContextConfiguration(false, missing, "C1", 30),
                    new AgentSlackContextConfiguration(true, missing, "C1", 30),
                    new AgentSlackContextConfiguration(true, home.toString(), "", 30),
                    new AgentSlackContextConfiguration(true, "relative", "C1", 30),
                    new AgentSlackContextConfiguration(true, home.toString(), "C1", 0),
                    new AgentSlackContextConfiguration(true, home.toString(), "C1", 301))) {
                final var tool = new SlackContextTool(new SlackContextClient(configuration, json));
                assertFalse(tool.usable(home));
                assertTrue(
                        tool.execute(Map.of("operation", "summary"), ToolContext.empty(home, new DisabledSandbox(home)))
                                .error());
            }
            assertFalse(Files.exists(Path.of(missing)));
        }
    }

    @Test
    void toolMetadataAndInvocationPolicy() throws Exception {
        final var root = privateDirectory();
        try (final var json = json()) {
            final var tool = tool(root, json);
            assertEquals("slack_context", tool.name());
            assertEquals(ToolCategory.READ, tool.category());
            assertTrue(tool.readonly());
            assertTrue(tool.usable(home));
            assertEquals(false, tool.schema().get("additionalProperties"));
            assertEquals(List.of("operation"), tool.schema().get("required"));
            final var properties = (Map<?, ?>) tool.schema().get("properties");
            for (final var field : List.of("userId", "authorUserId", "mentionedUserId")) {
                final var schema = (Map<?, ?>) properties.get(field);
                assertEquals(64, schema.get("maxLength"));
                assertEquals("[UW][A-Z0-9]{1,63}", schema.get("pattern"));
                assertTrue(schema.get("description").toString().contains("<@USER_ID>"));
            }
            assertEquals(8, ((Map<?, ?>) properties.get("terms")).get("maxItems"));
            assertEquals(20, ((Map<?, ?>) properties.get("limit")).get("maximum"));
            assertEquals(128, ((Map<?, ?>) properties.get("pageToken")).get("maxLength"));
            assertTrue(((List<?>) ((Map<?, ?>) properties.get("operation")).get("enum")).contains("thread"));
            assertTrue(properties.containsKey("threadTs"));
            for (final var arguments : List.<Map<String, Object>>of(
                    Map.of("operation", "thread", "channel", "C2"),
                    Map.of("operation", "thread", "cursor", "raw-slack-cursor"),
                    Map.of("operation", "thread", "threadTs", "https://slack.com/"),
                    Map.of("operation", "thread", "limit", 21),
                    Map.of("operation", "thread", "limit", 1.5),
                    Map.of("operation", "thread", "pageToken", ""))) {
                assertEquals(
                        "Invalid Slack context arguments.",
                        tool.execute(arguments, ToolContext.empty(home, new DisabledSandbox(home)))
                                .output());
            }
            final var denied = context(() -> false, () -> false, Set.of("other_tool"));
            assertEquals(
                    "Slack context tool is not allowed.",
                    tool.execute(Map.of("operation", "summary"), denied).output());
            assertEquals(
                    "Slack context tool is not allowed.",
                    tool.execute(Map.of("operation", "thread", "limit", 20, "pageToken", "opaque"), denied)
                            .output());
            assertEquals(
                    "Slack context request cancelled.",
                    tool.execute(Map.of("operation", "summary"), context(() -> true, () -> false, null))
                            .output());
            assertEquals(
                    "Slack context request cancelled.",
                    tool.execute(Map.of("operation", "summary"), context(() -> false, () -> true, null))
                            .output());
            assertFalse(Files.exists(root.resolve("request.json")));
        }
    }

    @Test
    void clientChecksPolicyAndDisabledConfigurationBeforeMailboxIo() throws Exception {
        // A null mapper makes an attempted mailbox open fail, so the distinct errors verify check ordering.
        final var root = privateDirectory();
        final var client =
                new SlackContextClient(new AgentSlackContextConfiguration(true, root.toString(), "C1", 3), null);
        final var args = Map.<String, Object>of("operation", "summary");
        assertEquals(
                "Slack context tool is not allowed.",
                client.call(args, context(() -> false, () -> false, Set.of("other_tool")))
                        .output());
        assertEquals(
                "Slack context request cancelled.",
                client.call(args, context(() -> true, () -> false, null)).output());
        assertEquals(
                "Slack context request cancelled.",
                client.call(args, context(() -> false, () -> true, null)).output());
        try {
            Thread.currentThread().interrupt();
            assertEquals(
                    "Slack context request cancelled.",
                    client.call(args, ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                            .output());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        final var disabled =
                new SlackContextClient(new AgentSlackContextConfiguration(false, root.toString(), "C1", 3), null);
        assertFalse(disabled.usable());
        assertEquals(
                "Slack context capability unavailable.",
                disabled.call(args, ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                        .output());
        final var unconfigured = new SlackContextClient(null, null);
        assertFalse(unconfigured.usable());
        assertEquals(
                "Slack context capability unavailable.",
                unconfigured
                        .call(args, ToolContext.empty(testWorkdir, new DisabledSandbox(testWorkdir)))
                        .output());
        try (final var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void clientCancelsInFlightAndCleansUpMailbox() throws Exception {
        final var root = privateDirectory();
        final var cancelled = new AtomicBoolean();
        try (final var json = json();
                final var controller = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var client =
                    new SlackContextClient(new AgentSlackContextConfiguration(true, root.toString(), "C1", 3), json);
            final var future = executor.submit(
                    () -> client.call(Map.of("operation", "summary"), context(() -> false, cancelled::get, null)));
            await(controller, null);
            cancelled.set(true);
            assertEquals(
                    "Slack context request unavailable.",
                    future.get(2, TimeUnit.SECONDS).output());
            assertNull(controller.pending());
            assertFalse(Files.exists(root.resolve("response.json")));
        }
    }

    @Test
    void cancellationWhileWaitingForCollidingMailboxDoesNotPublishOrClearStripeOwnersRequest() throws Exception {
        final var root = privateDirectory();
        // The mailbox deliberately shares 64 lock stripes, even between unrelated directories.
        final var stripe = Math.floorMod(root.toAbsolutePath().hashCode(), 64);
        final var blockerRoot = IntStream.range(0, 4096)
                .mapToObj(i -> home.resolve("blocker-" + i))
                .filter(path -> Math.floorMod(path.toAbsolutePath().hashCode(), 64) == stripe)
                .findFirst()
                .orElseThrow();
        Files.createDirectory(
                blockerRoot, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var cancelled = new AtomicBoolean();
        final var checks = new AtomicInteger();
        final var lockWaitEntered = new CountDownLatch(1);
        try (final var json = json();
                final var blocker = new SlackContextMailbox(blockerRoot, json, "C1");
                final var controller = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var blocked = executor.submit(() ->
                    blocker.request(Map.of("operation", "summary"), System.currentTimeMillis() + 4000, () -> false));
            try {
                final var original = await(blocker, null);
                final var client = new SlackContextClient(
                        new AgentSlackContextConfiguration(true, root.toString(), "C1", 3), json);
                final var waiting = executor.submit(() -> client.call(
                        Map.of("operation", "summary"),
                        context(
                                () -> false,
                                () -> {
                                    // One client policy check, then two mailbox checks around a failed lock attempt.
                                    if (checks.incrementAndGet() == 3) {
                                        lockWaitEntered.countDown();
                                    }
                                    return cancelled.get();
                                },
                                null)));
                assertTrue(lockWaitEntered.await(2, TimeUnit.SECONDS), "Client must reach the contended lock wait");
                assertFalse(waiting.isDone());
                assertNull(controller.pending(), "Waiting for a stripe must not publish a request");
                cancelled.set(true);
                assertEquals(
                        "Slack context request unavailable.",
                        waiting.get(2, TimeUnit.SECONDS).output());
                assertNull(controller.pending());
                assertFalse(Files.exists(root.resolve("response.json")));
                assertEquals(original, blocker.pending(), "Cancellation must not clear the stripe owner's request");
                blocker.respond(original, Map.of("status", "ok"));
                assertEquals(Map.of("status", "ok"), blocked.get(2, TimeUnit.SECONDS));
                assertNull(blocker.pending());
            } finally {
                cancelled.set(true);
                blocked.cancel(true);
            }
        }
    }

    @Test
    void validatesStrictPerOperationArgumentsWithoutTouchingMailbox() throws Exception {
        final var root = privateDirectory();
        try (final var json = json()) {
            final var tool = tool(root, json);
            assertTrue(tool.execute(null, ToolContext.empty(home, new DisabledSandbox(home)))
                    .error());
            for (final var args : List.<Map<String, Object>>of(
                    Map.of(),
                    Map.of("operation", "unknown"),
                    Map.of("operation", "history"),
                    Map.of("operation", "summary", "channel", "C2"),
                    Map.of("operation", "summary", "terms", List.of("x")),
                    Map.of("operation", "resolve_user"),
                    Map.of("operation", "resolve_user", "userId", "<@U1>"),
                    Map.of("operation", "resolve_user", "userId", "U1", "limit", 1),
                    Map.of("operation", "search", "query", "in:other"),
                    Map.of("operation", "search", "userId", "U1"),
                    Map.of("operation", "search", "authorUserId", "U.*"),
                    Map.of("operation", "search", "authorUserId", "U" + "A".repeat(64)),
                    Map.of("operation", "search", "mentionedUserId", "W" + "A".repeat(64)),
                    Map.of("operation", "search", "mentionedUserId", "B1"),
                    Map.of("operation", "search", "terms", "text"),
                    Map.of("operation", "search", "terms", List.of()),
                    Map.of("operation", "search", "terms", List.of(3)),
                    Map.of("operation", "search", "terms", List.of(" ")),
                    Map.of("operation", "search", "terms", List.of("x\n")),
                    Map.of("operation", "search", "terms", List.of("x".repeat(129))),
                    Map.of("operation", "search", "terms", Collections.nCopies(9, "x")),
                    Map.of("operation", "search", "limit", 0),
                    Map.of("operation", "search", "limit", 21),
                    Map.of("operation", "search", "limit", 100),
                    Map.of("operation", "search", "limit", 1.5),
                    Map.of("operation", "search", "limit", "1"),
                    Map.of("operation", "search", "after", -1),
                    Map.of("operation", "search", "before", Double.NaN),
                    Map.of("operation", "search", "after", new BigDecimal("9223372036854775808")),
                    Map.of("operation", "search", "after", 10, "before", 10),
                    Map.of("operation", "search", "after", 11, "before", 10),
                    Map.of("operation", "search", "pageToken", ""),
                    Map.of("operation", "search", "pageToken", "x".repeat(129)))) {
                assertEquals(
                        "Invalid Slack context arguments.",
                        tool.execute(args, ToolContext.empty(home, new DisabledSandbox(home)))
                                .output(),
                        args.toString());
            }
            assertFalse(Files.exists(root.resolve("request.json")));
        }
    }

    @Test
    void boundedIdentifierFastPaths() throws Exception {
        final var root = privateDirectory();
        try (final var json = json()) {
            final var tool = tool(root, json);
            final var longId = "U" + "A".repeat(100_000);
            assertTimeout(Duration.ofSeconds(1), () -> {
                for (var i = 0; i < 100; i++) {
                    assertEquals(
                            "Invalid Slack context arguments.",
                            tool.execute(
                                            Map.of("operation", "resolve_user", "userId", longId),
                                            ToolContext.empty(home, new DisabledSandbox(home)))
                                    .output());
                }
            });
            for (final var id : List.of("", "U", "u1", "U1\n", "U1 ", "U.*", "U" + "A".repeat(64))) {
                assertEquals(
                        "Invalid Slack context arguments.",
                        tool.execute(
                                        Map.of("operation", "resolve_user", "userId", id),
                                        ToolContext.empty(home, new DisabledSandbox(home)))
                                .output());
            }
        }
    }

    @Test
    void forwardsValidOperationsWithTrustedChannelAndMapsStatuses() throws Exception {
        final var root = privateDirectory();
        try (final var json = json();
                final var controller = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tool = tool(root, json);
            final Map<String, Object> nullableSearch = new HashMap<>(Map.of("operation", "search"));
            nullableSearch.put("terms", null);
            String previousId = null;
            for (final var args : List.<Map<String, Object>>of(
                    Map.of("operation", "summary"),
                    Map.of("operation", "resolve_user", "userId", "W123"),
                    Map.of("operation", "resolve_user", "userId", "U" + "A".repeat(63)),
                    Map.of(
                            "operation",
                            "search",
                            "authorUserId",
                            "U" + "A".repeat(63),
                            "mentionedUserId",
                            "W" + "A".repeat(63),
                            "terms",
                            Collections.nCopies(8, "x".repeat(128)),
                            "pageToken",
                            "x".repeat(128),
                            "limit",
                            1),
                    Map.of(
                            "operation",
                            "search",
                            "terms",
                            List.of("release", "notes"),
                            "authorUserId",
                            "U1",
                            "mentionedUserId",
                            "W2",
                            "after",
                            1,
                            "before",
                            2,
                            "limit",
                            20,
                            "pageToken",
                            "opaque-token"),
                    nullableSearch)) {
                final var future =
                        executor.submit(() -> tool.execute(args, ToolContext.empty(home, new DisabledSandbox(home))));
                final var request = await(controller, previousId);
                previousId = request.id();
                assertEquals("C1", request.channel());
                assertEquals(args.get("operation"), request.arguments().get("operation"));
                if (args == nullableSearch) {
                    assertEquals(Map.of("operation", "search"), request.arguments());
                }
                controller.respond(request, Map.of("status", "ok", "data", List.of()));
                final var result = future.get(2, TimeUnit.SECONDS);
                assertTrue(result.success());
                assertTrue(result.output().contains("\"status\":\"ok\""));
            }
            for (final var status : List.of("no_results", "denied", "cancelled", "rate_limited", "failed", "unknown")) {
                final var future = executor.submit(() -> tool.execute(
                        Map.of("operation", "summary"), ToolContext.empty(home, new DisabledSandbox(home))));
                final var request = await(controller, previousId);
                previousId = request.id();
                controller.respond(request, Map.of("status", status));
                assertEquals(
                        status.equals("no_results") ? ToolResult.Status.OK : ToolResult.Status.ERROR,
                        future.get(2, TimeUnit.SECONDS).status());
            }
            assertNull(controller.pending());
        }
    }

    @Test
    void remoteFilesystemDiagnosticsAreNotExposed() throws Exception {
        final var root = privateDirectory();
        try (final var json = json();
                final var controller = new SlackContextMailbox(root, json, "C1");
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var future = executor.submit(() -> tool(root, json)
                    .execute(Map.of("operation", "summary"), ToolContext.empty(home, new DisabledSandbox(home))));
            await(controller, null);
            Files.writeString(root.resolve("response.json"), "private-diagnostic-payload");
            Files.setPosixFilePermissions(root.resolve("response.json"), PosixFilePermissions.fromString("rw-------"));
            assertEquals(
                    "Slack context request unavailable.",
                    future.get(2, TimeUnit.SECONDS).output());
        }
    }

    private static ToolContext context(
            final BooleanSupplier cancelled, final BooleanSupplier callCancelled, final Set<String> allowedTools) {
        return new ToolContext(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                cancelled,
                null,
                callCancelled,
                testWorkdir,
                new DisabledSandbox(testWorkdir),
                -1,
                allowedTools,
                null,
                null);
    }

    private static SlackContextTool tool(final Path root, final JsonMapper json) {
        return new SlackContextTool(
                new SlackContextClient(new AgentSlackContextConfiguration(true, root.toString(), "C1", 3), json));
    }

    private Path privateDirectory() throws Exception {
        return Files.createDirectory(
                home.resolve("box"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static SlackContextMailbox.Request await(final SlackContextMailbox mailbox, final String previousId)
            throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            final var request = mailbox.pending();
            // A completed response can still be visible until the client finishes mailbox cleanup.
            if (request != null && !request.id().equals(previousId)) {
                return request;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Tool did not publish request");
    }

    private static JsonMapper json() {
        return JsonMappers.create(ignored -> Optional.empty());
    }
}
