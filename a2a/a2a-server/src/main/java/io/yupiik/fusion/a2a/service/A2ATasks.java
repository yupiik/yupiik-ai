/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.service;

import static java.util.Optional.ofNullable;
import static java.util.logging.Level.FINE;

import io.yupiik.fusion.a2a.api.TaskUpdater;
import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.configuration.A2ADurations;
import io.yupiik.fusion.a2a.protocol.A2AErrors;
import io.yupiik.fusion.a2a.protocol.A2AStream;
import io.yupiik.fusion.a2a.protocol.SseBus;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Holds the live {@link A2ATask}s.
 * <p>
 * Inject it to reach a task outside of an {@link io.yupiik.fusion.a2a.api.A2AExecutor} - a scheduler pushing progress,
 * an incoming webhook resuming a task - with {@link #updater(String)}:
 * <pre>{@code
 * tasks.updater(taskId).ifPresent(it -> it.complete(Artifact.text("answer", "42")));
 * }</pre>
 * NOTE: tasks are kept in memory, so a clustered deployment needs a task aware load balancer - or an external store,
 * which this class is the only place to change.
 */
@ApplicationScoped
public class A2ATasks {
    /**
     * What {@code fusion.a2a.taskRetention} means when it is unset.
     */
    private static final Duration DEFAULT_RETENTION = Duration.ofSeconds(1800);

    private final Logger logger = Logger.getLogger(A2ATasks.class.getName());

    private final Map<String, A2ATask> tasks = new ConcurrentHashMap<>();
    private final Clock clock;
    private final PushNotifier push;
    private final Duration retention;

    public A2ATasks(final PushNotifier push, final A2AConfiguration configuration, final Optional<Clock> clock) {
        this.clock = clock == null ? null : clock.orElseGet(Clock::systemUTC);
        this.push = push;
        // not floored to anything: zero - and a negative - means "keep them forever", see evictExpired
        this.retention = configuration == null
                ? DEFAULT_RETENTION
                : A2ADurations.parse(configuration.taskRetention(), DEFAULT_RETENTION);
    }

    /**
     * Creates and registers a task, this is what the first {@code SendMessage} of a conversation does.
     *
     * @param contextId the conversation to attach it to.
     * @param metadata  the request metadata to keep on the task, it can be {@code null}.
     * @return the new task, its identifier is server generated as the specification requires.
     */
    public A2ATask create(final String contextId, final Map<String, Object> metadata) {
        return create(contextId, metadata, null);
    }

    public A2ATask create(final String contextId, final Map<String, Object> metadata, final String owner) {
        evictExpired();
        final var task = new A2ATask(UUID.randomUUID().toString(), contextId, metadata, owner, clock);
        tasks.put(task.id(), task);
        return task;
    }

    /**
     * @param id a task identifier.
     * @return the matching live task if any.
     */
    public Optional<A2ATask> find(final String id) {
        if (id == null) {
            return Optional.empty();
        }
        return ofNullable(tasks.get(id));
    }

    /**
     * @param id a task identifier.
     * @return the matching task.
     * @throws io.yupiik.fusion.jsonrpc.JsonRpcException {@code TaskNotFoundError} when it does not exist - or is not
     *                                                   visible, the specification asks not to distinguish the two.
     */
    public A2ATask require(final String id) {
        return find(id).orElseThrow(() -> A2AErrors.taskNotFound(id));
    }

    /**
     * @return all the live tasks, i.e. what {@code ListTasks} filters.
     */
    public Collection<A2ATask> tasks() {
        return tasks.values();
    }

    /**
     * @param taskId the task to drive.
     * @return an updater on {@code taskId}, empty when there is no such task. Use it to report progress from outside
     * an executor.
     */
    public Optional<TaskUpdater> updater(final String taskId) {
        return find(taskId).map(this::updaterFor);
    }

    /**
     * @return where the task events are forwarded as webhook calls.
     */
    public PushNotifier pushNotifier() {
        return push;
    }

    /**
     * @param task the task to drive.
     * @return an updater bound to the current turn of {@code task}.
     */
    public TaskUpdater updaterFor(final A2ATask task) {
        return new DefaultTaskUpdater(task, this, push, task.turn(), true);
    }

    /**
     * Registers a new subscriber on a task.
     *
     * @param task    the task to watch.
     * @param framing how to frame the events, it depends on the binding - see {@link A2AStream#jsonRpc} and
     *                {@link A2AStream#plain}.
     * @return the stream, its {@link A2AStream#bus()} is the body to answer with.
     */
    public A2AStream newStream(final A2ATask task, final Function<SseBus, A2AStream> framing) {
        final var holder = new A2AStream[1];
        final var bus = new SseBus(() -> task.removeStream(holder[0]));
        holder[0] = framing.apply(bus);
        task.addStream(holder[0]);
        // a comment right away commits the response, so the client - and any proxy in between - knows the stream is
        // live even before the agent has anything to say
        bus.keepAlive();
        return holder[0];
    }

    /**
     * Terminates a task: its streams are closed and any pending {@code SendMessage} fails.
     *
     * @param id the task to drop.
     * @return {@code true} when a task was dropped.
     */
    public boolean drop(final String id) {
        final var task = id == null ? null : tasks.remove(id);
        if (task == null) {
            return false;
        }
        logger.log(FINE, () -> "Dropping task '" + id + "'");
        task.close();
        return true;
    }

    /**
     * Removes a task from the store without touching it, which is what happens when an executor answers a plain
     * message instead of tracking any work: the task must not be observable at all.
     *
     * @param id the task to forget.
     */
    void forget(final String id) {
        if (id != null) {
            tasks.remove(id);
        }
    }

    /**
     * Drops the tasks which were not updated for more than {@code fusion.a2a.taskRetention}.
     * <p>
     * It is called when a task is created so no background thread is needed - which keeps the native image simple.
     */
    public void evictExpired() {
        if (retention.isZero() || retention.isNegative()) {
            return;
        }
        tasks.entrySet().removeIf(it -> {
            if (!it.getValue().isExpired(retention)) {
                return false;
            }
            logger.log(FINE, () -> "Task '" + it.getKey() + "' expired");
            it.getValue().close();
            return true;
        });
    }

    @Destroy
    public void close() {
        tasks.values().forEach(A2ATask::close);
        tasks.clear();
    }
}
