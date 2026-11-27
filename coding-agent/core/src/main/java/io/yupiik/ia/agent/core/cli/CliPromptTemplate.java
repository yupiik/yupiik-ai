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
package io.yupiik.ia.agent.core.cli;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Renders the interactive CLI prompt line from a single compiled handlebars template. The template is compiled once for
 * the whole CLI lifecycle and then re-rendered for each prompt. The global configuration is serialized once and exposed
 * to the template under the {@code configuration} key; helpers are pure functions of the {@link HelperContext} they
 * receive (the rendered value is the compiled template deciding when a palette color applies).
 */
@ApplicationScoped
public class CliPromptTemplate {
    private final Template template;
    private final List<PromptHandlebarsHelper> helpers;
    private final Map<String, Object> configuration;

    /** Bean proxy constructor: leaves the real fields unset, the active instance is created by the injection point. */
    protected CliPromptTemplate() {
        this.template = null;
        this.helpers = null;
        this.configuration = null;
    }

    /**
     * @param helpers       the available prompt helpers, collected from every {@link PromptHandlebarsHelper} bean.
     * @param configuration the global configuration (reads {@code yca.prompt.template} and serializes the whole model
     *                      for the {@code configuration} template key).
     * @param mapper        the JSON mapper used to serialize the configuration once.
     */
    public CliPromptTemplate(
            final List<PromptHandlebarsHelper> helpers,
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper) {
        this.helpers = List.copyOf(helpers);
        final Map<String, Function<HelperContext, Object>> functions = new LinkedHashMap<>();
        functions.put("systemProperty", PromptHandlebarsHelper.systemProperty());
        for (final var helper : this.helpers) {
            functions.put(helper.name(), helper::apply);
        }
        this.template = new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(functions),
                        configuration.prompt().template()));
        this.configuration = mapper.fromString(Map.class, mapper.toString(configuration));
    }

    /** Renders the prompt line for the given runtime model, merged over the frozen global configuration map. */
    public String render(final Map<String, Object> data) {
        final var model = new LinkedHashMap<>(data);
        model.put("configuration", configuration);
        return template.render(model);
    }

    /** The collected helpers (for documentation/tests). */
    public List<PromptHandlebarsHelper> helpers() {
        return helpers;
    }
}
