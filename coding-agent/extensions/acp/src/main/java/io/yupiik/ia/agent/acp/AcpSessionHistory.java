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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.FeedbackTiming;
import io.yupiik.ia.agent.core.context.CompactionCoordinator;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SubagentDeliveryView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Read-only stable ACP v1 projection of stored sessions, including sessions from other frontends.
 * Owns neither session lifecycle nor pagination state. The caller serializes list results and sends
 * each replay consumer argument as the params of a session/update notification before replying to load.
 */
public final class AcpSessionHistory {
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int MAX_CURSOR_LENGTH = 1024;
    private static final Comparator<SessionStore.SessionInfo> ORDER = Comparator.comparingLong(
                    SessionStore.SessionInfo::modifiedAt)
            .reversed()
            .thenComparing(SessionStore.SessionInfo::id);

    private final SessionStore store;
    private final JsonMapper mapper;
    private final int pageSize;

    /** A page size of 50 is recommended; the supported range is 1 through 100. */
    public AcpSessionHistory(final SessionStore store, final JsonMapper mapper, final int pageSize) {
        this.store = Objects.requireNonNull(store);
        this.mapper = Objects.requireNonNull(mapper);
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Session page size must be between 1 and 100");
        }
        this.pageSize = pageSize;
    }

    /**
     * Lists newest first, breaking timestamp ties by ID. Paths are normalized lexically, not resolved
     * against the process cwd or the filesystem. Missing/invalid project markers are ineligible.
     * Cursors are bounded, stateless, query-bound keyset anchors, not snapshot promises or credentials.
     * A removed/modified/ineligible anchor invalidates its cursor; clients can restart from page one.
     */
    public Page list(final String cwd, final String cursor) {
        final var filter = cwd == null ? null : absolutePath(cwd);
        final var scope = digest(mapper.toString(Map.of("cwd", filter == null ? "*" : filter, "pageSize", pageSize)));
        final var anchor = cursor == null ? null : decodeCursor(cursor, scope);
        final var known = new HashMap<String, SessionStore.SessionInfo>();
        for (final var info : store.list()) {
            if (info != null && safeSessionId(info.id())) {
                // File stores can list both a legacy file and a session folder for the same ID.
                known.merge(info.id(), info, (left, right) -> ORDER.compare(left, right) <= 0 ? left : right);
            }
        }
        final var directories = new HashMap<String, String>();
        final var eligible = new ArrayList<SessionStore.SessionInfo>();
        for (final var info : known.values()) {
            final String dir;
            try {
                dir = absolutePath(store.projectDirOf(info.id()));
            } catch (final RuntimeException malformedMetadata) {
                // In particular, never use Path.toAbsolutePath() as a fallback for missing markers.
                continue;
            }
            if (filter == null || filter.equals(dir)) {
                eligible.add(info);
                directories.put(info.id(), dir);
            }
        }
        eligible.sort(ORDER);
        var start = 0;
        if (anchor != null) {
            var found = false;
            for (var i = 0; i < eligible.size(); i++) {
                final var info = eligible.get(i);
                if (anchor.id().equals(info.id()) && anchor.modifiedAt() == info.modifiedAt()) {
                    start = i + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw invalidCursor();
            }
        }
        final var end = Math.min(eligible.size(), start + pageSize);
        final var sessions = new ArrayList<SessionInfo>(end - start);
        for (var i = start; i < end; i++) {
            final var info = eligible.get(i);
            String title = null;
            try {
                title = safeTitle(store.summary(info.id()));
            } catch (final RuntimeException malformedMetadata) {
                // An unreadable summary does not invalidate an otherwise eligible session.
            }
            sessions.add(new SessionInfo(
                    info.id(),
                    directories.get(info.id()),
                    title,
                    info.modifiedAt() > 0
                            ? Instant.ofEpochMilli(info.modifiedAt()).toString()
                            : null));
        }
        return new Page(
                List.copyOf(sessions), end < eligible.size() ? encodeCursor(eligible.get(end - 1), scope) : null);
    }

    /** Validates before any store access, including for idempotent delete/close handlers. */
    public static String validateSessionId(final String id) {
        if (!safeSessionId(id)) {
            throw new IllegalArgumentException("Invalid session ID");
        }
        return id;
    }

    /** Unlike load().isEmpty(), this recognizes legitimate, known empty sessions. */
    public boolean exists(final String id) {
        validateSessionId(id);
        return store.list().stream().anyMatch(info -> info != null && id.equals(info.id()));
    }

    /** UnknownSessionException maps to ACP -32002; other invalid arguments map to -32602. */
    public SessionStore.SessionInfo requireKnown(final String id) {
        validateSessionId(id);
        return store.list().stream()
                .filter(info -> info != null && id.equals(info.id()))
                .min(ORDER)
                .orElseThrow(UnknownSessionException::new);
    }

    /**
     * Replays the full available store snapshot in order, never creating or marking a session.
     * Message IDs identify positions in this exact stored snapshot, NOT identities across mutations
     * (including append/compaction). Blocks of one message/channel share an ID.
     *
     * Internal digest messages are omitted and reported even if the client supports compaction:
     * Message has neither a durable compaction ID/lifecycle nor proof its summary is user-displayable.
     * The server must not turn these omissions into invented events or expose hidden digest framing.
     * Live-only notices, system/developer instructions and arbitrary message extensions are not replayed.
     */
    public ReplayReport replay(final String id, final Consumer<Map<String, Object>> updates) {
        Objects.requireNonNull(updates);
        requireKnown(id);
        final var messages = new ArrayList<>(store.load(id));
        final var snapshot = digest(id + "\n" + mapper.toString(messages));
        final var issues = new ArrayList<ReplayIssue>();
        final var toolIds = new HashSet<String>();
        var emitted = 0;
        for (var index = 0; index < messages.size(); index++) {
            final var message = messages.get(index);
            if (message == null) {
                issues.add(new ReplayIssue(index, Reason.UNSUPPORTED_CONTENT));
                continue;
            }
            if ("system".equals(message.role())
                    || "developer".equals(message.role())
                    || "notice".equals(message.role())
                    || "service".equals(message.role())) {
                continue;
            }
            if (CompactionCoordinator.isDigestMessage(message) || isPrunedMessage(message)) {
                issues.add(new ReplayIssue(index, Reason.COMPACTION_UNAVAILABLE));
                continue;
            }
            if ("user".equals(message.role())
                    && message.content() instanceof String text
                    && SubagentDeliveryView.parse(text) != null) {
                // This is an internal service delivery, not a user turn. No persisted ACP
                // parent-tool relationship exists from which to reconstruct a subagent update.
                issues.add(new ReplayIssue(index, Reason.SERVICE_MESSAGE_UNAVAILABLE));
                continue;
            }
            if ("user".equals(message.role()) || "assistant".equals(message.role())) {
                for (final var chunk : messageChunks(message, "history-" + snapshot + "-" + index, index, issues)) {
                    emit(id, chunk, updates);
                    emitted++;
                }
                if ("assistant".equals(message.role()) && message.toolCalls() != null) {
                    for (final var call : message.toolCalls()) {
                        if (call == null
                                || !validToolId(call.id())
                                || call.function() == null
                                || call.function().name() == null
                                || call.function().name().isBlank()
                                || (call.type() != null && !"function".equals(call.type()))) {
                            issues.add(new ReplayIssue(index, Reason.INVALID_TOOL_CALL));
                            continue;
                        }
                        if (!toolIds.add(call.id())) {
                            issues.add(new ReplayIssue(index, Reason.DUPLICATE_TOOL_CALL_ID));
                            continue;
                        }
                        final var update = new LinkedHashMap<String, Object>();
                        update.put("sessionUpdate", "tool_call");
                        update.put("toolCallId", call.id());
                        update.put("title", titleOr(call.function().name(), "Tool"));
                        update.put("name", call.function().name());
                        update.put("kind", "other");
                        update.put("status", "pending");
                        final var arguments = call.function().arguments();
                        if (arguments != null) {
                            update.put("rawInput", jsonOrString(arguments));
                        }
                        emit(id, update, updates);
                        emitted++;
                    }
                }
            } else if ("tool".equals(message.role())) {
                if (!validToolId(message.toolCallId())) {
                    issues.add(new ReplayIssue(index, Reason.INVALID_TOOL_CALL));
                    continue;
                }
                final var output = toolOutput(message.content(), index, issues);
                final var update = new LinkedHashMap<String, Object>();
                final var orphan = toolIds.add(message.toolCallId());
                update.put("sessionUpdate", orphan ? "tool_call" : "tool_call_update");
                update.put("toolCallId", message.toolCallId());
                if (orphan) {
                    // Project the actual result directly as an initial terminal-state call; do not
                    // invent an earlier invocation, input, execution progress or a replacement ID.
                    issues.add(new ReplayIssue(index, Reason.ORPHAN_TOOL_RESULT));
                    update.put("title", titleOr(message.name(), "Tool result"));
                    if (message.name() != null && !message.name().isBlank()) {
                        update.put("name", message.name());
                    }
                }
                final var failed = output.error()
                        || (message.others() != null
                                && Boolean.TRUE.equals(message.others().get("is_error")));
                update.put("status", failed ? "failed" : "completed");
                update.put("content", output.content());
                if (output.rawOutput() != null) {
                    update.put("rawOutput", output.rawOutput());
                }
                emit(id, update, updates);
                emitted++;
            } else {
                issues.add(new ReplayIssue(index, Reason.UNSUPPORTED_ROLE));
            }
        }
        return new ReplayReport(emitted, List.copyOf(issues));
    }

    private List<Map<String, Object>> messageChunks(
            final Message message, final String messageId, final int index, final List<ReplayIssue> issues) {
        final var out = new ArrayList<Map<String, Object>>();
        final var assistant = "assistant".equals(message.role());
        final var rawThought =
                assistant && message.others() != null ? message.others().get("thinking") : null;
        final var thought = rawThought instanceof String text ? text : null;
        if (rawThought != null && thought == null) {
            issues.add(new ReplayIssue(index, Reason.UNSUPPORTED_CONTENT));
        }
        if (assistant && message.content() instanceof String text && !text.isBlank()) {
            // These are displayable streamed thoughts persisted by AgentLoop, not raw provider
            // reasoning items/encrypted reasoning or arbitrary extensions. Reuse core's validated
            // offsets to retain interleaved text/thought order; never send the timing metadata.
            for (final var segment : FeedbackTiming.segments(message, text, thought)) {
                final var thinking = "thinking".equals(segment.kind());
                out.add(chunk(
                        thinking ? "agent_thought_chunk" : "agent_message_chunk",
                        messageId + (thinking ? "-thought" : ""),
                        Map.of("type", "text", "text", segment.text())));
            }
            return out;
        }
        if (thought != null && !thought.isBlank()) {
            out.add(chunk("agent_thought_chunk", messageId + "-thought", Map.of("type", "text", "text", thought)));
        }
        for (final var block : content(message.content(), index, issues)) {
            out.add(chunk(assistant ? "agent_message_chunk" : "user_message_chunk", messageId, block));
        }
        return out;
    }

    private static Map<String, Object> chunk(
            final String variant, final String messageId, final Map<String, Object> block) {
        return Map.of("sessionUpdate", variant, "messageId", messageId, "content", block);
    }

    private Output toolOutput(final Object value, final int index, final List<ReplayIssue> issues) {
        if (value == null) {
            return new Output(List.of(), null, false);
        }
        final var decoded = value instanceof String text ? jsonOrString(text) : value;
        if (decoded instanceof Map<?, ?> result && result.containsKey("content") && !result.containsKey("type")) {
            // MCP result envelopes can be persisted as JSON strings. Preserve media, but not MCP _meta.
            final var contents = toolContent(result.get("content"), index, issues);
            final var raw = new LinkedHashMap<String, Object>();
            raw.put("content", unwrapped(contents));
            if (result.get("structuredContent") instanceof Map<?, ?> structured) {
                raw.put("structuredContent", structured);
            }
            final var error = Boolean.TRUE.equals(result.get("isError"));
            raw.put("isError", error);
            return new Output(contents, raw, error);
        }
        if (value instanceof Map<?, ?> map && !map.containsKey("type")) {
            final var raw = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                if (key instanceof String text && !"_meta".equals(text)) {
                    raw.put(text, item);
                }
            });
            return new Output(List.of(), raw, false);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return new Output(List.of(), value, false);
        }
        if (value instanceof List<?> list
                && !list.stream().allMatch(item -> item instanceof Map<?, ?> map && map.containsKey("type"))) {
            return new Output(List.of(), value, false);
        }
        final var structured = decoded instanceof Map<?, ?> decodedMap && decodedMap.containsKey("type")
                || decoded instanceof List<?> list
                        && list.stream().allMatch(item -> item instanceof Map<?, ?> part && part.containsKey("type"));
        final var contents = toolContent(structured ? decoded : value, index, issues);
        // String output is kept verbatim; structured blocks use only their validated wire fields.
        return new Output(contents, !structured && value instanceof String ? value : unwrapped(contents), false);
    }

    private List<Map<String, Object>> toolContent(final Object value, final int index, final List<ReplayIssue> issues) {
        if (value == null || value instanceof String) {
            return content(value, index, issues).stream()
                    .<Map<String, Object>>map(block -> Map.of("type", "content", "content", block))
                    .toList();
        }
        final var values = value instanceof List<?> list ? list : List.of(value);
        final var out = new ArrayList<Map<String, Object>>();
        for (final var item : values) {
            if (item instanceof Map<?, ?> map && "diff".equals(map.get("type"))) {
                if (!(map.get("path") instanceof String path)
                        || !(map.get("newText") instanceof String)
                        || (map.get("oldText") != null && !(map.get("oldText") instanceof String))) {
                    issues.add(new ReplayIssue(index, Reason.UNSUPPORTED_CONTENT));
                    continue;
                }
                try {
                    absolutePath(path); // Validate, but preserve the stored display path verbatim.
                } catch (final IllegalArgumentException invalidPath) {
                    issues.add(new ReplayIssue(index, Reason.UNSUPPORTED_CONTENT));
                    continue;
                }
                final var diff = new LinkedHashMap<String, Object>();
                diff.put("type", "diff");
                diff.put("path", path);
                diff.put("newText", map.get("newText"));
                if (map.containsKey("oldText")) {
                    diff.put("oldText", map.get("oldText"));
                }
                out.add(diff);
            } else {
                final var source =
                        item instanceof Map<?, ?> map && "content".equals(map.get("type")) ? map.get("content") : item;
                final var projected = source instanceof Map<?, ?> map ? block(map) : null;
                if (projected == null) {
                    // In particular, a stored client-terminal ID cannot recreate a terminal after
                    // reconnect. Never send a dangling live terminal reference or invent its bytes.
                    issues.add(new ReplayIssue(index, Reason.UNSUPPORTED_CONTENT));
                } else {
                    out.add(Map.of("type", "content", "content", projected));
                }
            }
        }
        return out;
    }

    private static List<Object> unwrapped(final List<Map<String, Object>> contents) {
        return contents.stream()
                .map(item -> "content".equals(item.get("type")) ? item.get("content") : item)
                .toList();
    }

    private List<Map<String, Object>> content(final Object value, final int index, final List<ReplayIssue> issues) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String text) {
            return List.of(Map.of("type", "text", "text", text));
        }
        final var values = value instanceof List<?> list ? list : List.of(value);
        final var blocks = new ArrayList<Map<String, Object>>();
        for (final var item : values) {
            final var block = item instanceof Map<?, ?> map ? block(map) : null;
            if (block == null) {
                issues.add(new ReplayIssue(index, Reason.UNSUPPORTED_CONTENT));
            } else {
                blocks.add(block);
            }
        }
        return blocks;
    }

    private Map<String, Object> block(final Map<?, ?> source) {
        if (!(source.get("type") instanceof String type)) {
            return null;
        }
        final var out = new LinkedHashMap<String, Object>();
        switch (type) {
            case "text", "input_text", "output_text" -> {
                if (!(source.get("text") instanceof String text)) {
                    return null;
                }
                out.put("type", "text");
                out.put("text", text);
            }
            case "image", "audio" -> {
                if (!(source.get("data") instanceof String data) || !nonBlank(source.get("mimeType"))) {
                    return null;
                }
                out.put("type", type);
                out.put("data", data);
                out.put("mimeType", source.get("mimeType"));
                if ("image".equals(type)) {
                    copyStrings(source, out, "uri");
                }
            }
            case "resource_link" -> {
                if (!nonBlank(source.get("uri")) || !nonBlank(source.get("name"))) {
                    return null;
                }
                out.put("type", type);
                out.put("uri", source.get("uri"));
                out.put("name", source.get("name"));
                copyStrings(source, out, "title", "description", "mimeType");
                if (source.get("size") instanceof Number number) {
                    try {
                        final var size = new BigDecimal(number.toString()).longValueExact();
                        if (size >= 0) {
                            out.put("size", size);
                        }
                    } catch (final ArithmeticException | NumberFormatException invalidSize) {
                        // Malformed optional metadata is not promoted to the wire.
                    }
                }
            }
            case "resource" -> {
                if (!(source.get("resource") instanceof Map<?, ?> resource) || !nonBlank(resource.get("uri"))) {
                    return null;
                }
                final var text = resource.get("text") instanceof String;
                final var blob = resource.get("blob") instanceof String;
                if (text == blob) {
                    return null;
                }
                final var body = new LinkedHashMap<String, Object>();
                body.put("uri", resource.get("uri"));
                copyStrings(resource, body, text ? "text" : "blob", "mimeType");
                out.put("type", type);
                out.put("resource", body);
            }
            case "image_url" -> {
                final var image = source.get("image_url");
                final var url = image instanceof Map<?, ?> map ? map.get("url") : image;
                if (!(url instanceof String uri) || uri.isBlank()) {
                    return null;
                }
                final var comma = uri.indexOf(',');
                if (uri.startsWith("data:image/")
                        && comma > 0
                        && uri.substring(0, comma).endsWith(";base64")) {
                    out.put("type", "image");
                    out.put("mimeType", uri.substring("data:".length(), comma - ";base64".length()));
                    out.put("data", uri.substring(comma + 1));
                } else if (!uri.startsWith("data:")) {
                    // ACP image requires bytes. A remote reference can be preserved without fetching.
                    out.put("type", "resource_link");
                    out.put("uri", uri);
                    out.put("name", "Image");
                } else {
                    return null;
                }
            }
            case "input_audio" -> {
                if (!(source.get("input_audio") instanceof Map<?, ?> audio)
                        || !(audio.get("data") instanceof String data)
                        || !(audio.get("format") instanceof String format)) {
                    return null;
                }
                final var mime =
                        switch (format) {
                            case "wav" -> "audio/wav";
                            case "mp3" -> "audio/mpeg";
                            default -> null;
                        };
                if (mime == null) {
                    return null;
                }
                out.put("type", "audio");
                out.put("mimeType", mime);
                out.put("data", data);
            }
            default -> {
                return null;
            }
        }
        if (source.get("annotations") instanceof Map<?, ?> annotations) {
            final var safe = new LinkedHashMap<String, Object>();
            if (annotations.get("audience") instanceof List<?> audience
                    && audience.stream().allMatch(role -> "user".equals(role) || "assistant".equals(role))) {
                safe.put("audience", audience);
            }
            if (annotations.get("priority") instanceof Number priority
                    && Double.isFinite(priority.doubleValue())
                    && priority.doubleValue() >= 0
                    && priority.doubleValue() <= 1) {
                safe.put("priority", priority);
            }
            if (annotations.get("lastModified") instanceof String modified) {
                try {
                    Instant.parse(modified);
                    safe.put("lastModified", modified);
                } catch (final DateTimeParseException invalidDate) {
                    // Drop invalid optional annotations, not the content itself.
                }
            }
            if (!safe.isEmpty()) {
                out.put("annotations", safe);
            }
        }
        return out;
    }

    private Object jsonOrString(final String value) {
        try {
            return mapper.fromString(Object.class, value);
        } catch (final RuntimeException notJson) {
            return value;
        }
    }

    private void emit(final String id, final Map<String, Object> update, final Consumer<Map<String, Object>> consumer) {
        consumer.accept(Map.of("sessionId", id, "update", update));
    }

    private static void copyStrings(final Map<?, ?> source, final Map<String, Object> target, final String... keys) {
        for (final var key : keys) {
            if (source.get(key) instanceof String value) {
                target.put(key, value);
            }
        }
    }

    private static boolean nonBlank(final Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static boolean validToolId(final String id) {
        return id != null
                && !id.isBlank()
                && id.length() <= 512
                && id.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean safeSessionId(final String id) {
        return id != null
                && !id.isEmpty()
                && id.length() <= 128
                && SESSION_ID.matcher(id).matches();
    }

    private static String absolutePath(final String value) {
        if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Session cwd must be an absolute path");
        }
        final var path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Session cwd must be an absolute path");
        }
        return path.normalize().toString();
    }

    private static boolean isPrunedMessage(final Message message) {
        return "user".equals(message.role())
                && message.content() instanceof String text
                && text.startsWith("<pruned-context>")
                && text.stripTrailing().endsWith("</pruned-context>");
    }

    private static String titleOr(final String value, final String fallback) {
        final var title = safeTitle(value);
        return title == null ? fallback : title;
    }

    private static String safeTitle(final String value) {
        if (value == null
                || value.isBlank()
                || value.startsWith("<compacted-context>")
                || value.startsWith("<pruned-context>")) {
            return null;
        }
        final var out = new StringBuilder();
        var count = 0;
        var space = false;
        for (var offset = 0; offset < value.length() && count < 120; ) {
            final var cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == 0x2028 || cp == 0x2029) {
                break;
            }
            if (Character.isWhitespace(cp)) {
                space = !out.isEmpty();
            } else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT) {
                if (space && count < 119) {
                    out.append(' ');
                    count++;
                }
                out.appendCodePoint(cp);
                count++;
                space = false;
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private String encodeCursor(final SessionStore.SessionInfo info, final String scope) {
        final var json = mapper.toString(
                Map.of("v", "1", "scope", scope, "id", info.id(), "at", Long.toString(info.modifiedAt())));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private Anchor decodeCursor(final String value, final String scope) {
        if (value.isEmpty()
                || value.length() > MAX_CURSOR_LENGTH
                || !CURSOR.matcher(value).matches()) {
            throw invalidCursor();
        }
        try {
            final var bytes = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) {
                throw invalidCursor();
            }
            final var decoded = mapper.fromString(Object.class, new String(bytes, StandardCharsets.UTF_8));
            if (!(decoded instanceof Map<?, ?> map)
                    || !map.keySet().equals(Set.of("v", "scope", "id", "at"))
                    || !"1".equals(map.get("v"))
                    || !scope.equals(map.get("scope"))
                    || !(map.get("id") instanceof String id)
                    || !safeSessionId(id)
                    || !(map.get("at") instanceof String at)) {
                throw invalidCursor();
            }
            final var modifiedAt = Long.parseLong(at);
            if (!Long.toString(modifiedAt).equals(at)) {
                throw invalidCursor();
            }
            return new Anchor(id, modifiedAt);
        } catch (final RuntimeException malformed) {
            // Never echo cursor bytes, project paths or stored metadata in protocol errors.
            throw invalidCursor();
        }
    }

    private static IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("Invalid session cursor; restart session listing");
    }

    private static String digest(final String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    @JsonModel
    public record Page(List<SessionInfo> sessions, String nextCursor) {}

    @JsonModel
    public record SessionInfo(String sessionId, String cwd, String title, String updatedAt) {}

    public record ReplayReport(int emittedUpdates, List<ReplayIssue> issues) {}

    /** Indices refer to the loaded stored-message snapshot; no stored content is included. */
    public record ReplayIssue(int messageIndex, Reason reason) {}

    public enum Reason {
        COMPACTION_UNAVAILABLE,
        SERVICE_MESSAGE_UNAVAILABLE,
        UNSUPPORTED_CONTENT,
        UNSUPPORTED_ROLE,
        INVALID_TOOL_CALL,
        DUPLICATE_TOOL_CALL_ID,
        ORPHAN_TOOL_RESULT
    }

    public static final class UnknownSessionException extends IllegalArgumentException {
        public UnknownSessionException() {
            super("Unknown session");
        }
    }

    private record Anchor(String id, long modifiedAt) {}

    private record Output(List<Map<String, Object>> content, Object rawOutput, boolean error) {}
}
