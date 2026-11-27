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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.SecretRedactionConfiguration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@link ShapePreservingSecretMatcher} fed by {@code yca.secrets.patterns}: any value fully
 * matched by one of the configured regex patterns is replaced with a fake of the same length
 * (shared {@code replace}). This is the arbitrary-regex counterpart to
 * {@link ConfiguredPrefixSecretMatcher}; the regexes are user-supplied so they are the only regex
 * kept here by design.
 */
@ApplicationScoped
public class RegexSecretMatcher extends ShapePreservingSecretMatcher {

    private final List<Pattern> patterns;

    public RegexSecretMatcher(final CodingAgentConfiguration configuration) {
        this.patterns = compile(configuration == null ? null : configuration.secrets());
    }

    protected RegexSecretMatcher() {
        this.patterns = List.of();
    }

    private static List<Pattern> compile(final SecretRedactionConfiguration cfg) {
        final var raw = cfg == null || cfg.patterns() == null ? List.<String>of() : cfg.patterns();
        return raw.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(Pattern::compile)
                .toList();
    }

    @Override
    public boolean matches(final String value) {
        if (value == null || patterns.isEmpty()) {
            return false;
        }
        for (final var p : patterns) {
            if (p.matcher(value).matches()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int order() {
        return 1_000;
    }

    @Override
    public String label() {
        return "configured-patterns";
    }
}
