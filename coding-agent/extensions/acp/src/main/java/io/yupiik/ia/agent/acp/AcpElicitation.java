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
package io.yupiik.ia.agent.acp;

import static java.util.stream.Collectors.joining;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import io.yupiik.ia.agent.core.question.FeedbackGate;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.security.SecretNames;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Session-scoped, nonsecret stable-v1 form elicitation. This is not an approval gate:
 * tool authorization must continue to use {@code session/request_permission}.
 * No protocol input, transport, pending requests or executor is owned here.
 * Callers must only route nonsecret interactions here; the conservative name/keyword
 * checks are defense in depth, not proof that arbitrary prompts or answers are nonsecret.
 */
public final class AcpElicitation implements FeedbackGate {
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9]");
    private static final Pattern DEFAULT_SENSITIVE_WORD = wholeWords(AcpCommand.DEFAULT_ELICITATION_SENSITIVE_WORDS);

    private final String sessionId;
    private final String toolCallId;
    private final boolean formSupported;
    private final SecretNames secretNames;
    private final Duration timeout;
    private final long timeoutNanos;
    private final RequestChannel channel;
    private InputPolicy inputPolicy =
            new InputPolicy(AcpCommand.DEFAULT_ELICITATION_SENSITIVE_MARKERS, DEFAULT_SENSITIVE_WORD);

    /**
     * Create for one verified connection/session, optionally one tool call (null otherwise).
     * {@code formSupported} must come from negotiated capabilities, never from a prompt.
     * The channel owns async deadlines; the same timeout additionally bounds blocking feedback.
     */
    public AcpElicitation(
            final String sessionId,
            final String toolCallId,
            final boolean formSupported,
            final SecretNames secretNames,
            final Duration timeout,
            final RequestChannel channel) {
        if (sessionId == null || sessionId.isBlank() || (toolCallId != null && toolCallId.isBlank())) {
            throw new IllegalArgumentException("A verified session and an optional nonblank tool call are required");
        }
        this.sessionId = sessionId;
        this.toolCallId = toolCallId;
        this.formSupported = formSupported;
        this.secretNames = Objects.requireNonNull(secretNames, "secretNames");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) {
            throw new IllegalArgumentException("A positive bounded timeout is required");
        }
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /**
     * Configure once during session setup, before publishing this adapter to request threads.
     * These literal ACP additions replace only the ACP defaults, never the injected common
     * {@link SecretNames} rules or the caller's independent nonsecret approval requirement.
     * Lists are snapshotted and the whole-word pattern is compiled here, not per question.
     */
    public AcpElicitation withSensitiveInputs(final List<String> markers, final List<String> words) {
        final var normalized = Objects.requireNonNull(markers, "markers").stream()
                .map(marker -> {
                    final var normalizedMarker = marker == null ? "" : normalize(marker);
                    if (normalizedMarker.isEmpty()) {
                        throw new IllegalArgumentException("Sensitive markers must contain ASCII letters or digits");
                    }
                    return normalizedMarker;
                })
                .distinct()
                .toList();
        inputPolicy = new InputPolicy(normalized, wholeWords(words));
        return this;
    }

    /** Inspect the raw clientCapabilities object, not the initialize envelope. */
    public static boolean supportsForm(final Map<String, ?> clientCapabilities) {
        return clientCapabilities != null
                && clientCapabilities.get("elicitation") instanceof Map<?, ?> elicitation
                && elicitation.get("form") instanceof Map<?, ?>;
    }

    /** Run off the connection's protocol/event reader thread. Null means no validated answer. */
    @Override
    public Map<String, Object> ask(final List<Question> questions) {
        final CompletableFuture<Map<String, Object>> answer = elicit(questions, Function.identity(), null);
        try {
            return answer.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (final InterruptedException e) {
            answer.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (final TimeoutException e) {
            answer.cancel(true);
            return null;
        } catch (final ExecutionException | CancellationException e) {
            return null;
        }
    }

    /**
     * Deliberately not ShellIO's unrestricted readLine signature. Wiring must explicitly
     * approve the call site's nonsecret purpose, independently of these keyword checks.
     * In particular, generic authentication/provider-login readLine calls must pass false.
     * This adapter never changes to URL mode or falls back to a form for credentials.
     */
    public CompletionStage<Optional<String>> readLine(final String prompt, final boolean approvedNonSecret) {
        if (!approvedNonSecret) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return elicit(
                List.of(new Question("answer", prompt, List.of(), false)),
                answers -> Optional.of((String) answers.get("answer")),
                Optional.empty());
    }

    /** Nonsecurity yes/no feedback only, never tool approval or transaction authorization. */
    public CompletionStage<Boolean> confirm(final String question) {
        return elicit(
                List.of(new Question("answer", question, List.of("Yes", "No"), false)),
                answers -> "Yes".equals(answers.get("answer")),
                false);
    }

    public CompletionStage<Optional<String>> pick(final String title, final List<String> options) {
        if (options == null || options.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return elicit(
                List.of(new Question("answer", title, options, false)),
                answers -> Optional.of((String) answers.get("answer")),
                Optional.empty());
    }

    private <T> CompletableFuture<T> elicit(
            final List<Question> questions, final Function<Map<String, Object>, T> accepted, final T cancelled) {
        if (!formSupported || Thread.currentThread().isInterrupted()) {
            return CompletableFuture.completedFuture(cancelled);
        }
        final Schema schema;
        try {
            schema = schema(questions);
        } catch (final RuntimeException e) {
            return CompletableFuture.completedFuture(cancelled);
        }
        if (schema == null) {
            return CompletableFuture.completedFuture(cancelled);
        }
        final CompletableFuture<Reply> response;
        try {
            response = channel.request(
                            "elicitation/create",
                            new Form(
                                    sessionId,
                                    toolCallId,
                                    "form",
                                    "Please answer these non-sensitive questions.",
                                    schema),
                            timeout)
                    .toCompletableFuture();
        } catch (final RuntimeException e) {
            return CompletableFuture.completedFuture(cancelled);
        }
        final var result = new CompletableFuture<T>();
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                response.cancel(true);
            }
        });
        response.whenComplete((reply, error) -> {
            if (error != null) {
                result.complete(cancelled);
                return;
            }
            try {
                final var content = validate(schema, reply);
                result.complete(content == null ? cancelled : accepted.apply(content));
            } catch (final RuntimeException e) {
                result.complete(cancelled);
            }
        });
        return result;
    }

    private Schema schema(final List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        final var properties = new LinkedHashMap<String, Property>();
        for (final var question : questions) {
            if (question == null
                    || question.id() == null
                    || question.id().isBlank()
                    || question.prompt() == null
                    || question.prompt().isBlank()
                    || properties.containsKey(question.id())
                    || sensitive(question.id())
                    || sensitive(question.prompt())) {
                return null;
            }
            final var options = question.options() == null ? List.<String>of() : List.copyOf(question.options());
            if (options.stream().anyMatch(option -> option.isBlank() || sensitive(option))
                    || new HashSet<>(options).size() != options.size()
                    || (question.multiple() && options.isEmpty())) {
                return null;
            }
            properties.put(
                    question.id(),
                    question.multiple()
                            ? new Property(
                                    "array", question.prompt(), null, new Items("string", options), 0, options.size())
                            : new Property(
                                    "string", question.prompt(), options.isEmpty() ? null : options, null, null, null));
        }
        return new Schema("object", Collections.unmodifiableMap(properties), List.copyOf(properties.keySet()));
    }

    private boolean sensitive(final String text) {
        final var compact = normalize(text);
        final var policy = inputPolicy;
        return secretNames.isSecretName(compact)
                || policy.markers().stream().anyMatch(compact::contains)
                || (policy.words() != null && policy.words().matcher(text).find())
                || text.contains("://"); // Do not embed clickable authentication URLs in form fields.
    }

    private static String normalize(final String text) {
        return NON_ALPHANUMERIC.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static Pattern wholeWords(final List<String> words) {
        final var alternatives = Objects.requireNonNull(words, "words").stream()
                .map(word -> {
                    if (word == null || word.isBlank()) {
                        throw new IllegalArgumentException("Sensitive words must not be blank");
                    }
                    return Pattern.quote(word.strip());
                })
                .distinct()
                .collect(joining("|"));
        return alternatives.isEmpty()
                ? null
                : Pattern.compile(
                        "(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
    }

    private record InputPolicy(List<String> markers, Pattern words) {}

    private Map<String, Object> validate(final Schema schema, final Reply reply) {
        // Content is ignored for decline/cancel. Missing/null accepted content cannot answer required questions.
        if (reply == null
                || !"accept".equals(reply.action())
                || !(reply.content() instanceof Map<?, ?> content)
                || !content.keySet().equals(schema.properties().keySet())) {
            return null;
        }
        final var answers = new LinkedHashMap<String, Object>();
        for (final var entry : schema.properties().entrySet()) {
            final var property = entry.getValue();
            final var value = content.get(entry.getKey());
            if (property.items() != null) {
                if (!(value instanceof List<?> selections)
                        || selections.size() > property.maxItems()
                        || selections.stream()
                                .anyMatch(selection -> !(selection instanceof String)
                                        || !property.items().options().contains(selection))
                        || new HashSet<>(selections).size() != selections.size()) {
                    return null;
                }
                answers.put(entry.getKey(), List.copyOf(selections));
            } else if (value instanceof String text
                    && (property.options() == null || property.options().contains(text))) {
                answers.put(entry.getKey(), text);
            } else {
                return null;
            }
        }
        return Collections.unmodifiableMap(answers);
    }

    /**
     * The verified connection must own IDs, scope checks, pending cleanup, cancellation and deadlines.
     * Return promptly with a stage whose toCompletableFuture().cancel(true) cancels the actual request.
     * Decode the JSON-RPC result with JsonMapper into Reply; JSON-RPC errors, malformed results,
     * disconnects and session cancellation must fail/cancel the stage, never produce an accept.
     * Do not return an unlinked thenApply/minimalCompletionStage view that loses cancellation.
     */
    @FunctionalInterface
    public interface RequestChannel {
        CompletionStage<Reply> request(String method, Form params, Duration timeout);
    }

    @JsonModel
    public record Form(String sessionId, String toolCallId, String mode, String message, Schema requestedSchema) {}

    @JsonModel
    public record Schema(String type, Map<String, Property> properties, List<String> required) {}

    @JsonModel
    public record Property(
            String type,
            String title,
            @JsonProperty("enum") List<String> options,
            Items items,
            Integer minItems,
            Integer maxItems) {}

    @JsonModel
    public record Items(String type, @JsonProperty("enum") List<String> options) {}

    // Content is genuinely polymorphic: only accept interprets it, and decline/cancel ignore it entirely.
    @JsonModel
    public record Reply(String action, Object content) {}
}
