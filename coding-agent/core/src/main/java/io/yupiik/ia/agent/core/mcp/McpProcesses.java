package io.yupiik.ia.agent.core.mcp;

import io.yupiik.ai.mcp.client.McpProcessLifecycle;
import io.yupiik.maven.knowledge.process.ProcessService;

/** Adapts agent process accounting without coupling the protocol client to the agent. */
final class McpProcesses {
    private McpProcesses() {}

    static McpProcessLifecycle of(final ProcessService service) {
        if (service == null) {
            return null;
        }
        return new McpProcessLifecycle() {
            @Override
            public void track(final Process process) {
                service.track(process);
            }

            @Override
            public void dispose(final Process process) {
                service.dispose(process);
            }
        };
    }
}
