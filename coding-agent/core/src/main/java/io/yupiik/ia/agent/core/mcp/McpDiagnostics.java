package io.yupiik.ia.agent.core.mcp;

import io.yupiik.ai.mcp.client.McpFailure;
import io.yupiik.ai.mcp.client.McpHttpException;
import io.yupiik.ia.agent.core.tools.ToolDiagnosticException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.regex.Pattern;

final class McpDiagnostics {
    private static final Pattern UNSAFE_SERVER_NAME = Pattern.compile("[^A-Za-z0-9_.-]");

    private McpDiagnostics() {}

    static ToolDiagnosticException forUi(final String name, final Throwable failure) {
        return forUi(name, null, List.of(), failure);
    }

    static ToolDiagnosticException forUi(
            final String name, final String server, final List<Pattern> avoidOnErrorPatterns, final Throwable failure) {
        var current = failure;
        for (var depth = 0; current != null && depth < 16; depth++, current = current.getCause()) {
            if (current instanceof McpHttpException http) {
                return new ToolDiagnosticException(
                        "MCP tool " + name + " failed",
                        "MCP HTTP " + http.statusCode()
                                + (http.retryAfterMillis() == null
                                        ? ""
                                        : "; retry after " + http.retryAfterMillis() + " ms"));
            }
            if (current instanceof HttpTimeoutException) {
                return new ToolDiagnosticException("MCP tool " + name + " failed", "MCP request timed out");
            }
            if (current instanceof McpFailure mcp) {
                final var detail = mcp.detail();
                final var avoid = mcp.toolError()
                        && detail != null
                        && avoidOnErrorPatterns.stream()
                                .anyMatch(it -> it.matcher(detail).find());
                return new ToolDiagnosticException(
                        "MCP tool " + name + " failed"
                                + (avoid
                                        ? ". MCP server '"
                                                + UNSAFE_SERVER_NAME
                                                        .matcher(server)
                                                        .replaceAll("_")
                                                + "' returned an error matching its configured avoidance policy. "
                                                + "Avoid further calls to this server or its other tools for the current task "
                                                + "unless access changes or the user requests a retry. Continue with alternatives."
                                        : ""),
                        detail == null || detail.isBlank() ? mcp.getMessage() : detail);
            }
        }
        // Unknown exceptions can contain arbitrary server data or credentials; do not forward them.
        return new ToolDiagnosticException("MCP tool " + name + " failed", "MCP call failed");
    }
}
