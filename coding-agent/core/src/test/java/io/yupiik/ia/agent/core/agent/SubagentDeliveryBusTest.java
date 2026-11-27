/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubagentDeliveryBusTest {
    @Test
    void nestedDeliveriesGoOnlyToTheirLiveImmediateParent() {
        final var bus = SubagentDeliveryBus.get();
        final var root = UUID.randomUUID().toString();
        final var parent = root + "-child";
        final var delivery = new SubagentDeliveryBus.Delivery("grandchild", "worker", "done", true, false);
        bus.open(parent);
        try {
            bus.deliver(parent, delivery, true);
            bus.deliver(parent, delivery, true);
            assertTrue(bus.drain(root).isEmpty());
            assertEquals(List.of(delivery), bus.drain(parent));
            assertTrue(bus.drain(parent).isEmpty());
            bus.deliver(parent, delivery, true);
            assertEquals(List.of(delivery), bus.drain(parent), "draining must not close a live parent's channel");
        } finally {
            bus.close(parent);
        }
        bus.deliver(parent, delivery, true);
        assertTrue(bus.drain(parent).isEmpty(), "late completion must not recreate a dead parent's queue");
        bus.deliver(root, delivery);
        assertEquals(List.of(delivery), bus.drain(root), "root deliveries still survive between turns");
    }
}
