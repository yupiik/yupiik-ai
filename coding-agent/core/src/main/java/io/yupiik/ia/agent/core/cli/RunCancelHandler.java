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
package io.yupiik.ia.agent.core.cli;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Installs the headless INT (ctrl-c) handler through reflection on {@code sun.misc.Signal}, so the
 * module does not need a compile-time dependency on the JDK-internal API: a first interrupt cancels
 * the running turn, a second one inside two seconds force-exits the process. Best-effort: on any
 * failure (non-Oracle JVM, restricted reflection) the default JVM behavior is kept.
 */
final class RunCancelHandler {
    private static final Logger LOGGER = Logger.getLogger(RunCancelHandler.class.getName());

    private RunCancelHandler() {
        // utility
    }

    /**
     * Installs the handler and returns the cancellation flag it flips: poll it to abort the turn.
     *
     * @param userCancelled flag shared with the caller to distinguish a user interrupt from an internal one.
     * @return the pollable cancellation flag.
     */
    static BooleanSupplier install(final AtomicBoolean userCancelled) {
        final var cancelled = new AtomicBoolean();
        final var lastInterruptAt = new long[] {0};
        try {
            final var signal = Class.forName("sun.misc.Signal");
            final var handler = Class.forName("sun.misc.SignalHandler");
            final var handle = signal.getMethod("handle", signal, handler);
            final var ctor = signal.getConstructor(String.class);
            final var interruptHandler = Proxy.newProxyInstance(
                    RunCancelHandler.class.getClassLoader(), new Class<?>[] {handler}, (_, method, _) -> {
                        if (method.getName().equals("handle")) {
                            final var now = System.currentTimeMillis();
                            final var previous = lastInterruptAt[0];
                            lastInterruptAt[0] = now;
                            if (previous > 0 && now - previous <= 2_000L) {
                                LOGGER.severe("forced exit");
                                System.exit(130);
                            }
                            cancelled.set(true);
                            userCancelled.set(true);
                            LOGGER.warning("cancelling the current turn (ctrl-c again to force exit)...");
                        }
                        return null;
                    });
            handle.invoke(null, ctor.newInstance("INT"), interruptHandler);
        } catch (final Throwable ignored) {
            // signal handling is best-effort; headless runs keep the default JVM behavior
        }
        return cancelled::get;
    }
}
