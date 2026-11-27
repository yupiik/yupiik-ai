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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ReasoningFormatModeTest {

    @Test
    void parsingIsTolerant() {
        assertEquals(ReasoningFormatMode.THINK_TAGS, ReasoningFormatMode.fromConfigurationString("think_tags"));
        assertEquals(ReasoningFormatMode.THINK_TAGS, ReasoningFormatMode.fromConfigurationString("think"));
        assertEquals(ReasoningFormatMode.DEEPSEEK_R1, ReasoningFormatMode.fromConfigurationString("deepseek"));
        assertEquals(ReasoningFormatMode.DEEPSEEK_R1, ReasoningFormatMode.fromConfigurationString("deepseek_r1"));
        assertEquals(ReasoningFormatMode.QWEN, ReasoningFormatMode.fromConfigurationString("qwen"));
        assertEquals(ReasoningFormatMode.AUTO, ReasoningFormatMode.fromConfigurationString(""));
        assertEquals(ReasoningFormatMode.AUTO, ReasoningFormatMode.fromConfigurationString(null));
        assertEquals(ReasoningFormatMode.AUTO, ReasoningFormatMode.fromConfigurationString("weird"));
    }

    @Test
    void resolveSelectsTheMatchingPreset() {
        assertEquals(
                new ReasoningFormat("think", " response", false, "<tool_call>"), ReasoningFormatMode.QWEN.resolve());
        assertEquals(" response", ReasoningFormatMode.DEEPSEEK_R1.resolve().close());
        assertNull(
                ReasoningFormatMode.DEEPSEEK_R1.resolve().open(),
                "deepseek-r1 reasons from the start with no open tag");
        assertNull(ReasoningFormatMode.AUTO.resolve().close(), "auto keeps the default whole-line think handling");
    }
}
