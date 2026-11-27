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
package io.yupiik.ia.agent.slack.support;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.configuration.SlackThreadConfiguration;
import io.yupiik.ia.agent.slack.context.SlackActionRegistry;
import io.yupiik.ia.agent.slack.context.SlackContextAttempt;
import io.yupiik.ia.agent.slack.model.SlackJson;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** No container boot, network, scheduler, credentials, Docker, or model in domain tests. */
public final class SlackDomainFixture implements AutoCloseable {
    public final JsonMapper json = SlackJsonMapper.create();
    public final SlackContextConfiguration contextConfiguration = new SlackContextConfiguration(
            false,
            true,
            false,
            List.of(),
            30,
            32,
            12,
            20,
            131072,
            20,
            2,
            600,
            false,
            new SlackThreadConfiguration(false, List.of(), 20, 100, 65536, 3, 24),
            false);
    public final SlackActionRegistry actions = new SlackActionRegistry(contextConfiguration, Clock.systemUTC());
    public final SlackContextAttempt.Dispatcher contextDispatcher =
            new SlackContextAttempt.Dispatcher(contextConfiguration);
    public final SlackConfiguration configuration;
    public final SlackStore store;
    public final List<Exchange> exchanges = new ArrayList<>();
    public Function<Exchange, String> response =
            ignored -> "{\"ok\":true,\"channel\":\"C1\",\"ts\":\"2.1\",\"view\":{\"id\":\"V1\"}}";
    private final ExtendedHttpClient http =
            new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(new MemoryHttpClient()));
    public final SlackWebApi api = new SlackWebApi(
            json,
            http,
            new SlackWebApi.Settings(URI.create("https://slack.invalid/api/"), Duration.ofMillis(100), 65536, 65536),
            () -> "xapp-test",
            () -> "xoxb-test");

    public SlackDomainFixture(final Path root, final int maximum, final int perUser) throws IOException {
        final var agent = Files.writeString(root.resolve("agent.json"), "{\"yca\":{\"provider\":\"fake\"}}");
        configuration = new SlackConfiguration(
                "T1",
                "UBOT",
                List.of("C1", "C2"),
                List.of("U1", "U2"),
                false,
                false,
                "SLACK_APP_TOKEN",
                "SLACK_BOT_TOKEN",
                root.resolve("state").toString(),
                agent.toString(),
                maximum,
                perUser,
                16000,
                12000,
                60,
                86400);
        store = new SlackStore(configuration, json);
    }

    public SlackConfiguration channelMemberConfiguration() {
        return new SlackConfiguration(
                configuration.workspace(),
                configuration.botUser(),
                configuration.channels(),
                List.of(),
                true,
                configuration.allowSlackConnect(),
                configuration.appTokenEnv(),
                configuration.botTokenEnv(),
                configuration.home(),
                configuration.agentConfiguration(),
                configuration.maxPending(),
                configuration.maxPendingPerUser(),
                configuration.maxInputChars(),
                configuration.maxAnswerChars(),
                configuration.questionTimeoutSeconds(),
                configuration.retentionSeconds());
    }

    public static SlackPrompts prompts(final SlackPrompts baseline) {
        return new SlackPrompts(
                "trusted-policy",
                "{{policy}}\n{{output}}\n{{request}}\n{{context}}",
                "trusted-output",
                baseline.answerLabel(),
                baseline.cancelLabel(),
                "Question",
                baseline.contextGuidance());
    }

    public ExecutorConfiguration executor(final Path root) {
        return new ExecutorConfiguration(
                ExecutorConfiguration.Backend.DOCKER,
                root.resolve("attempts").toString(),
                60,
                1,
                "hidden",
                null,
                null,
                null,
                null);
    }

    public static SlackTask task(final String key, final String requester, final TaskStatus state, final long created) {
        return new SlackTask(
                SlackStore.identity(key),
                "T1",
                "C1",
                "1.1",
                requester,
                "Please explain this.",
                SlackStore.identity("T1:C1:1.1:" + requester),
                state,
                created,
                created,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
    }

    public void add(final SlackTask... tasks) {
        store.transaction(current -> {
            current.addAll(List.of(tasks));
            return null;
        });
    }

    public SlackJson.Payload payload(final Map<String, Object> value) {
        return json.fromString(SlackJson.Payload.class, json.toString(value));
    }

    @Override
    public void close() {
        contextDispatcher.close();
        actions.close();
        http.close();
        json.close();
    }

    public record Exchange(HttpRequest request, String body) {}

    private final class MemoryHttpClient extends HttpClient {
        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            final var body = new StringBuilder();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(final Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(final ByteBuffer bytes) {
                    body.append(StandardCharsets.UTF_8.decode(bytes));
                }

                @Override
                public void onError(final Throwable error) {
                    throw new AssertionError(error);
                }

                @Override
                public void onComplete() {}
            });
            final var exchange = new Exchange(request, body.toString());
            exchanges.add(exchange);
            final var payload = response.apply(exchange).getBytes(StandardCharsets.UTF_8);
            final var headers = HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a, b) -> true);
            final var subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return 200;
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public Version version() {
                    return Version.HTTP_1_1;
                }
            });
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean sent;

                @Override
                public void request(final long count) {
                    if (!sent && count > 0) {
                        sent = true;
                        subscriber.onNext(List.of(ByteBuffer.wrap(payload)));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {}
            });
            return subscriber
                    .getBody()
                    .thenApply(value -> (HttpResponse<T>) new MemoryResponse<>(request, headers, value))
                    .toCompletableFuture();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final HttpResponse.PushPromiseHandler<T> push) {
            throw new AssertionError("No push expected");
        }

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("No blocking HTTP expected");
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            throw new AssertionError("No TLS expected");
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }

    private record MemoryResponse<T>(HttpRequest request, HttpHeaders headers, T body) implements HttpResponse<T> {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
