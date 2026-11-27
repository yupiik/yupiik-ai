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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.cli.PromptHandlebarsHelper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.LabelsConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders the speaker labels used in terminal transcript headers (CLI, TUI, run).
 *
 * <p>All templates from {@code yca.labels} are compiled <em>once</em> at bean
 * construction - exactly once per application lifecycle, like
 * {@link io.yupiik.ia.agent.core.cli.CliPromptTemplate} - and rendered at each
 * turn boundary with a fresh context map. Null/blank context values are skipped
 * (never present as {@code null} keys), so templates can use
 * {@code {{#if agent}}} safely. A template rendering blank falls back to the
 * defaults (OS user name for the user label, "Assistant" for the assistant).</p>
 *
 * <p>Strictly a UI feature: the LLM conversation always keeps its hardcoded
 * {@code user}/{@code assistant} roles (see {@link io.yupiik.ia.agent.core.llm.Message}),
 * these labels only decorate the rendered transcript.</p>
 */
@ApplicationScoped
public class SpeakerLabels {
    private final Template userTemplate;
    private final Template assistantTemplate;
    private final Template logsTemplate;
    private final ChatClient chatClient;
    // Compiled helpers have no root-context accessor in Fusion 1.2.0. Scope the
    // captured instant to this render, never to the application-wide date helper.
    private final ThreadLocal<Ctx> rendering = new ThreadLocal<>();

    /** Bean proxy constructor: leaves the real fields unset, the active instance is created by the injection point. */
    protected SpeakerLabels() {
        this.userTemplate = null;
        this.assistantTemplate = null;
        this.logsTemplate = null;
        this.chatClient = null;
    }

    public SpeakerLabels(final CodingAgentConfiguration configuration, final ChatClient chatClient) {
        this(List.of(), configuration, chatClient);
    }

    public SpeakerLabels(
            final List<PromptHandlebarsHelper> helpers,
            final CodingAgentConfiguration configuration,
            final ChatClient chatClient) {
        this.chatClient = chatClient;
        final var labels = configuration == null ? null : configuration.labels();
        final var user = labels == null ? null : labels.userTemplate();
        final var assistant = labels == null ? null : labels.assistantTemplate();
        final var logs = labels == null ? null : labels.logsTemplate();
        final var functions = new LinkedHashMap<String, Function<HelperContext, Object>>();
        functions.put("systemProperty", PromptHandlebarsHelper.systemProperty());
        for (final var helper : helpers) {
            functions.put(
                    helper.name(),
                    helper instanceof FormatDatePromptHelper date
                            ? context -> {
                                final var ctx = rendering.get();
                                return ctx != null && !ctx.currentTime()
                                        ? date.applyAt(context, ctx.timestamp())
                                        : date.apply(context);
                            }
                            : helper::apply);
        }
        final var compiler = new HandlebarsCompiler();
        final var logsSource = logs == null ? "" : logs;
        // Validate the leaf first, with no partials: even a self-reference must fail
        // before the source is registered for the speaker templates.
        this.logsTemplate =
                compile(compiler, new HandlebarsCompiler.Settings().helpers(functions), "logsTemplate", logsSource);
        final var settings = new HandlebarsCompiler.Settings()
                .helpers(functions)
                .partials(Map.of("logsTemplate", logsSource))
                .cachePartials(true);
        this.userTemplate = compile(compiler, settings, "userTemplate", user);
        this.assistantTemplate = compile(compiler, settings, "assistantTemplate", assistant);
    }

    private static Template compile(
            final HandlebarsCompiler compiler,
            final HandlebarsCompiler.Settings settings,
            final String name,
            final String template) {
        try {
            return compiler.compile(
                    new HandlebarsCompiler.CompilationContext(settings, template == null ? "" : template));
        } catch (final IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid yca.labels." + name + ": " + invalid.getMessage(), invalid);
        }
    }

    /**
     * Context snapshot the template is rendered against. One instance is built at each
     * turn boundary; model/provider/agent are read at render time so a running session
     * always reflects the current model/provider/agent from the next written block. The
     * provider must be the session-effective one (per-session override included), resolved
     * by the caller (eg. {@link ChatClient#providerId(ChatSettings)}): this class never
     * guesses it from the process-wide current provider.
     */
    public record Ctx(
            String modelName,
            String provider,
            String agent,
            String mode,
            String sessionId,
            Map<String, Object> configuration,
            Long timestamp,
            boolean currentTime) {
        public Ctx(
                final String modelName,
                final String provider,
                final String agent,
                final String mode,
                final String sessionId,
                final Map<String, Object> configuration) {
            this(modelName, provider, agent, mode, sessionId, configuration, null, true);
        }

        /** Captured feedback time, or null for a legacy block whose time is unknown. */
        public Ctx at(final Long at) {
            return new Ctx(modelName, provider, agent, mode, sessionId, configuration, at, false);
        }
    }

    /** The rendered user label; blank renders fall back to {@link LabelsConfiguration#defaultUserLabel()}. */
    public String userLabel(final Ctx ctx) {
        return render(userTemplate, ctx, LabelsConfiguration.defaultUserLabel());
    }

    /** The rendered assistant label; blank renders fall back to {@link LabelsConfiguration#defaultAssistantLabel()}. */
    public String assistantLabel(final Ctx ctx) {
        return render(assistantTemplate, ctx, LabelsConfiguration.defaultAssistantLabel());
    }

    /** The rendered log block label; blank renders fall back to {@link LabelsConfiguration#defaultLogsLabel()}. */
    public String logsLabel(final Ctx ctx) {
        return render(logsTemplate, ctx, LabelsConfiguration.defaultLogsLabel());
    }

    private String render(final Template template, final Ctx ctx, final String fallback) {
        final var previous = rendering.get();
        rendering.set(ctx);
        try {
            final var rendered = template.render(context(ctx)).strip();
            return rendered.isBlank() ? fallback : rendered;
        } finally {
            if (previous == null) {
                rendering.remove();
            } else {
                rendering.set(previous);
            }
        }
    }

    /**
     * Builds the handlebars context map, skipping null/blank values so templates
     * never observe {@code null} entries.
     */
    private Map<String, Object> context(final Ctx ctx) {
        final var data = new LinkedHashMap<String, Object>();
        data.put("timestampKnown", ctx.currentTime() || ctx.timestamp() != null);
        put(data, "modelName", ctx.modelName());
        put(data, "provider", ctx.provider());
        put(data, "agent", ctx.agent());
        put(data, "mode", ctx.mode());
        put(data, "sessionId", ctx.sessionId());
        if (ctx.modelName() != null && !ctx.modelName().isBlank()) {
            final var model = new LinkedHashMap<String, Object>();
            put(model, "name", ctx.modelName());
            put(model, "provider", ctx.provider());
            data.put("model", model);
        }
        put(data, "configuration", ctx.configuration());
        return data;
    }

    private static void put(final Map<String, Object> map, final String key, final Object value) {
        if (value != null && !(value instanceof String s && s.isBlank())) {
            map.put(key, value);
        }
    }
}
