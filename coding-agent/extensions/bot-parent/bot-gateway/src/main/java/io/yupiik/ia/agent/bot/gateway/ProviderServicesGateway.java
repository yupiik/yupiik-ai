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
package io.yupiik.ia.agent.bot.gateway;

import static io.yupiik.ia.agent.bot.gateway.transport.GatewayTransport.*;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsServer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationImpl;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.bot.gateway.transport.ExchangeLease;
import io.yupiik.ia.agent.bot.gateway.transport.GatewayTransport;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import io.yupiik.ia.agent.core.llm.ProviderRequestBudget;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Fixed inference only. Numeric private HTTP targets require an operator-trusted control network.
 * The constructor's typed budget configuration supplies optional ProviderRequestBudget
 * settings; these must never originate from agent configuration or request headers.
 */
public final class ProviderServicesGateway implements AutoCloseable {
    public static final String YCA_SERVICES_PROVIDER_TOKEN = "YCA_SERVICES_PROVIDER_TOKEN";
    public static final String YCA_SERVICES_UPSTREAM_TOKEN = "YCA_SERVICES_UPSTREAM_TOKEN";
    public static final String YCA_SERVICES_KEYSTORE_PASSWORD = "YCA_SERVICES_KEYSTORE_PASSWORD";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern INFERENCE = Pattern.compile("/inference/" + UUID + "/(?:chat/completions|responses)");
    private static final Pattern ENV = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> RESERVED_ENV = Set.of(
            "PATH",
            "HOME",
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS",
            "ENV",
            "BASH_ENV",
            "SHELLOPTS",
            "BASHOPTS",
            "CDPATH",
            "IFS",
            "SSH_AUTH_SOCK",
            YCA_SERVICES_PROVIDER_TOKEN,
            YCA_SERVICES_UPSTREAM_TOKEN);
    private static final Pattern DNS = Pattern.compile(
            "(?=.{1,253}$)[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern LENGTH = Pattern.compile("[0-9]{1,10}");
    private final Settings settings;
    private final HttpsServer server;
    private final HttpClient client;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1);
    private final Semaphore slots;
    private final ProviderRequestBudget budget;
    private final Set<ExchangeLease> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final long expires;
    private volatile String provider;
    private volatile String upstream;

    /**
     * Trusted, controller-private listener policy, never supplied by an agent or request.
     * The inference path is an attempt UUID capability pinned to one COMPLETIONS or RESPONSES
     * suffix; the fixed numerical HTTP target must use the same API. TLS keys/settings must
     * remain outside agent mounts. maxConcurrent bounds this instance through streaming EOF;
     * the separately configured ProviderRequestBudget coordinates participating forwarders.
     */
    @JsonModel
    public record Settings(
            String serverName,
            int port,
            String keyStore,
            String keyStorePasswordEnv,
            String inferencePath,
            String inferenceTarget,
            long lifetimeSeconds,
            int timeoutSeconds,
            long maxInferenceBytes,
            long maxResponseBytes,
            int maxConcurrent) {
        public Settings {
            if (serverName == null
                    || !DNS.matcher(serverName).matches()
                    || port < 0
                    || port > 65535
                    || keyStore == null
                    || keyStore.isBlank()
                    || keyStorePasswordEnv == null
                    || !ENV.matcher(keyStorePasswordEnv).matches()
                    || RESERVED_ENV.contains(keyStorePasswordEnv)
                    || keyStorePasswordEnv.startsWith("DOCKER_")
                    || keyStorePasswordEnv.startsWith("LD_")
                    || keyStorePasswordEnv.startsWith("DYLD_")
                    || keyStorePasswordEnv.startsWith("XDG_")
                    || inferencePath == null
                    || !INFERENCE.matcher(inferencePath).matches()
                    || lifetimeSeconds < 1
                    || lifetimeSeconds > 86_400
                    || timeoutSeconds < 1
                    || timeoutSeconds > 900
                    || maxInferenceBytes < 1
                    || maxInferenceBytes > 16 * 1024 * 1024
                    || maxResponseBytes < 1
                    || maxResponseBytes > 16 * 1024 * 1024
                    || maxConcurrent < 1
                    || maxConcurrent > 64) {
                throw new IllegalArgumentException("Invalid services settings");
            }
            target(inferenceTarget, false);
            final var api = ChatRoutes.detectFlavor(inferencePath);
            if ((api != ApiFlavor.COMPLETIONS && api != ApiFlavor.RESPONSES)
                    || ChatRoutes.detectFlavor(inferenceTarget) != api) {
                throw new IllegalArgumentException("Provider route and target must pin the same supported API");
            }
        }

        /** Returns zero for an accepted request, otherwise its HTTP rejection status. Never chooses a destination. */
        public int reject(
                final String method,
                final URI uri,
                final Headers headers,
                final int boundPort,
                final String authorization) {
            long size = 0;
            if (headers.size() > 64 || !List.of(serverName + ":" + boundPort).equals(headers.get("Host"))) {
                return 400;
            }
            for (final var entry : headers.entrySet()) {
                size += entry.getKey().length() + 4;
                for (final var value : entry.getValue()) {
                    size += value.length() + 2;
                }
                if (size > 16 * 1024 || entry.getValue().size() != 1) {
                    return 400;
                }
            }
            if (headers.containsKey("Origin")
                    || headers.containsKey("Upgrade")
                    || headers.containsKey("Expect")
                    || (headers.containsKey("Connection")
                            && !List.of("close", "keep-alive").contains(headers.getFirst("Connection")))
                    || headers.containsKey("Proxy-Authorization")
                    || (headers.containsKey("Transfer-Encoding")
                            && (!"chunked".equals(headers.getFirst("Transfer-Encoding"))
                                    || headers.containsKey("Content-Length")))
                    || uri.isAbsolute()
                    || uri.getRawAuthority() != null
                    || uri.getRawFragment() != null
                    || !inferencePath.equals(uri.getRawPath())) {
                return 400;
            }
            final var supplied = headers.getFirst("Authorization");
            if (supplied == null
                    || !MessageDigest.isEqual(
                            supplied.getBytes(StandardCharsets.UTF_8),
                            authorization.getBytes(StandardCharsets.US_ASCII))) {
                return 401;
            }
            if (!"POST".equals(method) || uri.getRawQuery() != null) {
                return 405;
            }
            final var contentType = headers.getFirst("Content-Type");
            if (contentType == null
                    || !"application/json".equals(contentType.split(";", 2)[0].strip())
                    || headers.containsKey("Content-Encoding")
                    || headers.containsKey("Git-Protocol")) {
                return 415;
            }
            final var length = headers.getFirst("Content-Length");
            if (length != null && (!LENGTH.matcher(length).matches() || Long.parseLong(length) > maxInferenceBytes)) {
                return 413;
            }
            return 0;
        }
    }

    public ProviderServicesGateway(
            final Settings settings,
            final Configuration configuration,
            final ProviderBudgetConfiguration budgetConfiguration)
            throws IOException {
        if (configuration.get("YCA_CONTEXT_TOKEN").isPresent()
                || configuration.get("YCA_CONTEXT_GIT_TOKEN").isPresent()) {
            throw new IllegalArgumentException("GitHub capabilities are not supported");
        }
        this.settings = settings;
        this.budget = ProviderRequestBudget.fromConfiguration(budgetConfiguration);
        slots = new Semaphore(settings.maxConcurrent());
        provider = "Bearer " + secret(configuration, YCA_SERVICES_PROVIDER_TOKEN);
        final var credential = configuration.get(YCA_SERVICES_UPSTREAM_TOKEN).orElse(null);
        if (credential != null
                && (credential.length() > 4096 || credential.chars().anyMatch(c -> c < 33 || c > 126))) {
            if (!credential.isEmpty()) {
                throw new IllegalArgumentException("Invalid upstream credential");
            }
        }
        upstream = credential == null || credential.isEmpty() ? null : "Bearer " + credential;
        final var tls = tls(
                settings.keyStore(),
                settings.serverName(),
                configuration.get(settings.keyStorePasswordEnv()).orElse(null));
        expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.lifetimeSeconds());
        server = HttpsServer.create(new InetSocketAddress("0.0.0.0", settings.port()), 4);
        server.setHttpsConfigurator(GatewayTransport.configurator(tls, settings.serverName()));
        client = GatewayTransport.client(executor, settings.timeoutSeconds());
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        watchdog.setRemoveOnCancelPolicy(true);
        server.start();
        watchdog.schedule(this::close, Math.max(0, expires - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(final HttpExchange exchange) {
        if (closed.get() || !slots.tryAcquire()) {
            fail(exchange, 503);
            return;
        }
        final var lease = new ExchangeLease(exchange);
        active.add(lease);
        final long remaining =
                Math.min(expires - System.nanoTime(), TimeUnit.SECONDS.toNanos(settings.timeoutSeconds()));
        if (remaining <= 0 || closed.get()) {
            lease.close();
            active.remove(lease);
            slots.release();
            return;
        }
        ScheduledFuture<?> deadline = null;
        ProviderRequestBudget.Lease reservation = null;
        try {
            deadline = watchdog.schedule(() -> Thread.ofVirtual().start(lease::close), remaining, TimeUnit.NANOSECONDS);
            final var uri = exchange.getRequestURI();
            final var headers = exchange.getRequestHeaders();
            final int rejected = settings.reject(exchange.getRequestMethod(), uri, headers, port(), provider);
            if (rejected != 0) {
                if (rejected == 401) {
                    exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                }
                fail(exchange, rejected);
                return;
            }
            final var request = HttpRequest.newBuilder(URI.create(settings.inferenceTarget()))
                    .timeout(Duration.ofNanos(remaining))
                    .header("Content-Type", headers.getFirst("Content-Type"));
            if (upstream != null) {
                request.header("Authorization", upstream);
            }
            request.POST(HttpRequest.BodyPublishers.ofInputStream(
                    () -> bounded(exchange.getRequestBody(), settings.maxInferenceBytes())));
            final var upstreamRequest = request.build();
            final var acquired = budget.tryAcquire();
            if (acquired.isEmpty()) {
                fail(exchange, 429);
                return;
            }
            reservation = acquired.orElseThrow();
            if (lease.cancelled()) {
                return;
            }
            reservation.dispatched();
            lease.future(client.sendAsync(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream()));
            lease.future().thenAccept(response -> {
                lease.body(response.body());
                if (lease.cancelled()) {
                    lease.close();
                }
            });
            if (lease.cancelled()) {
                lease.future().cancel(true);
                return;
            }
            final var response = lease.future().get(remaining, TimeUnit.NANOSECONDS);
            lease.body(response.body());
            if (lease.cancelled()) {
                return;
            }
            // No redirects, cookies, location, authentication challenges or arbitrary upstream headers
            // escape.
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                fail(exchange, 502);
                return;
            }
            final var type = response.headers().firstValue("content-type");
            type.ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            final long maxResponse = settings.maxResponseBytes();
            if (response.headers().firstValueAsLong("content-length").orElse(0) > maxResponse) {
                fail(exchange, 502);
                return;
            }
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (final var input = bounded(lease.body(), maxResponse);
                    final var output = exchange.getResponseBody()) {
                final var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            }
            if (!lease.cancelled()) {
                reservation.completed();
            }
        } catch (final Exception rejected) {
            // Deliberately never expose bodies, routes, credentials or transport exception details.
            fail(exchange, 502);
        } finally {
            if (reservation != null) {
                try {
                    reservation.close();
                } catch (final IOException ignored) {
                    // Failed durable completion retains debt, never reclaim on transport failure.
                }
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
            lease.close();
            active.remove(lease);
            slots.release();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            provider = "";
            upstream = null;
            server.stop(0);
            active.forEach(lease -> Thread.ofVirtual().start(lease::close));
            client.shutdownNow();
            watchdog.shutdownNow();
            executor.shutdownNow();
            stopped.countDown();
        }
    }

    public static void main(final String[] args) {
        run(args, new ConfigurationImpl(List.of()));
    }

    private static void run(final String[] args, final Configuration configuration) {
        if (args.length != 2 || !("--config".equals(args[0]) || "--check".equals(args[0]))) {
            throw new IllegalArgumentException("Expected --config or --check file");
        }
        try (final var json = JsonMappers.forModels(ignored -> Optional.empty(), Settings.class);
                final var reader = Files.newBufferedReader(Path.of(args[1]))) {
            final var settings = json.read(Settings.class, reader);
            if ("--check".equals(args[0])) {
                final var context = tls(
                        settings.keyStore(),
                        settings.serverName(),
                        configuration.get(settings.keyStorePasswordEnv()).orElse(null));
                GatewayTransport.check(context, settings.serverName(), settings.port());
                return;
            }
            // JDK parser limits are read once. Configure only this dedicated process, never embedding
            // JVMs/tests.
            System.setProperty("sun.net.httpserver.maxReqHeaders", "64");
            System.setProperty("sun.net.httpserver.maxReqHeaderSize", "16384");
            System.setProperty("jdk.httpserver.maxConnections", "8");
            System.setProperty("sun.net.httpserver.maxIdleConnections", "4");
            System.setProperty("sun.net.httpserver.maxReqTime", "" + settings.timeoutSeconds());
            System.setProperty("sun.net.httpserver.maxRspTime", "" + settings.timeoutSeconds());
            try (final var gateway = new ProviderServicesGateway(
                    settings,
                    configuration,
                    new ProviderBudgetConfiguration(
                            configuration.get(ProviderRequestBudget.ROOT).orElse(null),
                            configuration.get(ProviderRequestBudget.ID).orElse(null),
                            configuration.get(ProviderRequestBudget.SLOTS).orElse(null)))) {
                final var hook = new Thread(gateway::close, "services-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    gateway.stopped.await();
                } finally {
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (final IllegalStateException stopping) {
                        // JVM shutdown already owns the hook.
                    }
                }
            }
        } catch (final Exception unavailable) {
            if (!"--check".equals(args[0])) {
                System.getLogger(ProviderServicesGateway.class.getName())
                        .log(System.Logger.Level.ERROR, "Services gateway unavailable");
            }
            System.exit(1);
        }
    }
}
