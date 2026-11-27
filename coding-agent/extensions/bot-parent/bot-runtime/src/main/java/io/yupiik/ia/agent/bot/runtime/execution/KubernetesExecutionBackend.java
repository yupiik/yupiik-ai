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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.ia.agent.bot.runtime.ToolEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Fusion Kubernetes HTTP Job adapter. sharedRoot must be the dispatcher's mount of the named PVC's root,
 * not a host temporary directory. Attempts must already contain sanitized configuration.json,
 * storage, workspace and output, with configuration paths rooted at /attempt.
 * The trusted image's entrypoint must be the agent CLI. Requires a healthy, non-force-deleting
 * Kubernetes control plane/kubelet and exclusive ownership of yca-* names in the namespace.
 * No TTL is set: uncertain termination deliberately leaves resources for operator recovery.
 */
public final class KubernetesExecutionBackend {
    private static final Pattern KUBERNETES_NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");

    private static final int MAX_OUTPUT = 8 * 1024 * 1024;
    private final JsonMapper json;
    private final KubernetesClient client;
    private final String namespace;
    private final String claim;
    private final String image;
    private final Path sharedRoot;
    private final Duration timeout;
    private final Duration commandTimeout;
    private final long uid;
    private final long gid;
    private final String serviceAccount;
    private final String showToolOutput;

    public KubernetesExecutionBackend(
            final JsonMapper json,
            final KubernetesClient client,
            final String namespace,
            final String claim,
            final String image,
            final Path sharedRoot,
            final long uid,
            final long gid,
            final Duration timeout,
            final Duration commandTimeout,
            final String serviceAccount,
            final String showToolOutput) {
        if (json == null
                || client == null
                || image.isBlank()
                || uid <= 0
                || gid <= 0
                || timeout.toSeconds() < 1
                || commandTimeout.isNegative()
                || commandTimeout.isZero()) {
            throw new IllegalArgumentException("Trusted runtime and positive budgets/uid/gid required");
        }
        validateName(namespace);
        validateName(claim);
        validateName(serviceAccount);
        this.serviceAccount = serviceAccount;
        this.showToolOutput = showToolOutput;
        this.json = json;
        this.client = client;
        this.sharedRoot = checked(sharedRoot, true);
        this.namespace = namespace;
        this.claim = claim;
        this.image = image;
        this.uid = uid;
        this.gid = gid;
        this.timeout = timeout;
        this.commandTimeout = commandTimeout;
    }

    public int execute(
            final Path directory,
            final String runtimeId,
            final String prompt,
            final Map<String, String> credentials,
            final Map<String, String> toolEnvironment) {
        return executePrepared(directory, runtimeId, prompt, credentials, toolEnvironment, null);
    }

    /** Provider-only agent: no upstream credentials or budget volume; DNS-free TLS gateway access. */
    public int executeProviderOnly(
            final Path directory,
            final String runtimeId,
            final String prompt,
            final Map<String, String> toolEnvironment,
            final KubernetesProviderServices.Connection services) {
        if (services == null || !runtimeId.equals(services.runtimeId())) {
            throw new IllegalArgumentException("Provider connection must match execution identity");
        }
        return executePrepared(directory, runtimeId, prompt, Map.of(), toolEnvironment, services);
    }

    private int executePrepared(
            final Path directory,
            final String runtimeId,
            final String prompt,
            final Map<String, String> credentials,
            final Map<String, String> toolEnvironment,
            final KubernetesProviderServices.Connection services) {
        ToolEnvironment.validate(toolEnvironment);
        validateRuntimeIdentity(runtimeId);
        if (!Set.of(
                        "GH_TOKEN",
                        "GITHUB_BOT_FORK_TOKEN",
                        "GITHUB_SOURCE_TOKEN",
                        "YCA_CONTEXT_TOKEN",
                        "YCA_CONTEXT_GIT_TOKEN")
                .containsAll(credentials.keySet())) {
            throw new IllegalArgumentException("Unsupported executor credential variable");
        }
        final var attempt = checked(directory, true);
        if (!attempt.startsWith(sharedRoot) || attempt.equals(sharedRoot)) {
            throw new IllegalArgumentException("Attempt must be a subtree of the shared PVC root");
        }
        checked(attempt.resolve("configuration.json"), false);
        for (final var child : List.of("storage", "workspace", "output")) {
            checked(attempt.resolve(child), true);
        }
        final var relative = sharedRoot.relativize(attempt).toString();
        final int feedbackTimeout;
        try {
            feedbackTimeout = TrustedFeedback.timeout(attempt);
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot validate prepared feedback", e);
        }
        // Names are single-use and controlled exclusively by this dispatcher.
        if (request("GET", jobs() + '/' + runtimeId, null, true).code() != 404
                || request("GET", secrets() + '/' + runtimeId, null, true).code() != 404) {
            throw new IllegalStateException("Runtime identity already exists; recover with stop first");
        }
        final var env = new ArrayList<Environment>();
        env.add(new Environment("HOME", "/attempt/storage", null));
        env.add(new Environment("GH_CONFIG_DIR", "/attempt/storage/gh", null));
        toolEnvironment.forEach((key, value) -> env.add(new Environment(key, value, null)));
        for (final var key : credentials.keySet()) {
            env.add(new Environment(key, null, new EnvironmentSource(new SecretKey(runtimeId, key))));
        }
        final var mounts = new ArrayList<VolumeMount>();
        for (final var child : List.of("configuration.json", "storage", "workspace", "output")) {
            mounts.add(new VolumeMount(
                    "attempt", "/attempt/" + child, relative + "/" + child, child.equals("configuration.json")));
        }
        mounts.add(new VolumeMount("tmp", "/tmp", null, null));
        if (feedbackTimeout > 0) {
            mounts.add(new VolumeMount("attempt", "/attempt/feedback", relative + "/feedback", false));
        }
        final var arguments = new ArrayList<>(List.of(
                "run",
                "--show-tool-output",
                showToolOutput,
                "--yca-configuration-json",
                "/attempt/configuration.json",
                "--yca-configuration-project-enabled",
                "false",
                "--yca-storage-home",
                "/attempt/storage",
                "--yca-workingDir",
                "/attempt/workspace",
                "--result-file",
                "/attempt/output/result.json",
                "--result-required-fields",
                "taskId,summary|failure",
                "--result-max-bytes",
                "6291456",
                "--prompt",
                prompt));
        if (feedbackTimeout > 0) {
            arguments.addAll(List.of(
                    "--yca-bot-feedback-enabled",
                    "true",
                    "--yca-bot-feedback-timeoutSeconds",
                    Integer.toString(feedbackTimeout)));
        }
        final var container = new Container(
                "agent",
                image,
                "/attempt/workspace",
                arguments,
                env,
                mounts,
                new SecurityContext(
                        true,
                        uid,
                        gid,
                        true,
                        false,
                        new Capabilities(List.of("ALL")),
                        new SeccompProfile("RuntimeDefault")),
                new Resources(
                        Map.of("cpu", "1", "memory", "1Gi", "ephemeral-storage", "1Gi"),
                        Map.of("cpu", "2", "memory", "4Gi", "ephemeral-storage", "2Gi")));
        final var pod = new PodSpec(
                serviceAccount,
                false,
                false,
                "Never",
                30,
                List.of(container),
                List.of(
                        new Volume("attempt", new PersistentVolumeClaim(claim), null),
                        new Volume("tmp", null, new EmptyDirectory("1Gi"))),
                services == null ? null : List.of(new HostAlias(services.address(), List.of(services.serverName()))));
        final var job = new Job(
                "batch/v1",
                "Job",
                metadata(runtimeId),
                new JobSpec(
                        0,
                        1,
                        1,
                        timeout.toSeconds(),
                        new PodTemplate(new Metadata(null, null, Map.of("yca-runtime", runtimeId)), pod)));
        // create, never apply: an existing runtime must not be overwritten or adopted.
        // The Secret is sent over authenticated HTTP only; never staged on the shared PVC.
        request(
                "POST",
                secrets(),
                json.toString(new Secret("v1", "Secret", metadata(runtimeId), "Opaque", credentials)),
                false);
        try {
            request("POST", jobs(), json.toString(job), false);
            final long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                final var pods = pods(runtimeId);
                if (pods.size() == 1) {
                    final var status = pods.getFirst().status();
                    if (Set.of("Succeeded", "Failed").contains(status.phase())) {
                        final var statuses = status.containerStatuses();
                        if (statuses != null && statuses.size() == 1) {
                            final var terminated = statuses.getFirst().state().terminated();
                            if (terminated != null && terminated.exitCode() != null) {
                                final var podName = pods.getFirst().metadata().name();
                                validateName(podName);
                                final var logs = request(
                                        "GET",
                                        core() + "/pods/" + podName + "/log?container=agent&limitBytes=" + MAX_OUTPUT,
                                        null,
                                        false);
                                Files.write(
                                        attempt.resolve("execution.log"),
                                        logs.output(),
                                        StandardOpenOption.CREATE_NEW,
                                        StandardOpenOption.WRITE);
                                return terminated.exitCode();
                            }
                        }
                    }
                } else if (pods.size() > 1) {
                    throw new IllegalStateException("Ambiguous Job pod identity");
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Execution timeout");
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot retain executor log", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execution interrupted; stop must be retried", e);
        } finally {
            stop(runtimeId);
        }
    }

    public void stop(final String runtimeId) {
        validateRuntimeIdentity(runtimeId);
        if (request("GET", jobs() + '/' + runtimeId, null, true).code() != 404) {
            request("PATCH", jobs() + '/' + runtimeId, "{\"spec\":{\"suspend\":true}}", false);
        }
        final long deadline = System.nanoTime() + commandTimeout.toNanos();
        while (!pods(runtimeId).stream().allMatch(KubernetesExecutionBackend::terminal)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Pod termination uncertain; retaining Job and Secret");
            }
            pause();
        }
        request(
                "DELETE",
                jobs() + '/' + runtimeId,
                "{\"apiVersion\":\"v1\",\"kind\":\"DeleteOptions\",\"propagationPolicy\":\"Foreground\"}",
                true);
        final long deletionDeadline = System.nanoTime() + commandTimeout.toNanos();
        while (request("GET", jobs() + '/' + runtimeId, null, true).code() != 404
                || !pods(runtimeId).isEmpty()) {
            if (System.nanoTime() >= deletionDeadline) {
                throw new IllegalStateException("Job or Pods remain; retaining Secret");
            }
            pause();
        }
        request("DELETE", secrets() + '/' + runtimeId, null, true);
        final long secretDeadline = System.nanoTime() + commandTimeout.toNanos();
        while (request("GET", secrets() + '/' + runtimeId, null, true).code() != 404) {
            if (System.nanoTime() >= secretDeadline) {
                throw new IllegalStateException("Secret deletion uncertain; retry cleanup");
            }
            pause();
        }
    }

    private static boolean terminal(final Pod pod) {
        final var status = pod.status();
        if (status == null
                || status.phase() == null
                || !Set.of("Succeeded", "Failed").contains(status.phase())
                || status.containerStatuses() == null
                || status.containerStatuses().size() != 1) {
            return false;
        }
        final var state = status.containerStatuses().getFirst().state();
        return state != null && state.terminated() != null && state.terminated().exitCode() != null;
    }

    private void pause() {
        try {
            Thread.sleep(100);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Termination interrupted; retaining resources");
        }
    }

    private List<Pod> pods(final String id) {
        final var result = request("GET", core() + "/pods?labelSelector=yca-runtime%3D" + id, null, false);
        final var value = json.fromString(PodList.class, new String(result.output(), StandardCharsets.UTF_8));
        if (value == null || value.items() == null) {
            throw new IllegalStateException("Malformed pod response");
        }
        return value.items();
    }

    private Metadata metadata(final String id) {
        return new Metadata(id, namespace, null);
    }

    private String core() {
        return "/api/v1/namespaces/" + namespace;
    }

    private String secrets() {
        return core() + "/secrets";
    }

    private String jobs() {
        return "/apis/batch/v1/namespaces/" + namespace + "/jobs";
    }

    private Result request(final String method, final String path, final String input, final boolean allowMissing) {
        final var builder = HttpRequest.newBuilder(URI.create("https://kubernetes.api" + path))
                .timeout(commandTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "PATCH".equals(method) ? "application/merge-patch+json" : "application/json")
                .method(
                        method,
                        input == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(input));
        // Bound both the body and the complete exchange, not just receipt of headers.
        final long deadline = System.nanoTime() + commandTimeout.toNanos();
        final var body = new BoundedBodySubscriber();
        final var received = new CompletableFuture<Result>();
        try {
            final var pending = client.sendAsync(builder.build(), info -> {
                // HttpClient can dispatch its final response future completion to the common pool.
                // Complete our exchange directly from the subscriber instead of waiting on that dispatch.
                body.getBody().whenComplete((bytes, error) -> {
                    if (error != null) {
                        received.completeExceptionally(error);
                    } else {
                        received.complete(new Result(info.statusCode(), bytes));
                    }
                });
                return body;
            });
            try {
                pending.whenComplete((response, error) -> {
                    if (error != null) {
                        received.completeExceptionally(new IllegalStateException("Cannot read Kubernetes response"));
                    }
                });
                final var response = received.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (!(response.code() >= 200 && response.code() < 300) && !(allowMissing && response.code() == 404)) {
                    throw new IllegalStateException("Kubernetes rejected operation; runtime state uncertain");
                }
                return response;
            } finally {
                pending.cancel(true);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kubernetes interrupted; runtime state uncertain");
        } catch (final Exception e) {
            // Never expose response bodies, manifests or transport diagnostics containing credentials.
            throw new IllegalStateException("Kubernetes request failed; runtime state uncertain");
        } finally {
            body.cancel();
        }
    }

    // No blocking stream reads or common-pool tasks. The body stage completes only at EOF.
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final byte[] scratch = new byte[8192];
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
                return;
            }
            subscription = value;
            value.request(1);
        }

        @Override
        public synchronized void onNext(final List<ByteBuffer> buffers) {
            if (done) {
                return;
            }
            int remaining = MAX_OUTPUT - bytes.size();
            for (final var buffer : buffers) {
                if (buffer.remaining() > remaining) {
                    cancel();
                    return;
                }
                remaining -= buffer.remaining();
            }
            for (final var buffer : buffers) {
                while (buffer.hasRemaining()) {
                    final var size = Math.min(buffer.remaining(), scratch.length);
                    buffer.get(scratch, 0, size);
                    bytes.write(scratch, 0, size);
                }
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onError(final Throwable error) {
            cancel(); // Never retain transport diagnostics or response content in an exception.
        }

        @Override
        public synchronized void onComplete() {
            if (!done) {
                done = true;
                body.complete(bytes.toByteArray());
            }
        }

        private synchronized void cancel() {
            if (!done) {
                done = true;
                if (subscription != null) {
                    subscription.cancel();
                }
                body.completeExceptionally(new IllegalStateException("Cannot read Kubernetes response"));
            }
        }
    }

    private static Path checked(final Path path, final boolean directory) {
        final var absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (final var part : absolute) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic runtime paths are forbidden");
            }
        }
        if (directory
                ? !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                : !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Missing runtime path");
        }
        return absolute;
    }

    private static void validateName(final String value) {
        if (value == null
                || value.length() > 63
                || !KUBERNETES_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid Kubernetes name");
        }
    }

    private static void validateRuntimeIdentity(final String value) {
        validateName(value);
        if (!value.startsWith("yca-")) {
            throw new IllegalArgumentException("Invalid execution identity");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(final Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalStateException("Malformed runtime response");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(final Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalStateException("Malformed runtime response");
        }
        return (List<Map<String, Object>>) value;
    }

    @JsonModel
    public record Metadata(
            @Property(documentation = "Kubernetes resource name.")
            String name,

            @Property(documentation = "Kubernetes namespace.")
            String namespace,

            @Property(documentation = "Runtime labels.") Map<String, String> labels) {}

    @JsonModel
    public record Pod(
            @Property(documentation = "Pod identity.") Metadata metadata,

            @Property(documentation = "Observed pod lifecycle.")
            PodStatus status) {}

    @JsonModel
    public record PodStatus(
            @Property(documentation = "Observed phase.") String phase,

            @Property(documentation = "Observed agent containers.")
            List<ContainerStatus> containerStatuses) {}

    @JsonModel
    public record ContainerStatus(
            @Property(documentation = "Container runtime state.")
            ContainerState state) {}

    @JsonModel
    public record ContainerState(
            @Property(documentation = "Confirmed process termination.")
            Terminated terminated) {}

    @JsonModel
    public record Terminated(
            @Property(documentation = "Process exit code.") Integer exitCode) {}

    @JsonModel
    public record JobSpec(
            @Property(documentation = "Kubernetes backoffLimit setting.")
            int backoffLimit,

            @Property(documentation = "Kubernetes parallelism setting.")
            int parallelism,

            @Property(documentation = "Kubernetes completions setting.")
            int completions,

            @Property(documentation = "Kubernetes activeDeadlineSeconds setting.")
            long activeDeadlineSeconds,

            @Property(documentation = "Kubernetes template setting.")
            PodTemplate template) {}

    @JsonModel
    public record PodTemplate(
            @Property(documentation = "Kubernetes metadata setting.")
            Metadata metadata,

            @Property(documentation = "Kubernetes spec setting.")
            PodSpec spec) {}

    @JsonModel
    public record PodSpec(
            @Property(documentation = "Kubernetes serviceAccountName setting.")
            String serviceAccountName,

            @Property(documentation = "Kubernetes automountServiceAccountToken setting.")
            boolean automountServiceAccountToken,

            @Property(documentation = "Kubernetes enableServiceLinks setting.")
            boolean enableServiceLinks,

            @Property(documentation = "Kubernetes restartPolicy setting.")
            String restartPolicy,

            @Property(documentation = "Kubernetes terminationGracePeriodSeconds setting.")
            int terminationGracePeriodSeconds,

            @Property(documentation = "Kubernetes containers setting.")
            List<Container> containers,

            @Property(documentation = "Kubernetes volumes setting.")
            List<Volume> volumes,

            @Property(documentation = "DNS-free provider gateway address mapping.")
            List<HostAlias> hostAliases) {}

    @JsonModel
    public record HostAlias(
            @Property(documentation = "Provider Service numeric IP.")
            String ip,

            @Property(documentation = "Trusted provider TLS name.")
            List<String> hostnames) {}

    @JsonModel
    public record Container(
            @Property(documentation = "Kubernetes name setting.")
            String name,

            @Property(documentation = "Kubernetes image setting.")
            String image,

            @Property(documentation = "Kubernetes workingDir setting.")
            String workingDir,

            @Property(documentation = "Kubernetes args setting.")
            List<String> args,

            @Property(documentation = "Kubernetes env setting.")
            List<Environment> env,

            @Property(documentation = "Kubernetes volumeMounts setting.")
            List<VolumeMount> volumeMounts,

            @Property(documentation = "Kubernetes securityContext setting.")
            SecurityContext securityContext,

            @Property(documentation = "Kubernetes resources setting.")
            Resources resources) {}

    @JsonModel
    public record Environment(
            @Property(documentation = "Kubernetes name setting.")
            String name,

            @Property(documentation = "Kubernetes value setting.")
            String value,

            @Property(documentation = "Kubernetes valueFrom setting.")
            EnvironmentSource valueFrom) {}

    @JsonModel
    public record EnvironmentSource(
            @Property(documentation = "Kubernetes secretKeyRef setting.")
            SecretKey secretKeyRef) {}

    @JsonModel
    public record SecretKey(
            @Property(documentation = "Kubernetes name setting.")
            String name,

            @Property(documentation = "Kubernetes key setting.")
            String key) {}

    @JsonModel
    public record VolumeMount(
            @Property(documentation = "Kubernetes name setting.")
            String name,

            @Property(documentation = "Kubernetes mountPath setting.")
            String mountPath,

            @Property(documentation = "Kubernetes subPath setting.")
            String subPath,

            @Property(documentation = "Kubernetes readOnly setting.")
            Boolean readOnly) {}

    @JsonModel
    public record Volume(
            @Property(documentation = "Kubernetes name setting.")
            String name,

            @Property(documentation = "Kubernetes persistentVolumeClaim setting.")
            PersistentVolumeClaim persistentVolumeClaim,

            @Property(documentation = "Kubernetes emptyDir setting.")
            EmptyDirectory emptyDir) {}

    @JsonModel
    public record PersistentVolumeClaim(
            @Property(documentation = "Kubernetes claimName setting.")
            String claimName) {}

    @JsonModel
    public record EmptyDirectory(
            @Property(documentation = "Kubernetes sizeLimit setting.")
            String sizeLimit) {}

    @JsonModel
    public record SecurityContext(
            @Property(documentation = "Kubernetes runAsNonRoot setting.")
            boolean runAsNonRoot,

            @Property(documentation = "Kubernetes runAsUser setting.")
            long runAsUser,

            @Property(documentation = "Kubernetes runAsGroup setting.")
            long runAsGroup,

            @Property(documentation = "Kubernetes readOnlyRootFilesystem setting.")
            boolean readOnlyRootFilesystem,

            @Property(documentation = "Kubernetes allowPrivilegeEscalation setting.")
            boolean allowPrivilegeEscalation,

            @Property(documentation = "Kubernetes capabilities setting.")
            Capabilities capabilities,

            @Property(documentation = "Kubernetes seccompProfile setting.")
            SeccompProfile seccompProfile) {}

    @JsonModel
    public record Capabilities(
            @Property(documentation = "Kubernetes drop setting.")
            List<String> drop) {}

    @JsonModel
    public record SeccompProfile(
            @Property(documentation = "Kubernetes type setting.")
            String type) {}

    @JsonModel
    public record Resources(
            @Property(documentation = "Kubernetes requests setting.")
            Map<String, String> requests,

            @Property(documentation = "Kubernetes limits setting.")
            Map<String, String> limits) {}

    private record Result(int code, byte[] output) {}

    @JsonModel
    public record Job(
            @Property(documentation = "Kubernetes API version.")
            String apiVersion,

            @Property(documentation = "Kubernetes resource kind.")
            String kind,

            @Property(documentation = "Runtime resource identity.")
            Metadata metadata,

            @Property(documentation = "Bounded hardened Job specification.")
            JobSpec spec) {}

    @JsonModel
    public record Secret(
            @Property(documentation = "Kubernetes API version.")
            String apiVersion,

            @Property(documentation = "Kubernetes resource kind.")
            String kind,

            @Property(documentation = "Runtime resource identity.")
            Metadata metadata,

            @Property(documentation = "Secret encoding type.")
            String type,

            @Property(documentation = "Agent-only credentials, never staged on shared storage.")
            Map<String, String> stringData) {}

    @JsonModel
    public record PodList(
            @Property(documentation = "Pods selected by the unique runtime label.")
            List<Pod> items) {}
}
