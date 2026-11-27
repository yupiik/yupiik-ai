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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ToolCallMarkupParserTest {

    private final ToolCallMarkupParser parser = new ToolCallMarkupParser();

    @Test
    void parsesDsmToolCalls() {
        final var raw = "I will read the file.\n\n"
                + "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=read>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>src/App.java</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final List<ToolCall> calls = parser.parse(raw);
        assertEquals(1, calls.size());
        final var call = calls.get(0);
        assertTrue(call.id().startsWith("btw-"), call.id());
        assertEquals("function", call.type());
        assertEquals("read", call.function().name());
        assertEquals("{\"path\":\"src/App.java\"}", call.function().arguments());
        assertNull(call.index());
    }

    @Test
    void parsesMultipleInvokesInOneBlock() {
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=read>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>a.txt</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=grep>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=pattern string=true>foo</\uFF5CDSML\uFF5Cparameter>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>b.txt</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = parser.parse(raw);
        assertEquals(2, calls.size());
        assertEquals("btw-1", calls.get(0).id());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\":\"a.txt\"}", calls.get(0).function().arguments());
        assertEquals("btw-2", calls.get(1).id());
        assertEquals("grep", calls.get(1).function().name());
        assertEquals(
                "{\"pattern\":\"foo\",\"path\":\"b.txt\"}",
                calls.get(1).function().arguments());
    }

    @Test
    void parsesLegacyV31Markers() {
        final var raw = "<\uFF5Ctool\u2581calls\u2581begin\uFF5C>\n"
                + "<\uFF5Ctool\u2581call\u2581begin\uFF5C>\n"
                + "name=read\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>src/App.java</\uFF5CDSML\uFF5Cparameter>\n"
                + "<\uFF5Ctool\u2581call\u2581end\uFF5C>\n"
                + "<\uFF5Ctool\u2581calls\u2581end\uFF5C>";
        final var calls = parser.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\":\"src/App.java\"}", calls.get(0).function().arguments());
    }

    @Test
    void parsesAsciiMangledMarkers() {
        final var raw = "<|tool_calls|>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=read>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>src/App.java</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</|tool_calls|>";
        final var calls = parser.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\":\"src/App.java\"}", calls.get(0).function().arguments());
    }

    @Test
    void parsesNonStringParameterAsRawJson() {
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=web_fetch>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=url string=true>https://example.test</\uFF5CDSML\uFF5Cparameter>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=timeout_seconds string=false>10</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = parser.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("web_fetch", calls.get(0).function().name());
        assertEquals(
                "{\"url\":\"https://example.test\",\"timeout_seconds\":10}",
                calls.get(0).function().arguments());
    }

    @Test
    void returnsEmptyWithoutMarkup() {
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("just a plain answer, no tools").isEmpty());
    }

    @Test
    void escapesQuotesAndBackslashesInParameters() {
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=grep>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=pattern string=true>say \"hi\"</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = parser.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("grep", calls.get(0).function().name());
        assertEquals("{\"pattern\":\"say \\\"hi\\\"\"}", calls.get(0).function().arguments());
    }

    @Test
    void parsesQwenJsonInTagWithArgumentsObject() {
        final var raw =
                "Answering now.\n<tool_call>\n{\"name\": \"read\", \"arguments\": {\"path\": \"a.txt\", \"limit\": 40}}\n</tool_call>";
        final var calls = parser.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("read", calls.get(0).function().name());
        // the arguments object is preserved verbatim
        assertEquals(
                "{\"path\": \"a.txt\", \"limit\": 40}", calls.get(0).function().arguments());
    }

    @Test
    void parsesQwenJsonInTagWithoutArguments() {
        final var raw = "<tool_call>\n{\"name\": \"read\"}\n</tool_call>";
        final var calls = parser.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{}", calls.get(0).function().arguments());
    }

    @Test
    void parsesHermesFunctionCallJsonTag() {
        final var raw =
                "<tool_call>\n{\"name\": \"get_current_weather\", \"arguments\": {\"location\": \"Boston\", \"unit\": \"fahrenheit\"}}\n</tool_call>";
        final var calls = parser.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("get_current_weather", calls.get(0).function().name());
        assertEquals(
                "{\"location\": \"Boston\", \"unit\": \"fahrenheit\"}",
                calls.get(0).function().arguments());
    }

    @Test
    void parsesGlmKvTag() {
        final var raw =
                "<tool_call>search_web<arg_key>search_terms</arg_key><arg_value>fusion json mapper</arg_value><arg_key>max_results</arg_key><arg_value>5</arg_value></tool_call>";
        final var calls = parser.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("search_web", calls.get(0).function().name());
        // GLM kv values are marshalled as JSON strings
        assertEquals(
                "{\"search_terms\":\"fusion json mapper\",\"max_results\":\"5\"}",
                calls.get(0).function().arguments());
    }
}
