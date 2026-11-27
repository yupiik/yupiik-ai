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
 * The shared, mutable verdict holder carried by blocking hook events. Because events are records (immutable)
 * and fusion dispatch passes the <em>same instance</em> to every observer, observers mutate this holder and
 * the producer reads it once {@code emit} returns — that is how a hook forbids or allows an action.
 *
 * <p>Not a {@code @JsonModel}: it is internal plumbing, never part of the on-the-wire input payload.</p>
 */
public final class HookResult {
    private volatile boolean forbidden;
    private volatile String reason;
    private volatile String context;

    /** First forbid wins; later forbids keep the initial reason so the denial is stable across observers. */
    public HookResult forbid(final String reason) {
        if (!forbidden) {
            this.forbidden = true;
            this.reason = reason;
        }
        return this;
    }

    /** Appends free text for the model (prompt/turn events). Later appends are kept. */
    public HookResult context(final String context) {
        if (context != null && !context.isBlank()) {
            this.context = this.context == null ? context : this.context + "\n" + context;
        }
        return this;
    }

    public boolean forbidden() {
        return forbidden;
    }

    /** @return the denial reason, {@code null} when not forbidden. */
    public String reason() {
        return forbidden ? reason : null;
    }

    /** @return model-facing context to append, {@code null} when none. */
    public String context() {
        return context;
    }
}
