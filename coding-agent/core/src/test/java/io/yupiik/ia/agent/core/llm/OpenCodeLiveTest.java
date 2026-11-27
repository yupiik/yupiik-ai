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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.SystemPromptBuilder;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.NativeProviderConfiguration;
import io.yupiik.ia.agent.core.wiring.HttpClientProducer;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Explicit opt-in only: never consumes free-tier requests in the normal test suite. */
@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = OpenCodeLiveTest.LiveModule.class)
@EnabledIfSystemProperty(named = "yca.opencode.live", matches = "true")
class OpenCodeLiveTest {
    @TempDir
    static Path directory;

    @Test
    void readToolRoundTripThroughCoreClient(final JsonMapper mapper, @Fusion final CodingAgentConfiguration cfg)
            throws Exception {
        final var fixture = directory.resolve("probe.txt");
        Files.writeString(fixture, "YCA_OPENCODE_OK");
        final var tools = List.of(Tool.function(
                "read",
                "Read a file",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("path", Map.of("type", "string")),
                        "required",
                        List.of("path"))));
        final var messages = new ArrayList<Message>();
        // Exercise the real agent prompt, not a short stand-in or captured OpenCode instructions.
        final var providers = new ProviderManager(cfg, mapper);
        messages.add(Message.system(new SystemPromptBuilder(
                        cfg, null, null, null, null, providers, null, null, null, (cfg).resolveWorkingDir())
                .build()));
        messages.add(
                Message.user("Call read for " + fixture + ". Then reply only with the file content. Do not reason."));
        try (final var http = new HttpClientProducer().httpClient(cfg, new NativeProviderConfiguration(null, null));
                final var client = new ChatClient(cfg, mapper, providers, Clock.systemUTC(), http)) {
            final var model = System.getProperty("yca.opencode.model", "big-pickle");
            assertTrue(client.models().ids().contains(model), "live catalog must advertise the selected model");
            final var response = client.chat(new ChatCompletionRequest(
                    null, messages, null, null, false, tools, "required", 64, null, Map.of()));
            assertNotNull(response.message().toolCalls(), "live endpoint must return a tool call");
            final var call = response.message().toolCalls().getFirst();
            assertEquals("read", call.function().name());
            final var args = mapper.fromString(Map.class, call.function().arguments());
            assertEquals(fixture.toString(), args.get("path"));
            messages.add(response.message());
            messages.add(Message.tool(call.id(), Files.readString(fixture)));
            final var followup = client.chat(
                    new ChatCompletionRequest(null, messages, null, null, false, tools, "none", 64, null, Map.of()));
            assertTrue(String.valueOf(followup.message().content()).contains("YCA_OPENCODE_OK"));
        }
    }

    public static class LiveModule extends ConfigurationTestModule {
        public LiveModule() {
            super(Map.ofEntries(
                    Map.entry("yca.storageHome", directory.toString()),
                    Map.entry("yca.home", directory.toString()),
                    Map.entry("yca.workingDir", directory.toString()),
                    Map.entry("yca.provider.api", "opencode-free"),
                    Map.entry("yca.provider.baseUrl", "https://opencode.ai/inference/openai/v1/chat/completions"),
                    Map.entry("yca.provider.model", System.getProperty("yca.opencode.model", "big-pickle")),
                    Map.entry("yca.provider.contextWindow", "128000"),
                    Map.entry("yca.provider.outputTokens", "64"),
                    Map.entry("yca.provider.timeoutSeconds", "45"),
                    Map.entry("yca.llm.retriesMaxAttempts", "1")));
        }
    }
}
