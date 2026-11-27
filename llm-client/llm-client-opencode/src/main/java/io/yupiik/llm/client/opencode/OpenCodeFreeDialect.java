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
package io.yupiik.llm.client.opencode;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.model.LlmMessage;
import io.yupiik.llm.client.model.LlmRequest;
import io.yupiik.llm.client.model.LlmResponse;
import io.yupiik.llm.client.model.LlmStreamEvent;
import io.yupiik.llm.client.model.LlmToolCall;
import io.yupiik.llm.client.model.Usage;
import io.yupiik.llm.client.openai.OpenAICompletionsDialect;
import io.yupiik.llm.client.spi.ProviderSpec;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Experimental OpenCode streaming completions. Availability to third-party clients is not guaranteed. */
public class OpenCodeFreeDialect extends OpenAICompletionsDialect implements AutoCloseable {
    private static final Pattern SESSION_ID = Pattern.compile("ses_[0-9a-f]{12}[0-9A-Za-z]{14}");

    private static final String WORKING_DIRECTORY = "Working directory:";
    private static final String ENVIRONMENT = "<env>";
    private final OpenCodeHttpClient ownedTransport;
    private static final String SESSION_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private final String sessionPrefix =
            "ses_" + String.format("%012x", ~(System.currentTimeMillis() * 4096L + 1) & 0xffffffffffffL);
    private final String fallbackSession = newSession();

    /** Uses a caller-owned HTTP client, which must select the OpenCode transport for this provider. */
    public OpenCodeFreeDialect(final JsonMapper mapper, final ExtendedHttpClient client, final ProviderSpec provider) {
        super(mapper, client, provider);
        ownedTransport = null;
    }

    @Override
    public void close() {
        if (ownedTransport != null) {
            try {
                // Release the owned wrapper's listeners/hooks. It does not close its supplied delegate.
                client.close();
            } finally {
                ownedTransport.close();
            }
        }
    }

    @Override
    public String name() {
        return "opencode-free";
    }

    @Override
    protected void authenticate(final HttpRequest.Builder builder) {
        builder.setHeader("authorization", "Bearer public");
    }

    @Override
    protected HttpRequest modelsRequest(final String afterId, final String limit) {
        final var request = super.modelsRequest(afterId, limit);
        // Inference exposes one catalog, shared by the protocol-specific routes.
        final var uri = request.uri();
        if (uri.getPath().endsWith("/openai/v1/models")) {
            return HttpRequest.newBuilder(request, (_, _) -> true)
                    .uri(java.net.URI.create(uri.toString().replace("/openai/v1/models", "/v1/models")))
                    .build();
        }
        return request;
    }

    @Override
    public CompletionStage<Flow.Publisher<LlmStreamEvent>> stream(
            final LlmRequest request, final BooleanSupplier cancelled) {
        return super.stream(request, cancelled == null ? () -> false : cancelled);
    }

    @Override
    protected byte[] encodeRequest(final LlmRequest request, final boolean stream) {
        return super.encodeRequest(request, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected HttpRequest http(final byte[] payload) {
        final var body = new LinkedHashMap<String, Object>(
                mapper.fromString(Map.class, new String(payload, StandardCharsets.UTF_8)));
        final var session = body.remove("prompt_cache_key");
        final var directory = patchEnvironment(body);
        final var builder = HttpRequest.newBuilder(java.net.URI.create(provider.baseUrl()))
                .timeout(provider.timeout())
                .header("content-type", "application/json")
                .header("accept", "*/*")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.toBytes(body)));
        authenticate(builder);
        // Keep the observed compatibility profile isolated to this dialect.
        // Values are generated locally; no captured session or project identifiers are reused.
        final var affinity = session == null ? fallbackSession : sessionAffinity(String.valueOf(session));
        builder.header("user-agent", "opencode/latest/2.2.3/cli")
                .header("x-opencode-client", "cli")
                .header("x-opencode-project", projectId(directory))
                .header("x-opencode-session", affinity)
                .header("x-opencode-session-id", affinity)
                .header("x-session-affinity", affinity)
                .header("x-session-id", affinity);
        provider.headersCustomizer().accept(builder);
        return builder.build();
    }

    /** Patches only the outgoing request, returning the directory used for project affinity. */
    @SuppressWarnings("unchecked")
    private String patchEnvironment(final Map<String, Object> body) {
        final var messages = new ArrayList<>((List<Map<String, Object>>) body.getOrDefault("messages", List.of()));
        for (int i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if ("system".equals(message.get("role")) && message.get("content") instanceof String text) {
                // Literal scans avoid regex backtracking, even with repeated unterminated tags.
                int start = text.indexOf(ENVIRONMENT);
                while (start >= 0) {
                    final int contentStart = start + ENVIRONMENT.length();
                    final int end = text.indexOf('<', contentStart);
                    if (end < 0) {
                        break;
                    }
                    if (text.startsWith("</env>", end)) {
                        final var directory = workingDirectory(text, contentStart, end);
                        if (directory != null) {
                            return directory;
                        }
                    }
                    start = text.indexOf(ENVIRONMENT, end);
                }
                final var foundDirectory = workingDirectory(text, 0, text.length());
                final var directory =
                        foundDirectory == null ? Path.of("").toAbsolutePath().toString() : foundDirectory;
                final var patched = new LinkedHashMap<>(message);
                patched.put("content", text + "\n\n" + environment(directory));
                messages.set(i, patched);
                body.put("messages", messages);
                return directory;
            }
        }
        final var directory = Path.of("").toAbsolutePath().toString();
        messages.addFirst(Map.of("role", "system", "content", environment(directory)));
        body.put("messages", messages);
        return directory;
    }

    private String workingDirectory(final String text, final int start, final int end) {
        int offset = start;
        while (offset < end) {
            int marker = offset;
            while (marker <= end - WORKING_DIRECTORY.length() && !text.startsWith(WORKING_DIRECTORY, marker)) {
                marker++;
            }
            if (marker > end - WORKING_DIRECTORY.length()) {
                return null;
            }
            int prefix = marker - 1;
            while (prefix >= start && horizontalSpace(text.charAt(prefix))) {
                prefix--;
            }
            if (prefix >= start && text.charAt(prefix) == '-') {
                prefix--;
                while (prefix >= start && horizontalSpace(text.charAt(prefix))) {
                    prefix--;
                }
            }
            final int valueStart = marker + WORKING_DIRECTORY.length();
            int lineEnd = valueStart;
            while (lineEnd < end && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            if (prefix < start || text.charAt(prefix) == '\n' || text.charAt(prefix) == '\r') {
                final var directory = text.substring(valueStart, lineEnd).strip();
                if (!directory.isEmpty()) {
                    return directory;
                }
            }
            offset = lineEnd + 1;
        }
        return null;
    }

    private String projectId(final String directory) {
        String normalized;
        try {
            normalized = Path.of(directory).toAbsolutePath().normalize().toString();
        } catch (final InvalidPathException invalid) {
            normalized = directory;
        }

        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private boolean horizontalSpace(final char value) {
        return value != '\n' && value != '\r' && (Character.isWhitespace(value) || Character.isSpaceChar(value));
    }

    private String environment(final String directory) {
        return "<env>\n  Working directory: " + directory.replace('\r', ' ').replace('\n', ' ') + "\n</env>";
    }

    private String newSession() {
        final var random = new SecureRandom();
        final var id = new StringBuilder(sessionPrefix);
        for (int i = 0; i < 14; i++) {
            id.append(SESSION_CHARS.charAt(random.nextInt(SESSION_CHARS.length())));
        }
        return id.toString();
    }

    private String sessionAffinity(final String session) {
        if (SESSION_ID.matcher(session).matches()) {
            return session;
        }
        final var uuid = UUID.nameUUIDFromBytes(session.getBytes(StandardCharsets.UTF_8));
        final var id = new StringBuilder(sessionPrefix);
        long bits = uuid.getMostSignificantBits();
        for (int i = 0; i < 14; i++) {
            if (i == 7) {
                bits = uuid.getLeastSignificantBits();
            }
            id.append(SESSION_CHARS.charAt((int) Long.remainderUnsigned(bits, SESSION_CHARS.length())));
            bits = Long.divideUnsigned(bits, SESSION_CHARS.length());
        }
        return id.toString();
    }

    @Override
    public CompletionStage<LlmResponse> chat(final LlmRequest request, final BooleanSupplier cancelled) {
        return stream(request, cancelled == null ? () -> false : cancelled).thenCompose(events -> {
            final var result = new CompletableFuture<LlmResponse>();
            events.subscribe(new Flow.Subscriber<>() {
                private final StringBuilder text = new StringBuilder();
                private final StringBuilder reasoning = new StringBuilder();
                private final Map<Integer, Call> calls = new TreeMap<>();
                private Usage usage = Usage.EMPTY;
                private String finish;

                @Override
                public void onSubscribe(final Flow.Subscription subscription) {
                    result.whenComplete((value, error) -> {
                        if (result.isCancelled()) {
                            subscription.cancel();
                        }
                    });
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(final LlmStreamEvent event) {
                    switch (event) {
                        case LlmStreamEvent.TextDelta t -> text.append(t.text());
                        case LlmStreamEvent.ReasoningDelta r -> reasoning.append(r.text());
                        case LlmStreamEvent.ToolCallDelta t -> {
                            final var call = calls.computeIfAbsent(t.index(), ignored -> new Call());
                            if (t.id() != null) {
                                call.id = t.id();
                            }
                            if (t.name() != null) {
                                call.name = t.name();
                            }
                            if (t.arguments() != null) {
                                call.arguments.append(t.arguments());
                            }
                        }
                        case LlmStreamEvent.End e -> {
                            if (e.finishReason() != null) {
                                finish = e.finishReason();
                            }
                            if (e.usage() != null) {
                                usage = e.usage();
                            }
                        }
                        default -> {}
                    }
                }

                @Override
                public void onError(final Throwable error) {
                    result.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    final var tools = calls.values().stream()
                            .map(c -> LlmToolCall.of(c.id, c.name, c.arguments.toString()))
                            .toList();
                    result.complete(new LlmResponse(
                            null,
                            request.model(),
                            null,
                            List.of(new LlmResponse.LlmChoice(
                                    0,
                                    new LlmMessage(
                                            "assistant",
                                            text.toString(),
                                            null,
                                            tools,
                                            null,
                                            reasoning.isEmpty()
                                                    ? Map.of()
                                                    : Map.of("reasoning_content", reasoning.toString())),
                                    finish)),
                            usage,
                            Map.of()));
                }
            });
            return result;
        });
    }

    private static final class Call {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
