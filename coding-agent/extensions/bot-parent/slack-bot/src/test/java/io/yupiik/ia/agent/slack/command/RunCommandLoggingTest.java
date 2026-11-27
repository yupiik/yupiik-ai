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
package io.yupiik.ia.agent.slack.command;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.SlotLease;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCommandLoggingTest {
    @TempDir
    Path root;

    @Test
    void startupReportsOnlyApprovedChannelCountAndParallelism() throws Exception {
        final var logger = Logger.getLogger(RunCommand.class.getName());
        final var previous = logger.getLevel();
        final var records = new ArrayList<LogRecord>();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        logger.setLevel(Level.INFO);
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            fixture.store.list();
            // Existing ownership prevents any network, credential access or worker startup.
            try (final var _ = SlotLease.tryAcquire(
                            fixture.configuration.stateRoot().resolve("runner.lock"))
                    .orElseThrow()) {
                final var command = new RunCommand(
                        new RunCommand.Options(3, "https://slack.com/api/", 20, 1048576, 1, 30),
                        fixture.configuration,
                        null,
                        null,
                        null,
                        null,
                        fixture.json,
                        fixture.store,
                        null,
                        null,
                        null,
                        null,
                        fixture.contextConfiguration,
                        fixture.actions);
                final var failure = assertThrows(IllegalStateException.class, command::run);
                assertEquals("Another Slack controller owns this state root", failure.getMessage());
            }
            assertEquals(1, records.size());
            final var record = records.getFirst();
            assertEquals(Level.INFO, record.getLevel());
            assertEquals("Slack controller starting: approvedChannels=2, parallelism=3", record.getMessage());
            assertNull(record.getThrown());
            assertNull(record.getParameters());
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }
}
