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
package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.tools.FileMutation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** File content is live-only; missing or redacted sides must never become an invented diff. */
@ExtendWith(TestWebJsonMapper.class)
class WebFileMutationTransportTest {
    @Test
    void liveFilesReachOnlyTheirSessionWithoutChangingTheTranscript(@TempDir final Path root, final JsonMapper mapper) {
        final var files = List.of(
                new FileMutation(
                        root.resolve("created.txt"),
                        FileMutation.Kind.CREATE,
                        new FileMutation.Content(null, "\ufeffcreated\r\n")),
                new FileMutation(
                        root.resolve("updated.txt"),
                        FileMutation.Kind.UPDATE,
                        new FileMutation.Content("before\n", "after\n")),
                new FileMutation(
                        root.resolve("deleted.txt"),
                        FileMutation.Kind.DELETE,
                        new FileMutation.Content("deleted\n", "")));
        final var event = event(root, files);
        final var broker = new SseBroker(mapper, policy());
        final var captured = new ArrayList<byte[]>();
        final var other = new ArrayList<byte[]>();
        broker.subscribe("session", captured::add);
        broker.subscribe("other", other::add);
        final var transcript = new ConversationTranscript();
        transcript.appendTranscript("existing conversation");
        final var before = transcript.markdown("", "");
        new WebShellFrontend("session", broker, transcript, mapper).onEvent(event);

        assertEquals(1, captured.size());
        assertTrue(other.isEmpty());
        assertEquals(before, transcript.markdown("", ""));
        final var dto = decode(mapper, captured.getFirst());
        assertEquals("tool-files-changed", dto.type());
        assertEquals("session", dto.sessionId());
        assertEquals("child", dto.subagentId());
        assertEquals("Worker", dto.subagentName());
        assertEquals("edit_file", dto.message());
        assertEquals(7, ((Number) dto.data().get("ordinal")).intValue());
        final var projected = files(dto);
        assertEquals(3, projected.size());
        for (var i = 0; i < files.size(); i++) {
            final var original = files.get(i);
            final var actual = (Map<?, ?>) projected.get(i);
            assertEquals(original.path().toString(), actual.get("path"));
            assertEquals(original.content().oldText(), actual.get("oldText"));
            assertEquals(original.content().newText(), actual.get("newText"));
            assertTrue(actual.containsKey("oldText"));
        }
        assertEquals("create", ((Map<?, ?>) projected.get(0)).get("kind"));
        assertEquals("update", ((Map<?, ?>) projected.get(1)).get("kind"));
        assertEquals("delete", ((Map<?, ?>) projected.get(2)).get("kind"));
        assertEquals(files, event.files(), "projection must not alter the source event");
    }

    @Test
    void eitherRedactedSideSuppressesBothSides(@TempDir final Path root, final JsonMapper mapper) {
        final var originals = List.of(
                new FileMutation(
                        root.resolve("old.txt"),
                        FileMutation.Kind.UPDATE,
                        new FileMutation.Content("secret before", "safe after")),
                new FileMutation(
                        root.resolve("new.txt"),
                        FileMutation.Kind.UPDATE,
                        new FileMutation.Content("safe before", "secret after")),
                new FileMutation(
                        root.resolve("create.txt"),
                        FileMutation.Kind.CREATE,
                        new FileMutation.Content(null, "secret created")),
                new FileMutation(
                        root.resolve("delete.txt"),
                        FileMutation.Kind.DELETE,
                        new FileMutation.Content("secret deleted", "")));
        final var captured = new ArrayList<byte[]>();
        final var broker = new SseBroker(mapper, policy());
        broker.subscribe("session", captured::add);
        broker.pushAgentEvent("session", event(root, originals));
        final var frame = new String(captured.getFirst(), StandardCharsets.UTF_8);
        assertFalse(frame.contains("secret"));
        assertFalse(frame.contains("safe before"));
        assertFalse(frame.contains("safe after"));
        for (final var file : files(decode(mapper, captured.getFirst()))) {
            assertMetadataOnly((Map<?, ?>) file);
        }
        assertEquals("secret before", originals.getFirst().content().oldText());
        assertEquals("secret after", originals.get(1).content().newText());
    }

    @Test
    void missingContentStaysMetadataOnlyAndPathsUseSessionPolicy(@TempDir final Path root, final JsonMapper mapper) {
        final var mutation = new FileMutation(root.resolve("secret.txt"), FileMutation.Kind.UPDATE, null);
        final var captured = new ArrayList<byte[]>();
        final var broker = new SseBroker(mapper, policy());
        broker.subscribe("session", captured::add);
        broker.pushAgentEvent("session", event(root, List.of(mutation)));
        final var projected =
                (Map<?, ?>) files(decode(mapper, captured.getFirst())).getFirst();
        assertEquals(root.resolve("masked.txt").toString(), projected.get("path"));
        assertEquals("update", projected.get("kind"));
        assertMetadataOnly(projected);
        assertNull(mutation.content());
        assertEquals(root.resolve("secret.txt"), mutation.path());
    }

    private SecretRedactor policy() {
        return new SecretRedactor(null, null) {
            @Override
            public String redact(final String text, final String sessionId) {
                assertEquals("session", sessionId);
                return text.replace("secret", "masked");
            }
        };
    }

    private AgentEvent.ToolFilesChanged event(final Path root, final List<FileMutation> files) {
        return new AgentEvent.ToolFilesChanged("edit_file", files, 7, "session", "child", "Worker", root);
    }

    private WebEventDto decode(final JsonMapper mapper, final byte[] frame) {
        final var text = new String(frame, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("event: agent\ndata: "));
        return mapper.fromString(
                WebEventDto.class,
                text.substring("event: agent\ndata: ".length()).stripTrailing());
    }

    private List<?> files(final WebEventDto dto) {
        return (List<?>) dto.data().get("files");
    }

    private void assertMetadataOnly(final Map<?, ?> file) {
        assertEquals(2, file.size());
        assertFalse(file.containsKey("oldText"));
        assertFalse(file.containsKey("newText"));
    }
}
