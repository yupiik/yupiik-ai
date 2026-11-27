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
package io.yupiik.ia.agent.core.cli.prompt;

import java.io.IOException;
import java.util.Optional;

/**
 * A {@link Prompt} backed by a single serialized {@code stdin} reader so reads
 * can happen while a turn is running: turn/queue reads and inline interaction
 * reads (approvals, confirmations, feedback) are separate lanes, and an
 * interaction line always wins over a queued prompt when both wait at once.
 *
 * <p>The pump is only started for an interactive terminal ({@code System.console()}
 * present); piped/scripted stdin keeps the plain synchronous reads so each line
 * still runs as its own turn.
 */
public interface LinedPrompt extends Prompt {
    /**
     * Reads a line destined for an inline interaction (permission gate, confirm,
     * pick, feedback). Higher priority than a queued turn prompt.
     */
    Optional<String> readInteractionLine(String prompt) throws IOException;

    /**
     * Non-blocking drain of a line typed while the REPL was waiting inside a
     * turn; empty when none is pending.
     */
    Optional<String> pollQueue();

    /** Whether the serialized reader (and thus prompt queueing) is active. */
    boolean queuing();

    /** Releases any thread blocked on a read; used to break the REPL on quit. */
    void wakeAll();
}
