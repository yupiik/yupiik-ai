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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.configuration.WorkerOptions.SourceMode;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@FusionSupport(modules = GithubConfigurationTestModule.class)
class CompletionPromptHandoffTest {
    @Fusion
    private PromptConfiguration prompts;

    @Fusion
    private GithubExecutorConfiguration executorRoot;

    @TempDir
    Path home;

    @Test
    void onlyRootCorrectionPromptSurvivesPrepareAndBrokerStagingForFlatAndWrappedTrustedConfiguration()
            throws Exception {
        final var correction = "Repair the result JSON only: {{validationError}} \"quotes\" <tag>&\n"
                + "{{> missing}} is literal configuration data.";
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var preparation = preparation(json);
            for (final var wrapped : List.of(false, true)) {
                final var yca = new LinkedHashMap<String, Object>();
                yca.put("provider", "openai");
                yca.put("model", "trusted-model");
                yca.put("hooks", Map.of("before", "HOST-HOOK-SECRET"));
                yca.put("run", Map.of("result-correction-prompt", "NESTED-UNTRUSTED-PROMPT"));
                final var root = new LinkedHashMap<String, Object>();
                if (wrapped) {
                    root.put("yca", yca);
                } else {
                    root.putAll(yca);
                }
                root.put(
                        "run",
                        Map.of(
                                "result-correction-prompt", correction,
                                "prompt", "HOST-RUN-PROMPT",
                                "result-file", "/host/private/result.json",
                                "hooks", Map.of("after", "HOST-RUN-HOOK"),
                                "arbitrary-option", "HOST-RUN-OPTION"));
                root.put("hooks", Map.of("after", "HOST-ROOT-HOOK"));
                root.put("otherHostSetting", "HOST-OTHER-SETTING");
                final var original = json.toString(root);
                final var trusted = Files.writeString(home.resolve("trusted-" + wrapped + ".json"), original);
                final var attempt = preparation.prepare(trusted, home.resolve("attempts"));
                try {
                    for (final var staged : List.of(false, true)) {
                        if (staged) {
                            preparation.stageBroker(
                                    attempt,
                                    "https://context.example.invalid/attempt-1",
                                    "owner/repo",
                                    Map.of("headSha", "0123456789abcdef0123456789abcdef01234567"),
                                    new WorkerOptions.Source(SourceMode.broker, "", 1000, 536870912L, 120),
                                    null);
                        }
                        final var serialized = Files.readString(attempt.resolve("configuration.json"));
                        final var document = json.fromString(AgentConfigurationDocument.class, serialized);
                        assertEquals(
                                Map.of("run", Map.of("result-correction-prompt", correction)), document.settings());
                        assertEquals(
                                Map.of("run", Map.of("result-correction-prompt", correction)),
                                document.completionSettings());
                        final var configuration = document.configuration();
                        assertEquals("openai", configuration.get("provider"));
                        assertEquals("trusted-model", configuration.get("model"));
                        assertEquals(Map.of(), configuration.get("hooks"));
                        assertFalse(configuration.containsKey("run"));
                        assertFalse(configuration.containsKey("otherHostSetting"));
                        assertEquals(staged, configuration.containsKey("githubContext"));
                        for (final var forbidden : List.of(
                                "HOST-HOOK-SECRET",
                                "NESTED-UNTRUSTED-PROMPT",
                                "HOST-RUN-PROMPT",
                                "/host/private/result.json",
                                "HOST-RUN-HOOK",
                                "HOST-RUN-OPTION",
                                "HOST-ROOT-HOOK",
                                "HOST-OTHER-SETTING")) {
                            assertFalse(serialized.contains(forbidden), forbidden);
                        }
                    }
                    assertTrue(Files.readString(attempt.resolve("workspace/github-source-broker.sh"))
                            .contains("--head '0123456789abcdef0123456789abcdef01234567'"));
                    assertEquals(original, Files.readString(trusted));
                } finally {
                    AttemptPreparation.delete(attempt);
                }
            }
        }
    }

    @Test
    void absentOrNonStringRootPromptDoesNotForwardRunOrFallBackToNestedPrompt() throws Exception {
        try (final var json = BotJsonMapper.create(key -> Optional.empty())) {
            final var preparation = preparation(json);
            for (final var run : List.of(
                    Map.of("prompt", "HOST-PROMPT"),
                    Map.of("result-correction-prompt", List.of("not-a-string")),
                    Map.of("result-correction-prompt", 42))) {
                final var trusted = Files.writeString(
                        home.resolve("invalid.json"),
                        json.toString(Map.of(
                                "yca",
                                Map.of(
                                        "model",
                                        "trusted-model",
                                        "run",
                                        Map.of("result-correction-prompt", "NESTED-PROMPT")),
                                "run",
                                run)));
                final var attempt = preparation.prepare(trusted, home.resolve("attempts"));
                try {
                    for (final var staged : List.of(false, true)) {
                        if (staged) {
                            preparation.stageBroker(
                                    attempt,
                                    "https://context.example.invalid/attempt-1",
                                    "owner/repo",
                                    Map.of("headSha", "0123456789abcdef0123456789abcdef01234567"),
                                    new WorkerOptions.Source(SourceMode.broker, "", 1000, 536870912L, 120),
                                    null);
                        }
                        final var document = json.fromString(
                                AgentConfigurationDocument.class,
                                Files.readString(attempt.resolve("configuration.json")));
                        assertEquals(Map.of(), document.completionSettings());
                        assertTrue(document.settings() == null
                                || document.settings().isEmpty());
                        assertFalse(document.configuration().containsKey("run"));
                        assertEquals("trusted-model", document.configuration().get("model"));
                    }
                } finally {
                    AttemptPreparation.delete(attempt);
                }
            }
        }
    }

    private AttemptPreparation preparation(final JsonMapper json) {
        return new AttemptPreparation(json, prompts, executorRoot.shared());
    }
}
