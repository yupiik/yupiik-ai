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
package io.yupiik.ia.agent.core.tools.builtin.bashoverride;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Index of the {@link JavaCommand} reimplementations registered via DI ({@code List<JavaCommand>}, one bean per
 * command - see {@code OutputProcessor} for the same collection-injection idiom). Lookups are by the shell command
 * name ({@code cat}, {@code ls}...).
 */
@ApplicationScoped
public class JavaCommandRegistry {
    private final Map<String, JavaCommand> byName;

    public JavaCommandRegistry(final List<JavaCommand> commands) {
        final var map = new LinkedHashMap<String, JavaCommand>();
        for (final var command : commands) {
            map.put(command.name(), command);
        }
        this.byName = Map.copyOf(map);
    }

    protected JavaCommandRegistry() {
        this.byName = Map.of();
    }

    public Optional<JavaCommand> get(final String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }
}
