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
package io.yupiik.llm.client.openai.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.Map;

/**
 * OpenAI responses API streaming event: `delta` carries the text fragments,
 * `item` the added output items and `response` the full snapshots (created/completed/...).
 *
 * @param type event type, e.g. {@code response.output_text.delta}.
 * @param delta textual fragment, for delta events.
 * @param outputIndex index of the output item this event relates to.
 * @param item output item added by the event, for item events.
 * @param response response snapshot, for created/completed/failed events.
 * @param extensions unknown wire fields kept as-is.
 */
@JsonModel
public record ResponsesStreamEvent(
        String type,
        String delta,
        @JsonProperty("output_index") Integer outputIndex,
        ResponsesResponse.OutputItem item,
        ResponsesResponse response,
        @JsonOthers Map<String, Object> extensions) {}
