/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionSandboxWiringTest {
    @TempDir
    Path root;

    @Test
    void contextAndLoopBorrowSandboxWhileRuntimeClosesItExactlyOnce() {
        final var closes = new AtomicInteger();
        final var sandbox = new CountingSandbox(root, closes);
        final var context = ToolContext.empty(root, sandbox);
        assertSame(sandbox, context.sandbox());
        final var loopCloses = new AtomicInteger();
        final var loop = new AgentLoop(root, sandbox) {
            @Override
            public void close() {
                loopCloses.incrementAndGet();
            }
        };
        assertSame(sandbox, loop.sandbox());
        final var runtime = new SessionRuntime(
                loop, new PermissionService(root) {}, null, null, null, null, null, null, root, sandbox);
        assertSame(sandbox, runtime.sandbox());
        runtime.close();
        runtime.close();
        assertEquals(1, loopCloses.get());
        assertEquals(1, closes.get());
    }

    @Test
    void sandboxIsRequiredEvenForStandaloneContexts() {
        assertThrows(NullPointerException.class, () -> ToolContext.empty(root, null));
        assertThrows(NullPointerException.class, () -> new AgentLoop(root, null) {});
    }

    private record CountingSandbox(Path tempDir, AtomicInteger closes) implements Sandbox {
        @Override
        public Sandbox approvalView(final PermissionService permissions) {
            return this;
        }

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void requireRead(final Path path) {}

        @Override
        public void requireWrite(final Path path) {}

        @Override
        public void requireNetwork(final URI uri) {}

        @Override
        public void request(final String kind, final String target, final boolean write) {}

        @Override
        public Command launch(final ProcessBuilder builder) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke() {}

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
