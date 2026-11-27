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
package io.yupiik.ia.agent.github.service;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import io.yupiik.ia.agent.bot.runtime.DockerControlConfiguration;
import io.yupiik.ia.agent.bot.runtime.ProviderBudgetEnvironment;
import io.yupiik.ia.agent.bot.runtime.ResultReader;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmission;
import io.yupiik.ia.agent.bot.runtime.admission.HostAdmissionConfiguration;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.execution.AttemptRelay;
import io.yupiik.ia.agent.bot.runtime.execution.DockerExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.execution.KubernetesExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.model.KubeconfigDocument;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.core.llm.ProviderRequestBudget;
import io.yupiik.ia.agent.github.client.DiscussionClient;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.context.AssessmentBase;
import io.yupiik.ia.agent.github.context.BrokerAttempt;
import io.yupiik.ia.agent.github.context.GithubContextBroker;
import io.yupiik.ia.agent.github.context.GithubContextSnapshot;
import io.yupiik.ia.agent.github.context.GithubPrivateContext;
import io.yupiik.ia.agent.github.execution.AgentGitSource;
import io.yupiik.ia.agent.github.execution.AttemptPreparation;
import io.yupiik.ia.agent.github.execution.AttemptServices;
import io.yupiik.ia.agent.github.execution.GithubExecutionPolicy;
import io.yupiik.ia.agent.github.execution.RepositoryCache;
import io.yupiik.ia.agent.github.execution.cache.DependencyCache;
import io.yupiik.ia.agent.github.execution.cache.DependencyCacheProvisioning;
import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import io.yupiik.ia.agent.github.model.TaskState;
import io.yupiik.ia.agent.github.store.MutableTask;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Process-bounded dispatcher. Short durable transitions never encompass network/executor work.
 * All workers sharing a home must use the same parallelism (1..64). Stop all workers before
 * changing it, including when switching to/from the legacy exclusive worker.lock protocol.
 * Sidecar lock files must not be deleted: their stable inodes are crash-released leases.
 */
@ApplicationScoped
public class Worker {
    private static final Pattern REPOSITORY_ID = Pattern.compile("[1-9][0-9]*");

    private static final Logger LOGGER = Logger.getLogger(Worker.class.getName());
    private static final Pattern PULL_NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern TASK_ID = Pattern.compile("[a-zA-Z0-9-]+");
    private static final Pattern FORK_OWNER = Pattern.compile("[A-Za-z0-9-]+");

    private final GithubBotConfiguration conf;
    private final TaskStore store;
    private final GitHubClient github;
    private final JsonMapper json;
    private final ReentrantLock initialization = new ReentrantLock();
    private volatile boolean initialized;
    private DockerExecutionBackend backend;
    private Path attemptRoot;
    private NonoExecutionBackend nono;
    private final ExecutorConfiguration execution;
    private final BotAudit audit;
    private final Clock clock;
    private final TeamAuthorization authorization;
    private final AttemptPreparation preparation;
    private final GithubPrivateContext privateContext;
    private final GithubContextSnapshot snapshot;
    private final ForkProvisioner forks;
    private final ReviewPublisher reviews;
    private final StrictSolvePublisher strictSolve;
    private final DiscussionPublisher discussions;
    private final ContextConfiguration contexts;
    private final FixPublisher fixes;
    private final HostAdmissionConfiguration admissionConfiguration;
    private final Configuration sources;
    private final ProviderBudgetConfiguration budgetConfiguration;
    private final DockerControlConfiguration dockerControl;

    public Worker(
            final GithubBotConfiguration conf,
            final TaskStore store,
            final GitHubClient github,
            final JsonMapper json,
            final ExecutorConfiguration execution,
            final BotAudit audit,
            final TeamAuthorization authorization,
            final AttemptPreparation preparation,
            final GithubPrivateContext privateContext,
            final GithubContextSnapshot snapshot,
            final ForkProvisioner forks,
            final ReviewPublisher reviews,
            final StrictSolvePublisher strictSolve,
            final DiscussionPublisher discussions,
            final ContextConfiguration contexts,
            final FixPublisher fixes,
            final HostAdmissionConfiguration admission,
            final Clock clock,
            final Configuration sources,
            final ProviderBudgetConfiguration budgetConfiguration,
            final DockerControlConfiguration dockerControl) {
        this.conf = conf;
        this.store = store;
        this.github = github;
        this.json = json;
        this.clock = clock;
        this.execution = execution;
        this.audit = audit;
        this.authorization = authorization;
        this.preparation = preparation;
        this.privateContext = privateContext;
        this.snapshot = snapshot;
        this.forks = forks;
        this.reviews = reviews;
        this.strictSolve = strictSolve;
        this.discussions = discussions;
        this.contexts = contexts;
        this.fixes = fixes;
        this.admissionConfiguration = admission;
        this.sources = sources;
        this.budgetConfiguration = budgetConfiguration;
        this.dockerControl = dockerControl;
    }

    private void initializeBackend() {
        initialization.lock();
        try {
            if (!initialized) {
                configureBackend();
                initialized = true;
            }
        } finally {
            initialization.unlock();
        }
    }

    private void configureBackend() {
        final var requestBudget = ProviderBudgetEnvironment.select(budgetConfiguration);
        try {
            ProviderRequestBudget.fromConfiguration(budgetConfiguration);
        } catch (final IOException invalid) {
            throw new IllegalArgumentException("Cannot initialize the trusted provider request budget", invalid);
        }
        if (!requestBudget.isEmpty()
                && !(execution.backend() == ExecutorConfiguration.Backend.DOCKER
                        && execution.docker().services() != null)
                && execution.relay().bind().isBlank()) {
            throw new IllegalArgumentException(
                    "Shared provider request budget requires restricted services or the trusted inference relay");
        }
        this.attemptRoot = execution.attemptRoot().isBlank()
                ? null
                : Path.of(execution.attemptRoot()).toAbsolutePath().normalize();
        final var stateRoot = Path.of(conf.home()).toAbsolutePath().normalize();
        if (attemptRoot != null && (attemptRoot.startsWith(stateRoot) || stateRoot.startsWith(attemptRoot))) {
            throw new IllegalArgumentException("Attempt root and dispatcher state must be disjoint");
        }
        final var timeout = Duration.ofSeconds(execution.timeoutSeconds());
        switch (execution.backend()) {
            case DOCKER -> {
                if (execution.docker().egress() != null
                        && !execution.relay().bind().isBlank()) {
                    throw new IllegalArgumentException(
                            "Docker enforced egress cannot reach the private credential relay; use a public HTTPS provider or disable one mode");
                }
                this.backend = new DockerExecutionBackend(
                        GithubExecutionPolicy.POLICY,
                        execution.docker().executable(),
                        execution.docker().image(),
                        timeout,
                        execution.docker().network(),
                        execution.docker().egress(),
                        execution.docker().services(),
                        execution.showToolOutput(),
                        execution.docker().disableContainerLogs());
                this.nono = null;
            }
            case NONO -> {
                if (execution.nono().agentExecutable().isBlank()
                        || execution.nono().runtimeRoot().isBlank()) {
                    throw new IllegalArgumentException("Nono requires a trusted agent executable and runtime root");
                }
                this.backend = null;
                this.nono = new NonoExecutionBackend(
                        json,
                        execution.nono().executable(),
                        Path.of(execution.nono().agentExecutable()),
                        Path.of(execution.nono().runtimeRoot()),
                        Path.of(conf.home()),
                        timeout,
                        Duration.ofSeconds(execution.commandTimeoutSeconds()),
                        execution.showToolOutput());
            }
            case KUBERNETES -> {
                if (attemptRoot == null) {
                    throw new IllegalArgumentException("Kubernetes requires a dedicated attempt root");
                }
                this.backend = null;
                this.nono = null;
            }
            default -> throw new IllegalArgumentException("Unsupported executor backend");
        }
    }

    private KubernetesClient kubernetesClient() {
        final var settings = execution.kubernetes();
        final var configuration = new KubernetesClientConfiguration()
                .setToken(settings.token())
                .setCertificates(settings.certificates())
                .setSkipTls(settings.skipTls());
        if (!settings.master().isBlank()) {
            configuration.setMaster(settings.master());
        }
        if (!settings.kubeconfig().isBlank()) {
            final var path = Path.of(settings.kubeconfig()).toAbsolutePath().normalize();
            if (!path.getFileName().toString().endsWith(".json")) {
                throw new IllegalArgumentException(
                        "github.executor.kubernetes.kubeconfig must be JSON without exec authentication");
            }
            try {
                final var content = json.fromString(KubeconfigDocument.class, Files.readString(path));
                if (content == null
                        || content.users() == null
                        || content.users().stream()
                                .anyMatch(entry -> entry == null
                                        || entry.user() == null
                                        || entry.user().containsKey("exec")
                                        || entry.user().containsKey("auth-provider"))) {
                    throw new IllegalArgumentException(
                            "Kubernetes kubeconfig requires static credentials, not external authentication plugins");
                }
            } catch (final IOException e) {
                throw new IllegalArgumentException("Cannot read Kubernetes kubeconfig");
            }
            configuration.setKubeconfig(path);
        }
        if (!settings.privateKey().isBlank()) {
            configuration.setPrivateKey(settings.privateKey());
        }
        if (!settings.privateKeyCertificate().isBlank()) {
            configuration.setPrivateKeyCertificate(settings.privateKeyCertificate());
        }
        return new KubernetesClient(configuration);
    }

    private KubernetesExecutionBackend kubernetesBackend(final KubernetesClient client) {
        final var settings = execution.kubernetes();
        return new KubernetesExecutionBackend(
                json,
                client,
                settings.namespace(),
                settings.claim(),
                settings.image(),
                attemptRoot,
                settings.uid(),
                settings.gid(),
                Duration.ofSeconds(execution.timeoutSeconds()),
                Duration.ofSeconds(execution.commandTimeoutSeconds()),
                settings.serviceAccount(),
                execution.showToolOutput());
    }

    /** One invocation processes one task, keeping poll scheduling independent from agent latency. */
    public void run(final String requestedId, final WorkerOptions options) {
        initializeBackend();
        if (options.maxRetries() < 0 || execution.timeoutSeconds() <= 0 || execution.commandTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Invalid execution budget");
        }
        if (options.parallelism() < 1 || options.parallelism() > 64) {
            throw new IllegalArgumentException("Worker parallelism must be between 1 and 64");
        }
        store.initialize();
        final var home = Path.of(conf.home()).toAbsolutePath().normalize();
        try (final var slot = acquireSlot(home, options)) {
            if (slot == null) {
                return;
            }
            // Snapshot only candidates. A candidate is authoritative only after leasing and rereading it.
            final var candidates = store.transaction(state -> state.list().stream()
                    .filter(it -> requestedId == null || requestedId.isBlank() || requestedId.equals(it.id()))
                    .filter(this::eligible)
                    .map(it -> String.valueOf(it.id()))
                    .toList());
            for (final var id : candidates) {
                // IDs are store-generated UUIDs, never paths supplied by callers.
                if (!TASK_ID.matcher(id).matches()) {
                    throw new IllegalStateException("Invalid task identity");
                }
                try (final var lease = tryAcquire(home.resolve("task-" + id + ".lock"))) {
                    if (lease == null) {
                        continue;
                    }
                    final var task = store.transaction(state -> {
                        final var value = state.tasks().get(id);
                        return value == null ? null : value.document();
                    });
                    if (task == null || !eligible(task)) {
                        continue;
                    }
                    if (options.source().mode() == WorkerOptions.SourceMode.git
                            && (agentCommenting(task, options) || task.kind() != TaskKind.solve)) {
                        throw new IllegalArgumentException(
                                "Git source mode requires a strict solve (agentCommenting=false)");
                    }
                    if (options.source().mode() == WorkerOptions.SourceMode.broker
                            && (agentCommenting(task, options)
                                    || !options.cacheRoot().isBlank()
                                    || options.privateSource())) {
                        throw new IllegalArgumentException(
                                "Broker mode requires a strict solve/review/fix and cannot share export or anonymous cache staging");
                    }
                    if (task.kind() == TaskKind.fix && agentCommenting(task, options)) {
                        throw new IllegalArgumentException(
                                "Fix requires agentCommenting=false; only the host may update an existing PR");
                    }
                    final var admission = new HostAdmission(admissionConfiguration);
                    admission.validateDisjoint(
                            home, attemptRoot == null ? Path.of(System.getProperty("java.io.tmpdir")) : attemptRoot);
                    final var admitted = admission.tryAcquire();
                    if (admitted.isEmpty()) {
                        return;
                    }
                    try (final var hostLease = admitted.orElseThrow()) {
                        process(home, task, options, hostLease);
                    }
                    return;
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException("Worker storage failure", e);
        }
    }

    private boolean eligible(final TaskDocument task) {
        return Set.of(TaskState.pending, TaskState.running, TaskState.publishing)
                        .contains(task.state())
                || (Set.of(TaskState.done, TaskState.failed).contains(task.state())
                        && (task.attemptDirectory() != null));
    }

    private boolean agentCommenting(final TaskDocument task, final WorkerOptions options) {
        // Advisory reviews are always read-only; even compatibility credential mode cannot grant
        // an assessment agent permission to implement the proposal or mutate the target.
        return options.agentCommenting()
                && !(task.kind() == TaskKind.review
                        && Set.of("issue", "discussion")
                                .contains(task.reviewTarget() == null ? "" : task.reviewTarget()));
    }

    private Lease acquireSlot(final Path home, final WorkerOptions options) throws IOException {
        if (options.parallelism() == 1) {
            return tryAcquire(home.resolve("worker.lock"));
        }
        for (int slot = 0; slot < options.parallelism(); slot++) {
            final var lease = tryAcquire(home.resolve("worker-" + slot + ".lock"));
            if (lease != null) {
                return lease;
            }
        }
        return null;
    }

    private Lease tryAcquire(final Path path) throws IOException {
        final var channel =
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean acquired = false;
        try {
            final var lock = channel.tryLock();
            if (lock == null) {
                return null;
            }
            acquired = true;
            return new Lease(channel, lock);
        } catch (final OverlappingFileLockException e) {
            // Same-JVM contention is local to this lease, not to other slots or candidates.
            return null;
        } finally {
            if (!acquired) {
                channel.close();
            }
        }
    }

    private record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try (channel) {
                lock.release();
            }
        }
    }

    private void process(
            final Path home,
            final TaskDocument task,
            final WorkerOptions options,
            final HostAdmission.Lease hostLease) {
        if (task.state() != TaskState.running) {
            hostLease.confirmTermination(); // This invocation has not made launch possible yet.
        }
        audit.record("worker-selected", task);
        final var id = String.valueOf(task.id());
        if (Set.of(TaskState.done, TaskState.failed).contains(task.state())) {
            // A crash after the terminal transition must not strand credential-bearing attempt data.
            // Terminal tasks were already stopped before publication; never execute or publish them again.
            if (task.attemptDirectory() != null) {
                final var directory = task.attemptDirectory();
                cleanup(task, directory);
                store.transaction(state -> {
                    state.task(id).attemptDirectory(null);
                    return null;
                });
            }
            return;
        }
        if ((task.state() == TaskState.running)) {
            recover(task, options, hostLease);
        }
        final var current = store.transaction(state -> state.task(id).document());
        if ((current.state() == TaskState.publishing)) {
            publish(current, options);
            return;
        }
        if (current.attemptDirectory() != null) {
            final var previous = current.attemptDirectory();
            cleanup(current, previous);
            store.transaction(state -> {
                state.task(id).attemptDirectory(null);
                return null;
            });
        }
        if (!authorized(current)) {
            audit.record("authorization-denied", current);
            throw new IllegalStateException("Execution blocked: the requesting user is no longer authorized");
        }
        var stage = "reconciliation";
        try {
            final var prior = existingPullRequest(current, options);
            final AgentResult result;
            if ((current.kind() == TaskKind.help)) {
                result = new AgentResult(null, null, "Help requested.", null, null, null, null, null, null, null);
            } else if (prior != null) {
                result = new AgentResult(
                        BigDecimal.ONE,
                        id,
                        "Reconciled the task pull request from a previous attempt.",
                        null,
                        prior,
                        null,
                        null,
                        null,
                        null,
                        null);
            } else {
                stage = "execution";
                result = execute(current, options, hostLease);
            }
            stage = "result-staging";
            stage(id, result);
        } catch (final RuntimeException e) {
            final var interruptedTask =
                    store.transaction(state -> state.task(id).document());
            if (!"execution".equals(stage)) {
                retainFailure(interruptedTask, stage, e, null);
            }
            if ((interruptedTask.state() == TaskState.running)) {
                // A failed stop deliberately escapes: never retry or clean up an uncertain runtime.
                stopRuntime(interruptedTask, hostLease);
            }
            failedAttempt(id, e, options);
        }
        final var completed = store.transaction(state -> state.task(id).document());
        if ((completed.state() == TaskState.publishing)) {
            publish(completed, options);
        }
    }

    private boolean authorized(final TaskDocument task) {
        return task.actorId() == null || authorization.allows(task.repository(), task.actorId(), null);
    }

    private void recover(final TaskDocument task, final WorkerOptions options, final HostAdmission.Lease hostLease) {
        // Persisted runtime identity precedes launch. An uncertain stop blocks recovery, not starts another agent.
        stopRuntime(task, hostLease);
        try {
            stage(String.valueOf(task.id()), readResult(task));
        } catch (final IOException | IllegalArgumentException e) {
            retainFailure(task, "recovery-result", e, null);
            failedAttempt(String.valueOf(task.id()), e, options);
        }
    }

    private void stopRuntime(final TaskDocument task, final HostAdmission.Lease hostLease) {
        hostLease.markUncertain();
        try {
            stopRuntimeChecked(task);
            hostLease.confirmTermination();
        } catch (final RuntimeException e) {
            retainFailure(task, "runtime-stop", e, null);
            throw e;
        }
    }

    private void stopRuntimeChecked(final TaskDocument task) {
        final var identity = task.runtimeId();
        if (identity == null || !identity.equals("yca-" + task.id() + "-" + task.attempts())) {
            throw new IllegalStateException("Recovery requires the persisted task-bound runtime identity");
        }
        if (!execution
                .backend()
                .name()
                .equalsIgnoreCase(
                        String.valueOf((task.executionBackend() == null ? "docker" : task.executionBackend())))) {
            throw new IllegalStateException("Restore the persisted execution backend before recovery");
        }
        if ((task.executionFingerprint() != null) && !executionFingerprint().equals(task.executionFingerprint())) {
            throw new IllegalStateException("Restore the persisted executor configuration before recovery");
        }
        if (task.runtimeId() != null) {
            final var runtime = task.runtimeId();
            switch (execution.backend()) {
                case DOCKER -> backend.stop(runtime);
                case NONO -> nono.stop(runtime);
                case KUBERNETES -> {
                    try (final var client = kubernetesClient()) {
                        kubernetesBackend(client).stop(runtime);
                    }
                }
            }
        }
    }

    private AgentResult execute(
            final TaskDocument initialTask, final WorkerOptions options, final HostAdmission.Lease hostLease) {
        var task = initialTask;
        final var id = task.id();
        Path directory = null;
        BrokerAttempt brokerContext = null;
        AttemptServices privateServices = null;
        Path privateServicesDirectory = null;
        DependencyCache dependencyCache = null;
        DependencyCache.Baseline cacheBaseline = null;
        String cacheIdentity = null;
        Map<String, String> toolEnvironment = Map.of();
        var stage = "preparation";
        Integer exitCode = null;
        final int attempt = task.attempts() + 1;
        try {
            final boolean restricted = execution.backend() == ExecutorConfiguration.Backend.DOCKER
                    && execution.docker().services() != null;
            if (restricted
                    && (options.source().mode() != WorkerOptions.SourceMode.broker
                            || agentCommenting(task, options)
                            || !execution.relay().bind().isBlank())) {
                throw new IllegalArgumentException(
                        "Private services require credential-free broker mode without a second relay");
            }
            final var runtime = "yca-" + id + "-" + attempt;
            final var config = options.agentConfiguration().isBlank()
                    ? Path.of(conf.home()).resolve("agent-configuration.json")
                    : Path.of(options.agentConfiguration());
            directory = preparation.prepare(config.toAbsolutePath().normalize(), attemptRoot);
            if (!agentCommenting(task, options) && task.kind() == TaskKind.review) {
                stage = "runtime-preflight";
                GithubContextBroker.verifyRuntime(directory.resolve("workspace"));
            }
            stage = "source-staging";
            if (AssessmentBase.isAssessment(task)) {
                if (!options.cacheRoot().isBlank()) {
                    throw new IllegalArgumentException(
                            "Advisory reviews require pinned context and cannot use the moving anonymous clone cache");
                }
                final var base = AssessmentBase.pin(github, task);
                store.transaction(state -> {
                    final var actual = state.task(id);
                    final var recovery =
                            new LinkedHashMap<String, Object>(actual.recovery() == null ? Map.of() : actual.recovery());
                    recovery.putAll(base.recovery());
                    actual.recovery(recovery);
                    return null;
                });
                task = store.transaction(state -> state.task(id).document());
            }
            if (!agentCommenting(task, options) && (task.kind() == TaskKind.solve)) {
                if (options.forkOwner().isBlank() || !options.cacheRoot().isBlank()) {
                    throw new IllegalArgumentException(
                            "Strict solve requires a fork owner and cannot use the anonymous clone cache");
                }
                final var context = privateContext.stageSolve(
                        task, directory, options.source().mode());
                if (options.source().mode() == WorkerOptions.SourceMode.git) {
                    AgentGitSource.stage(
                            directory,
                            task.repository(),
                            context.baseSha(),
                            options.source(),
                            execution.backend() == ExecutorConfiguration.Backend.NONO
                                    ? Path.of(execution.nono().runtimeRoot())
                                            .toAbsolutePath()
                                            .normalize()
                                            .resolve("bin/sh")
                                    : Path.of("/bin/sh"));
                }
                store.transaction(state -> {
                    final var actual = state.task(id);
                    if ((actual.strictSolveForkOwner() != null)
                            && !options.forkOwner().equals(actual.strictSolveForkOwner())) {
                        throw new IllegalStateException("Restore the pinned strict solve fork owner");
                    }
                    actual.strictSolveForkOwner(options.forkOwner());
                    actual.strictSolveBaseSha(context.baseSha());
                    actual.strictSolveBaseBranch(context.baseBranch());
                    return null;
                });
                task = store.transaction(state -> state.task(id).document());
            }
            if (!agentCommenting(task, options) && task.kind() == TaskKind.fix) {
                final var target = fixes.prepare(task);
                privateContext.stageFix(
                        task,
                        directory,
                        target.repository(),
                        target.headSha(),
                        target.branch(),
                        options.source().mode());
                store.transaction(state -> {
                    final var actual = state.task(id);
                    actual.strictSolveBaseSha(target.headSha());
                    actual.strictSolveBaseBranch(target.branch());
                    return null;
                });
                task = store.transaction(state -> state.task(id).document());
            }
            if (!agentCommenting(task, options)
                    && (task.kind() == TaskKind.review)
                    && options.source().mode() != WorkerOptions.SourceMode.broker) {
                snapshot.stage(task, directory);
                if (options.privateSource()) {
                    privateContext.stage(task, directory);
                }
            }
            if (!options.cacheRoot().isBlank()) {
                if (options.privateSource()) {
                    throw new IllegalArgumentException(
                            "Private source staging cannot share the anonymous clone-cache destination");
                }
                if (!"https://api.github.com".equals(conf.api())) {
                    throw new IllegalArgumentException("Anonymous clone cache supports public github.com only");
                }
                final var cache = Path.of(options.cacheRoot()).toAbsolutePath().normalize();
                final var stateRoot = Path.of(conf.home()).toAbsolutePath().normalize();
                if (cache.startsWith(stateRoot)
                        || stateRoot.startsWith(cache)
                        || (attemptRoot != null && (cache.startsWith(attemptRoot) || attemptRoot.startsWith(cache)))) {
                    throw new IllegalArgumentException("Clone cache must be disjoint from state and attempt roots");
                }
                new RepositoryCache(cache, options.cacheGit(), Duration.ofSeconds(execution.timeoutSeconds()))
                        .stage("https://github.com/" + task.repository() + ".git", directory.resolve("workspace"));
            }
            stage = "credentials-and-fork";
            final var credentials = new LinkedHashMap<String, String>();
            if (options.source().mode() == WorkerOptions.SourceMode.broker) {
                if (!"https://api.github.com".equals(conf.api())) {
                    throw new IllegalArgumentException(
                            "Brokered Git acquisition currently supports canonical github.com only");
                }
                if (execution.backend() == ExecutorConfiguration.Backend.DOCKER) {
                    dockerControl.requireLocal();
                    if (execution.docker().egress() != null) {
                        throw new IllegalArgumentException(
                                "Broker mode cannot bypass enforced Docker egress; configure and accept a supported routed topology before enabling it");
                    }
                    backend.requireLocalDaemon();
                }
                final var principal = new MutableTask(task);
                principal.attempts(attempt);
                principal.runtimeId(runtime);
                brokerContext = new BrokerAttempt(
                        contexts,
                        options.source(),
                        github,
                        json,
                        principal.document(),
                        Path.of(conf.home()).toAbsolutePath().normalize(),
                        directory,
                        attemptRoot,
                        clock,
                        Duration.ofSeconds(execution.timeoutSeconds()));
                final var binding = brokerContext.storageBinding(principal.document());
                store.transaction(state -> {
                    final var actual = state.task(id);
                    final var recovery =
                            new LinkedHashMap<String, Object>(actual.recovery() == null ? Map.of() : actual.recovery());
                    recovery.put("contextStorage", binding);
                    actual.recovery(recovery);
                    return null;
                });
                credentials.putAll(brokerContext.credentials());
                preparation.stageBroker(
                        directory,
                        brokerContext.endpoint(),
                        brokerContext.service().primaryRepository(),
                        brokerContext.service().metadata(),
                        options.source(),
                        execution.backend() == ExecutorConfiguration.Backend.NONO
                                ? Path.of(execution.nono().runtimeRoot())
                                        .toAbsolutePath()
                                        .normalize()
                                : null);
                if (restricted) {
                    privateServicesDirectory = Path.of((String) binding.get("path"));
                }
            }
            if (options.source().mode() == WorkerOptions.SourceMode.git) {
                final var sourceCredentials = new LinkedHashMap<String, String>();
                final var names =
                        new ArrayList<>(List.of("GH_TOKEN", "GH_AGENT_TOKEN", "GITHUB_BOT_FORK_TOKEN", "GITHUB_TOKEN"));
                names.add(options.source().tokenEnv());
                names.add(conf.auth().tokenEnv());
                names.add(conf.auth().agentTokenEnv());
                for (final var name : names) {
                    if (name != null && !name.isEmpty()) {
                        sources.get(name).ifPresent(value -> sourceCredentials.put(name, value));
                    }
                }
                credentials.putAll(AgentGitSource.credentials(options.source(), conf.auth(), sourceCredentials));
            }
            final var agentToken = sources.get(conf.auth().agentTokenEnv()).orElse(null);
            if (agentCommenting(task, options) && agentToken != null && !agentToken.isBlank()) {
                credentials.put("GH_TOKEN", agentToken);
            }
            final var forkToken = sources.get("GITHUB_BOT_FORK_TOKEN").orElse(null);
            if (agentCommenting(task, options) && forkToken != null && !forkToken.isBlank()) {
                credentials.put("GITHUB_BOT_FORK_TOKEN", forkToken);
            }
            if ((task.kind() == TaskKind.solve)) {
                if (options.forkOwner().isBlank()
                        || (agentCommenting(task, options) && (agentToken == null || agentToken.isBlank()))) {
                    throw new IllegalArgumentException(
                            "Solving requires a configured fork owner and separate agent token");
                }
                if (options.provisionFork()) {
                    forks.ensure(id, String.valueOf(task.repository()), options.forkOwner());
                } else {
                    verifyFork(String.valueOf(task.repository()), options);
                }
            }
            stage = "dependency-cache";
            if (options.dependencyCache() != null && options.dependencyCache().enabled()) {
                validateDependencyCacheRoot(Path.of(options.dependencyCache().root()), directory, options.cacheRoot());
                // Invalid provisioning is a configuration/safety failure, not a cache miss.
                // Restore first: provisioning files must not be included in a restored entry.
                try {
                    final var repository = github.get("/repos/" + task.repository(), RestResponses.Repository.class);
                    final var repositoryId = String.valueOf(repository.id());
                    if (!REPOSITORY_ID.matcher(repositoryId).matches()) {
                        throw new IOException("Dependency cache requires an immutable repository identity");
                    }
                    cacheIdentity = conf.api() + "#" + repositoryId;
                    dependencyCache = new DependencyCache(options.dependencyCache(), clock);
                    dependencyCache.maintain(64);
                    cacheBaseline = dependencyCache.restore(cacheIdentity, directory.resolve("storage"));
                } catch (final IOException | RuntimeException cacheFailure) {
                    dependencyCache = null;
                    cacheBaseline = null;
                    LOGGER.warning("Dependency cache unavailable; continuing without cache publication");
                }
                toolEnvironment = DependencyCacheProvisioning.provision(
                        options.dependencyCache(),
                        directory,
                        execution.backend() != ExecutorConfiguration.Backend.NONO);
            }
            if (privateServicesDirectory != null) {
                // Launcher-owned proxy settings must win over generated tool configuration.
                // MAVEN_OPTS can still select the private dependency cache repository.
                privateServices = new AttemptServices(
                        directory.resolve("configuration.json"),
                        privateServicesDirectory,
                        json,
                        execution.docker().services(),
                        brokerContext.privateEndpoint(),
                        brokerContext.endpoint(),
                        credentials,
                        sources,
                        budgetConfiguration,
                        Duration.ofSeconds(execution.timeoutSeconds()));
            }
            stage = "launch-preparation";
            final var attemptPath = directory.toString();
            // Once launch becomes possible, absence of a queue reference is not proof of termination.
            hostLease.markUncertain();
            Files.delete(directory.resolve(".prepared-only"));
            store.transaction(state -> {
                final var actual = state.task(id);
                actual.state(TaskState.running);
                actual.attempts(attempt);
                actual.runtimeId(runtime);
                actual.executionBackend(execution.backend().name().toLowerCase(Locale.ROOT));
                actual.executionFingerprint(executionFingerprint());
                actual.attemptDirectory(attemptPath);
                actual.startedAt(clock.instant().toString());
                return null;
            });
            task = store.transaction(state -> state.task(id).document());
            audit.record("execution-starting", task);
            final var prompt = preparation.prompt(
                    task,
                    options.forkOwner(),
                    agentCommenting(task, options),
                    options.source().mode());
            final int exit;
            stage = "relay-and-context";
            try (final var context = brokerContext;
                    final var services = privateServices;
                    final var relay = execution.relay().bind().isBlank()
                            ? null
                            : new AttemptRelay(
                                    directory.resolve("configuration.json"),
                                    json,
                                    execution.relay().bind(),
                                    execution.relay().advertised(),
                                    budgetConfiguration);
                    final var broker = !agentCommenting(task, options) && (task.kind() == TaskKind.review)
                            ? (context == null
                                    ? new GithubContextBroker(github, json, task, directory)
                                    : new GithubContextBroker(context.service(), json, task, directory))
                            : null) {
                stage = "execution";
                exit = switch (execution.backend()) {
                    case DOCKER ->
                        services == null
                                ? backend.execute(directory, runtime, prompt, credentials, toolEnvironment)
                                : backend.execute(
                                        directory,
                                        runtime,
                                        prompt,
                                        credentials,
                                        toolEnvironment,
                                        services.settings(),
                                        services.credentials(),
                                        null);
                    case NONO -> nono.execute(directory, runtime, prompt, credentials, toolEnvironment);
                    case KUBERNETES -> {
                        try (final var client = kubernetesClient()) {
                            yield kubernetesBackend(client)
                                    .execute(directory, runtime, prompt, credentials, toolEnvironment);
                        }
                    }
                };
            }
            exitCode = exit;
            if (exit != 0) {
                throw new IllegalArgumentException("Agent exited unsuccessfully");
            }
            // A client process exiting is not sufficient evidence that its remote runtime stopped.
            stage = "runtime-stop";
            stopRuntime(store.transaction(state -> state.task(id).document()), hostLease);
            stage = "result-validation";
            final var result = readResult(task);
            if (dependencyCache != null
                    && cacheBaseline != null
                    && (result.failure() == null || result.failure().isBlank())) {
                // execute/stopRuntime have established quiescence. Keep attempt storage until
                // admitted publications settle; never let a cache failure retry a successful task.
                try {
                    final var publication = publishDependencyCache(
                            dependencyCache, cacheIdentity, directory.resolve("storage"), cacheBaseline, options);
                    LOGGER.info("Dependency cache: published=" + publication.published()
                            + ", unchanged=" + publication.unchanged() + ", conflicted=" + publication.conflicted()
                            + ", timedOut=" + publication.timedOut());
                    dependencyCache.maintain(64);
                } catch (final IOException | RuntimeException cacheFailure) {
                    LOGGER.warning("Dependency cache publication failed; retaining the task result");
                }
            }
            return result;
        } catch (final IOException | RuntimeException e) {
            // Capture before either provisioning cleanup or the outer stop/retry/publication path.
            // Early failures have no durable attempt directory or runtime yet.
            final var failed =
                    new MutableTask(store.transaction(state -> state.task(id).document()));
            failed.attempts(attempt);
            if (failed.state() != TaskState.running) {
                failed.runtimeId(null);
            }
            if (directory != null) {
                failed.attemptDirectory(directory.toString());
            }
            retainFailure(failed.document(), stage, e, exitCode);
            // Provisioning failure occurred before durable launch; remove secrets/workspace immediately.
            if (directory != null
                    && !store.<Boolean>transaction(
                            state -> TaskState.running == state.task(id).state())) {
                try {
                    AttemptPreparation.delete(directory);
                } catch (final IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            if (e instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalArgumentException("Execution filesystem failure", e);
        } finally {
            if (privateServices != null) {
                privateServices.close();
            }
            if (brokerContext != null) {
                brokerContext.close();
            }
        }
    }

    private DependencyCache.Publication publishDependencyCache(
            final DependencyCache cache,
            final String identity,
            final Path storage,
            final DependencyCache.Baseline baseline,
            final WorkerOptions options)
            throws IOException {
        final var settled = new CountDownLatch(1);
        final var policy = options.dependencyCache();
        final var shutdown = Thread.ofPlatform().name("github-cache-drain").unstarted(() -> {
            try {
                // Protect admitted work against ordinary JVM shutdown, but never claim that
                // Java can interrupt a blocked filesystem call or survive SIGKILL.
                settled.await(
                        (long) policy.gracefulTimeoutSeconds() + policy.limits().operationTimeoutSeconds() + 10,
                        TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            return cache.publish(identity, storage, baseline);
        } finally {
            settled.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (final IllegalStateException shutdownInProgress) {
                // The hook observes the settled latch; attempt cleanup can now proceed.
            }
        }
    }

    private void validateDependencyCacheRoot(final Path configured, final Path directory, final String sourceCache) {
        final var protectedRoots = new ArrayList<Path>();
        protectedRoots.add(Path.of(conf.home()));
        protectedRoots.add(directory);
        if (!sourceCache.isBlank()) {
            protectedRoots.add(Path.of(sourceCache));
        }
        if (attemptRoot != null) {
            protectedRoots.add(attemptRoot);
        }
        if (contexts != null && !contexts.storageRoot().isBlank()) {
            protectedRoots.add(Path.of(contexts.storageRoot()));
        }
        if (execution.backend() == ExecutorConfiguration.Backend.NONO) {
            // Nono's stateRoot constructor argument is conf.home(), already excluded above.
            protectedRoots.add(Path.of(execution.nono().runtimeRoot()));
        }
        DependencyCacheProvisioning.validatePrivateRoot(configured, protectedRoots);
    }

    private AgentResult readResult(final TaskDocument task) throws IOException {
        final var directory = task.attemptDirectory();
        if (directory == null) {
            throw new IllegalArgumentException("No recoverable execution result");
        }
        final var path = Path.of(directory).resolve("output/result.json");
        Path parent = path.toAbsolutePath().normalize().getRoot();
        for (final var segment : path.toAbsolutePath().normalize()) {
            parent = parent.resolve(segment);
            if (Files.isSymbolicLink(parent)) {
                throw new IllegalArgumentException("Symbolic structured result paths are forbidden");
            }
        }
        final long resultLimit = (task.strictSolveBaseSha() != null) ? 6L * 1024 * 1024 : 1_000_000;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > resultLimit) {
            throw new IllegalArgumentException("No bounded structured result");
        }
        final AgentResult parsed;
        try {
            parsed = json.fromString(AgentResult.class, ResultReader.read(path, (int) resultLimit));
        } catch (final IllegalStateException e) {
            throw new IllegalArgumentException("Invalid structured result", e);
        }
        if (parsed == null) {
            throw new IllegalArgumentException("Structured result must be a JSON object");
        }
        if (parsed.schemaVersion() == null
                || parsed.schemaVersion().compareTo(BigDecimal.ONE) != 0
                || !task.id().equals(parsed.taskId())
                || (parsed.summary() == null || parsed.summary().isBlank())
                        && (parsed.failure() == null || parsed.failure().isBlank())) {
            throw new IllegalArgumentException("Structured result schema/task identity mismatch");
        }
        validateReviewResult(task, parsed);
        return parsed;
    }

    private void validateReviewResult(final TaskDocument task, final AgentResult result) {
        if (task.kind() != TaskKind.review) {
            if (result.assessment() != null) {
                throw new IllegalArgumentException("Assessments are only valid for advisory reviews");
            }
            return;
        }
        if (!Set.of("pull_request", "issue", "discussion")
                .contains(task.reviewTarget() == null ? "" : task.reviewTarget())) {
            throw new IllegalArgumentException("Review target classification is required");
        }
        final boolean advisory = !"pull_request".equals(task.reviewTarget());
        if (result.prUrl() != null
                || result.strictSolve() != null
                || (advisory && (result.review() != null || result.reviewId() != null))
                || (result.failure() != null && result.assessment() != null)
                || (!advisory && result.assessment() != null)) {
            throw new IllegalArgumentException("Result payload does not match the review target");
        }
        if (advisory && result.failure() == null) {
            // Rendering also performs bounded semantic validation, before any publication intent.
            new AssessmentRenderer().render(result.assessment());
        }
    }

    private void verifyFork(final String repository, final WorkerOptions options) {
        if (!FORK_OWNER.matcher(options.forkOwner()).matches()) {
            throw new IllegalArgumentException("Invalid fork owner");
        }
        final var fork = github.get(
                "/repos/" + options.forkOwner() + "/" + repository.split("/")[1], RestResponses.Repository.class);
        if (fork == null
                || !Boolean.TRUE.equals(fork.fork())
                || fork.parent() == null
                || !repository.equalsIgnoreCase(fork.parent().full_name())) {
            throw new IllegalArgumentException("Pre-provisioned fork does not belong to the expected upstream");
        }
    }

    private String existingPullRequest(final TaskDocument task, final WorkerOptions options) {
        if (!(task.kind() == TaskKind.solve)
                || !agentCommenting(task, options)
                || (task.strictSolveBaseSha() != null)
                || ((Number) task.attempts()).intValue() == 0
                || options.forkOwner().isBlank()) {
            return null;
        }
        final var head = URLEncoder.encode(options.forkOwner() + ":github-bot/" + task.id(), StandardCharsets.UTF_8);
        String next = "/repos/" + task.repository() + "/pulls?state=open&head=" + head + "&per_page=100";
        while (next != null) {
            final var page = github.<List<RestResponses.Pull>>page(
                    next, new Types.ParameterizedTypeImpl(List.class, RestResponses.Pull.class));
            for (final var pr : page.body()) {
                if (validPullRequest(task, pr, options)) {
                    return String.valueOf(pr.html_url());
                }
            }
            next = page.next();
        }
        return null;
    }

    private boolean validPullRequest(
            final TaskDocument task, final RestResponses.Pull pr, final WorkerOptions options) {
        if (pr == null || !"open".equals(pr.state()) || pr.head() == null || pr.base() == null) {
            return false;
        }
        final var head = pr.head();
        final var base = pr.base();
        if (head.repo() == null || base.repo() == null) {
            return false;
        }
        final var repo = String.valueOf(task.repository());
        final var fork = options.forkOwner() + "/" + repo.split("/")[1];
        return ("github-bot/" + task.id()).equals(head.ref())
                && fork.equalsIgnoreCase(head.repo().full_name())
                && repo.equalsIgnoreCase(base.repo().full_name());
    }

    private void stage(final String id, final AgentResult result) {
        if (result.failure() != null) {
            retainFailure(
                    store.transaction(state -> state.task(id).document()),
                    "agent-result",
                    new IllegalArgumentException(result.failure()),
                    0);
        }
        store.transaction(state -> {
            final var task = state.task(id);
            task.result(result);
            task.state(TaskState.publishing);
            return null;
        });
    }

    private void failedAttempt(final String id, final Exception failure, final WorkerOptions options) {
        // Errors are intentionally classified, never copied from secret-bearing tool logs to GitHub.
        store.transaction(state -> {
            final var task = state.task(id);
            if (task.state() == TaskState.publishing) {
                return null;
            }
            final int attempts = ((Number) task.attempts()).intValue();
            if (attempts >= 1 + options.maxRetries() || task.state() != TaskState.running) {
                task.state(TaskState.publishing);
                task.result(new AgentResult(
                        null,
                        null,
                        null,
                        "I could not complete the task because executor setup or execution failed. The operator can inspect the task diagnostics.",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
            } else {
                task.state(TaskState.pending);
            }
            task.lastError(failure.getClass().getSimpleName());
            return null;
        });
        audit.record(
                "execution-failed", store.transaction(state -> state.task(id).document()));
    }

    private void publish(final TaskDocument task, final WorkerOptions options) {
        try {
            publishChecked(task, options);
        } catch (final RuntimeException e) {
            final var current = store.transaction(state -> state.task(task.id()).document());
            if (current.lastFailure() == null || Objects.equals(current.lastFailure(), task.lastFailure())) {
                retainFailure(current, "publication", e, null);
            }
            throw e;
        }
    }

    private void publishChecked(final TaskDocument task, final WorkerOptions options) {
        final var id = String.valueOf(task.id());
        final var prefix = "/repos/" + task.repository() + "/issues/" + task.number() + "/comments";
        final var marker = "<!-- yca-task:" + id + " -->";
        final boolean discussion = "discussion".equals(task.sourceType());
        String next = discussion ? null : prefix + "?per_page=100";
        while (next != null) {
            final var page = github.<List<RestResponses.FeedItem>>page(
                    next, new Types.ParameterizedTypeImpl(List.class, RestResponses.FeedItem.class));
            for (final var comment : page.body()) {
                if (comment != null
                        && String.valueOf(comment.body()).contains(marker)
                        && comment.user() != null
                        && conf.login()
                                .equalsIgnoreCase(String.valueOf(comment.user().login()))) {
                    finish(
                            task,
                            "commentUrl",
                            String.valueOf(comment.html_url()),
                            Boolean.TRUE.equals(task.publicationFailure()));
                    return;
                }
            }
            next = page.next();
        }
        if (!discussion && (task.publicationIntent() != null)) {
            throw new IllegalStateException("Ambiguous final comment; reconcile previous write before retrying");
        }
        // Existing comments may be reconciled after revocation, but no new privileged write is allowed.
        // Preserve staged results and intents so an operator can restore authorization or reconcile them.
        if (!authorized(task) && !discussion) {
            audit.record("authorization-denied", task);
            throw new IllegalStateException("Publication blocked: the requesting user is no longer authorized");
        }
        final var result = task.result();
        validateReviewResult(task, result);
        boolean failure = result.failure() != null;
        String outcome = failure ? publicFailure(task) : "";
        if (!failure && (task.kind() == TaskKind.help)) {
            outcome = "Mention `@" + conf.login()
                    + " review` on a pull request for code review, or on an issue or enabled Discussion for an advisory assessment. Use `@"
                    + conf.login() + " solve <request>` on an issue or enabled Discussion.";
        } else if (!failure && (task.kind() == TaskKind.review)) {
            if ("pull_request".equals(task.reviewTarget())) {
                // The native review is the response. Do not announce it in a second comment.
                final var reviewUrl = reviews.publish(task, result);
                finish(task, "reviewUrl", reviewUrl, false);
                return;
            }
            outcome = new AssessmentRenderer().render(result.assessment());
        } else if (!failure && task.kind() == TaskKind.fix) {
            outcome = "I updated the existing pull request: " + fixes.publish(task, result);
        } else if (!failure && (task.kind() == TaskKind.solve) && (task.strictSolveBaseSha() != null)) {
            outcome = "I opened a pull request: " + strictSolve.publish(task, result, options.forkOwner());
        } else if (!failure && result.prUrl() != null) {
            final var url = result.prUrl();
            final var expected = "https://github.com/" + task.repository() + "/pull/";
            if (url.startsWith(expected)
                    && PULL_NUMBER.matcher(url.substring(expected.length())).matches()) {
                final var pr = github.get(
                        "/repos/" + task.repository() + "/pulls/" + url.substring(expected.length()),
                        RestResponses.Pull.class);
                if (validPullRequest(task, pr, options)) {
                    outcome = "I opened a pull request: " + url;
                }
            }
        }
        if (outcome.isEmpty()) {
            outcome = "I failed to produce an independently verified pull request or review.";
            failure = true;
        }
        final boolean failed = failure;
        if (discussion) {
            store.transaction(state -> {
                state.task(id).publicationFailure(failed);
                return null;
            });
            final var url = discussions.publish(task, marker + "\n" + outcome, () -> authorized(task));
            finish(task, "commentUrl", url, failed);
            return;
        }
        store.transaction(state -> {
            if (state.task(id).publicationIntent() != null) {
                throw new IllegalStateException("Final comment already claimed; reconcile before another write");
            }
            state.task(id).publicationIntent(marker);
            state.task(id).publicationFailure(failed);
            return null;
        });
        audit.record(
                "publication-starting",
                store.transaction(state -> state.task(id).document()));
        final var posted = github.post(
                prefix, new GitHubClient.CommentRequest(marker + "\n" + outcome), RestResponses.FeedItem.class);
        final var postedUrl = posted == null ? "null" : String.valueOf(posted.html_url());
        final var expectedUrl =
                "https://github.com/" + task.repository() + "/issues/" + task.number() + "#issuecomment-";
        final var expectedPullUrl =
                "https://github.com/" + task.repository() + "/pull/" + task.number() + "#issuecomment-";
        if (!(postedUrl.startsWith(expectedUrl)
                        && PULL_NUMBER
                                .matcher(postedUrl.substring(expectedUrl.length()))
                                .matches())
                && !(postedUrl.startsWith(expectedPullUrl)
                        && PULL_NUMBER
                                .matcher(postedUrl.substring(expectedPullUrl.length()))
                                .matches())) {
            throw new IllegalStateException("Unverified final comment URL; reconcile previous write before retrying");
        }
        finish(task, "commentUrl", postedUrl, failed);
    }

    private void finish(
            final TaskDocument task, final String responseKey, final String responseUrl, final boolean failed) {
        final var id = String.valueOf(task.id());
        store.transaction(state -> {
            final var actual = state.task(id);
            actual.state(failed ? TaskState.failed : TaskState.done);
            switch (responseKey) {
                case "commentUrl" -> actual.commentUrl(responseUrl);
                case "reviewUrl" -> actual.reviewUrl(responseUrl);
                default -> throw new IllegalArgumentException("Unsupported response field: " + responseKey);
            }
            actual.completedAt(clock.instant().toString());
            actual.result(null);
            return null;
        });
        if (task.attemptDirectory() != null) {
            final var directory = task.attemptDirectory();
            cleanup(task, directory);
            store.transaction(state -> {
                state.task(id).attemptDirectory(null);
                return null;
            });
        }
        audit.record("completed", store.transaction(state -> state.task(id).document()));
    }

    private String publicFailure(final TaskDocument task) {
        final var detail = task.lastFailure();
        return "I could not complete the requested task. "
                + (detail == null ? "" : detail.message() + " ")
                + "The operator can inspect the retained private task diagnostics.";
    }

    private String lookupFailure(final Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof GitHubClient.DiscussionGraphqlException) {
                return "GitHub could not complete the Discussion operation. Check host-token permissions, repository access and GitHub service or rate-limit status; the target was not inferred to be deleted.";
            }
            if (cause instanceof DiscussionClient.MissingDiscussionNodeException) {
                return "The Discussion or a related comment is missing or inaccessible to the host token. Check the target and repository access.";
            }
            if (cause instanceof GitHubClient.HttpException http) {
                return switch (http.status()) {
                    case 404 ->
                        "The target or required repository context is missing or inaccessible to the host token. Check the target and repository access.";
                    case 401, 403 ->
                        "GitHub denied access to the target or required context. Check host-token permissions, repository access and rate limits.";
                    default -> null;
                };
            }
        }
        return null;
    }

    private String executionFingerprint() {
        // Client settings can contain PEM credentials. Persist only a digest, never the configuration.
        final var identity = "runtime-ownership-v2:" + GithubExecutionPolicy.POLICY + ':' + execution;
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void cleanup(final TaskDocument task, final String directory) {
        try {
            cleanupChecked(task, directory);
        } catch (final RuntimeException e) {
            retainFailure(task, "cleanup", e, null);
            throw e;
        }
    }

    private void cleanupChecked(final TaskDocument task, final String directory) {
        try {
            BrokerAttempt.cleanup(
                    task,
                    contexts.storageRoot().isBlank()
                            ? Path.of(conf.home()).resolve("context")
                            : Path.of(contexts.storageRoot()));
        } catch (final IOException invalid) {
            throw new IllegalStateException("Private context recovery cleanup failed", invalid);
        }
        if ("nono".equalsIgnoreCase(String.valueOf(task.executionBackend()))) {
            if (nono == null
                    || ((task.executionFingerprint() != null)
                            && !executionFingerprint().equals(task.executionFingerprint()))) {
                throw new IllegalStateException("Restore Nono executor settings before private-state cleanup");
            }
            final var runtime = task.runtimeId();
            if (runtime == null) {
                throw new IllegalStateException("Nono runtime identity required for private-state cleanup");
            }
            nono.cleanupStoppedState(runtime);
        }
        cleanup(directory);
    }

    private void retainFailure(
            final TaskDocument task, final String stage, final Throwable failure, final Integer exitCode) {
        String diagnosticFile = null;
        try {
            diagnosticFile =
                    new TaskDiagnostics(Path.of(conf.home()), json, clock).capture(task, stage, failure, exitCode);
        } catch (final IOException | RuntimeException captureFailure) {
            // Do not mask the original error, retain credentials, or print a potentially secret-bearing message.
        }
        final var lookup = Set.of("source-staging", "credentials-and-fork", "relay-and-context", "publication")
                        .contains(stage)
                ? lookupFailure(failure)
                : null;
        final var detail = new TaskDocument.FailureDetail(
                stage,
                failure.getClass().getSimpleName(),
                lookup != null
                        ? lookup
                        : switch (stage) {
                            case "preparation" -> "Unable to prepare the private attempt configuration.";
                            case "runtime-preflight" ->
                                "Strict reviews require a dispatcher runtime and filesystem with SecureDirectoryStream support.";
                            case "source-staging" -> "Unable to stage repository context or source.";
                            case "credentials-and-fork" -> "Unable to prepare execution credentials or fork.";
                            case "launch-preparation" -> "Unable to prepare the execution launch.";
                            case "relay-and-context" -> "Unable to initialize the attempt relay or context broker.";
                            case "execution" ->
                                exitCode == null
                                        ? "Executor launch or execution failed."
                                        : "Agent process returned a non-zero exit status.";
                            case "runtime-stop" -> "Unable to confirm runtime termination; recovery remains blocked.";
                            case "result-validation", "recovery-result" ->
                                "Unable to load a valid bounded task result.";
                            case "agent-result" -> "The agent reported a task failure.";
                            case "publication" -> "Unable to publish or reconcile the task response.";
                            case "cleanup" -> "Unable to remove private attempt data.";
                            default -> "Unable to reconcile or stage the task result.";
                        },
                clock.instant().toString(),
                task.attempts(),
                exitCode,
                diagnosticFile,
                diagnosticFile != null);
        try {
            store.transaction(state -> {
                final var actual = state.task(task.id());
                actual.lastFailure(detail);
                actual.lastError(failure.getClass().getSimpleName());
                return null;
            });
        } catch (final RuntimeException metadataFailure) {
            // Diagnostic metadata is best-effort too. Let the original stop/cleanup/error policy proceed;
            // its normal queue transitions still fail closed if state storage remains unavailable.
        }
    }

    private void cleanup(final String directory) {
        final var path = Path.of(directory).toAbsolutePath().normalize();
        if (!path.getParent()
                        .equals((attemptRoot == null ? Path.of(System.getProperty("java.io.tmpdir")) : attemptRoot)
                                .toAbsolutePath()
                                .normalize())
                || !path.getFileName().toString().startsWith("yca-github-")) {
            throw new IllegalStateException("Unexpected cleanup path");
        }
        try {
            AttemptPreparation.delete(path);
        } catch (final IOException e) {
            throw new IllegalStateException("Attempt cleanup failed", e);
        }
    }
}
