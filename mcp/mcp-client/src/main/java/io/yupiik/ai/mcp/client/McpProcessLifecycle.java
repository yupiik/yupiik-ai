package io.yupiik.ai.mcp.client;
/** Optional process accounting policy. The client still owns termination, including descendants.
 * Implementations must not take ownership of caller resources other than the supplied process. */
public interface McpProcessLifecycle {
    void track(Process process);

    void dispose(Process process);
}
