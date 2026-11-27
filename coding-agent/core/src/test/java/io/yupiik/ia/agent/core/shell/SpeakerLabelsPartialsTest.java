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
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.LabelsConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = ConfigurationTestModule.class)
class SpeakerLabelsPartialsTest {
    private CodingAgentConfiguration baseline;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        baseline = configuration;
    }

    @Test
    void capturedAndUnknownTimesDoNotChangeTheLiveHelperOrLeakBetweenRenders() {
        final var labels = labels(new LabelsConfiguration(
                baseline.labels().userTemplate(),
                baseline.labels().assistantTemplate(),
                "{{#if timestampKnown}}[{{formatDate \"yyyy\"}}]{{/if}}"));
        assertEquals(
                "[2000] provider::model",
                labels.assistantLabel(
                        CTX.at(Instant.parse("2000-01-01T00:00:00Z").toEpochMilli())));
        assertEquals("provider::model", labels.assistantLabel(CTX.at(null)));
        assertEquals("[2031] provider::model", labels.assistantLabel(CTX));
        final var inline = labels(new LabelsConfiguration(
                baseline.labels().userTemplate(),
                "{{formatDate \"yyyy\"}} Assistant",
                baseline.labels().logsTemplate()));
        assertEquals("Assistant", inline.assistantLabel(CTX.at(null)));
        assertEquals("2031 Assistant", inline.assistantLabel(CTX));
    }

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2031-04-05T06:07:08Z"), ZoneId.systemDefault());
    private static final SpeakerLabels.Ctx CTX =
            new SpeakerLabels.Ctx("model", "provider", "agent", "plan", "s1", null);

    @Test
    void defaultsShareTheLogsPrefix() {
        final var labels = labels(new LabelsConfiguration(
                baseline.labels().userTemplate(), baseline.labels().assistantTemplate(), "[shared]"));
        assertEquals("[shared] " + LabelsConfiguration.defaultUserLabel(), labels.userLabel(CTX));
        assertEquals("[shared] provider::model", labels.assistantLabel(CTX));
        assertEquals("[shared]", labels.logsLabel(CTX));
    }

    @Test
    void partialInheritsHelpersAndEachRenderingContext() {
        final var labels = labels(new LabelsConfiguration(
                "{{> logsTemplate}} user",
                "{{> logsTemplate}} assistant",
                "[{{formatDate \"yyyy-MM-dd HH:mm:ss\"}} {{model.provider}} {{agent}}]"));
        final var stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(CLOCK.instant());
        assertEquals("[" + stamp + " provider agent] user", labels.userLabel(CTX));
        assertEquals("[" + stamp + " provider agent] assistant", labels.assistantLabel(CTX));
        final var other = new SpeakerLabels.Ctx("other", "second", "reviewer", "build", "s2", null);
        assertEquals("[" + stamp + " second reviewer] assistant", labels.assistantLabel(other));
    }

    @Test
    void blankPartialDoesNotExpandTheStandaloneLogsFallback() {
        for (final var source : List.of("", "  ", "{{#if missing}}hidden{{/if}}")) {
            final var labels = labels(new LabelsConfiguration(
                    baseline.labels().userTemplate(), baseline.labels().assistantTemplate(), source));
            assertEquals(LabelsConfiguration.defaultUserLabel(), labels.userLabel(CTX));
            assertEquals("provider::model", labels.assistantLabel(CTX));
            assertEquals("Logs", labels.logsLabel(CTX));
        }
    }

    @Test
    void existingInlineDateTemplatesStillWork() {
        final var labels = labels(new LabelsConfiguration(
                "[{{formatDate \"yyyy\"}}] user",
                "[{{formatDate \"yyyy\"}}] assistant",
                baseline.labels().logsTemplate()));
        assertEquals("[2031] user", labels.userLabel(CTX));
        assertEquals("[2031] assistant", labels.assistantLabel(CTX));
    }

    @Test
    void logsTemplateCannotReferenceAnyPartialEvenInsideAConditional() {
        for (final var source : List.of(
                "{{> logsTemplate}}",
                "{{> userTemplate}}",
                "{{> assistantTemplate}}",
                "{{> unknown}}",
                "{{#if missing}}{{> logsTemplate}}{{/if}}")) {
            final var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> labels(new LabelsConfiguration(
                            baseline.labels().userTemplate(), baseline.labels().assistantTemplate(), source)));
            assertTrue(error.getMessage().contains("yca.labels.logsTemplate"), error.getMessage());
            assertTrue(error.getMessage().contains("No partials"), error.getMessage());
        }
    }

    @Test
    void speakerTemplatesCannotReferenceEachOtherOrThemselves() {
        for (final var property : List.of("userTemplate", "assistantTemplate")) {
            for (final var partial : List.of("userTemplate", "assistantTemplate", "unknown")) {
                final var error = assertThrows(
                        IllegalArgumentException.class,
                        () -> labels(new LabelsConfiguration(
                                "userTemplate".equals(property)
                                        ? "{{> " + partial + "}}"
                                        : baseline.labels().userTemplate(),
                                "assistantTemplate".equals(property)
                                        ? "{{> " + partial + "}}"
                                        : baseline.labels().assistantTemplate(),
                                baseline.labels().logsTemplate())));
                assertTrue(error.getMessage().contains("yca.labels." + property), error.getMessage());
            }
        }
    }

    private SpeakerLabels labels(final LabelsConfiguration labels) {
        return new SpeakerLabels(
                List.of(new FormatDatePromptHelper(CLOCK)),
                new CodingAgentConfiguration(
                        baseline.storageHome(),
                        baseline.home(),
                        baseline.workingDir(),
                        baseline.projectDir(),
                        baseline.provider(),
                        baseline.model(),
                        baseline.llm(),
                        baseline.compaction(),
                        baseline.pruning(),
                        baseline.compression(),
                        baseline.memory(),
                        baseline.todos(),
                        baseline.optimize(),
                        baseline.permissions(),
                        baseline.sandbox(),
                        baseline.mcp(),
                        baseline.mavenRepositoryKnowledge(),
                        baseline.structuralCodeGraph(),
                        baseline.sessions(),
                        baseline.skills(),
                        baseline.agents(),
                        baseline.mode(),
                        baseline.httpClient(),
                        baseline.tools(),
                        baseline.subagents(),
                        baseline.schedulerThreads(),
                        baseline.metrics(),
                        baseline.maxLineLength(),
                        baseline.resumeAliases(),
                        baseline.slashcommands(),
                        baseline.prompt(),
                        labels,
                        baseline.logging(),
                        baseline.hooks(),
                        baseline.notifications(),
                        baseline.secrets(),
                        null),
                null);
    }
}
