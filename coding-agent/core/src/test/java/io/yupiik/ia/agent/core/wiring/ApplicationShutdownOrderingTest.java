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
package io.yupiik.ia.agent.core.wiring;

import static io.yupiik.fusion.framework.api.ConfiguringContainer.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.mcp.WebSearchManager;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.maven.knowledge.process.ProcessService;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Locks in the application shutdown ordering contract.
 *
 * <p>Fusion destroys application-scoped beans in <em>ascending</em> {@code destroyPriority} order
 * ({@code ApplicationFusionContext.close()} sorts with {@code Comparator.comparingInt(destroyPriority)}).
 * That runtime value is baked by the fusion annotation processor into each generated {@code $FusionBean}
 * from the {@code @Destroy(order = ...)} qualifier (default {@code 0}), so it is <em>not</em> readable from
 * the source annotation at runtime (the annotation is {@code @Retention(SOURCE)}). This test therefore boots a
 * real container and reads {@code FusionBean#destroyPriority()} from the registered beans.</p>
 *
 * <p>The shared {@link ExecutorServiceProducer} (virtual-thread executor) and {@link SchedulerProducer}
 * (scheduled executor) are destroyed with {@code @Destroy(order = Integer.MAX_VALUE)}, i.e. <em>last</em>.
 * Their closes are <em>graceful and unbounded</em> (e.g. {@code Executors.newVirtualThreadPerTaskExecutor().close()}
 * waits for every in-flight thread). They are a correct backstop only under the assumption that every bean
 * releasing a blocking resource (subprocesses, MCP stdio pipes, HTTP clients, SSE streams) was already torn
 * down - otherwise the executor close waits forever on a thread parked on a resource no one freed yet.</p>
 *
 * <p>This test guards that assumption: every application-scoped bean that releases a blocking resource at
 * {@code @Destroy} must run <em>before</em> the {@code Integer.MAX_VALUE} executors, i.e. declare
 * {@code order} strictly below {@link Integer#MAX_VALUE}. Today all of them use the default {@code 0}; a
 * future edit adding an aggressive {@code @Destroy(order = Integer.MAX_VALUE)} to one of these beans would
 * silently reintroduce a shutdown hang, and this test would fail it.</p>
 */
class ApplicationShutdownOrderingTest {

    /** The bucket the shared executors are destroyed in (last). */
    private static final int EXECUTOR_DESTROY_ORDER = Integer.MAX_VALUE;

    /**
     * Application-scoped beans that release blocking resources on teardown. Any of these running at (or after)
     * the shared executors' {@code Integer.MAX_VALUE} bucket would deadlock the graceful executor close.
     * Keyed by the bean's {@code type()}: the resource releasers are concrete classes, the produced executors
     * are their interface types.
     */
    private static final Map<Type, String> EXPECTED_DESTROY_ORDER = Map.ofEntries(
            Map.entry(ProcessService.class, "ProcessService (kills subprocess trees, unblocking pipe drains)"),
            Map.entry(McpManager.class, "McpManager (closes MCP clients, unblocking stdio readLine())"),
            Map.entry(ChatClient.class, "ChatClient (closes the HTTP client(s))"),
            Map.entry(BtwAnswerer.class, "BtwAnswerer (closes its dedicated /btw LLM client)"),
            Map.entry(
                    StructuralCodeGraph.class,
                    "StructuralCodeGraph (flushes persists + shuts down scheduler/watch service)"),
            Map.entry(WebSearchManager.class, "WebSearchManager (closes the web-search MCP client)"));

    /** The produced executors that legitimately live in the {@link #EXECUTOR_DESTROY_ORDER} (last) bucket. */
    private static final Map<Type, String> EXPECTED_EXECUTOR_ORDER = Map.ofEntries(
            Map.entry(ExecutorService.class, "shared virtual-thread executor"),
            Map.entry(ScheduledExecutorService.class, "scheduler"));

    @Test
    void resourceReleasersAreDestroyedBeforeTheSharedExecutors() {
        // Boot the real container with only the beans under test (plus fusion infra) so this is fast and
        // does not wire the whole application; the bean filter is mirroring TestJsonMapper's pattern.
        try (final var container = of().register(beanFilter()).start()) {
            final var registered = container.getBeans().getBeans();

            final var byType = registered.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey, e -> e.getValue().get(0).destroyPriority(), (a, b) -> Math.max(a, b)));

            // every resource releaser must be destroyed strictly before the executors
            final var offenders = EXPECTED_DESTROY_ORDER.entrySet().stream()
                    .filter(e -> {
                        final var order = byType.get(e.getKey());
                        return order != null && order >= EXECUTOR_DESTROY_ORDER;
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> byType.get(e.getKey())));
            if (!offenders.isEmpty()) {
                final var detail = offenders.entrySet().stream()
                        .map(e -> EXPECTED_DESTROY_ORDER.get(e.getKey()) + " (order=" + e.getValue() + ")")
                        .collect(Collectors.joining(", "));
                fail("shutdown-deadlock risk - these beans are not destroyed before the shared executors ("
                        + EXECUTOR_DESTROY_ORDER + "): " + detail);
            }

            // and each expected bean must actually be registered, with the exact expected bucket
            for (final var entry : EXPECTED_DESTROY_ORDER.entrySet()) {
                assertTrue(
                        byType.containsKey(entry.getKey()),
                        () -> entry.getValue() + " was not registered as a bean (filter too strict?)");
            }
            for (final var entry : EXPECTED_EXECUTOR_ORDER.entrySet()) {
                assertEquals(
                        EXECUTOR_DESTROY_ORDER,
                        byType.get(entry.getKey()),
                        () -> entry.getValue() + " must sit in the last (" + EXECUTOR_DESTROY_ORDER + ") bucket");
            }
        }
    }

    private static FusionModule beanFilter() {
        return new FusionModule() {
            @Override
            public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                // keep only the beans under test plus the fusion infra beans every wiring needs
                final var targets = new ArrayList<Type>();
                targets.addAll(List.of(
                        Configuration.class,
                        Emitter.class,
                        RuntimeContainer.class,
                        ExecutorService.class,
                        ScheduledExecutorService.class));
                targets.addAll(EXPECTED_DESTROY_ORDER.keySet());
                return (runtimeContainer, bean) -> isType(bean.type(), targets);
            }

            @Override
            public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                // no listeners: nothing initializes, so no heavy wiring is triggered
                return (runtimeContainer, listener) -> false;
            }
        };
    }

    private static boolean isType(final Type type, final List<Type> expected) {
        final var clazz = type instanceof ParameterizedType parameterized
                ? (Class<?>) parameterized.getRawType()
                : (Class<?>) type;
        for (final var e : expected) {
            final var expectedClass =
                    e instanceof ParameterizedType parameterized ? (Class<?>) parameterized.getRawType() : (Class<?>) e;
            if (expectedClass.isAssignableFrom(clazz)) {
                return true;
            }
        }
        return false;
    }
}
