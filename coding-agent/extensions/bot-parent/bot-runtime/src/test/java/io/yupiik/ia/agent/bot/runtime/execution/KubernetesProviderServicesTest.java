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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import io.yupiik.ia.agent.core.llm.JsonMappers;
import java.io.ByteArrayOutputStream;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KubernetesProviderServicesTest {
    private static final Pattern RESPONSES_PATH = Pattern.compile("/inference/[0-9a-f-]{36}/responses");
    private static final Pattern COMPLETIONS_PATH = Pattern.compile("/inference/[0-9a-f-]{36}/chat/completions");
    private static final String ID = "yca-plan";
    private static final String NAME = ID + "-provider";
    private static final String CORE = "/api/v1/namespaces/plans/";
    private static final String POD = CORE + "pods/" + NAME;
    private static final String SECRET = CORE + "secrets/" + NAME;
    private static final String SERVICE = CORE + "services/" + NAME;
    private static final Map<String, String> PRIVATE = Map.of(
            "YCA_SERVICES_PROVIDER_TOKEN", "a".repeat(43),
            "YCA_SERVICES_UPSTREAM_TOKEN", "private-provider-key",
            "YCA_SERVICES_KEYSTORE_PASSWORD", "private-tls-password");

    @Test
    void responsesConnectionAndPersistedSettingsPreserveThePinnedApi() throws Exception {
        try (final var cluster = new Cluster()) {
            final var services = new KubernetesProviderServices(
                    cluster.json, cluster.client, "plans", config("http://10.0.0.9:8080/v1/responses"));
            final var connection = services.start(ID, PRIVATE, "shared-provider", 3);
            assertEquals(connection, services.recover(ID));
            assertTrue(
                    RESPONSES_PATH.matcher(connection.endpoint().getRawPath()).matches());
            final var settings = map(cluster.json.fromString(
                    Object.class, decode(map(cluster.resources.get(SECRET).get("data")), "settings.json")));
            assertEquals("http://10.0.0.9:8080/v1/responses", settings.get("inferenceTarget"));
            assertEquals(connection.endpoint().getRawPath(), settings.get("inferencePath"));
        }
        for (final var suffix :
                List.of("/messages", "/codex/responses", "/responses/compact", "/responses/id", "/responses?other=1")) {
            assertThrows(IllegalArgumentException.class, () -> config("http://10.0.0.9:8080/v1" + suffix));
        }
    }

    @Test
    void createsSeparateHardenedGatewayAndRecoversWithoutProcessState() throws Exception {
        try (final var cluster = new Cluster()) {
            final var connection = cluster.services().start(ID, PRIVATE, "shared-provider", 3);
            assertEquals(connection, cluster.services().recover(ID));
            assertEquals("10.96.0.4", connection.address());
            assertEquals("provider.internal", connection.serverName());
            assertEquals("a".repeat(43), connection.capability());
            assertEquals("https", connection.endpoint().getScheme());
            assertTrue(COMPLETIONS_PATH.matcher(connection.endpoint().getPath()).matches());
            assertFalse(connection.toString().contains(connection.capability()));
            assertEquals(5, cluster.resources.size());
            final var secret = cluster.resources.get(SECRET);
            assertEquals(true, secret.get("immutable"));
            final var data = map(secret.get("data"));
            PRIVATE.forEach((key, value) -> assertEquals(value, decode(data, key)));
            assertEquals("shared-provider", decode(data, "YCA_PROVIDER_BUDGET_ID"));
            assertEquals("3", decode(data, "YCA_PROVIDER_BUDGET_SLOTS"));
            assertEquals("/budget", decode(data, "YCA_PROVIDER_BUDGET_ROOT"));
            final var settings = map(cluster.json.fromString(Object.class, decode(data, "settings.json")));
            assertEquals("http://10.0.0.9:8080/v1/chat/completions", settings.get("inferenceTarget"));
            assertEquals(connection.endpoint().getPath(), settings.get("inferencePath"));
            assertEquals("/tls/gateway.p12", settings.get("keyStore"));
            final var pod = cluster.resources.get(POD);
            final var spec = map(pod.get("spec"));
            assertEquals("budget-node", spec.get("nodeName"));
            assertEquals(false, spec.get("automountServiceAccountToken"));
            assertEquals(false, spec.get("enableServiceLinks"));
            assertEquals("Never", spec.get("restartPolicy"));
            final var containers = list(spec.get("containers"));
            assertEquals(1, containers.size());
            final var gateway = containers.getFirst();
            assertEquals(List.of("--config", "/private/settings.json"), gateway.get("args"));
            assertEquals(
                    List.of(
                            "java",
                            "-cp",
                            "@/app/jib-classpath-file",
                            "io.yupiik.ia.agent.bot.gateway.ProviderServicesGateway"),
                    gateway.get("command"));
            final var security = map(gateway.get("securityContext"));
            assertEquals(true, security.get("readOnlyRootFilesystem"));
            assertEquals(false, security.get("allowPrivilegeEscalation"));
            assertEquals(List.of("ALL"), map(security.get("capabilities")).get("drop"));
            assertEquals("RuntimeDefault", map(security.get("seccompProfile")).get("type"));
            for (final var env : list(gateway.get("env"))) {
                assertFalse(env.containsKey("value"));
                final var key = map(map(env.get("valueFrom")).get("secretKeyRef"));
                assertEquals(NAME, key.get("name"));
                assertEquals(env.get("name"), key.get("key"));
            }
            final var volumes = list(spec.get("volumes"));
            assertEquals(
                    "budget", map(volumes.get(2).get("persistentVolumeClaim")).get("claimName"));
            assertEquals("tls", map(volumes.get(1).get("secret")).get("secretName"));
            for (final var resource : cluster.resources.entrySet()) {
                if (!resource.getKey().equals(SECRET)) {
                    final var manifest = cluster.json.toString(resource.getValue());
                    PRIVATE.values().forEach(value -> assertFalse(manifest.contains(value)));
                }
            }
            assertTrue(cluster.calls.getFirst().startsWith("GET "));
            final var posts =
                    cluster.calls.stream().filter(c -> c.startsWith("POST")).toList();
            assertEquals("POST " + CORE + "secrets", posts.getFirst());
            assertEquals("POST " + CORE + "pods", posts.getLast());
            assertThrows(
                    IllegalStateException.class, () -> cluster.services().start(ID, PRIVATE, "shared-provider", 3));
        }
    }

    @Test
    void policiesSelectActualBackendAgentLabelAndOnlyNumericUpstream() throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.services().start(ID, PRIVATE, "budget", 1);
            final var policies = cluster.resources.values().stream()
                    .filter(r -> "NetworkPolicy".equals(r.get("kind")))
                    .toList();
            assertEquals(2, policies.size());
            final var agent = map(policies.stream()
                    .filter(p -> map(p.get("metadata")).get("name").equals(NAME + "-agent"))
                    .findFirst()
                    .orElseThrow()
                    .get("spec"));
            assertEquals(Map.of("matchLabels", Map.of("yca-runtime", ID)), agent.get("podSelector"));
            assertEquals(List.of(), agent.get("ingress"));
            assertEquals(List.of("Ingress", "Egress"), agent.get("policyTypes"));
            final var gateway = map(policies.stream()
                    .filter(p -> map(p.get("metadata")).get("name").equals(NAME))
                    .findFirst()
                    .orElseThrow()
                    .get("spec"));
            final var peer =
                    list(list(agent.get("egress")).getFirst().get("to")).getFirst();
            assertEquals(gateway.get("podSelector"), peer.get("podSelector"));
            assertFalse(peer.containsKey("namespaceSelector"));
            assertEquals(
                    List.of(Map.of("podSelector", agent.get("podSelector"))),
                    list(gateway.get("ingress")).getFirst().get("from"));
            assertEquals(
                    List.of(Map.of("ipBlock", Map.of("cidr", "10.0.0.9/32"))),
                    list(gateway.get("egress")).getFirst().get("to"));
            final var upstreamPort = list(list(gateway.get("egress")).getFirst().get("ports"));
            assertEquals(1, upstreamPort.size());
            assertEquals("TCP", upstreamPort.getFirst().get("protocol"));
            assertEquals(8080, ((Number) upstreamPort.getFirst().get("port")).intValue());
            final var gatewayPort = list(list(agent.get("egress")).getFirst().get("ports"));
            assertEquals(1, gatewayPort.size());
            assertEquals("TCP", gatewayPort.getFirst().get("protocol"));
            assertEquals(8443, ((Number) gatewayPort.getFirst().get("port")).intValue());
            assertFalse(cluster.json.toString(policies).contains("53"));
        }
    }

    @Test
    void stopIsRecoverableUidGuardedAndTerminalConfirmedBeforeAnySupportingDeletion() throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.services().start(ID, PRIVATE, "budget", 1);
            cluster.calls.clear();
            cluster.services().stop(ID);
            assertTrue(cluster.resources.isEmpty());
            final var deletes =
                    cluster.calls.stream().filter(c -> c.startsWith("DELETE")).toList();
            assertEquals("DELETE " + POD, deletes.getFirst());
            assertEquals("DELETE " + SECRET, deletes.getLast());
            assertTrue(cluster.calls.indexOf("PATCH " + POD) < cluster.calls.indexOf("DELETE " + SERVICE));
            cluster.services().stop(ID);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"running", "missing-exit", "foreign", "foreign-support", "missing-guard", "agent", "lost-pod"})
    void unsafeTerminationNeverDeletesSupportingResources(final String failure) throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.services().start(ID, PRIVATE, "budget", 1);
            cluster.failure = failure;
            if (failure.equals("foreign")) {
                map(map(cluster.resources.get(POD).get("metadata")).get("labels"))
                        .put("yca-provider-owner", "foreign");
            } else if (failure.equals("foreign-support")) {
                map(map(cluster.resources.get(SERVICE).get("metadata")).get("labels"))
                        .put("yca-provider-owner", "foreign");
            } else if (failure.equals("missing-guard")) {
                map(cluster.resources.get(POD).get("metadata")).put("finalizers", List.of());
            }
            cluster.calls.clear();
            assertThrows(IllegalStateException.class, () -> cluster.services().stop(ID));
            assertTrue(cluster.resources.containsKey(SECRET));
            assertTrue(cluster.resources.containsKey(SERVICE));
            assertFalse(cluster.calls.contains("DELETE " + SECRET));
            assertFalse(cluster.calls.contains("DELETE " + SERVICE));
            assertFalse(cluster.calls.contains("PATCH " + POD));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"services", "networkpolicies", "pods"})
    void partialCreateRetainsAnchorAndNewInstanceCanCleanUp(final String failedCollection) throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.failCreate = failedCollection;
            assertThrows(IllegalStateException.class, () -> cluster.services().start(ID, PRIVATE, "budget", 1));
            assertTrue(cluster.resources.containsKey(SECRET));
            cluster.services().stop(ID);
            assertTrue(cluster.resources.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ErrImagePull", "ImagePullBackOff", "InvalidImageName", "CreateContainerConfigError"})
    void acceptedNeverStartedPodCanBeCleanedAfterReadinessFailureOrLostCreateResponse(final String reason)
            throws Exception {
        for (final var loseResponse : List.of(false, true)) {
            try (final var cluster = new Cluster()) {
                cluster.startupStatus = neverStartedStatus(reason);
                cluster.startupStatus.put("phase", "Pending");
                cluster.deletionStatus = neverStartedStatus(reason);
                cluster.losePodCreateResponse = loseResponse;
                if (loseResponse) {
                    // Optional empty container identity/history fields can be omitted by the API.
                    list(cluster.deletionStatus.get("containerStatuses"))
                            .getFirst()
                            .remove("containerID");
                    list(cluster.deletionStatus.get("containerStatuses"))
                            .getFirst()
                            .remove("lastState");
                }
                assertThrows(
                        IllegalStateException.class, () -> cluster.services().start(ID, PRIVATE, "budget", 1));
                assertEquals(5, cluster.resources.size());
                assertTrue(cluster.calls.contains("POST " + CORE + "pods"));
                assertThrows(
                        IllegalStateException.class, () -> cluster.services().recover(ID));
                assertEquals(5, cluster.resources.size());
                assertFalse(cluster.calls.stream().anyMatch(c -> c.startsWith("DELETE") || c.startsWith("PATCH")));
                cluster.calls.clear();
                cluster.services().stop(ID);
                assertTrue(cluster.resources.isEmpty());
                final var deletes = cluster.calls.stream()
                        .filter(c -> c.startsWith("DELETE"))
                        .toList();
                assertEquals("DELETE " + POD, deletes.getFirst());
                assertEquals("DELETE " + SECRET, deletes.getLast());
                assertTrue(cluster.calls.indexOf("PATCH " + POD) < cluster.calls.indexOf("DELETE " + SERVICE));
                cluster.services().stop(ID);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-status",
                "empty-statuses",
                "multiple-statuses",
                "missing-exit",
                "previously-running",
                "previously-terminated",
                "restarted",
                "missing-restarts",
                "fractional-restarts",
                "string-restarts",
                "started",
                "missing-started",
                "string-started",
                "container-id",
                "null-container-id",
                "wrong-name",
                "missing-name",
                "unknown-reason",
                "missing-reason",
                "missing-waiting",
                "null-last-state",
                "running-state",
                "Pending",
                "Running",
                "Succeeded",
                "missing-deletion",
                "empty-deletion",
                "null-deletion"
            })
    void uncertainNeverStartedEvidenceRetainsFinalizerAndSupportingResources(final String evidence) throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.startupStatus = neverStartedStatus("ErrImagePull");
            cluster.startupStatus.put("phase", "Pending");
            assertThrows(IllegalStateException.class, () -> cluster.services().start(ID, PRIVATE, "budget", 1));
            cluster.deletionStatus = neverStartedStatus("ErrImagePull");
            final var container =
                    list(cluster.deletionStatus.get("containerStatuses")).getFirst();
            switch (evidence) {
                case "missing-status" -> cluster.deletionStatus.remove("containerStatuses");
                case "empty-statuses" -> cluster.deletionStatus.put("containerStatuses", List.of());
                case "multiple-statuses" ->
                    cluster.deletionStatus.put("containerStatuses", List.of(container, container));
                case "missing-exit" -> container.put("state", Map.of("terminated", Map.of()));
                case "previously-running" -> container.put("lastState", Map.of("running", Map.of()));
                case "previously-terminated" -> container.put("lastState", Map.of("terminated", Map.of("exitCode", 0)));
                case "restarted" -> container.put("restartCount", 1);
                case "missing-restarts" -> container.remove("restartCount");
                case "fractional-restarts" -> container.put("restartCount", 0.5);
                case "string-restarts" -> container.put("restartCount", "0");
                case "started" -> container.put("started", true);
                case "missing-started" -> container.remove("started");
                case "string-started" -> container.put("started", "false");
                case "container-id" -> container.put("containerID", "containerd://previous");
                case "null-container-id" -> container.put("containerID", null);
                case "wrong-name" -> container.put("name", "other");
                case "missing-name" -> container.remove("name");
                case "unknown-reason" ->
                    container.put("state", Map.of("waiting", Map.of("reason", "ContainerCreating")));
                case "missing-reason" -> container.put("state", Map.of("waiting", Map.of()));
                case "missing-waiting" -> container.put("state", Map.of());
                case "null-last-state" -> container.put("lastState", null);
                case "running-state" ->
                    container.put("state", Map.of("waiting", Map.of("reason", "ErrImagePull"), "running", Map.of()));
                case "Pending", "Running", "Succeeded" -> cluster.deletionStatus.put("phase", evidence);
                case "missing-deletion", "empty-deletion", "null-deletion" -> cluster.acknowledgePodDeletion = false;
                default -> fail("Unhandled evidence: " + evidence);
            }
            if (evidence.equals("empty-deletion")) {
                map(cluster.resources.get(POD).get("metadata")).put("deletionTimestamp", "");
            } else if (evidence.equals("null-deletion")) {
                map(cluster.resources.get(POD).get("metadata")).put("deletionTimestamp", null);
            }
            cluster.calls.clear();
            assertThrows(IllegalStateException.class, () -> cluster.services().stop(ID));
            assertEquals(5, cluster.resources.size());
            assertEquals(
                    List.of("yca.yupiik.io/provider-terminal-confirmed"),
                    map(cluster.resources.get(POD).get("metadata")).get("finalizers"));
            assertEquals(
                    List.of("DELETE " + POD),
                    cluster.calls.stream()
                            .filter(c -> c.startsWith("DELETE") || c.startsWith("PATCH"))
                            .toList());
        }
    }

    private static Map<String, Object> neverStartedStatus(final String reason) {
        return new LinkedHashMap<>(Map.of(
                "phase",
                "Failed",
                "containerStatuses",
                List.of(new LinkedHashMap<>(Map.of(
                        "name",
                        "gateway",
                        "started",
                        false,
                        "restartCount",
                        0,
                        "containerID",
                        "",
                        "lastState",
                        Map.of(),
                        "state",
                        Map.of("waiting", Map.of("reason", reason)))))));
    }

    @Test
    void lostCreateResponseIsRecoverableAndDoesNotAdoptOrDeleteCollisions() throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.loseCreate = true;
            assertThrows(IllegalStateException.class, () -> cluster.services().start(ID, PRIVATE, "budget", 1));
            assertTrue(cluster.resources.containsKey(SECRET));
            cluster.services().stop(ID);
            assertTrue(cluster.resources.isEmpty());
            cluster.resources.put(SERVICE, Map.of("kind", "Service"));
            cluster.calls.clear();
            assertThrows(IllegalStateException.class, () -> cluster.services().start(ID, PRIVATE, "budget", 1));
            assertFalse(cluster.calls.stream().anyMatch(c -> c.startsWith("POST") || c.startsWith("DELETE")));
        }
    }

    @Test
    void notReadyRecoveryRetainsResourcesWithoutDestructiveCalls() throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.failure = "not-ready";
            // Persist every resource, but fail the create response instead of waiting for readiness expiry.
            cluster.losePodCreateResponse = true;
            assertThrows(IllegalStateException.class, () -> cluster.services().start(ID, PRIVATE, "budget", 1));
            assertEquals(5, cluster.resources.size());
            assertEquals(
                    false,
                    list(map(cluster.resources.get(POD).get("status")).get("containerStatuses"))
                            .getFirst()
                            .get("ready"));
            assertFalse(cluster.calls.stream().anyMatch(c -> c.startsWith("DELETE") || c.startsWith("PATCH")));
            cluster.calls.clear();

            final var error = assertThrows(
                    IllegalStateException.class, () -> cluster.services().recover(ID));
            assertEquals("Provider readiness uncertain; retaining resources", error.getMessage());
            assertEquals(2, cluster.notReadyPodReads, "recovery must observe a non-ready Pod, not accept it");
            assertEquals(5, cluster.resources.size());
            assertFalse(cluster.calls.stream().anyMatch(c -> c.startsWith("DELETE") || c.startsWith("PATCH")));
        }
    }

    @Test
    void interruptedRecoveryRetainsResourcesWithoutDestructiveCalls() throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.losePodCreateResponse = true;
            assertThrows(IllegalStateException.class, () -> cluster.services().start(ID, PRIVATE, "budget", 1));
            assertEquals(5, cluster.resources.size());
            assertFalse(cluster.calls.stream().anyMatch(c -> c.startsWith("DELETE") || c.startsWith("PATCH")));
            cluster.calls.clear();
            Thread.currentThread().interrupt();
            try {
                final var error = assertThrows(
                        IllegalStateException.class, () -> cluster.services().recover(ID));
                assertEquals("Kubernetes interrupted; retain provider resources", error.getMessage());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertEquals(5, cluster.resources.size());
            assertTrue(cluster.calls.isEmpty());
        }
    }

    @Test
    void validatesTrustedInputsBeforeTransport() throws Exception {
        try (final var cluster = new Cluster()) {
            final var services = cluster.services();
            assertThrows(IllegalArgumentException.class, () -> services.start("../oops", PRIVATE, "budget", 1));
            assertThrows(IllegalArgumentException.class, () -> services.start(ID, Map.of(), "budget", 1));
            assertThrows(IllegalArgumentException.class, () -> services.start(ID, PRIVATE, "../budget", 1));
            assertThrows(IllegalArgumentException.class, () -> services.start(ID, PRIVATE, "budget", 0));
            assertThrows(
                    IllegalArgumentException.class, () -> config("http://provider.example:8080/v1/chat/completions"));
            assertThrows(IllegalArgumentException.class, () -> config("http://127.0.0.1:8080/v1/chat/completions"));
            assertTrue(cluster.calls.isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stalled", "oversized", "interrupted", "malformed"})
    void transportFailureIsBoundedCancelledAndNeverDeletesResources(final String failure) throws Exception {
        try (final var cluster = new Cluster()) {
            cluster.services().start(ID, PRIVATE, "budget", 1);
            cluster.calls.clear();
            cluster.failure = failure;
            try {
                final var error = assertThrows(
                        IllegalStateException.class, () -> cluster.services().stop(ID));
                assertNull(error.getCause());
                assertFalse(error.toString().contains("private-transport-detail"));
                if (failure.equals("interrupted")) {
                    assertTrue(Thread.currentThread().isInterrupted());
                }
            } finally {
                Thread.interrupted();
            }
            assertEquals(5, cluster.resources.size());
            assertFalse(cluster.calls.stream().anyMatch(c -> c.startsWith("DELETE") || c.startsWith("PATCH")));
            if (!failure.equals("malformed")) {
                assertTrue(cluster.cancelled.get());
            }
        }
    }

    @Test
    void reservesGatewayReadinessAndLaunchHeadroom() {
        assertEquals(
                980, KubernetesProviderServices.gatewayLifetimeSeconds(Duration.ofSeconds(900), Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> KubernetesProviderServices.gatewayLifetimeSeconds(Duration.ofDays(1), Duration.ofSeconds(1)));
    }

    private static KubernetesProviderServices.Config config(final String upstream) {
        return new KubernetesProviderServices.Config(
                "gateway@sha256:qualified",
                "tls",
                "provider.internal",
                upstream,
                "budget-node",
                "budget",
                "gateway",
                8443,
                // Fake responses are synchronous: uncertain state fails on the first observation,
                // without sleeping or relying on wall-clock polling in these sub-500ms tests.
                Duration.ofNanos(1),
                Duration.ofMillis(100),
                Duration.ofNanos(1),
                120,
                10);
    }

    private static String decode(final Map<String, Object> data, final String key) {
        return new String(Base64.getDecoder().decode((String) data.get(key)), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    /** In-process transport, but real request publishers, JSON bytes, response subscribers and Fusion client. */
    private static final class Cluster extends HttpClient implements AutoCloseable {
        private final JsonMapper json = JsonMappers.create(ignored -> Optional.empty());
        private final Map<String, Map<String, Object>> resources = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final KubernetesClient client = new KubernetesClient(new KubernetesClientConfiguration()
                .setMaster("http://fake.invalid")
                .setTokenEvaluator(() -> "cluster-token")
                .setClient(this));
        private String failure;
        private int notReadyPodReads;
        private String failCreate;
        private boolean loseCreate;
        private boolean losePodCreateResponse;
        private boolean acknowledgePodDeletion = true;
        private Map<String, Object> startupStatus;
        private Map<String, Object> deletionStatus;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private KubernetesProviderServices services() {
            return new KubernetesProviderServices(
                    json, client, "plans", config("http://10.0.0.9:8080/v1/chat/completions"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            final var path = request.uri().getPath();
            final var method = request.method();
            calls.add(method + ' ' + path);
            final var input = new ByteArrayOutputStreamSubscriber();
            request.bodyPublisher().ifPresent(p -> p.subscribe(input));
            final var body = input.bytes.size() == 0
                    ? Map.<String, Object>of()
                    : map(json.fromString(Object.class, input.bytes.toString(StandardCharsets.UTF_8)));
            int status = 200;
            Object output = Map.of();
            if (method.equals("POST")) {
                final var meta = map(body.get("metadata"));
                final var target = path + '/' + meta.get("name");
                if (path.endsWith('/' + String.valueOf(failCreate))) {
                    status = 500;
                } else if (resources.containsKey(target)) {
                    status = 409;
                } else {
                    meta.put("uid", "uid-" + resources.size());
                    meta.put("resourceVersion", "1");
                    if (body.containsKey("stringData")) {
                        final var data = new HashMap<String, Object>();
                        map(body.remove("stringData"))
                                .forEach((key, value) -> data.put(
                                        key,
                                        Base64.getEncoder()
                                                .encodeToString(((String) value).getBytes(StandardCharsets.UTF_8))));
                        body.put("data", data);
                    }
                    if (path.endsWith("/services")) {
                        map(body.get("spec")).put("clusterIP", "10.96.0.4");
                    } else if (path.endsWith("/pods")) {
                        body.put(
                                "status",
                                Map.of(
                                        "phase",
                                        "Running",
                                        "conditions",
                                        List.of(Map.of("type", "Ready", "status", "True")),
                                        "containerStatuses",
                                        List.of(Map.of("ready", !"not-ready".equals(failure)))));
                    }
                    if (target.equals(POD) && startupStatus != null) {
                        body.put("status", startupStatus);
                    }
                    resources.put(target, body);
                    if (loseCreate || (losePodCreateResponse && target.equals(POD))) {
                        return CompletableFuture.failedFuture(new IllegalStateException("private transport detail"));
                    }
                    status = 201;
                    output = body;
                }
            } else if (method.equals("GET")) {
                if (path.equals(POD) && "not-ready".equals(failure) && resources.containsKey(POD)) {
                    notReadyPodReads++;
                }
                if (path.endsWith("/pods")) {
                    output = Map.of(
                            "items",
                            "agent".equals(failure)
                                    ? List.of(Map.of("status", Map.of("phase", "Running")))
                                    : List.of());
                } else if (resources.containsKey(path)) {
                    output = resources.get(path);
                } else {
                    status = 404;
                }
            } else if (method.equals("DELETE")) {
                final var resource = resources.get(path);
                assertNotNull(resource);
                assertEquals(
                        map(resource.get("metadata")).get("uid"),
                        map(body.get("preconditions")).get("uid"));
                if (path.equals(POD)) {
                    if ("lost-pod".equals(failure)) {
                        resources.remove(path);
                    } else {
                        if (acknowledgePodDeletion) {
                            map(resource.get("metadata")).put("deletionTimestamp", "now");
                        }
                        resource.put(
                                "status",
                                Map.of(
                                        "phase",
                                        "running".equals(failure) ? "Running" : "Failed",
                                        "containerStatuses",
                                        List.of(Map.of(
                                                "state",
                                                Map.of(
                                                        "terminated",
                                                        "missing-exit".equals(failure)
                                                                ? Map.of()
                                                                : Map.of("exitCode", 143))))));
                        if (deletionStatus != null) {
                            resource.put("status", deletionStatus);
                        }
                    }
                } else {
                    resources.remove(path);
                }
            } else if (method.equals("PATCH")) {
                assertEquals(POD, path);
                final var meta = map(body.get("metadata"));
                final var actual = map(resources.get(path).get("metadata"));
                assertEquals(actual.get("uid"), meta.get("uid"));
                assertEquals(actual.get("resourceVersion"), meta.get("resourceVersion"));
                assertEquals(List.of(), meta.get("finalizers"));
                resources.remove(path);
            }
            final int code = status;
            final var headers = HttpHeaders.of(Map.of(), (k, v) -> true);
            final var subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override
                public int statusCode() {
                    return code;
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
            final var bytes = ("oversized".equals(failure)
                            ? "x".repeat(1024 * 1024 + 1)
                            : "malformed".equals(failure) ? "{private-transport-detail" : json.toString(output))
                    .getBytes(StandardCharsets.UTF_8);
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean sent;

                @Override
                public void request(final long count) {
                    if ("stalled".equals(failure) || "interrupted".equals(failure)) {
                        return;
                    }
                    if (!sent) {
                        sent = true;
                        subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
            if ("interrupted".equals(failure)) {
                Thread.currentThread().interrupt();
            }
            return subscriber.getBody().toCompletableFuture().thenApply(value -> new HttpResponse<T>() {
                @Override
                public int statusCode() {
                    return code;
                }

                @Override
                public HttpRequest request() {
                    return request;
                }

                @Override
                public Optional<HttpResponse<T>> previousResponse() {
                    return Optional.empty();
                }

                @Override
                public HttpHeaders headers() {
                    return headers;
                }

                @Override
                public T body() {
                    return value;
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
                public Version version() {
                    return Version.HTTP_1_1;
                }
            });
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
            throw new UnsupportedOperationException();
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

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                final HttpRequest request,
                final HttpResponse.BodyHandler<T> handler,
                final HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }

        @Override
        public void close() {
            client.close();
            json.close();
        }
    }

    private static final class ByteArrayOutputStreamSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(final ByteBuffer buffer) {
            final var chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }

        @Override
        public void onError(final Throwable error) {
            throw new AssertionError(error);
        }

        @Override
        public void onComplete() {
            /* Request body retained as real bytes. */
        }
    }
}
