/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.model.fusion.OpenRpc;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the description of a skill comes from: the OpenRPC documents the Fusion annotation processor generates.
 * <p>
 * All of them are read and merged, not just the first one on the classpath - there is one per module using the processor,
 * so reading a single resource would drop the methods of every other module depending on the classpath ordering.
 */
@FusionSupport
class OpenRpcServiceTest {
    @Test
    void theA2AMethodsAreDescribed(@Fusion final JsonMapper jsons) {
        final var document = new OpenRpcService(jsons).load();

        // this module generates one document, and it describes the eleven A2A operations
        assertNotNull(document.methods());
        assertTrue(
                document.methods().containsKey(A2AProtocol.SEND_MESSAGE),
                () -> String.valueOf(document.methods().keySet()));
        assertTrue(document.methods().containsKey(A2AProtocol.LIST_TASKS));
        assertEquals(
                "Sends a message to the agent to create, continue or restart a task.",
                document.methods().get(A2AProtocol.SEND_MESSAGE).description(),
                "the description is the @JsonRpc documentation, so it cannot drift from the method");
    }

    @Test
    void theSharedSchemasAreOptional(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);

        assertNotNull(service.schemas(service.load()));
        // a document is free to describe no shared schema at all, and a null must not reach the caller
        assertEquals(Map.of(), service.schemas(new OpenRpc(null, null)));
        assertEquals(Map.of(), service.schemas(new OpenRpc(Map.of(), Map.of())));
    }

    @Test
    void everyDocumentOnTheClasspathIsMerged(@Fusion final JsonMapper jsons, @TempDir final Path work)
            throws Exception {
        // a module contributing a document with neither schemas nor methods: it must not blank what the others describe
        final var contributed = write(work, "{}");
        final var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contributed);
        try {
            final var document = new OpenRpcService(jsons).load();

            assertTrue(
                    document.methods().containsKey(A2AProtocol.SEND_MESSAGE), () -> String.valueOf(document.methods()));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void aDocumentWhichIsNotOneIsReported(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        // a broken resource is a build accident: failing at startup beats publishing a card describing nothing
        for (final var content : List.of("{ not json", "null", "")) {
            final var contributed = write(work, content);
            final var previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(contributed);
            try {
                final var service = new OpenRpcService(jsons);

                assertThrows(RuntimeException.class, service::load, () -> "'" + content + "' should be refused");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    @Test
    void aClasspathWhichCannotBeReadIsReported(@Fusion final JsonMapper jsons) {
        final var previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(previous) {
            @Override
            public Enumeration<URL> getResources(final String name) throws IOException {
                throw new IOException("the classpath is gone");
            }
        });
        try {
            final var service = new OpenRpcService(jsons);

            final var error = assertThrows(IllegalStateException.class, service::load);
            assertEquals("the classpath is gone", error.getCause().getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void theProtectedConstructorIsUsableBySubclasses() {
        // the container subclasses the bean to proxy it, so the no-argument constructor must not blow up
        assertEquals(Map.of(), new OpenRpcService(null) {}.schemas(new OpenRpc(null, null)));
    }

    /**
     * @param work    where to lay the resource out.
     * @param content the document to contribute.
     * @return a loader offering it on top of the real classpath, i.e. one more module contributing a document.
     */
    private static ClassLoader write(final Path work, final String content) throws IOException {
        final var location = work.resolve("META-INF/fusion/jsonrpc/openrpc.json");
        Files.createDirectories(location.getParent());
        Files.writeString(location, content);
        return new URLClassLoader(
                new URL[] {work.toUri().toURL()}, Thread.currentThread().getContextClassLoader());
    }
}
