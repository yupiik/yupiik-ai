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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Append-only local audit trail. Only identifiers and fixed event codes are accepted, never task text or tokens. */
@ApplicationScoped
public class BotAudit {
    private static final Pattern EVENT_NAME = Pattern.compile("[a-z-]+");

    private static final Logger LOGGER = Logger.getLogger(BotAudit.class.getName());
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final GithubBotConfiguration configuration;
    private final JsonMapper json;
    private final Clock clock;

    public BotAudit(final GithubBotConfiguration configuration, final JsonMapper json, final Clock clock) {
        this.configuration = configuration;
        this.json = json;
        this.clock = clock;
    }

    public void record(final String event, final TaskDocument task) {
        if (!EVENT_NAME.matcher(event).matches()) {
            throw new IllegalArgumentException("Invalid audit event");
        }
        final var home = Path.of(configuration.home()).toAbsolutePath().normalize();
        final var local = LOCKS.computeIfAbsent(home, key -> new ReentrantLock());
        local.lock();
        try (final var lockChannel = FileChannel.open(
                        home.resolve("audit.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                final var lock = lockChannel.lock();
                final var output = FileChannel.open(
                        home.resolve("audit.jsonl"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND,
                        LinkOption.NOFOLLOW_LINKS)) {
            final var audit = new AuditEntry(
                    clock.instant().toString(),
                    event,
                    task.id(),
                    task.actorId(),
                    task.repository(),
                    task.number(),
                    task.state(),
                    task.attempts());
            final var bytes = ByteBuffer.wrap((json.toString(audit) + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                output.write(bytes);
            }
            output.force(true);
            LOGGER.fine(() -> "GitHub bot event=" + event + " task=" + task.id());
        } catch (final IOException e) {
            throw new UncheckedIOException("Audit append failed", e);
        } finally {
            local.unlock();
        }
    }

    @JsonModel
    public record AuditEntry(
            @Property(documentation = "UTC event timestamp.")
            String at,

            @Property(documentation = "Fixed audit event code.")
            String event,

            @Property(documentation = "Task identifier when known.")
            String id,

            @Property(documentation = "Immutable actor identifier when known.")
            String actorId,

            @Property(documentation = "Repository full name when known.")
            String repository,

            @Property(documentation = "Issue or PR number when known.")
            Long number,

            @Property(documentation = "Durable task state when known.")
            TaskState state,

            @Property(documentation = "Consumed execution attempts when known.")
            Integer attempts) {}
}
