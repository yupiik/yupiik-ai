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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.configuration.SecretRedactionMode;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Default redaction observer: implements all three boundary events and the direct {@code redact()}/
 * {@code restore()} text API. In {@code REDACT} mode it replaces harvested/pattern/matcher-detected
 * secrets with format-preserving fakes and records the fake->real mapping in the provenance
 * {@link SecretVault}; in {@code FAIL} mode it aborts the outbound call instead; in
 * {@code PASSTHROUGH} mode it is a no-op. Inbound it restores only registry-tracked fakes (in the
 * assistant text and in tool args before execution).
 *
 * <p>Scanning is efficient: harvested exact values are substring-matched (longest first, one pass per
 * value); token-shaped secrets come from the injected {@link SecretMatcher}s applied to contiguous
 * word tokens; PEM blocks are handled separately (they span lines); custom {@code patterns} run as
 * substring regexes. Everything runs once per message, never per-word regex over the environment.</p>
 *
 * <p>Whatever the detection source (harvested exact value or inline token), every value is rendered
 * through the same replacement chain: the highest-priority {@link SecretMatcher} that recognizes it
 * produces the shape-preserving fake (prefix kept, body regenerated), falling back to the shape-blind
 * regenerator only for values no matcher claims. A value therefore looks the same inline or harvested.</p>
 */
@ApplicationScoped
public class SecretRedactor {

    /** Splits text into contiguous candidate segments for the value matchers. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=:@-]{4,}");

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "(-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY(?: BLOCK)?-----)([\\s\\S]*?)(-----END (?:[A-Z ]+ )?PRIVATE KEY(?: BLOCK)?-----)");

    private final SecretRegistry registry;
    private final SecretVault vault;
    private final SecretSessionModes sessionModes;

    public SecretRedactor(final SecretRegistry registry, final SecretSessionModes sessionModes) {
        this.registry = registry;
        this.vault = registry == null ? null : registry.vault();
        this.sessionModes = sessionModes;
    }

    protected SecretRedactor() {
        this.registry = null;
        this.vault = null;
        this.sessionModes = null;
    }

    /* ---------------- outbound ---------------- */

    public void onOutbound(@OnEvent @Order(100) final OutboundLlmRequest event) {
        final var mode = modeFor(event.sessionId());
        if (mode == null || mode == SecretRedactionMode.PASSTHROUGH) {
            return; // disabled or per-session passthrough: send raw
        }
        final var messages = event.messages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        final var redacted = new ArrayList<Message>(messages.size());
        var matched = 0;
        for (final var msg : messages) {
            if (redactMessage(msg, event.sessionId(), redacted)) {
                matched++;
            }
        }
        if (mode == SecretRedactionMode.FAIL && matched > 0) {
            throw new SecretRedactionException(matched);
        }
        if (matched > 0) {
            event.messages(redacted);
        }
    }

    private boolean redactMessage(final Message msg, final String sessionId, final List<Message> out) {
        Object content = msg.content();
        if (content instanceof String s) {
            final var fixed = redact(s, sessionId);
            if (!fixed.equals(s)) {
                content = fixed;
            }
        }
        List<ToolCall> calls = msg.toolCalls();
        if (calls != null && !calls.isEmpty()) {
            final var nc = new ArrayList<ToolCall>(calls.size());
            boolean changedCall = false;
            for (final var call : calls) {
                var fn = call.function();
                if (fn != null && fn.arguments() != null) {
                    final var fixed = redact(fn.arguments(), sessionId);
                    if (!fixed.equals(fn.arguments())) {
                        fn = new ToolCall.FunctionCall(fn.name(), fixed);
                        changedCall = true;
                    }
                }
                nc.add(fn == call.function() ? call : new ToolCall(call.id(), call.type(), fn, call.index()));
            }
            if (changedCall) {
                calls = nc;
            }
        }
        final var changed = !content.equals(msg.content()) || calls != msg.toolCalls();
        if (changed) {
            out.add(new Message(msg.role(), content, msg.name(), calls, msg.toolCallId(), msg.others()));
        } else {
            out.add(msg);
        }
        return changed;
    }

    /* ---------------- inbound (assistant text) ---------------- */

    public void onInbound(@OnEvent @Order(100) final InboundLlmResponse event) {
        event.content().setLength(0);
        event.content().append(restore(event.assistantText(), event.sessionId()));
    }

    /* ---------------- inbound (tool args before execution) ---------------- */

    public void onToolPrepare(@OnEvent @Order(100) final ToolExecutionPrepare event) {
        restoreInPlace(event.args(), event.sessionId());
    }

    /* ---------------- public text API ---------------- */

    /**
     * Redacts {@code text} for the given session (REDACT/FAIL aware). Used directly by auxiliary
     * paths (memory extraction, system-prompt injection, doc-enricher cache) that are not full LLM
     * request boundaries.
     */
    public String redact(final String text, final String sessionId) {
        final var mode = modeFor(sessionId);
        if (text == null || text.isBlank() || mode == null || mode == SecretRedactionMode.PASSTHROUGH) {
            return text;
        }
        String out = redactHarvested(text, sessionId);
        out = redactCustomPatterns(out, sessionId);
        out = redactPem(out, sessionId);
        out = redactTokens(out, sessionId);
        return out;
    }

    /** Restores only provenance-registry fakes found in {@code text}. */
    public String restore(final String text, final String sessionId) {
        if (text == null || text.isBlank() || vault == null) {
            return text;
        }
        // replace every recorded fake by its real value, longest fake first so a longer fake that
        // shares a prefix is restored before a shorter overlapping one is processed. This also
        // matches fakes embedded as `key=value` / inside URIs, which the tokenizer would have split.
        String out = text;
        final var acc = new ArrayDeque<String[]>();
        vault.forEachFake(sessionId, (fake, real) -> acc.add(new String[] {fake, real}));
        final List<String[]> fakes = new ArrayList<>(acc);
        fakes.sort(Comparator.comparingInt((String[] e) -> e[0].length()).reversed());
        for (final var e : fakes) {
            final var fake = e[0];
            final var real = e[1];
            int idx = out.indexOf(fake);
            while (idx >= 0) {
                out = out.substring(0, idx) + real + out.substring(idx + fake.length());
                idx = out.indexOf(fake, idx + real.length());
            }
        }
        return out;
    }

    public boolean enabled() {
        final var mode = mode();
        return mode != null && mode != SecretRedactionMode.PASSTHROUGH;
    }

    public SecretRedactionMode mode() {
        return registry == null || registry.config() == null
                ? null
                : registry.config().mode();
    }

    /** Effective mode for a session: per-session override first, then the global config default. */
    public SecretRedactionMode modeFor(final String sessionId) {
        if (sessionModes != null) {
            final var perSession = sessionModes.of(sessionId);
            if (perSession != null) {
                return perSession;
            }
        }
        return mode();
    }

    public SecretRegistry registry() {
        return registry;
    }

    /* ---------------- internals ---------------- */

    private String redactHarvested(final String text, final String sessionId) {
        final var values = registry.harvestedValues();
        if (values.isEmpty()) {
            return text;
        }
        String out = text;
        for (final var value : values) {
            if (value == null
                    || value.isBlank()
                    || value.length()
                            < (registry.config() == null ? 8 : registry.config().minValueLength())) {
                continue;
            }
            if (registry.inExclude(value)) {
                continue;
            }
            int idx = out.indexOf(value);
            while (idx >= 0) {
                final var fake = replacementFor(sessionId, value);
                out = out.substring(0, idx) + fake + out.substring(idx + value.length());
                idx = out.indexOf(fake, idx + fake.length());
            }
        }
        return out;
    }

    private String redactCustomPatterns(final String text, final String sessionId) {
        final var patterns = registry.customPatterns();
        if (patterns.isEmpty()) {
            return text;
        }
        String out = text;
        for (final var pattern : patterns) {
            try {
                final var m = pattern.matcher(out);
                final var res = new StringBuilder();
                int last = 0;
                while (m.find()) {
                    final var matched = m.group();
                    if (registry.inExclude(matched)) {
                        res.append(out, last, m.end());
                    } else {
                        res.append(out, last, m.start()).append(ensureFake(sessionId, matched));
                    }
                    last = m.end();
                }
                res.append(out, last, out.length());
                out = res.toString();
            } catch (final RuntimeException ignored) {
                // Keep a failed custom pattern from aborting the remaining redaction passes.
            }
        }
        return out;
    }

    /** Replaces whole PEM private-key blocks (multi-line) regardless of token boundaries. */
    private String redactPem(final String text, final String sessionId) {
        if (!PEM_BLOCK.matcher(text).find()) {
            return text;
        }
        final var m = PEM_BLOCK.matcher(text);
        final var res = new StringBuilder();
        int last = 0;
        while (m.find()) {
            res.append(text, last, m.start());
            final var block = m.group();
            final var fake = ensureFake(sessionId, block, PemKeySecretMatcher::fakeBlock);
            res.append(fake);
            last = m.end();
        }
        res.append(text, last, text.length());
        return res.toString();
    }

    /** Applies the value matchers to contiguous candidate tokens. */
    private String redactTokens(final String text, final String sessionId) {
        if (registry.matchers().isEmpty()) {
            return text;
        }
        String out = text;
        for (final var matcher : registry.matchers()) {
            final var m = TOKEN.matcher(out);
            final var res = new StringBuilder();
            int last = 0;
            while (m.find()) {
                final var token = m.group();
                if (!registry.inExclude(token) && matcher.matches(token)) {
                    res.append(out, last, m.start()).append(ensureFake(sessionId, token, matcher::replace));
                } else {
                    res.append(out, last, m.end());
                }
                last = m.end();
            }
            res.append(out, last, out.length());
            out = res.toString();
        }
        return out;
    }

    /**
     * Resolves the fake for a candidate value regardless of where it was detected (harvested exact
     * value, inline token, ...): the highest-priority ({@link SecretMatcher#order()}) matcher that
     * {@link SecretMatcher#matches matches} wins and produces a shape-preserving fake (prefix kept,
     * body regenerated); values no matcher recognizes fall back to the shape-blind
     * {@link #fakeOf(String)} regenerator. Unifying the source here guarantees a value is disguised
     * identically whether it was harvested or seen inline.
     */
    private String replacementFor(final String sessionId, final String value) {
        for (final var matcher : registry.matchers()) {
            if (matcher.matches(value)) {
                return ensureFake(sessionId, value, matcher::replace);
            }
        }
        return ensureFake(sessionId, value);
    }

    /** Apply the provenance-safe replacement for {@code real}; records the mapping. */
    private String ensureFake(final String sessionId, final String real) {
        if (vault == null) {
            return fakeOf(real);
        }
        if (isEmittedFake(sessionId, real)) {
            return real; // already a placeholder we emitted; never re-randomize it
        }
        final var existing = vault.fakeOf(sessionId, real);
        if (existing != null) {
            return existing;
        }
        // random fakes make collisions negligible, but if one ever collides with a DIFFERENT real
        // already bound we must roll another so the restore side never resolves the wrong value
        String fake;
        do {
            fake = fakeOf(real);
        } while (isTaken(sessionId, fake, real));
        vault.record(sessionId, fake, real);
        return fake;
    }

    private String ensureFake(final String sessionId, final String real, final Function<String, String> replacement) {
        if (vault == null) {
            return replacement.apply(real);
        }
        if (isEmittedFake(sessionId, real)) {
            return real; // already a placeholder we emitted; never re-randomize it
        }
        final var existing = vault.fakeOf(sessionId, real);
        if (existing != null) {
            return existing;
        }
        String fake;
        do {
            fake = replacement.apply(real);
        } while (isTaken(sessionId, fake, real));
        vault.record(sessionId, fake, real);
        return fake;
    }

    /**
     * True when {@code value} is already a fake this redactor emitted in the session. With
     * prefix-preserving fakes, a later pass (e.g. token scanning) can see a placeholder and mistake it
     * for a fresh real secret; this guard stops re-randomizing our own output so it keeps round-tripping.
     */
    private boolean isEmittedFake(final String sessionId, final String value) {
        return vault.realOf(sessionId, value) != null;
    }

    /** True when {@code fake} is already bound to a different real value (a collision to avoid). */
    private boolean isTaken(final String sessionId, final String fake, final String real) {
        if (fake == null) {
            return false;
        }
        final var bound = vault.realOf(sessionId, fake);
        return bound != null && !bound.equals(real);
    }

    /** Length-preserving random placeholder for a harvested/matched exact value (unique per value). */
    private static String fakeOf(final String real) {
        return ShapePreservingSecretMatcher.regenerate(real);
    }

    /** Deep in-place restore of fakes inside a tool-args map (mutating mutable maps only). */
    private void restoreInPlace(final Map<String, Object> args, final String sessionId) {
        if (args == null) {
            return;
        }
        for (final var e : args.entrySet()) {
            final var value = e.getValue();
            if (value instanceof String s) {
                final var r = restore(s, sessionId);
                if (!r.equals(s)) {
                    try {
                        e.setValue(r);
                    } catch (final UnsupportedOperationException ignored) {
                        // immutable arg map (defensive): skip; the restore is best-effort
                    }
                }
            } else if (value instanceof Map<?, ?>) {
                restoreInPlace(uncheckedMap(value), sessionId);
            } else if (value instanceof List<?> list) {
                for (final var item : list) {
                    if (item instanceof Map<?, ?>) {
                        restoreInPlace(uncheckedMap(item), sessionId);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uncheckedMap(final Object value) {
        return (Map<String, Object>) value;
    }

    /* package-private for tests */
    static String tokenPattern() {
        return TOKEN.pattern();
    }
}
