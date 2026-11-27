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
package io.yupiik.ia.agent.slack.service;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import io.yupiik.ia.agent.bot.runtime.AttemptPreparation;
import io.yupiik.ia.agent.bot.runtime.NativeProviderServices;
import io.yupiik.ia.agent.bot.runtime.ProviderBudgetEnvironment;
import io.yupiik.ia.agent.bot.runtime.ResultReader;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.execution.DockerExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.execution.KubernetesExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.execution.KubernetesProviderServices;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.model.KubeconfigDocument;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.llm.ProviderRequestBudget;
import io.yupiik.ia.agent.core.llm.UnixProviderProtocol;
import io.yupiik.ia.agent.slack.configuration.SlackProviderConfiguration;
import io.yupiik.ia.agent.slack.context.SlackContextStorage;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/** Adapter for shared sandbox backends; credentials and controller state are never supplied to the agent. */
public final class SlackSandbox implements Sandbox {
    private static final Pattern SANDBOX_ID = Pattern.compile("yca-slack-[0-9a-f]{32}-[1-9][0-9]*");

    private final DockerExecutionBackend docker;
    private final NonoExecutionBackend nono;
    private final KubernetesExecutionBackend kubernetes;
    private final KubernetesClient client;
    private final JsonMapper json;
    private final ExecutorConfiguration settings;
    private final Path stateRoot;
    private final SlackProviderConfiguration provider;
    private final Configuration sources;
    private final ProviderBudgetConfiguration budgetConfiguration;
    private final boolean allowSwapBackedTmpfs;
    private final boolean allowContainerLogs;
    private final KubernetesProviderServices kubeServices;
    private final HttpClient nativeHttp;
    private final ConcurrentMap<String, NativeProviderServices> nativeServices = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Path> manifests = new ConcurrentHashMap<>();

    public SlackSandbox(
            final ExecutorConfiguration settings,
            final SlackProviderConfiguration provider,
            final Configuration sources,
            final JsonMapper json,
            final Path stateRoot,
            final ProviderBudgetConfiguration budgetConfiguration,
            final boolean allowSwapBackedTmpfs,
            final boolean allowContainerLogs) {
        this.settings = settings;
        this.json = json;
        this.stateRoot = stateRoot;
        this.provider = provider;
        this.sources = sources;
        this.budgetConfiguration = budgetConfiguration;
        this.allowSwapBackedTmpfs = allowSwapBackedTmpfs;
        this.allowContainerLogs = allowContainerLogs;
        if (settings.backend() == ExecutorConfiguration.Backend.DOCKER
                && (settings.docker() == null || settings.docker().services() == null)) {
            throw new IllegalArgumentException(
                    "Slack requires Docker restricted provider services; direct credential handoff is not enabled");
        }
        if (settings.backend() == ExecutorConfiguration.Backend.NONO
                && (provider == null
                        || provider.nativeBridge() == null
                        || !provider.nativeBridge().qualified()
                        || !provider.nativeBridge().outerIsolationQualified()
                        || provider.upstream() == null
                        || !provider.upstream().startsWith("https://")
                        || settings.nono() == null)) {
            throw new IllegalArgumentException("Slack Nono requires a qualified native provider bridge");
        }
        if (settings.backend() == ExecutorConfiguration.Backend.KUBERNETES
                && (provider == null
                        || provider.kubernetes() == null
                        || !provider.kubernetes().qualified()
                        || settings.kubernetes() == null)) {
            throw new IllegalArgumentException("Slack Kubernetes requires qualified isolated provider services");
        }
        if (settings.timeoutSeconds() < 1
                || settings.timeoutSeconds() > 86400
                || settings.commandTimeoutSeconds() < 1
                || settings.attemptRoot() == null
                || settings.attemptRoot().isBlank()) {
            throw new IllegalArgumentException("Slack requires positive budgets and an explicit private attempt root");
        }
        final var attempts = Path.of(settings.attemptRoot()).toAbsolutePath().normalize();
        if (attempts.startsWith(stateRoot) || stateRoot.startsWith(attempts)) {
            throw new IllegalArgumentException("Slack attempts and controller state must be disjoint");
        }
        try {
            ProviderBudgetEnvironment.select(budgetConfiguration, attempts, stateRoot);
            ProviderRequestBudget.fromConfiguration(budgetConfiguration);
        } catch (final IOException invalid) {
            throw new IllegalArgumentException("Cannot initialize the trusted provider request budget", invalid);
        }
        if (settings.relay() != null
                && settings.relay().bind() != null
                && !settings.relay().bind().isBlank()) {
            throw new IllegalArgumentException(
                    "Slack provider relay integration is not enabled; configure trusted provider routing externally");
        }
        final var timeout = Duration.ofSeconds(settings.timeoutSeconds());
        switch (settings.backend()) {
            case DOCKER -> {
                docker = new DockerExecutionBackend(
                        SlackExecutionPolicy.POLICY,
                        settings.docker().executable(),
                        settings.docker().image(),
                        timeout,
                        settings.docker().network(),
                        settings.docker().egress(),
                        settings.docker().services(),
                        settings.showToolOutput(),
                        settings.docker().disableContainerLogs());
                docker.requireLocalDaemon();
                nono = null;
                kubernetes = null;
                client = null;
                kubeServices = null;
                nativeHttp = null;
            }
            case NONO -> {
                docker = null;
                nono = new NonoExecutionBackend(
                        json,
                        settings.nono().executable(),
                        Path.of(settings.nono().agentExecutable()),
                        Path.of(settings.nono().runtimeRoot()),
                        stateRoot,
                        timeout,
                        Duration.ofSeconds(settings.commandTimeoutSeconds()),
                        settings.showToolOutput());
                nono.validateProviderSupport();
                kubernetes = null;
                client = null;
                kubeServices = null;
                final var socketRoot = Path.of(provider.nativeBridge().socketRoot());
                try {
                    NativeProviderServices.validateSocketParent(socketRoot.resolve("probe.sock"));
                } catch (final IOException invalid) {
                    throw new IllegalArgumentException("Private native socket root is unavailable", invalid);
                }
                final var normalized = socketRoot.toAbsolutePath().normalize();
                final var longestSocket = normalized
                        .resolve("yca-slack-" + "0".repeat(32) + '-' + Integer.MAX_VALUE)
                        .resolve("provider.sock");
                if (longestSocket.toString().getBytes(StandardCharsets.UTF_8).length > 100) {
                    throw new IllegalArgumentException(
                            "Native socket root leaves insufficient room for a durable runtime identity");
                }
                for (final var excluded : List.of(
                        attempts,
                        stateRoot,
                        Path.of(settings.nono().runtimeRoot()).toAbsolutePath().normalize())) {
                    if (normalized.startsWith(excluded) || excluded.startsWith(normalized)) {
                        throw new IllegalArgumentException(
                                "Native provider sockets must be disjoint from agent grants and controller state");
                    }
                }
                ProviderBudgetEnvironment.select(budgetConfiguration, socketRoot, attempts, stateRoot);
                nativeHttp = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(settings.commandTimeoutSeconds()))
                        .build();
            }
            case KUBERNETES -> {
                docker = null;
                nono = null;
                final var kube = settings.kubernetes();
                final var configuration = new KubernetesClientConfiguration()
                        .setToken(kube.token())
                        .setCertificates(kube.certificates())
                        .setSkipTls(kube.skipTls());
                if (!kube.master().isBlank()) {
                    configuration.setMaster(kube.master());
                }
                if (!kube.kubeconfig().isBlank()) {
                    try {
                        final var file =
                                Path.of(kube.kubeconfig()).toAbsolutePath().normalize();
                        if (!file.toString().endsWith(".json")) {
                            throw new IllegalArgumentException("Only static JSON kubeconfig is supported");
                        }
                        final var document =
                                json.fromString(KubeconfigDocument.class, ResultReader.read(file, 1024 * 1024));
                        if (document.users() == null
                                || document.users().stream()
                                        .anyMatch(u -> u == null
                                                || u.user() == null
                                                || u.user().containsKey("exec")
                                                || u.user().containsKey("auth-provider"))) {
                            throw new IllegalArgumentException("Kubernetes external authentication is prohibited");
                        }
                        configuration.setKubeconfig(file);
                    } catch (final IOException failure) {
                        throw new IllegalArgumentException("Cannot load private static kubeconfig");
                    }
                }
                if (!kube.privateKey().isBlank()) {
                    configuration.setPrivateKey(kube.privateKey());
                }
                if (!kube.privateKeyCertificate().isBlank()) {
                    configuration.setPrivateKeyCertificate(kube.privateKeyCertificate());
                }
                client = new KubernetesClient(configuration);
                kubernetes = new KubernetesExecutionBackend(
                        json,
                        client,
                        kube.namespace(),
                        kube.claim(),
                        kube.image(),
                        attempts,
                        kube.uid(),
                        kube.gid(),
                        timeout,
                        Duration.ofSeconds(settings.commandTimeoutSeconds()),
                        kube.serviceAccount(),
                        settings.showToolOutput());
                final var service = provider.kubernetes();
                final var budget = ProviderBudgetEnvironment.select(budgetConfiguration, attempts, stateRoot);
                if (budget.isEmpty() || service.budgetClaim().equals(kube.claim())) {
                    throw new IllegalArgumentException(
                            "Kubernetes requires a separate qualified local provider budget PVC and explicit request budget");
                }
                kubeServices = new KubernetesProviderServices(
                        json,
                        client,
                        kube.namespace(),
                        new KubernetesProviderServices.Config(
                                service.image(),
                                service.tlsSecret(),
                                service.serverName(),
                                provider.upstream(),
                                service.nodeName(),
                                service.budgetClaim(),
                                service.serviceAccount(),
                                service.port(),
                                Duration.ofSeconds(settings.commandTimeoutSeconds()),
                                Duration.ofSeconds(settings.commandTimeoutSeconds()),
                                Duration.ofSeconds(settings.commandTimeoutSeconds()),
                                KubernetesProviderServices.gatewayLifetimeSeconds(
                                        timeout, Duration.ofSeconds(settings.commandTimeoutSeconds())),
                                Math.min(900, settings.timeoutSeconds())));
                nativeHttp = null;
            }
            default -> throw new IllegalArgumentException("Unsupported sandbox backend");
        }
    }

    public int execute(final Path path, final String id, final String prompt, final Path temporaryDirectory) {
        validateIdentity(id);
        if (temporaryDirectory != null) {
            try {
                new SlackContextStorage().validate(settings, allowSwapBackedTmpfs, allowContainerLogs);
            } catch (final IOException invalid) {
                throw new IllegalArgumentException("Slack context transient storage is unavailable");
            }
        }
        if (docker != null) {
            if (settings.docker().services() != null) {
                try {
                    final var privateRoot = Files.createDirectory(
                            stateRoot.resolve("provider-" + id),
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    manifests.put(id, privateRoot);
                    final var services = new SlackProviderServices(
                            path.resolve("configuration.json"),
                            privateRoot,
                            json,
                            new SlackProviderServices.Routing(
                                    settings.docker().services().providerTarget(),
                                    "https://" + settings.docker().services().serverName() + ":8443",
                                    settings.docker().services().serverName(),
                                    8443,
                                    "/services/tls.p12",
                                    settings.docker().services().keyStorePasswordEnv()),
                            sources,
                            budgetConfiguration,
                            settings.timeoutSeconds());
                    return docker.execute(
                            path,
                            id,
                            prompt,
                            Map.of(),
                            Map.of(),
                            services.settings(),
                            services.secrets(),
                            temporaryDirectory);
                } catch (final IOException invalid) {
                    throw new IllegalStateException("Cannot prepare private Slack provider services");
                }
            }
            return docker.execute(path, id, prompt, Map.of(), Map.of());
        }
        if (nono != null) {
            try {
                final var privateRoot = Files.createDirectory(
                        Path.of(provider.nativeBridge().socketRoot()).resolve(id),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                manifests.put(id, privateRoot);
                final var services = new SlackProviderServices(
                        path.resolve("configuration.json"),
                        privateRoot,
                        json,
                        new SlackProviderServices.Routing(
                                provider.upstream(),
                                UnixProviderProtocol.ENDPOINT.toString(),
                                "native.invalid",
                                0,
                                "/unused",
                                ""),
                        sources,
                        budgetConfiguration,
                        settings.timeoutSeconds());
                final var budget = ProviderRequestBudget.fromConfiguration(budgetConfiguration);
                final var bridge = new NativeProviderServices(
                        privateRoot.resolve("provider.sock"),
                        nativeHttp,
                        services.upstream(),
                        services.api(),
                        "Bearer " + services.secrets().get("YCA_SERVICES_UPSTREAM_TOKEN"),
                        services.secrets().get("YCA_SERVICES_PROVIDER_TOKEN"),
                        Duration.ofSeconds(services.timeoutSeconds()),
                        services.maxConcurrent(),
                        () -> {
                            final var lease = budget.tryAcquire()
                                    .orElseThrow(() -> new IllegalStateException("Provider budget saturated"));
                            return new NativeProviderServices.Reservation() {
                                public void dispatched() {
                                    lease.dispatched();
                                }

                                public void complete() throws IOException {
                                    lease.completed();
                                }

                                public void close() throws IOException {
                                    lease.close();
                                }
                            };
                        });
                nativeServices.put(id, bridge);
                return nono.execute(path, id, prompt, Map.of(), Map.of(), bridge.socketPath());
            } catch (final IOException invalid) {
                throw new IllegalStateException("Cannot prepare private native provider services", invalid);
            }
        }
        try {
            final var privateRoot = Files.createDirectory(
                    stateRoot.resolve("provider-" + id),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            manifests.put(id, privateRoot);
            final var config = provider.kubernetes();
            final var services = new SlackProviderServices(
                    path.resolve("configuration.json"),
                    privateRoot,
                    json,
                    new SlackProviderServices.Routing(
                            provider.upstream(),
                            "https://" + config.serverName() + ':' + config.port(),
                            config.serverName(),
                            config.port(),
                            "/tls/gateway.p12",
                            "YCA_SERVICES_KEYSTORE_PASSWORD"),
                    sources,
                    budgetConfiguration,
                    settings.timeoutSeconds());
            final var connection = kubeServices.start(
                    id,
                    Map.of(
                            "YCA_SERVICES_PROVIDER_TOKEN", services.secrets().get("YCA_SERVICES_PROVIDER_TOKEN"),
                            "YCA_SERVICES_UPSTREAM_TOKEN", services.secrets().get("YCA_SERVICES_UPSTREAM_TOKEN"),
                            "YCA_SERVICES_KEYSTORE_PASSWORD", services.secrets().get("YCA_SERVICES_KEYSTORE_PASSWORD")),
                    services.secrets().get(ProviderBudgetEnvironment.ID),
                    Integer.parseInt(services.secrets().get(ProviderBudgetEnvironment.SLOTS)));
            services.retarget(path.resolve("configuration.json"), json, connection.endpoint());
            return kubernetes.executeProviderOnly(path, id, prompt, Map.of(), connection);
        } catch (final IOException invalid) {
            throw new IllegalStateException("Cannot prepare private Kubernetes provider services", invalid);
        }
    }

    public void stop(final String id) {
        validateIdentity(id);
        if (docker != null) {
            docker.stop(id);
        } else if (nono != null) {
            nono.stopProviderOnly(id);
            final var bridge = nativeServices.get(id);
            if (bridge != null) {
                bridge.close();
                nativeServices.remove(id, bridge);
            }
            nono.cleanupProviderOnly(id);
        } else {
            kubernetes.stop(id);
            kubeServices.stop(id);
        }
        final var privateRoot = manifests.getOrDefault(
                id,
                nono != null
                        ? Path.of(provider.nativeBridge().socketRoot()).resolve(id)
                        : stateRoot.resolve("provider-" + id));
        if (privateRoot != null) {
            try {
                AttemptPreparation.delete(privateRoot);
                manifests.remove(id);
            } catch (final IOException retained) {
                // Runtime is confirmed stopped; private stale settings can be retained for operator cleanup.
            }
        }
    }

    @Override
    public void close() {
        if (!nativeServices.isEmpty()) {
            throw new IllegalStateException(
                    "Native provider services remain active; retain controller state for recovery");
        }
        if (nativeHttp != null) {
            nativeHttp.close();
        }
        if (client != null) {
            client.close();
        }
    }

    private void validateIdentity(final String id) {
        if (id == null || !SANDBOX_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid Slack runtime identity");
        }
    }

    @Override
    public String recoveryIdentity() {
        final var budget = ProviderBudgetEnvironment.select(budgetConfiguration);
        return String.valueOf(provider)
                + ':'
                + budget.getOrDefault(ProviderBudgetEnvironment.ROOT, "")
                + ':'
                + budget.getOrDefault(ProviderBudgetEnvironment.ID, "")
                + ':'
                + budget.getOrDefault(ProviderBudgetEnvironment.SLOTS, "");
    }
}
