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
package io.yupiik.ia.agent.core.llm;

/**
 * Signals a user-visible delay imposed by the {@link ChatClient} resilience layer: rate-limit throttling
 * or an exponential-backoff retry. Emitted so CLI / TUI front ends can surface "waiting" feedback.
 */
public record PauseEvent(Pause kind, String message, long durationMs) {

    public enum Pause {
        THROTTLE,
        RETRY
    }

    public static PauseEvent throttle(final String message, final long durationMs) {
        return new PauseEvent(Pause.THROTTLE, message, durationMs);
    }

    public static PauseEvent retry(final String message, final long durationMs) {
        return new PauseEvent(Pause.RETRY, message, durationMs);
    }
}
