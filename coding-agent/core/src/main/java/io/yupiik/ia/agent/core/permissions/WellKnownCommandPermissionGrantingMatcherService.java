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
package io.yupiik.ia.agent.core.permissions;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.order.Order;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Built-in {@link PermissionGrantingMatcherService}: anchors well-known
 * subcommand commands ({@code git}, {@code docker}, {@code mvn}/{@code gradle},
 * {@code npm}/{@code pnpm}/{@code yarn}) as {@code [name, subcommand]} so a
 * granted {@code git commit ...} unlocks the {@code git commit} family but not
 * {@code git push}; single-token commands anchor as {@code [name]}.
 *
 * <p>Registered at the LOWEST priority ({@code Integer.MAX_VALUE}) so any
 * user-provided service (default {@link #order() 1000}) is consulted first and
 * can override or replace the built-in choices.</p>
 */
@ApplicationScoped
@Order(Integer.MAX_VALUE)
public class WellKnownCommandPermissionGrantingMatcherService implements PermissionGrantingMatcherService {

    /** Commands whose second token is a subcommand the anchor should keep. */
    static final Set<String> SUBCOMMAND_COMMANDS =
            Set.of("git", "docker", "mvn", "mvnw", "gradle", "gradlew", "npm", "pnpm", "yarn", "go", "kubectl", "gh");

    @Override
    public Optional<List<String>> grantedPermissions(final List<String> fullCommand) {
        if (fullCommand == null || fullCommand.isEmpty() || fullCommand.get(0) == null) {
            return Optional.empty();
        }
        final var name = fullCommand.get(0);
        if (!SUBCOMMAND_COMMANDS.contains(name)) {
            return Optional.empty(); // not a well-known command: let the fallback (rule prefix / first token) decide
        }
        if (fullCommand.size() >= 2
                && fullCommand.get(1) != null
                && !fullCommand.get(1).startsWith("-")) {
            return Optional.of(List.of(name, fullCommand.get(1)));
        }
        return Optional.of(List.of(name));
    }
}
