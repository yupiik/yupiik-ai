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

/**
 * SPI for secret value detection and format-preserving replacement.
 *
 * <p>Implementations are injected into the {@link SecretRegistry} as {@code List<SecretMatcher>}
 * (fusion aggregates every {@code @ApplicationScoped} bean of this type). A matcher decides whether
 * a value <em>looks like</em> a secret of its family and, when it does, produces a plausible fake
 * that keeps the outer shape (the provider prefix, roughly the same length and alphabet) so the LLM
 * can still reason about it naturally while never seeing the real value.</p>
 *
 * <p>The registry tracks which fake it emitted (the provenance registry); inbound, only those fakes
 * are restored to the real value, so a model-invented token-shaped string passes through untouched.
 * Adding a custom matcher (or a custom {@code @OnEvent} inbound/outbound filter observer) is the
 * extension point for deployments with bespoke credential formats.</p>
 */
public interface SecretMatcher {

    /**
     * @param value a candidate string (no outer delimiters)
     * @return true when {@code value} is a secret this matcher can replace
     */
    boolean matches(String value);

    /**
     * @param value a value accepted by {@link #matches(String)}
     * @return a format-preserving fake for {@code value}, never the raw value
     */
    String replace(String value);

    /**
     * Stable sort weight for ambiguous matches (most-specific prefix first). Default 0, built-in
     * prefix matchers with longer prefixes override to a higher value so e.g. {@code sk-proj-} wins
     * over {@code sk-}. Higher = applied first in case of overlap.
     */
    default int order() {
        return 0;
    }

    /**
     * Human-readable provider/family label for diagnostics (e.g. {@code github-pat}, {@code aws-key},
     * {@code connection-uri}). Used by {@code /secrets show} without revealing any value.
     */
    default String label() {
        return getClass().getSimpleName();
    }
}
