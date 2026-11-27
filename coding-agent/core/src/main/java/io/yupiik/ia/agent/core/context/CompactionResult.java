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
package io.yupiik.ia.agent.core.context;

import io.yupiik.ia.agent.core.llm.Message;
import java.util.List;

/**
 * The outcome of a prune or compact operation, including which strategy ran so
 * frontends can report the truth instead of implying an LLM summarization
 * happened when it fell back to a local truncation.
 *
 * @param messages the resulting message list (never the caller's original list
 *                 unless {@code mode} is {@link Mode#NONE})
 * @param mode how the context was reduced
 * @param error a human-readable note when a fallback or failure occurred
 */
public record CompactionResult(List<Message> messages, Mode mode, String error) {

    public enum Mode {
        // ensure to sync doc in documentation/src/main/minisite/content/features/context.adoc
        /** Nothing needed to be (or could be) done. */
        NONE,
        /** The LLM summarizer produced the reduced context. */
        SUMMARY,
        /** The summarizer failed and a deterministic local truncation ran. */
        TRUNCATE,
        /** Old tool outputs were cleared locally (no LLM call). */
        CLEARED,
        /** Old verbose tool outputs were spliced locally, keeping their head and tail (no LLM call). */
        SHRUNK
    }

    public boolean changed() {
        return mode != Mode.NONE;
    }
}
