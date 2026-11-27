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

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Descriptor of how a model embeds its reasoning in the text {@code content} channel. It is a
 * record (not an enum with one constant per model) because the meaningful surface is the set of
 * delimiter flags, not a closed list of model families; named presets cover the known families and
 * a custom descriptor can be assembled if a new one appears.
 *
 * @param open the opening reasoning marker, or {@code null}/blank when the model reasons without an
 *        opening tag (eg. DeepSeek-R1 starts inside reasoning).
 * @param close the closing reasoning marker.
 * @param force whether the content must be treated as already inside reasoning until the first
 *        {@code close} (models that either omit the open tag or whose template pre-writes it).
 * @param toolStart an additional delimiter that ends reasoning even without {@code close}
 *        (eg. {@code <tool_call>} on Qwen/Nemotron, which opens the tool channel mid-sentence).
 */
@JsonModel
public record ReasoningFormat(String open, String close, boolean force, String toolStart) {

    /** GLM/Qwen style whole-line reasoning tags: {@code  think ...} lines, optionally a leading space. */
    public static ReasoningFormat thinkTags() {
        return new ReasoningFormat("think", null, false, null);
    }

    /** DeepSeek-R1: reasons from the start with no open tag until the {@code  response} marker. */
    public static ReasoningFormat deepSeekR1() {
        return new ReasoningFormat(null, " response", true, null);
    }

    /** Qwen/Nemotron: {@code  think ... response} with an implicit reasoning close when the tool channel opens. */
    public static ReasoningFormat qwen() {
        return new ReasoningFormat("think", " response", false, "<tool_call>");
    }
}
