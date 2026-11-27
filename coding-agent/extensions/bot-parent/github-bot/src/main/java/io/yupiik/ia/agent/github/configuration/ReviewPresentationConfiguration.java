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
package io.yupiik.ia.agent.github.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/** Markdown presentation only; correlation markers and publication policy remain host-owned. */
@RootConfiguration("github.review.presentation")
public record ReviewPresentationConfiguration(
        @Property(
                defaultValue = "\"## \\uD83D\\uDCCB Summary\\n\\n"
                        + "{{#if summary}}{{{summary}}}{{else}}No review summary was provided.{{/if}}\\n\\n"
                        + "## \\uD83D\\uDD0E Findings\\n\\n"
                        + "{{#if findings}}{{#if nativeCommentsPosted}}"
                        + "{{findingCount}} finding(s) posted as inline review comments.\\n"
                        + "{{else}}{{#each findings}}"
                        + "### {{number}}. `{{{path}}}:{{line}}` ({{side}})\\n\\n"
                        + "{{{body}}}{{newline}}{{/each}}{{/if}}\\n"
                        + "{{else}}No inline findings were reported.\\n{{/if}}\\n"
                        + "## \\uD83E\\uDDEA Tests\\n\\n"
                        + "{{#if tests}}Agent-reported test evidence (not independently verified by the host):\\n\\n"
                        + "{{#each tests}}- {{{this}}}{{/each}}"
                        + "{{else}}No test evidence was reported.{{/if}}\"",
                documentation =
                        "Review report Markdown template. Model: summary, findings (number, path, side, line, body), findingCount, nativeCommentsPosted, tests. Use triple braces for literal Markdown data. The pure {{newline}} helper preserves an explicit line break at block boundaries; Fusion each joins iterations with a newline.")
        String reportTemplate,

        @Property(
                defaultValue = "\"### {{number}}. `{{{path}}}:{{line}}` ({{side}})\\n\\n{{{body}}}\"",
                documentation =
                        "Native inline finding Markdown template. Model: number, path, side, line, body. Severity is not supplied by the finding schema; do not infer it. Use triple braces for literal Markdown data. The pure {{newline}} helper preserves an explicit line break at block boundaries; Fusion each joins iterations with a newline.")
        String findingTemplate) {}
