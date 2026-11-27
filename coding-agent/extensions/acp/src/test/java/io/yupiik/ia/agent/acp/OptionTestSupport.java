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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.shell.SessionRuntime;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/** Real configuration/resolution with controlled transport and tiny session loops. */
final class OptionTestSupport implements AutoCloseable {
    final CodingAgentConfiguration configuration;
    final ProviderManager providers;
    final ChatClient chat;
    final AcpSessionOptions options;
    final AcpCommand.Conf commandOptions;

    OptionTestSupport(
            final CodingAgentConfiguration configuration,
            final AcpCommand.Conf commandOptions,
            final JsonMapper mapper,
            final Function<ChatSettings, CompletableFuture<ModelsResponse>> fetch) {
        this.configuration = configuration;
        this.commandOptions = commandOptions;
        providers = new ProviderManager(configuration, mapper);
        chat = new ChatClient(configuration, mapper, providers, Clock.systemUTC(), null) {
            @Override
            public CompletableFuture<ModelsResponse> modelsAsync(final ChatSettings settings) {
                return fetch.apply(settings);
            }
        };
        options = new AcpSessionOptions(
                chat,
                providers,
                new AgentSessionService(chat, null, null, null, null, null, null, null, configuration, null, null));
    }

    SessionRuntime runtime(final ChatSettings initial, final Path workdir) {
        return runtime(initial, settings -> true, workdir);
    }

    SessionRuntime runtime(final ChatSettings initial, final Predicate<ChatSettings> accept, final Path workdir) {
        final var loop = new AgentLoop(workdir, new DisabledSandbox(workdir)) {
            private volatile ChatSettings settings = initial;

            @Override
            public ChatSettings chatSettings() {
                return settings;
            }

            @Override
            public void updateChatSettings(final ChatSettings value) {
                if (accept.test(value)) {
                    settings = value;
                }
            }

            @Override
            public boolean iterationLimitReached() {
                return false;
            }

            @Override
            public void close() {}
        };
        return new SessionRuntime(
                loop,
                new PermissionService(configuration, workdir),
                new UserFeedbackService(),
                null,
                null,
                null,
                null,
                null,
                workdir,
                new DisabledSandbox(workdir));
    }

    @Override
    public void close() {
        chat.close();
    }
}
