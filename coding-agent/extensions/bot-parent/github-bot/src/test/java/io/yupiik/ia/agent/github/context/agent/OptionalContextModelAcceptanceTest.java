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
package io.yupiik.ia.agent.github.context.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.serialization.JsonCodec;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.Tool;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.github.configuration.AgentContextConfiguration;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in live tool-shape acceptance, not a model-quality benchmark.
 * Set YCA_CONTEXT_ACCEPTANCE_ENABLED=true and explicitly supply all of
 * YCA_CONTEXT_ACCEPTANCE_PROVIDER, API, MODEL, BASE_URL and API_KEY (with the same prefix).
 * No ambient provider credentials/configuration are inherited and no system properties are mutated.
 * The context capability is disabled: only the explicitly authorized model endpoint can be called.
 */
@FusionSupport(modules = OptionalContextModelAcceptanceTest.Source.class)
class OptionalContextModelAcceptanceTest {
    @Fusion
    private CodingAgentConfiguration injectedConfiguration;

    @TempDir
    static Path home;

    @Test
    @Timeout(120)
    void explicitlyAuthorizedModelProducesArgumentsAcceptedByBothContextTools() throws Exception {
        final var environment = System.getenv();
        assumeTrue(
                "true".equals(environment.get("YCA_CONTEXT_ACCEPTANCE_ENABLED")),
                "Live context model acceptance requires explicit authorization");
        for (final var suffix : List.of("PROVIDER", "API", "MODEL", "BASE_URL", "API_KEY")) {
            final var name = "YCA_CONTEXT_ACCEPTANCE_" + suffix;
            final var value = environment.get(name);
            assertNotNull(value, "Explicit acceptance setting required: " + name);
            assertFalse(value.isBlank(), "Explicit acceptance setting must not be blank: " + name);
        }
        final var config = injectedConfiguration;
        final var workdir = config.resolveWorkingDir();
        try (final var container = jsonContainer();
                final var lookup = container.lookup(JsonMapper.class)) {
            final var json = lookup.instance();
            final var disabled = new AgentContextConfiguration(false, "", "", "", "fixture/primary");
            final List<EmbeddedTool> tools = List.of(
                    new GitTool(disabled, json, Configuration.of(Map.of())),
                    new GithubContextTool(disabled, json, Configuration.of(Map.of())));
            final var provider = config.provider().providers().getFirst().id();
            final var model = config.provider().providers().getFirst().model();
            try (final var http =
                            new HttpClientProducer().httpClient(config, new NativeProviderConfiguration(null, null));
                    final var client =
                            new ChatClient(config, json, new ProviderManager(config, json), Clock.systemUTC(), http)) {
                final var response = client.chat(new ChatCompletionRequest(
                        "$" + provider + "/" + model,
                        List.of(
                                Message.user(
                                        "Call both provided tools now: github_context with operation summary, and git with argv [\"log\",\"-1\"]. Do not use a shell command string. Do not invent a repository. Omit optional fields or use null. Do not answer in prose.")),
                        null,
                        null,
                        false,
                        tools.stream()
                                .map(tool -> Tool.function(tool.name(), tool.description(workdir), tool.schema()))
                                .toList(),
                        null,
                        1024,
                        null,
                        null));
                final var calls = response.message().toolCalls();
                assertNotNull(calls, "Model must return tool calls");
                assertEquals(2, calls.size(), "One call to each context tool is required");
                final var names = new HashSet<String>();
                for (final var call : calls) {
                    final var name = call.function().name();
                    assertTrue(names.add(name), "Duplicate tool call");
                    final var tool = tools.stream()
                            .filter(candidate -> candidate.name().equals(name))
                            .findFirst()
                            .orElseThrow();
                    final var arguments =
                            object(json.fromString(Object.class, call.function().arguments()));
                    if (name.equals("git")) {
                        assertEquals(List.of("log", "-1"), arguments.get("argv"));
                    } else {
                        assertEquals("summary", arguments.get("operation"));
                    }
                    final var result =
                            tool.execute(arguments, ToolContext.empty(workdir, new DisabledSandbox(workdir)));
                    assertTrue(result.error());
                    assertEquals(
                            "Context capability unavailable.",
                            result.output(),
                            "Arguments must pass production runtime validation");
                }
                assertEquals(Set.of("git", "github_context"), names);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) assertInstanceOf(Map.class, value);
    }

    private static RuntimeContainer jsonContainer() {
        return ConfiguringContainer.of()
                .register(new FusionModule() {
                    @Override
                    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                        return (container, bean) -> {
                            final var type = bean.type() instanceof ParameterizedType parameterized
                                    ? (Class<?>) parameterized.getRawType()
                                    : (Class<?>) bean.type();
                            return JsonCodec.class.isAssignableFrom(type)
                                    || JsonMapper.class.isAssignableFrom(type)
                                    || Configuration.class.isAssignableFrom(type)
                                    || Emitter.class.isAssignableFrom(type)
                                    || RuntimeContainer.class.isAssignableFrom(type);
                        };
                    }

                    @Override
                    public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                        return (container, listener) -> false;
                    }
                })
                .start();
    }

    public static class Source extends GithubConfigurationTestModule {
        public Source() {
            super(settings());
        }

        private static Map<String, String> settings() {
            if (!"true".equals(System.getenv("YCA_CONTEXT_ACCEPTANCE_ENABLED"))) {
                return Map.of("yca.storageHome", home.toString(), "yca.workingDir", home.toString());
            }
            final var values = new HashMap<String, String>();
            values.put("yca.storageHome", home.toString());
            values.put("yca.workingDir", home.toString());
            values.put("yca.provider.providers.length", "1");
            for (final var field : Map.of(
                            "id",
                            "PROVIDER",
                            "api",
                            "API",
                            "model",
                            "MODEL",
                            "baseUrl",
                            "BASE_URL",
                            "apiKey",
                            "API_KEY")
                    .entrySet()) {
                final var value = System.getenv("YCA_CONTEXT_ACCEPTANCE_" + field.getValue());
                if (value != null) {
                    values.put("yca.provider.providers.0." + field.getKey(), value);
                }
            }
            values.put("yca.provider.providers.0.timeoutSeconds", "60");
            values.put("yca.provider.providers.0.contextWindow", "128000");
            values.put("yca.llm.retriesMaxAttempts", "1");
            values.put("yca.llm.rateLimitRequestsPerMinute", "0");
            return values;
        }
    }
}
