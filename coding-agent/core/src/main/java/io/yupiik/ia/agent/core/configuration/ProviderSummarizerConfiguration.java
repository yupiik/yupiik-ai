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
 * Per-provider summarizer input-cap override. Every field is nullable: a value below zero (the
 * default) inherits the global {@link SummarizerConfiguration} value of the {@link CompactionConfiguration}.
 * See {@link SummarizerConfiguration} for the field meaning.
 */
@JsonModel
public record ProviderSummarizerConfiguration(
        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider summarizer chunk cap in chars; a negative value (the default) inherits the global yca.compaction.summarizer.chunkChars.")
        Integer chunkChars,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider summarizer chunk floor in chars; a negative value (the default) inherits the global yca.compaction.summarizer.minChunkChars.")
        Integer minChunkChars,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider summarizer digest cap in chars; a negative value (the default) inherits the global yca.compaction.summarizer.digestChars.")
        Integer digestChars,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider per-tool-output cap in chars; a negative value (the default) inherits the global yca.compaction.summarizer.toolOutputChars.")
        Integer toolOutputChars) {}
