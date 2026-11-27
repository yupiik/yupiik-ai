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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class AskUserAnswersFormatterTest {
    private JsonMapper mapper;
    private AskUserAnswersFormatter formatter;

    @BeforeEach
    void setUp(final JsonMapper mapper) {
        this.mapper = mapper;
        formatter = new AskUserAnswersFormatter(mapper);
    }

    @Test
    void flatAnswersRenderAsKeyValuePairs() {
        final var answers = formatter.answers("{\"integration\":\"fusion\",\"stack\":\"react\"}");
        assertEquals(2, answers.size());
        assertEquals("integration", answers.get(0).key());
        assertEquals("fusion", answers.get(0).value());
        assertEquals("stack", answers.get(1).key());
        assertEquals("react", answers.get(1).value());
    }

    @Test
    void keyOrderFollowsTheJsonDocument() {
        final var answers = formatter.answers("{\"b\":\"2\",\"a\":\"1\"}");
        assertEquals("b", answers.get(0).key());
        assertEquals("a", answers.get(1).key());
    }

    @Test
    void listValuesAreJoined() {
        final var answers = formatter.answers("{\"m\":[\"x\",\"y\"]}");
        assertEquals(1, answers.size());
        assertEquals("x, y", answers.get(0).value());
    }

    @Test
    void malformedJsonIsRejected() {
        assertNull(formatter.answers("{oops"));
        assertNull(formatter.answers(null));
        assertNull(formatter.answers("plain text"));
    }

    @Test
    void nonObjectJsonIsRejected() {
        assertNull(formatter.answers("[1,2]"));
        assertNull(formatter.answers("\"x\""));
    }

    @Test
    void nestedValuesAreRejected() {
        assertNull(formatter.answers("{\"k\":{\"n\":1}}"));
    }

    @Test
    void emptyObjectGivesEmptyList() {
        assertEquals(0, formatter.answers("{}").size());
    }
}
