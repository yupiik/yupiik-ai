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
package io.yupiik.fusion.a2a.configuration;

import static io.yupiik.fusion.a2a.protocol.A2AProtocol.JSONRPC_PATH;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

/**
 * Binds the Fusion JSON-RPC endpoint on {@code /a2a}, i.e. makes the A2A endpoint the default JSON-RPC one instead of
 * {@code /jsonrpc}.
 * <p>
 * This is only a default: a {@code ConfigurationSource} with a higher priority - or a
 * {@code -Dfusion.jsonrpc.binding=/jsonrpc} - overrides it, which is what to do to <em>also</em> expose the skills as
 * a plain JSON-RPC API. The A2A binding itself stays on {@code /a2a} in all cases, see
 * {@link io.yupiik.fusion.a2a.protocol.A2AEndpoint}.
 */
@DefaultScoped
public class JSONRPCEndpointConfiguration implements ConfigurationSource {
    @Override
    public String get(final String key) {
        return "fusion.jsonrpc.binding".equals(key) ? JSONRPC_PATH : null;
    }
}
