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
import java.util.Map;

/**
 * Renders the CLI prompt line with Handlebars.
 *
 * <p>The template is compiled once at startup; the whole configuration is also
 * exposed to the template under the {@code configuration} key so the prompt can
 * render any configured value without additional wiring.</p>
 */
@JsonModel
public record PromptTemplateConfiguration(
        @Property(
                defaultValue =
                        "\"[{{#if (eq mode \\\"plan\\\")}}{{lookup colors \\\"accent\\\"}}{{else}}{{lookup colors \\\"success\\\"}}{{/if}}{{{mode}}}{{lookup colors \\\"reset\\\"}}@{{lookup colors \\\"model\\\"}}{{{modelName}}}{{lookup colors \\\"reset\\\"}}]{{#if permission}} ({{#if (eq permission \\\"auto\\\")}}{{lookup colors \\\"warning\\\"}}{{else}}{{lookup colors \\\"status\\\"}}{{/if}}{{{permission}}}{{lookup colors \\\"reset\\\"}}){{/if}}{{#if agent}} [{{{agent}}}]{{/if}} {{#if (lte model.contextPercentage 30)}}{{lookup colors \\\"dim\\\"}}{{else if (lte model.contextPercentage 70)}}{{lookup colors \\\"warning\\\"}}{{else}}{{lookup colors \\\"error\\\"}}{{/if}}{{{model.contextPercentage}}}%{{lookup colors \\\"reset\\\"}}> \"",
                documentation =
                        "Handlebars template rendering the CLI prompt line. Placeholders: mode (plan/build), modelName (the active model), model (name, contextPercentage, contextTokens, contextWindow), permission (full_yolo/yolo/auto/ask or empty), agent (current custom agent or empty), prune, compact, color (whether ANSI color is enabled), colors (the ANSI color palette) and configuration (the whole yca configuration as a map, e.g. configuration.provider.providers.0.contextWindow). The engine provides the standard helpers (eq, ne, gt, gte, lt, lte, and, or, not, default, lookup, time) usable inline, as blocks and in sub-expressions like {{#if (lte model.contextPercentage 30)}}. systemProperty reads a system property with an optional default: {{systemProperty \"user.name\" \"You\"}} (1 argument reads the key and renders nothing when absent, 2 arguments add the default). formatDate renders the current date/time in the JVM default timezone (end-user local time, independent of the internal UTC clock): {{formatDate}} (HH:mm:ss) or {{formatDate \"pattern\"}} with any java.time pattern. Prefer {{{value}}} triple braces to render plain values unescaped, and {{lookup colors \"name\"}} to pull a color from the palette. Use {{#if agent}} / {{#if permission}} to drop empty segments. The built-in template closes every colored segment with {{lookup colors \"reset\"}} and ends with a trailing space so typed input starts unstyled. Literal helper arguments use double quotes.")
        String template,

        @Property(
                defaultValue =
                        "java.util.Map.of(\"accent\", \"\\u001b[38;5;141m\", \"success\", \"\\u001b[38;5;114m\", \"error\", \"\\u001b[38;5;203m\", \"warning\", \"\\u001b[38;5;215m\", \"status\", \"\\u001b[38;5;244m\", \"info\", \"\\u001b[38;5;253m\", \"dim\", \"\\u001b[2m\", \"bold\", \"\\u001b[1m\", \"reset\", \"\\u001b[0m\")",
                documentation =
                        "ANSI color palette exposed to the prompt template under the colors key (name to ANSI prefix; reset closes a colored segment). The model entry is added at runtime from the resolved model style. Leave empty to render the prompt without colors.")
        Map<String, String> colors) {}
