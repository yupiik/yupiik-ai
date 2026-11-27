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
 * Per-provider override of the working-context guidance limits. Every field is nullable: a null
 * field inherits the global {@link ContextLimit} value of the {@link CompressConfiguration}, so an
 * unset block keeps the historical behavior. See {@link ContextLimit} for the field meaning.
 */
@JsonModel
public record ProviderContextLimit(
        @Property(
                documentation =
                        "Per-provider soft working-context limit; null inherits the global yca.compress.contextLimit.min.")
        String min,

        @Property(
                documentation =
                        "Per-provider hard working-context limit; null inherits the global yca.compress.contextLimit.max.")
        String max) {}
