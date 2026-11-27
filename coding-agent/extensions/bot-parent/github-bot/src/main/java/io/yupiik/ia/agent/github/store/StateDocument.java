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
package io.yupiik.ia.agent.github.store;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.github.model.TaskDocument;
import java.util.Map;

/** Persisted queue schema; task keys and cursor endpoint keys are intentionally dynamic. */
@JsonModel
public record StateDocument(
        @Property(documentation = "Queue schema version; currently 1.")
        Integer schemaVersion,

        @Property(documentation = "Durable tasks indexed by identifier.")
        Map<String, TaskDocument> tasks,

        @Property(documentation = "Polling watermarks and continuations indexed by endpoint.")
        Map<String, String> cursors,

        @Property(documentation = "Invocation receipts retained independently from tasks.")
        Map<String, String> receipts) {}
