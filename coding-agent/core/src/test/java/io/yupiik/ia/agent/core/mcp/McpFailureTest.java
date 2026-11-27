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
package io.yupiik.ia.agent.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ai.mcp.client.McpFailure;
import io.yupiik.ai.mcp.client.McpHttpException;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpFailureTest {
    @Test
    void configuredCredentialsAreLiteralNotRegexesOrReplacementTemplates() {
        final var credential = "a.$1\\key[0]";
        final var failure =
                new McpFailure("failed", "Quota exceeded for " + credential).sanitized(credential, List.of(), null);
        assertEquals("Quota exceeded for [redacted]", failure.detail());
    }

    @Test
    void removesCredentialsAndTerminalControlsBeforeBoundingDetail() {
        final var failure = new McpFailure(
                        "MCP tool returned an error",
                        "Quota: se\u001b[31mcret\u001b[0m env-value session-value bearer-value\n"
                                + "password=hunter2 apiKey=unconfigured Authorization: Bearer private-token "
                                + "https://user:pass@example.test/?token=query-token\u0000\u202e\n" + "x".repeat(9000))
                .sanitized("secret", List.of("TOKEN=env-value", "Authorization=Bearer bearer-value"), "session-value");
        assertTrue(
                failure.detail().startsWith("Quota: [redacted] [redacted] [redacted] [redacted]\n"), failure.detail());
        for (final var credential : List.of(
                "secret",
                "env-value",
                "session-value",
                "bearer-value",
                "o5fWBRP",
                "eYbhRYLo30sJ",
                "private-token",
                "user:pass",
                "query-token",
                "\u001b",
                "\u0000",
                "\u202e")) {
            assertFalse(failure.detail().contains(credential), credential);
        }
        assertTrue(failure.detail().endsWith("[diagnostic truncated]"));
        assertTrue(failure.detail().length() < 8250);
        assertEquals("MCP tool returned an error", failure.getMessage());
    }

    @Test
    void redactsCompleteCredentialBeforeTruncating() {
        final var failure = new McpFailure("failed", "x".repeat(8190) + "credential-at-boundary")
                .sanitized("credential-at-boundary", List.of(), null);
        assertFalse(failure.detail().contains("credential"));
    }

    @Test
    void httpUiDetailContainsStatusAndRetryHintButNotBody() {
        final var failure = McpDiagnostics.forUi("test", new McpHttpException("test", 429, "secret-body", 2000L));
        assertEquals("MCP tool test failed", failure.getMessage());
        assertEquals("MCP HTTP 429; retry after 2000 ms", failure.detail());
        assertFalse(failure.toString().contains("secret-body"));
    }
}
