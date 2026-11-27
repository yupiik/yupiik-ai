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

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.memory.MemoryCompactor;
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Extracts durable facts (user preferences, project decisions, long-lived
 * knowledge) out of a session conversation into the shared memory store.
 *
 * <p>The end-of-turn hook ({@link #asyncExtractNow(List, ChatSettings)}) runs
 * immediately after a turn ends - while the app sits idle waiting for the
 * next user input - asynchronously and best-effort, and only when at least
 * {@code memory.extractMinChars} characters of <em>new</em> (not yet
 * extracted) user/assistant text accumulated since the last successful
 * extraction. Tool results, tool-call frames, the system prompt, compaction/
 * prune digests and the volatile {@code <session-state>} tail are excluded
 * from the excerpt: they carry no durable signal. A close-time forced pass
 * waits for an active extraction and only processes the remaining delta,
 * including short text deferred by the asynchronous threshold.</p>
 *
 * <p>Mined facts are written directly to the durable store
 * ({@link MemoryStore#append}/{@link MemoryStore#appendText}) - live and crash-safe.
 * The prompt prefix is a session-frozen snapshot (built once, never re-read
 * mid-session), so the current session already has the facts conversationally and
 * its frozen memory section never reflects them; they are picked up by the
 * <em>next</em> session (or a resume) which rebuilds the snapshot. The fact write
 * is invisible to the current prompt, so it never invalidates the provider cache.</p>
 *
 * <p>The watermark is an in-memory conversation snapshot: side-effect free on crash
 * (nothing to recover - a killed session simply never ran its pass), and
 * re-feeding already extracted messages on resume is harmless thanks to the
 * fuzzy deduplication in {@link MemoryStore}. It guarantees the pending delta
 * is extracted at most once until new messages appear.</p>
 */
public class MemoryExtractor {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern MINIMAL_FACT =
            Pattern.compile("\\{\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"scope\"\\s*:\\s*\"([^\"]*)\""
                    + "(?:\\s*,\\s*\"p\"\\s*:\\s*(\\d+))?(?:\\s*,\\s*\"path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?"
                    + "(?:\\s*,\\s*\"topic\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?\\s*}");
    private static final Logger LOGGER = Logger.getLogger(MemoryExtractor.class.getName());

    /** Max messages of the delta fed to the model (newest kept). */
    private static final int MAX_MESSAGES = 20;

    /** Hard cap on the excerpt characters (newest kept, role-aware). */
    private static final int MAX_EXCERPT_CHARS = 12_000;

    /**
     * Markers of synthetic/injected user content that must never be mined for
     * facts: compaction/prune digests, truncation nudges, the volatile tail,
     * context-overflow re-asks. Mirrors {@code AgentLoop.lastUserPrompt}.
     */
    static boolean isSyntheticUserContent(final Message message) {
        if (message.others() != null && Boolean.TRUE.equals(message.others().get("volatileTail"))) {
            return true;
        }
        final var content = message.content();
        if (!(content instanceof String s)) {
            return true;
        }
        return s.contains("<compacted-context>")
                || s.contains("<pruned-context>")
                || s.startsWith("... output truncated")
                || s.startsWith("When a compacted context is provided");
    }

    /** Whether this message carries durable signal worth feeding the extractor. */
    static boolean isMemoryWorthy(final Message message) {
        if ("tool".equals(message.role())) {
            return false;
        }
        if ("system".equals(message.role())) {
            return false;
        }
        if (isSyntheticUserContent(message)) {
            return false;
        }
        final var content = message.content();
        if (!(content instanceof String s) || s.isBlank()) {
            return false;
        }
        // assistant tool-call frames carry no text (only tool_calls)
        return !"assistant".equals(message.role())
                || message.toolCalls() == null
                || message.toolCalls().isEmpty();
    }

    /** Imperative recall phrasings that mark the surrounding fact as an explicit directive (p>=4). */
    private static final List<Pattern> RECALL_PATTERNS = List.of(
            Pattern.compile("\\bremember that\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bremember to\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(and|also) remember\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdon'?t forget\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnote (down )?that\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(please )?keep in mind\\b", Pattern.CASE_INSENSITIVE));

    /** Whether the excerpt (rendered delta) contains user imperative recall intent. */
    static boolean hasRecallIntent(final String excerpt) {
        if (excerpt == null) {
            return false;
        }
        final var lower = excerpt.toLowerCase(Locale.ROOT);
        for (final var pattern : RECALL_PATTERNS) {
            if (pattern.matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    private final ChatClient chatClient;
    private final CodingAgentConfiguration configuration;
    private final ProviderManager providerManager;
    private final MemoryStore memoryStore;
    private final JsonMapper mapper;
    private final ExecutorService executor;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final Emitter emitter;
    private final Clock clock;
    private final UsageTracker usageTracker;
    private final String sessionId;
    private final MemoryCompactor memoryCompactor;
    private final MemoryProductionPolicy productionPolicy;
    private final SecretRedactor secretRedactor;
    /** Serializes extraction and protects the successful input snapshot (in-memory watermark). */
    private final ReentrantLock extractionLock = new ReentrantLock();

    private List<Message> extractedMessages = List.of();
    private final Path workdir;

    /**
     * Binds this session's project root once, before extraction is submitted (including close-time extraction).
     * Null retains the configured default. The shared store and compactor are never rebound.
     */
    public MemoryExtractor(
            final ChatClient chatClient,
            final CodingAgentConfiguration configuration,
            final ProviderManager providerManager,
            final MemoryStore memoryStore,
            final JsonMapper mapper,
            final ExecutorService executor,
            final Emitter emitter,
            final Clock clock,
            final UsageTracker usageTracker,
            final String sessionId,
            final MemoryCompactor memoryCompactor,
            final MemoryProductionPolicy productionPolicy,
            final SecretRedactor secretRedactor,
            final Path workdir) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.chatClient = chatClient;
        this.configuration = configuration;
        this.providerManager = providerManager;
        this.memoryStore = memoryStore;
        this.mapper = mapper;
        this.executor = executor;
        this.emitter = emitter;
        this.clock = clock;
        this.usageTracker = usageTracker;
        this.sessionId = sessionId;
        this.memoryCompactor = memoryCompactor;
        this.productionPolicy = productionPolicy;
        this.secretRedactor = secretRedactor;
    }

    protected MemoryExtractor(final Path workdir) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.chatClient = null;
        this.configuration = null;
        this.providerManager = null;
        this.memoryStore = null;
        this.mapper = null;
        this.executor = null;
        this.emitter = null;
        this.clock = null;
        this.usageTracker = null;
        this.sessionId = null;
        this.memoryCompactor = null;
        this.productionPolicy = null;
        this.secretRedactor = null;
    }

    /**
     * End-of-turn hook: fires immediately - no debounce - and best-effort, asynchronously, on
     * the background executor. It runs while the app sits idle waiting for the next user input,
     * with a stable copy of the conversation. The successful-input watermark avoids duplicate
     * calls when hooks overlap with forced extraction. The {@code extractMinChars} threshold
     * and meaningful-delta gate are applied on the executor task.
     */
    public void asyncExtractNow(final List<Message> messages) {
        asyncExtractNow(messages, ChatSettings.EMPTY, true);
    }

    public void asyncExtractNow(final List<Message> messages, final ChatSettings settings) {
        asyncExtractNow(messages, settings, true);
    }

    /**
     * End-of-turn hook with the eligibility decision computed by {@code AgentLoop}:
     * {@code eligible} is the turn-cadence/todo-completion flush decision
     * ({@code memory.extractEveryTurns} / {@code memory.extractOnTodosDone}). When not
     * eligible, the turn's delta is left pending (the watermark is NOT advanced), so it is
     * picked up by the next eligible turn or the forced session-close pass - nothing is lost
     * and nothing is re-processed. Direct callers (tests, close paths) use the overloads above
     * which default to eligible=true (legacy every-turn behavior).
     */
    public void asyncExtractNow(final List<Message> messages, final ChatSettings settings, final boolean eligible) {
        if (!enabled() || !eligible) {
            return;
        }
        final var effective = settings == null ? ChatSettings.EMPTY : settings;
        final var snapshot = List.copyOf(messages);
        executor.execute(() -> {
            try {
                doExtractNow(snapshot, effective, false);
            } catch (final RuntimeException e) {
                if (LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINER, "Memory extraction failed", e);
                }
                notifyFailure("memory extraction aborted: " + e.getMessage(), e);
            } finally {
                if (memoryCompactor != null) {
                    memoryCompactor.maybeCompact(effective, workdir);
                }
            }
        });
    }

    /**
     * Synchronous extraction used when the session stops (tab close, headless run end). With
     * the end-of-turn hook covering the delta while the app was idle, this is normally a
     * watermark no-op; it stays as the deterministic last-resort guarantee for sessions closed
     * before their first turn ever finished (e.g. a prompt typed while the previous session
     * still ran elsewhere).
     *
     * @return {@code true} when an extraction pass ran, {@code false} when
     *         there was nothing new to extract.
     */
    public boolean forceExtractNow(final List<Message> messages) {
        return forceExtractNow(messages, ChatSettings.EMPTY);
    }

    public boolean forceExtractNow(final List<Message> messages, final ChatSettings settings) {
        try {
            return doExtractNow(List.copyOf(messages), settings == null ? ChatSettings.EMPTY : settings, true);
        } catch (final RuntimeException e) {
            LOGGER.log(Level.FINE, "Forced memory extraction failed", e);
            return false;
        }
    }

    /**
     * Shared synchronous entry point behind {@link #forceExtractNow(List, ChatSettings)};
     * {@code forced} makes the close-time pass bypass the {@code extractMinChars} threshold
     * (a short session must not lose its facts when it closes).
     */
    private boolean doExtractNow(final List<Message> messages, final ChatSettings settings, final boolean forced) {
        extractionLock.lock();
        try {
            if (!enabled() || !hasMeaningfulDelta(messages)) {
                return false;
            }
            doExtract(messages, settings, forced);
            return true;
        } finally {
            extractionLock.unlock();
        }
    }

    private boolean enabled() {
        return (productionPolicy == null || productionPolicy.enabled(sessionId))
                && configuration != null
                && configuration.memory().enabled()
                && configuration.memory().maxRatio() > 0
                && configuration.memory().maxChars() > 0;
    }

    /**
     * Whether the pending delta contains at least one memory-worthy message:
     * a delta made only of tool frames, digest or tail messages must not fire
     * an extraction call (nothing to gain), and must not block the watermark
     * either - those messages will never become worthy, so skipping them is
     * a terminal decision.
     */
    private boolean hasMeaningfulDelta(final List<Message> messages) {
        for (final var m : delta(messages)) {
            if (isMemoryWorthy(m)) {
                return true;
            }
        }
        return false;
    }

    private void doExtract(final List<Message> messages, final ChatSettings settings, final boolean forced) {
        final var delta = delta(messages);
        if (!forced && deltaChars(delta) < configuration.memory().extractMinChars()) {
            // Keep short deltas pending for the next eligible turn or forced close-time pass.
            return;
        }
        final var excerpt = buildExcerpt(delta);
        // never let a conversation excerpt (which may carry secrets) reach the memory LLM raw
        final var redactedExcerpt = secretRedactor == null ? excerpt : secretRedactor.redact(excerpt, sessionId);
        try {
            final var request = new ChatCompletionRequest(
                            memoryModel(settings) == null
                                            || memoryModel(settings).isBlank()
                                    ? null
                                    : memoryModel(settings),
                            List.of(
                                    Message.system(
                                            "Extract durable facts from this conversation excerpt: user preferences, project decisions, long lived knowledge. The response is constrained to the object {\"facts\": [{\"text\": string, \"scope\": \"global\" or \"project\", \"p\": integer 1-5, \"path\": string or null, \"topic\": string or null}]} by the output schema, max 3 facts. Use an empty facts array when nothing is worth remembering. EXCLUDE: transient task state, implementation details (\"X must be fixed / must round-trip\"), plans, undecided choices, and one-off session observations (benchmarks, sandbox paths, single-run results) that will be stale or superseded later. Keep each fact to one short sentence, at most 200 characters. User preferences and cross-project durable rules (\"always use tabs\", \"never edit generated files\") are scope \"global\"; project-specific decisions are scope \"project\". Set p to 5 for an explicit directive the user must keep (eg. they said \"remember\", \"don't forget\" or \"always\"), 4 for strong durable user preferences, else 2. Fill path with the relative file/package the fact is about when obvious (else null), and topic with 1-3 short domain keywords (eg. maven build) to aid retrieval."),
                                    Message.user(redactedExcerpt)),
                            null,
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null)
                    .withSessionId(sessionId)
                    .withSettings(settings)
                    .withTuning(
                            providerManager == null
                                    ? null
                                    : providerManager.resolvedBackgroundTuning(settings, "memory"));
            final var res = chatClient.chatStructured(request, "memory_facts", factsSchema());
            if (usageTracker != null && res.result() != null) {
                usageTracker.recordAuxiliary(res.result().usage());
            }
            if (!res.structured()) {
                // the provider rejected the JSON Schema (e.g. Anthropic 400 'json was malformed')
                // and chatStructured already fell back to a plain completion: surface a soft notice
                // with context, then still save the plain snippet below
                final var fallbackContent = res.result() == null
                                || res.result().message() == null
                                || res.result().message().content() == null
                        ? ""
                        : String.valueOf(res.result().message().content());
                notifyFailure(
                        "memory extraction (structured facts) rejected by the provider; saving a plain snippet instead"
                                + (fallbackContent.isBlank() ? "" : " (" + clamp(fallbackContent, 200) + ")"));
            }
            if (res.structured()) {
                final var content =
                        res.result().message() == null || res.result().message().content() == null
                                ? ""
                                : String.valueOf(res.result().message().content());
                final var facts = parseFacts(content);
                if (!facts.isEmpty()) {
                    // a user imperative recall ("remember that...", "don't forget...") in the delta
                    // upgrades the mined facts to at least p4 so they are treated as directives
                    final var recall = hasRecallIntent(excerpt);
                    final var global = new ArrayList<String>();
                    final var project = new ArrayList<String>();
                    for (final var fact : facts) {
                        final var p = Math.max(fact.p(), recall ? 4 : 2);
                        final var safeText =
                                secretRedactor == null ? fact.text() : secretRedactor.redact(fact.text(), sessionId);
                        final var stored = MemoryStore.withMeta(p, fact.topic(), fact.path(), safeText);
                        if (stored == null) {
                            continue;
                        }
                        if ("global".equals(fact.scope())) {
                            global.add(stored);
                        } else {
                            project.add(stored);
                        }
                    }
                    if (!global.isEmpty()) {
                        memoryStore.append(true, global, workdir);
                    }
                    if (!project.isEmpty()) {
                        memoryStore.append(false, project, workdir);
                    }
                }
                resetFailures();
                extractedMessages = messages;
                return;
            }
            // structured output unsupported or rejected: fall back to a plain compression snippet
            final var snippet = compressExcerpt(redactedExcerpt, settings);
            if (snippet != null && !snippet.isBlank()) {
                memoryStore.appendText(false, snippet, workdir);
            }
            // NOTE: do NOT resetFailures() here. A provider that persistently lacks structured output
            // returns structured=false on every pass; resetting the consecutive-failure counter would
            // re-emit the 'rejected by the provider' WARN on every pass (the counter is reset by a
            // genuine structured success at the top of this method, clearing the streak so a real
            // recovery re-arms the notice). Keeping the counter non-zero makes notifyFailure's
            // `failures == 1` gate warn exactly once per consecutive non-structured window.
            extractedMessages = messages;
        } catch (final IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                notifyFailure("memory extraction (structured facts) failed: " + e.getMessage(), e);
                return;
            }
            // the structured call failed on the wire (e.g. a gateway that streams unconditionally and
            // drops the flag, or a transient HTTP failure): save memory via the plain snippet fallback
            // instead of losing the turn, and surface a soft notice with the contextual detail
            try {
                final var snippet = compressExcerpt(redactedExcerpt, settings);
                if (snippet == null || snippet.isBlank()) {
                    notifyFailure("memory extraction (structured facts) failed: " + e.getMessage(), e);
                } else {
                    memoryStore.appendText(false, snippet, workdir);
                    // NOTE: like the `structured=false` path above, a wire-failure salvage that persists
                    // memory is a steady-state degraded condition: do NOT resetFailures() here, or the
                    // 'rejected by the provider' WARN re-fires on every pass for a persistently-throwing
                    // backend. Only a genuine structured success (top of the method) clears the streak.
                    notifyFailure(
                            "memory extraction (structured facts) failed: " + e.getMessage()
                                    + "; saving a plain snippet instead: " + clamp(snippet, 200),
                            e);
                }
            } catch (final IOException | InterruptedException fallbackFailure) {
                if (fallbackFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                notifyFailure(
                        "memory extraction (structured facts) failed: " + e.getMessage()
                                + " (fallback snippet also failed: " + fallbackFailure.getMessage() + ")",
                        e);
            }
            // the watermark is NOT advanced on failure: the delta stays pending for the next
            // pass (periodic or forced at close), so a transient failure never loses facts
        }
    }

    /**
     * The not-yet-extracted suffix of a stable snapshot. Changed history is
     * reprocessed rather than silently skipped; an older prefix of an already
     * extracted snapshot is a no-op. Called only while holding extractionLock.
     */
    private List<Message> delta(final List<Message> messages) {
        final var sharedSize = Math.min(extractedMessages.size(), messages.size());
        if (!extractedMessages.subList(0, sharedSize).equals(messages.subList(0, sharedSize))) {
            // Pruned or replaced history: prefer harmless re-processing over losing facts.
            return messages;
        }
        // An older queued snapshot can run after a newer forced pass; it is already covered.
        return messages.subList(sharedSize, messages.size());
    }

    private static long deltaChars(final List<Message> delta) {
        var chars = 0L;
        for (final var m : delta) {
            if (isMemoryWorthy(m)) {
                chars += String.valueOf(m.content()).length();
            }
        }
        return chars;
    }

    /**
     * Builds the model excerpt from the delta: only user and assistant text
     * messages, rendered chronological oldest-first, capped at
     * {@link #MAX_MESSAGES} messages then {@link #MAX_EXCERPT_CHARS} chars.
     * When the char cap binds, assistant messages are dropped oldest-first
     * before any user message is dropped: the user's own words are the
     * durable signal and are never sacrificed while an assistant message can
     * still be shed. A single over-long user message is truncated to fit (the
     * tail is kept: the most recent part of a paste is its content).
     */
    static String buildExcerpt(final List<Message> delta) {
        // 1. keep the newest MAX_MESSAGES worthy messages, oldest-first order for rendering
        final var worthy = new ArrayList<Message>();
        for (var i = delta.size() - 1; i >= 0 && worthy.size() < MAX_MESSAGES; i--) {
            final var m = delta.get(i);
            if (isMemoryWorthy(m)) {
                worthy.add(m);
            }
        }
        Collections.reverse(worthy);
        if (worthy.isEmpty()) {
            return "";
        }

        // 2. enforce the char cap role-aware: drop assistant messages oldest-first
        //    before any user message, drop user messages oldest-first only when all
        //    assistants are gone. Always keep the newest message (truncated to fit
        //    as a last resort, tail kept: the most recent part of a paste is its content).
        var chars = 0L;
        for (final var m : worthy) {
            chars += contentLen(m);
        }
        if (chars > MAX_EXCERPT_CHARS) {
            final var keep = new ArrayList<>(worthy);
            // drop assistant messages oldest-first
            for (var i = 0; i < keep.size() && chars > MAX_EXCERPT_CHARS; ) {
                if ("assistant".equals(keep.get(i).role())) {
                    chars -= contentLen(keep.remove(i));
                } else {
                    i++;
                }
            }
            // still over: drop user messages oldest-first, never the newest one
            while (chars > MAX_EXCERPT_CHARS && keep.size() > 1) {
                var dropped = -1;
                for (var i = 0; i < keep.size() - 1; i++) {
                    if ("user".equals(keep.get(i).role())) {
                        dropped = i;
                        break;
                    }
                }
                if (dropped < 0) {
                    break; // only the newest (user) message remains: truncate below
                }
                chars -= contentLen(keep.remove(dropped));
            }
            worthy.clear();
            worthy.addAll(keep);
        }
        // last resort: a single over-long message is truncated to fit (tail kept)
        if (chars > MAX_EXCERPT_CHARS && worthy.size() == 1) {
            final var text = String.valueOf(worthy.get(0).content());
            final var truncated = text.substring(Math.max(0, text.length() - MAX_EXCERPT_CHARS));
            return "[" + worthy.get(0).role() + "] " + truncated + '\n';
        }
        return render(worthy);
    }

    private static long contentLen(final Message m) {
        return String.valueOf(m.content()).length();
    }

    private static String render(final List<Message> messages) {
        final var sb = new StringBuilder();
        for (final var m : messages) {
            sb.append('[').append(m.role()).append("] ").append(m.content()).append('\n');
        }
        return sb.toString();
    }

    private void notifyFailure(final String message) {
        notifyFailure(message, null);
    }

    private void notifyFailure(final String message, final Throwable cause) {
        final int failures = consecutiveFailures.incrementAndGet();
        if (failures == 1 && emitter != null) {
            emitter.emit(new AgentEvent.Notice(
                    AgentEvent.Notice.Level.WARN, message, null, sessionId, null, null, cause, workdir));
        }
    }

    private static String clamp(final String text, final int max) {
        return text == null || text.length() <= max ? text == null ? "" : text : text.substring(0, max) + "...";
    }

    private void resetFailures() {
        consecutiveFailures.set(0);
    }

    public static Map<String, Object> factsSchema() {
        final var itemProperties = Map.<String, Object>of(
                "text", Map.of("type", "string"),
                "scope", Map.of("type", "string", "enum", List.of("global", "project")),
                "p",
                        Map.of(
                                "type",
                                "integer",
                                "description",
                                "Importance weight 1..5 (5 = explicit keep directive, default 2)."),
                "path", Map.of("type", "string", "description", "Relative code path/package the fact is anchored to."),
                "topic", Map.of("type", "string", "description", "Space-separated topic keywords for retrieval."));
        final var item = Map.of(
                "type",
                "object",
                "properties",
                itemProperties,
                "required",
                List.of("text", "scope"),
                "additionalProperties",
                false);
        // no additionalProperties on the array wrapper: it is invalid JSON Schema there (the keyword
        // only applies to objects) and Anthropic structured outputs reject the schema with a 400
        final var facts = Map.of("type", "array", "items", item);
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of("facts", facts),
                "required",
                List.of("facts"),
                "additionalProperties",
                false);
    }

    private record Fact(String text, String scope, int p, String path, List<String> topic) {}

    private List<Fact> parseFacts(final String content) {
        try {
            final var root = mapper == null ? parseFactsMinimal(content) : mapper.fromString(Object.class, content);
            if (!(root instanceof Map<?, ?> map) || !(map.get("facts") instanceof List<?> facts)) {
                return List.of();
            }
            final var out = new ArrayList<Fact>();
            for (final var raw : facts) {
                if (raw instanceof Map<?, ?> f) {
                    final var text = String.valueOf(f.get("text"));
                    final var scope = "global".equals(f.get("scope")) ? "global" : "project";
                    if (text == null || text.equals("null") || text.isBlank()) {
                        continue;
                    }
                    int p = 2;
                    final var rawP = f.get("p");
                    if (rawP instanceof Number n) {
                        final var v = n.intValue();
                        if (v >= 1 && v <= 5) {
                            p = v;
                        }
                    } else if (rawP != null) {
                        try {
                            final var v = Integer.parseInt(rawP.toString().strip());
                            if (v >= 1 && v <= 5) {
                                p = v;
                            }
                        } catch (final NumberFormatException ignored) {
                            // keep default
                        }
                    }
                    final var path = f.get("path") instanceof String s && !s.isBlank() ? s.strip() : null;
                    final var topic = topicTokens(f.get("topic"));
                    out.add(new Fact(text.strip(), scope, p, path, topic));
                }
            }
            return out;
        } catch (final RuntimeException e) {
            LOGGER.log(Level.FINEST, "Unparsable structured memory facts: " + content, e);
            return List.of();
        }
    }

    private static List<String> topicTokens(final Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            return List.of();
        }
        final var parts = WHITESPACE.split(s.trim());
        return parts.length == 0 ? List.of() : List.of(parts);
    }

    /**
     * Minimal {@code {"facts":[{"text":"...","scope":"...","p":N,"path":"...","topic":"..."}]} parser used when
     * no {@link JsonMapper} is wired (unit tests, defensive): enough for the small, model-produced facts payload.
     * Falls back to an empty map when the shape is unexpected. {@code p}/{@code path}/{@code topic} are optional.
     */
    private static Object parseFactsMinimal(final String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }
        final var facts = new ArrayList<Map<String, String>>();
        final var matcher = MINIMAL_FACT.matcher(content);
        while (matcher.find()) {
            final var m = new LinkedHashMap<String, String>();
            m.put("text", matcher.group(1).replace("\\\"", "\""));
            m.put("scope", matcher.group(2));
            if (matcher.group(3) != null) {
                m.put("p", matcher.group(3));
            }
            if (matcher.group(4) != null) {
                m.put("path", matcher.group(4).replace("\\\"", "\""));
            }
            if (matcher.group(5) != null) {
                m.put("topic", matcher.group(5).replace("\\\"", "\""));
            }
            facts.add(m);
        }
        return Map.of("facts", facts);
    }

    /**
     * Fallback when the provider does not support structured output: ask a plain completion to
     * compress the excerpt into a concise, standalone memory snippet and return it as raw text.
     * Returns {@code null} when the compression call fails.
     */
    private String compressExcerpt(final String excerpt, final ChatSettings settings)
            throws IOException, InterruptedException {
        final var memoryModel = memoryModel(settings);
        final var res = chatClient.chat(new ChatCompletionRequest(
                        memoryModel == null || memoryModel.isBlank() ? null : memoryModel,
                        List.of(
                                Message.system(
                                        "Compress the following conversation excerpt into a concise, standalone memory snippet "
                                                + "capturing durable facts, decisions and user preferences. Keep long-lived knowledge only, "
                                                + "drop transient task state. Reply with only the snippet text, a few short sentences, no preamble."),
                                Message.user(excerpt)),
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null)
                .withSessionId(sessionId)
                .withSettings(settings));
        if (usageTracker != null) {
            usageTracker.recordAuxiliary(res.usage());
        }
        final var content = res.message() == null || res.message().content() == null
                ? ""
                : String.valueOf(res.message().content());
        final var snippet = content.strip();
        if (snippet.isEmpty()) {
            notifyFailure("memory extraction (fallback snippet) produced no output; memory left untouched");
        }
        return snippet.isEmpty() ? null : snippet;
    }

    /**
     * The routable memory model for the session: the effective provider's
     * {@code backgroundModelOverrides.memory} (blank = its own default model, "inherit" = the
     * global chain), then the global {@code yca.model.memory} chain (memory falls back to
     * compact), then null (the request settings resolve the model).
     */
    private String memoryModel(final ChatSettings settings) {
        return providerManager == null
                ? (configuration.model() == null ? null : configuration.model().resolvedMemory())
                : providerManager.resolvedMemory(settings);
    }
}
