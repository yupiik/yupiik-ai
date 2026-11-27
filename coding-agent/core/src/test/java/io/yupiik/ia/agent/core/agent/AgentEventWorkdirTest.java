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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.context.CompactionResult;
import io.yupiik.ia.agent.core.events.CompactionDoneEvent;
import io.yupiik.ia.agent.core.events.EventContext;
import io.yupiik.ia.agent.core.events.HookResponse;
import io.yupiik.ia.agent.core.events.NotificationEvent;
import io.yupiik.ia.agent.core.events.SubagentStartedEvent;
import io.yupiik.ia.agent.core.events.SubagentStoppedEvent;
import io.yupiik.ia.agent.core.events.hooks.AgentEventHookRouter;
import io.yupiik.ia.agent.core.events.hooks.CallbackRunner;
import io.yupiik.ia.agent.core.events.hooks.EventContextBuilder;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.wiring.PrecisionDurationMarkerProvider;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class AgentEventWorkdirTest {
    @Test
    void everyEventRequiresAnExplicitResolvedRoot(@TempDir final Path root) {
        final List<Function<Path, AgentEvent>> events = List.of(
                p -> new AgentEvent.FeedbackStarted("text", 1, "s", null, null, p),
                p -> new AgentEvent.TextDelta("text", "s", p),
                p -> new AgentEvent.TextReset("s", p),
                p -> new AgentEvent.ThinkingDelta("thought", "s", p),
                p -> new AgentEvent.ToolStarted("read", "{}", "s", p),
                p -> new AgentEvent.ToolFinished("read", "output", true, "s", p),
                p -> new AgentEvent.ToolStream("bash", "line", "s", p),
                p -> new AgentEvent.UsageUpdated(Usage.EMPTY, 0, 0, "s", p),
                p -> new AgentEvent.LlmRequestCompleted(Usage.EMPTY, "model", 1, "s", p),
                p -> new AgentEvent.Compacted("prune", "auto", "s", p),
                p -> new AgentEvent.CompactionPass(
                        "pass", AgentEvent.CompactionPass.Status.STARTED, null, null, "s", null, null, p),
                p -> new AgentEvent.Status("running", "s", p),
                p -> AgentEvent.Pause.retry("retry", 1, "s", p),
                p -> AgentEvent.Notice.warn("warning", "s", p),
                p -> AgentEvent.Notice.compactionStart("compacting", "s", p),
                p -> AgentEvent.Notice.compacted("compacted", "s", p),
                p -> AgentEvent.Notice.compactionGateEscalated("blocked", "s", p),
                p -> new AgentEvent.TurnDone("done", "s", p),
                p -> new AgentEvent.TurnCancelled("s", p),
                p -> new AgentEvent.Error("failure", "s", p),
                p -> new AgentEvent.SubagentStarted("task", "s", "child", "worker", p),
                p -> new AgentEvent.SubagentCompleted("done", true, "s", "child", "worker", p),
                p -> new AgentEvent.SubagentDelivered("done", true, false, "s", p),
                p -> new AgentEvent.PromptIntegrated("queued", "s", p),
                p -> new AgentEvent.TurnMetrics(0, 0, 0, 0, 0, 0, "s", p),
                p -> new AgentEvent.ToolTimingEvent("read", 1, 1, false, "s", p),
                p -> new AgentEvent.Phase(AgentEvent.Phase.REQUEST_SENT, "s", p));
        for (final var event : events) {
            assertSame(root, event.apply(root).workdir());
            assertThrows(NullPointerException.class, () -> event.apply(null));
            assertThrows(IllegalArgumentException.class, () -> event.apply(Path.of("relative")));
            assertThrows(IllegalArgumentException.class, () -> event.apply(root.resolve("nested/../other")));
        }
        for (final var type : AgentEvent.class.getPermittedSubclasses()) {
            for (final var constructor : type.getConstructors()) {
                assertSame(Path.class, constructor.getParameterTypes()[constructor.getParameterCount() - 1]);
            }
        }
    }

    @Test
    void interleavedParentAndChildEventsReachFusionAndHooksOnceInOrder(@TempDir final Path root) {
        final var observed = new ArrayList<AgentEvent>();
        final var hooks = new CopyOnWriteArrayList<HookCall>();
        final var router = router(hooks);
        try (final var bus = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(listener(event -> {
                    observed.add(event);
                    router.onEvent(event);
                }))
                .start()) {
            final var a = loop(bus, "A", null, root.resolve("a"));
            final var b = loop(bus, "B", null, root.resolve("b"));
            final var childA = loop(bus, "A", "child-A", root.resolve("child-a"));
            final var childB = loop(bus, "B", "child-B", root.resolve("child-b"));
            final List<AgentEvent> expected = List.of(
                    a.notice(AgentEvent.Notice.Level.WARN, "parent A"),
                    new AgentEvent.SubagentStarted("task B", "B", "child-B", "worker", childB.sessionWorkdir),
                    new AgentEvent.Compacted("prune", "auto", "B", b.sessionWorkdir),
                    new AgentEvent.SubagentCompleted("done A", true, "A", "child-A", "worker", childA.sessionWorkdir));
            a.emit(expected.get(0));
            childB.emit(expected.get(1));
            b.emit(expected.get(2));
            childA.emit(expected.get(3));
            assertEquals(expected, observed);
            assertEquals(
                    List.of("Notification", "SubagentStarted", "CompactionDone", "SubagentStopped"),
                    hooks.stream().map(HookCall::id).toList());
            for (var i = 0; i < expected.size(); i++) {
                final var event = expected.get(i);
                final var hook = hooks.get(i);
                assertEquals(event.sessionId(), hook.context().sessionId());
                assertEquals(event.subagentId(), hook.context().subagentId());
                assertEquals(event.subagentName(), hook.context().subagentName());
                assertEquals(event.workdir().toString(), hook.context().cwd());
                assertSame(event.workdir(), hook.workdir());
            }
            final var text = new AgentEvent.TextDelta("hello", "A", "child-A", "worker", childA.sessionWorkdir);
            childA.emit(text);
            assertEquals(
                    List.of(
                            new AgentEvent.FeedbackStarted(
                                    "text", 1234, "A", "child-A", "worker", childA.sessionWorkdir),
                            text),
                    observed.subList(4, observed.size()));
            assertEquals(4, hooks.size(), "feedback and text must not duplicate observe-only hooks");
        }
    }

    @Test
    void adoptedCompactionUsesTheNormalBusOnceWithoutDuplicatingLegacyHooks(@TempDir final Path root) {
        final var observed = new ArrayList<AgentEvent>();
        final var hooks = new CopyOnWriteArrayList<HookCall>();
        final var router = router(hooks);
        try (final var bus = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(listener(event -> {
                    observed.add(event);
                    router.onEvent(event);
                }))
                .start()) {
            final var parent = loop(bus, "A", null, root.resolve("parent"));
            final var child = loop(bus, "B", "child", root.resolve("child"));
            final var result = new CompactionResult(List.of(), CompactionResult.Mode.SUMMARY, "");
            for (final var owner : List.of(parent, child)) {
                final var before = observed.size();
                owner.emitAdoptedCompaction(result);
                assertEquals(before + 2, observed.size(), "one start and one terminal, without a parallel route");
                final var start = (AgentEvent.CompactionPass) observed.get(before);
                final var terminal = (AgentEvent.CompactionPass) observed.get(before + 1);
                assertEquals(AgentEvent.CompactionPass.Status.STARTED, start.status());
                assertEquals(AgentEvent.CompactionPass.Status.SUCCEEDED, terminal.status());
                assertEquals(start.id(), terminal.id());
                assertEquals(CompactionResult.Mode.SUMMARY, terminal.mode());
                for (final var pass : List.of(start, terminal)) {
                    assertEquals(owner.currentSessionId, pass.sessionId());
                    assertEquals(owner.subagentId, pass.subagentId());
                    assertEquals(owner.subagentName, pass.subagentName());
                    assertSame(owner.sessionWorkdir, pass.workdir());
                }
            }
            assertEquals(0, hooks.size(), "operation events do not duplicate legacy CompactionDone hooks");
            parent.emit(parent.compacted("compact", "SUMMARY"));
            assertEquals(5, observed.size());
            assertEquals(
                    List.of("CompactionDone"), hooks.stream().map(HookCall::id).toList());
        }
    }

    @Test
    void concurrentActorsKeepTheirOwnRootsAndPerActorOrder(@TempDir final Path root) throws Exception {
        final var observed = new CopyOnWriteArrayList<AgentEvent>();
        final var hooks = new CopyOnWriteArrayList<HookCall>();
        final var router = router(hooks);
        try (final var bus = ConfiguringContainer.of()
                        .disableAutoDiscovery(true)
                        .register(listener(event -> {
                            observed.add(event);
                            router.onEvent(event);
                        }))
                        .start();
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var actors = List.of(
                    loop(bus, "A", null, root.resolve("a")),
                    loop(bus, "B", null, root.resolve("b")),
                    loop(bus, "A", "child-A", root.resolve("child-a")),
                    loop(bus, "B", "child-B", root.resolve("child-b")));
            final var barrier = new CyclicBarrier(actors.size());
            final var futures = new ArrayList<Future<?>>();
            for (final var actor : actors) {
                futures.add(executor.submit(() -> {
                    for (var round = 0; round < 8; round++) {
                        barrier.await(5, TimeUnit.SECONDS);
                        actor.emit(actor.notice(AgentEvent.Notice.Level.INFO, "round-" + round));
                    }
                    return null;
                }));
            }
            for (final var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
            assertEquals(32, observed.size());
            assertEquals(32, hooks.size());
            for (final var actor : actors) {
                final var events = observed.stream()
                        .filter(event -> event.workdir().equals(actor.sessionWorkdir))
                        .map(AgentEvent.Notice.class::cast)
                        .toList();
                assertEquals(8, events.size());
                for (var round = 0; round < events.size(); round++) {
                    final var event = events.get(round);
                    assertEquals("round-" + round, event.message());
                    assertEquals(actor.currentSessionId, event.sessionId());
                    assertEquals(actor.subagentId, event.subagentId());
                }
                final var calls = hooks.stream()
                        .filter(hook -> hook.workdir().equals(actor.sessionWorkdir))
                        .toList();
                assertEquals(8, calls.size());
                assertTrue(calls.stream()
                        .allMatch(hook -> hook.context().cwd().equals(actor.sessionWorkdir.toString())
                                && hook.context().sessionId().equals(actor.currentSessionId)));
            }
        }
    }

    private AgentLoop loop(final Emitter emitter, final String session, final String child, final Path root) {
        final var loop = new AgentLoop(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                emitter,
                null,
                null,
                null,
                null,
                Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC),
                null,
                null,
                null,
                session,
                child,
                child,
                null,
                new PrecisionDurationMarkerProvider(),
                null,
                null,
                null,
                null,
                null,
                root,
                new DisabledSandbox(root));
        loop.currentSessionId = session;
        return loop;
    }

    private FusionListener<AgentEvent> listener(final Consumer<AgentEvent> observer) {
        return new FusionListener<>() {
            @Override
            public Type eventType() {
                return AgentEvent.class;
            }

            @Override
            public void onEvent(final RuntimeContainer container, final AgentEvent event) {
                observer.accept(event);
            }
        };
    }

    private AgentEventHookRouter router(final List<HookCall> calls) {
        return new AgentEventHookRouter(
                new CallbackRunner() {
                    @Override
                    public List<HookResponse> run(
                            final String id, final Object input, final int timeout, final Path workdir) {
                        final var context =
                                switch (input) {
                                    case NotificationEvent.Payload payload -> payload.eventContext();
                                    case SubagentStartedEvent.Payload payload -> payload.eventContext();
                                    case SubagentStoppedEvent.Payload payload -> payload.eventContext();
                                    case CompactionDoneEvent.Payload payload -> payload.eventContext();
                                    default -> throw new AssertionError(input);
                                };
                        calls.add(new HookCall(id, context, workdir));
                        return List.of();
                    }
                },
                new EventContextBuilder());
    }

    private record HookCall(String id, EventContext context, Path workdir) {}
}
