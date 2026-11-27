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
package io.yupiik.ia.agent.web;

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.http.server.spi.Endpoint;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Host/Origin guard for {@code POST /api/rpc}. {@link JsonRpcEndpoint} matches by priority 1000;
 * this guard registers at a higher priority and forwards only proven-loopback requests, so a
 * DNS-rebinding or cross-site attack never reaches the JSON-RPC handler (which would otherwise
 * happily execute {@code session.prompt} against the local agent). The SSE endpoint and the
 * static asset handler apply the same {@link HttpOriginGuard} checks in their own {@code handle}.
 */
class RpcOriginGuardEndpoint implements Endpoint {
    private final Endpoint delegate;
    private final String boundHost;

    RpcOriginGuardEndpoint(final Endpoint delegate, final String boundHost) {
        this.delegate = delegate;
        this.boundHost = boundHost;
    }

    @Override
    public int priority() {
        return delegate.priority() + 1; // win the dispatch over the JSON-RPC endpoint
    }

    @Override
    public boolean matches(final Request request) {
        return delegate.matches(request);
    }

    @Override
    public CompletionStage<Response> handle(final Request request) {
        final var rejected = HttpOriginGuard.rejectIfForeign(request, boundHost);
        if (rejected != null) {
            return completedFuture(rejected);
        }
        return delegate.handle(request);
    }
}

/**
 * As {@link RpcOriginGuardEndpoint}, but standing in front of a whole list of endpoints: used by
 * {@link JreAccessLogger} when access logging is enabled (the logger is then the single dispatcher),
 * so the guard still runs before any real endpoint. Package-private helper kept in the same file as
 * its single-endpoint sibling.
 */
final class DelegatingOriginGuardEndpoint implements Endpoint {
    private final List<Endpoint> delegates;
    private final String boundHost;

    DelegatingOriginGuardEndpoint(final List<Endpoint> delegates, final String boundHost) {
        // highest-priority first, the dispatch order used by the JRE server and JreAccessLogger
        this.delegates = delegates.stream()
                .sorted((a, b) -> Integer.compare(b.priority(), a.priority()))
                .toList();
        this.boundHost = boundHost;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean matches(final Request request) {
        return true;
    }

    @Override
    public CompletionStage<Response> handle(final Request request) {
        final var rejected = HttpOriginGuard.rejectIfForeign(request, boundHost);
        if (rejected != null) {
            return completedFuture(rejected);
        }
        return delegates.stream()
                .filter(e -> e.matches(request))
                .findFirst()
                .map(e -> e.handle(request))
                .orElseGet(() -> completedFuture(
                        Response.of().status(404).body("Not Found").build()));
    }
}
