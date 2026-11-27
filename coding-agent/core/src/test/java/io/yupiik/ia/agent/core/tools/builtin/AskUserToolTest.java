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
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.question.UserFeedbackService;
import io.yupiik.ia.agent.core.runtime.Cancellations;
import io.yupiik.ia.agent.core.sandbox.DisabledSandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class AskUserToolTest {
    @TempDir
    private static Path testWorkdir;

    private JsonMapper mapper;
    private UserFeedbackService feedback;
    private AskUserTool tool;

    @BeforeEach
    void setUp(final JsonMapper mapper) {
        this.mapper = mapper;
        feedback = new UserFeedbackService() {
            @Override
            public Map<String, Object> ask(final List<Question> questions) {
                final Map<String, Object> answers = new HashMap<>();
                for (final Question q : questions) {
                    answers.put(q.id(), q.multiple() ? List.of() : "");
                }
                return answers;
            }
        };
        tool = new AskUserTool(mapper);
    }

    private ToolContext context() {
        return new ToolContext(
                "s1",
                null,
                null,
                null,
                feedback,
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
    }

    @Test
    void executeParsesOptionObjectsWithPromptField() {
        final var args = new HashMap<String, Object>();
        final var questions = new ArrayList<Map<String, Object>>();
        final var q1 = new HashMap<String, Object>();
        q1.put("id", "q1");
        q1.put("prompt", "Choose option:");
        final var opts = new ArrayList<Map<String, Object>>();
        final var opt1 = new HashMap<String, Object>();
        opt1.put("id", "opt1");
        opt1.put("prompt", "First option");
        opt1.put("description", "Desc 1");
        final var opt2 = new HashMap<String, Object>();
        opt2.put("id", "opt2");
        opt2.put("prompt", "Second option");
        opt2.put("description", "Desc 2");
        opts.add(opt1);
        opts.add(opt2);
        q1.put("options", opts);
        q1.put("multiple", false);
        questions.add(q1);
        args.put("questions", questions);

        final ToolResult result = tool.execute(args, context());
        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void executeFallbacksFromPromptToDescriptionToId() {
        final var args = new HashMap<String, Object>();
        final var questions = new ArrayList<Map<String, Object>>();
        final var q1 = new HashMap<String, Object>();
        q1.put("id", "q1");
        q1.put("prompt", "Choose:");
        final var opts = new ArrayList<Map<String, Object>>();
        final var opt1 = new HashMap<String, Object>();
        opt1.put("id", "opt1");
        opt1.put("description", "Has description only");
        final var opt2 = new HashMap<String, Object>();
        opt2.put("id", "opt2");
        final var opt3 = new HashMap<String, Object>();
        opt3.put("id", "opt3");
        opt3.put("prompt", "Has prompt");
        opts.add(opt1);
        opts.add(opt2);
        opts.add(opt3);
        q1.put("options", opts);
        q1.put("multiple", false);
        questions.add(q1);
        args.put("questions", questions);

        final ToolResult result = tool.execute(args, context());
        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void executeFallbacksQuestionPromptFromFirstOption() {
        final var args = new HashMap<String, Object>();
        final var questions = new ArrayList<Map<String, Object>>();
        final var q1 = new HashMap<String, Object>();
        q1.put("id", "q1");
        final var opts = new ArrayList<Map<String, Object>>();
        final var opt1 = new HashMap<String, Object>();
        opt1.put("id", "opt1");
        opt1.put("prompt", "First option prompt");
        final var opt2 = new HashMap<String, Object>();
        opt2.put("id", "opt2");
        opt2.put("prompt", "Second option prompt");
        opts.add(opt1);
        opts.add(opt2);
        q1.put("options", opts);
        q1.put("multiple", false);
        questions.add(q1);
        args.put("questions", questions);

        final ToolResult result = tool.execute(args, context());
        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void executeHandlesMixedStringAndObjectOptions() {
        final var args = new HashMap<String, Object>();
        final var questions = new ArrayList<Map<String, Object>>();
        final var q1 = new HashMap<String, Object>();
        q1.put("id", "q1");
        q1.put("prompt", "Choose:");
        final var opts = new ArrayList<Object>();
        opts.add("simple string option");
        final var opt2 = new HashMap<String, Object>();
        opt2.put("id", "opt2");
        opt2.put("prompt", "Object option");
        opts.add(opt2);
        opts.add("another string");
        q1.put("options", opts);
        q1.put("multiple", false);
        questions.add(q1);
        args.put("questions", questions);

        final ToolResult result = tool.execute(args, context());
        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void executeMultipleQuestionsWithObjectOptions() {
        final var args = new HashMap<String, Object>();
        final var questions = new ArrayList<Map<String, Object>>();

        final var q1 = new HashMap<String, Object>();
        q1.put("id", "q1");
        q1.put("prompt", "Question 1");
        final var opts1 = new ArrayList<Map<String, Object>>();
        final var o1a = new HashMap<String, Object>();
        o1a.put("id", "a");
        o1a.put("prompt", "Option A");
        final var o1b = new HashMap<String, Object>();
        o1b.put("id", "b");
        o1b.put("prompt", "Option B");
        opts1.add(o1a);
        opts1.add(o1b);
        q1.put("options", opts1);
        q1.put("multiple", false);
        questions.add(q1);

        final var q2 = new HashMap<String, Object>();
        q2.put("id", "q2");
        q2.put("prompt", "Question 2");
        final var opts2 = new ArrayList<Map<String, Object>>();
        final var o2a = new HashMap<String, Object>();
        o2a.put("id", "x");
        o2a.put("description", "Option X desc");
        final var o2b = new HashMap<String, Object>();
        o2b.put("id", "y");
        o2b.put("description", "Option Y desc");
        opts2.add(o2a);
        opts2.add(o2b);
        q2.put("options", opts2);
        q2.put("multiple", true);
        questions.add(q2);

        args.put("questions", questions);

        final ToolResult result = tool.execute(args, context());
        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void executeReturnsErrorForEmptyQuestions() {
        final ToolResult result = tool.execute(Map.of("questions", List.of()), context());
        assertFalse(result.success());
        assertEquals("questions must be a non empty array of {id, prompt, options, multiple}", result.output());
    }

    @Test
    void executeReturnsErrorForInvalidQuestion() {
        final ToolResult result = tool.execute(Map.of("questions", List.of("not an object")), context());
        assertFalse(result.success());
        assertEquals("each question must be an object with an id and a prompt", result.output());
    }

    @Test
    void executeReportsUserRejectionAsSuccessWithCancelSignal() {
        // a cancelled/declined ask_user (the interactive gate returned no answer) must be a
        // SUCCESS tool outcome carrying a clear "cancelled" signal - not an error - so the turn
        // advances and the model adapts instead of retrying ask_user.
        final var rejecting = new ToolContext(
                "s1",
                null,
                null,
                null,
                new UserFeedbackService() {
                    @Override
                    public Map<String, Object> ask(final List<Question> questions) {
                        return null;
                    }
                },
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
        final var args = Map.<String, Object>of("questions", List.of(Map.of("id", "q1", "prompt", "Pick?")));
        final ToolResult result = tool.execute(args, rejecting);
        assertNotNull(result);
        assertTrue(result.success(), "a user rejection is a successful outcome, not an error");
        assertTrue(result.output().contains("\"cancelled\":true"), "must carry a cancelled marker");
        assertTrue(result.output().contains("Do not call"), "must tell the model not to re-ask");
    }

    @Test
    void executeReturnsErrorWhenNoFeedbackGateIsInstalled() {
        // genuinely gate-less (no interactive feedback service): keeps the hard error.
        final var gateLess = new ToolContext(
                "s1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Cancellations.NEVER,
                null,
                null,
                testWorkdir,
                new DisabledSandbox(testWorkdir));
        final var args = Map.<String, Object>of("questions", List.of(Map.of("id", "q1", "prompt", "Pick?")));
        final ToolResult result = tool.execute(args, gateLess);
        assertNotNull(result);
        assertFalse(result.success());
        assertEquals("ask_user requires an active session feedback gate.", result.output());
    }
}
