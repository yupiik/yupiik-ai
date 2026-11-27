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

/** Explicit session resource boundary, independent of tool consent. */
public interface Sandbox extends AutoCloseable {
    /** Borrows the same boundary and lifetime with an explicit approval route. */
    Sandbox approvalView(PermissionService permissions);

    boolean enabled();

    Path tempDir();

    void requireRead(Path path);

    void requireWrite(Path path);

    void requireNetwork(URI uri);

    /** Requests a precise capability, never re-executing a failed command. */
    void request(String kind, String target, boolean write);

    /** Immutable launch policy and an owned process tree. Closing always terminates the command. */
    Command launch(ProcessBuilder builder) throws IOException;

    /** Strict revocation: terminate all commands and tunnels before dropping dynamic grants. */
    void revoke();

    @Override
    void close();

    interface Command extends AutoCloseable {
        Process process();

        @Override
        void close();
    }
}
