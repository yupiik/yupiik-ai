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
 * Reads a line from the user terminal. The base implementation is a plain
 * {@code stdin} read (no line editing) while richer implementations - enabled
 * when the terminal widget stack is on the classpath - can provide history and
 * key bindings.
 * <p>
 * The bean is looked up by priority so an implementation can override the
 * default one, see {@code io.yupiik.fusion.framework.build.api.order.Order}.
 */
public interface Prompt {
    /**
     * @param prompt text to display before reading, may be blank.
     * @return the line entered by the user (without its line terminator) or
     *         empty when the input reaches its end (Ctrl-D).
     * @throws PromptInterruptException when the read is interrupted (Ctrl-C).
     * @throws IOException              when the underlying stream fails.
     */
    Optional<String> readLine(String prompt) throws IOException;

    /**
     * @return {@code true} when the prompt supports rich line edition
     *         (history, word motion, ...).
     */
    default boolean editing() {
        return false;
    }

    /**
     * Whether this prompt can detect a standalone {@code Esc} press while a
     * turn is running without blocking. Only a backend with a genuinely
     * pollable reader may return {@code true}: a backend whose read parks
     * until input arrives would hide the next prompt until the user types,
     * so it must return {@code false} and keep the blocking turn wait.
     */
    default boolean isInterruptAvailable() {
        return false;
    }

    /**
     * Non-blocking probe for a standalone {@code Esc} press, consuming it when
     * present. Escape sequences (arrows, Alt-keys) are consumed and ignored so
     * they never leak into the next line edit.
     *
     * @return {@code true} after a standalone {@code Esc} was detected.
     */
    default boolean pollInterrupt() {
        return false;
    }

    /**
     * The write path the surrounding REPL must use for every chunk it prints.
     * Line-editing backends (jline) return a sink that decides - atomically
     * with the read lifecycle - between a raw terminal write and a
     * {@code printAbove} + prompt repaint, so external output can never
     * desynchronize the prompt display; the plain {@code stdin} backend has no
     * in-place display and returns {@link PromptOutput#raw()}.
     * <p>
     * Never {@code null}: callers must not need a fallback branch, as choosing
     * one themselves is precisely the race this sink removes.
     *
     * @return the sink routing the REPL output for this backend.
     */
    default PromptOutput externalOutput() {
        return PromptOutput.raw();
    }
}
