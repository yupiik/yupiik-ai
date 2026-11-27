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

import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import java.util.function.Function;

/**
 * A callable helper made available to the CLI prompt handlebars template. Each {@code @ApplicationScoped} bean
 * implementing this interface is registered under its {@link #name()} and can be invoked from the template.
 *
 * <p>The returned value is used as-is (inline and sub-expression calls are HTML-escaped by the engine, block calls
 * are not), so helpers render plain text or pre-formatted ANSI accordingly.</p>
 *
 * <p>A {@link Function} whose {@code apply} receives the {@link HelperContext} (positional arguments, {@code key=value}
 * hash and block/inverse renderers) and returns the helper output.</p>
 */
public interface PromptHandlebarsHelper extends Function<HelperContext, Object> {
    /** The helper name usable in the template, e.g. {@code {{time}}}. */
    String name();

    /** A short description used to document the helper. */
    String description();

    /**
     * The {@code systemProperty} helper as a lambda: reads a system property from 1 positional argument
     * ({@code {{systemProperty "user.name"}}}, null when the key is absent) or 2 arguments (key + default,
     * {@code {{systemProperty "user.name" "You"}}}). Registered alongside the bean helpers so it is available
     * to every template compiled with this SPI (CLI prompt line and speaker labels).
     */
    static Function<HelperContext, Object> systemProperty() {
        return context -> {
            if (context.args().isEmpty() || context.args().size() > 2) {
                throw new IllegalArgumentException("systemProperty expects 1 or 2 arguments (key [default])");
            }
            final var key = String.valueOf(context.args().getFirst());
            final var value = System.getProperty(key);
            if (value != null) {
                return value;
            }
            return context.args().size() == 2 ? String.valueOf(context.args().get(1)) : null;
        };
    }
}
