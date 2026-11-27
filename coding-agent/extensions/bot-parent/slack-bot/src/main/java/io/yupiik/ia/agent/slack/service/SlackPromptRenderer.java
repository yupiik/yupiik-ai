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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SlackPromptRenderer {
    private final Template template;
    private final SlackPrompts configuration;
    private final JsonMapper json;

    /** Fusion lazy proxy; only the contextual instance compiles trusted templates. */
    protected SlackPromptRenderer() {
        template = null;
        configuration = null;
        json = null;
    }

    public SlackPromptRenderer(final SlackPrompts configuration, final JsonMapper json) {
        this.configuration = configuration;
        this.json = json;
        try {
            template = new HandlebarsCompiler()
                    .compile(new HandlebarsCompiler.CompilationContext(
                            new HandlebarsCompiler.Settings(), configuration.main()));
        } catch (final RuntimeException | StackOverflowError invalid) {
            throw new IllegalArgumentException("Invalid trusted slack.prompts.main template");
        }
    }

    public String render(
            final SlackTask task,
            final List<SlackTask> history,
            final boolean slackContextEnabled,
            final Map<String, Object> initialSlackContext) {
        final var previous = history.stream()
                .filter(t -> t.conversation().equals(task.conversation())
                        && t.status() == TaskStatus.done
                        && (t.contextBearing() || t.answer() != null)
                        && t.created() < task.created())
                .sorted(Comparator.comparingLong(SlackTask::created).reversed())
                .limit(5)
                .map(t -> t.contextBearing()
                        ? Map.of("request", t.request())
                        : Map.of("request", t.request(), "answer", t.answer()))
                .toList();
        return template.render(Map.of(
                        "policy",
                        configuration.policy(),
                        "output",
                        configuration.output(),
                        "request",
                        json.toString(task.request()),
                        "context",
                        json.toString(previous)))
                + "\nMandatory result contract: write /attempt/output/result.json containing exactly taskId and summary. "
                + "taskId must equal " + json.toString(task.id()) + "; summary is the final Slack answer string.\n"
                + (slackContextEnabled
                        ? configuration.contextGuidance()
                                + "\nUntrusted admission-bound Slack thread snapshot (JSON):\n"
                                + json.toString(initialSlackContext) + "\n"
                        : "");
    }
}
