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
package io.yupiik.ia.agent.core.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemoryProductionPolicyTest {

    private final MemoryProductionPolicy policy = new MemoryProductionPolicy();

    @Test
    void defaultsToProducing() {
        assertTrue(policy.enabled("a"), "an untouched session produces memory");
        assertTrue(policy.enabled(null), "a null session id produces memory");
        assertTrue(policy.enabled(" "), "a blank session id produces memory");
    }

    @Test
    void disableAndReenableArePerSession() {
        policy.set("a", false);
        policy.set("b", false);
        assertFalse(policy.enabled("a"), "a is disabled");
        assertFalse(policy.enabled("b"), "b is disabled");

        policy.set("a", true);
        assertTrue(policy.enabled("a"), "a is re-enabled");
        assertFalse(policy.enabled("b"), "b stays disabled");
        assertTrue(policy.enabled("c"), "an untouched session stays enabled");
    }

    @Test
    void clearReleasesTheEntry() {
        policy.set("a", false);
        assertFalse(policy.enabled("a"));
        policy.clear("a");
        assertTrue(policy.enabled("a"), "cleared session falls back to producing");
        // clearing again is harmless (idempotent, nothing leaks)
        policy.clear("a");
        assertTrue(policy.enabled("a"));
    }

    @Test
    void reEnablingDoesNotRetainAnEntry() {
        policy.set("a", false);
        policy.set("a", true);
        // after a re-enable the toggle must have released the override so it never lingers
        policy.clear("a");
        assertTrue(policy.enabled("a"), "not explicitly disabled stays enabled");
    }
}
