/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import java.util.concurrent.locks.ReentrantLock;

/** Owns the launcher (a PID namespace on Linux) as well as its remaining descendants. */
public final class SandboxCommand implements Sandbox.Command {
    private final Process process;
    private final Runnable cleanup;
    private final ReentrantLock closeLock = new ReentrantLock();
    private boolean closed;

    public SandboxCommand(final Process process, final Runnable cleanup) {
        this.process = process;
        this.cleanup = cleanup;
    }

    @Override
    public Process process() {
        return process;
    }

    @Override
    public void close() {
        // Every caller waits for completed termination, not just a request to terminate.
        closeLock.lock();
        var interrupted = false;
        try {
            if (closed) {
                return;
            }
            final var descendants = process.descendants().toList();
            process.destroyForcibly();
            descendants.reversed().forEach(ProcessHandle::destroyForcibly);
            for (; ; ) {
                try {
                    process.waitFor();
                    break;
                } catch (final InterruptedException interruption) {
                    interrupted = true;
                }
            }
            closed = true;
            cleanup.run();
        } finally {
            closeLock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
