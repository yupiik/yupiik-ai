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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

import io.yupiik.fusion.a2a.model.fusion.OpenRpc;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the OpenRPC documents the Fusion annotation processor generates, which is where the description of a
 * {@code @A2ASkill} method comes from.
 */
@ApplicationScoped
public class OpenRpcService {
    private static final String OPENRPC_LOCATION = "META-INF/fusion/jsonrpc/openrpc.json";

    private final JsonMapper jsons;

    public OpenRpcService(final JsonMapper jsonMapper) {
        this.jsons = jsonMapper;
    }

    /**
     * Loads and merges <b>all</b> the OpenRPC documents visible from the classpath.
     * <p>
     * There is one document per module using the Fusion processor - this library has one for the A2A protocol methods
     * and the application has one for its own methods - so reading a single resource would drop the methods of all the
     * other modules depending on the classpath ordering.
     *
     * @return the merged document.
     */
    public OpenRpc load() {
        final var loader = ofNullable(Thread.currentThread().getContextClassLoader())
                .orElseGet(OpenRpcService.class::getClassLoader);
        final var schemas = new HashMap<String, OpenRpc.JsonSchema>();
        final var methods = new HashMap<String, OpenRpc.JsonRpcMethod>();
        try {
            final var documents = loader.getResources(OPENRPC_LOCATION);
            while (documents.hasMoreElements()) {
                try (final var in =
                        new InputStreamReader(documents.nextElement().openStream(), UTF_8)) {
                    final var document =
                            requireNonNull(jsons.read(OpenRpc.class, in), () -> "Empty " + OPENRPC_LOCATION);
                    if (document.schemas() != null) {
                        schemas.putAll(document.schemas());
                    }
                    if (document.methods() != null) {
                        methods.putAll(document.methods());
                    }
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return new OpenRpc(schemas, methods);
    }

    /**
     * @param openRpc a document.
     * @return its shared schemas, never {@code null}.
     */
    public Map<String, OpenRpc.JsonSchema> schemas(final OpenRpc openRpc) {
        return openRpc.schemas() == null ? Map.of() : openRpc.schemas();
    }
}
