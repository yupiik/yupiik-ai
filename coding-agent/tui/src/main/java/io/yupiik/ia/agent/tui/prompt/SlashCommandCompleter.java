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
package io.yupiik.ia.agent.tui.prompt;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.ia.agent.core.agent.AgentProvider;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.mcp.McpManager;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ShellRegistered;
import io.yupiik.ia.agent.core.shell.ShellSession;
import io.yupiik.ia.agent.core.shell.ShellUnregistered;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.CompletionEngine;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;

/**
 * JLine {@link Completer} for the plain {@code cli} REPL: completes the slash
 * command names (from the shared {@link SlashRegistry}) and their arguments
 * (models, providers, agents, sessions, tools, options, paths) by reusing the
 * {@link CompletionEngine}. Registered on the {@link LineReader} produced by
 * {@link JLineProducer}.
 */
@ApplicationScoped
public class SlashCommandCompleter implements Completer {
    private final List<String> slashNames;
    private final CompletionEngine engine;
    private final Parser parser;
    private volatile ShellSession session;

    protected SlashCommandCompleter() {
        this.slashNames = null;
        this.engine = null;
        this.parser = null;
    }

    public SlashCommandCompleter(
            final SlashRegistry registry,
            final ChatClient chatClient,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final SessionStore sessionStore,
            final McpManager mcpManager) {
        this(registry, chatClient, providerManager, agentProvider, sessionStore, mcpManager, null);
    }

    public SlashCommandCompleter(
            final SlashRegistry registry,
            final ChatClient chatClient,
            final ProviderManager providerManager,
            final AgentProvider agentProvider,
            final SessionStore sessionStore,
            final McpManager mcpManager,
            final SubagentRegistry subagentRegistry) {
        this.slashNames = registry.names();
        this.parser = new SlashCommandParser(registry);
        this.engine = new CompletionEngine(registry, new ArgCompletion.Env() {
            @Override
            public List<String> models() {
                return modelsOf(chatClient);
            }

            @Override
            public List<String> providers() {
                return providersOf(providerManager);
            }

            @Override
            public List<String> agents() {
                return agentsOf(agentProvider);
            }

            @Override
            public List<String> sessions() {
                return sessionsOf(sessionStore);
            }

            @Override
            public List<String> tools() {
                return toolsOf(mcpManager);
            }

            @Override
            public List<String> servers() {
                return serversOf(mcpManager);
            }

            @Override
            public List<String> subagents() {
                final var current = session;
                return subagentRegistry == null || current == null || current.sessionId() == null
                        ? List.of()
                        : subagentRegistry.completionCandidates(current.sessionId());
            }

            @Override
            public List<String> variants(final String model) {
                return variantsOf(chatClient, model);
            }

            @Override
            public List<String> variants() {
                return variantsOf(chatClient, chatClient == null ? null : chatClient.model());
            }

            @Override
            public List<String> paths(final String token) {
                final var current = session;
                return current == null
                        ? List.of()
                        : CompletionEngine.pathCandidates(
                                token, current.runtime().workdir());
            }
        });
    }

    public void onRegistered(@OnEvent final ShellRegistered event) {
        session = event.env().session();
    }

    public Parser parser() {
        return parser;
    }

    public void onUnregistered(@OnEvent final ShellUnregistered event) {
        if (session == event.env().session()) {
            session = null;
        }
    }

    @Override
    public void complete(final LineReader reader, final ParsedLine line, final List<Candidate> candidates) {
        if (line == null) {
            return;
        }
        final var text = line.line();
        final var cursor = line.cursor();
        if (isCompletingCommand(text, cursor)) {
            final var token = text.substring(0, cursor).toLowerCase(Locale.ROOT);
            for (final var name : slashNames) {
                if (name.toLowerCase(Locale.ROOT).contains(token)) {
                    candidates.add(new Candidate(name));
                }
            }
            return;
        }
        final var suggestion = engine.suggest(text, cursor);
        if (suggestion.empty()) {
            return;
        }
        final var atAgent = "agents".equals(suggestion.label());
        for (final var item : suggestion.items()) {
            candidates.add(new Candidate(atAgent ? "@" + item : item));
        }
    }

    private static boolean isCompletingCommand(final String text, final int cursor) {
        if (!text.startsWith("/") || cursor <= 0 || cursor >= text.length()) {
            return false;
        }
        for (int i = 0; i < cursor; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> modelsOf(final ChatClient chatClient) {
        if (chatClient == null) {
            return List.of();
        }
        try {
            final var ids = chatClient.models().ids();
            return ids == null ? List.of() : ids;
        } catch (final Exception e) {
            return List.of();
        }
    }

    private static List<String> providersOf(final ProviderManager providerManager) {
        if (providerManager == null || providerManager.providers() == null) {
            return List.of();
        }
        try {
            return providerManager.providers().stream()
                    .map(ProviderConfiguration::id)
                    .toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> agentsOf(final AgentProvider agentProvider) {
        if (agentProvider == null || agentProvider.agents() == null) {
            return List.of();
        }
        try {
            return agentProvider.agents().stream().map(AgentConfiguration::name).toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> sessionsOf(final SessionStore sessionStore) {
        if (sessionStore == null) {
            return List.of();
        }
        try {
            final var sessions = sessionStore.list();
            if (sessions == null || sessions.isEmpty()) {
                return List.of();
            }
            return sessions.stream().map(SessionStore.SessionInfo::id).toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> toolsOf(final McpManager mcpManager) {
        if (mcpManager == null) {
            return List.of();
        }
        try {
            final var byServer = mcpManager.toolsByServer();
            if (byServer == null || byServer.isEmpty()) {
                return List.of();
            }
            return byServer.values().stream()
                    .flatMap(List::stream)
                    .map(EmbeddedTool::name)
                    .distinct()
                    .sorted()
                    .toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> serversOf(final McpManager mcpManager) {
        if (mcpManager == null) {
            return List.of();
        }
        try {
            return mcpManager.serverStatuses().stream()
                    .map(status -> status.name())
                    .toList();
        } catch (final RuntimeException e) {
            return List.of();
        }
    }

    private static List<String> variantsOf(final ChatClient chatClient, final String model) {
        try {
            if (chatClient == null) {
                return List.of();
            }
            final var variants = chatClient.variants(model);
            final var hasDefault = chatClient.implicitVariant(model) != null;
            final var size = (variants == null ? 0 : variants.size()) + (hasDefault ? 1 : 0);
            if (size == 0) {
                return List.of();
            }
            final var names = new ArrayList<String>(size);
            if (hasDefault) {
                names.add("default");
            }
            if (variants != null) {
                for (final var variant : variants) {
                    names.add(variant.name());
                }
            }
            return names;
        } catch (final RuntimeException e) {
            return List.of();
        }
    }
}
