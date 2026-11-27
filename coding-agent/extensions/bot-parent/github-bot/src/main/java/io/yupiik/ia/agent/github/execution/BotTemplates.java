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

import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instance-scoped trusted templates. Fusion control flow and a pure {@code newline} helper are available;
 * no application configuration, process, system property or environment helpers are installed.
 * Sources also serve as named partials. Rendered values are never compiled as templates.
 */
public final class BotTemplates {
    private final Map<String, Template> templates;

    public BotTemplates(final Map<String, String> sources) {
        final var snapshot = Map.copyOf(sources);
        final var compiler = new HandlebarsCompiler();
        final var settings = new HandlebarsCompiler.Settings()
                .helpers(Map.of("newline", ignored -> "\n"))
                .partials(snapshot)
                .cachePartials(true);
        final var compiled = new LinkedHashMap<String, Template>();
        for (final var entry : snapshot.entrySet()) {
            try {
                compiled.put(
                        entry.getKey(),
                        compiler.compile(new HandlebarsCompiler.CompilationContext(settings, entry.getValue())));
            } catch (final RuntimeException | StackOverflowError invalid) {
                // Parser exceptions can quote source text. Do not retain their message or cause.
                throw new IllegalArgumentException("Invalid trusted bot template '" + entry.getKey()
                        + "': check Handlebars syntax and partial references (including cycles)");
            }
        }
        templates = Map.copyOf(compiled);
    }

    public String render(final String name, final Map<String, Object> model) {
        final var template = templates.get(name);
        if (template == null) {
            throw new IllegalArgumentException("Unknown trusted bot template '" + name + "'");
        }
        try {
            return template.render(model);
        } catch (final RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Cannot render trusted bot template '" + name + "': check the template and its explicit model");
        }
    }
}
