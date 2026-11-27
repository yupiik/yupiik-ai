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
package io.yupiik.ia.agent.core.events;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/**
 * Fires after context compaction resolved (wraps {@code AgentEvent.Compacted}). Observe-only; a callback may print a
 * {@code HookResponse} ack (cosmetic) but cannot block. Config key {@code yca.hooks.CompactionDone}.
 */
public record CompactionDoneEvent(EventContext eventContext, List<String> kinds, String mode) implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext, kinds, mode);
    }

    @Override
    public HookResult result() {
        return new HookResult();
    }

    @Override
    public String description() {
        return """
                Fires after context compaction completed. Config key `yca.hooks.CompactionDone`.

                Input payload (`CompactionDoneEvent.Payload`): `eventContext`, `kinds`, `mode`.
                Observe-only; output `context` is cosmetic.

                The `mode` is one of `NONE` (nothing was done), `SUMMARY` (the LLM summarizer
                produced the reduced context), `TRUNCATE` (the summarizer failed and a
                deterministic local truncation ran), `CLEARED` (old tool outputs were cleared
                locally, no LLM call) or `SHRUNK` (old verbose tool outputs were spliced to
                their head and tail, no LLM call).

                The `kinds` list reports every reduction applied in the same checkpoint, in
                execution order: `compact`, `compact-retry` or `round-compact` (LLM compaction),
                `prune` or `round-prune` (local tool-output pruning), `tool` (per-tool output
                reduction), or `dedup`, `supersede`, `purge-errors` (the zero-LLM context
                strategies, always reported with `mode` `CLEARED`). Several entries are merged
                into a single event when they run together (e.g. `["dedup", "prune"]`).""";
    }

    @JsonModel
    public record Payload(EventContext eventContext, List<String> kinds, String mode) {}
}
