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

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.SessionLifecycle;
import io.yupiik.ia.agent.core.shell.ThinkTagSplitter;
import io.yupiik.ia.agent.core.style.ModelStyleService;
import io.yupiik.ia.agent.core.todo.PlanFileService;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.concurrent.ExecutorService;

/**
 * Test-only bean aggregating every {@link TuiCommand} dependency so tests do a
 * single lookup instead of one per dependency. Session-scoped components
 * (runtime, loop, permissions, todos...) are intentionally absent: they are
 * created per session by {@link io.yupiik.ia.agent.core.shell.SessionRuntimeFactory}.
 */
@DefaultScoped
public record TuiDeps(
        TuiConfiguration tuiConfiguration,
        CodingAgentConfiguration configuration,
        SessionStore sessionStore,
        SessionLifecycle sessionLifecycle,
        PlanFileService planFileService,
        ChatClient chatClient,
        ModelStyleService modelStyleService,
        MemoryStore memoryStore,
        McpManager mcpManager,
        ExecutorService executor,
        StructuralCodeGraph structuralCodeGraph,
        ProviderManager providerManager,
        AgentProvider agentProvider,
        AgentSessionService agentSessionService,
        ThinkTagSplitter thinkTags) {}
