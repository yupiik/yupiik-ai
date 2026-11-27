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
 * Summarizer input-cap knobs of the automatic compaction. The global
 * {@link CompactionConfiguration#summarizer()} carries the defaults below; the per-provider
 * {@link ProviderSummarizerConfiguration} mirrors the shape with null fields meaning "inherit the
 * global value".
 */
@JsonModel
public record SummarizerConfiguration(
        @Property(
                defaultValue = "0",
                documentation = "Character cap of a single summarizer input chunk (0 = auto: a quarter of the compact "
                        + "model's usable window, floored at minChunkChars). One chunk is one LLM call, "
                        + "so large windows compact in a single call; when a summarizer call fails "
                        + "because the input is too large, the cap is halved (down to the floor) and "
                        + "compaction retried.")
        int chunkChars,

        @Property(
                defaultValue = "12000",
                documentation = "Floor of the auto-derived summarizer chunk cap and of the halving retries: the legacy "
                        + "fixed 12k-char chunk, now just a configured lower bound, so even small "
                        + "summarizer models can never receive a chunk below this.")
        int minChunkChars,

        @Property(
                defaultValue = "0",
                documentation = "Character cap of the rolling digest accumulated across chunks and injected into merge "
                        + "calls (0 = auto: half the effective chunk cap, so a merge call carries at "
                        + "most ~1.5x a chunk).")
        int digestChars,

        @Property(
                defaultValue = "2000",
                documentation = "Character cap applied to each tool output when the history is serialized into the "
                        + "summarizer input (0 disables the cap).")
        int toolOutputChars) {

    /** Default chunk cap: 0 means auto-derived from the compact model usable window. */
    public static final int DEFAULT_CHUNK_CHARS = 0;

    /** Default floor of the auto-derived chunk cap in chars (12000). */
    public static final int DEFAULT_MIN_CHUNK_CHARS = 12_000;

    /** Default digest cap: 0 means half the effective chunk cap. */
    public static final int DEFAULT_DIGEST_CHARS = 0;

    /** Default per-tool-output cap in chars (2000). */
    public static final int DEFAULT_TOOL_OUTPUT_CHARS = 2_000;

    public SummarizerConfiguration {
        if (chunkChars < 0) {
            throw new IllegalArgumentException("chunkChars must be zero or positive");
        }
        if (minChunkChars < 1) {
            throw new IllegalArgumentException("minChunkChars must be at least 1");
        }
        if (digestChars < 0) {
            throw new IllegalArgumentException("digestChars must be zero or positive");
        }
        if (toolOutputChars < 0) {
            throw new IllegalArgumentException("toolOutputChars must be zero or positive");
        }
    }
}
