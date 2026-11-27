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
package io.yupiik.ia.agent.core.agent;

import static java.util.stream.Collectors.joining;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.shell.ConversationTranscript;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.stream.Stream;

@ApplicationScoped
public class SubagentRegistry {
    private static final Logger LOGGER = Logger.getLogger(SubagentRegistry.class.getName());

    private final CodingAgentConfiguration configuration;
    private final ProviderManager providerManager;
    private final Map<String, SubagentState> subagents = new ConcurrentHashMap<>();
    private final ReentrantLock admissionLock = new ReentrantLock();
    private final Map<String, ConversationTranscript> subagentTranscripts = new ConcurrentHashMap<>();
    /**
     * The effective (provider, model) tuple of a sub-agent, captured when its runner resolves the
     * model routing, so {@link #transcriptOf} can seed the transcript's default speaker labels from
     * what the child actually runs on instead of the process-wide current provider.
     */
    private final Map<String, TranscriptIdentity> transcriptIdentities = new ConcurrentHashMap<>();
    /**
     * Completed sub-agent results keyed by sub-agent id. Kept separately from {@link #subagents} so
     * {@code collect} can still return a finished result even after the state was LRU-evicted from
     * {@link #subagents} (the {@code get -> collect} race). Bounded (via an insertion-ordered eviction
     * policy) above the state-map bound so a result whose state was just evicted survives a delayed collect.
     */
    private final Map<String, SubagentResult> completedResults =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, SubagentResult> eldest) {
                    return size() > Math.max(maxCompletedStates(), 64);
                }
            });

    /**
     * Process-wide per-target-provider in-flight sub-agent count: the {@code N-1} cap (N = the
     * provider's {@code maxConcurrent}, reserved one slot for the main session) is global per
     * instance. Bounded by the number of configured providers, so no session-keyed cleanup is needed.
     */
    private final ConcurrentHashMap<String, AtomicInteger> providerSubagents = new ConcurrentHashMap<>();

    /** The per-session cap ({@code yca.subagents.maxConcurrent}): the "number of sub agents per session" limit. */
    private final int sessionMaxConcurrent;

    private final SpeakerLabels speakerLabels;

    public SubagentRegistry(final CodingAgentConfiguration configuration, final ProviderManager providerManager) {
        this(configuration, providerManager, null);
    }

    public SubagentRegistry(
            final CodingAgentConfiguration configuration,
            final ProviderManager providerManager,
            final SpeakerLabels speakerLabels) {
        this.configuration = configuration;
        this.providerManager = providerManager;
        this.speakerLabels = speakerLabels;
        this.sessionMaxConcurrent = configuration != null && configuration.subagents() != null
                ? Math.max(0, configuration.subagents().maxConcurrent())
                : 4;
    }

    protected SubagentRegistry() {
        this.configuration = null;
        this.providerManager = null;
        this.speakerLabels = null;
        this.sessionMaxConcurrent = 4;
    }

    /**
     * The per-session sub-agent budget: the number of simultaneous sub-agents one session may keep
     * in flight ({@code yca.subagents.maxConcurrent}). The per-provider {@code N-1} cap is enforced
     * separately at admission against the target provider (see {@link #start}).
     */
    public int maxConcurrent() {
        return sessionMaxConcurrent;
    }

    public boolean enabled() {
        return configuration != null
                && configuration.subagents() != null
                && configuration.subagents().enabled()
                && sessionMaxConcurrent > 0;
    }

    /** Why a sub-agent spawn was refused, surfaced to the LLM by {@code spawn_subagent}. */
    public enum RefusalReason {
        DISABLED,
        PARENT_CANCELLED,
        /** Target provider maxConcurrent == 1: the main session reserves the only slot. */
        PROVIDER_FORBIDDEN,
        /** Target provider already runs N-1 sub-agents (its cap minus the main-session slot). */
        PROVIDER_FULL,
        /** The spawning session already has {@code yca.subagents.maxConcurrent} sub-agents in flight. */
        SESSION_FULL
    }

    /** Admission outcome: the started state, or the reason it was refused (null state). */
    public record StartResult(SubagentState state, RefusalReason reason) {
        public boolean admitted() {
            return state != null;
        }
    }

    public Optional<SubagentState> start(final String parentSessionId, final String task) {
        return start(parentSessionId, task, null);
    }

    public Optional<SubagentState> start(final String parentSessionId, final String task, final String name) {
        return start(parentSessionId, task, name, null);
    }

    /**
     * Starts a sub-agent, optionally with a requested model/reference.
     *
     * @param model the raw model reference from {@code spawn_subagent model=} ($provider/model or bare), or {@code null}
     *     when the caller did not request one (the provider {@code subagents.defaultModel} or parent inheritance applies).
     */
    public Optional<SubagentState> start(
            final String parentSessionId, final String task, final String name, final String model) {
        return start(new AgentRunSpec(parentSessionId, task, name, null, model, null, null, null));
    }

    /** Backward-compatible start: no target provider id, no session budget (tests, standalone). */
    public Optional<SubagentState> start(final AgentRunSpec spec) {
        final var result = start(spec, null, null);
        return Optional.ofNullable(result.state());
    }

    /**
     * Admit a sub-agent against the two independent limits:
     * <ol>
     *   <li>the per-session cap ({@code yca.subagents.maxConcurrent}) via {@code budget};</li>
     *   <li>the per-target-provider {@code N-1} cap (N = the provider's {@code maxConcurrent} when
     *       {@code >= 0}, else the global {@code yca.llm.maxConcurrent}); N == 1 forbids sub-agents
     *       on that provider entirely, N &gt; 1 allows at most N-1 concurrent sub-agents there.</li>
     * </ol>
     * Both gates must pass. On refusal nothing is acquired or reserved (no leak), and the reason is
     * returned so {@code spawn_subagent} can hint the LLM about the limiting factor.
     *
     * @param providerId the provider the sub-agent will actually run on (from the effective model);
     *     null/blank falls back to the process-wide current provider
     * @param budget the spawning session's budget; null skips the per-session gate (tests, standalone)
     */
    public StartResult start(final AgentRunSpec spec, final String providerId, final SubagentBudget budget) {
        admissionLock.lock();
        try {
            if (spec.subagentScope() != null
                    && spec.subagentScope().parentCancelled().getAsBoolean()) {
                return new StartResult(null, RefusalReason.PARENT_CANCELLED);
            }
            return startAdmitted(spec, providerId, budget);
        } finally {
            admissionLock.unlock();
        }
    }

    private StartResult startAdmitted(final AgentRunSpec spec, final String providerId, final SubagentBudget budget) {
        if (!enabled()) {
            return new StartResult(null, RefusalReason.DISABLED);
        }
        final var n = providerConcurrency(providerId);
        if (n == 1) {
            return new StartResult(null, RefusalReason.PROVIDER_FORBIDDEN);
        }
        if (n > 1) {
            final var counter = providerCounter(providerKey(providerId));
            // reserve-then-rollback: atomic against concurrent spawns on the same provider
            final var reserved = counter.incrementAndGet();
            if (reserved > n - 1) {
                counter.decrementAndGet();
                return new StartResult(null, RefusalReason.PROVIDER_FULL);
            }
            final var state = admit(budget, spec, providerKey(providerId));
            if (state == null) {
                counter.decrementAndGet();
                return new StartResult(null, RefusalReason.SESSION_FULL);
            }
            return new StartResult(state, null);
        }
        // unlimited provider (n <= 0): only the session cap applies
        final var state = admit(budget, spec, providerKey(providerId));
        if (state == null) {
            return new StartResult(null, RefusalReason.SESSION_FULL);
        }
        return new StartResult(state, null);
    }

    /** The per-session permit acquisition + state creation, factored so the caller rolls back the
     * provider reservation when it fails. Returns null when the session budget is exhausted.
     * The budget is stored on the state so {@code complete}/{@code cancel} can release it. */
    private SubagentState admit(final SubagentBudget budget, final AgentRunSpec spec, final String providerKey) {
        if (budget != null && !budget.tryAcquire()) {
            return null;
        }
        final var id = UUID.randomUUID().toString();
        final var state = new SubagentState(id, spec, System.currentTimeMillis(), providerKey, budget);
        subagents.put(id, state);
        return state;
    }

    /**
     * The target provider's concurrency N: {@code provider.maxConcurrent()} when {@code >= 0},
     * else the global {@code yca.llm.maxConcurrent()}. {@code <= 0} means unlimited (no provider gate).
     * A {@code null}/{@code "default"} provider id resolves to the process-wide current provider.
     */
    private int providerConcurrency(final String providerId) {
        final var provider = providerId == null || providerId.isBlank()
                ? (providerManager == null ? null : providerManager.current())
                : (providerManager == null ? null : providerManager.provider(providerId));
        if (provider != null && provider.maxConcurrent() >= 0) {
            return provider.maxConcurrent();
        }
        return configuration != null && configuration.llm() != null
                ? configuration.llm().maxConcurrent()
                : -1;
    }

    /** The stable counter key for a provider id: blank -> "default". */
    private String providerKey(final String providerId) {
        return providerId == null || providerId.isBlank() ? "default" : providerId;
    }

    private AtomicInteger providerCounter(final String providerKey) {
        return providerSubagents.computeIfAbsent(providerKey, _ -> new AtomicInteger());
    }

    /** Releases both limits held by a state (its provider reservation + its session permit). */
    private void release(final SubagentState state) {
        if (state.providerId() != null) {
            final var counter = providerSubagents.get(state.providerId());
            if (counter != null) {
                counter.decrementAndGet();
            }
        }
        final var budget = state.subagentBudget();
        if (budget != null) {
            budget.release();
        }
    }

    public void complete(final String subagentId, final SubagentResult result) {
        final var state = subagents.get(subagentId);
        if (state != null && state.complete(result)) {
            release(state);
            completedResults.put(subagentId, result);
        }
        cleanup();
    }

    public void cancel(final String subagentId) {
        admissionLock.lock();
        try {
            final var state = subagents.get(subagentId);
            if (state != null && state.cancel()) {
                cancelByParentSession(state.childSessionId());
                subagents.remove(subagentId, state);
                release(state);
            }
        } finally {
            admissionLock.unlock();
        }
    }

    /**
     * Latches the cancel of every sub-agent spawned by a session: called when that session's turn
     * ends on an interrupt. Pushing it here (rather than only letting children poll the parent
     * probe) matters because the UI resets its interrupt flag when the next turn starts, so a child
     * polling afterwards would never see the cancel.
     *
     * @return the number of sub-agents cancelled
     */
    public int cancelByParentSession(final String parentSessionId) {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            return 0;
        }
        var cancelled = 0;
        for (final var entry : List.copyOf(subagents.entrySet())) {
            if (parentSessionId.equals(entry.getValue().parentSessionId())) {
                cancel(entry.getKey());
                cancelled++;
            }
        }
        return cancelled;
    }

    /** The effective (provider, model) a sub-agent runs on, captured by its runner at model-resolution time. */
    public record TranscriptIdentity(String provider, String model) {}

    /**
     * Records the effective (provider, model) tuple of a sub-agent, resolved by its runner when
     * the model routing is known. Lets {@link #transcriptOf} seed the transcript's default speaker
     * labels from what the child actually runs on (a {@code $provider/model} spawn may target a
     * provider different from the process-wide current one).
     */
    public void setTranscriptIdentity(final String subagentId, final String provider, final String model) {
        if (subagentId == null || subagentId.isBlank()) {
            return;
        }
        transcriptIdentities.put(subagentId, new TranscriptIdentity(provider, model));
    }

    /**
     * The effective (provider, model) a sub-agent runs on, previously recorded through
     * {@link #setTranscriptIdentity} by its runner once the model routing was resolved.
     * Empty when unknown (sub-agent not started yet, identity not resolved, or transcript dropped).
     */
    public Optional<TranscriptIdentity> identityOf(final String subagentId) {
        if (subagentId == null || subagentId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(transcriptIdentities.get(subagentId));
    }

    /**
     * The per-subagent transcript capture for the given id, lazily created. This
     * instance folds the subagent-tagged events (they are dropped from the parent
     * transcript) so the TUI can render the child conversation through
     * {@code /subagents <id>} without mixing two model voices in one pane. The default
     * speaker labels use the identity stored by {@link #setTranscriptIdentity} when known,
     * else the process-wide current provider (the transcript is sometimes created before
     * the runner resolves the routing).
     */
    public ConversationTranscript transcriptOf(final String subagentId) {
        return subagentTranscripts.computeIfAbsent(subagentId, id -> {
            final var t = new ConversationTranscript(true);
            if (speakerLabels != null) {
                final var identity = transcriptIdentities.get(id);
                final var provider = identity != null
                                && identity.provider() != null
                                && !identity.provider().isBlank()
                        ? identity.provider()
                        : (providerManager == null ? null : providerManager.currentProviderId());
                final var model = identity != null
                                && identity.model() != null
                                && !identity.model().isBlank()
                        ? identity.model()
                        : (providerManager == null
                                        || providerManager.current() == null
                                        || providerManager.current().model() == null
                                ? null
                                : providerManager.current().model());
                final var ctx = new SpeakerLabels.Ctx(model, provider, id, null, null, null);
                t.setLabels(speakerLabels.userLabel(ctx), speakerLabels.assistantLabel(ctx));
                t.setLabelResolver((identity2, user) -> {
                    final var ictx = new SpeakerLabels.Ctx(
                            identity2.model(), identity2.provider(), identity2.agent(), null, null, null);
                    return user ? speakerLabels.userLabel(ictx) : speakerLabels.assistantLabel(ictx);
                });
            }
            return t;
        });
    }

    /** Drops the transient subagent captures that already completed; bounded by the LRU cleanup. */
    public void dropTranscript(final String subagentId) {
        subagentTranscripts.remove(subagentId);
        transcriptIdentities.remove(subagentId);
    }

    public Optional<SubagentResult> collect(final String subagentId) {
        final var state = subagents.remove(subagentId);
        if (state != null && state.isDone()) {
            completedResults.remove(subagentId);
            return Optional.ofNullable(state.result());
        }
        // the state may have been LRU-evicted from the (bounded) subagents map by a concurrent
        // complete() of another child in the get->collect window; the result cache still holds the
        // finished outcome so a delayed collect is never a spurious "no result".
        return Optional.ofNullable(completedResults.remove(subagentId));
    }

    public Optional<SubagentState> get(final String subagentId) {
        return Optional.ofNullable(subagents.get(subagentId));
    }

    public List<SubagentState> listActive() {
        return subagents.values().stream().filter(s -> !s.isDone()).toList();
    }

    /** Every tracked subagent of a parent session (running and recently completed). */
    public List<SubagentState> listAll(final String parentSessionId) {
        return subagents.values().stream()
                .filter(s -> parentSessionId == null || parentSessionId.equals(s.parentSessionId()))
                .sorted((a, b) -> Long.compare(a.startedAt(), b.startedAt()))
                .toList();
    }

    /** Exact ids are global; otherwise exact names are resolved within the parent session. */
    public List<SubagentState> resolve(final String parentSessionId, final String reference) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        final var byId = get(reference);
        if (byId.isPresent()) {
            return List.of(byId.get());
        }
        return listAll(parentSessionId).stream()
                .filter(state -> reference.equals(state.name()))
                .toList();
    }

    /** Sorted, unique ids and nonblank names of running and completed children. */
    public List<String> completionCandidates(final String parentSessionId) {
        return listAll(parentSessionId).stream()
                .flatMap(state -> Stream.of(state.id(), state.name()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /** Shared diagnostic for an unknown or ambiguous selection. */
    public String selectionError(final String reference, final List<SubagentState> matches) {
        if (matches.isEmpty()) {
            return "Unknown subagent: " + reference;
        }
        return "Ambiguous subagent: " + reference + ". Use an id: "
                + matches.stream().map(SubagentState::id).sorted().collect(joining(", "));
    }

    /** The number of in-flight sub-agents on a provider (informational; 0 for unknown ids). */
    public int providerActive(final String providerId) {
        final var counter = providerSubagents.get(providerKey(providerId));
        return counter == null ? 0 : counter.get();
    }

    /**
     * Safety guard: maximum completed states retained before LRU eviction.
     * Scales with the per-session concurrency limit to bound memory.
     */
    private int maxCompletedStates() {
        return Math.max(3, sessionMaxConcurrent * 3);
    }

    private void cleanup() {
        // Evict oldest completed states when exceeding maxCompletedStates()
        final var completed = subagents.values().stream()
                .filter(SubagentState::isDone)
                .sorted((a, b) -> Long.compare(a.completedAt(), b.completedAt()))
                .toList();
        final var toRemove = completed.size() - maxCompletedStates();
        if (toRemove > 0) {
            for (int i = 0; i < toRemove; i++) {
                subagents.remove(completed.get(i).id());
            }
        }
    }

    public static final class SubagentState {
        /** Session key owned by this child, also used to route deliveries from its descendants. */
        public String childSessionId() {
            return parentSessionId() + "-subagent-" + id();
        }

        private final String id;
        private final AgentRunSpec spec;
        private final long startedAt;
        private volatile SubagentResult result;
        private volatile boolean cancelled;
        private volatile boolean done;
        private volatile long completedAt;

        public SubagentState(final String id, final String parentSessionId, final String task, final long startedAt) {
            this(id, new AgentRunSpec(parentSessionId, task, null, null, null, null, null, null), startedAt);
        }

        public SubagentState(
                final String id,
                final String parentSessionId,
                final String task,
                final String name,
                final long startedAt) {
            this(id, new AgentRunSpec(parentSessionId, task, name, null, null, null, null, null), startedAt);
        }

        public SubagentState(
                final String id,
                final String parentSessionId,
                final String task,
                final String name,
                final long startedAt,
                final String model) {
            this(id, new AgentRunSpec(parentSessionId, task, name, null, model, null, null, null), startedAt);
        }

        public SubagentState(final String id, final AgentRunSpec spec, final long startedAt) {
            this(id, spec, startedAt, spec.providerId());
        }

        SubagentState(final String id, final AgentRunSpec spec, final long startedAt, final String providerId) {
            this(id, spec, startedAt, providerId, spec.subagentBudget());
        }

        SubagentState(
                final String id,
                final AgentRunSpec spec,
                final long startedAt,
                final String providerId,
                final SubagentBudget budget) {
            this.id = id;
            this.spec = providerId == null
                    ? spec
                    : new AgentRunSpec(
                            spec.parentSessionId(),
                            spec.task(),
                            spec.name(),
                            spec.profile(),
                            spec.model(),
                            spec.maxTurns(),
                            spec.allowedTools(),
                            spec.parentAllowedTools(),
                            providerId,
                            budget == null ? spec.subagentBudget() : budget,
                            spec.admittedModel(),
                            spec.subagentScope());
            this.startedAt = startedAt;
        }

        public String id() {
            return id;
        }

        public String parentSessionId() {
            return spec.parentSessionId();
        }

        public String task() {
            return spec.task();
        }

        public String name() {
            return spec.name();
        }

        /** The raw model reference requested via {@code spawn_subagent model=}, or {@code null} when none was requested. */
        public String model() {
            return spec.model();
        }

        public EffectiveAgentModel admittedModel() {
            return spec.admittedModel();
        }

        public String profile() {
            return spec.profile();
        }

        public Integer maxTurns() {
            return spec.maxTurns();
        }

        public Set<String> allowedTools() {
            return spec.allowedTools();
        }

        public Set<String> parentAllowedTools() {
            return spec.parentAllowedTools();
        }

        /** The provider the sub-agent was admitted against (stable counter key), or null. */
        public String providerId() {
            return spec.providerId();
        }

        /** The spawning session's budget this child holds a permit of, or null. */
        public SubagentBudget subagentBudget() {
            return spec.subagentBudget();
        }

        public AgentRunSpec spec() {
            return spec;
        }

        public long startedAt() {
            return startedAt;
        }

        public long completedAt() {
            return completedAt;
        }

        public synchronized boolean complete(final SubagentResult result) {
            if (done) {
                return false;
            }
            this.result = result;
            this.done = true;
            this.completedAt = System.currentTimeMillis();
            notifyAll();
            return true;
        }

        public synchronized boolean cancel() {
            if (done) {
                return false;
            }
            this.cancelled = true;
            this.done = true;
            this.completedAt = System.currentTimeMillis();
            notifyAll();
            return true;
        }

        public synchronized SubagentResult awaitResult() throws InterruptedException {
            while (!done) {
                wait();
            }
            return result;
        }

        public boolean isDone() {
            return done;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public SubagentResult result() {
            return result;
        }
    }

    public static final class SubagentResult {
        public enum Status {
            SUCCEEDED,
            FAILED,
            CANCELLED,
            BUDGET_EXHAUSTED
        }

        private final String summary;
        private final String fullConversation;
        private final List<String> artifacts;
        private final Status status;
        private final String error;

        private SubagentResult(
                final String summary,
                final String fullConversation,
                final List<String> artifacts,
                final Status status,
                final String error) {
            this.summary = summary;
            this.fullConversation = fullConversation;
            this.artifacts = List.copyOf(artifacts);
            this.status = status;
            this.error = error;
        }

        public static SubagentResult success(
                final String summary, final String fullConversation, final List<String> artifacts) {
            return new SubagentResult(summary, fullConversation, artifacts, Status.SUCCEEDED, null);
        }

        public static SubagentResult error(final String error, final String fullConversation) {
            return new SubagentResult(null, fullConversation, List.of(), Status.FAILED, error);
        }

        /**
         * The sub-agent stopped on a cancel (parent interrupt or an explicit
         * {@code cancel_subagent}) instead of failing: the parent transcript must render it as a
         * cancellation, not as a red "Subagent failed" card.
         */
        public static SubagentResult cancelled(final String fullConversation) {
            return new SubagentResult(null, fullConversation, List.of(), Status.CANCELLED, "Sub-agent cancelled");
        }

        public static SubagentResult budgetExhausted(final String fullConversation, final String message) {
            return new SubagentResult(null, fullConversation, List.of(), Status.BUDGET_EXHAUSTED, message);
        }

        public String summary() {
            return summary;
        }

        public String fullConversation() {
            return fullConversation;
        }

        public List<String> artifacts() {
            return artifacts;
        }

        public boolean success() {
            return status == Status.SUCCEEDED;
        }

        public boolean cancelled() {
            return status == Status.CANCELLED;
        }

        public Status status() {
            return status;
        }

        public boolean budgetExhausted() {
            return status == Status.BUDGET_EXHAUSTED;
        }

        public String error() {
            return error;
        }
    }
}
