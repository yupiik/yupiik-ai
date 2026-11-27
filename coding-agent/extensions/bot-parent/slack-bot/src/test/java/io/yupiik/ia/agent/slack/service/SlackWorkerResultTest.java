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
package io.yupiik.ia.agent.slack.service;

import static io.yupiik.ia.agent.slack.support.SlackDomainFixture.task;
import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.context.SlackContextStorage;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import io.yupiik.ia.agent.slack.support.SlackDomainFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@FusionSupport(modules = SlackConfigurationTestModule.class)
class SlackWorkerResultTest {
    @Fusion
    private SlackPrompts baselinePrompts;

    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"Safe answer", "Quotes: \"yes\"\nBackslash: \\ and accents: \u00e9", "MAX"})
    void publishesTypedResultWithoutChangingAnswer(final String value) throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var answer = "MAX".equals(value) ? "a".repeat(fixture.configuration.maxAnswerChars()) : value;
            final var id = task("result", "U1", TaskStatus.queued, 1).id();
            final var source = "{\"summary\":" + fixture.json.toString(answer) + ",\"taskId\":\"" + id + "\"}";
            run(fixture, source);
            final var saved = fixture.store.list().getFirst();
            assertEquals(TaskStatus.done, saved.status());
            assertEquals(answer, saved.answer());
            assertEquals(1, fixture.exchanges.size());
            assertTrue(fixture.exchanges.getFirst().body().contains("\"plain_text\""));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void malformedResultFailsWithoutPublication(final String source) throws Exception {
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var id = task("result", "U1", TaskStatus.queued, 1).id();
            run(fixture, source.replace("TASK", id));
            final var saved = fixture.store.list().getFirst();
            assertEquals(TaskStatus.failed, saved.status());
            assertNull(saved.answer());
            assertTrue(fixture.exchanges.isEmpty());
        }
    }

    private static Stream<String> invalidResults() {
        return Stream.of(
                "null",
                "{\"taskId\":",
                "{}",
                "{\"taskId\":\"TASK\"}",
                "{\"summary\":\"answer\"}",
                "{\"taskId\":null,\"summary\":\"answer\"}",
                "{\"taskId\":\"TASK\",\"summary\":null}",
                "{\"taskId\":\"other\",\"summary\":\"answer\"}",
                "{\"taskId\":\"TASK\",\"summary\":\"\"}",
                "{\"taskId\":\"TASK\",\"summary\":\" \"}",
                "{\"taskId\":\"TASK\",\"summary\":\"" + "a".repeat(12001) + "\"}");
    }

    @Test
    void rendersOnlyExactUserMentionsAndPreservesUntrustedTextAndCitations() throws Exception {
        final var literal = " Name <!here> <!channel> <!everyone> <@U3|name> <@u3> <@B3> *bold* "
                + "<https://evil.invalid|click> [1](https://example.org/source?q=a&b=c) https://example.org/real";
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var answer = "<@U3>" + literal + "<@W123ABC>";
            final var id = task("result", "U1", TaskStatus.queued, 1).id();
            run(fixture, "{\"summary\":" + fixture.json.toString(answer) + ",\"taskId\":\"" + id + "\"}");
            assertEquals(TaskStatus.done, fixture.store.list().getFirst().status());
            final var body = fixture.json.fromString(
                    Map.class, fixture.exchanges.getFirst().body());
            assertFalse(body.containsKey("link_names"));
            assertFalse(body.containsKey("parse"));
            final var block = (Map<?, ?>) ((List<?>) body.get("blocks")).getFirst();
            assertEquals(
                    Map.of(
                            "type",
                            "rich_text",
                            "elements",
                            List.of(Map.of(
                                    "type",
                                    "rich_text_section",
                                    "elements",
                                    List.of(
                                            Map.of("type", "user", "user_id", "U3"),
                                            Map.of("type", "text", "text", literal),
                                            Map.of("type", "user", "user_id", "W123ABC"))))),
                    block);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PLAIN", "BOUNDARY", "DENSE", "INVALID"})
    void preservesContentAcrossBoundedBlocks(final String kind) throws Exception {
        final var answer =
                switch (kind) {
                    case "BOUNDARY" -> "a".repeat(2898) + "<@U3>" + "\ud83d\ude00" + "b".repeat(2900);
                    case "DENSE" -> "<@U3> <@W123> ".repeat(300);
                    case "INVALID" -> "<!here> <@U3|name> <@u3> <@U" + "A".repeat(65) + "> https://example.org";
                    default -> "a".repeat(2899) + "\ud83d\ude00" + "b".repeat(3000);
                };
        try (final var fixture = new SlackDomainFixture(root, 4, 2)) {
            final var id = task("result", "U1", TaskStatus.queued, 1).id();
            run(fixture, "{\"summary\":" + fixture.json.toString(answer) + ",\"taskId\":\"" + id + "\"}");
            assertEquals(TaskStatus.done, fixture.store.list().getFirst().status());
            final var body = fixture.json.fromString(
                    Map.class, fixture.exchanges.getFirst().body());
            final var blocks = (List<?>) body.get("blocks");
            assertTrue(blocks.size() <= 50);
            final var restored = new StringBuilder();
            for (final var item : blocks) {
                final var block = (Map<?, ?>) item;
                if ("section".equals(block.get("type"))) {
                    final var text = (Map<?, ?>) block.get("text");
                    assertEquals("plain_text", text.get("type"));
                    final var value = (String) text.get("text");
                    assertTrue(value.length() <= 2900);
                    assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)));
                    assertFalse(Character.isLowSurrogate(value.charAt(0)));
                    restored.append(value);
                } else {
                    final var section = (Map<?, ?>) ((List<?>) block.get("elements")).getFirst();
                    final var elements = (List<?>) section.get("elements");
                    assertTrue(elements.size() <= 100);
                    for (final var entry : elements) {
                        final var element = (Map<?, ?>) entry;
                        if ("user".equals(element.get("type"))) {
                            restored.append("<@").append(element.get("user_id")).append('>');
                        } else {
                            assertEquals("text", element.get("type"));
                            restored.append(element.get("text"));
                        }
                    }
                }
            }
            assertEquals(answer, restored.toString());
            if ("PLAIN".equals(kind) || "INVALID".equals(kind)) {
                assertTrue(blocks.stream().allMatch(block -> "section".equals(((Map<?, ?>) block).get("type"))));
            }
        }
    }

    private void run(final SlackDomainFixture fixture, final String source) {
        fixture.add(task("result", "U1", TaskStatus.queued, 1));
        final Sandbox sandbox = new Sandbox() {
            @Override
            public int execute(
                    final Path directory, final String identity, final String prompt, final Path temporaryDirectory) {
                try {
                    Files.createDirectories(directory.resolve("output"));
                    Files.writeString(directory.resolve("output/result.json"), source);
                    return 0;
                } catch (final IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            public void stop(final String identity) {}

            @Override
            public void close() {}
        };
        final var worker = new SlackWorker(
                fixture.configuration,
                fixture.executor(root),
                fixture.json,
                fixture.store,
                new SlackPromptRenderer(SlackDomainFixture.prompts(baselinePrompts), fixture.json),
                sandbox,
                new SlackFeedback(
                        fixture.configuration, SlackDomainFixture.prompts(baselinePrompts), fixture.store, fixture.api),
                fixture.api,
                new HostAdmission(new HostAdmissionConfiguration("", 1, "")),
                fixture.contextConfiguration,
                fixture.actions,
                fixture.contextDispatcher,
                new SlackContextStorage());
        assertTrue(worker.runOne());
    }
}
