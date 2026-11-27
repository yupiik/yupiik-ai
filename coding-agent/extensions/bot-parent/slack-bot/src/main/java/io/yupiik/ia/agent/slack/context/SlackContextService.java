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
package io.yupiik.ia.agent.slack.context;

import io.yupiik.ia.agent.slack.configuration.SlackContextConfiguration;
import io.yupiik.ia.agent.slack.model.SlackJson;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Attempt-local controller capability, not an injectable singleton. The parent owns shared dispatch/rate limits,
 * supplies the current action token, and MUST close this service when the attempt loses ownership.
 * Calls use an "operation" discriminator: summary, resolve_user (userId), thread (limit, pageToken), or search
 * (authorUserId, mentionedUserId, terms: list of literal phrases, after/before: epoch seconds,
 * limit, pageToken). Only those fields are accepted. Page tokens are opaque, single-use, and bound to
 * the exact original arguments (including omitted defaults). Results and minimal names are untrusted data.
 *
 * <p>Transport integration contract: SlackWebApi.memberInfo(String) returns
 * CompletionStage&lt;SlackJson.UserInfoResponse&gt;; searchContext(SlackJson.SearchRequest) returns
 * CompletionStage&lt;SlackJson.SearchResponse&gt;; threadReplies(SlackJson.ThreadRequest) returns
 * CompletionStage&lt;SlackJson.ThreadResponse&gt;.
 * SearchRequest field order is query, action_token, channel_types, content_types, context_channel_id,
 * include_context_messages, include_message_blocks, include_bots, limit, cursor, after, before, sort,
 * sort_dir, disable_semantic_search. The parent owns those wire records and their generated codecs.
 */
public final class SlackContextService implements AutoCloseable {
    private static final Pattern USER = Pattern.compile("[UW][A-Z0-9]{1,63}");
    private static final Pattern WORKSPACE = Pattern.compile("T[A-Z0-9]{1,63}");
    private static final Pattern CHANNEL = Pattern.compile("[CG][A-Z0-9]{1,63}");
    private static final Pattern MENTION = Pattern.compile("<@([UW][A-Z0-9]{1,63})(?:\\|[^<>\\r\\n]{1,128})?>");
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N} ]{1,128}");
    private static final Pattern OPERATOR = Pattern.compile("(?i)(?:^| )(?:OR|AND|NOT)(?: |$)");
    private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{1,12}\\.[0-9]{1,6}");
    private static final Pattern THREAD_TIMESTAMP = Pattern.compile("[0-9]{1,20}\\.[0-9]{1,20}");
    private static final Pattern BOT = Pattern.compile("B[A-Z0-9]{1,63}");
    private static final Set<String> SEARCH_FIELDS =
            Set.of("operation", "authorUserId", "mentionedUserId", "terms", "after", "before", "limit", "pageToken");
    private final SlackContextConfiguration configuration;
    private final SlackActionRegistry.Grant grant;
    private final SlackWebApi api;
    private final Clock clock;
    private final Instant expires;
    private final Semaphore concurrent;
    private final ReentrantLock state = new ReentrantLock();
    private final AtomicBoolean revoked = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    // All below are protected by state. No full profiles, wire responses, or action tokens are retained.
    private final Set<String> grants = new LinkedHashSet<>();
    private final Map<String, Map<String, Object>> names = new HashMap<>();
    private final Map<String, Page> pages = new HashMap<>();
    private final Map<String, ThreadPage> threadPages = new HashMap<>();
    private final Set<String> searchRoots = new LinkedHashSet<>();
    private final Map<String, ThreadProgress> threads = new HashMap<>();
    private int threadRequests;
    private int threadMessages;
    private long threadBytes;
    private int requests;
    private int results;
    private long responseBytes;

    public SlackContextService(
            final SlackContextConfiguration configuration,
            final SlackWebApi api,
            final SlackActionRegistry.Grant grant,
            final Clock clock) {
        this.configuration = Objects.requireNonNull(configuration);
        this.grant = Objects.requireNonNull(grant);
        this.api = Objects.requireNonNull(api);
        this.clock = Objects.requireNonNull(clock);
        expires = Instant.ofEpochMilli(grant.deadline());
        concurrent = new Semaphore(configuration.maxConcurrent());
        if (grant.workspace() == null
                || !WORKSPACE.matcher(grant.workspace()).matches()
                || grant.channel() == null
                || !CHANNEL.matcher(grant.channel()).matches()
                || grant.requester() == null
                || !USER.matcher(grant.requester()).matches()) {
            throw new IllegalArgumentException("Invalid Slack context task scope");
        }
        grant(grant.requester());
        if (grant.request() != null) {
            final var mentions = MENTION.matcher(grant.request());
            while (mentions.find() && grants.size() < configuration.maxMembers()) {
                grant(mentions.group(1));
            }
        }
        grant.onRevoke(this::close);
    }

    public Map<String, Object> call(final Map<String, Object> arguments) {
        checkActive();
        if (arguments == null || !(arguments.get("operation") instanceof String operation)) {
            throw invalid();
        }
        if (!concurrent.tryAcquire()) {
            throw new IllegalStateException("Slack context concurrency limit reached");
        }
        try {
            final Map<String, Object> answer;
            switch (operation) {
                case "summary" -> {
                    fields(arguments, Set.of("operation"));
                    state.lock();
                    try {
                        checkActive();
                        final var summary = new LinkedHashMap<String, Object>(Map.of(
                                "workspace",
                                grant.workspace(),
                                "channel",
                                grant.channel(),
                                "requester",
                                grant.requester(),
                                "grantedUserIds",
                                List.copyOf(grants),
                                "resolveMentions",
                                configuration.resolveMentions(),
                                "searchEnabled",
                                canSearch(),
                                "remainingRequests",
                                configuration.maxRequests() - requests,
                                "remainingResults",
                                configuration.maxResults() - results,
                                "expiresAt",
                                expires.getEpochSecond()));
                        summary.put("threadEnabled", canThread());
                        if (grant.threadRoot() != null) {
                            summary.put("threadRoot", grant.threadRoot());
                        }
                        if (grant.triggerTs() != null) {
                            summary.put("triggerTs", grant.triggerTs());
                        }
                        summary.put(
                                "thread",
                                Map.of(
                                        "initialMessages",
                                                configuration.thread().initialMessages(),
                                        "maxMessages", configuration.thread().maxMessages(),
                                        "maxBytes", configuration.thread().maxBytes(),
                                        "maxRequests", configuration.thread().maxRequests(),
                                        "lookbackHours", configuration.thread().lookbackHours(),
                                        "requests", threadRequests,
                                        "messages", threadMessages,
                                        "bytes", threadBytes));
                        answer = boundedAnswer(Map.copyOf(summary));
                    } finally {
                        state.unlock();
                    }
                }
                case "resolve_user" -> {
                    fields(arguments, Set.of("operation", "userId"));
                    answer = resolveUser(requiredUser(arguments, "userId"));
                }
                case "search" -> {
                    fields(arguments, SEARCH_FIELDS);
                    answer = search(arguments);
                }
                case "thread" -> {
                    fields(arguments, Set.of("operation", "threadTs", "limit", "pageToken"));
                    answer = thread(arguments);
                }
                default -> throw invalid();
            }
            checkActive();
            return answer;
        } finally {
            concurrent.release();
        }
    }

    private Map<String, Object> resolveUser(final String user) {
        state.lock();
        try {
            checkActive();
            if (!configuration.resolveMentions() || !grants.contains(user)) {
                throw new IllegalArgumentException("Slack member is not granted");
            }
            final var cached = names.get(user);
            if (cached != null) {
                return boundedAnswer(cached);
            }
            reserveRequest();
        } finally {
            state.unlock();
        }
        final var response = await(() -> api.memberInfo(user));
        state.lock();
        try {
            checkActive();
            if (response == null || !Boolean.TRUE.equals(response.ok()) || response.user() == null) {
                throw unavailable();
            }
            final var member = response.user();
            if (!user.equals(member.id())
                    || !grant.workspace().equals(member.team_id())
                    || !Boolean.FALSE.equals(member.deleted())
                    || !Boolean.FALSE.equals(member.is_bot())) {
                throw unavailable();
            }
            final var profile = member.profile();
            final var result = Map.<String, Object>of(
                    "userId",
                    user,
                    "displayName",
                    name(profile == null ? null : profile.display_name(), member.name()),
                    "realName",
                    name(profile == null ? null : profile.real_name(), member.real_name()),
                    "untrusted",
                    true);
            final var answer = boundedAnswer(result);
            names.put(user, result);
            return answer;
        } finally {
            state.unlock();
        }
    }

    private boolean canThread() {
        return configuration.thread().enabled()
                && configuration.thread().channels().contains(grant.channel())
                && timestamp(grant.threadRoot())
                && timestamp(grant.triggerTs())
                && new BigDecimal(grant.threadRoot()).compareTo(new BigDecimal(grant.triggerTs())) < 0;
    }

    private Map<String, Object> thread(final Map<String, Object> arguments) {
        final var spec = new ThreadSpec(optionalString(arguments, "threadTs"), integer(arguments, "limit", null));
        final var requested =
                spec.limit() == null ? Math.min(20, configuration.thread().initialMessages()) : spec.limit();
        if (requested < 1 || requested > 20) {
            throw invalid();
        }
        final var root = spec.root() == null ? grant.threadRoot() : spec.root();
        final ThreadProgress progress;
        final ThreadPage page;
        final int limit;
        state.lock();
        try {
            checkActive();
            if (!canThread()) {
                throw new IllegalStateException("Slack thread context is disabled for this source");
            }
            if (!Objects.equals(root, grant.threadRoot()) && !searchRoots.contains(root)) {
                throw invalid();
            }
            progress = threads.computeIfAbsent(root, ignored -> new ThreadProgress());
            if (progress.busy) {
                throw new IllegalStateException("Slack thread page already in flight");
            }
            if (threadRequests >= configuration.thread().maxRequests()
                    || threadMessages >= configuration.thread().maxMessages()
                    || threadBytes >= configuration.thread().maxBytes()) {
                throw new IllegalStateException("Slack thread budget exhausted");
            }
            final var token = optionalString(arguments, "pageToken");
            if (token == null) {
                if (progress.started) {
                    throw new IllegalArgumentException("Slack thread requires its next page token");
                }
                page = new ThreadPage(spec, null);
            } else {
                final var saved = threadPages.get(token);
                if (saved == null || !saved.spec().equals(spec)) {
                    throw new IllegalArgumentException("Invalid scoped Slack thread page token");
                }
                page = saved;
            }
            reserveRequest();
            if (token != null) {
                threadPages.remove(token);
            }
            threadRequests++;
            progress.busy = true;
            limit = (int) Math.min(requested, configuration.thread().maxMessages() - threadMessages);
        } finally {
            state.unlock();
        }
        try {
            // Start at the root, not at a moving wall-clock boundary. Old replies are filtered below;
            // the upstream scan itself is bounded by the page limit and the independent call budget.
            final var response = await(() -> api.threadReplies(new SlackJson.ThreadRequest(
                    grant.channel(), root, root, grant.triggerTs(), true, limit, page.cursor())));
            state.lock();
            try {
                checkActive();
                if (response == null
                        || !Boolean.TRUE.equals(response.ok())
                        || response.messages() == null
                        || (response.channel() != null && !grant.channel().equals(response.channel()))
                        || (response.team() != null && !grant.workspace().equals(response.team()))
                        || response.messages().size() > limit) {
                    throw unavailable();
                }
                final var cursor = response.response_metadata() == null
                        ? null
                        : response.response_metadata().next_cursor();
                final var hasCursor = cursor != null && !cursor.isBlank();
                if (hasCursor && (cursor.length() > 4096 || progress.cursors.contains(cursor))) {
                    throw unavailable();
                }
                final var more = hasCursor || Boolean.TRUE.equals(response.has_more());
                final var next = hasCursor
                                && threadRequests < configuration.thread().maxRequests()
                                && requests < configuration.maxRequests()
                        ? UUID.randomUUID().toString()
                        : null;
                final var answer = new LinkedHashMap<String, Object>();
                answer.put("channelId", grant.channel());
                answer.put("threadTs", root);
                answer.put("cutoffTs", grant.triggerTs());
                answer.put("order", "earliest_first");
                answer.put("untrusted", true);
                answer.put("truncated", more);
                if (next != null) {
                    answer.put("pageToken", next);
                }
                final var accepted = new ArrayList<Map<String, Object>>();
                answer.put("messages", accepted);
                var previous = progress.last;
                var omitted = false;
                var byteLimit = false;
                final var cutoff = new BigDecimal(grant.triggerTs());
                final var earliest = cutoff.subtract(BigDecimal.valueOf(
                        Duration.ofHours(configuration.thread().lookbackHours()).toSeconds()));
                for (final var message : response.messages()) {
                    if (message == null || !timestamp(message.ts())) {
                        throw unavailable();
                    }
                    final var ts = new BigDecimal(message.ts());
                    if (previous != null && ts.compareTo(previous) <= 0) {
                        throw unavailable(); // No unordered pages, duplicates, or overlapping pagination.
                    }
                    previous = ts;
                    if (threadMessages + accepted.size()
                            >= configuration.thread().maxMessages()) {
                        omitted = true;
                        continue;
                    }
                    if (byteLimit) {
                        continue;
                    }
                    if (!validThreadMessage(message, root, ts, cutoff, earliest)) {
                        omitted = true;
                        continue;
                    }
                    final var item = new LinkedHashMap<String, Object>();
                    item.put("messageTs", message.ts());
                    item.put("content", message.text());
                    item.put("untrusted", true);
                    if (message.user() != null && USER.matcher(message.user()).matches()) {
                        item.put("authorUserId", message.user());
                    }
                    if (message.bot_id() != null) {
                        item.put("botId", message.bot_id());
                    }
                    accepted.add(Map.copyOf(item));
                    final var size = jsonUpperBound(answer) + 256;
                    if (threadBytes + size > configuration.thread().maxBytes()
                            || responseBytes + size > configuration.maxResponseBytes()
                            || size > 128 * 1024) {
                        accepted.removeLast();
                        byteLimit = true;
                        omitted = true;
                        // Still validate the rest of the page's ordering, but never skip ahead over lost text.
                        continue;
                    }
                }
                // Do not offer continuation when this page lost text to a byte cap or total message cap.
                if (byteLimit
                        || threadMessages + accepted.size()
                                >= configuration.thread().maxMessages()) {
                    answer.remove("pageToken");
                }
                answer.put("truncated", more || omitted);
                answer.put("messages", List.copyOf(accepted));
                final var size = jsonUpperBound(answer) + 256;
                if (threadBytes + size > configuration.thread().maxBytes()) {
                    throw new IllegalStateException("Slack thread response budget exhausted");
                }
                final var bounded = boundedAnswer(Map.copyOf(answer));
                progress.started = true;
                progress.last = previous;
                threadMessages += accepted.size();
                threadBytes += size;
                if (hasCursor) {
                    progress.cursors.add(cursor);
                }
                if (answer.containsKey("pageToken")) {
                    threadPages.put(next, new ThreadPage(spec, cursor));
                }
                for (final var item : accepted) {
                    grant((String) item.get("authorUserId"));
                    final var mentions = MENTION.matcher((String) item.get("content"));
                    while (mentions.find()) {
                        grant(mentions.group(1));
                    }
                }
                return bounded;
            } finally {
                state.unlock();
            }
        } finally {
            state.lock();
            try {
                progress.busy = false;
            } finally {
                state.unlock();
            }
        }
    }

    private boolean validThreadMessage(
            final SlackJson.ThreadMessage message,
            final String threadRoot,
            final BigDecimal ts,
            final BigDecimal cutoff,
            final BigDecimal earliest) {
        final var root = new BigDecimal(threadRoot);
        if (ts.compareTo(root) < 0
                || ts.compareTo(cutoff) >= 0
                || (ts.compareTo(root) != 0 && ts.compareTo(earliest) < 0)
                || (message.thread_ts() != null && !threadRoot.equals(message.thread_ts()))
                || (ts.compareTo(root) != 0 && message.thread_ts() == null)
                || (message.channel() != null && !grant.channel().equals(message.channel()))
                || (message.team() != null && !grant.workspace().equals(message.team()))
                || !"message".equals(message.type())
                || Boolean.TRUE.equals(message.hidden())
                || (message.subtype() != null && !"bot_message".equals(message.subtype()))
                || message.text() == null
                || message.text().length() > configuration.thread().maxBytes()
                || message.text().getBytes(StandardCharsets.UTF_8).length
                        > configuration.thread().maxBytes()
                || (message.user() != null && !USER.matcher(message.user()).matches())
                || (message.bot_id() != null && !BOT.matcher(message.bot_id()).matches())
                || (message.user() == null && message.bot_id() == null)) {
            return false;
        }
        if (message.edited() != null) {
            if (!timestamp(message.edited().ts())) {
                return false;
            }
            final var edited = new BigDecimal(message.edited().ts());
            if (edited.compareTo(ts) < 0 || edited.compareTo(cutoff) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean timestamp(final String value) {
        return value != null && THREAD_TIMESTAMP.matcher(value).matches();
    }

    private Map<String, Object> search(final Map<String, Object> arguments) {
        final var spec = spec(arguments);
        final Page page;
        final int limit;
        state.lock();
        try {
            checkActive();
            if (!canSearch()) {
                throw new IllegalStateException("Slack context search is disabled for this source");
            }
            requireGrant(spec.author());
            requireGrant(spec.mentioned());
            final var token = optionalString(arguments, "pageToken");
            if (token != null) {
                final var saved = pages.get(token);
                if (saved == null || !saved.spec().equals(spec)) {
                    throw new IllegalArgumentException("Invalid scoped Slack page token");
                }
                page = saved;
                pages.remove(token);
            } else {
                final var now = new BigDecimal(grant.triggerTs()).longValue();
                final var earliest =
                        now - Duration.ofDays(configuration.lookbackDays()).toSeconds();
                final var after = spec.after() == null ? earliest : spec.after();
                final var before = spec.before() == null ? now : spec.before();
                if (after < earliest || before > now || before <= after) {
                    throw new IllegalArgumentException("Invalid Slack search time range");
                }
                page = new Page(spec, after, before, null);
            }
            if (page.before() <= page.after()) {
                throw new IllegalArgumentException("Slack search page is outside the lookback window");
            }
            limit = Math.min(spec.limit(), configuration.maxResults() - results);
            if (limit < 1) {
                throw new IllegalStateException("Slack context result budget exhausted");
            }
            reserveRequest();
        } finally {
            state.unlock();
        }
        final String token;
        try {
            token = grant.get();
        } catch (final RuntimeException error) {
            throw unavailable();
        }
        if (token == null || token.isBlank() || token.length() > 8192) {
            throw unavailable();
        }
        final var query = query(spec);
        final var response = await(() -> api.searchContext(new SlackJson.SearchRequest(
                query,
                token,
                List.of("public_channel"),
                List.of("messages"),
                grant.channel(),
                false,
                false,
                false,
                limit,
                page.cursor(),
                page.after(),
                page.before(),
                "timestamp",
                "desc",
                true)));
        state.lock();
        try {
            checkActive();
            if (!token.equals(grant.get())) {
                throw unavailable();
            }
            if (response == null || !Boolean.TRUE.equals(response.ok()) || response.results() == null) {
                throw unavailable();
            }
            final var messages = response.results().messages();
            final var accepted = new ArrayList<Map<String, Object>>();
            final var authors = new ArrayList<String>();
            // Metadata plus token have ample headroom; every string is conservatively JSON-sized below.
            long bytes = 2048;
            if (messages != null) {
                for (final var message : messages) {
                    if (accepted.size() >= limit || results + accepted.size() >= configuration.maxResults()) {
                        break;
                    }
                    if (!validMessage(message, page)) {
                        continue;
                    }
                    final var item = Map.<String, Object>of(
                            "authorUserId",
                            message.author_user_id(),
                            "channelId",
                            message.channel_id(),
                            "messageTs",
                            message.message_ts(),
                            "content",
                            message.content(),
                            "permalink",
                            message.permalink(),
                            "untrusted",
                            true);
                    final var itemBytes = jsonUpperBound(item);
                    if (responseBytes + bytes + itemBytes > configuration.maxResponseBytes()
                            || bytes + itemBytes > 128 * 1024) {
                        if (accepted.isEmpty()) {
                            throw new IllegalStateException("Slack context response budget exhausted");
                        }
                        break;
                    }
                    bytes += itemBytes;
                    accepted.add(item);
                    authors.add(message.author_user_id());
                }
            }
            final var answer = new LinkedHashMap<String, Object>();
            answer.put("messages", List.copyOf(accepted));
            final var cursor = response.response_metadata() == null
                    ? null
                    : response.response_metadata().next_cursor();
            String next = null;
            if (cursor != null
                    && !cursor.isBlank()
                    && cursor.length() <= 4096
                    && requests < configuration.maxRequests()
                    && results + accepted.size() < configuration.maxResults()
                    && pages.size() < configuration.maxRequests()) {
                next = UUID.randomUUID().toString();
                answer.put("pageToken", next);
            }
            final var bounded = boundedAnswer(Map.copyOf(answer));
            if (next != null) {
                pages.put(next, new Page(spec, page.after(), page.before(), cursor));
            }
            // Only authors of actually returned, scope-validated messages can expand grants.
            authors.forEach(this::grant);
            // Only returned, fully validated origin-channel hits confer root candidates.
            // Search has no root field; Slack rejects candidates that are not thread roots.
            searchRoots.addAll(accepted.stream()
                    .map(item -> (String) item.get("messageTs"))
                    .toList());
            results += accepted.size();
            return bounded;
        } finally {
            state.unlock();
        }
    }

    private SearchSpec spec(final Map<String, Object> arguments) {
        final var terms = new ArrayList<String>();
        if (arguments.containsKey("terms")) {
            if (!(arguments.get("terms") instanceof List<?> values) || values.isEmpty() || values.size() > 8) {
                throw invalid();
            }
            for (final var value : values) {
                if (!(value instanceof String term)
                        || term.isBlank()
                        || !TERM.matcher(term).matches()
                        || OPERATOR.matcher(term).find()) {
                    throw new IllegalArgumentException("Slack search terms must be literal words or phrases");
                }
                terms.add(term.strip());
            }
        }
        final var limit = integer(arguments, "limit", (long) configuration.maxResults());
        if (limit < 1 || limit > configuration.maxResults()) {
            throw invalid();
        }
        return new SearchSpec(
                optionalUser(arguments, "authorUserId"),
                optionalUser(arguments, "mentionedUserId"),
                List.copyOf(terms),
                integer(arguments, "after", null),
                integer(arguments, "before", null),
                limit.intValue());
    }

    private String query(final SearchSpec spec) {
        final var query = new StringBuilder("in:<#").append(grant.channel()).append('>');
        if (spec.author() != null) {
            query.append(" from:<@").append(spec.author()).append('>');
        }
        if (spec.mentioned() != null) {
            query.append(' ').append(quote("<@" + spec.mentioned() + ">"));
        }
        for (final var term : spec.terms()) {
            query.append(' ').append(quote(term));
        }
        return query.toString();
    }

    private boolean validMessage(final SlackJson.SearchMessage message, final Page page) {
        if (message == null
                || !grant.workspace().equals(message.team_id())
                || !grant.channel().equals(message.channel_id())
                || message.author_user_id() == null
                || !USER.matcher(message.author_user_id()).matches()
                || message.content() == null
                || message.message_ts() == null
                || !TIMESTAMP.matcher(message.message_ts()).matches()
                || (page.spec().author() != null && !page.spec().author().equals(message.author_user_id()))) {
            return false;
        }
        final var timestamp = new BigDecimal(message.message_ts());
        if (timestamp.compareTo(BigDecimal.valueOf(page.after())) <= 0
                || timestamp.compareTo(BigDecimal.valueOf(page.before())) >= 0
                || timestamp.compareTo(new BigDecimal(grant.triggerTs())) >= 0) {
            return false;
        }
        if (page.spec().mentioned() != null) {
            final var mentions = MENTION.matcher(message.content());
            var found = false;
            while (mentions.find()) {
                if (page.spec().mentioned().equals(mentions.group(1))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        try {
            final var uri = URI.create(message.permalink());
            final var host = uri.getHost();
            final var slackTimestamp =
                    timestamp.movePointRight(6).toBigIntegerExact().toString();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && ("slack.com".equalsIgnoreCase(host)
                            || host.toLowerCase(Locale.ROOT).endsWith(".slack.com"))
                    && uri.getRawUserInfo() == null
                    && uri.getPort() == -1
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && ("/archives/" + grant.channel() + "/p" + slackTimestamp).equals(uri.getRawPath());
        } catch (final RuntimeException invalid) {
            return false;
        }
    }

    private <T> T await(final Supplier<CompletionStage<T>> invocation) {
        checkActive();
        final CompletableFuture<T> future;
        try {
            future = invocation.get().toCompletableFuture();
        } catch (final RuntimeException error) {
            if (error instanceof SlackWebApi.SlackApiException safe) {
                rejectAction(safe);
                throw safe;
            }
            throw unavailable();
        }
        state.lock();
        try {
            try {
                checkActive();
                pending.add(future);
            } catch (final RuntimeException revoked) {
                future.cancel(true);
                throw revoked;
            }
        } finally {
            state.unlock();
        }
        try {
            checkActive();
            final var remaining = Duration.between(clock.instant(), expires).toMillis();
            final var value = future.get(
                    Math.max(1, Math.min(remaining, TimeUnit.SECONDS.toMillis(configuration.timeoutSeconds()))),
                    TimeUnit.MILLISECONDS);
            checkActive();
            return value;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (final ExecutionException error) {
            if (error.getCause() instanceof SlackWebApi.SlackApiException safe) {
                rejectAction(safe);
            }
            if (error.getCause() instanceof SlackWebApi.SlackApiException safe
                    && (safe.status() == 429
                            || Set.of(
                                            "ratelimited",
                                            "missing_scope",
                                            "invalid_auth",
                                            "invalid_action_token",
                                            "action_token_expired",
                                            "expired_action_token",
                                            "not_enabled",
                                            "restricted_action")
                                    .contains(safe.error()))) {
                throw safe;
            }
            throw unavailable();
        } catch (final TimeoutException | CancellationException error) {
            // Do not leak bodies, tokens, member data, or transport exception causes to the caller.
            throw unavailable();
        } finally {
            pending.remove(future);
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private boolean canSearch() {
        return configuration.searchEnabled()
                && configuration.sourceChannels().contains(grant.channel())
                && grant.get() != null;
    }

    private void rejectAction(final SlackWebApi.SlackApiException error) {
        if (Set.of("invalid_action_token", "action_token_expired", "expired_action_token")
                .contains(error.error())) {
            grant.revoke(SlackActionRegistry.Terminal.ACTION_REJECTED);
        }
    }

    private void reserveRequest() {
        checkActive();
        if (requests >= configuration.maxRequests()) {
            throw new IllegalStateException("Slack context request budget exhausted");
        }
        requests++;
    }

    private void grant(final String user) {
        if (user != null && USER.matcher(user).matches() && grants.size() < configuration.maxMembers()) {
            grants.add(user);
        }
    }

    private void requireGrant(final String user) {
        if (user != null && !grants.contains(user)) {
            throw new IllegalArgumentException("Slack member is not granted");
        }
    }

    private void checkActive() {
        if (!clock.instant().isBefore(expires)) {
            close();
            throw new IllegalStateException("Slack context expired");
        }
        if (revoked.get() || !grant.active(clock.millis())) {
            throw new IllegalStateException("Slack context is revoked");
        }
        if (!configuration.enabled()) {
            throw new IllegalStateException("Slack context is disabled");
        }
    }

    /** Revokes local capabilities and cancels in-flight calls; borrowed transport resources stay open. */
    @Override
    public void close() {
        state.lock();
        try {
            revoked.set(true);
            pending.forEach(future -> future.cancel(true));
            pending.clear();
            pages.clear();
            threadPages.clear();
            threads.clear();
            searchRoots.clear();
            threadRequests = 0;
            threadMessages = 0;
            threadBytes = 0;
            names.clear();
            grants.clear();
        } finally {
            state.unlock();
        }
    }

    private static void fields(final Map<String, Object> arguments, final Set<String> allowed) {
        if (!allowed.containsAll(arguments.keySet())) {
            throw invalid();
        }
    }

    private static String requiredUser(final Map<String, Object> arguments, final String key) {
        final var value = optionalUser(arguments, key);
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private static String optionalUser(final Map<String, Object> arguments, final String key) {
        final var value = optionalString(arguments, key);
        if (value != null && !USER.matcher(value).matches()) {
            throw invalid();
        }
        return value;
    }

    private static String optionalString(final Map<String, Object> arguments, final String key) {
        if (!arguments.containsKey(key)) {
            return null;
        }
        if (!(arguments.get(key) instanceof String value) || value.isBlank() || value.length() > 128) {
            throw invalid();
        }
        return value;
    }

    private static Long integer(final Map<String, Object> arguments, final String key, final Long fallback) {
        if (!arguments.containsKey(key)) {
            return fallback;
        }
        if (!(arguments.get(key) instanceof Number number)) {
            throw invalid();
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (final ArithmeticException | NumberFormatException invalid) {
            throw invalid();
        }
    }

    private static String quote(final String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String name(final String preferred, final String fallback) {
        final var value = preferred == null || preferred.isBlank() ? fallback : preferred;
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(128, value.length()));
    }

    // Upper bound even when a serializer escapes every UTF-16 unit as six ASCII characters. Only small maps/lists
    // with strings, booleans and integers are emitted. This avoids injecting a second JSON mapper.
    private static long jsonUpperBound(final Object value) {
        if (value instanceof String text) {
            return 2L + 6L * text.length();
        }
        if (value instanceof Map<?, ?> map) {
            long size = 2;
            for (final var entry : map.entrySet()) {
                size += jsonUpperBound(entry.getKey()) + 2 + jsonUpperBound(entry.getValue());
            }
            return size;
        }
        if (value instanceof List<?> list) {
            long size = 2;
            for (final var item : list) {
                size += jsonUpperBound(item) + 1;
            }
            return size;
        }
        return 32;
    }

    // Called with state held, before committing grants, cache entries, or page tokens.
    private Map<String, Object> boundedAnswer(final Map<String, Object> answer) {
        checkActive();
        final var size = jsonUpperBound(answer) + 256; // Host status and mailbox envelope headroom.
        if (responseBytes + size > configuration.maxResponseBytes() || size > 128 * 1024) {
            throw new IllegalStateException("Slack context response budget exhausted");
        }
        responseBytes += size;
        return answer;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Slack context arguments");
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Slack context upstream unavailable");
    }

    private record SearchSpec(
            String author, String mentioned, List<String> terms, Long after, Long before, int limit) {}

    private record Page(SearchSpec spec, long after, long before, String cursor) {}

    private record ThreadSpec(String root, Long limit) {}

    private record ThreadPage(ThreadSpec spec, String cursor) {}

    private static final class ThreadProgress {
        private final Set<String> cursors = new LinkedHashSet<>();
        private BigDecimal last;
        private boolean started;
        private boolean busy;
    }
}
