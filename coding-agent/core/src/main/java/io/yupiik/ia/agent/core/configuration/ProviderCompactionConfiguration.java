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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Per-provider overrides of the compaction resilience knobs. A null/non-positive field inherits the
 * global {@link CompactionConfiguration} / {@link LlmConfiguration} value, so an unset block keeps
 * the historical behavior. Each group ({@code buffer}, {@code gate}, {@code summarizer},
 * {@code speculative}, {@code tail}, {@code contextLimit}) is a nullable record whose per-field
 * management mirrors the global shape: a null field inside a group inherits the global value.
 */
@JsonModel
public record ProviderCompactionConfiguration(
        @Property(
                defaultValue = "-1",
                documentation = "Per-provider maximum retry attempts for 429/5xx and network errors on the compaction "
                        + "summarizer path. Zero or negative (the default) falls back to the global "
                        + "yca.llm.retriesMaxAttempts.")
        int retriesMaxAttempts,

        @Property(
                defaultValue = "-1",
                documentation = "Per-provider base delay in milliseconds of the exponential backoff (with jitter) for "
                        + "compaction summarizer retries. Zero or negative (the default) falls back to the "
                        + "global yca.llm.retriesBaseDelayMs.")
        int retriesBaseDelayMs,

        @Property(
                defaultValue = "-1",
                documentation = "Per-provider client side requests per minute throttle for the compaction summarizer. "
                        + "Zero or negative (the default) falls back to the global "
                        + "yca.llm.rateLimitRequestsPerMinute.")
        int rateLimitRequestsPerMinute,

        @Property(
                defaultValue = "null",
                documentation =
                        "Whether a compaction summarizer that exhausts all its retries on a transient 5xx/network "
                                + "failure falls back to the provider's main (conversation) model for one last "
                                + "attempt. Null (the default) inherits the global yca.compaction.fallbackToMainModel; "
                                + "true/false override it.")
        Boolean fallbackToMainModel,

        @Property(
                documentation = "Per-provider buffer-mode trigger override. Null fields inherit the global "
                        + "yca.compaction.buffer.* values. See {@link ProviderBufferConfiguration}.")
        ProviderBufferConfiguration buffer,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.CompactionLlmMode.AUTO",
                documentation = "Per-provider compaction LLM mode: \"auto\" (the default) inherits the global "
                        + "yca.compaction.mode; \"llm\" forces the LLM-backed compaction for this provider "
                        + "even when the global mode is \"no_llm\"; \"no_llm\" forces the LLM-free compaction "
                        + "even when the global mode is \"llm\". The tri-state keeps the explicit opt-out of "
                        + "a global \"no_llm\" expressible (a nullable Boolean cannot: the framework "
                        + "materializes a default-filled per-provider block, binding an absent Boolean to "
                        + "false).")
        CompactionLlmMode mode,

        @Property(
                documentation = "Per-provider compaction-gate budget override. Null fields inherit the global "
                        + "yca.compaction.gate.* values. See {@link ProviderGateConfiguration}.")
        ProviderGateConfiguration gate,

        @Property(
                documentation = "Per-provider verbatim recent-tail override. Null fields inherit the global "
                        + "yca.compaction.tail.* values. See {@link ProviderTailConfiguration}.")
        ProviderTailConfiguration tail,

        @Property(
                documentation = "Per-provider summarizer input-cap override. Null fields inherit the global "
                        + "yca.compaction.summarizer.* values. See {@link ProviderSummarizerConfiguration}.")
        ProviderSummarizerConfiguration summarizer,

        @Property(
                documentation = "Per-provider speculative-compaction override. Null fields inherit the global "
                        + "yca.compaction.speculative.* values. See {@link ProviderSpeculativeConfiguration}.")
        ProviderSpeculativeConfiguration speculative,

        @Property(
                documentation = "Per-provider working-context guidance limit override. Null fields inherit the global "
                        + "yca.compress.contextLimit.* values. See {@link ProviderContextLimit}.")
        ProviderContextLimit contextLimit) {}
