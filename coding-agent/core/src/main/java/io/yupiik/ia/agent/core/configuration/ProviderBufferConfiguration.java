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
 * Per-provider buffer-mode trigger override. Every field is nullable: a value below zero (the
 * default) inherits the global {@link BufferConfiguration} value of the {@link CompactionConfiguration}.
 * See {@link BufferConfiguration} for the field meaning.
 */
@JsonModel
public record ProviderBufferConfiguration(
        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider buffer trigger percent; a negative value (the default) inherits the global yca.compaction.buffer.percent.")
        Integer percent,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider buffer trigger floor in tokens; a negative value (the default) inherits the global yca.compaction.buffer.minTokens.")
        Integer minTokens,

        @Property(
                defaultValue = "-1",
                documentation =
                        "Per-provider buffer safety cap percent; a negative value (the default) inherits the global yca.compaction.buffer.safetyPercent.")
        Integer safetyPercent) {}
