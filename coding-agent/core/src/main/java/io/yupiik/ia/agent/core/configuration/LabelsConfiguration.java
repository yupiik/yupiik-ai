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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Speaker labels rendered in the terminal transcript headers.
 *
 * <p>This is strictly a UI feature: the labels only decorate the rendered
 * markdown of {@code ConversationTranscript} (the {@code **label**} markers
 * before user/assistant blocks and the TUI turn headers). The LLM conversation
 * is always built from {@code Message} records with the hardcoded roles
 * {@code user}/{@code assistant} and never sees these labels.</p>
 */
@JsonModel
public record LabelsConfiguration(
        @Property(
                defaultValue = "\"{{> logsTemplate}} {{systemProperty \\\"user.name\\\" \\\"You\\\"}}\"",
                documentation =
                        "Handlebars template rendering the user speaker label shown in transcript headers (CLI, TUI, run). "
                                + "UI only: the LLM conversation keeps its hardcoded user/assistant roles and is never affected. "
                                + "Context keys: userName (System.getProperty(\"user.name\"), missing values are skipped), modelName, "
                                + "model (name, provider), provider, agent, mode (plan/build), sessionId and configuration (the whole "
                                + "yca configuration as a map). The systemProperty helper reads a system property with an optional "
                                + "default: {{systemProperty \"user.name\" \"You\"}}. The formatDate helper renders the captured block "
                                + "date/time in the JVM default timezone (end-user local time, independent of the internal UTC "
                                + "clock): {{formatDate}} uses HH:mm:ss and "
                                + "{{formatDate \"yyyy-MM-dd HH:mm:ss\"}} accepts any java.time pattern. "
                                + "The logsTemplate configuration is usable as a Handlebars partial with {{> logsTemplate}}, "
                                + "sharing this label's rendering context, captured time and helpers; the default uses it for the timestamp. "
                                + "timestampKnown is false for legacy history without captured timing; formatDate then renders empty. "
                                + "Customize logsTemplate to change the shared prefix, set it to an empty string to omit it, "
                                + "or remove the partial reference to disable the prefix for this label only.")
        String userTemplate,

        @Property(
                defaultValue =
                        "\"{{> logsTemplate}} {{#if model.name}}{{model.provider}}::{{model.name}}{{else}}Assistant{{/if}}\"",
                documentation =
                        "Handlebars template rendering the assistant speaker label shown in transcript headers (CLI, TUI, run). "
                                + "UI only: the LLM conversation keeps its hardcoded user/assistant roles and is never affected, "
                                + "changing the active model only changes what headers display. "
                                + "Context keys: userName, modelName, model (name, provider), provider, agent, mode (plan/build), "
                                + "sessionId and configuration (the whole yca configuration as a map). "
                                + "The default renders the active provider and model, e.g. openai::gpt-5-mini; when no model is "
                                + "resolved (no provider configured yet) it falls back to: Assistant. The formatDate helper is "
                                + "available exactly like in the user label (JVM default timezone): {{formatDate}} (HH:mm:ss) or "
                                + "{{formatDate \"pattern\"}} with any java.time pattern. "
                                + "The logsTemplate configuration is usable as a Handlebars partial with {{> logsTemplate}}, "
                                + "sharing this label's rendering context, captured time and helpers; the default uses it for the timestamp. "
                                + "Each new text/reasoning feedback block keeps its first-visible time, including during replay. "
                                + "timestampKnown is false for legacy history without captured timing; formatDate then renders empty. "
                                + "Customize logsTemplate to change the shared prefix, set it to an empty string to omit it, "
                                + "or remove the partial reference to disable the prefix for this label only.")
        String assistantTemplate,

        @Property(
                defaultValue = "\"{{#if timestampKnown}}[{{formatDate \\\"HH:mm:ss\\\"}}]{{/if}}\"",
                documentation =
                        "Handlebars template rendering the label of the captured log blocks shown in the TUI transcript "
                                + "content view. UI only: the LLM conversation is never affected. "
                                + "Context keys: userName, modelName, model (name, provider), provider, agent, mode (plan/build), "
                                + "sessionId and configuration (the whole yca configuration as a map). "
                                + "The formatDate helper is available exactly like in the user/assistant labels (JVM default "
                                + "timezone): {{formatDate}} (HH:mm:ss) or {{formatDate \"pattern\"}} with any java.time pattern; "
                                + "remove it to disable the timestamp. timestampKnown indicates whether a captured timestamp is known; "
                                + "the default guards the bracketed prefix with {{#if timestampKnown}} so legacy history has no timestamp. "
                                + "Also available as {{> logsTemplate}} in userTemplate and assistantTemplate, including their "
                                + "defaults. This template cannot reference any partials (including itself), preventing cycles. "
                                + "An empty string removes the shared prefix; a standalone blank log label falls back to Logs.")
        String logsTemplate) {

    /** The rendered user label when a template renders blank: the OS user name, else "You". */
    public static String defaultUserLabel() {
        final var name = System.getProperty("user.name");
        return name == null || name.isBlank() ? "You" : name;
    }

    /** The rendered assistant label when a template renders blank. */
    public static String defaultAssistantLabel() {
        return "Assistant";
    }

    /** The rendered log block label when a template renders blank. */
    public static String defaultLogsLabel() {
        return "Logs";
    }
}
