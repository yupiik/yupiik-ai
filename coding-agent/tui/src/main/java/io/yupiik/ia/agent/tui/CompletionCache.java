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
package io.yupiik.ia.agent.tui;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy completion value store for the TUI prompt. Each slice is populated on
 * first need by the shell (never by a blanket refresh), keyed by the scope its
 * value depends on:
 *
 * <ul>
 *   <li>{@link #tools()}, {@link #servers()} and {@link #sessions()} are
 *       <em>application-scoped</em> (MCP tools rarely change mid-run; a restart
 *       is acceptable), so they are fetched once on first use and kept for the
 *       process lifetime - only {@link #refreshSessions()} invalidates the
 *       sessions slice because a new/closed tab changes the resume candidates.</li>
 *   <li>{@link #models(String)} and {@link #variants(String, String)} are
 *       <em>provider-scoped</em> (different providers expose different model
 *       ids, e.g. {@code glm-5.3} vs {@code z-ai/glm-5.3}), so they are cached
 *       in a per-provider map and fetched lazily the first time that provider
 *       is completed against. Switching tabs/providers never needs an
 *       invalidation: each provider's list lives under its own key, so a stale
 *       list from another provider can never be offered.</li>
 * </ul>
 *
 * <p>In-flight fetches are tracked per key so a burst of keystrokes against a
 * not-yet-loaded slice schedules at most one warm-up. Every applied mutation
 * fires {@code onChanged} so the shell can recompute a pending empty completion
 * popup once the async value lands.</p>
 */
public final class CompletionCache {
    private final Runnable onChanged;

    // application-scoped slices (loaded once on first need)
    private volatile List<String> tools = List.of();
    private volatile boolean toolsLoaded;
    private volatile List<String> servers = List.of();
    private volatile boolean serversLoaded;
    private volatile List<String> sessions = List.of();
    private volatile boolean sessionsLoaded;

    // provider-scoped slices (lazy per provider)
    private final ConcurrentMap<String, List<String>> models = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, List<String>>> variants = new ConcurrentHashMap<>();

    // in-flight guards so concurrent completions do not schedule duplicate warm-ups
    private final AtomicBoolean toolsLoading = new AtomicBoolean();
    private final AtomicBoolean serversLoading = new AtomicBoolean();
    private final AtomicBoolean sessionsLoading = new AtomicBoolean();
    private final Set<String> modelsLoading = ConcurrentHashMap.newKeySet();
    private final Set<String> variantsLoading = ConcurrentHashMap.newKeySet();

    public CompletionCache(final Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    private void changed() {
        onChanged.run();
    }

    /* ---------------- application-scoped: tools ---------------- */

    public List<String> tools() {
        return tools;
    }

    public boolean toolsLoaded() {
        return toolsLoaded;
    }

    /** Starts an application-wide tools fetch; {@code false} when one is already in flight or done. */
    public boolean beginToolsLoad() {
        return !toolsLoaded && toolsLoading.compareAndSet(false, true);
    }

    public void endToolsLoad() {
        toolsLoading.set(false);
    }

    public void tools(final List<String> value) {
        tools = value == null ? List.of() : value;
        toolsLoaded = true;
        changed();
    }

    /* ---------------- application-scoped: servers ---------------- */

    public List<String> servers() {
        return servers;
    }

    public boolean serversLoaded() {
        return serversLoaded;
    }

    public boolean beginServersLoad() {
        return !serversLoaded && serversLoading.compareAndSet(false, true);
    }

    public void endServersLoad() {
        serversLoading.set(false);
    }

    public void servers(final List<String> value) {
        servers = value == null ? List.of() : value;
        serversLoaded = true;
        changed();
    }

    /* ---------------- application-scoped: sessions ---------------- */

    public List<String> sessions() {
        return sessions;
    }

    public boolean sessionsLoaded() {
        return sessionsLoaded;
    }

    public boolean beginSessionsLoad() {
        return !sessionsLoaded && sessionsLoading.compareAndSet(false, true);
    }

    public void endSessionsLoad() {
        sessionsLoading.set(false);
    }

    public void sessions(final List<String> value) {
        sessions = value == null ? List.of() : value;
        sessionsLoaded = true;
        changed();
    }

    /** Drops the loaded flag so the next completion re-fetches (a tab was created or closed). */
    public void refreshSessions() {
        sessionsLoaded = false;
        sessions = List.of();
    }

    /* ---------------- provider-scoped: models ---------------- */

    public List<String> models(final String providerId) {
        return models.getOrDefault(providerId, List.of());
    }

    public boolean modelsLoaded(final String providerId) {
        return models.containsKey(providerId);
    }

    /** Starts a models fetch for one provider; {@code false} when one is already in flight or done. */
    public boolean beginModelsLoad(final String providerId) {
        return !models.containsKey(providerId) && modelsLoading.add(providerId);
    }

    public void endModelsLoad(final String providerId) {
        modelsLoading.remove(providerId);
    }

    public void models(final String providerId, final List<String> value) {
        models.put(providerId, value == null ? List.of() : value);
        changed();
    }

    /* ---------------- provider-scoped: variants ---------------- */

    public List<String> variants(final String providerId, final String model) {
        final var byProvider = variants.get(providerId);
        return byProvider == null || model == null ? List.of() : byProvider.getOrDefault(model, List.of());
    }

    public boolean variantsLoaded(final String providerId, final String model) {
        final var byProvider = variants.get(providerId);
        return model != null && byProvider != null && byProvider.containsKey(model);
    }

    /** Starts a variants fetch for one provider+model; {@code false} when one is already in flight or done. */
    public boolean beginVariantsLoad(final String providerId, final String model) {
        return model != null
                && !variantsLoaded(providerId, model)
                && variantsLoading.add(providerId + "\u0000" + model);
    }

    public void endVariantsLoad(final String providerId, final String model) {
        if (model != null) {
            variantsLoading.remove(providerId + "\u0000" + model);
        }
    }

    public void variants(final String providerId, final String model, final List<String> value) {
        variants.compute(providerId, (k, byProvider) -> {
            final var next = byProvider == null
                    ? new ConcurrentHashMap<String, List<String>>()
                    : new ConcurrentHashMap<>(byProvider);
            next.put(model, value == null ? List.of() : value);
            return next;
        });
        changed();
    }

    /** Clears the provider-scoped models/variants (optionally for a single provider); used on explicit reload. */
    public void clearProvider() {
        models.clear();
        variants.clear();
    }
}
