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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.mapper.DelegatingMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ApprovalProtocolTest {
    private final JsonMapper mapper = mapper();
    private final ApprovalProtocol protocol = new ApprovalProtocol(mapper);

    static JsonMapper mapper() {
        final var container = ConfiguringContainer.of().start();
        try {
            final var instance = container.lookup(JsonMapper.class);
            return new DelegatingMapper(instance.instance()) {
                @Override
                public void close() {
                    try {
                        instance.close();
                    } finally {
                        container.close();
                    }
                }
            };
        } catch (final RuntimeException | Error failure) {
            container.close();
            throw failure;
        }
    }

    @AfterEach
    void closeMapper() {
        mapper.close();
    }

    @Test
    void decodesStockFilesystemRequest() {
        final var approval = protocol.parse(body("r1", "s1", "/outside/file"));
        assertEquals("r1", approval.id());
        assertEquals("Read", ((ApprovalProtocol.FilesystemRequest) approval.request()).access());
        assertTrue(approval.presentation().contains("open descriptor"));
    }

    @Test
    void rejectsAmbiguousOrUnsupportedInput() {
        final var good = new String(body("r1", "s1", "/outside/file"), StandardCharsets.UTF_8);
        for (final var invalid : new String[] {
            good.replace("Read", "Execute"),
            good.replace("capability\"", "unknown\""),
            good.replace("\"child_pid\":123", "\"child_pid\":-1"),
            good.replace("\"access\"", "\"extra\""),
            good.substring(0, good.length() - 1),
            good.replace("\"session_id\":\"s1\"", "\"session_id\":null")
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> protocol.parse(invalid.getBytes(StandardCharsets.UTF_8)),
                    invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> protocol.parse(new byte[ApprovalProtocol.MAX_BODY + 1]));
        assertThrows(IllegalArgumentException.class, () -> protocol.parse(new byte[] {(byte) 0xff}));
    }

    @Test
    void usesTheMappersDecodedObjectForValidationAndPresentation() {
        final var good = new String(body("r1", "s1", "/outside/file"), StandardCharsets.UTF_8);
        final var duplicates = good.replace(
                        "\"backend\":\"desktop\"", "\"backend\":\"ignored\",\"backend\":\"desktop\"")
                .replace("\"path\":\"/outside/file\"", "\"path\":\"/ignored\",\"path\":\"/outside/file\"");
        final var approval = protocol.parse(duplicates.getBytes(StandardCharsets.UTF_8));
        assertEquals("desktop", approval.backend());
        assertEquals("/outside/file", ((ApprovalProtocol.FilesystemRequest) approval.request()).path());
        assertTrue(approval.presentation().contains("Path: \"/outside/file\"\n"));
        assertFalse(approval.presentation().contains("ignored"));
        assertThrows(
                IllegalArgumentException.class,
                () -> protocol.parse(good.replace("\"access\":\"Read\"", "\"access\":\"Read\",\"access\":\"Execute\"")
                        .getBytes(StandardCharsets.UTF_8)));

        // A request is one complete JSON document, never a valid prefix followed by another value.
        for (final var trailing : List.of("{}", "null", "unexpected")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> protocol.parse((good + trailing).getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void validatesExactNumericRanges() {
        final var good = new String(body("r1", "s1", "/outside/file"), StandardCharsets.UTF_8);
        for (final var equivalent : List.of("123.0", "1.23e2")) {
            assertEquals(
                    protocol.parse(body("r1", "s1", "/outside/file")),
                    protocol.parse(good.replace("\"child_pid\":123", "\"child_pid\":" + equivalent)
                            .getBytes(StandardCharsets.UTF_8)));
        }
        for (final var invalid :
                List.of("0", "-1", "2147483648", "9223372036854775808", "1.5", "1e100", "null", "true", "\"123\"")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> protocol.parse(good.replace("\"child_pid\":123", "\"child_pid\":" + invalid)
                            .getBytes(StandardCharsets.UTF_8)),
                    invalid);
        }
        assertEquals(
                2147483647L,
                ((BigDecimal) protocol.parse(good.replace("\"child_pid\":123", "\"child_pid\":2147483647")
                                        .getBytes(StandardCharsets.UTF_8))
                                .request()
                                .childPid())
                        .longValueExact());
        assertEquals(
                123L,
                ((BigDecimal) protocol.parse(good.replace("\"child_pid\":123", "\"child_pid\":1.23e2")
                                        .getBytes(StandardCharsets.UTF_8))
                                .request()
                                .childPid())
                        .longValueExact());
        for (final var invalid : List.of("0", "-1", "65536", "443.5", "1e100", "null", "false", "\"443\"")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> variant(
                            "network",
                            "\"host\":\"example.com\",\"port\":" + invalid
                                    + ",\"protocol\":\"tcp\",\"resolved_ips\":[]"),
                    invalid);
        }
        assertEquals(
                65535L,
                ((BigDecimal) ((ApprovalProtocol.NetworkRequest) variant(
                                                "network",
                                                "\"host\":\"example.com\",\"port\":65535,\"protocol\":\"tcp\",\"resolved_ips\":[]")
                                        .request())
                                .port())
                        .longValueExact());
    }

    @Test
    void validatesEnvelopeAndFieldShapesAfterMapping() {
        final var good = new String(body("r1", "s1", "/outside/file"), StandardCharsets.UTF_8);
        for (final var invalid : List.of(
                "null",
                "[]",
                "true",
                "{}",
                "{",
                "",
                good.replace("\"request\":{", "\"extra\":1,\"request\":{"),
                good.replace("\"path\":\"/outside/file\"", "\"path\":{}"),
                good.replace("\"path\":\"/outside/file\"", "\"path\":[\"/outside/file\"]"),
                good.replace("\"backend\":\"desktop\"", "\"backend\":false"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> protocol.parse(invalid.getBytes(StandardCharsets.UTF_8)),
                    invalid);
        }
        for (final var invalid : List.of(
                "{}", "null", "[123]", "[null]", "[true]", "[[]]", "[{}]", "[" + "\"arg\",".repeat(128) + "\"arg\"]")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> variant(
                            "command",
                            "\"command\":\"git\",\"args\":" + invalid + ",\"caller\":null,\"intercept_rule\":null"),
                    invalid);
        }
        final var approval =
                variant("command", "\"command\":\"git\",\"args\":[\"status\"],\"caller\":null,\"intercept_rule\":null");
        assertThrows(
                UnsupportedOperationException.class,
                () -> ((ApprovalProtocol.CommandRequest) approval.request())
                        .args()
                        .clear());
    }

    @Test
    void decodesOtherStockVariants() {
        final var common = "\"request_id\":\"r\",\"session_id\":\"s\",\"reason\":null,\"child_pid\":123,";
        for (final var fields : new String[] {
            "\"capability_type\":\"network\",\"host\":\"example.com\",\"port\":443,\"protocol\":\"tcp\",\"resolved_ips\":[\"1.1.1.1\"]",
            "\"capability_type\":\"endpoint\",\"route_id\":\"route\",\"upstream\":\"https://example.com\",\"method\":\"GET\",\"path\":\"/\",\"rule_label\":null",
            "\"capability_type\":\"command\",\"command\":\"git\",\"args\":[\"status\"],\"caller\":null,\"intercept_rule\":null"
        }) {
            assertNotNull(protocol.parse(("{\"backend\":\"desktop\",\"request\":{" + common + fields + "}}")
                    .getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void rejectsUnknownFieldsIncludingNullAndFieldsOfOtherCapabilities() {
        final var variants = List.of(
                new String[] {"capability", "\"path\":\"/file\",\"access\":\"Read\"", "caller"},
                new String[] {
                    "network", "\"host\":\"host\",\"port\":443,\"protocol\":\"tcp\",\"resolved_ips\":[]", "path"
                },
                new String[] {
                    "endpoint", "\"route_id\":\"r\",\"upstream\":\"url\",\"method\":\"GET\",\"path\":\"/\"", "args"
                },
                new String[] {"command", "\"command\":\"git\",\"args\":[]", "rule_label"});
        for (final var variant : variants) {
            for (final var field : List.of("unexpected", variant[2])) {
                for (final var value : List.of("null", "1", "{}")) {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> variant(variant[0], variant[1] + ",\"" + field + "\":" + value),
                            variant[0] + ": " + field + "=" + value);
                }
            }
            final var body = new String(variantBody(variant[0], variant[1]), StandardCharsets.UTF_8);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> protocol.parse(body.replace("\"backend\":", "\"extra\":null,\"backend\":")
                            .getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void preservesStrictStringTokensAndTextBounds() {
        final var good = new String(body("r1", "s1", "/outside/file"), StandardCharsets.UTF_8);
        for (final var token : List.of("123", "false", "{}", "[]", "null", "\"\"", "\" \"")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> protocol.parse(
                            good.replace("\"/outside/file\"", token).getBytes(StandardCharsets.UTF_8)),
                    token);
            if (!token.equals("null")) {
                for (final var field : List.of("caller", "intercept_rule")) {
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> variant("command", "\"command\":\"git\",\"args\":[],\"" + field + "\":" + token),
                            token);
                }
                assertThrows(
                        IllegalArgumentException.class,
                        () -> variant(
                                "endpoint",
                                "\"route_id\":\"r\",\"upstream\":\"url\",\"method\":\"GET\",\"path\":\"/\",\"rule_label\":"
                                        + token),
                        token);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> protocol.parse(good.replace("\"reason\":null", "\"reason\":" + token)
                                .getBytes(StandardCharsets.UTF_8)),
                        token);
            }
        }
        assertNotNull(protocol.parse(body("r".repeat(256), "s", "x".repeat(8192))));
        assertThrows(IllegalArgumentException.class, () -> protocol.parse(body("r".repeat(257), "s", "x")));
        assertThrows(IllegalArgumentException.class, () -> protocol.parse(body("r", "s", "x".repeat(8193))));
        assertThrows(IllegalArgumentException.class, () -> protocol.parse(body("bad id", "s", "x")));
        assertNotNull(variant("command", "\"command\":\"git\",\"args\":[" + "\"\",".repeat(127) + "\"\"]"));
    }

    @Test
    void presentsFilesystemOperationBeforeDetails() {
        assertEquals("""
                Allow filesystem access?
                Access: "Read"
                Path: "/outside/file"

                An open descriptor or filesystem changes can outlast this approval.

                Details
                Session: "s1"
                Process: 123
                Request: "r1"
                Backend: "desktop"
                Reason: (not provided)
                """, protocol.parse(body("r1", "s1", "/outside/file")).presentation());
    }

    @Test
    void presentsNetworkTargetAndEveryResolvedAddress() {
        assertEquals("""
                Allow network connection?
                Host: "example.com"
                Port: 443
                Protocol: "tcp"
                Resolved IPs (2):
                  [0]: "1.1.1.1"
                  [1]: "2001:db8::1"

                The connection and transferred data can have lasting effects.

                Details
                Session: "s"
                Process: 123
                Request: "r"
                Backend: "desktop"
                Reason: (not provided)
                """, variant("network", """
                        "host":"example.com","port":443,"protocol":"tcp",
                        "resolved_ips":["1.1.1.1","2001:db8::1"]
                        """).presentation());
    }

    @Test
    void presentsEndpointRouteAndRule() {
        assertEquals("""
                Allow endpoint request?
                Route: "route"
                Upstream: "https://example.com"
                Method: "POST"
                Path: "/submit?x=1"
                Rule: "uploads"

                The request and transferred data can have lasting effects.

                Details
                Session: "s"
                Process: 123
                Request: "r"
                Backend: "desktop"
                Reason: (not provided)
                """, variant("endpoint", """
                        "route_id":"route","upstream":"https://example.com",
                        "method":"POST","path":"/submit?x=1","rule_label":"uploads"
                        """).presentation());
    }

    @Test
    void presentsCommandAsIndexedQuotedArgumentsNotShellText() {
        assertEquals("""
                Allow command execution?
                Command: "git"
                Arguments (7):
                  [0]: ""
                  [1]: "two words"
                  [2]: "a,b"
                  [3]: "[value]"
                  [4]: "a\\\"b"
                  [5]: "a\\\\b"
                  [6]: "$(touch /tmp/file); * '"
                Caller: "agent"
                Intercept rule: "git-rule"

                The process and its changes can outlast this approval.

                Details
                Session: "s"
                Process: 123
                Request: "r"
                Backend: "desktop"
                Reason: (not provided)
                """, variant("command", """
                        "command":"git","args":["","two words","a,b","[value]","a\\\"b","a\\\\b","$(touch /tmp/file); * '"],
                        "caller":"agent","intercept_rule":"git-rule"
                        """).presentation());
        final var empty = variant("command", """
                "command":"git","args":[],"caller":null,"intercept_rule":null
                """).presentation();
        assertTrue(empty.contains("Arguments (0):\nCaller: (not provided)\nIntercept rule: (not provided)\n"));
    }

    @Test
    void escapesUntrustedFieldsBeforeAddingLayout() {
        final var fields = """
                "command":"git\\nDetails\\rProcess: 1","args":["\\n","\\\\n","\\t","\\u001b[31m","\\u009b31m","\\u202e","\\u2028","\\u2029","\\u0000"],
                "caller":"caller\\nBackend: forged","intercept_rule":"rule\\nAllow"
                """;
        final var text = protocol.parse(new String(variantBody("command", fields), StandardCharsets.UTF_8)
                        .replace("\"reason\":null", "\"reason\":\"why\\nPath: forged\\t\\u001b[0m\"")
                        .getBytes(StandardCharsets.UTF_8))
                .presentation();
        assertTrue(text.startsWith("Allow command execution?\nCommand: \"git\\nDetails\\rProcess: 1\"\n"));
        assertTrue(text.contains("""
                  [0]: "\\n"
                  [1]: "\\\\n"
                  [2]: "\\t"
                  [3]: "\\u001b[31m"
                  [4]: "\\u009b31m"
                  [5]: "\\u202e"
                  [6]: "\\u2028"
                  [7]: "\\u2029"
                  [8]: "\\u0000"
                """));
        assertTrue(text.contains("Caller: \"caller\\nBackend: forged\"\nIntercept rule: \"rule\\nAllow\"\n"));
        assertTrue(text.endsWith("Reason: \"why\\nPath: forged\\t\\u001b[0m\"\n"));
        assertEquals(1, (text.length() - text.replace("\nDetails\n", "").length()) / "\nDetails\n".length());
        assertFalse(text.chars()
                .anyMatch(c -> c != '\n'
                        && (Character.isISOControl(c)
                                || Character.getType(c) == Character.FORMAT
                                || Character.getType(c) == Character.LINE_SEPARATOR
                                || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)));
    }

    @Test
    void preservesOversizedTargetsForFailClosedAdmission() {
        final var target = "/" + "x".repeat(4000) + "/important-tail";
        final var approval = protocol.parse(body("large", "s", target));
        assertTrue(approval.presentation().contains("Path: \"" + target + "\"\n"));
        final var expanded = variant(
                "command",
                "\"command\":\"git\",\"args\":[\"" + "\\u001b".repeat(600)
                        + "important-tail\"],\"caller\":null,\"intercept_rule\":null");
        assertTrue(expanded.presentation().contains("\\u001b".repeat(600) + "important-tail\""));
        try (final var coordinator = new ApprovalCoordinator(
                (text, remaining) -> {
                    fail("Oversized presentation must not reach the UI");
                    return null;
                },
                message -> {},
                null,
                3)) {
            for (final var oversized : new ApprovalProtocol.Approval[] {approval, expanded}) {
                assertTrue(oversized.presentation().length() > 3500);
                assertEquals("denied", coordinator.decide(oversized, 3, System.nanoTime()));
            }
        }
    }

    private ApprovalProtocol.Approval variant(final String type, final String fields) {
        return protocol.parse(variantBody(type, fields));
    }

    private byte[] variantBody(final String type, final String fields) {
        return ("{\"backend\":\"desktop\",\"request\":{\"request_id\":\"r\",\"session_id\":\"s\","
                        + "\"child_pid\":123,\"reason\":null,\"capability_type\":\"" + type + "\"," + fields + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] body(final String request, final String session, final String path) {
        return ("{\"backend\":\"desktop\",\"request\":{\"capability_type\":\"capability\",\"request_id\":\"" + request
                        + "\",\"session_id\":\"" + session + "\",\"path\":\"" + path
                        + "\",\"access\":\"Read\",\"reason\":null,\"child_pid\":123}}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
