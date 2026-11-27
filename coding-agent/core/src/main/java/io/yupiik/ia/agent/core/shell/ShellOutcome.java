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
package io.yupiik.ia.agent.core.shell;

/**
 * Typed result of a slash command action. Actions return data instead of
 * touching the terminal/UI themselves, which keeps them testable headless and
 * lets any shell render them its own way. Interactions needing a user response
 * (pick, confirm) go through {@link ShellIO}, not through an outcome.
 */
public sealed interface ShellOutcome {
    record None() implements ShellOutcome {}

    record Status(String message) implements ShellOutcome {}

    record Transcript(String markdown) implements ShellOutcome {}

    record Error(String message) implements ShellOutcome {}

    record Quit() implements ShellOutcome {}

    static ShellOutcome none() {
        return new None();
    }

    static ShellOutcome status(final String message) {
        return new Status(message);
    }

    static ShellOutcome transcript(final String markdown) {
        return new Transcript(markdown);
    }

    static ShellOutcome error(final String message) {
        return new Error(message);
    }

    static ShellOutcome quit() {
        return new Quit();
    }

    /** Routes this outcome to the rendering channels of a frontend. */
    default void applyTo(final ShellFrontend frontend) {
        switch (this) {
            case Status(String message) -> frontend.status(message);
            case Transcript(String markdown) -> frontend.transcript(markdown);
            case Error(String message) -> frontend.error(message);
            case Quit quit -> {}
            case None none -> {}
        }
    }
}
