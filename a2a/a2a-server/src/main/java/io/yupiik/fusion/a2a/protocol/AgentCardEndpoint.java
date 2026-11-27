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
package io.yupiik.fusion.a2a.protocol;

import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.service.AgentCardService;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.json.JsonMapper;
import java.util.concurrent.CompletionStage;

/**
 * Publishes the agent card on {@code /.well-known/agent-card.json}, which is how a client discovers the agent - see
 * section 8 of the specification.
 * <p>
 * The card carries the caching headers section 8.6 asks for: a {@code Cache-Control} with the configured
 * {@code max-age} and an {@code ETag} derived from the agent version, so a client can revalidate with
 * {@code If-None-Match} and get a {@code 304}.
 */
@ApplicationScoped
public class AgentCardEndpoint {
    private final AgentCardService cards;
    private final JsonMapper jsons;
    private final int maxAge;

    public AgentCardEndpoint(
            final AgentCardService cards, final JsonMapper jsons, final A2AConfiguration configuration) {
        this.cards = cards;
        this.jsons = jsons;
        this.maxAge = configuration == null ? 300 : Math.max(0, configuration.agentCardMaxAge());
    }

    @HttpMatcher(methods = "GET", path = A2AProtocol.AGENT_CARD_PATH, pathMatching = HttpMatcher.PathMatching.EXACT)
    public CompletionStage<Response> card(final Request request) {
        final var card = cards.card(request);
        final var etag = '"' + card.version() + '"';
        if (etag.equals(request.header("if-none-match"))) {
            return completedFuture(
                    Response.of().status(304).header("etag", etag).build());
        }
        return completedFuture(Response.of()
                .status(200)
                .header("content-type", "application/json;charset=utf-8")
                .header("cache-control", "public, max-age=" + maxAge)
                .header("etag", etag)
                .body(jsons.toString(card))
                .build());
    }
}
