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
package io.yupiik.ia.agent.core.usage;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-session cache of the provider account usage snapshot. Keyed by session id so every tab (TUI,
 * web, CLI) keeps its own quota independent of the others - and, critically, of the session's own
 * provider (each tab can run on its own provider). The quota refresh runs here, entirely in core:
 * the Web UI only consumes the quota fields broadcast through the regular backend event.
 *
 * <p>Refresh cadence: fetched at provider attach, then at most once per {@link #TTL_MILLIS} (~5 min)
 * unless forced (a {@code /usage} slash command always forces a refresh). The access token is
 * resolved through {@link CodexAuthTemplateHelper} and never leaves yca. With a shared scheduler,
 * {@link #activity(String, boolean)} enables TTL polling during turns and one final refresh after
 * turn end. Attach alone never starts recurring polling.</p>
 *
 * <p>Blocking semantics. When an {@link ExecutorService} is injected (the normal application wiring)
 * refreshes run asynchronously on that shared pool so provider attachment and a stale (non-forced)
 * snapshot read never block the inference/tool critical path: {@link #attach(String, ChatSettings)}
 * and a non-forced {@link #snapshot(String, boolean)} return immediately with the cached snapshot and
 * schedule a background refresh, and concurrent refreshes of the same session are coalesced into one
 * backend call. Only the explicit {@code /usage} path ({@link #refresh(String)} /
 * {@link #snapshot(String, true)}) joins the (coalesced) fetch so it returns fresh data, bounded so a
 * stuck backend never hangs the command. Without an injected executor (legacy/programmatic use and the
 * unit tests) the historical synchronous blocking behavior is preserved.</p>
 */
@ApplicationScoped
public class QuotaSnapshotStore {
    private static final Logger LOGGER = Logger.getLogger(QuotaSnapshotStore.class.getName());

    /** Refresh TTL: at most one quota fetch every 5 minutes per session unless forced. */
    public static final long TTL_MILLIS = 5L * 60L * 1000L;

    /** Bound on a forced (user-visible) refresh join so a stuck backend never hangs a {@code /usage}. */
    private static final long FORCED_JOIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15);

    private final CodexUsageClient client;
    private final ProviderManager providerManager;
    private final Clock clock;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final boolean async;
    private final ConcurrentHashMap<String, SessionQuota> sessions = new ConcurrentHashMap<>();
    /**
     * One coalesced in-flight refresh per session, tagged with the provider generation it was fetched for.
     * Coalescing is generation-scoped: re-attaching the same provider reuses the running fetch, while a
     * provider switch (generation bump) starts a fresh refresh so the new provider's quota is fetched
     * promptly even if the old provider's fetch is still in flight (the latter is dropped on completion).
     * The entry is removed on completion so a later stale read starts fresh.
     */
    private final ConcurrentHashMap<String, InFlightRefresh> inFlight = new ConcurrentHashMap<>();

    /** An in-flight refresh: the provider generation it was scheduled for, and the future the caller observes. */
    private record InFlightRefresh(SessionQuota quota, long generation, CompletableFuture<CodexUsageSnapshot> future) {}

    /**
     * A session-scoped quota: the provider it runs on, a provider generation (bumped on every provider
     * change so a stale async refresh of a previous provider never overwrites the current one) and the
     * cached snapshot + fetch time.
     */
    private static final class SessionQuota {
        volatile ChatSettings settings;
        volatile ProviderConfiguration provider;
        volatile CodexUsageSnapshot snapshot = CodexUsageSnapshot.empty();
        volatile long lastFetchMillis;
        volatile long providerGeneration;
        Runnable listener;
        boolean active;
        boolean finalRefresh;
        ScheduledFuture<?> timer;
        long timerGeneration;

        boolean stale(final long now) {
            return snapshot == null || now - lastFetchMillis >= TTL_MILLIS;
        }
    }

    public QuotaSnapshotStore(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodexAuthTemplateHelper codexAuth,
            final ProviderManager providerManager,
            final Clock clock) {
        this(mapper, httpClient, codexAuth, providerManager, clock, null);
    }

    public QuotaSnapshotStore(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodexAuthTemplateHelper codexAuth,
            final ProviderManager providerManager,
            final Clock clock,
            final ExecutorService executor) {
        this(mapper, httpClient, codexAuth, providerManager, clock, executor, null);
    }

    /** Uses shared application executors; neither executor is owned or closed by this store. */
    public QuotaSnapshotStore(
            final JsonMapper mapper,
            final ExtendedHttpClient httpClient,
            final CodexAuthTemplateHelper codexAuth,
            final ProviderManager providerManager,
            final Clock clock,
            final ExecutorService executor,
            final ScheduledExecutorService scheduler) {
        this.client = new CodexUsageClient(mapper, httpClient, codexAuth);
        this.providerManager = providerManager;
        this.clock = clock;
        this.executor = executor;
        this.scheduler = scheduler;
        // A provided (app-managed, injected) executor enables non-blocking background refreshes; without
        // one (tests/programmatic use) the historical synchronous blocking semantics are preserved.
        this.async = executor != null;
        // precompute nothing; provider/root resolution is lazy per session
    }

    protected QuotaSnapshotStore() {
        this.client = null;
        this.providerManager = null;
        this.clock = null;
        this.executor = null;
        this.scheduler = null;
        this.async = false;
    }

    /**
     * Records the session's provider selection so quota follows the tab's own provider. When an executor
     * is wired, kicks off an asynchronous (non-blocking) background refresh; otherwise refreshes
     * synchronously (legacy blocking semantics).
     */
    public void attach(final String sessionId, final ChatSettings settings) {
        if (sessionId == null || sessionId.isBlank()) {
            // no session id yet (eg. a provider switch before the first turn): nothing to track
            return;
        }
        final SessionQuota quota = sessions.computeIfAbsent(sessionId, s -> new SessionQuota());
        final ProviderConfiguration provider = resolveProvider(settings);
        final boolean refresh;
        synchronized (quota) {
            final ProviderConfiguration previous = quota.provider;
            quota.settings = settings;
            quota.provider = provider;
            // A different provider invalidates both old fetches and the old polling deadline.
            if (previous != provider) {
                cancelTimer(quota);
                quota.providerGeneration++;
                quota.snapshot = CodexUsageSnapshot.empty();
                quota.lastFetchMillis = clock.millis() - TTL_MILLIS;
            }
            refresh = usageEnabled(provider) && isCodexProvider(provider) && quota.stale(clock.millis());
            armTimer(sessionId, quota);
        }
        if (refresh) {
            refreshIfEnabled(sessionId, quota);
        }
    }

    /** Replaces the session's single publication listener; null removes it. May precede attach. */
    public void listen(final String sessionId, final Runnable listener) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        final var quota = sessions.computeIfAbsent(sessionId, s -> new SessionQuota());
        synchronized (quota) {
            quota.listener = listener;
        }
    }

    /** Marks turn start/end. End requests one last refresh at the next eligible TTL, then stops. */
    public void activity(final String sessionId, final boolean active) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        final var quota = sessions.get(sessionId);
        if (quota == null) {
            return; // A turn finishing after close must not recreate the released session.
        }
        synchronized (quota) {
            quota.active = active;
            quota.finalRefresh = !active;
            armTimer(sessionId, quota);
        }
    }

    // All timer and activity state is protected by the session monitor. Scheduler threads only
    // dispatch work; even a slow HTTP fetch or listener never runs on the scheduler thread.
    private void cancelTimer(final SessionQuota quota) {
        quota.timerGeneration++;
        if (quota.timer != null) {
            quota.timer.cancel(false);
            quota.timer = null;
        }
    }

    private void armTimer(final String sessionId, final SessionQuota quota) {
        cancelTimer(quota);
        if (scheduler == null
                || executor == null
                || sessions.get(sessionId) != quota
                || (!quota.active && !quota.finalRefresh)
                || !usageEnabled(quota.provider)
                || !isCodexProvider(quota.provider)) {
            return;
        }
        final long token = quota.timerGeneration;
        final long delay = Math.max(0, TTL_MILLIS - (clock.millis() - quota.lastFetchMillis));
        try {
            quota.timer = scheduler.schedule(
                    () -> {
                        try {
                            executor.submit(() -> onTimer(sessionId, quota, token));
                        } catch (final RuntimeException e) {
                            LOGGER.log(Level.FINE, "Quota timer dispatch rejected", e);
                        }
                    },
                    delay,
                    TimeUnit.MILLISECONDS);
        } catch (final RuntimeException e) {
            LOGGER.log(Level.FINE, "Quota timer scheduling rejected", e);
        }
    }

    private void onTimer(final String sessionId, final SessionQuota quota, final long token) {
        synchronized (quota) {
            if (sessions.get(sessionId) != quota || quota.timerGeneration != token) {
                return;
            }
            quota.timer = null;
            if (!quota.stale(clock.millis())) {
                armTimer(sessionId, quota);
                return;
            }
            quota.finalRefresh = false;
            final var future = scheduleAsync(sessionId, quota);
            if (future != null) {
                future.whenComplete((_, _) -> {
                    synchronized (quota) {
                        armTimer(sessionId, quota);
                    }
                });
            } else {
                armTimer(sessionId, quota);
            }
        }
    }

    /**
     * Returns the current quota snapshot. A non-forced read (the sidebar gauge / inference path) returns
     * the cached snapshot immediately and, when stale, schedules a background refresh asynchronously
     * (never blocking). A forced read (a {@code /usage} call) joins the coalesced fetch (bounded) so it
     * returns fresh data. Never throws for a quota-less/unauthenticated provider: it returns an empty
     * snapshot carrying the capability state so UIs can hide the gauge.
     *
     * @param sessionId the session to read.
     * @param force whether to bypass the TTL and re-fetch now.
     * @return the snapshot view (never {@code null}).
     */
    public QuotaView snapshot(final String sessionId, final boolean force) {
        if (sessionId == null || sessionId.isBlank()) {
            // mirror cachedSnapshot: a null/blank key is simply "no quota yet" (a provider switch or
            // /usage before a session has a live turn id), never a ConcurrentHashMap.get(null) NPE
            return new QuotaView(false, false, CodexUsageSnapshot.empty());
        }
        final SessionQuota quota = sessions.get(sessionId);
        if (quota == null) {
            return new QuotaView(false, false, CodexUsageSnapshot.empty());
        }
        final var provider = quota.provider;
        final boolean enabled = usageEnabled(provider);
        final boolean supported = enabled && isCodexProvider(provider);
        if (supported) {
            if (force) {
                // explicit user-visible refresh: await the (coalesced) result so /usage shows fresh data
                if (async) {
                    awaitFresh(sessionId, quota);
                } else {
                    refreshSynchronously(sessionId, quota, provider, quota.providerGeneration);
                }
            } else if (quota.stale(clock.millis())) {
                // inference-path read: never block; schedule a background refresh when stale
                refreshIfEnabled(sessionId, quota);
            }
        }
        return new QuotaView(enabled, supported, quota.snapshot == null ? CodexUsageSnapshot.empty() : quota.snapshot);
    }

    /** Reads the current cached quota without checking or refreshing its TTL. */
    public QuotaView cachedSnapshot(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // a null/blank key would NPE inside ConcurrentHashMap.get(null); treat it as "no quota yet"
            return new QuotaView(false, false, CodexUsageSnapshot.empty());
        }
        final SessionQuota quota = sessions.get(sessionId);
        if (quota == null) {
            return new QuotaView(false, false, CodexUsageSnapshot.empty());
        }
        final var provider = quota.provider;
        final boolean enabled = usageEnabled(provider);
        final boolean supported = enabled && isCodexProvider(provider);
        return new QuotaView(enabled, supported, quota.snapshot == null ? CodexUsageSnapshot.empty() : quota.snapshot);
    }

    /** Forces a refresh and returns the fresh view (used by the /usage command). */
    public QuotaView refresh(final String sessionId) {
        // snapshot already handles a null/blank key and a not-yet-attached session
        return snapshot(sessionId, true);
    }

    /** Releases a session's quota entry (called when the session closes). */
    public void close(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        final var quota = sessions.remove(sessionId);
        if (quota != null) {
            synchronized (quota) {
                cancelTimer(quota);
                quota.listener = null;
            }
        }
        inFlight.remove(sessionId);
    }

    /**
     * Triggers a refresh for a session whose provider enables Codex usage. Async (coalesced, non-blocking)
     * when an executor is wired, else the historical synchronous blocking refresh. The caller decides when
     * a refresh is warranted (stale read, forced {@code /usage}); this method only executes it.
     */
    private void refreshIfEnabled(final String sessionId, final SessionQuota quota) {
        if (!usageEnabled(quota.provider) || !isCodexProvider(quota.provider)) {
            return;
        }
        final String root = codexRoot(quota.provider);
        if (root == null || root.isBlank()) {
            return;
        }
        if (!async) {
            refreshSynchronously(sessionId, quota, quota.provider, quota.providerGeneration);
            return;
        }
        scheduleAsync(sessionId, quota);
    }

    /**
     * Routes a refresh to the coalesced in-flight future. Atomically reuses the in-flight future when one
     * is running, else installs a fresh one and schedules the fetch. Returns the future the caller should
     * observe ({@code null} only when the caller should not proceed) so a forced read never waits on a
     * completed/removed entry and never orphans a running refresh.
     */
    private CompletableFuture<CodexUsageSnapshot> scheduleAsync(final String sessionId, final SessionQuota quota) {
        final var provider = quota.provider;
        final long generation = quota.providerGeneration;
        // compute() gives the read-or-create atomicity that get+putIfAbsent cannot: it always returns the
        // future actually held in the map (never an orphaned one), and the binary-lock scope is limited to
        // future creation + submit (the network fetch runs off this task). Coalescing is generation-scoped
        // so a provider switch never lets the new provider's refresh ride on (and be dropped by) the old one.
        final InFlightRefresh held = inFlight.compute(sessionId, (_, current) -> {
            if (current != null
                    && current.quota() == quota
                    && current.generation() == generation
                    && !current.future().isDone()) {
                return current; // coalesce: same provider's refresh is already in flight
            }
            final var task = new CompletableFuture<CodexUsageSnapshot>();
            try {
                executor.submit(() -> runAsyncRefresh(sessionId, quota, task, provider, generation));
                return new InFlightRefresh(quota, generation, task);
            } catch (final RuntimeException e) {
                // pool rejected/shut down: do not leave a dead entry behind; the caller fell back to cached
                return null;
            }
        });
        return held == null ? null : held.future();
    }

    /** The async refresh body: fetch, publish (guarded by the provider generation), handle failures, clear in-flight. */
    private void runAsyncRefresh(
            final String sessionId,
            final SessionQuota quota,
            final CompletableFuture<CodexUsageSnapshot> task,
            final ProviderConfiguration provider,
            final long generation) {
        try {
            final CodexUsageSnapshot fresh = client.fetch(codexRoot(provider), providerId(provider));
            publishIfCurrent(sessionId, quota, provider, generation, fresh);
            task.complete(fresh);
        } catch (final Throwable e) {
            final Throwable cause = unwrap(e);
            if (cause instanceof CodexUsageClient.NeedsLoginException) {
                LOGGER.log(
                        Level.FINE,
                        () -> "Codex usage not refreshed for session " + sessionId + ": " + cause.getMessage());
            } else {
                LOGGER.log(
                        Level.WARNING,
                        () -> "Could not refresh Codex usage for session " + sessionId + ": " + cause.getMessage());
            }
            // back off on failure so a broken/unauthorized backend is not hammered on every stale read
            backOffIfCurrent(sessionId, quota, provider, generation);
            task.completeExceptionally(cause);
        } finally {
            // remove only if this exact generation/task is still the map entry: a provider switch already
            // replaced it with a fresh refresh, which must stay in the map even as the stale task completes
            inFlight.remove(sessionId, new InFlightRefresh(quota, generation, task));
        }
    }

    /** Captures the coalesced in-flight refresh and waits on it (bounded) so the caller sees fresh data. */
    private void awaitFresh(final String sessionId, final SessionQuota quota) {
        final CompletableFuture<CodexUsageSnapshot> future = scheduleAsync(sessionId, quota);
        if (future == null) {
            return; // unsupported provider or the pool rejected the submit (already handled/nopped)
        }
        try {
            future.get(FORCED_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException e) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "Timed out waiting for the forced Codex usage refresh for session " + sessionId);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final ExecutionException e) {
            // already logged by the async task; the caller still returns the (possibly stale) cached snapshot
        }
    }

    /** Legacy synchronous refresh (used when no executor is wired). Mirrors the historical behavior. */
    private void refreshSynchronously(
            final String sessionId,
            final SessionQuota quota,
            final ProviderConfiguration provider,
            final long generation) {
        try {
            final CodexUsageSnapshot fresh = client.fetch(codexRoot(provider), providerId(provider));
            publishIfCurrent(sessionId, quota, provider, generation, fresh);
        } catch (final CodexUsageClient.NeedsLoginException e) {
            LOGGER.log(Level.FINE, () -> "Codex usage not refreshed for session " + sessionId + ": " + e.getMessage());
            backOffIfCurrent(sessionId, quota, provider, generation);
        } catch (final RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    () -> "Could not refresh Codex usage for session " + sessionId + ": " + e.getMessage());
            backOffIfCurrent(sessionId, quota, provider, generation);
        }
    }

    /** Publishes a fetched snapshot only if the session is still on the same provider lineage. */
    private void publishIfCurrent(
            final String sessionId,
            final SessionQuota quota,
            final ProviderConfiguration provider,
            final long generation,
            final CodexUsageSnapshot fresh) {
        final Runnable listener;
        synchronized (quota) {
            if (sessions.get(sessionId) != quota
                    || quota.provider != provider
                    || quota.providerGeneration != generation) {
                return;
            }
            quota.snapshot = fresh;
            quota.lastFetchMillis = clock.millis();
            armTimer(sessionId, quota);
            listener = quota.listener;
        }
        if (listener != null) {
            try {
                listener.run();
            } catch (final Throwable e) {
                LOGGER.log(Level.WARNING, "Quota publication listener failed", e);
            }
        }
    }

    /** Records the failure back-off (so a broken backend is not re-polled every stale read) when still current. */
    private void backOffIfCurrent(
            final String sessionId,
            final SessionQuota quota,
            final ProviderConfiguration provider,
            final long generation) {
        synchronized (quota) {
            if (sessions.get(sessionId) == quota
                    && quota.provider == provider
                    && quota.providerGeneration == generation) {
                quota.lastFetchMillis = clock.millis();
                armTimer(sessionId, quota);
            }
        }
    }

    /** The current provider for the session (per-tab), never the process-global default. */
    private ProviderConfiguration resolveProvider(final ChatSettings settings) {
        if (providerManager == null) {
            return null;
        }
        // the session's own provider (per-tab), never the process-global default
        final String providerId = settings == null ? null : settings.providerId();
        return providerId == null || providerId.isBlank()
                ? providerManager.current()
                : providerManager.provider(providerId);
    }

    private static boolean usageEnabled(final ProviderConfiguration provider) {
        return provider != null && provider.usageEnabled();
    }

    private static boolean isCodexProvider(final ProviderConfiguration provider) {
        if (provider == null || provider.baseUrl() == null) {
            return false;
        }
        final String url = provider.baseUrl().toLowerCase(Locale.ROOT);
        return url.contains("/codex");
    }

    private static String providerId(final ProviderConfiguration provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            return "default";
        }
        return provider.id();
    }

    /**
     * Derives the Codex API root from the provider base URL: strip any {@code /codex} route suffix
     * (the Responses chat path) to reach the root that serves {@code /wham/usage}. Eg.
     * {@code https://chatgpt.com/backend-api/codex} -> {@code https://chatgpt.com/backend-api}.
     */
    static String codexRoot(final ProviderConfiguration provider) {
        if (provider == null || provider.baseUrl() == null) {
            return null;
        }
        String url = provider.baseUrl();
        final int codex = url.toLowerCase(Locale.ROOT).indexOf("/codex");
        if (codex >= 0) {
            url = url.substring(0, codex);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.isEmpty() ? null : url;
    }

    private static Throwable unwrap(final Throwable e) {
        if (e instanceof CompletionException ce && ce.getCause() != null) {
            return unwrap(ce.getCause());
        }
        if (e instanceof ExecutionException ee && ee.getCause() != null) {
            return unwrap(ee.getCause());
        }
        return e;
    }

    /** The quota view a session consumes: capability state plus the underlying snapshot. */
    public record QuotaView(boolean enabled, boolean supported, CodexUsageSnapshot snapshot) {

        /** The primary rate-limit window (the one that drives the sidebar gauge), or {@code null}. */
        public CodexUsageSnapshot.RateLimit primaryWindow() {
            if (snapshot == null || snapshot.rateLimits() == null) {
                return null;
            }
            for (final var limit : snapshot.rateLimits()) {
                if (CodexUsageSnapshot.RateLimit.PRIMARY.equals(limit.kind()) && limit.usedPercent() != null) {
                    return limit;
                }
            }
            return null;
        }
    }

    /** Conceptual wiring hook for the session id used by tests to pre-seed entries. visible for tests. */
    Map<String, SessionQuota> sessions() {
        return sessions;
    }
}
