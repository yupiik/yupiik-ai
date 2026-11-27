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

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.configuration.GithubExecutorConfiguration;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions.SourceMode;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AttemptAssessmentPreparationTest {
    @Nested
    @FusionSupport(modules = GithubConfigurationTestModule.class)
    class Defaults {
        @Fusion
        private PromptConfiguration prompts;

        @Fusion
        private GithubExecutorConfiguration executorRoot;

        @Test
        void issueAndDiscussionAssessmentsHaveAdvisoryContractInEverySupportedMode() {
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var preparation = new AttemptPreparation(json, prompts, executorRoot.shared());
                for (final var target : List.of("issue", "discussion")) {
                    final var task = TaskFixtures.task(Map.of(
                            "id",
                            "assessment-1",
                            "kind",
                            "review",
                            "reviewTarget",
                            target,
                            "repository",
                            "owner/repo",
                            "number",
                            42,
                            "sourceType",
                            target,
                            "request",
                            "Evaluate {{> untrusted}}"));
                    for (final var source : List.of(SourceMode.export, SourceMode.broker)) {
                        for (final var commenting : List.of(false, true)) {
                            if (commenting && source == SourceMode.broker) {
                                continue;
                            }
                            final var prompt = preparation.prompt(task, null, commenting, source);
                            assertTrue(prompt.contains("For " + target + " review: assess the proposal"), prompt);
                            assertTrue(prompt.contains("summary, tests and assessment (or failure instead)"), prompt);
                            for (final var field : List.of(
                                    "recommendation",
                                    "assessment",
                                    "concerns",
                                    "suggestedApproach",
                                    "acceptanceCriteria",
                                    "openQuestions")) {
                                assertTrue(prompt.contains(field), prompt);
                            }
                            for (final var value : List.of(
                                    "proceed",
                                    "proceed_with_conditions",
                                    "reconsider",
                                    "clarification_needed",
                                    "16000",
                                    "2000",
                                    "28000")) {
                                assertTrue(prompt.contains(value), prompt);
                            }
                            assertTrue(prompt.contains("do not implement code changes"), prompt);
                            assertTrue(prompt.contains("close the issue or Discussion, or mark an answer"), prompt);
                            assertTrue(prompt.contains("Do not return review, reviewId, strictSolve or prUrl"), prompt);
                            assertTrue(prompt.contains("omit assessment and return failure instead"), prompt);
                            assertTrue(prompt.contains("Evaluate {{> untrusted}}"), prompt);
                            assertFalse(prompt.contains("For review: obtain current head SHA"), prompt);
                            assertFalse(prompt.contains("Return review:{"), prompt);
                            assertFalse(prompt.contains("You MAY publish"), prompt);
                            assertFalse(prompt.contains("For reviews return reviewId"), prompt);
                            assertFalse(prompt.contains("Findings need severity"), prompt);
                        }
                    }
                }
            }
        }

        @Test
        void pullRequestReviewKeepsPinnedDiffContract() {
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var preparation = new AttemptPreparation(json, prompts, executorRoot.shared());
                final var prompt = preparation.prompt(
                        TaskFixtures.task(Map.of(
                                "id",
                                "task-1",
                                "kind",
                                "review",
                                "reviewTarget",
                                "pull_request",
                                "repository",
                                "owner/repo",
                                "number",
                                42)),
                        null,
                        false);
                assertTrue(prompt.contains("For review: obtain current head SHA, diff and conversations."), prompt);
                assertTrue(prompt.contains("summary, tests and review (or failure instead)"), prompt);
                assertTrue(prompt.contains("Return review:{headSha:"), prompt);
                assertFalse(prompt.contains("Return assessment with recommendation"), prompt);
            }
        }
    }

    @Nested
    @FusionSupport(modules = IdentityModule.class)
    class Identity {
        @Fusion
        private PromptConfiguration prompts;

        @Fusion
        private GithubExecutorConfiguration executorRoot;

        @Test
        void targetIdentityAndReferenceAreExplicitAndUseCorrectGithubNamespace() {
            try (final var json = BotJsonMapper.create(ignored -> Optional.empty())) {
                final var preparation = new AttemptPreparation(json, prompts, executorRoot.shared());
                for (final var target : List.of("issue", "discussion", "pull_request")) {
                    final var namespace =
                            switch (target) {
                                case "issue" -> "issues";
                                case "discussion" -> "discussions";
                                default -> "pull";
                            };
                    final var prompt = preparation.prompt(
                            TaskFixtures.task(Map.of(
                                    "id",
                                    "task-1",
                                    "kind",
                                    "review",
                                    "reviewTarget",
                                    target,
                                    "repository",
                                    "owner/repo",
                                    "number",
                                    42)),
                            null,
                            false);
                    final var parts = prompt.split("\\|", 3);
                    assertEquals("\"https://github.com/owner/repo/" + namespace + "/42\"", parts[0]);
                    assertEquals(target, parts[1]);
                    assertEquals(target, ((Map<?, ?>) json.fromString(Object.class, parts[2])).get("reviewTarget"));
                }
            }
        }
    }

    public static class IdentityModule extends GithubConfigurationTestModule {
        public IdentityModule() {
            super(Map.of("github.prompts.main", "{{{referenceJson}}}|{{{reviewTarget}}}|{{{taskJson}}}"));
        }
    }
}
