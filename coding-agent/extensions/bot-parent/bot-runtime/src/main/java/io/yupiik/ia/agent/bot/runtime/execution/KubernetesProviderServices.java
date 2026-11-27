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
package io.yupiik.ia.agent.bot.runtime.execution;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Provider-only, separate-Pod services. Names are single-use; the namespace and yca-* labels/names
 * must be exclusively controlled by the dispatcher. No untrusted RBAC may create pods or read Secrets.
 * Requires Kubernetes >= 1.27 terminal status before Pod removal, a healthy control plane/kubelet,
 * no force deletion, and an admission policy preserving our terminal-confirmation finalizer.
 *
 * Deployment prerequisites (not discoverable or guaranteed by this adapter): a qualified CNI enforcing
 * ingress AND egress before first packet, including Service DNAT, with no additive allow policies;
 * node/metadata traffic isolation (NetworkPolicy alone does not block node-local traffic); a qualified
 * node-local budget PVC on nodeName, shared ONLY with trusted budget participants using the same
 * filesystem locking protocol, with its root owned by UID 10001 and mode 0700 (no fsGroup rewriting).
 * The agent must NEVER mount this PVC or receive these private secrets.
 * The TLS Secret must contain gateway.p12, valid for tlsServerName, and its public CA must be trusted
 * by the agent image. The trusted image must contain java and @/app/jib-classpath-file.
 * No DNS egress is allowed: the returned host alias must be installed on the agent Pod.
 *
 * State lives in Kubernetes, not this instance. Failed/uncertain creation deliberately retains the
 * anchor Secret for stop(runtimeId) recovery. Never reuse an ID or call stop until the agent backend
 * has confirmed termination. Concurrent starts are rejected by create-only Secret acquisition.
 */
public final class KubernetesProviderServices {
    private static final Pattern TLS_NAME = Pattern.compile("[a-z][a-z0-9.-]{0,252}");
    private static final Pattern CAPABILITY = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern BUDGET_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern INFERENCE_PATH = Pattern.compile(
            "/inference/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/(?:chat/completions|responses)");
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    private static final String OWNER = "yca-provider-owner";
    private static final String RUNTIME = "yca-provider-runtime";
    private static final String FINALIZER = "yca.yupiik.io/provider-terminal-confirmed";
    private static final String SETTINGS = "/private/settings.json";
    private static final String MAIN = "io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway";
    private static final String PROVIDER = "YCA_SERVICES_PROVIDER_TOKEN";
    private static final String UPSTREAM = "YCA_SERVICES_UPSTREAM_TOKEN";
    private static final String PASSWORD = "YCA_SERVICES_KEYSTORE_PASSWORD";
    private final JsonMapper json;
    private final KubernetesClient client;
    private final String namespace;
    private final Config config;

    public static long gatewayLifetimeSeconds(final Duration execution, final Duration control) {
        positive(execution);
        positive(control);
        // Readiness/recovery and agent creation perform bounded control exchanges before the
        // agent's own timeout begins. Include a conservative envelope for those exchanges and
        // shutdown, rather than expiring the gateway at the agent's nominal timeout.
        final long lifetime;
        try {
            lifetime = Math.addExact(
                    execution.toSeconds(), Math.addExact(Math.multiplyExact(control.toSeconds(), 20), 60));
        } catch (final ArithmeticException invalid) {
            throw new IllegalArgumentException("Provider gateway lifetime exceeds the supported budget", invalid);
        }
        if (lifetime > 86_400) {
            throw new IllegalArgumentException(
                    "Reserve provider startup/shutdown headroom within the one-day gateway lifetime");
        }
        return lifetime;
    }

    /** Trusted configuration only; bind this qualified record in the parent, not agent configuration. */
    public record Config(
            String gatewayImage,
            String tlsSecret,
            String tlsServerName,
            String upstreamEndpoint,
            String nodeName,
            String budgetClaim,
            String serviceAccount,
            int port,
            Duration startupTimeout,
            Duration requestTimeout,
            Duration stopTimeout,
            long lifetimeSeconds,
            int inferenceTimeoutSeconds) {
        public Config {
            name(tlsSecret);
            name(nodeName);
            name(budgetClaim);
            name(serviceAccount);
            if (gatewayImage == null
                    || gatewayImage.isBlank()
                    || port < 1024
                    || port > 65535
                    || tlsServerName == null
                    || !TLS_NAME.matcher(tlsServerName).matches()
                    || lifetimeSeconds < 1
                    || lifetimeSeconds > 86_400
                    || inferenceTimeoutSeconds < 1
                    || inferenceTimeoutSeconds > 900) {
                throw new IllegalArgumentException("Invalid trusted provider services configuration");
            }
            positive(startupTimeout);
            positive(requestTimeout);
            positive(stopTimeout);
            // Gateway validation rejects DNS, alternate routes, loopback/link-local, redirects, etc.
            new ProviderServicesGateway.Settings(
                    tlsServerName,
                    port,
                    "/tls/gateway.p12",
                    PASSWORD,
                    "/inference/00000000-0000-0000-0000-000000000000" + route(upstreamEndpoint),
                    upstreamEndpoint,
                    lifetimeSeconds,
                    inferenceTimeoutSeconds,
                    8 * 1024 * 1024,
                    8 * 1024 * 1024,
                    1);
            if (URI.create(upstreamEndpoint).getHost().startsWith("127.")) {
                throw new IllegalArgumentException("Upstream must be outside the gateway Pod");
            }
        }
    }

    /** Only capability and endpoint are agent-visible. No provider key, TLS password or budget mount. */
    public record Connection(String runtimeId, URI endpoint, String capability, String address, String serverName) {
        public Connection {
            identity(runtimeId);
            ipv4(address);
            if (serverName == null
                    || !TLS_NAME.matcher(serverName).matches()
                    || capability == null
                    || !CAPABILITY.matcher(capability).matches()
                    || endpoint == null
                    || !"https".equals(endpoint.getScheme())
                    || !serverName.equals(endpoint.getHost())
                    || endpoint.getPort() < 1024
                    || endpoint.getPort() > 65535
                    || endpoint.getRawUserInfo() != null
                    || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null
                    || !INFERENCE_PATH.matcher(endpoint.getRawPath()).matches()) {
                throw new IllegalArgumentException("Invalid provider connection");
            }
        }

        @Override
        public String toString() {
            return "ProviderConnection[" + runtimeId + "]";
        }
    }

    public KubernetesProviderServices(
            final JsonMapper json, final KubernetesClient client, final String namespace, final Config config) {
        this.json = Objects.requireNonNull(json);
        this.client = Objects.requireNonNull(client);
        name(namespace);
        this.namespace = namespace;
        this.config = Objects.requireNonNull(config);
    }

    /**
     * privateSecrets is the actual gateway environment: capability, upstream provider key (possibly
     * empty), and TLS password, using the three YCA_SERVICES_* names. Nothing is inferred from host env.
     * budgetId/slots use the core YCA_PROVIDER_BUDGET_* protocol and remain gateway-only.
     */
    public Connection start(
            final String runtimeId, final Map<String, String> privateSecrets, final String budgetId, final int slots) {
        identity(runtimeId);
        if (privateSecrets == null
                || !privateSecrets.keySet().equals(Set.of(PROVIDER, UPSTREAM, PASSWORD))
                || privateSecrets.values().stream().anyMatch(v -> v == null || v.length() > 4096)
                || !CAPABILITY.matcher(privateSecrets.get(PROVIDER)).matches()
                || privateSecrets.get(PASSWORD).isEmpty()
                || privateSecrets.get(UPSTREAM).chars().anyMatch(c -> c < 33 || c > 126)
                || budgetId == null
                || !BUDGET_ID.matcher(budgetId).matches()
                || slots < 1
                || slots > 65536) {
            throw new IllegalArgumentException("Invalid trusted gateway credentials or budget");
        }
        final var resources = resources(runtimeId);
        // Fail closed before acquiring the anchor. Never adopt a preexisting resource.
        for (final var resource : resources) {
            if (get(resource) != null) {
                throw new IllegalStateException("Provider identity exists; recover with stop first");
            }
        }
        final var values = new LinkedHashMap<String, String>(privateSecrets);
        values.put("settings.json", json.toString(settings(runtimeId)));
        values.put("YCA_PROVIDER_BUDGET_ROOT", "/budget");
        values.put("YCA_PROVIDER_BUDGET_ID", budgetId);
        values.put("YCA_PROVIDER_BUDGET_SLOTS", Integer.toString(slots));
        final var anchor = resources.getLast();
        final var secret = object("v1", "Secret", metadata(runtimeId, null));
        secret.put("type", "Opaque");
        secret.put("immutable", true);
        secret.put("stringData", values);
        // If this request is uncertain, retain everything. Recovery uses the deterministic ID.
        request("POST", collection(anchor), secret, false);
        final var owned = get(anchor);
        ownedAnchor(owned, runtimeId);
        final var uid = uid(owned);
        for (final var resource : resources.subList(0, resources.size() - 1)) {
            final Map<String, Object> manifest =
                    switch (kind(resource)) {
                        case "services" -> service(runtimeId, uid);
                        case "networkpolicies" -> policy(runtimeId, uid, resource.endsWith("-agent"));
                        case "pods" -> pod(runtimeId, uid, values.keySet());
                        default -> throw new IllegalStateException("Unknown provider resource");
                    };
            request("POST", collection(resource), manifest, false);
        }
        return recover(runtimeId);
    }

    /** Reconstructs the connection from immutable cluster state, and waits for TLS-check readiness. */
    public Connection recover(final String runtimeId) {
        identity(runtimeId);
        final var resources = resources(runtimeId);
        final var anchor = get(resources.getLast());
        ownedAnchor(anchor, runtimeId);
        final var owner = uid(anchor);
        for (final var resource : resources.subList(0, resources.size() - 1)) {
            owned(get(resource), runtimeId, owner);
        }
        final var data = map(anchor.get("data"));
        final var settings = json.fromString(ProviderServicesGateway.Settings.class, decode(data, "settings.json"));
        final var serverName = settings.serverName();
        final var port = settings.port();
        final var service = get(resources.getFirst());
        owned(service, runtimeId, owner);
        final var address = text(map(service.get("spec")).get("clusterIP"));
        ipv4(address);
        final long deadline = System.nanoTime() + config.startupTimeout().toNanos();
        while (true) {
            final var observed = get(pods() + '/' + gateway(runtimeId));
            owned(observed, runtimeId, owner);
            final var meta = map(observed.get("metadata"));
            final var status = map(observed.getOrDefault("status", Map.of()));
            if (meta.containsKey("deletionTimestamp") || terminal(observed)) {
                throw new IllegalStateException("Provider gateway is stopping or terminal");
            }
            if ("Running".equals(status.get("phase"))
                    && list(status.getOrDefault("conditions", List.of())).stream()
                            .anyMatch(c -> "Ready".equals(c.get("type")) && "True".equals(c.get("status")))
                    && list(status.getOrDefault("containerStatuses", List.of())).size() == 1
                    && Boolean.TRUE.equals(
                            list(status.get("containerStatuses")).getFirst().get("ready"))) {
                return new Connection(
                        runtimeId,
                        URI.create("https://" + serverName + ':' + port + settings.inferencePath()),
                        decode(data, PROVIDER),
                        address,
                        serverName);
            }
            pause(deadline, "Provider readiness uncertain; retaining resources");
        }
    }

    /**
     * Call only AFTER the agent is terminal-confirmed. Pod deletion requests graceful JVM shutdown;
     * our finalizer retains status until kubelet confirms the process stopped. Missing/foreign or
     * nonterminal state never authorizes dropping a finalizer or deleting supporting resources.
     */
    public void stop(final String runtimeId) {
        identity(runtimeId);
        // The agent backend owns its own cancellation and terminal confirmation. Do not cut the
        // gateway out from under an active/uncertain agent, including after dispatcher restart.
        if (get("/apis/batch/v1/namespaces/" + namespace + "/jobs/" + runtimeId) != null
                || !list(get(pods() + "?labelSelector=yca-runtime%3D" + runtimeId)
                                .get("items"))
                        .isEmpty()) {
            throw new IllegalStateException("Stop the agent backend before provider cleanup");
        }
        final var resources = resources(runtimeId);
        final var anchor = get(resources.getLast());
        if (anchor == null) {
            for (final var resource : resources.subList(0, resources.size() - 1)) {
                if (get(resource) != null) {
                    throw new IllegalStateException("Provider anchor missing; operator recovery required");
                }
            }
            return;
        }
        ownedAnchor(anchor, runtimeId);
        final var owner = uid(anchor);
        // Validate ALL surviving resources before making any destructive request (partial creation).
        for (final var resource : resources.subList(0, resources.size() - 1)) {
            final var value = get(resource);
            if (value != null) {
                owned(value, runtimeId, owner);
            }
        }
        final var podPath = pods() + '/' + gateway(runtimeId);
        var observed = get(podPath);
        if (observed != null) {
            owned(observed, runtimeId, owner);
            final var podUid = uid(observed);
            if (!listStrings(map(observed.get("metadata")).get("finalizers")).contains(FINALIZER)) {
                throw new IllegalStateException("Provider terminal guard missing; operator recovery required");
            }
            delete(podPath, observed);
            final long deadline = System.nanoTime() + config.stopTimeout().toNanos();
            while (true) {
                observed = get(podPath);
                owned(observed, runtimeId, owner);
                if (!podUid.equals(uid(observed))) {
                    throw new IllegalStateException("Provider Pod identity changed");
                }
                if (terminal(observed)) {
                    break;
                }
                pause(deadline, "Provider termination uncertain; retaining resources");
            }
            final var meta = map(observed.get("metadata"));
            final var finalizers = new ArrayList<>(listStrings(meta.get("finalizers")));
            finalizers.remove(FINALIZER);
            request(
                    "PATCH",
                    podPath,
                    Map.of(
                            "metadata",
                            Map.of(
                                    "uid",
                                    podUid,
                                    "resourceVersion",
                                    text(meta.get("resourceVersion")),
                                    "finalizers",
                                    finalizers)),
                    false);
            absent(podPath, deadline);
        }
        for (final var resource : resources) {
            if (resource.equals(podPath)) {
                continue;
            }
            final var value = get(resource);
            if (value != null) {
                if (resource.equals(resources.getLast())) {
                    ownedAnchor(value, runtimeId);
                    if (!owner.equals(uid(value))) {
                        throw new IllegalStateException("Provider anchor identity changed");
                    }
                } else {
                    owned(value, runtimeId, owner);
                }
                delete(resource, value);
                absent(resource, System.nanoTime() + config.stopTimeout().toNanos());
            }
        }
    }

    private ProviderServicesGateway.Settings settings(final String id) {
        return new ProviderServicesGateway.Settings(
                config.tlsServerName(),
                config.port(),
                "/tls/gateway.p12",
                PASSWORD,
                "/inference/" + UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8))
                        + route(config.upstreamEndpoint()),
                config.upstreamEndpoint(),
                config.lifetimeSeconds(),
                config.inferenceTimeoutSeconds(),
                8 * 1024 * 1024,
                8 * 1024 * 1024,
                1);
    }

    private static String route(final String endpoint) {
        final var api = ChatRoutes.detectFlavor(endpoint);
        if (api != ApiFlavor.COMPLETIONS && api != ApiFlavor.RESPONSES) {
            throw new IllegalArgumentException("Explicit completions or responses upstream required");
        }
        return api == ApiFlavor.RESPONSES ? "/responses" : "/chat/completions";
    }

    private Map<String, Object> pod(final String id, final String owner, final Set<String> keys) {
        final var metadata = metadata(id, owner);
        metadata.put("finalizers", List.of(FINALIZER));
        final var result = object("v1", "Pod", metadata);
        final var env = keys.stream()
                .filter(k -> !k.equals("settings.json"))
                .sorted()
                .map(k -> Map.of("name", k, "valueFrom", Map.of("secretKeyRef", Map.of("name", gateway(id), "key", k))))
                .toList();
        final var security = Map.of(
                "runAsNonRoot",
                true,
                "runAsUser",
                10001,
                "runAsGroup",
                10001,
                "readOnlyRootFilesystem",
                true,
                "allowPrivilegeEscalation",
                false,
                "capabilities",
                Map.of("drop", List.of("ALL")),
                "seccompProfile",
                Map.of("type", "RuntimeDefault"));
        final var container = Map.ofEntries(
                Map.entry("name", "gateway"),
                Map.entry("image", config.gatewayImage()),
                Map.entry("command", List.of("java", "-cp", "@/app/jib-classpath-file", MAIN)),
                Map.entry("args", List.of("--config", SETTINGS)),
                Map.entry("env", env),
                Map.entry("securityContext", security),
                Map.entry(
                        "resources",
                        Map.of(
                                "requests",
                                Map.of("cpu", "100m", "memory", "128Mi"),
                                "limits",
                                Map.of("cpu", "1", "memory", "512Mi", "ephemeral-storage", "64Mi"))),
                Map.entry(
                        "volumeMounts",
                        List.of(
                                mount("private", "/private", true),
                                mount("tls", "/tls", true),
                                mount("budget", "/budget", false),
                                mount("tmp", "/tmp", false))),
                Map.entry(
                        "readinessProbe",
                        Map.of(
                                "exec",
                                Map.of(
                                        "command",
                                        List.of("java", "-cp", "@/app/jib-classpath-file", MAIN, "--check", SETTINGS)),
                                "periodSeconds",
                                1,
                                "timeoutSeconds",
                                5)));
        result.put(
                "spec",
                Map.ofEntries(
                        Map.entry("nodeName", config.nodeName()),
                        Map.entry("serviceAccountName", config.serviceAccount()),
                        Map.entry("automountServiceAccountToken", false),
                        Map.entry("enableServiceLinks", false),
                        Map.entry("restartPolicy", "Never"),
                        Map.entry("terminationGracePeriodSeconds", 30),
                        Map.entry("activeDeadlineSeconds", config.lifetimeSeconds()),
                        Map.entry("containers", List.of(container)),
                        Map.entry(
                                "volumes",
                                List.of(
                                        Map.of(
                                                "name",
                                                "private",
                                                "secret",
                                                Map.of(
                                                        "secretName",
                                                        gateway(id),
                                                        "defaultMode",
                                                        0444,
                                                        "items",
                                                        List.of(Map.of(
                                                                "key", "settings.json", "path", "settings.json")))),
                                        Map.of(
                                                "name",
                                                "tls",
                                                "secret",
                                                Map.of(
                                                        "secretName",
                                                        config.tlsSecret(),
                                                        "defaultMode",
                                                        0444,
                                                        "items",
                                                        List.of(Map.of("key", "gateway.p12", "path", "gateway.p12")))),
                                        Map.of(
                                                "name",
                                                "budget",
                                                "persistentVolumeClaim",
                                                Map.of("claimName", config.budgetClaim())),
                                        Map.of("name", "tmp", "emptyDir", Map.of("sizeLimit", "64Mi"))))));
        return result;
    }

    private Map<String, Object> service(final String id, final String owner) {
        final var result = object("v1", "Service", metadata(id, owner));
        result.put(
                "spec",
                Map.of(
                        "type",
                        "ClusterIP",
                        "selector",
                        Map.of(RUNTIME, id, OWNER, owner),
                        "ports",
                        List.of(Map.of("port", config.port(), "targetPort", config.port(), "protocol", "TCP"))));
        return result;
    }

    private Map<String, Object> policy(final String id, final String owner, final boolean agent) {
        final var metadata = metadata(id, owner);
        if (agent) {
            metadata.put("name", gateway(id) + "-agent");
        }
        final var result = object("networking.k8s.io/v1", "NetworkPolicy", metadata);
        final var gatewayPeer = Map.of("podSelector", Map.of("matchLabels", Map.of(RUNTIME, id, OWNER, owner)));
        final var agentPeer = Map.of("podSelector", Map.of("matchLabels", Map.of("yca-runtime", id)));
        final var upstream = URI.create(config.upstreamEndpoint());
        final var upstreamPort =
                upstream.getPort() < 0 ? ("https".equals(upstream.getScheme()) ? 443 : 80) : upstream.getPort();
        result.put(
                "spec",
                Map.of(
                        "podSelector",
                        agent ? agentPeer.get("podSelector") : gatewayPeer.get("podSelector"),
                        "policyTypes",
                        List.of("Ingress", "Egress"),
                        "ingress",
                        agent ? List.of() : List.of(Map.of("from", List.of(agentPeer), "ports", ports(config.port()))),
                        "egress",
                        agent
                                ? List.of(Map.of("to", List.of(gatewayPeer), "ports", ports(config.port())))
                                : List.of(Map.of(
                                        "to",
                                        List.of(Map.of("ipBlock", Map.of("cidr", upstream.getHost() + "/32"))),
                                        "ports",
                                        ports(upstreamPort)))));
        return result;
    }

    private static List<Map<String, Object>> ports(final int port) {
        return List.of(Map.of("port", port, "protocol", "TCP"));
    }

    private static Map<String, Object> mount(final String name, final String path, final boolean readOnly) {
        return Map.of("name", name, "mountPath", path, "readOnly", readOnly);
    }

    private Map<String, Object> metadata(final String id, final String owner) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("name", gateway(id));
        result.put("namespace", namespace);
        result.put("labels", owner == null ? Map.of(RUNTIME, id) : Map.of(RUNTIME, id, OWNER, owner));
        return result;
    }

    private static Map<String, Object> object(final String api, final String kind, final Map<String, Object> metadata) {
        final var result = new LinkedHashMap<String, Object>();
        result.put("apiVersion", api);
        result.put("kind", kind);
        result.put("metadata", metadata);
        return result;
    }

    private void ownedAnchor(final Map<String, Object> value, final String id) {
        if (value == null
                || !gateway(id).equals(map(value.get("metadata")).get("name"))
                || !namespace.equals(map(value.get("metadata")).get("namespace"))
                || !id.equals(map(map(value.get("metadata")).get("labels")).get(RUNTIME))
                || !Boolean.TRUE.equals(value.get("immutable"))) {
            throw new IllegalStateException("Invalid provider anchor");
        }
    }

    private void owned(final Map<String, Object> value, final String id, final String owner) {
        if (value == null || !namespace.equals(map(value.get("metadata")).get("namespace"))) {
            throw new IllegalStateException("Provider resource missing or foreign");
        }
        final var labels = map(map(value.get("metadata")).get("labels"));
        if (!id.equals(labels.get(RUNTIME)) || !owner.equals(labels.get(OWNER))) {
            throw new IllegalStateException("Provider resource ownership mismatch");
        }
        uid(value);
    }

    private static boolean terminal(final Map<String, Object> value) {
        final var status = map(value.getOrDefault("status", Map.of()));
        final var containers = list(status.getOrDefault("containerStatuses", List.of()));
        if (!Set.of("Succeeded", "Failed").contains(status.getOrDefault("phase", "")) || containers.size() != 1) {
            return false;
        }
        final var container = containers.getFirst();
        final var state = map(container.getOrDefault("state", Map.of()));
        if (state.containsKey("terminated") && map(state.get("terminated")).get("exitCode") instanceof Number) {
            return true;
        }
        // Under the qualified healthy-kubelet prerequisite, terminal Failed prevents future starts.
        // Explicit startup-error waiting status, with no launch history, proves this container never
        // launched. Neither a terminal phase alone nor absent statuses is evidence of termination.
        return "Failed".equals(status.get("phase"))
                && map(value.get("metadata")).get("deletionTimestamp") instanceof String deletion
                && !deletion.isBlank()
                && "gateway".equals(container.get("name"))
                && Boolean.FALSE.equals(container.get("started"))
                && container.get("restartCount") instanceof Number restarts
                && "0".equals(restarts.toString())
                && (!container.containsKey("containerID") || "".equals(container.get("containerID")))
                && map(container.getOrDefault("lastState", Map.of())).isEmpty()
                && state.size() == 1
                && state.containsKey("waiting")
                && Set.of("ErrImagePull", "ImagePullBackOff", "InvalidImageName", "CreateContainerConfigError")
                        .contains(map(state.get("waiting")).getOrDefault("reason", ""));
    }

    private void delete(final String path, final Map<String, Object> value) {
        request(
                "DELETE",
                path,
                Map.of(
                        "apiVersion",
                        "v1",
                        "kind",
                        "DeleteOptions",
                        "gracePeriodSeconds",
                        30,
                        "preconditions",
                        Map.of("uid", uid(value))),
                true);
    }

    private void absent(final String path, final long deadline) {
        while (get(path) != null) {
            pause(deadline, "Provider deletion uncertain; retaining resources");
        }
    }

    private static void pause(final long deadline, final String failure) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
            throw new IllegalStateException(failure);
        }
        try {
            TimeUnit.NANOSECONDS.sleep(
                    Math.min(TimeUnit.MILLISECONDS.toNanos(10), Math.max(1, deadline - System.nanoTime())));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private Map<String, Object> get(final String path) {
        final var response = request("GET", path, null, true);
        try {
            return response == null ? null : map(json.fromString(Object.class, response));
        } catch (final RuntimeException malformed) {
            throw new IllegalStateException("Malformed provider resource; retain resources");
        }
    }

    private String request(
            final String method, final String path, final Map<String, Object> body, final boolean missing) {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Kubernetes interrupted; retain provider resources");
        }
        final long deadline = System.nanoTime() + config.requestTimeout().toNanos();
        final var subscriber = new BoundedBody();
        final var received = new CompletableFuture<String>();
        final var request = HttpRequest.newBuilder(URI.create("https://kubernetes.api" + path))
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", method.equals("PATCH") ? "application/merge-patch+json" : "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(json.toString(body)))
                .build();
        CompletableFuture<?> pending = null;
        try {
            pending = client.sendAsync(request, info -> {
                subscriber.getBody().whenComplete((bytes, error) -> {
                    if (error != null
                            || (!(info.statusCode() >= 200 && info.statusCode() < 300)
                                    && !(missing && info.statusCode() == 404))) {
                        received.completeExceptionally(new IllegalStateException("Kubernetes operation uncertain"));
                    } else {
                        received.complete(info.statusCode() == 404 ? null : new String(bytes, StandardCharsets.UTF_8));
                    }
                });
                return subscriber;
            });
            pending.whenComplete((response, error) -> {
                if (error != null) {
                    received.completeExceptionally(new IllegalStateException("Kubernetes operation uncertain"));
                }
            });
            return received.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kubernetes interrupted; retain provider resources");
        } catch (final Exception e) {
            // Do not propagate request bodies, private credentials or transport diagnostics.
            throw new IllegalStateException("Kubernetes operation uncertain; retain provider resources");
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
            subscriber.cancel();
        }
    }

    private List<String> resources(final String id) {
        final var name = gateway(id);
        return List.of(
                core() + "/services/" + name,
                policies() + '/' + name,
                policies() + '/' + name + "-agent",
                pods() + '/' + name,
                core() + "/secrets/" + name);
    }

    private String core() {
        return "/api/v1/namespaces/" + namespace;
    }

    private String pods() {
        return core() + "/pods";
    }

    private String policies() {
        return "/apis/networking.k8s.io/v1/namespaces/" + namespace + "/networkpolicies";
    }

    private static String collection(final String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    private static String kind(final String path) {
        return collection(path).substring(collection(path).lastIndexOf('/') + 1);
    }

    private static String gateway(final String id) {
        return id + "-provider";
    }

    private static void identity(final String id) {
        name(id);
        if (!id.startsWith("yca-") || id.length() > 48) {
            throw new IllegalArgumentException("Invalid provider runtime identity");
        }
    }

    private static void name(final String value) {
        if (value == null || !DNS_LABEL.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Kubernetes name");
        }
    }

    private static void positive(final Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid provider timeout");
        }
    }

    private static String uid(final Map<String, Object> value) {
        if (value == null) {
            throw new IllegalStateException("Provider anchor or resource missing");
        }
        return text(map(value.get("metadata")).get("uid"));
    }

    private static String decode(final Map<String, Object> values, final String key) {
        return new String(Base64.getDecoder().decode(text(values.get(key))), StandardCharsets.UTF_8);
    }

    private static String text(final Object value) {
        if (!(value instanceof String text) || text.isEmpty()) {
            throw new IllegalStateException("Malformed provider resource");
        }
        return text;
    }

    private static void ipv4(final String value) {
        if (!IPV4.matcher(value).matches()) {
            throw new IllegalStateException("Numeric IPv4 provider Service required");
        }
        for (final var part : value.split("\\.")) {
            if (Integer.parseInt(part) > 255) {
                throw new IllegalStateException("Invalid provider Service IP");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Malformed provider resource");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(final Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalStateException("Malformed provider resource list");
        }
        return (List<Map<String, Object>>) value;
    }

    private static List<String> listStrings(final Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException("Malformed provider finalizers");
        }
        return values.stream().map(KubernetesProviderServices::text).toList();
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean done;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public synchronized void onSubscribe(final Flow.Subscription value) {
            if (done || subscription != null) {
                value.cancel();
            } else {
                subscription = value;
                value.request(1);
            }
        }

        @Override
        public synchronized void onNext(final List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            for (final var buffer : buffers) {
                if (buffer.remaining() > 1024 * 1024 - bytes.size()) {
                    cancel();
                    return;
                }
                final var chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onComplete() {
            if (!done) {
                done = true;
                body.complete(bytes.toByteArray());
            }
        }

        @Override
        public synchronized void onError(final Throwable error) {
            cancel();
        }

        private synchronized void cancel() {
            if (!done) {
                done = true;
                if (subscription != null) {
                    subscription.cancel();
                }
                body.completeExceptionally(new IllegalStateException("Cannot read provider resource"));
            }
        }
    }
}
