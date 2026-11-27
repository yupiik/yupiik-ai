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
package io.yupiik.ia.agent.github.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import java.net.URI;

/** Host-only settings, deliberately independent of the persisted executor fingerprint. */
@RootConfiguration("github.context")
public record ContextConfiguration(
        @Property(defaultValue = "\"127.0.0.1\"", documentation = "JRE HTTP bind address; no implicit agent routing.")
        String bind,

        @Property(
                defaultValue = "0",
                documentation =
                        "Listener port; zero selects a random port. Set a dedicated port per concurrent worker behind a static HTTPS terminator.")
        int port,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Required explicit HTTPS origin when using the context service, without credentials, query, fragment or non-root path. Route to this exact worker, not a random worker load balancer.")
        String advertised,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Private owner-only host context storage root; empty uses github.home/context. Keep outside agent attempt mounts and never mount in agents.")
        String storageRoot,

        @Property(
                defaultValue = "\"git\"",
                documentation = "Trusted host Git executable, including HTTPS and HTTP-backend helpers.")
        String git,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Exact comma-separated additional repository grants. Empty denies additional repositories; primary and verified PR head remain available.")
        String repositories,

        @Property(
                defaultValue = "256",
                documentation =
                        "Maximum authenticated requests shared by filesystem, HTTP control and native Git context adapters per attempt, from 1 to 4096.")
        int maxRequests,

        @Property(
                defaultValue = "1073741824L",
                documentation =
                        "Maximum response bytes shared by all context adapters per attempt, from 1 MiB to 16 GiB.")
        long maxResponseBytes,

        @Property(
                defaultValue = "67108864L",
                documentation =
                        "Maximum bytes for Git compatibility HTTP buffers, from 1 to 2147483639 (Java array-safe bound). Bounds request input and buffered responses, not streamed responses; size for the worker heap.")
        long maxHttpBufferBytes) {
    public ContextConfiguration {
        if (bind == null
                || bind.isBlank()
                || port < 0
                || port > 65535
                || advertised == null
                || storageRoot == null
                || git == null
                || git.isBlank()
                || repositories == null
                || maxRequests < 1
                || maxRequests > 4096
                || maxResponseBytes < 1024L * 1024
                || maxResponseBytes > 16L * 1024 * 1024 * 1024
                || maxHttpBufferBytes < 1
                || maxHttpBufferBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("Invalid GitHub context settings");
        }
        if (!advertised.isEmpty()) {
            origin(advertised);
        }
    }

    public static URI origin(final String value) {
        final var uri = URI.create(value);
        if (!"https".equals(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("Context advertised endpoint must be an explicit HTTPS origin");
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }
}
