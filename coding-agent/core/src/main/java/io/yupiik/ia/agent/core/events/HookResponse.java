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

/**
 * The <em>output</em> JSON schema a hook callback can print on stdout to drive a decision. It is documented
 * in each event's {@link YcaEvent#description()} and is <em>not</em> part of the input payload.
 *
 * <p>Contract (identical for every event):</p>
 * <ul>
 *   <li>exit {@code 0} <b>and</b> stdout {@code strip()} is empty → no action (no field applies).</li>
 *   <li>exit {@code 0} <b>and</b> stdout is a valid {@code HookResponse} JSON → the fields apply
 *       ({@code decision: "forbid"} blocks the action, {@code decision: "allow"} lets it proceed, and on
 *       model-facing events a {@code context} is appended for the model).</li>
 *   <li>stdout is invalid JSON <b>or</b> exit is non-zero <b>or</b> the callback times out → the callback
 *       "throws": an error is surfaced, no decision applies. Only valid JSON or empty output are "ok".</li>
 * </ul>
 *
 * <p>Serialized camelCase, eg. {@code {"decision":"forbid","reason":"blocked by policy"}}.</p>
 */
@JsonModel
public record HookResponse(
        /** {@code "allow"} or {@code "forbid"}; {@code null} means no decision (fields ignored). */
        HookResponseDecision decision,
        /** Reason shown when {@code decision} is {@code "forbid"}. */
        String reason,
        /** Free text appended for the model on prompt/turn events (ignored elsewhere). */
        String context) {
    @JsonModel
    public enum HookResponseDecision {
        forbid,
        allow
    }
}
