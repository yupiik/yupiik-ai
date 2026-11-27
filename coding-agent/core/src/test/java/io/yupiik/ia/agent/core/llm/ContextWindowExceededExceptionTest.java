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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.llm.client.ContextWindowExceededException;
import org.junit.jupiter.api.Test;

class ContextWindowExceededExceptionTest {

    @Test
    void detectsContextLengthBodies() {
        assertTrue(
                ContextWindowExceededException.isCause(400, "This model's maximum context length is 128000 tokens..."));
        assertTrue(ContextWindowExceededException.isCause(
                413, "{\"error\":{\"message\":\"Please reduce the length of the messages\"}}"));
        assertTrue(ContextWindowExceededException.isCause(
                400, "{\"error\":{\"message\":\"Input is too long: context_length 999 > 800\"}}"));
    }

    @Test
    void ignoresNonContextErrors() {
        assertFalse(ContextWindowExceededException.isCause(400, "Authentication required"));
        assertFalse(ContextWindowExceededException.isCause(500, "internal server error"));
        assertFalse(ContextWindowExceededException.isCause(200, "maximum context length is fine here"));
        assertFalse(ContextWindowExceededException.isCause(400, null));
        assertFalse(ContextWindowExceededException.isCause(400, ""));
    }
}
