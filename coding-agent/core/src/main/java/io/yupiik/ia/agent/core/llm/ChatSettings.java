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
package io.yupiik.ia.agent.core.llm;

/**
 * Immutable per-session model selection (provider, model, variant and sampling
 * overrides). One value belongs to one tab/session: it is held by the owning
 * session's agent loop (durable selection state that survives request rebuilds,
 * turns and resumes) and embedded into each {@link ChatCompletionRequest} the
 * session sends, so concurrent sessions never see each other's selection and a
 * request is self-contained.
 *
 * <p>Core-only, deliberately: the type never crosses into {@code llm-client}
 * and is never serialized on the wire ({@link LlmWire} maps the fields of
 * {@link ChatCompletionRequest} explicitly and skips the selection). Plain
 * immutable value: no scope, no thread-local, no DI.</p>
 */
public record ChatSettings(String providerId, String model, String variant, ChatClient.SamplingOverride sampling) {

    /** No override at all: the process default provider and model apply. */
    public static final ChatSettings EMPTY = new ChatSettings(null, null, null, null);

    public ChatSettings {
        providerId = normalizeProvider(providerId);
        model = normalize(model);
        variant = normalize(variant);
        sampling = sampling == null || sampling.isEmpty() ? null : sampling;
    }

    private static String normalize(final String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalizeProvider(final String value) {
        // A concrete route can target a provider literally named "default". Keep that id in
        // directly constructed request settings; only the UI convenience setter treats it as reset.
        return normalize(value);
    }

    /** A copy with the provider override replaced. Blank (or {@code "default"}) clears it. */
    public ChatSettings withProviderId(final String value) {
        final var id = normalize(value);
        return new ChatSettings("default".equalsIgnoreCase(id) ? null : id, model, variant, sampling);
    }

    /** A copy with the model override replaced. Blank resets to the provider default and drops the sampling override. */
    public ChatSettings withModel(final String value) {
        return new ChatSettings(providerId, value, variant, null);
    }

    /** A copy with the named variant replaced. Blank clears the selection. */
    public ChatSettings withVariant(final String value) {
        return new ChatSettings(providerId, model, value, sampling);
    }

    /** A copy with the sampling overrides replaced. A null (or empty) override clears them. */
    public ChatSettings withSampling(final ChatClient.SamplingOverride value) {
        return new ChatSettings(providerId, model, variant, value);
    }

    /** Drops the model-scoped overrides (model, variant, sampling); the provider id is preserved. */
    public ChatSettings withoutModelOverrides() {
        return new ChatSettings(providerId, null, null, null);
    }

    /** This selection's overrides merged over {@code base}'s (this wins per field), or {@code base} when this is empty. */
    public ChatSettings inheritFrom(final ChatSettings base) {
        if (base == null) {
            return this;
        }
        if (this.equals(EMPTY)) {
            return base;
        }
        return new ChatSettings(
                providerId != null ? providerId : base.providerId(),
                model != null ? model : base.model(),
                variant != null ? variant : base.variant(),
                sampling != null ? sampling : base.sampling());
    }
}
