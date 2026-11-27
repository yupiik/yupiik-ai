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
package io.yupiik.ia.agent.core.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Routes low-level status messages from core components to the active frontend sink. Sinks are
 * tracked as a stack so that if two turns ever overlap (eg. a live turn plus a background
 * re-indexing turn), clearing one turn's sink does not drop the other's; {@code report} always
 * delivers to the most recently installed sink.
 */
public class StatusReporter {
    private final Deque<Consumer<String>> sinks = new ArrayDeque<>();

    public StatusReporter() {}

    public synchronized void setSink(final Consumer<String> sink) {
        if (sink == null) {
            if (!sinks.isEmpty()) {
                sinks.removeLast();
            }
            return;
        }
        sinks.addLast(sink);
    }

    public void report(final String message) {
        final Consumer<String> s;
        synchronized (this) {
            s = sinks.peekLast();
        }
        if (s != null && message != null && !message.isBlank()) {
            s.accept(message);
        }
    }
}
