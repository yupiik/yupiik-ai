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

/**
 * Contract every hookable yca event implements.
 *
 * <p>An event exposes a serializable <em>input payload</em> — a {@code @JsonModel} record piped to the
 * configured hook callbacks on stdin as camelCase JSON. The metadata methods ({@link #description()},
 * {@link #id()}, {@link #result()}) are <em>not</em> part of that payload: fusion-json has no ignore
 * annotation (every {@code @JsonModel} record component is serialized), so anything that must not travel on
 * the wire — in particular the mutable {@link HookResult} verdict holder — lives outside the payload. The
 * splitting is explicit in each concrete event (a payload record plus the event wrapper).</p>
 */
public interface YcaEvent {

    /**
     * @return the stable event identifier used as the {@code yca.hooks.<id>} configuration key and in the
     * generated event documentation. Defaults to the simple class name (eg. {@code ToolUsePreEvent}).
     */
    default String id() {
        return getClass().getSimpleName();
    }

    /**
     * @return human and machine-readable documentation of the event: when it fires, its input payload (the
     * {@code @JsonModel} record) and the <em>output</em> JSON schema ({@link HookResponse}) a callback may
     * print on stdout to drive a decision. Rendered on the hooks documentation page by the minisite preaction.
     */
    String description();

    /**
     * @return the shared context of the session (or process) that produced the event, always populated.
     * Nullable sub-fields (model, agentId, agentName) stay null when unknown.
     */
    EventContext eventContext();

    /**
     * @return the serializable {@code @JsonModel} input payload piped on the callback's stdin. Must never
     * include the mutable {@link HookResult}.
     */
    Object payload();

    /**
     * @return the shared, mutable verdict holder the observers and callbacks drive; {@code null} for
     * non-blocking events (nothing can veto them).
     */
    HookResult result();
}
