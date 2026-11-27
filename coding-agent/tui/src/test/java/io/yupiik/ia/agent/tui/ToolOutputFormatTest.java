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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ToolOutputFormatTest {
    private JsonMapper mapper;
    private ToolOutputFormat service;

    @BeforeEach
    void setUp(final JsonMapper mapper) {
        this.mapper = mapper;
        service = new ToolOutputFormat(mapper);
    }

    @Test
    void compactJsonDetectedAndPrettyPrinted() {
        assertSame(ToolOutputFormat.Format.JSON, service.detect("{\"a\":1,\"b\":2}"));
        final var pretty = service.prettyJson("{\"a\":1,\"b\":2}");
        assertTrue(pretty.contains("\n"), pretty);
        assertTrue(pretty.contains("\"a\""), pretty);
    }

    @Test
    void invalidJsonStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("{oops"));
        assertEquals("{oops", service.prettyJson("{oops"));
    }

    @Test
    void xmlDetectedAndPrettyPrinted() {
        assertSame(ToolOutputFormat.Format.XML, service.detect("<root><a>1</a><b>2</b></root>"));
        final var pretty = service.prettyXml("<root><a>1</a><b>2</b></root>");
        assertTrue(pretty.contains("\n"), pretty);
    }

    @Test
    void doctypeXmlRejectedForSecurity() {
        final var raw = "<!DOCTYPE foo [ ]><root>secret</root>";
        assertSame(ToolOutputFormat.Format.TEXT, service.detect(raw));
        assertEquals(raw, service.prettyXml(raw));
    }

    @Test
    void yamlHeuristic() {
        assertSame(ToolOutputFormat.Format.YAML, service.detect("a: 1\nb: 2"));
    }

    @Test
    void diffSampleWins() {
        assertSame(ToolOutputFormat.Format.DIFF, service.detect("--- a/file\n+++ b/file\n-x\n+y"));
    }

    @Test
    void plainLogStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("2026-09-21 10:00:00 INFO boom\ndone"));
    }

    @Test
    void markdownDocumentWithFenceDetected() {
        assertSame(ToolOutputFormat.Format.MARKDOWN, service.detect("# Title\n\n```java\nint a = 1;\n```"));
    }

    @Test
    void markdownHeadingWithCompleteTableDetected() {
        assertSame(
                ToolOutputFormat.Format.MARKDOWN, service.detect("## Report\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n"));
    }

    @Test
    void markdownOneSignalStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("# Title only"));
    }

    /**
     * The regression: a python {@code tabulate(...)} dump plus one list marker used to be
     * classified as markdown and rendered as a bordered grid, prefixing every line with a
     * vertical bar. Two loose signals are no longer enough.
     */
    @Test
    void pipeTableWithOneListMarkerStaysText() {
        final var tabulated = """
                | name | age |
                |------|-----|
                | bob  | 42  |
                - bob is 42
                """;
        assertSame(ToolOutputFormat.Format.TEXT, service.detect(tabulated));
    }

    /** A delimiter row without a body row is not a table, so a piped dump stays text. */
    @Test
    void incompleteTableStaysText() {
        assertSame(ToolOutputFormat.Format.TEXT, service.detect("# a\n\n| x | y |\n| --- | --- |\n"));
    }

    /** A proven source language wins over the loose yaml key heuristic. */
    @Test
    void detectedLanguageWinsOverYaml() {
        assertSame(ToolOutputFormat.Format.CODE, service.detect("name: bob\nage: 42", "python3 report.py"));
    }

    /** ...but json/xml/diff keep their dedicated rendering even for a code command. */
    @Test
    void jsonStillWinsOverDetectedLanguage() {
        assertSame(ToolOutputFormat.Format.JSON, service.detect("{\"a\":1,\"b\":2}", "python3 x.py"));
    }

    /** A proven document language renders as colored source, not the MARKDOWN document render. */
    @Test
    void provenDocumentLanguagesRenderAsColoredSource() {
        assertSame(ToolOutputFormat.Format.CODE, service.detect("# Title\n\n```java\nint a;\n```\n", "markdown"));
        assertSame(ToolOutputFormat.Format.CODE, service.detect("= Doc\n\n:key: value\n", "asciidoc"));
    }

    @Test
    void detectionEvictsByWeightIncludingLanguage() throws Exception {
        final var language = "x".repeat(200 * 1024);
        for (var i = 0; i < 6; i++) {
            assertSame(ToolOutputFormat.Format.CODE, service.detect("output" + i, language));
        }
        final var cached = cache("detectCache");
        assertEquals(5, cached.size());
        assertTrue(!cached.containsKey(language + "\noutput0"));
        assertTrue(cached.containsKey(language + "\noutput5"));
        assertWeight("detectCache", 1024 * 1024);
    }

    @Test
    void detectionEvictsByEntryCount() throws Exception {
        for (var i = 0; i < 129; i++) {
            service.detect("output" + i, "java");
        }
        assertEquals(128, cache("detectCache").size());
        assertTrue(!cache("detectCache").containsKey("java\noutput0"));
        assertWeight("detectCache", 1024 * 1024);
    }

    @Test
    void detectionBypassesOversizedSources() throws Exception {
        final var exact = "x".repeat(256 * 1024);
        assertSame(ToolOutputFormat.Format.CODE, service.detect(exact, ""));
        assertTrue(cache("detectCache").isEmpty());
        assertSame(ToolOutputFormat.Format.CODE, service.detect("output", exact));
        assertTrue(cache("detectCache").isEmpty());
        assertTrue(cache("detectCache").isEmpty());
        assertWeight("detectCache", 1024 * 1024);
    }

    @Test
    void prettyEvictsByCombinedSourceAndValueWeight() throws Exception {
        for (var i = 0; i < 6; i++) {
            final var raw = "\"" + i + "x".repeat(50_000) + "\"";
            final var pretty = service.prettyJson(raw);
            assertEquals(pretty, service.prettyJson(raw));
        }
        final var cached = cache("prettyCache");
        assertEquals(5, cached.size());
        assertTrue(cached.keySet().stream().noneMatch(key -> key.startsWith("\"0")));
        assertWeight("prettyCache", 512 * 1024);
    }

    @Test
    void prettyEvictsByEntryCountAndBypassesOversizedSourcesAndValues() throws Exception {
        for (var i = 0; i < 17; i++) {
            service.prettyJson("[" + i + "]");
        }
        assertEquals(16, cache("prettyCache").size());
        assertTrue(!cache("prettyCache").containsKey("[0]"));
        service.clearCaches();
        final var oversized = "\"" + "x".repeat(65536) + "\"";
        assertSame(oversized, service.prettyJson(oversized));
        assertSame(oversized, service.prettyXml(oversized));
        assertTrue(cache("prettyCache").isEmpty());
        final var expanding = "<r>".repeat(80) + "<a/>".repeat(4000) + "</r>".repeat(80);
        final var result = service.prettyXml(expanding);
        assertTrue(result.length() > 512 * 1024);
        assertEquals(result, service.prettyXml(expanding));
        assertTrue(cache("prettyCache").isEmpty());
        assertWeight("prettyCache", 512 * 1024);
    }

    @Test
    void cachedAndClearedResultsHaveParity() throws Exception {
        for (final var raw : new String[] {"{\"a\":1}", "<r><a>1</a></r>", "invalid", "", "a: 1\nb: 2"}) {
            final var detected = service.detect(raw);
            final var json = service.prettyJson(raw);
            assertSame(detected, service.detect(raw));
            assertEquals(json, service.prettyJson(raw));
            service.clearCaches();
            final var xml = service.prettyXml(raw);
            assertEquals(xml, service.prettyXml(raw));
            service.clearCaches();
            assertSame(detected, service.detect(raw));
            assertEquals(json, service.prettyJson(raw));
            service.clearCaches();
            assertEquals(xml, service.prettyXml(raw));
        }
        service.clearCaches();
        service.clearCaches();
        assertTrue(cache("detectCache").isEmpty());
        assertTrue(cache("prettyCache").isEmpty());
        assertWeight("detectCache", 1024 * 1024);
        assertWeight("prettyCache", 512 * 1024);
    }

    @Test
    void concurrentReplacementAndClearingKeepWeightsConsistent() throws Exception {
        try (final var executor = Executors.newFixedThreadPool(4)) {
            final var tasks = IntStream.range(0, 4)
                    .<Callable<Void>>mapToObj(worker -> () -> {
                        for (var i = 0; i < 100; i++) {
                            if (worker == 0) {
                                service.clearCaches();
                            } else {
                                assertSame(ToolOutputFormat.Format.JSON, service.detect("[1,2,3]", "java"));
                                assertTrue(service.prettyJson("[1,2,3]").contains("\n"));
                            }
                        }
                        return null;
                    })
                    .toList();
            for (final var future : executor.invokeAll(tasks, 1, TimeUnit.SECONDS)) {
                future.get();
            }
        }
        assertWeight("detectCache", 1024 * 1024);
        assertWeight("prettyCache", 512 * 1024);
        service.clearCaches();
        assertWeight("detectCache", 1024 * 1024);
        assertWeight("prettyCache", 512 * 1024);
    }

    // Retention limits are observable only through retained entries; keep inspection out of the production API.
    @SuppressWarnings("unchecked")
    private Map<String, ?> cache(final String name) throws Exception {
        final var field = ToolOutputFormat.class.getDeclaredField(name);
        field.setAccessible(true);
        final var cache = (Map<String, ?>) field.get(service);
        synchronized (cache) {
            return Map.copyOf(cache);
        }
    }

    private void assertWeight(final String name, final int maximum) throws Exception {
        final var entries = cache(name);
        final var weight = entries.entrySet().stream()
                .mapToInt(entry ->
                        entry.getKey().length() + (entry.getValue() instanceof String value ? value.length() : 0))
                .sum();
        assertTrue(weight <= maximum);
        final var field = ToolOutputFormat.class.getDeclaredField(name + "Weight");
        field.setAccessible(true);
        assertEquals(weight, field.getInt(service));
    }
}
