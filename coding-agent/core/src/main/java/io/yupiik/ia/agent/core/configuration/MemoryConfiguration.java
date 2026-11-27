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

@JsonModel
public record MemoryConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Automatically extract durable knowledge from conversations into memory files.")
        boolean enabled,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.MemoryMode.AUTO",
                documentation = "When the durable memory is flushed to disk for the next session. AUTO picks from the "
                        + "routing: the memory model resolving to the same model as the current turn selects "
                        + "LAZY (extraction is expensive: only at a todo milestone or session close), a "
                        + "distinct/cheaper memory model selects FULL (the every-N-turns cadence plus the "
                        + "todo-completion flush). FULL and LAZY force either extreme. The prompt prefix is "
                        + "built once per session and never re-read mid-session, so this governs only the "
                        + "durability cadence, not what the current turn sees.")
        MemoryMode mode,

        @Property(
                defaultValue = "2000",
                documentation = "Maximum number of characters of memory injected in the system prompt.")
        int maxChars,

        @Property(
                defaultValue = "0.2",
                documentation =
                        "Fraction of the resolved system prompt budget (provider/model aware) used as the memory "
                                + "injection budget: the effective budget is min(maxChars, resolvedSystemPromptCap * maxRatio). "
                                + "0 disables the ratio (plain maxChars). When the resolved budget falls below 1000 characters "
                                + "the memory section is auto-disabled (and extraction is skipped) so a small context window "
                                + "is never consumed by low-value memory.")
        double maxRatio,

        @Property(
                defaultValue = "true",
                documentation =
                        "Periodically rewrite the project memory with an LLM call (compact model) when it grows past "
                                + "64 KiB: near-duplicates are merged, superseded and one-off facts dropped. A "
                                + "backup (MEMORY.md.bak) is kept and rewrites reducing the file by less than 20% are "
                                + "discarded.")
        boolean compactEnabled,

        @Property(
                defaultValue = "1000",
                documentation =
                        "Minimum number of characters of new (not yet extracted) user/assistant text required to "
                                + "trigger an end-of-turn memory extraction. Chit-chat below this threshold is skipped: it "
                                + "would only produce an empty facts array while still costing an LLM call. The forced "
                                + "extraction at session close ignores this threshold (but still honors the per-session "
                                + "watermark, so it never re-processes already extracted messages).")
        int extractMinChars,

        @Property(
                defaultValue = "200",
                documentation =
                        "Deterministic per-scope memory cap (number of bullets). Beyond it, older bullets are dropped "
                                + "on write - a cheap, model-free floor that prevents unbounded growth even when the "
                                + "LLM-based compaction (compactEnabled) is disabled or below its size threshold. The "
                                + "selection ranker still picks the most relevant bullets for the prompt, so a full "
                                + "cap does not mean the model sees everything - only that the store does not grow "
                                + "forever. Applies to the global memory file and to the project memory file.")
        int maxLines,

        @Property(
                defaultValue = "50",
                documentation =
                        "Per-scope cap on the number of `p==5` \"keep\" bullets (explicit directives). Keeps the "
                                + "protected tier bounded: when the keep count exceeds this, the oldest keep bullet is "
                                + "demoted to p4 (not dropped) before the normal eviction runs. 0 disables the cap.")
        int maxKeepLines,

        @Property(
                defaultValue = "2",
                documentation =
                        "Default importance weight (1..5, 5 = explicit keep directive) assigned to a memory bullet "
                                + "when it carries no metadata (plain extracted facts, hand-written lines). The selection "
                                + "ranker multiplies relevance by this weight, and pruning evicts lowest-weight first.")
        int defaultPriority,

        @Property(
                defaultValue = "3",
                documentation = "Run the end-of-turn memory extraction only every N completed user turns "
                        + "(0 or negative disables the periodic extraction entirely: extraction then runs "
                        + "only on the todo-completion flush or the forced session-close pass). Skipped turns "
                        + "do not advance the extraction watermark, so their delta is picked up by the next "
                        + "eligible turn - nothing is lost, extraction never re-processes already-mined text.")
        int extractEveryTurns,

        @Property(
                defaultValue = "true",
                documentation = "Additionally run the end-of-turn memory extraction when a todo item transitions into "
                        + "completed/cancelled during the turn (a natural milestone flush), even when the "
                        + "extractEveryTurns cadence has not been reached. The finished-set comparison is "
                        + "membership-based, so todos added mid-turn cannot fake a completion.")
        boolean extractOnTodosDone) {}
