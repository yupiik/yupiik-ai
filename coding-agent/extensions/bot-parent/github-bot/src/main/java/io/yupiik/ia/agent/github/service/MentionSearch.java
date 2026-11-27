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

import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.MentionSearchClient;
import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Durable, bounded search planning, not request authorization. The consumer must read actual feeds
 * and authorize their contents. Search results and credentials never enter the state document.
 */
public final class MentionSearch {
    private static final int MAX_LEAVES = 256;
    private static final int MAX_TREE = 511;
    private static final int MAX_DEPTH = 64;
    private static final int QUERY_TARGET = 240;
    private static final int QUERY_MAX = 2048;
    private static final long MAX_SECOND = 253402300799L;
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}(?:\\[bot\\])?");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}");

    private final GithubBotConfiguration conf;
    private final TaskStore store;
    private final MentionSearchClient client;
    private final Clock clock;

    public MentionSearch(
            final GithubBotConfiguration conf, final TaskStore store, final GitHubClient github, final Clock clock) {
        this.conf = Objects.requireNonNull(conf);
        this.store = Objects.requireNonNull(store);
        this.client = new MentionSearchClient(Objects.requireNonNull(github));
        this.clock = Objects.requireNonNull(clock);
        if (conf.login() == null || !LOGIN.matcher(conf.login()).matches() || conf.api() == null) {
            throw new IllegalArgumentException("Invalid mention search identity");
        }
    }

    /** Returns the sum of consumer results. The budget counts search requests, including split probes. */
    public int poll(
            final List<String> repositories,
            final MentionSearchClient.Kind kind,
            final int requestBudget,
            final ToIntFunction<List<String>> consumer) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(consumer);
        if (requestBudget < 0) {
            throw new IllegalArgumentException("Negative mention search budget");
        }
        final var selected = selection(repositories);
        if (selected.isEmpty()) {
            return 0;
        }
        final var prefix = prefix(selected, kind);
        var snapshot = store.transaction(state -> {
            final var existing = snapshot(state.cursors(), prefix);
            if (!existing.isEmpty()) {
                return existing;
            }
            final var initial = encode(seed(selected));
            initial.forEach((key, value) -> state.cursors().put(prefix + key, value));
            return initial;
        });
        var plan = decode(snapshot, selected);
        final var completed = new HashSet<Integer>();
        int requests = 0;
        int result = 0;
        while (true) {
            final var index = eligible(plan, completed, requests < requestBudget);
            if (index < 0) {
                return result;
            }
            final var id = plan.active().get(index);
            final var leaf = plan.leaves().get(id);
            final var start = System.nanoTime();
            if (leaf.status().equals("directfallback")) {
                final int consumed = consumer.applyAsInt(leaf.repos());
                plan = replace(plan, index, finishFallback(leaf, elapsed(start)));
                snapshot = save(prefix, snapshot, plan);
                completed.add(id);
                result += consumed;
                continue;
            }
            // A query which cannot fit the client limit is never sent or silently shortened.
            final var query = query(leaf);
            if (query.length() > QUERY_MAX) {
                plan = divideOrFallback(plan, index, leaf);
                snapshot = save(prefix, snapshot, plan);
                continue;
            }
            requests++;
            final var page = client.search(query, kind, leaf.after());
            final var measured = response(leaf, page, elapsed(start));
            if (page.count() >= 900 || leaf.seen() + page.nodes().size() >= 900) {
                // Do not consume the probe page. It may be a truncated view of the parent interval.
                plan = divideOrFallback(plan, index, measured);
                snapshot = save(prefix, snapshot, plan);
                continue;
            }
            final var accepted = new LinkedHashSet<String>();
            final var names = leaf.repos().stream()
                    .collect(
                            LinkedHashMap<String, String>::new,
                            (collected, repo) -> collected.put(lower(repo), repo),
                            Map::putAll);
            for (final var node : page.nodes()) {
                final var name = names.get(lower(node.repository()));
                if (name != null) {
                    accepted.add(name);
                }
            }
            // No cursor or page statistics are committed until all feed work for this page succeeds.
            final int consumed = consumer.applyAsInt(List.copyOf(accepted));
            final var updated = new Leaf(
                    id,
                    leaf.repos(),
                    leaf.scope(),
                    leaf.from(),
                    leaf.until(),
                    page.next(),
                    page.count(),
                    page.next() == null ? 0 : measured.seen(),
                    page.next() == null ? 0 : measured.pages(),
                    measured.totalRequests(),
                    elapsed(start),
                    page.next() == null ? clock.instant().toString() : leaf.lastCompletedAt(),
                    page.next() == null ? measured.pages() : leaf.lastPages(),
                    "search",
                    leaf.depth());
            plan = replace(plan, index, updated);
            snapshot = save(prefix, snapshot, plan);
            result += consumed;
            if (page.next() == null) {
                completed.add(id);
            }
        }
    }

    private int eligible(final Plan plan, final Set<Integer> completed, final boolean hasBudget) {
        for (int offset = 0; offset < plan.active().size(); offset++) {
            final int index = (plan.position() + offset) % plan.active().size();
            final var leaf = plan.leaves().get(plan.active().get(index));
            if (!completed.contains(leaf.id()) && (hasBudget || leaf.status().equals("directfallback"))) {
                return index;
            }
        }
        return -1;
    }

    private Leaf response(final Leaf leaf, final MentionSearchClient.Page page, final long duration) {
        return new Leaf(
                leaf.id(),
                leaf.repos(),
                leaf.scope(),
                leaf.from(),
                leaf.until(),
                leaf.after(),
                page.count(),
                leaf.seen() + page.nodes().size(),
                leaf.pages() + 1,
                Math.addExact(leaf.totalRequests(), 1),
                duration,
                leaf.lastCompletedAt(),
                leaf.lastPages(),
                leaf.status(),
                leaf.depth());
    }

    private Leaf finishFallback(final Leaf leaf, final long duration) {
        return new Leaf(
                leaf.id(),
                leaf.repos(),
                leaf.scope(),
                leaf.from(),
                leaf.until(),
                leaf.after(),
                leaf.count(),
                leaf.seen(),
                leaf.pages(),
                leaf.totalRequests(),
                duration,
                clock.instant().toString(),
                leaf.pages(),
                "directfallback",
                leaf.depth());
    }

    private Plan replace(final Plan plan, final int index, final Leaf leaf) {
        final var leaves = new LinkedHashMap<>(plan.leaves());
        leaves.put(leaf.id(), leaf);
        return new Plan(plan.active(), (index + 1) % plan.active().size(), plan.nextId(), leaves);
    }

    private Plan divideOrFallback(final Plan plan, final int index, final Leaf leaf) {
        final var children = new ArrayList<Leaf>();
        if (plan.active().size() < MAX_LEAVES && plan.nextId() + 2 <= MAX_TREE && leaf.depth() < MAX_DEPTH) {
            if (leaf.repos().size() > 1) {
                final int middle = leaf.repos().size() / 2;
                children.add(child(
                        plan.nextId(), leaf.repos().subList(0, middle), leaf.from(), leaf.until(), leaf.depth() + 1));
                children.add(child(
                        plan.nextId() + 1,
                        leaf.repos().subList(middle, leaf.repos().size()),
                        leaf.from(),
                        leaf.until(),
                        leaf.depth() + 1));
            } else {
                final long end = leaf.until() == null ? clock.instant().getEpochSecond() : leaf.until();
                if (end > leaf.from() && end - leaf.from() > 1) {
                    final long middle = leaf.from() + (end - leaf.from()) / 2;
                    // CREATED is immutable. Inclusive overlap is intentional; the right tail stays open.
                    children.add(child(plan.nextId(), leaf.repos(), leaf.from(), middle, leaf.depth() + 1));
                    children.add(child(plan.nextId() + 1, leaf.repos(), middle, leaf.until(), leaf.depth() + 1));
                }
            }
        }
        if (children.isEmpty()) {
            return replace(plan, index, status(leaf, "directfallback"));
        }
        final var leaves = new LinkedHashMap<>(plan.leaves());
        leaves.put(leaf.id(), status(leaf, "split"));
        children.forEach(child -> leaves.put(child.id(), child));
        final var active = new ArrayList<>(plan.active());
        active.remove(index);
        active.addAll(index, children.stream().map(Leaf::id).toList());
        return new Plan(active, (index + children.size()) % active.size(), plan.nextId() + 2, leaves);
    }

    private Leaf status(final Leaf leaf, final String status) {
        return new Leaf(
                leaf.id(),
                leaf.repos(),
                leaf.scope(),
                leaf.from(),
                leaf.until(),
                leaf.after(),
                leaf.count(),
                leaf.seen(),
                leaf.pages(),
                leaf.totalRequests(),
                leaf.lastDurationMillis(),
                leaf.lastCompletedAt(),
                leaf.lastPages(),
                status,
                leaf.depth());
    }

    private Leaf child(final int id, final List<String> repos, final long from, final Long until, final int depth) {
        return new Leaf(id, repos, null, from, until, null, 0, 0, 0, 0, 0, null, 0, "search", depth);
    }

    private Plan seed(final List<String> selected) {
        final var pending = new LinkedHashSet<>(selected);
        final var seeds = new ArrayList<Leaf>();
        final var discovery =
                conf.repositories() == null ? null : conf.repositories().discovery();
        if (discovery != null && !discovery.isBlank()) {
            for (final var raw : discovery.split(",", -1)) {
                final var scope = raw.strip();
                final var parts = scope.split(":", -1);
                if (parts.length != 2
                        || !(parts[0].equals("org") || parts[0].equals("user"))
                        || !OWNER.matcher(parts[1]).matches()) {
                    throw new IllegalArgumentException("Invalid mention search discovery scope");
                }
                final var repos = pending.stream()
                        .filter(repo -> repo.substring(0, repo.indexOf('/')).equalsIgnoreCase(parts[1]))
                        .toList();
                if (!repos.isEmpty() && seeds.size() < MAX_LEAVES - 1) {
                    final var leaf = child(seeds.size(), repos, 0, null, 0);
                    seeds.add(new Leaf(leaf.id(), repos, scope, 0, null, null, 0, 0, 0, 0, 0, null, 0, "search", 0));
                    pending.removeAll(repos);
                }
            }
        }
        var group = new ArrayList<String>();
        for (final var repo : pending) {
            final var proposed = new ArrayList<>(group);
            proposed.add(repo);
            // Reserve room for a future upper bound, so exact-repository children also fit.
            if (!group.isEmpty()
                    && query(child(0, proposed, 0, MAX_SECOND, 0)).length() > QUERY_TARGET
                    && seeds.size() < MAX_LEAVES - 1) {
                seeds.add(child(seeds.size(), group, 0, null, 0));
                group = new ArrayList<>();
            }
            group.add(repo);
        }
        if (!group.isEmpty()) {
            final var leaf = child(seeds.size(), group, 0, null, 0);
            seeds.add(seeds.size() == MAX_LEAVES - 1 ? status(leaf, "directfallback") : leaf);
        }
        final var leaves = seeds.stream()
                .collect(LinkedHashMap<Integer, Leaf>::new, (result, leaf) -> result.put(leaf.id(), leaf), Map::putAll);
        return new Plan(seeds.stream().map(Leaf::id).toList(), 0, seeds.size(), leaves);
    }

    private String query(final Leaf leaf) {
        final var query = new StringBuilder("mentions:").append(conf.login()).append(" sort:created-asc");
        if (leaf.scope() != null) {
            query.append(' ').append(leaf.scope());
        } else {
            leaf.repos().forEach(repo -> query.append(" repo:").append(repo));
        }
        query.append(" created:>=").append(Instant.ofEpochSecond(leaf.from()));
        if (leaf.until() != null) {
            query.append(" created:<=").append(Instant.ofEpochSecond(leaf.until()));
        }
        return query.toString();
    }

    private List<String> selection(final List<String> repositories) {
        Objects.requireNonNull(repositories);
        final var names = new LinkedHashMap<String, String>();
        for (final var repo : List.copyOf(repositories)) {
            if (!validRepository(repo)) {
                throw new IllegalArgumentException("Invalid mention search repository");
            }
            names.putIfAbsent(lower(repo), repo);
        }
        return names.values().stream().sorted().toList();
    }

    private boolean validRepository(final String repo) {
        return repo != null && REPOSITORY.matcher(repo).matches() && !repo.endsWith("/.") && !repo.endsWith("/..");
    }

    private String prefix(final List<String> selected, final MentionSearchClient.Kind kind) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            final var identity =
                    conf.api() + "\n" + conf.login() + "\n" + kind.name() + "\n" + String.join("\n", selected);
            return "mention-search/v1/"
                    + HexFormat.of().formatHex(digest.digest(identity.getBytes(StandardCharsets.UTF_8))) + "/";
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, String> snapshot(final Map<String, String> cursors, final String prefix) {
        final var result = new LinkedHashMap<String, String>();
        cursors.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                result.put(key.substring(prefix.length()), value);
            }
        });
        return Map.copyOf(result);
    }

    private Map<String, String> save(final String prefix, final Map<String, String> before, final Plan plan) {
        final var after = encode(plan);
        store.transaction(state -> {
            if (!snapshot(state.cursors(), prefix).equals(before)) {
                // Another poller advanced this plan. Replay is safe, overwriting its progress is not.
                throw new IllegalStateException("Mention search plan changed concurrently");
            }
            state.cursors().keySet().removeIf(key -> key.startsWith(prefix));
            after.forEach((key, value) -> state.cursors().put(prefix + key, value));
            return null;
        });
        return after;
    }

    private Map<String, String> encode(final Plan plan) {
        final var values = new LinkedHashMap<String, String>();
        values.put(
                "active",
                String.join(",", plan.active().stream().map(String::valueOf).toList()));
        values.put("position", Integer.toString(plan.position()));
        values.put("nextId", Integer.toString(plan.nextId()));
        for (final var leaf : plan.leaves().values()) {
            final var prefix = "leaf/" + leaf.id() + "/";
            values.put(prefix + "reposCSV", String.join(",", leaf.repos()));
            put(values, prefix + "scope", leaf.scope());
            values.put(prefix + "from", Long.toString(leaf.from()));
            put(values, prefix + "until", leaf.until());
            put(values, prefix + "after", leaf.after());
            values.put(prefix + "count", Long.toString(leaf.count()));
            values.put(prefix + "seen", Long.toString(leaf.seen()));
            values.put(prefix + "pages", Long.toString(leaf.pages()));
            values.put(prefix + "totalRequests", Long.toString(leaf.totalRequests()));
            values.put(prefix + "lastDurationMillis", Long.toString(leaf.lastDurationMillis()));
            put(values, prefix + "lastCompletedAt", leaf.lastCompletedAt());
            values.put(prefix + "lastPages", Long.toString(leaf.lastPages()));
            values.put(prefix + "status", leaf.status());
            values.put(prefix + "depth", Integer.toString(leaf.depth()));
        }
        return Map.copyOf(values);
    }

    private void put(final Map<String, String> values, final String key, final Object value) {
        if (value != null) {
            values.put(key, value.toString());
        }
    }

    private Plan decode(final Map<String, String> values, final List<String> selected) {
        try {
            final int next = (int) number(values, "nextId", MAX_TREE);
            final var activeText = required(values, "active").split(",", -1);
            if (next == 0 || activeText.length > MAX_LEAVES) {
                throw new IllegalArgumentException();
            }
            final var active = new ArrayList<Integer>();
            for (final var text : activeText) {
                active.add(Integer.valueOf(text));
            }
            if (new HashSet<>(active).size() != active.size()) {
                throw new IllegalArgumentException();
            }
            final int position = (int) number(values, "position", active.size() - 1);
            final var leaves = new LinkedHashMap<Integer, Leaf>();
            final var covered = new HashSet<String>();
            for (int id = 0; id < next; id++) {
                final var prefix = "leaf/" + id + "/";
                final var repos = List.of(required(values, prefix + "reposCSV").split(",", -1));
                if (repos.isEmpty() || new HashSet<>(repos).size() != repos.size() || !selected.containsAll(repos)) {
                    throw new IllegalArgumentException();
                }
                final var scope = values.get(prefix + "scope");
                if (scope != null) {
                    final var parts = scope.split(":", -1);
                    if (parts.length != 2
                            || !(parts[0].equals("org") || parts[0].equals("user"))
                            || !OWNER.matcher(parts[1]).matches()
                            || repos.stream()
                                    .anyMatch(repo -> !repo.substring(0, repo.indexOf('/'))
                                            .equalsIgnoreCase(parts[1]))) {
                        throw new IllegalArgumentException();
                    }
                }
                final long from = number(values, prefix + "from", MAX_SECOND);
                final Long until =
                        values.containsKey(prefix + "until") ? number(values, prefix + "until", MAX_SECOND) : null;
                final var after = values.get(prefix + "after");
                final var completedAt = values.get(prefix + "lastCompletedAt");
                if ((until != null && until < from) || (after != null && (after.isBlank() || after.length() > 4096))) {
                    throw new IllegalArgumentException();
                }
                if (completedAt != null) {
                    Instant.parse(completedAt);
                }
                final var status = required(values, prefix + "status");
                if (!Set.of("search", "split", "directfallback").contains(status)
                        || active.contains(id) == status.equals("split")) {
                    throw new IllegalArgumentException();
                }
                final var leaf = new Leaf(
                        id,
                        repos,
                        scope,
                        from,
                        until,
                        after,
                        number(values, prefix + "count", Integer.MAX_VALUE),
                        number(values, prefix + "seen", 1100),
                        number(values, prefix + "pages", Long.MAX_VALUE),
                        number(values, prefix + "totalRequests", Long.MAX_VALUE),
                        number(values, prefix + "lastDurationMillis", Long.MAX_VALUE),
                        completedAt,
                        number(values, prefix + "lastPages", Long.MAX_VALUE),
                        status,
                        (int) number(values, prefix + "depth", MAX_DEPTH));
                if (leaf.pages() > leaf.totalRequests()
                        || leaf.lastPages() > leaf.totalRequests()
                        || (status.equals("search")
                                && (leaf.count() >= 900
                                        || leaf.seen() >= 1000
                                        || (after == null && (leaf.seen() != 0 || leaf.pages() != 0))
                                        || (after != null && (leaf.pages() == 0 || leaf.pages() > leaf.seen()))))) {
                    throw new IllegalArgumentException();
                }
                leaves.put(id, leaf);
                if (active.contains(id)) {
                    covered.addAll(repos);
                }
            }
            if (!leaves.keySet().containsAll(active) || !covered.equals(new HashSet<>(selected))) {
                throw new IllegalArgumentException();
            }
            final var plan = new Plan(active, position, next, leaves);
            validateCoverage(plan, selected);
            // Unknown keys, missing keys, noncanonical numbers and orphaned leaves all fail closed.
            if (!encode(plan).equals(values)) {
                throw new IllegalArgumentException();
            }
            return plan;
        } catch (final RuntimeException e) {
            throw new IllegalStateException("Corrupt mention search plan", e);
        }
    }

    private void validateCoverage(final Plan plan, final List<String> selected) {
        for (final var repo : selected) {
            final var intervals = plan.active().stream()
                    .map(plan.leaves()::get)
                    .filter(leaf -> leaf.repos().contains(repo))
                    .sorted(Comparator.comparingLong(Leaf::from))
                    .toList();
            // A fallback reads the entire repository feed, independently of its search interval.
            if (intervals.stream().anyMatch(leaf -> leaf.status().equals("directfallback"))) {
                continue;
            }
            long end = 0;
            boolean unbounded = false;
            for (final var interval : intervals) {
                if (interval.from() > end) {
                    throw new IllegalArgumentException("Gap in mention search intervals");
                }
                if (interval.until() == null) {
                    unbounded = true;
                    break;
                }
                end = Math.max(end, interval.until());
            }
            if (!unbounded) {
                throw new IllegalArgumentException("Missing mention search future tail");
            }
        }
    }

    private long number(final Map<String, String> values, final String key, final long max) {
        final long value = Long.parseLong(required(values, key));
        if (value < 0 || value > max) {
            throw new IllegalArgumentException();
        }
        return value;
    }

    private String required(final Map<String, String> values, final String key) {
        return Objects.requireNonNull(values.get(key));
    }

    private static String lower(final String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private long elapsed(final long start) {
        return Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }

    private record Plan(List<Integer> active, int position, int nextId, Map<Integer, Leaf> leaves) {
        private Plan {
            active = List.copyOf(active);
            leaves = Map.copyOf(leaves);
        }
    }

    private record Leaf(
            int id,
            List<String> repos,
            String scope,
            long from,
            Long until,
            String after,
            long count,
            long seen,
            long pages,
            long totalRequests,
            long lastDurationMillis,
            String lastCompletedAt,
            long lastPages,
            String status,
            int depth) {
        private Leaf {
            repos = List.copyOf(repos);
        }
    }
}
