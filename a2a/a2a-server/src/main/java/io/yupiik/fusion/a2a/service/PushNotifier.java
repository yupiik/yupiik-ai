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

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.configuration.A2ADurations;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.TaskPushNotificationConfig;
import io.yupiik.fusion.a2a.protocol.A2AProtocol;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

/**
 * Delivers the task events to the webhooks registered with {@code CreateTaskPushNotificationConfig}.
 * <p>
 * The payload is the very same {@link StreamResponse} a stream carries - so a client can consume both mechanisms with
 * one code path - posted with the {@code application/a2a+json} media type, the configured {@code Authorization} header
 * and the {@code X-A2A-Notification-Token} header echoing the token the client registered.
 * <p>
 * Delivery is asynchronous and best effort: it never blocks the task, and a failure is logged and forgotten. This is
 * the "at least once attempt" the specification asks for; add a retry strategy here if the deployment needs one.
 */
@ApplicationScoped
public class PushNotifier {
    /**
     * What {@code fusion.a2a.pushTimeout} means when it is unset - or set to something which is not a positive
     * duration.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final Logger logger = Logger.getLogger(PushNotifier.class.getName());

    private final JsonMapper jsons;
    private final HttpClient http;
    private final Duration timeout;

    public PushNotifier(final JsonMapper jsons, final A2AConfiguration configuration, final A2AThreads threads) {
        this.jsons = jsons;
        // the HTTP client refuses a timeout of zero, so anything which is not positive falls back to the default
        this.timeout = configuration == null
                ? DEFAULT_TIMEOUT
                : A2ADurations.positive(configuration.pushTimeout(), DEFAULT_TIMEOUT);
        // one client for the whole application, on the shared virtual thread executor
        this.http = jsons == null
                ? null
                : HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .executor(threads.executor())
                        .build();
    }

    /**
     * Posts {@code event} to every webhook registered on {@code task}, doing nothing when there is none.
     *
     * @param task  the task which changed.
     * @param event what happened.
     */
    public void notify(final A2ATask task, final StreamResponse event) {
        final var configs = List.copyOf(task.pushConfigs());
        if (configs.isEmpty()) {
            return;
        }
        final var payload = jsons.toString(event);
        // chained on the task so the webhook sees the events in the order the agent produced them
        task.serializePush(() -> CompletableFuture.allOf(configs.stream()
                .map(config -> send(config, payload).toCompletableFuture())
                .toArray(CompletableFuture[]::new)));
    }

    private CompletionStage<Void> send(final TaskPushNotificationConfig config, final String payload) {
        if (config.url() == null || config.url().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        final HttpRequest request;
        try {
            final var builder = HttpRequest.newBuilder(URI.create(config.url()))
                    .timeout(timeout)
                    .header("content-type", A2AProtocol.MEDIA_TYPE)
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (config.authentication() != null && config.authentication().authorization() != null) {
                builder.header("authorization", config.authentication().authorization());
            }
            if (config.token() != null && !config.token().isBlank()) {
                builder.header(A2AProtocol.NOTIFICATION_TOKEN_HEADER, config.token());
            }
            request = builder.build();
        } catch (final RuntimeException re) { // an invalid url, do not let it break the task
            logger.log(
                    WARNING,
                    re,
                    () -> "Can't build the push notification for '" + config.url() + "': " + re.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        // a delivery failure is logged and forgotten: it must never fail the task
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).handle((response, error) -> {
            if (error != null) {
                logger.log(
                        WARNING,
                        error,
                        () -> "Push notification to '" + config.url() + "' failed: " + error.getMessage());
            } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logger.log(
                        WARNING,
                        () -> "Push notification to '" + config.url() + "' answered HTTP " + response.statusCode());
            } else {
                logger.log(FINE, () -> "Push notification to '" + config.url() + "' delivered");
            }
            return null;
        });
    }

    @Destroy
    public void close() {
        if (http != null) {
            http.close();
        }
    }
}
