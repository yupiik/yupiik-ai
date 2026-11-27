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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class InterruptCoordinatorTest {
    private static Clock at(final long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneId.of("UTC"));
    }

    @Test
    void busyInterruptsAndStaysAlive() {
        assertEquals(InterruptCoordinator.Decision.INTERRUPT, new InterruptCoordinator(at(1000)).interrupt(true));
    }

    @Test
    void firstIdlePressArmsAndSecondWithinWindowQuits() {
        final var clock = new MutableClock(0);
        final var coordinator = new InterruptCoordinator(clock);
        assertEquals(InterruptCoordinator.Decision.ARM_QUIT, coordinator.interrupt(false));
        clock.now = 1500;
        assertEquals(InterruptCoordinator.Decision.QUIT, coordinator.interrupt(false));
    }

    @Test
    void pressAfterWindowArmsAgain() {
        final var clock = new MutableClock(0);
        final var coordinator = new InterruptCoordinator(clock);
        assertEquals(InterruptCoordinator.Decision.ARM_QUIT, coordinator.interrupt(false));
        clock.now = 5000;
        assertEquals(InterruptCoordinator.Decision.ARM_QUIT, coordinator.interrupt(false));
    }

    @Test
    void quitOnFirstPressPolicySkipsTheWindow() {
        assertEquals(InterruptCoordinator.Decision.QUIT, new InterruptCoordinator(at(1000), true).interrupt(false));
    }

    private static final class MutableClock extends Clock {
        private long now;

        private MutableClock(final long now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }
}
