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
package io.yupiik.ia.agent.core.permissions;

import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.BashPermissionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandOptionsConfiguration;
import io.yupiik.ia.agent.core.configuration.CommandPermissionConfiguration;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles the {@code yca.permissions.bash.rules} configuration into fast matchers and evaluates
 * bash commands against them.
 *
 * <p>The command is split into segments on {@code &&}, {@code ||}, {@code ;}, {@code |}, single {@code &}
 * and newlines with a quote aware tokenizer (line continuations and pipelines spanning several lines are
 * supported). Every segment must match a rule resolving to {@code all}/{@code yolo} for the active agent
 * mode for the whole command to be auto-allowed; redirections, backticks and excluded options
 * (eg. {@code sed -i}) make the segment fall back to the normal ask/gate flow.</p>
 *
 * <p>Command substitutions {@code $( ... )} are verified recursively instead of being blanket-denied:
 * the substituted command is evaluated with the same engine, so a read-only substitution
 * (eg. {@code $(find . -name '*.java')}) is auto-allowed - glued into surrounding text
 * (eg. {@code echo "file: $(ls)!"}) the same as standalone - while any side effect gates the whole command.
 * The substitution gate evaluates the inner command against the <em>effective</em> rules: the built-in
 * read-only families ({@link CommandPermissionConfiguration#defaultRules()}) union the rules compiled into
 * this instance (the configured {@code yca.permissions.bash.rules} plus any session/override rules), so a
 * custom rule family the user added - even with a higher privilege - is honored inside {@code $( ... )}
 * exactly like at the top level, and the read-only baseline stays available whatever the configuration.</p>
 *
 * <p>Shell control keywords ({@code for}, {@code if}, {@code while}, {@code do}, {@code done}...) are syntax,
 * not commands: they are skipped so {@code for f in $(find .); do echo $f; done} works, but every real
 * command in a loop body, condition or branch is still evaluated segment by segment.</p>
 */
public final class BashRules {

    /**
     * Result of a command evaluation: whether the command is auto-allowed, its arity-style prefix and,
     * when denied, the first segment that was not auto-allowed plus {@link Cause why}.
     */
    public record Evaluation(boolean allowed, String prefix, String deniedSegment, Cause deniedCause) {}

    /** Why a bash segment was not auto-allowed by the rules, used to explain approval prompts and denials. */
    public enum Cause {
        /** No configured rule matched the segment (eg. {@code git add}). */
        NO_MATCH,
        /** A rule matched but resolves to interactive approval (ask/auto_edit) in the active mode. */
        NOT_YOLO,
        /** The segment carries a redirection, a backtick, or a $() group that could not be verified read-only. */
        REDIRECT_OR_SUBSTITUTION
    }

    private BashRules() {
        // no-op
    }

    public static Compiled compile(final BashPermissionsConfiguration configuration) {
        return compile(configuration == null ? null : configuration.rules());
    }

    /**
     * Compiles a raw rule list (configured rules, session overrides, or a mix) into a {@link Compiled}
     * evaluator. Built-in read-only families are not injected here: they are only added by
     * {@link Compiled}'s substitution verifier, so the top-level gate reflects exactly the rules passed.
     */
    public static Compiled compile(final List<CommandPermissionConfiguration> rules) {
        return new Compiled(rules == null || rules.isEmpty() ? List.of() : Compiled.rawCompile(rules));
    }

    private static Excludes compileExcludes(final List<CommandOptionsConfiguration> excludeOptions) {
        final var exact = new HashSet<String>();
        final var longOptions = new ArrayList<String>();
        final var sequences = new ArrayList<List<String>>();
        if (excludeOptions != null) {
            for (final CommandOptionsConfiguration unit : excludeOptions) {
                if (unit == null) {
                    continue;
                }
                final var options = unit.normalizedOptions();
                if (options.isEmpty()) {
                    continue;
                }
                if (options.size() == 1) {
                    final var option = options.get(0);
                    if (option.startsWith("--")) {
                        longOptions.add(option);
                    } else {
                        exact.add(option);
                    }
                } else {
                    sequences.add(options);
                }
            }
        }
        return new Excludes(exact, longOptions, sequences);
    }

    public static final class Compiled {
        private static final Set<String> TOLERATED_REDIRECTS = Set.of("2>/dev/null", ">/dev/null", "2>&1");
        // shell control keywords are syntax markers, not commands: they never execute anything by themselves
        private static final Set<String> STRUCTURAL_HEAD =
                Set.of("for", "while", "until", "if", "then", "else", "elif", "do", "done", "fi", "break", "continue");
        // command substitution placeholders introduced by mask(command)
        private static final String SUBSTITUTION_PREFIX = "YCA_SUB_";
        // guard against pathological nested $($($(...))) commands
        private static final int MAX_SUBSTITUTION_DEPTH = 32;

        private final List<CompiledRule> rules;
        /**
         * Evaluator used to verify the inner command of every {@code $( ... )} substitution: the
         * built-in read-only families unioned with this instance's rules. Built per instance (no
         * static compilation cache) so the substitution gate follows whatever effective rules were
         * compiled here (configured + session overrides) on top of the read-only baseline.
         */
        private final Compiled substitutionRules;

        /**
         * The built-in read-only families as compiled rules. This is a <em>config</em> cache (the default
         * rule list never changes), not a compilation cache: the per-instance union is what gets compiled.
         */
        private static final List<CompiledRule> DEFAULT_RULE_LIST = defaultRuleList();

        private static List<CompiledRule> defaultRuleList() {
            // built through a plain instance so the default config itself has no substitution re-union
            return new Compiled(rawCompile(CommandPermissionConfiguration.defaultRules()), null).rules;
        }

        private Compiled(final List<CompiledRule> rules) {
            this(rules, unionWithDefaults(rules));
        }

        /**
         * Plain constructor used for the union instance (and the internal default instance): the rules
         * already include the read-only baseline, so its own substitution verifier is itself.
         */
        private Compiled(final List<CompiledRule> rules, final Compiled substitutionRules) {
            this.rules = rules;
            this.substitutionRules = substitutionRules == null ? this : substitutionRules;
        }

        /** Compiles {@code CommandPermissionConfiguration}s into {@code CompiledRule}s without building a {@code Compiled}. */
        private static List<CompiledRule> rawCompile(final List<CommandPermissionConfiguration> configuration) {
            final var compiledRules = new ArrayList<CompiledRule>();
            if (configuration != null) {
                for (final CommandPermissionConfiguration rule : configuration) {
                    if (rule == null || rule.matcher() == null || rule.matcher().commandStart() == null) {
                        continue;
                    }
                    final var commandStart = rule.matcher().commandStart().normalizedOptions();
                    if (commandStart.isEmpty()) {
                        continue;
                    }
                    compiledRules.add(new CompiledRule(
                            commandStart,
                            compileExcludes(rule.matcher().excludeOptions()),
                            rule.plan(),
                            rule.defaultMode()));
                }
            }
            return List.copyOf(compiledRules);
        }

        /**
         * Compiles the substitution verifier for the given rules: {@code defaultRules ∪ rules}, deduplicated
         * (a configuration already using the defaults must not duplicate them). The union instance is built
         * with the plain constructor so it never re-unions itself.
         */
        private static Compiled unionWithDefaults(final List<CompiledRule> rules) {
            final var defaults = DEFAULT_RULE_LIST;
            if (rules.isEmpty()) {
                return new Compiled(defaults, null);
            }
            final var merged = new ArrayList<CompiledRule>(defaults.size() + rules.size());
            merged.addAll(defaults);
            for (final var rule : rules) {
                if (!merged.contains(rule)) {
                    merged.add(rule);
                }
            }
            return new Compiled(List.copyOf(merged), null);
        }

        /**
         * @param agentMode the active agent mode, selecting the {@code plan} or {@code default} value of each rule.
         * @param command the raw bash command.
         * @return whether every segment of the command is auto-allowed by a rule resolving to {@code all}/{@code yolo},
         * plus the arity-style command prefix (matched rule {@code commandStart}) used to key session grants.
         */
        public Evaluation evaluate(final AgentMode agentMode, final String command) {
            return evaluateDeep(agentMode, command, 0);
        }

        private Evaluation evaluateDeep(final AgentMode agentMode, final String command, final int depth) {
            if (command == null || command.isBlank()) {
                return new Evaluation(true, null, null, null);
            }
            if (depth >= MAX_SUBSTITUTION_DEPTH) {
                return new Evaluation(false, null, command, Cause.REDIRECT_OR_SUBSTITUTION);
            }
            final var masked = mask(command);
            final var segments = tokenize(masked.text());
            var allowed = true;
            var matchedSegments = 0;
            var matchedStart = (String) null;
            String deniedSegment = null;
            Cause deniedCause = null;
            for (final var segment : segments) {
                final var decision = evaluateSegment(segment, agentMode, masked, depth);
                if (!decision.allowed()) {
                    allowed = false;
                    if (deniedSegment == null) {
                        deniedSegment = renderSegment(segment, masked);
                        deniedCause = decision.cause();
                    }
                }
                if (decision.matchedStart() != null) {
                    matchedSegments++;
                    if (matchedStart == null) {
                        matchedStart = decision.matchedStart();
                    }
                }
            }
            // the fine grained prefix is only meaningful for plain single segment commands: a compound
            // command approved once must not silently unlock its other segments
            return new Evaluation(
                    allowed,
                    segments.size() == 1 && matchedSegments == 1 ? matchedStart : null,
                    deniedSegment,
                    deniedCause);
        }

        /**
         * Normalized text of a segment for reporting and allow/deny pattern matching: tolerated
         * {@code /dev/null} redirects, shell control keywords ({@code do}, {@code done}, {@code if},
         * {@code fi}, ...) and leading env assignments are syntax/plumbing, not command material - so
         * a loop body {@code do rm -rf $j 2>/dev/null} is reported and matched as {@code rm -rf $j}
         * and a deny entry like {@code bash:rm *} cannot be bypassed by a preceding {@code do}. The
         * substitution placeholders are expanded back (eg. {@code YCA_SUB_0} -> {@code $(...)}).
         */
        private static String renderSegment(final Segment segment, final MaskedCommand masked) {
            var tokens = withoutToleratedRedirects(segment.tokens());
            final var structural = structuralPrefixLength(tokens);
            if (structural > 0) {
                tokens = new ArrayList<>(tokens.subList(structural, tokens.size()));
            }
            var i = 0;
            while (i < tokens.size() && isEnvAssignment(tokens.get(i).text())) {
                i++;
            }
            final var sb = new StringBuilder();
            for (; i < tokens.size(); i++) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(expand(tokens.get(i).text(), masked));
            }
            return sb.toString();
        }

        /**
         * Command segments (quote aware, operators stripped) as normalized text, for allow/deny pattern
         * matching: tolerated redirects, control keywords and env assignments are stripped the same way
         * as during evaluation, so deny/allow entries match the real command of each segment.
         */
        public List<String> segments(final String command) {
            if (command == null || command.isBlank()) {
                return List.of();
            }
            final var masked = mask(command);
            final var segments = new ArrayList<String>();
            for (final var segment : tokenize(masked.text())) {
                final var text = renderSegment(segment, masked);
                if (!text.isEmpty()) {
                    segments.add(text);
                }
            }
            return segments;
        }

        private static List<Token> withoutToleratedRedirects(final List<Token> tokens) {
            final var effective = new ArrayList<Token>(tokens.size());
            for (final var token : tokens) {
                if (!TOLERATED_REDIRECTS.contains(token.text())) {
                    effective.add(token);
                }
            }
            return effective;
        }

        /**
         * Exploration-coverage keys of a bash command for the plateau detector: one key per segment,
         * {@code <matched-rule-prefix>:<target>} where the prefix comes from the configured bash rules
         * ({@code yca.permissions.bash.rules} {@code commandStart}, so a rule added there - eg.
         * {@code jq} - is automatically covered instead of duplicating a hardcoded command list) and
         * the target is the last non-flag, non-redirected path-like token of the segment. Commands
         * with no matching rule, pure env assignments, {@code cd} and shell wrappers yield no key
         * (they are never plateau signals). The tokenizer (quote aware, operator segmentation) is the
         * same one used for permission evaluation. Note this reflects the rules compiled into this
         * instance: a session/override rule (eg. an approved family) also produces coverage keys, so
         * exploration commands approved for the session contribute to plateau detection like configured ones.
         */
        public List<String> coverageKeys(final String command) {
            if (command == null || command.isBlank()) {
                return List.of();
            }
            final var keys = new ArrayList<String>(1);
            for (final var segment : tokenize(command)) {
                final var effective = new ArrayList<Token>(segment.tokens().size());
                for (final var token : segment.tokens()) {
                    if (!TOLERATED_REDIRECTS.contains(token.text())) {
                        effective.add(token);
                    }
                }
                if (effective.isEmpty()) {
                    continue;
                }
                var i = 0;
                while (i < effective.size() && isEnvAssignment(effective.get(i).text())) {
                    i++;
                }
                if (i >= effective.size()) {
                    continue;
                }
                final var head = effective.get(i).text();
                if ("cd".equals(head)) {
                    continue;
                }
                final var tokens = new ArrayList<String>(effective.size() - i);
                var skipNext = false;
                for (var j = i; j < effective.size(); j++) {
                    final var token = effective.get(j);
                    if (token.redirect() || token.substitution()) {
                        skipNext = true; // the redirect operator AND its target are not coverage
                        continue;
                    }
                    if (skipNext) {
                        skipNext = false;
                        continue;
                    }
                    tokens.add(token.text());
                }
                if (tokens.isEmpty()) {
                    continue;
                }
                if (isShellCommand(tokens.get(0))) {
                    continue;
                }
                final var prefix = coveragePrefix(tokens);
                if (prefix == null) {
                    continue;
                }
                final var last = tokens.get(tokens.size() - 1);
                final var target = last.contains(":") ? last.substring(last.lastIndexOf(':') + 1) : last;
                keys.add(prefix + ":" + target);
            }
            return keys;
        }

        /**
         * Best matching configured rule prefix ({@code commandStart} joined) for the given command
         * tokens, or null when no rule matches (the command is not a recognized read-only family, so
         * it cannot be classified as an exploration target).
         */
        private String coveragePrefix(final List<String> tokens) {
            String best = null;
            var bestLength = 0;
            for (final var rule : rules) {
                if (!rule.matches(tokens)) {
                    continue;
                }
                if (best == null || rule.commandStart().size() > bestLength) {
                    best = String.join(" ", rule.commandStart());
                    bestLength = rule.commandStart().size();
                }
            }
            return best;
        }

        /**
         * Best matching configured rule prefix for a single normalized segment text
         * (eg. {@code docker run nginx}), or null when no rule matches. This is the
         * anchor used to key a session grant: when the global rules recognize the
         * command, the grant is keyed by the rule {@code commandStart}, not the first
         * word, so {@code docker run x} grants {@code docker run} - and consequently
         * any {@code docker run ...} command.
         */
        public String rulePrefixFor(final String segment) {
            if (segment == null || segment.isBlank()) {
                return null;
            }
            final var masked = mask(segment);
            final var texts = new ArrayList<String>();
            for (final var token : tokenize(masked.text())) {
                for (final var effective : withoutToleratedRedirects(token.tokens())) {
                    if (!effective.redirect() && !effective.substitution()) {
                        texts.add(effective.text());
                    }
                }
            }
            return texts.isEmpty() ? null : coveragePrefix(texts);
        }

        private record SegmentDecision(boolean allowed, String matchedStart, Cause cause) {}

        private SegmentDecision evaluateSegment(
                final Segment segment, final AgentMode agentMode, final MaskedCommand masked, final int depth) {
            var effective = new ArrayList<Token>(segment.tokens().size());
            for (final var token : segment.tokens()) {
                if (!TOLERATED_REDIRECTS.contains(token.text())) {
                    effective.add(token);
                }
            }
            // remaining redirections (>, <) and backticks are never auto-allowed; $() is masked to placeholders
            for (final var token : effective) {
                if (token.redirect() || token.substitution()) {
                    return new SegmentDecision(false, null, Cause.REDIRECT_OR_SUBSTITUTION);
                }
            }
            // control keywords are syntax, not commands: `for ... done` and `if ... fi` never execute the
            // keywords themselves, and the real commands of their bodies/conditions/branches are still
            // evaluated below segment by segment, so `for f in $(rm -rf /); do ...` stays gated
            final var structural = structuralPrefixLength(effective);
            if (structural > 0) {
                if (structural >= effective.size()) {
                    return new SegmentDecision(true, null, null); // keyword-only segment (done, fi, ...)
                }
                if ("for".equals(effective.get(0).text())) {
                    return evaluateForSegment(effective, structural, agentMode, masked, depth);
                }
                effective = new ArrayList<>(effective.subList(structural, effective.size()));
            }

            var i = 0;
            while (i < effective.size() && isEnvAssignment(effective.get(i).text())) {
                i++;
            }
            if (i >= effective.size()) {
                // pure env assignments: their value may embed a $() that must be verified too
                return verifyPlaceholders(effective, agentMode, masked, depth);
            }

            final var text = effective.get(i).text();
            final var decision = "cd".equals(text)
                    ? new SegmentDecision(true, null, null) // directory change has no repository side effect
                    : "xargs".equals(text)
                            ? evaluateXargs(effective, i, agentMode)
                            : evaluateCommandTokens(toTexts(effective, i), agentMode);
            if (!decision.allowed()) {
                return decision;
            }
            // a family match (or cd/xargs/env tolerance) is not enough on its own: every $() argument
            // must itself evaluate to a read-only command
            return verifyPlaceholders(effective, agentMode, masked, depth);
        }

        /**
         * {@code for NAME [in] LIST} body opener: NAME is a plain loop variable and {@code in} is syntax,
         * only the iteration LIST is command-line material - literal words, globs and verified $().
         */
        private SegmentDecision evaluateForSegment(
                final List<Token> tokens,
                final int start,
                final AgentMode agentMode,
                final MaskedCommand masked,
                final int depth) {
            var idx = start;
            if (idx < tokens.size()) {
                idx++; // loop variable name
            }
            if (idx < tokens.size() && "in".equals(tokens.get(idx).text())) {
                idx++;
            }
            return verifyPlaceholders(new ArrayList<>(tokens.subList(idx, tokens.size())), agentMode, masked, depth);
        }

        /**
         * Fails the segment unless every $() placeholder in the tokens evaluates to an allowed command.
         * Placeholders are verified recursively with the same engine whether they stand alone
         * ({@code echo $(ls)}) or are glued into surrounding text ({@code echo "a $(ls) b"}): shell
         * interpolation of a substitution output is identical in both shapes, so gluing adds no side
         * effect beyond the inner command itself - a glued {@code echo "a $(rm -rf /) b"} still recurses
         * into {@code rm} and stays gated. The inner command is evaluated against
         * {@link #substitutionRules}: the built-in read-only families union the rules compiled into this
         * instance (configured rules + session/override rules), so a custom rule family the user added is
         * honored inside {@code $( ... )} the same way as at the top level, while the read-only baseline
         * stays available whatever the configuration. Only unbalanced or out-of-range (malformed)
         * placeholders fall back to the gate, since they cannot be mapped to a verifiable inner command.
         */
        private SegmentDecision verifyPlaceholders(
                final List<Token> tokens, final AgentMode agentMode, final MaskedCommand masked, final int depth) {
            for (final var token : tokens) {
                final var ref = placeholderRef(token.text());
                if (ref.kind() == PlaceholderKind.NONE) {
                    continue;
                }
                for (final var index : tokenPlaceholderIndices(token.text(), masked)) {
                    if (index < 0) {
                        return new SegmentDecision(false, null, Cause.REDIRECT_OR_SUBSTITUTION);
                    }
                    final var inner = substitutionRules.evaluateDeep(
                            agentMode, masked.substitutions().get(index), depth + 1);
                    if (!inner.allowed()) {
                        return new SegmentDecision(
                                false,
                                null,
                                inner.deniedCause() != null ? inner.deniedCause() : Cause.REDIRECT_OR_SUBSTITUTION);
                    }
                }
            }
            return new SegmentDecision(true, null, null);
        }

        /**
         * The substitution indices referenced by a token, in scan order. A {@code PURE} placeholder is a
         * single index; a {@code GLUED} token may reference several (eg. {@code "x$(a)y$(b)z"}). Returns an
         * empty list for {@code NONE}, and throws-free fails closed on a malformed or out-of-range reference
         * by returning a single {@code -1}, which callers treat as unverifiable (gated).
         */
        private static List<Integer> tokenPlaceholderIndices(final String token, final MaskedCommand masked) {
            final var ref = placeholderRef(token);
            final int count = masked.substitutions().size();
            if (ref.kind() == PlaceholderKind.NONE) {
                return List.of();
            }
            if (ref.kind() == PlaceholderKind.PURE) {
                return ref.index() < 0 || ref.index() >= count ? List.of(-1) : List.of(ref.index());
            }
            final var indices = new ArrayList<Integer>(1);
            for (var i = 0; ; ) {
                final var at = token.indexOf(SUBSTITUTION_PREFIX, i);
                if (at < 0) {
                    break;
                }
                var end = at + SUBSTITUTION_PREFIX.length();
                while (end < token.length() && token.charAt(end) >= '0' && token.charAt(end) <= '9') {
                    end++;
                }
                if (end == at + SUBSTITUTION_PREFIX.length()) {
                    return List.of(-1); // dangling prefix, not a numeric placeholder: unverifiable
                }
                final var index = Integer.parseInt(token.substring(at + SUBSTITUTION_PREFIX.length(), end));
                if (index < 0 || index >= count) {
                    return List.of(-1); // out of range: unverifiable
                }
                indices.add(index);
                i = end;
            }
            return indices.isEmpty() ? List.of(-1) : indices;
        }

        private static int structuralPrefixLength(final List<Token> tokens) {
            var count = 0;
            while (count < tokens.size()
                    && STRUCTURAL_HEAD.contains(tokens.get(count).text())) {
                count++;
            }
            return count;
        }

        private static List<String> toTexts(final List<Token> tokens, final int from) {
            final var texts = new ArrayList<String>(tokens.size() - from);
            for (var i = from; i < tokens.size(); i++) {
                texts.add(tokens.get(i).text());
            }
            return texts;
        }

        private SegmentDecision evaluateCommandTokens(final List<String> tokens, final AgentMode agentMode) {

            var allowed = false;
            String bestStart = null;
            var bestLength = 0;

            for (final var rule : rules) {
                if (!rule.matches(tokens)) {
                    continue;
                }
                if (bestStart == null || rule.commandStart().size() > bestLength) {
                    bestStart = String.join(" ", rule.commandStart());
                    bestLength = rule.commandStart().size();
                }
                if (rule.resolve(agentMode) == PermissionMode.YOLO) {
                    allowed = true;
                }
            }

            return new SegmentDecision(
                    allowed, bestStart, allowed ? null : (bestStart != null ? Cause.NOT_YOLO : Cause.NO_MATCH));
        }

        private SegmentDecision evaluateXargs(
                final List<Token> tokens, final int xargsIndex, final AgentMode agentMode) {

            var i = xargsIndex + 1;

            while (i < tokens.size()) {
                final var option = tokens.get(i).text();

                // `--` terminates xargs options.
                if ("--".equals(option)) {
                    i++;
                    break;
                }

                // Not an option anymore: this is the command xargs will execute.
                if (!option.startsWith("-") || "-".equals(option)) {
                    break;
                }

                final var consumed = consumeXargsOption(tokens, i);
                if (consumed < 0) {
                    // Unknown/unsupported option or malformed option.
                    // Fail closed rather than accidentally auto-allowing it.
                    return new SegmentDecision(false, null, Cause.NO_MATCH);
                }

                i += consumed;
            }

            // Plain `xargs` executes `echo` by default. Don't treat that as
            // an implicitly allowed command.
            if (i >= tokens.size()) {
                return new SegmentDecision(false, null, Cause.NO_MATCH);
            }

            final var command = new ArrayList<String>(tokens.size() - i);
            for (; i < tokens.size(); i++) {
                command.add(tokens.get(i).text());
            }

            // Do not allow xargs to hide a shell command from the evaluator.
            if (isShellCommand(command.get(0))) {
                return new SegmentDecision(false, null, Cause.NO_MATCH);
            }

            return evaluateCommandTokens(command, agentMode);
        }

        /**
         * Parses one xargs option.
         *
         * @return the number of tokens consumed, or -1 for an unsupported/malformed option.
         */
        private int consumeXargsOption(final List<Token> tokens, final int index) {

            final var option = tokens.get(index).text();

            // Long options with separate arguments.
            if (option.equals("--delimiter")
                    || option.equals("--eof")
                    || option.equals("--replace")
                    || option.equals("--max-lines")
                    || option.equals("--max-args")
                    || option.equals("--max-chars")
                    || option.equals("--max-procs")
                    || option.equals("--arg-file")) {
                return index + 1 < tokens.size() ? 2 : -1;
            }

            // Long options with attached arguments.
            if (option.startsWith("--delimiter=")
                    || option.startsWith("--eof=")
                    || option.startsWith("--replace=")
                    || option.startsWith("--max-lines=")
                    || option.startsWith("--max-args=")
                    || option.startsWith("--max-chars=")
                    || option.startsWith("--max-procs=")
                    || option.startsWith("--arg-file=")) {
                return 1;
            }

            // Argument-less options.
            if (option.equals("-0")
                    || option.equals("--null")
                    || option.equals("-r")
                    || option.equals("--no-run-if-empty")
                    || option.equals("-t")
                    || option.equals("--verbose")
                    || option.equals("-p")
                    || option.equals("--interactive")
                    || option.equals("-x")
                    || option.equals("--exit")
                    || option.equals("-o")
                    || option.equals("--open-tty")) {
                return 1;
            }

            // Short options with separate arguments.
            if (option.equals("-d")
                    || option.equals("-E")
                    || option.equals("-I")
                    || option.equals("-L")
                    || option.equals("-n")
                    || option.equals("-P")
                    || option.equals("-s")
                    || option.equals("-a")) {
                return index + 1 < tokens.size() ? 2 : -1;
            }

            // Short options with attached arguments.
            if (option.length() > 2) {
                final var prefix = option.substring(0, 2);

                if (prefix.equals("-d")
                        || prefix.equals("-E")
                        || prefix.equals("-I")
                        || prefix.equals("-L")
                        || prefix.equals("-n")
                        || prefix.equals("-P")
                        || prefix.equals("-s")) {
                    return 1;
                }
            }

            // Unknown xargs option -> fail closed.
            return -1;
        }

        private boolean isShellCommand(final String command) {
            return switch (command) {
                case "sh", "bash", "dash", "zsh", "ksh", "fish", "csh", "tcsh" -> true;
                default -> false;
            };
        }

        // ------------------------------------------------------------------
        // quote aware tokenizer with operator segmentation
        // ------------------------------------------------------------------

        private record Token(String text, boolean redirect, boolean substitution) {}

        private record Segment(List<Token> tokens) {}

        private static final class TokenBuilder {
            private final StringBuilder text = new StringBuilder();
            private boolean started;
            private boolean redirect;
            private boolean substitution;

            void append(final char c) {
                text.append(c);
                started = true;
            }

            void markRedirect() {
                redirect = true;
                started = true;
            }

            void markSubstitution() {
                substitution = true;
                started = true;
            }

            boolean endsWithRedirect() {
                return text.length() > 0
                        && (text.charAt(text.length() - 1) == '>' || text.charAt(text.length() - 1) == '<');
            }

            Token build() {
                final var token = new Token(text.toString(), redirect, substitution);
                reset();
                return token;
            }

            void reset() {
                text.setLength(0);
                started = false;
                redirect = false;
                substitution = false;
            }
        }

        private static List<Segment> tokenize(final String raw) {
            final var segments = new ArrayList<Segment>();
            var tokens = new ArrayList<Token>();
            final var builder = new TokenBuilder();
            var continuation = false; // the previous separator (|, ||, &&) expects the command to continue
            final var length = raw.length();
            for (var i = 0; i < length; i++) {
                final var c = raw.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r') {
                    if (builder.started) {
                        tokens.add(builder.build());
                    }
                    continue;
                }
                if (c == '\n') {
                    if (continuation && tokens.isEmpty() && !builder.started) {
                        continue; // the pipeline continues on the next line
                    }
                    if (builder.started) {
                        tokens.add(builder.build());
                    }
                    if (!tokens.isEmpty()) {
                        segments.add(new Segment(tokens));
                        tokens = new ArrayList<>();
                    }
                    continuation = false;
                    continue;
                }
                if (c == ';' || c == '&' || c == '|') {
                    if (c == '&' && builder.endsWithRedirect()) {
                        builder.append(c); // fd manipulation such as 2>&1 stays part of the redirect token
                        builder.markRedirect();
                        continue;
                    }
                    if (builder.started) {
                        tokens.add(builder.build());
                    }
                    if (!tokens.isEmpty()) {
                        segments.add(new Segment(tokens));
                        tokens = new ArrayList<>();
                    }
                    if (c == '|') {
                        continuation = true;
                    } else if (c == '&' && i + 1 < length && raw.charAt(i + 1) == '&') {
                        i++;
                        continuation = true;
                    } else {
                        continuation = false;
                    }
                    continue;
                }
                if (c == '\'') { // literal until the closing quote
                    for (i = i + 1; i < length && raw.charAt(i) != '\''; i++) {
                        builder.append(raw.charAt(i));
                    }
                    continue;
                }
                if (c == '"') { // $() and backticks are live, backslash escapes
                    for (i = i + 1; i < length && raw.charAt(i) != '"'; i++) {
                        final var d = raw.charAt(i);
                        if (d == '\\' && i + 1 < length && raw.charAt(i + 1) != '\n') {
                            builder.append(raw.charAt(++i));
                            continue;
                        }
                        if (d == '`' || (d == '$' && i + 1 < length && raw.charAt(i + 1) == '(')) {
                            builder.markSubstitution();
                        }
                        builder.append(d);
                    }
                    continue;
                }
                if (c == '\\') {
                    if (i + 1 < length) {
                        final var next = raw.charAt(i + 1);
                        if (next == '\n') {
                            i++; // consume the newline too: the line continues without a separator
                            continue;
                        }
                        builder.append(next);
                        i++;
                    }
                    continue;
                }
                if (c == '>' || c == '<') {
                    builder.append(c);
                    builder.markRedirect();
                    continue;
                }
                if (c == '$') {
                    if (i + 1 < length && raw.charAt(i + 1) == '(') {
                        builder.markSubstitution();
                        builder.append('$');
                        builder.append('(');
                        i++;
                    } else {
                        builder.append(c);
                    }
                    continue;
                }
                if (c == '`') {
                    builder.markSubstitution();
                    builder.append(c);
                    continue;
                }
                builder.append(c);
            }
            if (builder.started) {
                tokens.add(builder.build());
            }
            if (!tokens.isEmpty()) {
                segments.add(new Segment(tokens));
            }
            return segments;
        }

        private static boolean isEnvAssignment(final String token) {
            if (token.isEmpty() || !Character.isLetterOrDigit(token.charAt(0)) && token.charAt(0) != '_') {
                return false;
            }
            for (var i = 0; i < token.length(); i++) {
                final var c = token.charAt(i);
                if (c == '=') {
                    return i > 0 && (Character.isLetterOrDigit(token.charAt(i - 1)) || token.charAt(i - 1) == '_');
                }
                if (!Character.isLetterOrDigit(c) && c != '_' && c != '.') {
                    return false;
                }
            }
            return false;
        }

        // ------------------------------------------------------------------
        // command substitution masking ($( ... ) and $(( ... )))
        // ------------------------------------------------------------------

        private record MaskedCommand(String text, List<String> substitutions) {}

        private enum PlaceholderKind {
            NONE,
            PURE,
            GLUED
        }

        private record PlaceholderRef(PlaceholderKind kind, int index) {}

        /**
         * Replaces every balanced command substitution {@code $( ... )} with a {@code YCA_SUB_<n>} placeholder
         * (the captured command is stored for recursive verification) and every arithmetic expansion
         * {@code $(( ... ))} with a harmless literal: arithmetic has no side effect. Single quoted content is
         * left untouched (no substitution happens there) while double quoted content is masked the same way
         * (substitutions are live inside). Unbalanced {@code $(} is left raw and fails closed in the tokenizer.
         */
        private static MaskedCommand mask(final String raw) {
            final var sb = new StringBuilder(raw.length());
            final var substitutions = new ArrayList<String>();
            maskInto(raw, 0, raw.length(), sb, substitutions);
            return new MaskedCommand(sb.toString(), substitutions);
        }

        private static void maskInto(
                final String raw,
                final int from,
                final int to,
                final StringBuilder out,
                final List<String> substitutions) {
            var i = from;
            while (i < to) {
                final var c = raw.charAt(i);
                if (c == '\'') { // single quotes: everything inside is literal, $() included
                    var end = i + 1;
                    while (end < to && raw.charAt(end) != '\'') {
                        end++;
                    }
                    out.append(raw, i, Math.min(end + 1, to));
                    i = end + 1;
                    continue;
                }
                if (c == '"') { // double quotes: $() and $(( )) are live, backslash escapes
                    out.append(c);
                    i++;
                    while (i < to) {
                        final var d = raw.charAt(i);
                        if (d == '\\' && i + 1 < to) {
                            out.append(d).append(raw.charAt(i + 1));
                            i += 2;
                            continue;
                        }
                        if (d == '"') {
                            out.append(d);
                            i++;
                            break;
                        }
                        if (d == '$' && i + 1 < to && raw.charAt(i + 1) == '(') {
                            i = captureGroup(raw, i + 1, to, out, substitutions);
                            continue;
                        }
                        out.append(d);
                        i++;
                    }
                    continue;
                }
                if (c == '\\') {
                    out.append(c);
                    if (i + 1 < to) {
                        out.append(raw.charAt(i + 1));
                        i++;
                    }
                    i++;
                    continue;
                }
                if (c == '$' && i + 1 < to && raw.charAt(i + 1) == '(') {
                    i = captureGroup(raw, i + 1, to, out, substitutions);
                    continue;
                }
                out.append(c);
                i++;
            }
        }

        /**
         * Captures the group opened by {@code $(} at {@code openParen}: command substitutions become
         * {@code YCA_SUB_<n>} placeholders, arithmetic {@code $(( ... ))} becomes a harmless literal.
         * Returns the index just past the group, or {@code openParen + 1} when unbalanced so the raw
         * {@code $(} stays in place and is rejected by the tokenizer (fail closed).
         */
        private static int captureGroup(
                final String raw,
                final int openParen,
                final int to,
                final StringBuilder out,
                final List<String> substitutions) {
            final var arithmetic = openParen + 1 < to && raw.charAt(openParen + 1) == '(';
            final var end = findGroupEnd(raw, openParen, to);
            if (end < 0) {
                out.append('$').append('(');
                return openParen + 1;
            }
            if (arithmetic) {
                out.append('0');
            } else {
                substitutions.add(raw.substring(openParen + 1, end));
                out.append(SUBSTITUTION_PREFIX).append(substitutions.size() - 1);
            }
            return end + 1;
        }

        /**
         * Finds the {@code )} balancing the paren at {@code openParen}, ignoring quoted and escaped parens
         * while still counting the parens of nested substitutions. Returns -1 when the group is unbalanced.
         */
        private static int findGroupEnd(final String raw, final int openParen, final int to) {
            var depth = 1;
            var i = openParen + 1;
            while (i < to) {
                final var c = raw.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    i = skipSingleQuoted(raw, i, to);
                    continue;
                }
                if (c == '"') {
                    i = skipDoubleQuoted(raw, i, to);
                    continue;
                }
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
                i++;
            }
            return -1;
        }

        private static int skipSingleQuoted(final String raw, final int open, final int to) {
            var i = open + 1;
            while (i < to && raw.charAt(i) != '\'') {
                i++;
            }
            return i < to ? i + 1 : to;
        }

        /** Skips a double quoted section while still counting the parens of nested live $() groups. */
        private static int skipDoubleQuoted(final String raw, final int open, final int to) {
            var i = open + 1;
            while (i < to) {
                final var c = raw.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return i + 1;
                }
                if (c == '$' && i + 1 < to && raw.charAt(i + 1) == '(') {
                    final var end = findGroupEnd(raw, i + 1, to);
                    if (end < 0) {
                        return to; // unbalanced: nothing sensible to do, bail out
                    }
                    i = end + 1;
                    continue;
                }
                i++;
            }
            return to;
        }

        /** Parses the numeric index of a pure {@code YCA_SUB_<n>} placeholder, or -1 when not one. */
        private static int substitutionIndex(final String token) {
            if (!token.startsWith(SUBSTITUTION_PREFIX)) {
                return -1;
            }
            final var number = token.substring(SUBSTITUTION_PREFIX.length());
            if (number.isEmpty()) {
                return -1;
            }
            for (var i = 0; i < number.length(); i++) {
                if (number.charAt(i) < '0' || number.charAt(i) > '9') {
                    return -1;
                }
            }
            return Integer.parseInt(number);
        }

        /**
         * Classifies a token relative to the substitution placeholders: {@code PURE} when the whole token
         * (the value part for env assignments) is a placeholder, {@code GLUED} when a placeholder is mixed
         * with other literal text (verified recursively by {@link #verifyPlaceholders(List, AgentMode, MaskedCommand, int)}),
         * {@code NONE} otherwise.
         */
        private static PlaceholderRef placeholderRef(final String token) {
            var candidate = token;
            if (isEnvAssignment(token)) {
                candidate = token.substring(token.indexOf('=') + 1);
            }
            final var index = substitutionIndex(candidate);
            if (index >= 0) {
                return new PlaceholderRef(PlaceholderKind.PURE, index);
            }
            if (token.contains(SUBSTITUTION_PREFIX) || candidate.contains(SUBSTITUTION_PREFIX)) {
                return new PlaceholderRef(PlaceholderKind.GLUED, -1);
            }
            return new PlaceholderRef(PlaceholderKind.NONE, -1);
        }

        /** Replaces placeholders in a token by their original {@code $( ... )} text (denials and deny matching). */
        private static String expand(final String token, final MaskedCommand masked) {
            final var ref = placeholderRef(token);
            if (ref.kind() == PlaceholderKind.GLUED) {
                var expanded = token;
                for (var i = 0; i < masked.substitutions().size(); i++) {
                    expanded = expanded.replace(
                            SUBSTITUTION_PREFIX + i,
                            "$(" + masked.substitutions().get(i) + ")");
                }
                return expanded;
            }
            if (ref.kind() != PlaceholderKind.PURE
                    || ref.index() >= masked.substitutions().size()) {
                return token;
            }
            final var replacement = "$(" + masked.substitutions().get(ref.index()) + ")";
            if (isEnvAssignment(token)) {
                return token.substring(0, token.indexOf('=') + 1) + replacement;
            }
            return replacement;
        }
    }

    // ------------------------------------------------------------------
    // compiled rule model
    // ------------------------------------------------------------------

    private record Excludes(Set<String> exact, List<String> longOptions, List<List<String>> sequences) {

        boolean anyMatch(final List<String> tokens) {
            for (final var token : tokens) {
                if (exact.contains(token)) {
                    return true;
                }
                for (final var option : longOptions) {
                    if (token.equals(option) || token.startsWith(option + "=")) {
                        return true;
                    }
                }
            }
            for (final var sequence : sequences) {
                if (containsConsecutive(tokens, sequence)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record CompiledRule(
            List<String> commandStart, Excludes excludes, PermissionMode plan, PermissionMode defaultMode) {

        PermissionMode resolve(final AgentMode agentMode) {
            if (agentMode == AgentMode.PLAN) {
                return plan != null ? plan : defaultMode;
            }
            return defaultMode != null ? defaultMode : plan;
        }

        boolean matches(final List<String> tokens) {
            if (commandStart.size() > tokens.size()) {
                return false;
            }
            for (var i = 0; i < commandStart.size(); i++) {
                if (!commandStart.get(i).equals(tokens.get(i))) {
                    return false;
                }
            }
            return !excludes.anyMatch(tokens);
        }
    }

    private static boolean containsConsecutive(final List<String> tokens, final List<String> sequence) {
        for (var i = 0; i + sequence.size() <= tokens.size(); i++) {
            var matches = true;
            for (var j = 0; j < sequence.size(); j++) {
                if (!sequence.get(j).equals(tokens.get(i + j))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
