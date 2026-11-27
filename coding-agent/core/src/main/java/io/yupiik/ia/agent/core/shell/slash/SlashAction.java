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
package io.yupiik.ia.agent.core.shell.slash;

import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

/**
 * A slash command body. Actions receive the parsed argument and the shell
 * environment and return a typed outcome instead of touching the terminal or
 * the UI, so the same action serves every shell.
 * <p>
 * Stages are used (instead of plain values) because a few commands interact
 * with the user (pick a session, confirm a plan reload): those chain on
 * {@link ShellIO} stages and complete when the user answers. Simple commands
 * use {@link #sync(BiFunction)} which wraps an immediate result.
 */
@FunctionalInterface
public interface SlashAction {
    CompletionStage<ShellOutcome> apply(final String arg, final ShellEnv env);

    /** Wraps a synchronous action body. */
    static SlashAction sync(final BiFunction<String, ShellEnv, ShellOutcome> action) {
        return (arg, env) -> CompletableFuture.completedStage(action.apply(arg, env));
    }
}
