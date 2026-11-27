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
package io.yupiik.ia.agent.nono;

import com.sun.net.httpserver.HttpExchange;
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.json.JsonMapper;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class ApprovalEndpoint {
    private static final Pattern JSON_CONTENT_TYPE = Pattern.compile("(?i)application/json(?:;\\s*charset=utf-8)?");

    private final PrivateState state;
    private final ApprovalCoordinator coordinator;
    private final ScheduledExecutorService timer;
    // Leave a few transport slots for retries and overload responses above the 128 unique-decision limit.
    private final Semaphore admissions = new Semaphore(132);
    private final ApprovalProtocol protocol;

    public ApprovalEndpoint(
            final PrivateState state,
            final ApprovalCoordinator coordinator,
            final ScheduledExecutorService timer,
            final JsonMapper mapper) {
        this.state = state;
        this.coordinator = coordinator;
        this.timer = timer;
        this.protocol = new ApprovalProtocol(mapper);
    }

    // Catch all routes so authentication still precedes routing and body access.
    // Inject only Request: generated JSON aggregation would bypass the upload bounds below.
    @HttpMatcher(priority = 0)
    public CompletionStage<Response> handle(final Request request) {
        final var receivedAt = System.nanoTime();
        if (!state.authenticates(request.header("authorization"))) {
            return response(401, "denied");
        }
        if (!"POST".equals(request.method()) || !"/approve".equals(request.path()) || request.query() != null) {
            return response(404, "denied");
        }
        if (!admissions.tryAcquire()) {
            return response(503, "denied");
        }
        try {
            final var contentType = request.header("content-type");
            if (contentType == null || !JSON_CONTENT_TYPE.matcher(contentType).matches()) {
                return response(415, "denied");
            }
            final var length = Integer.parseInt(request.header("content-length"));
            final var seconds = Integer.parseInt(request.header("x-nono-timeout-secs"));
            if (length < 1
                    || length > ApprovalProtocol.MAX_BODY
                    || seconds < 1
                    || seconds > 3600
                    || request.header("transfer-encoding") != null) {
                return response(400, "denied");
            }
            final byte[] body;
            final var exchange = request.unwrapOrNull(HttpExchange.class);
            // Bound stalled authenticated uploads too. Do not use fullBody(): the JRE adapter reads it unbounded.
            final var expiry = timer.schedule(exchange::close, Math.min(3, seconds), TimeUnit.SECONDS);
            try (final var stream = request.unwrapOrNull(InputStream.class)) {
                body = stream.readNBytes(length + 1);
            } finally {
                expiry.cancel(false);
            }
            if (body.length != length) {
                return response(400, "denied");
            }
            return response(200, coordinator.decide(protocol.parse(body), seconds, receivedAt));
        } catch (final Exception error) {
            return response(400, "denied");
        } finally {
            admissions.release();
        }
    }

    private CompletionStage<Response> response(final int status, final String decision) {
        return CompletableFuture.completedFuture(Response.of()
                .status(status)
                .header("content-type", "application/json")
                .header("cache-control", "no-store")
                .header("connection", "close")
                .body("{\"decision\":\"" + decision + "\"}")
                .build());
    }
}
