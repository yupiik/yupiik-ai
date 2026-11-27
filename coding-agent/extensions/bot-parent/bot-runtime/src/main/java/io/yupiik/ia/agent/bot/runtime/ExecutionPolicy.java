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
package io.yupiik.ia.agent.bot.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Launcher-owned authority, never derived from an attempt name or agent configuration. */
public record ExecutionPolicy(String applicationId, Gateway gateway) {
    public static final String RUNTIME_LABEL = "io.yupiik.yca.bot.runtime";
    public static final String COMPONENT_LABEL = "io.yupiik.yca.bot.component";
    private static final Pattern APPLICATION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    public ExecutionPolicy {
        if (applicationId == null || !APPLICATION.matcher(applicationId).matches()) {
            throw new IllegalArgumentException("Explicit application identity required");
        }
        Objects.requireNonNull(gateway, "Explicit gateway contract required");
    }

    public List<String> labels(final String runtimeId, final String component) {
        return List.of(
                "--label",
                "app=" + applicationId,
                "--label",
                RUNTIME_LABEL + '=' + runtimeId,
                "--label",
                COMPONENT_LABEL + '=' + component);
    }

    public String ownership(final String runtimeId, final String component) {
        return applicationId + '|' + runtimeId + '|' + component;
    }

    public static String inspectFormat(final boolean network) {
        final var labels = network ? ".Labels" : ".Config.Labels";
        return "{{index " + labels + " \"app\"}}|{{index " + labels + " \"" + RUNTIME_LABEL + "\"}}|{{index " + labels
                + " \"" + COMPONENT_LABEL + "\"}}";
    }

    public record Gateway(String mainClass, Set<String> allowedTokens, Set<String> requiredTokens) {
        private static final Pattern MAIN_CLASS =
                Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
        private static final Pattern VARIABLE = Pattern.compile("[A-Z_][A-Z0-9_]*");

        public Gateway {
            if (mainClass == null
                    || !MAIN_CLASS.matcher(mainClass).matches()
                    || allowedTokens == null
                    || requiredTokens == null
                    || !allowedTokens.containsAll(requiredTokens)
                    || allowedTokens.stream()
                            .anyMatch(it -> it == null || !VARIABLE.matcher(it).matches())) {
                throw new IllegalArgumentException("Explicit bounded gateway contract required");
            }
            // Stable ordering is also part of durable recovery fingerprints.
            allowedTokens = Collections.unmodifiableSet(new TreeSet<>(allowedTokens));
            requiredTokens = Collections.unmodifiableSet(new TreeSet<>(requiredTokens));
        }
    }
}
