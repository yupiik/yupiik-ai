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
package io.yupiik.ia.agent.nono;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.fusion.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded decoder and validator for the pinned nono 0.81.0 webhook contract. */
public final class ApprovalProtocol {
    private static final Pattern PROTOCOL_IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:-]+");

    public static final int MAX_BODY = 32768;
    private final JsonMapper mapper;

    public ApprovalProtocol(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    public Approval parse(final byte[] bytes) {
        if (bytes.length > MAX_BODY) {
            throw new IllegalArgumentException("Oversized request");
        }
        try {
            final var text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            // Fusion has no discriminator codec generation. This projection only selects a codec;
            // the complete envelope is then decoded by that codec, including strict extras checks.
            final var tag = mapper.fromString(TaggedEnvelope.class, text);
            final var type = identifier(tag.request().capabilityType());
            final WireEnvelope envelope =
                    switch (type) {
                        case "capability" -> mapper.fromString(FilesystemEnvelope.class, text);
                        case "network" -> mapper.fromString(NetworkEnvelope.class, text);
                        case "endpoint" -> mapper.fromString(EndpointEnvelope.class, text);
                        case "command" -> mapper.fromString(CommandEnvelope.class, text);
                        default -> throw new IllegalArgumentException("Unknown capability");
                    };
            noExtras(envelope.extras());
            final var backend = identifier(envelope.backend());
            final var request = envelope.request();
            noExtras(request.extras());
            final var id = identifier(request.requestId());
            final var session = identifier(request.sessionId());
            integer(request.childPid(), Integer.MAX_VALUE);
            optionalString(request.reason());
            switch (request) {
                case FilesystemRequest r -> {
                    string(r.path());
                    if (!Set.of("Read", "Write", "ReadWrite").contains(string(r.access()))) {
                        throw new IllegalArgumentException("Invalid access");
                    }
                }
                case NetworkRequest r -> {
                    string(r.host());
                    integer(r.port(), 65535);
                    string(r.protocol());
                    strings(r.resolvedIps());
                }
                case EndpointRequest r -> {
                    string(r.routeId());
                    string(r.upstream());
                    string(r.method());
                    string(r.path());
                    optionalString(r.ruleLabel());
                }
                case CommandRequest r -> {
                    string(r.command());
                    strings(r.args());
                    optionalString(r.caller());
                    optionalString(r.interceptRule());
                }
            }
            return new Approval(backend, id, session, type, request);
        } catch (final Exception error) {
            throw new IllegalArgumentException("Invalid approval request");
        }
    }

    private static long integer(final Object value, final long maximum) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException("Invalid integer");
        }
        final var integer = number.longValueExact();
        if (integer < 1 || integer > maximum) {
            throw new IllegalArgumentException("Integer out of range");
        }
        return integer;
    }

    private static String identifier(final String value) {
        string(value);
        if (value.length() > 256 || !PROTOCOL_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid identity");
        }
        return value;
    }

    private static String string(final String value) {
        if (value == null || value.isBlank() || value.length() > 8192) {
            throw new IllegalArgumentException("Invalid string");
        }
        return value;
    }

    private static void optionalString(final Object value) {
        if (value != null) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("Invalid string");
            }
            string(text);
        }
    }

    private static void strings(final List<String> values) {
        if (values == null || values.size() > 128 || values.stream().anyMatch(v -> v == null)) {
            throw new IllegalArgumentException("Invalid list");
        }
    }

    private static void noExtras(final Map<String, Object> extras) {
        if (extras != null && !extras.isEmpty()) {
            throw new IllegalArgumentException("Unknown field");
        }
    }

    @JsonModel
    public record TaggedEnvelope(RequestTag request) {}

    @JsonModel
    public record RequestTag(
            @JsonProperty("capability_type") String capabilityType) {}

    private sealed interface WireEnvelope {
        String backend();

        Request request();

        Map<String, Object> extras();
    }

    @JsonModel
    public record FilesystemEnvelope(
            String backend,
            FilesystemRequest request,
            @JsonOthers Map<String, Object> extras) implements WireEnvelope {}

    @JsonModel
    public record NetworkEnvelope(
            String backend,
            NetworkRequest request,
            @JsonOthers Map<String, Object> extras) implements WireEnvelope {}

    @JsonModel
    public record EndpointEnvelope(
            String backend,
            EndpointRequest request,
            @JsonOthers Map<String, Object> extras) implements WireEnvelope {}

    @JsonModel
    public record CommandEnvelope(
            String backend,
            CommandRequest request,
            @JsonOthers Map<String, Object> extras) implements WireEnvelope {}

    public sealed interface Request {
        String capabilityType();

        String requestId();

        String sessionId();

        Object reason();

        Object childPid();

        Map<String, Object> extras();
    }

    // Numeric leaves deliberately use Object: BigDecimal codecs also accept quoted numbers,
    // but this protocol requires a JSON number token before exact range validation.
    // Nullable text leaves also need token preservation: generated String fields can map
    // wrong-type tokens to null, which is indistinguishable from an allowed absent/null value.
    @JsonModel
    public record FilesystemRequest(
            @JsonProperty("capability_type") String capabilityType,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("session_id") String sessionId,
            Object reason,
            @JsonProperty("child_pid") Object childPid,
            String path,
            String access,
            @JsonOthers Map<String, Object> extras)
            implements Request {
        public FilesystemRequest {
            childPid = BigDecimal.valueOf(integer(childPid, Integer.MAX_VALUE));
        }
    }

    @JsonModel
    public record NetworkRequest(
            @JsonProperty("capability_type") String capabilityType,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("session_id") String sessionId,
            Object reason,
            @JsonProperty("child_pid") Object childPid,
            String host,
            Object port,
            String protocol,
            @JsonProperty("resolved_ips") List<String> resolvedIps,
            @JsonOthers Map<String, Object> extras)
            implements Request {
        public NetworkRequest {
            childPid = BigDecimal.valueOf(integer(childPid, Integer.MAX_VALUE));
            port = BigDecimal.valueOf(integer(port, 65535));
            resolvedIps = resolvedIps == null ? null : List.copyOf(resolvedIps);
        }
    }

    @JsonModel
    public record EndpointRequest(
            @JsonProperty("capability_type") String capabilityType,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("session_id") String sessionId,
            Object reason,
            @JsonProperty("child_pid") Object childPid,
            @JsonProperty("route_id") String routeId,
            String upstream,
            String method,
            String path,
            @JsonProperty("rule_label") Object ruleLabel,
            @JsonOthers Map<String, Object> extras)
            implements Request {
        public EndpointRequest {
            childPid = BigDecimal.valueOf(integer(childPid, Integer.MAX_VALUE));
        }
    }

    @JsonModel
    public record CommandRequest(
            @JsonProperty("capability_type") String capabilityType,
            @JsonProperty("request_id") String requestId,
            @JsonProperty("session_id") String sessionId,
            Object reason,
            @JsonProperty("child_pid") Object childPid,
            String command,
            List<String> args,
            Object caller,
            @JsonProperty("intercept_rule") Object interceptRule,
            @JsonOthers Map<String, Object> extras)
            implements Request {
        public CommandRequest {
            childPid = BigDecimal.valueOf(integer(childPid, Integer.MAX_VALUE));
            args = args == null ? null : List.copyOf(args);
        }
    }

    public record Approval(String backend, String id, String session, String type, Request request) {
        public String presentation() {
            final var text = new StringBuilder();
            switch (request) {
                case FilesystemRequest r -> {
                    text.append("Allow filesystem access?\n");
                    field(text, "Access", r.access());
                    field(text, "Path", r.path());
                    text.append("\nAn open descriptor or filesystem changes can outlast this approval.\n");
                }
                case NetworkRequest r -> {
                    text.append("Allow network connection?\n");
                    field(text, "Host", r.host());
                    field(text, "Port", integer(r.port(), 65535));
                    field(text, "Protocol", r.protocol());
                    indexed(text, "Resolved IPs", r.resolvedIps());
                    text.append("\nThe connection and transferred data can have lasting effects.\n");
                }
                case EndpointRequest r -> {
                    text.append("Allow endpoint request?\n");
                    field(text, "Route", r.routeId());
                    field(text, "Upstream", r.upstream());
                    field(text, "Method", r.method());
                    field(text, "Path", r.path());
                    field(text, "Rule", r.ruleLabel());
                    text.append("\nThe request and transferred data can have lasting effects.\n");
                }
                case CommandRequest r -> {
                    text.append("Allow command execution?\n");
                    field(text, "Command", r.command());
                    indexed(text, "Arguments", r.args());
                    field(text, "Caller", r.caller());
                    field(text, "Intercept rule", r.interceptRule());
                    text.append("\nThe process and its changes can outlast this approval.\n");
                }
            }
            text.append("\nDetails\n");
            field(text, "Session", session);
            field(text, "Process", integer(request.childPid(), Integer.MAX_VALUE));
            field(text, "Request", id);
            field(text, "Backend", backend);
            field(text, "Reason", request.reason());
            return text.toString();
        }

        private static void field(final StringBuilder text, final String label, final Object value) {
            text.append(label).append(": ");
            if (value instanceof String string) {
                quoted(text, string);
            } else {
                text.append(value == null ? "(not provided)" : value);
            }
            text.append('\n');
        }

        private static void indexed(final StringBuilder text, final String label, final List<String> values) {
            text.append(label).append(" (").append(values.size()).append("):\n");
            for (int i = 0; i < values.size(); i++) {
                field(text, "  [" + i + "]", values.get(i));
            }
        }

        // Quote each value before layout so controls cannot forge labels or be removed by UI sanitization.
        // Escaping quotes and backslashes also keeps empty, whitespace, and literal escape arguments distinct.
        private static void quoted(final StringBuilder text, final String value) {
            text.append('"');
            for (int i = 0; i < value.length(); i++) {
                final var c = value.charAt(i);
                switch (c) {
                    case '"' -> text.append("\\\"");
                    case '\\' -> text.append("\\\\");
                    case '\n' -> text.append("\\n");
                    case '\r' -> text.append("\\r");
                    case '\t' -> text.append("\\t");
                    default -> {
                        final var kind = Character.getType(c);
                        if (kind == Character.CONTROL
                                || kind == Character.FORMAT
                                || kind == Character.SURROGATE
                                || kind == Character.LINE_SEPARATOR
                                || kind == Character.PARAGRAPH_SEPARATOR) {
                            text.append(String.format("\\u%04x", (int) c));
                        } else {
                            text.append(c);
                        }
                    }
                }
            }
            text.append('"');
        }
    }
}
