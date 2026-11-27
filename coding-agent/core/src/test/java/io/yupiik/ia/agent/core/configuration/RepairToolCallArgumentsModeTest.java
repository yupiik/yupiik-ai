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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RepairToolCallArgumentsModeTest {

    @Test
    void parsingIsTolerant() {
        assertEquals(
                RepairToolCallArgumentsMode.ENABLED, RepairToolCallArgumentsMode.fromConfigurationString("enabled"));
        assertEquals(
                RepairToolCallArgumentsMode.DISABLED, RepairToolCallArgumentsMode.fromConfigurationString("disabled"));
        assertEquals(RepairToolCallArgumentsMode.AUTO, RepairToolCallArgumentsMode.fromConfigurationString("auto"));
        assertEquals(RepairToolCallArgumentsMode.AUTO, RepairToolCallArgumentsMode.fromConfigurationString(""));
        assertEquals(RepairToolCallArgumentsMode.AUTO, RepairToolCallArgumentsMode.fromConfigurationString(null));
        assertEquals(
                RepairToolCallArgumentsMode.ENABLED, RepairToolCallArgumentsMode.fromConfigurationString(" ENABLED "));
    }

    @Test
    void autoCandidateMatchesQwen27BOnly() {
        assertTrue(RepairToolCallArgumentsMode.AUTO.isAutoCandidate("qwen3.8-27b"));
        assertTrue(RepairToolCallArgumentsMode.AUTO.isAutoCandidate("Qwen3-27B-it"));
        assertFalse(RepairToolCallArgumentsMode.AUTO.isAutoCandidate("qwq-27b"));
        assertTrue(RepairToolCallArgumentsMode.AUTO.isAutoCandidate("Qwen2.5-27B-Instruct"));
        assertFalse(RepairToolCallArgumentsMode.AUTO.isAutoCandidate("qwen3-14b"));
        assertFalse(RepairToolCallArgumentsMode.AUTO.isAutoCandidate("qwen3-72b"));
        assertFalse(RepairToolCallArgumentsMode.AUTO.isAutoCandidate("gpt-5-mini"));
        assertFalse(RepairToolCallArgumentsMode.AUTO.isAutoCandidate(""));
        assertFalse(RepairToolCallArgumentsMode.AUTO.isAutoCandidate(null));
    }
}
