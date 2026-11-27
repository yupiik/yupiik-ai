/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import io.yupiik.ia.agent.core.permissions.PermissionService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/** Explicit opt-out; never selected as recovery from a failed sandbox launch. */
public final class DisabledSandbox implements Sandbox {
    private final Path tempDir;

    public DisabledSandbox(final Path tempDir) {
        this.tempDir = Objects.requireNonNull(tempDir);
    }

    @Override
    public Sandbox approvalView(final PermissionService permissions) {
        Objects.requireNonNull(permissions, "permissions");
        return this;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Path tempDir() {
        return tempDir;
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
        return new SandboxCommand(builder.start(), () -> {});
    }

    @Override
    public void revoke() {}

    @Override
    public void close() {}
}
