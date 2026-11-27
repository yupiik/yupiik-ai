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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tolerant in-band tool-call scanner: one fixture per catalogue shape, with the {@code path}
 * argument-preservation guarantee that the fixed-spelling regex parser used to break. */
class MarkupToolCallScannerTest {
    private final MarkupToolCallScanner scanner = new MarkupToolCallScanner();

    @Test
    void canonicalDsmlEditFileKeepsPathUnderMissingParameterCloser() {
        // the critical regression: a parameter whose closer is absent must not drop "path"
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=edit_file>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>src/App.java\n"
                + "<\uFF5CDSML\uFF5Cparameter name=oldString string=true>a</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("edit_file", calls.get(0).function().name());
        assertTrue(calls.get(0).function().arguments().contains("\"path\":\"src/App.java\""));
        assertTrue(calls.get(0).function().arguments().contains("\"oldString\":\"a\""));
    }

    @Test
    void vllmStyleWithoutDsmBrandParsses() {
        // vLLM HF spelling: <|tool_calls> (no literal DSML word) with slash-first closers
        final var raw = "<\uFF5Ctool_calls>\n"
                + "<\uFF5Cinvoke name=read>\n"
                + "<\uFF5Cparameter name=path string=true>a.txt</\uFF5Cparameter>\n"
                + "</\uFF5Cinvoke>\n"
                + "</\uFF5Ctool_calls>";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\":\"a.txt\"}", calls.get(0).function().arguments());
    }

    @Test
    void asciiPipeDsmlWithQuotedAttributes() {
        final var raw = "<|DSML|tool_calls>\n"
                + "<|DSML|invoke name=\"edit_file\">\n"
                + "<|DSML|parameter name=\"path\" string=\"true\">target.txt</|DSML|parameter>\n"
                + "</|DSML|invoke>\n"
                + "</|DSML|tool_calls>";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("edit_file", calls.get(0).function().name());
        assertEquals("{\"path\":\"target.txt\"}", calls.get(0).function().arguments());
    }

    @Test
    void slashLastCloserFormAccepted() {
        // some gateways emit the closing slash after the pipe prefix (post-DSML mangling)
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=read>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>a.txt<\uFF5CDSML\uFF5C/parameter>\n"
                + "<\uFF5CDSML\uFF5C/invoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = scanner.parse(raw);
        assertTrue(calls.size() >= 1, calls.toString());
        final var c = calls.get(0);
        assertEquals("read", c.function().name());
        assertTrue(c.function().arguments().contains("\"path\":\"a.txt\""));
    }

    @Test
    void bpeFragmentGarbageIsSkippedAndPathSurvives() {
        // BPE-fragmented near-miss opener then a well-formed call
        final var raw = "Some prose.\n"
                + "<\uFF5C\uFF5Ctool_coll\uFF5C>\n" // fragmented garbage
                + "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=edit_file>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>x/y</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("edit_file", calls.get(0).function().name());
        assertTrue(calls.get(0).function().arguments().contains("\"path\":\"x/y\""));
    }

    @Test
    void missingStringAttributeFallsBackToJsonThenText() {
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=web_fetch>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=limit>5</\uFF5CDSML\uFF5Cparameter>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=url>https://e.test</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("web_fetch", calls.get(0).function().name());
        // limit=5 parses as JSON number; url is not JSON so stays a string
        assertTrue(calls.get(0).function().arguments().contains("\"limit\":5"));
        assertTrue(calls.get(0).function().arguments().contains("\"url\":\"https://e.test\""));
    }

    @Test
    void truncatedBlockSalvagesCompleteArguments() {
        // the whole block is cut mid-parameter but the first arg must survive
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=edit_file>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=path string=true>a.txt</\uFF5CDSML\uFF5Cparameter>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=oldString";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("edit_file", calls.get(0).function().name());
        assertTrue(calls.get(0).function().arguments().contains("\"path\":\"a.txt\""));
    }

    @Test
    void mistralToolCallsArray() {
        final var raw =
                "[TOOL_CALLS] [{\"name\": \"get_weather\", \"arguments\": {\"location\": \"SF\"}}, {\"name\": \"read\", \"arguments\": {\"path\": \"a.txt\"}}]";
        final var calls = scanner.parse(raw);
        assertEquals(2, calls.size(), calls.toString());
        assertEquals("get_weather", calls.get(0).function().name());
        assertEquals("{\"location\": \"SF\"}", calls.get(0).function().arguments());
        assertEquals("read", calls.get(1).function().name());
        assertEquals("{\"path\": \"a.txt\"}", calls.get(1).function().arguments());
    }

    @Test
    void mistralToolCallsSingleObjectLine() {
        final var raw = "[TOOL_CALLS] [{\"name\": \"read\", \"arguments\": {\"path\": \"/tmp/x\"}}]";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("read", calls.get(0).function().name());
    }

    @Test
    void stripRemovesMistralArrayAndDsmlAndLeavesProse() {
        final var raw = "Before.\n[TOOL_CALLS] [{\"name\": \"read\", \"arguments\": {}}]\n"
                + "<\uFF5CDSML\uFF5Ctool_calls>\n<\uFF5CDSML\uFF5Cinvoke name=read>\n{\"p\":1}\n</\uFF5CDSML\uFF5Cinvoke>\n</\uFF5CDSML\uFF5Ctool_calls>\nAfter.";
        final var out = scanner.strip(raw);
        assertTrue(out.contains("Before."));
        assertTrue(out.contains("After."));
        assertFalse(out.contains("TOOL_CALLS"));
        assertFalse(out.contains("tool_calls"));
    }

    @Test
    void jsonTagPreservesArgumentsVerbatim() {
        final var raw = "<tool_call>\n{\"name\": \"read\", \"arguments\": {\"path\": \"/root/x.txt\"}}\n</tool_call>";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\": \"/root/x.txt\"}", calls.get(0).function().arguments());
    }

    @Test
    void directJsonInsideInvokeAsFallback() {
        // documented fallback: the invoke body is a JSON object, not DSML parameters
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=edit_file>\n"
                + "{\"path\": \"b.txt\", \"oldString\": \"x\", \"newString\": \"y\"}\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        // parse yields no DSML params -> empty args, but the call itself is still surfaced
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("edit_file", calls.get(0).function().name());
    }

    @Test
    void escapesQuotesInDsmlStringValue() {
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=grep>\n"
                + "<\uFF5CDSML\uFF5Cparameter name=pattern string=true>say \"hi\"</\uFF5CDSML\uFF5Cparameter>\n"
                + "</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = scanner.parse(raw);
        assertEquals(1, calls.size(), calls.toString());
        assertEquals("{\"pattern\":\"say \\\"hi\\\"\"}", calls.get(0).function().arguments());
    }

    @Test
    void multipleInvokesKeepOrderAndIndependentArgs() {
        final var raw = "<\uFF5CDSML\uFF5Ctool_calls>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=read>\n<\uFF5CDSML\uFF5Cparameter name=path string=true>a</\uFF5CDSML\uFF5Cparameter>\n</\uFF5CDSML\uFF5Cinvoke>\n"
                + "<\uFF5CDSML\uFF5Cinvoke name=grep>\n<\uFF5CDSML\uFF5Cparameter name=pattern string=true>f</\uFF5CDSML\uFF5Cparameter>\n</\uFF5CDSML\uFF5Cinvoke>\n"
                + "</\uFF5CDSML\uFF5Ctool_calls>";
        final var calls = scanner.parse(raw);
        assertEquals(2, calls.size(), calls.toString());
        assertEquals("read", calls.get(0).function().name());
        assertEquals("{\"path\":\"a\"}", calls.get(0).function().arguments());
        assertEquals("grep", calls.get(1).function().name());
        assertEquals("{\"pattern\":\"f\"}", calls.get(1).function().arguments());
    }
}
