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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AcpSessionHistoryTest {
    @Test
    @Timeout(5)
    void sessionIdsPreserveAsciiGrammarAndRejectOversizedInputs() {
        for (final var id : List.of("a", "Z", "0", "a._-09AZaz", "A" + "_".repeat(127))) {
            assertEquals(id, AcpSessionHistory.validateSessionId(id));
        }
        for (final var id : Arrays.asList(
                null,
                "",
                "_a",
                "-a",
                ".a",
                "a/b",
                "a\\b",
                "a b",
                "a\t",
                "a\n",
                "a\r\n",
                "a\u0085",
                "a\u2028",
                "\u00e9",
                "\uff11",
                "a\ud83d\ude00",
                "a".repeat(129),
                "a".repeat(100_000),
                "a".repeat(127) + "/")) {
            assertThrows(IllegalArgumentException.class, () -> AcpSessionHistory.validateSessionId(id));
        }
    }

    @Test
    void listingFiltersNormalizesSortsDeduplicatesAndPaginatesWithoutRetainedState() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            store.add("b", 3000, "/projects/a", "Second", List.of());
            store.add("a", 3000, "/projects/a/child/..", "  First\t title\u0000\u202e\nENV_SECRET", List.of());
            store.add("c", 2000, "/projects/a", "x".repeat(200), List.of());
            store.add("other", 4000, "/projects/b", "", List.of());
            store.infos.add(new SessionStore.SessionInfo("a", 1000, 999999));
            store.add("unknown", 9000, null, "Never shown", List.of());
            store.add("relative", 9000, "relative/path", "Never shown", List.of());
            store.add("invalid", 9000, "/projects/\u0000a", "Never shown", List.of());
            store.add("bad-metadata", 9000, Map.of("env", "SECRET"), "Never shown", List.of());
            store.add("../escape", 9999, "/projects/a", "Never accessed", List.of());
            final var history = new AcpSessionHistory(store, mapper, 2);
            final var first = history.list("/projects/a/.", null);
            assertEquals(
                    List.of(
                            new AcpSessionHistory.SessionInfo(
                                    "a", "/projects/a", "First title", "1970-01-01T00:00:03Z"),
                            new AcpSessionHistory.SessionInfo("b", "/projects/a", "Second", "1970-01-01T00:00:03Z")),
                    first.sessions());
            assertNotNull(first.nextCursor());
            assertTrue(first.nextCursor().length() < 1024);
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessions",
                                            List.of(
                                                    Map.of(
                                                            "sessionId",
                                                            "a",
                                                            "cwd",
                                                            "/projects/a",
                                                            "title",
                                                            "First title",
                                                            "updatedAt",
                                                            "1970-01-01T00:00:03Z"),
                                                    Map.of(
                                                            "sessionId",
                                                            "b",
                                                            "cwd",
                                                            "/projects/a",
                                                            "title",
                                                            "Second",
                                                            "updatedAt",
                                                            "1970-01-01T00:00:03Z")),
                                    "nextCursor", first.nextCursor())),
                    raw(mapper, first));

            // A fresh helper continues the same cursor: no connection-local pagination cache.
            final var second = new AcpSessionHistory(store, mapper, 2).list("/projects/a", first.nextCursor());
            assertEquals(
                    List.of(new AcpSessionHistory.SessionInfo(
                            "c", "/projects/a", "x".repeat(120), "1970-01-01T00:00:02Z")),
                    second.sessions());
            assertNull(second.nextCursor());
            assertFalse(raw(mapper, second).containsKey("nextCursor"));
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessions",
                                    List.of(Map.of(
                                            "sessionId",
                                            "other",
                                            "cwd",
                                            "/projects/b",
                                            "updatedAt",
                                            "1970-01-01T00:00:04Z")))),
                    raw(mapper, history.list("/projects/b", null)));
            assertEquals(
                    List.of("other", "a"),
                    history.list(null, null).sessions().stream()
                            .map(AcpSessionHistory.SessionInfo::sessionId)
                            .toList());
            assertEquals(Map.of("sessions", List.of()), raw(mapper, history.list("/projects/absent", null)));
            assertEquals(0, store.loads);
            assertFalse(store.metadataIds.contains("../escape"));
            final var json = mapper.toString(first);
            for (final var forbidden : List.of("ENV_SECRET", "SECRET", "sizeBytes", "_meta", "mcpServers", "env")) {
                assertFalse(json.contains(forbidden), json);
            }
        }
    }

    @Test
    void malformedOptionalTitlesAndDatesAreOmittedAndNoCwdIsFabricated() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            store.add("empty-summary", 0, "/a", "", List.of());
            store.add("bad-summary", 0, "/a", Map.of("credentials", "SECRET"), List.of());
            store.add("digest-title", 0, "/a", "<compacted-context>hidden</compacted-context>", List.of());
            store.add("blank-cwd", 0, "", "Invisible", List.of());
            store.add("newline-cwd", 0, "/a\nSECRET", "Invisible", List.of());
            store.add("missing-cwd", 0, null, "Invisible", List.of());
            final var history = new AcpSessionHistory(store, mapper, 50);
            assertEquals(
                    Map.of(
                            "sessions",
                            List.of(
                                    Map.of("sessionId", "bad-summary", "cwd", "/a"),
                                    Map.of("sessionId", "digest-title", "cwd", "/a"),
                                    Map.of("sessionId", "empty-summary", "cwd", "/a"))),
                    raw(mapper, history.list(null, null)));
            for (final var filter : List.of("", "a", "/a\u0000", "/a\n")) {
                assertThrows(IllegalArgumentException.class, () -> history.list(filter, null));
            }
            assertThrows(IllegalArgumentException.class, () -> new AcpSessionHistory(store, mapper, 0));
            assertThrows(IllegalArgumentException.class, () -> new AcpSessionHistory(store, mapper, 101));
        }
    }

    @Test
    void cursorsRejectMalformedCrossQueryCrossSizeAndStaleAnchors() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            store.add("a", 3000, "/a", "a", List.of());
            store.add("b", 2000, "/a", "b", List.of());
            store.add("c", 1000, "/a", "c", List.of());
            final var history = new AcpSessionHistory(store, mapper, 1);
            final var token = history.list("/a", null).nextCursor();
            final var cursor = raw(
                    mapper,
                    mapper.fromString(
                            Object.class, new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)));
            for (final var invalid : List.of(
                    "",
                    "not a cursor",
                    "A",
                    "!",
                    "x".repeat(1025),
                    "x".repeat(100_000),
                    token + "=",
                    token + "\n",
                    token + "\u00e9",
                    token + "\u2028",
                    token("{}"),
                    token("{"))) {
                final var error = assertThrows(IllegalArgumentException.class, () -> history.list("/a", invalid));
                assertEquals("Invalid session cursor; restart session listing", error.getMessage());
            }
            for (final var field : List.of("v", "scope", "id", "at")) {
                final var invalid = new LinkedHashMap<>(cursor);
                invalid.put(
                        field,
                        switch (field) {
                            case "v" -> "2";
                            case "scope" -> "wrong-query";
                            case "id" -> "../escape";
                            default -> "not-a-number";
                        });
                assertThrows(IllegalArgumentException.class, () -> history.list("/a", token(mapper.toString(invalid))));
            }
            final var extra = new LinkedHashMap<>(cursor);
            extra.put("env", "SECRET");
            assertThrows(IllegalArgumentException.class, () -> history.list("/a", token(mapper.toString(extra))));
            assertThrows(IllegalArgumentException.class, () -> history.list("/b", token));
            assertThrows(IllegalArgumentException.class, () -> history.list(null, token));
            assertThrows(
                    IllegalArgumentException.class, () -> new AcpSessionHistory(store, mapper, 2).list("/a", token));
            assertEquals(
                    "b",
                    new AcpSessionHistory(store, mapper, 1)
                            .list("/a/.", token)
                            .sessions()
                            .getFirst()
                            .sessionId());

            store.infos.set(0, new SessionStore.SessionInfo("a", 4000, 1));
            assertThrows(IllegalArgumentException.class, () -> history.list("/a", token));
            store.infos.set(0, new SessionStore.SessionInfo("a", 3000, 1));
            store.projects.put("a", "/b");
            assertThrows(IllegalArgumentException.class, () -> history.list("/a", token));
            store.projects.put("a", "/a");
            store.infos.removeFirst();
            assertThrows(IllegalArgumentException.class, () -> history.list("/a", token));
            assertEquals("b", history.list("/a", null).sessions().getFirst().sessionId());
        }
    }

    @Test
    void knownEmptySessionsAreNotConfusedWithUnknownAndInvalidIdsNeverReachTheStore() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            store.add("empty", 1, null, "", List.of());
            final var history = new AcpSessionHistory(store, mapper, 50);
            assertTrue(history.exists("empty"));
            assertEquals("empty", history.requireKnown("empty").id());
            assertFalse(history.exists("missing"));
            assertEquals(0, store.loads);
            assertThrows(AcpSessionHistory.UnknownSessionException.class, () -> history.requireKnown("missing"));
            assertThrows(
                    AcpSessionHistory.UnknownSessionException.class,
                    () -> history.replay("missing", ignored -> fail()));
            assertEquals(0, store.loads);
            assertEquals(new AcpSessionHistory.ReplayReport(0, List.of()), history.replay("empty", ignored -> fail()));
            assertEquals(1, store.loads);
            final var lists = store.lists;
            for (final var id : Arrays.asList(
                    null, "", ".", "..", "../a", "a/b", "a\\b", "/a", "a\n", "a\u0000", "x".repeat(129))) {
                assertThrows(IllegalArgumentException.class, () -> AcpSessionHistory.validateSessionId(id));
                assertThrows(IllegalArgumentException.class, () -> history.exists(id));
                assertThrows(IllegalArgumentException.class, () -> history.requireKnown(id));
                assertThrows(IllegalArgumentException.class, () -> history.replay(id, ignored -> fail()));
            }
            assertEquals(lists, store.lists);
            assertEquals(1, store.loads);
            assertEquals("import.v1-1", AcpSessionHistory.validateSessionId("import.v1-1"));
            assertEquals(1, store.infos.size());
        }
    }

    @Test
    void replayPreservesOrderedMessagesToolRelationshipsErrorsAndMediaWithoutMetadataLeakage() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            final var hidden = Map.<String, Object>of(
                    "env", Map.of("API_KEY", "ENV_SECRET"),
                    "mcpServers", List.of(Map.of("headers", "HEADER_SECRET")),
                    "yca", Map.of("provider", "PRIVATE_PROVIDER"),
                    "reasoning_content", "HIDDEN_REASONING");
            final var image = Map.of("type", "image", "mimeType", "image/png", "data", "aW1hZ2U=");
            final var resource = Map.of("type", "resource", "resource", Map.of("uri", "file:///a", "text", "contents"));
            store.add(
                    "s",
                    1,
                    "/a",
                    "Title",
                    List.of(
                            Message.projectDir("/a"),
                            Message.system("SYSTEM_SECRET"),
                            new Message("developer", "DEVELOPER_SECRET", null, null, null, null),
                            new Message("notice", "LIVE_NOTICE_SECRET", null, null, null, null),
                            new Message("service", "SERVICE_SECRET", null, null, null, null),
                            new Message("user", "Question", null, null, null, hidden),
                            new Message(
                                    "assistant",
                                    "Working",
                                    null,
                                    List.of(
                                            call("read-id", "read", "{\"path\":\"/a\"}"),
                                            call("broken-id", "execute", "not-json")),
                                    null,
                                    hidden),
                            Message.tool("broken-id", "Failed", true),
                            new Message(
                                    "tool",
                                    Map.of(
                                            "content",
                                            List.of(Map.of("type", "text", "text", "Read"), image, resource),
                                            "structuredContent",
                                            Map.of("matches", 1),
                                            "_meta",
                                            hidden),
                                    null,
                                    null,
                                    "read-id",
                                    hidden),
                            Message.assistant("Done", null)));
            final var events = new ArrayList<Map<String, Object>>();
            final var history = new AcpSessionHistory(store, mapper, 1);
            final var report = history.replay("s", events::add);
            assertEquals(new AcpSessionHistory.ReplayReport(7, List.of()), report);
            assertEquals(
                    List.of(
                            "user_message_chunk",
                            "agent_message_chunk",
                            "tool_call",
                            "tool_call",
                            "tool_call_update",
                            "tool_call_update",
                            "agent_message_chunk"),
                    events.stream()
                            .map(AcpSessionHistoryTest::update)
                            .map(it -> it.get("sessionUpdate"))
                            .toList());
            final var wire = wireEvents(mapper, events);
            assertEquals(
                    Map.of(
                            "sessionId",
                            "s",
                            "update",
                            Map.of(
                                    "sessionUpdate",
                                    "user_message_chunk",
                                    "messageId",
                                    update(wire.get(0)).get("messageId"),
                                    "content",
                                    Map.of("type", "text", "text", "Question"))),
                    wire.get(0));
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessionUpdate",
                                    "tool_call",
                                    "toolCallId",
                                    "read-id",
                                    "title",
                                    "read",
                                    "name",
                                    "read",
                                    "kind",
                                    "other",
                                    "status",
                                    "pending",
                                    "rawInput",
                                    Map.of("path", "/a"))),
                    update(wire.get(2)));
            assertEquals("not-json", update(wire.get(3)).get("rawInput"));
            assertEquals(
                    Map.of(
                            "sessionUpdate",
                            "tool_call_update",
                            "toolCallId",
                            "broken-id",
                            "status",
                            "failed",
                            "content",
                            List.of(Map.of("type", "content", "content", Map.of("type", "text", "text", "Failed"))),
                            "rawOutput",
                            "Failed"),
                    update(wire.get(4)));
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessionUpdate",
                                    "tool_call_update",
                                    "toolCallId",
                                    "read-id",
                                    "status",
                                    "completed",
                                    "content",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "content",
                                                    "content",
                                                    Map.of("type", "text", "text", "Read")),
                                            Map.of("type", "content", "content", image),
                                            Map.of("type", "content", "content", resource)),
                                    "rawOutput",
                                    Map.of(
                                            "content",
                                            List.of(Map.of("type", "text", "text", "Read"), image, resource),
                                            "structuredContent",
                                            Map.of("matches", 1),
                                            "isError",
                                            false))),
                    update(wire.get(5)));
            assertTrue(wire.stream().allMatch(event -> event.keySet().equals(Set.of("sessionId", "update"))));
            final var json = mapper.toString(events);
            for (final var forbidden :
                    List.of("SECRET", "HIDDEN_REASONING", "PRIVATE_PROVIDER", "mcpServers", "_meta", "thinking")) {
                assertFalse(json.contains(forbidden), json);
            }
            final var again = new ArrayList<Map<String, Object>>();
            new AcpSessionHistory(store, mapper, 100).replay("s", again::add);
            assertEquals(wire, wireEvents(mapper, again));
            store.messages.get("s").add(Message.user("Appended"));
            final var changed = new ArrayList<Map<String, Object>>();
            history.replay("s", changed::add);
            assertNotEquals(
                    update(events.getFirst()).get("messageId"),
                    update(changed.getFirst()).get("messageId"));
        }
    }

    @Test
    void persistedDisplayableThoughtsKeepChannelOrderButNeverExposeRawReasoningOrTimingMetadata() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            final var feedback = Map.of(
                    "feedback",
                    List.of(
                            Map.of("kind", "text", "offset", 0, "at", 10),
                            Map.of("kind", "thinking", "offset", 0, "at", 11),
                            Map.of("kind", "text", "offset", 1, "at", 12)),
                    "provider",
                    "PROVIDER_SECRET");
            store.add(
                    "s",
                    1,
                    "/a",
                    "",
                    List.of(
                            new Message(
                                    "assistant",
                                    "AB",
                                    null,
                                    null,
                                    null,
                                    Map.of(
                                            "thinking",
                                            "Thought",
                                            "yca",
                                            feedback,
                                            "reasoning_content",
                                            "RAW_REASONING_SECRET",
                                            "reasoning_items",
                                            List.of(Map.of("encrypted_content", "ENCRYPTED_SECRET")))),
                            new Message(
                                    "assistant",
                                    "Answer",
                                    null,
                                    null,
                                    null,
                                    Map.of(
                                            "thinking",
                                            "Legacy thought",
                                            "yca",
                                            Map.of("feedback", "MALFORMED_SECRET"))),
                            new Message(
                                    "assistant",
                                    "Still visible",
                                    null,
                                    null,
                                    null,
                                    Map.of("thinking", Map.of("env", "MALFORMED_SECRET"))),
                            new Message("user", "User", null, null, null, Map.of("thinking", "USER_EXTENSION_SECRET")),
                            new Message("assistant", null, null, null, null, Map.of("thinking", "Thought only"))));
            final var events = new ArrayList<Map<String, Object>>();
            final var report = new AcpSessionHistory(store, mapper, 50).replay("s", events::add);
            assertEquals(
                    new AcpSessionHistory.ReplayReport(
                            8, List.of(issue(2, AcpSessionHistory.Reason.UNSUPPORTED_CONTENT))),
                    report);
            final var wire = wireEvents(mapper, events);
            assertEquals(
                    List.of(
                            "agent_message_chunk",
                            "agent_thought_chunk",
                            "agent_message_chunk",
                            "agent_thought_chunk",
                            "agent_message_chunk",
                            "agent_message_chunk",
                            "user_message_chunk",
                            "agent_thought_chunk"),
                    wire.stream()
                            .map(AcpSessionHistoryTest::update)
                            .map(it -> it.get("sessionUpdate"))
                            .toList());
            assertEquals(
                    List.of("A", "Thought", "B", "Legacy thought", "Answer", "Still visible", "User", "Thought only"),
                    wire.stream()
                            .map(AcpSessionHistoryTest::update)
                            .map(it -> ((Map<?, ?>) it.get("content")).get("text"))
                            .toList());
            assertEquals(
                    update(wire.get(0)).get("messageId"), update(wire.get(2)).get("messageId"));
            assertNotEquals(
                    update(wire.get(0)).get("messageId"), update(wire.get(1)).get("messageId"));
            assertEquals(
                    Map.of(
                            "sessionId",
                            "s",
                            "update",
                            Map.of(
                                    "sessionUpdate",
                                    "agent_thought_chunk",
                                    "messageId",
                                    update(wire.get(1)).get("messageId"),
                                    "content",
                                    Map.of("type", "text", "text", "Thought"))),
                    wire.get(1));
            assertFalse(mapper.toString(events).contains("SECRET"));
            assertFalse(mapper.toString(events).contains("feedback"));
        }
    }

    @Test
    void allSupportedContentBlocksKeepTheirExactWireShapesAndMessageGrouping() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            final var text = Map.of(
                    "type",
                    "text",
                    "text",
                    "Text",
                    "annotations",
                    Map.of(
                            "audience",
                            List.of("user"),
                            "priority",
                            0.5,
                            "lastModified",
                            "2026-01-01T00:00:00Z",
                            "env",
                            "SECRET"),
                    "_meta",
                    Map.of("key", "SECRET"));
            final var image =
                    Map.of("type", "image", "mimeType", "image/png", "data", "aW1n", "uri", "file:///image.png");
            final var audio = Map.of("type", "audio", "mimeType", "audio/wav", "data", "d2F2");
            final var embeddedText = Map.of(
                    "type",
                    "resource",
                    "resource",
                    Map.of("uri", "file:///a", "text", "A", "mimeType", "text/plain", "_meta", "SECRET"));
            final var embeddedBlob = Map.of(
                    "type",
                    "resource",
                    "resource",
                    Map.of("uri", "file:///b", "blob", "Yg==", "mimeType", "application/octet-stream"));
            final var link = Map.of(
                    "type",
                    "resource_link",
                    "uri",
                    "file:///c",
                    "name",
                    "c",
                    "size",
                    12,
                    "title",
                    "C",
                    "description",
                    "Description",
                    "mimeType",
                    "text/plain");
            store.add(
                    "s",
                    1,
                    "/a",
                    "",
                    List.of(
                            new Message(
                                    "user",
                                    List.of(
                                            text,
                                            image,
                                            audio,
                                            embeddedText,
                                            embeddedBlob,
                                            link,
                                            Map.of(
                                                    "type",
                                                    "image_url",
                                                    "image_url",
                                                    Map.of("url", "data:image/png;base64,aW1n")),
                                            Map.of(
                                                    "type",
                                                    "image_url",
                                                    "image_url",
                                                    Map.of("url", "https://example.invalid/image.png")),
                                            Map.of(
                                                    "type",
                                                    "input_audio",
                                                    "input_audio",
                                                    Map.of("data", "bXAz", "format", "mp3"))),
                                    null,
                                    null,
                                    null,
                                    null),
                            new Message(
                                    "assistant",
                                    List.of(Map.of("type", "output_text", "text", "Done"), image),
                                    null,
                                    null,
                                    null,
                                    null),
                            Message.user("Repeated"),
                            Message.user("Repeated")));
            final var events = new ArrayList<Map<String, Object>>();
            final var report = new AcpSessionHistory(store, mapper, 1).replay("s", events::add);
            assertEquals(13, report.emittedUpdates());
            assertEquals(List.of(), report.issues());
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "blocks",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "text",
                                                    "text",
                                                    "Text",
                                                    "annotations",
                                                    Map.of(
                                                            "audience",
                                                            List.of("user"),
                                                            "priority",
                                                            0.5,
                                                            "lastModified",
                                                            "2026-01-01T00:00:00Z")),
                                            image,
                                            audio,
                                            Map.of(
                                                    "type",
                                                    "resource",
                                                    "resource",
                                                    Map.of("uri", "file:///a", "text", "A", "mimeType", "text/plain")),
                                            embeddedBlob,
                                            link,
                                            Map.of("type", "image", "mimeType", "image/png", "data", "aW1n"),
                                            Map.of(
                                                    "type",
                                                    "resource_link",
                                                    "uri",
                                                    "https://example.invalid/image.png",
                                                    "name",
                                                    "Image"),
                                            Map.of("type", "audio", "mimeType", "audio/mpeg", "data", "bXAz")))),
                    raw(
                            mapper,
                            Map.of(
                                    "blocks",
                                    events.subList(0, 9).stream()
                                            .map(AcpSessionHistoryTest::update)
                                            .map(it -> it.get("content"))
                                            .toList())));
            assertEquals(
                    1,
                    events.subList(0, 9).stream()
                            .map(AcpSessionHistoryTest::update)
                            .map(it -> it.get("messageId"))
                            .distinct()
                            .count());
            assertEquals(
                    1,
                    events.subList(9, 11).stream()
                            .map(AcpSessionHistoryTest::update)
                            .map(it -> it.get("messageId"))
                            .distinct()
                            .count());
            assertNotEquals(
                    update(events.get(11)).get("messageId"),
                    update(events.get(12)).get("messageId"));
            assertFalse(mapper.toString(events).contains("SECRET"));
        }
    }

    @Test
    void malformedContentIsReportedWithoutFlatteningAndOrphanResultsAreNotInventedInvocations() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            store.add(
                    "s",
                    1,
                    "/a",
                    "",
                    List.of(
                            new Message(
                                    "user",
                                    List.of(
                                            Map.of("type", "video", "secret", "UNSUPPORTED_SECRET"),
                                            Map.of("type", "image", "mimeType", "image/png"),
                                            Map.of("type", "resource", "resource", Map.of("uri", "file:///a")),
                                            Map.of(
                                                    "type",
                                                    "text",
                                                    "text",
                                                    "Visible",
                                                    "annotations",
                                                    Map.of(
                                                            "priority",
                                                            "SECRET",
                                                            "lastModified",
                                                            "bad",
                                                            "audience",
                                                            List.of("system")))),
                                    null,
                                    null,
                                    null,
                                    null),
                            Message.assistant(
                                    null,
                                    Arrays.asList(
                                            null,
                                            call(null, "bad", "{}"),
                                            call("ok", "read", "{}"),
                                            call("ok", "read", "{}"))),
                            Message.tool(null, "INVALID_TOOL_SECRET"),
                            Message.toolResult(
                                    "orphan",
                                    "{\"content\":[{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"aW1n\"}],\"isError\":true,\"_meta\":{\"key\":\"META_SECRET\"}}",
                                    "read"),
                            new Message(
                                    "tool",
                                    Map.of("answer", 42, "_meta", Map.of("key", "META_SECRET")),
                                    null,
                                    null,
                                    "ok",
                                    null),
                            new Message("unrecognized", "UNKNOWN_ROLE_SECRET", null, null, null, null)));
            final var events = new ArrayList<Map<String, Object>>();
            final var report = new AcpSessionHistory(store, mapper, 50).replay("s", events::add);
            assertEquals(4, report.emittedUpdates());
            assertEquals(
                    List.of(
                            issue(0, AcpSessionHistory.Reason.UNSUPPORTED_CONTENT),
                            issue(0, AcpSessionHistory.Reason.UNSUPPORTED_CONTENT),
                            issue(0, AcpSessionHistory.Reason.UNSUPPORTED_CONTENT),
                            issue(1, AcpSessionHistory.Reason.INVALID_TOOL_CALL),
                            issue(1, AcpSessionHistory.Reason.INVALID_TOOL_CALL),
                            issue(1, AcpSessionHistory.Reason.DUPLICATE_TOOL_CALL_ID),
                            issue(2, AcpSessionHistory.Reason.INVALID_TOOL_CALL),
                            issue(3, AcpSessionHistory.Reason.ORPHAN_TOOL_RESULT),
                            issue(5, AcpSessionHistory.Reason.UNSUPPORTED_ROLE)),
                    report.issues());
            final var wire = wireEvents(mapper, events);
            assertEquals(
                    Map.of("type", "text", "text", "Visible"),
                    update(wire.getFirst()).get("content"));
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessionUpdate",
                                    "tool_call",
                                    "toolCallId",
                                    "orphan",
                                    "title",
                                    "read",
                                    "name",
                                    "read",
                                    "status",
                                    "failed",
                                    "content",
                                    List.of(Map.of(
                                            "type",
                                            "content",
                                            "content",
                                            Map.of("type", "image", "mimeType", "image/png", "data", "aW1n"))),
                                    "rawOutput",
                                    Map.of(
                                            "content",
                                            List.of(Map.of("type", "image", "mimeType", "image/png", "data", "aW1n")),
                                            "isError",
                                            true))),
                    update(wire.get(2)));
            assertFalse(update(wire.get(2)).containsKey("rawInput"));
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessionUpdate",
                                    "tool_call_update",
                                    "toolCallId",
                                    "ok",
                                    "status",
                                    "completed",
                                    "content",
                                    List.of(),
                                    "rawOutput",
                                    Map.of("answer", 42))),
                    update(wire.get(3)));
            assertFalse(mapper.toString(events).contains("SECRET"));
        }
    }

    @Test
    void compactionDigestsAreReportedNotDisclosedOrInventedAsEventsAndOrdinaryExamplesSurvive() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            store.add(
                    "s",
                    1,
                    "/a",
                    "",
                    List.of(
                            Message.user("<compacted-context>\nINTERNAL_SUMMARY_SECRET\n</compacted-context>"),
                            Message.user("<pruned-context>\nINTERNAL_PRUNED_SECRET\n</pruned-context>"),
                            Message.user("Example: <compacted-context>quoted</compacted-context>"),
                            Message.user("<compacted-context>unclosed example"),
                            Message.assistant("<compacted-context>agent example</compacted-context>", null),
                            Message.user(
                                    "<subagent sessionID=\"sub1\" state=\"completed\" description=\"Worker\">\nSERVICE_SECRET\n</subagent>"),
                            Message.user("Example: <subagent sessionID=\"sub1\">quoted</subagent>")));
            final var events = new ArrayList<Map<String, Object>>();
            final var report = new AcpSessionHistory(store, mapper, 50).replay("s", events::add);
            assertEquals(
                    new AcpSessionHistory.ReplayReport(
                            4,
                            List.of(
                                    issue(0, AcpSessionHistory.Reason.COMPACTION_UNAVAILABLE),
                                    issue(1, AcpSessionHistory.Reason.COMPACTION_UNAVAILABLE),
                                    issue(5, AcpSessionHistory.Reason.SERVICE_MESSAGE_UNAVAILABLE))),
                    report);
            final var wire = wireEvents(mapper, events);
            assertEquals(
                    List.of("user_message_chunk", "user_message_chunk", "agent_message_chunk", "user_message_chunk"),
                    wire.stream()
                            .map(AcpSessionHistoryTest::update)
                            .map(it -> it.get("sessionUpdate"))
                            .toList());
            assertFalse(mapper.toString(events).contains("SECRET"));
        }
    }

    @Test
    void toolContentWrappersDiffsAndSerializedMediaArePreservedButStaleTerminalsAreNotFabricated() {
        try (final var mapper = CodecsTest.mapper()) {
            final var store = new Store();
            final var image = Map.of("type", "image", "mimeType", "image/png", "data", "aW1n");
            final var diff = Map.of("type", "diff", "path", "/a/file", "oldText", "before", "newText", "after");
            store.add(
                    "s",
                    1,
                    "/a",
                    "",
                    List.of(
                            Message.assistant(
                                    null,
                                    List.of(
                                            call("id", "edit", "{}"),
                                            call("image-id", "read", "{}"),
                                            call("array-id", "read", "{}"),
                                            call("empty-id", "read", "{}"))),
                            new Message(
                                    "tool",
                                    List.of(
                                            Map.of(
                                                    "type",
                                                    "content",
                                                    "content",
                                                    image,
                                                    "_meta",
                                                    Map.of("env", "SECRET")),
                                            diff,
                                            Map.of("type", "terminal", "terminalId", "STALE_SECRET"),
                                            Map.of(
                                                    "type",
                                                    "diff",
                                                    "path",
                                                    "relative/file",
                                                    "newText",
                                                    "INVALID_SECRET")),
                                    null,
                                    null,
                                    "id",
                                    null),
                            Message.tool("image-id", mapper.toString(image)),
                            new Message("tool", List.of("value", 42, true), null, null, "array-id", null),
                            new Message("tool", null, null, null, "empty-id", null)));
            final var events = new ArrayList<Map<String, Object>>();
            final var report = new AcpSessionHistory(store, mapper, 50).replay("s", events::add);
            assertEquals(
                    new AcpSessionHistory.ReplayReport(
                            8,
                            List.of(
                                    issue(1, AcpSessionHistory.Reason.UNSUPPORTED_CONTENT),
                                    issue(1, AcpSessionHistory.Reason.UNSUPPORTED_CONTENT))),
                    report);
            final var wire = wireEvents(mapper, events);
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessionUpdate",
                                    "tool_call_update",
                                    "toolCallId",
                                    "id",
                                    "status",
                                    "completed",
                                    "content",
                                    List.of(Map.of("type", "content", "content", image), diff),
                                    "rawOutput",
                                    List.of(image, diff))),
                    update(wire.get(4)));
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessionUpdate",
                                    "tool_call_update",
                                    "toolCallId",
                                    "image-id",
                                    "status",
                                    "completed",
                                    "content",
                                    List.of(Map.of("type", "content", "content", image)),
                                    "rawOutput",
                                    List.of(image))),
                    update(wire.get(5)));
            assertEquals(
                    raw(
                            mapper,
                            Map.of(
                                    "sessionUpdate",
                                    "tool_call_update",
                                    "toolCallId",
                                    "array-id",
                                    "status",
                                    "completed",
                                    "content",
                                    List.of(),
                                    "rawOutput",
                                    List.of("value", 42, true))),
                    update(wire.get(6)));
            assertEquals(
                    Map.of(
                            "sessionUpdate",
                            "tool_call_update",
                            "toolCallId",
                            "empty-id",
                            "status",
                            "completed",
                            "content",
                            List.of()),
                    update(wire.get(7)));
            assertFalse(mapper.toString(events).contains("SECRET"));
        }
    }

    private static ToolCall call(final String id, final String name, final String arguments) {
        return new ToolCall(id, "function", new ToolCall.FunctionCall(name, arguments), null);
    }

    private static AcpSessionHistory.ReplayIssue issue(final int index, final AcpSessionHistory.Reason reason) {
        return new AcpSessionHistory.ReplayIssue(index, reason);
    }

    private static String token(final String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> raw(final JsonMapper mapper, final Object value) {
        return (Map<String, Object>) mapper.fromString(Object.class, mapper.toString(value));
    }

    private static List<Map<String, Object>> wireEvents(
            final JsonMapper mapper, final List<Map<String, Object>> events) {
        return events.stream().map(event -> raw(mapper, event)).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> update(final Map<String, Object> params) {
        return (Map<String, Object>) params.get("update");
    }

    /** Deliberately fails every write and any unknown load, including accidental creation on load. */
    private static final class Store implements SessionStore {
        private final List<SessionInfo> infos = new ArrayList<>();
        private final Map<String, Object> projects = new HashMap<>();
        private final Map<String, Object> summaries = new HashMap<>();
        private final Map<String, List<Message>> messages = new HashMap<>();
        private final List<String> metadataIds = new ArrayList<>();
        private int loads;
        private int lists;

        private void add(
                final String id,
                final long modified,
                final Object project,
                final Object summary,
                final List<Message> history) {
            infos.add(new SessionInfo(id, modified, 1));
            projects.put(id, project);
            summaries.put(id, summary);
            messages.put(id, new ArrayList<>(history));
        }

        @Override
        public List<SessionInfo> list() {
            lists++;
            return List.copyOf(infos);
        }

        @Override
        public String projectDirOf(final String id) {
            metadataIds.add(id);
            final var value = projects.get(id);
            if (value == null || value instanceof String) {
                return (String) value;
            }
            throw new IllegalStateException("Malformed project metadata");
        }

        @Override
        public String summary(final String id) {
            return (String) summaries.get(id);
        }

        @Override
        public List<Message> load(final String id) {
            loads++;
            final var result = messages.get(id);
            assertNotNull(result, "Unknown session must not be loaded");
            return result;
        }

        @Override
        public String create() {
            return fail("Projection must not create sessions");
        }

        @Override
        public void append(final String id, final SessionEntry entry) {
            fail("Projection must not append history");
        }

        @Override
        public void replace(final String id, final List<Message> history) {
            fail("Projection must not rewrite history");
        }

        @Override
        public void delete(final String id) {
            fail("Projection must not delete sessions");
        }
    }
}
