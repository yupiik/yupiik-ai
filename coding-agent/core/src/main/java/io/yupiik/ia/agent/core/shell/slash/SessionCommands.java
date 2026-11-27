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
package io.yupiik.ia.agent.core.shell.slash;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.pretty.PrettyJsonMapper;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.context.TokenEstimator;
import io.yupiik.ia.agent.core.context.UsageTracker;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.session.AgentSessionService;
import io.yupiik.ia.agent.core.session.SessionStore;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Session lifecycle commands shared by every shell: new/resume/quit plus the
 * context dump and session persistence helpers.
 */
@ApplicationScoped
public class SessionCommands implements SlashCommandSet {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public String title() {
        return "Session commands";
    }

    private static final int RESUME_PAGE_SIZE = 50;
    private static final int RESUME_SUMMARY_WIDTH = 80;

    private static final String TRANSCRIPT = "transcript";

    /**
     * What {@code /context} can dump. The first three are read-only views, the last two are the
     * context operations; every one of them accepts an optional trailing file path.
     */
    private static final List<String> CONTEXT_MODES =
            List.of(TRANSCRIPT, "llm-payload", "next-request", "compact", "prune");

    private static final String CONTEXT_MODES_USAGE = "[" + String.join("|", CONTEXT_MODES) + "] [path]";

    private static final String CONTEXT_MODES_HINT = String.join("|", CONTEXT_MODES) + " [path]";

    private static final String LAST_REQUEST_NOTE = """
            This is the exact last request sent to the provider, captured right before the call: the \
            session state tail included and the persisted transcript excluded (the store drops the \
            system message and the volatile tails, so it is never what the model sees).""";

    private static final String NEXT_REQUEST_NOTE = """
            Preview of the next request, rebuilt from the persisted transcript and the current \
            system prompt without calling the provider. It is a reconstruction, not a history: the \
            context usage, nudge and memory sections reflect the state right now, and a prune or \
            compaction applied during the turn will shrink it further.""";

    private final SessionStore sessionStore;
    private final AgentSessionService agentSessionService;
    private final PrettyJsonMapper pretty;

    public SessionCommands(final SessionStore sessionStore, final AgentSessionService agentSessionService) {
        this(sessionStore, agentSessionService, null);
    }

    public SessionCommands(
            final SessionStore sessionStore, final AgentSessionService agentSessionService, final JsonMapper mapper) {
        this.sessionStore = sessionStore;
        this.agentSessionService = agentSessionService;
        // null in tests and in a shell built without a mapper: tool schemas then fall back to
        // their toString() instead of failing the dump
        this.pretty = mapper == null ? null : new PrettyJsonMapper(mapper);
    }

    /** Parsed {@code /context} argument: the mode (never null) and the optional file path. */
    record ContextRequest(String mode, String path) {}

    /**
     * Splits the argument on the first space. The leading token is a mode only when it is one of
     * {@link #CONTEXT_MODES}, so the legacy {@code /context out.txt} - and even a file whose name
     * contains spaces - keeps working.
     */
    static ContextRequest parseContext(final String arg) {
        final var trimmed = arg == null ? "" : arg.strip();
        if (trimmed.isEmpty()) {
            return new ContextRequest(TRANSCRIPT, "");
        }
        final var space = trimmed.indexOf(' ');
        final var head = space < 0 ? trimmed : trimmed.substring(0, space);
        for (final var mode : CONTEXT_MODES) {
            if (mode.equals(head.toLowerCase(Locale.ROOT))) {
                return new ContextRequest(
                        mode, space < 0 ? "" : trimmed.substring(space + 1).strip());
            }
        }
        return new ContextRequest(TRANSCRIPT, trimmed);
    }

    /**
     * Modes before any argument is typed, paths as soon as a mode is there - the two coexist so
     * {@code /context out.txt} still completes to files.
     */
    private static final ArgCompletion CONTEXT_COMPLETION = (arg, token, env) -> {
        if (!ArgCompletion.previousToken(arg).isEmpty()) {
            return ArgCompletion.PATH.complete(arg, token, env);
        }
        final var modes = ArgCompletion.fuzzyResult("mode", CONTEXT_MODES, token);
        final var items = new ArrayList<>(modes.items());
        for (final var path :
                ArgCompletion.fuzzyResult("path", env.paths(token), token).items()) {
            if (!items.contains(path)) {
                items.add(path);
            }
        }
        items.sort(Comparator.naturalOrder());
        return ArgCompletion.Result.of(modes.empty() ? "path" : "mode", items);
    };

    @Override
    public List<SlashCommand> commands() {
        return List.of(
                SlashCommand.of(
                        new SlashCommandSpec("/new", null, "start a fresh session", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> {
                            env.session().sessionId(sessionStore.create());
                            env.session().activeAgent("");
                            env.transcript().reset();
                            return ShellOutcome.status(
                                    "new session " + env.session().sessionId());
                        })),
                SlashCommand.of(
                        new SlashCommandSpec("/resume", "<id>", "resume an existing session", SlashGroup.SESSION),
                        ArgCompletion.from("session", ArgCompletion.Env::sessions),
                        this::resume),
                SlashCommand.of(
                        new SlashCommandSpec("/quit", null, "leave the session (or Ctrl-D)", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> ShellOutcome.quit())),
                SlashCommand.of(
                        new SlashCommandSpec("/exit", null, "leave the session", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> ShellOutcome.quit())),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/context",
                                CONTEXT_MODES_USAGE,
                                "dump the transcript or the real LLM payload to stdout or a file",
                                SlashGroup.SESSION),
                        CONTEXT_COMPLETION,
                        SlashAction.sync(this::context)),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/session-id",
                                null,
                                "show the current session identifier and its path",
                                SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> {
                            final var info = agentSessionService.sessionInfo(
                                    env.session().sessionId());
                            return ShellOutcome.status("session " + info.id() + " -> " + info.path());
                        })),
                SlashCommand.of(
                        new SlashCommandSpec(
                                "/session-flush", null, "rewrite the current session to disk", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> ShellOutcome.status(
                                agentSessionService.flush(env.session().sessionId())))),
                SlashCommand.of(
                        new SlashCommandSpec("/clear", null, "clear the conversation", SlashGroup.SESSION),
                        SlashAction.sync((arg, env) -> {
                            env.transcript().clearConversation();
                            env.frontend().clear();
                            return ShellOutcome.none();
                        })));
    }

    private CompletionStage<ShellOutcome> resume(final String arg, final ShellEnv env) {
        if (!arg.isBlank()) {
            return CompletableFuture.completedStage(doResume(arg.strip(), env));
        }
        // most recent first regardless of the store implementation; the id tie-breaker
        // (also descending) makes the ordering absolute so pagination never reshuffles pages
        final var labels = sessionStore.list().stream()
                .sorted(Comparator.comparingLong(SessionStore.SessionInfo::modifiedAt)
                        .reversed()
                        .thenComparing(SessionStore.SessionInfo::id, Comparator.reverseOrder()))
                .map(this::labelOf)
                .toList();
        if (labels.isEmpty()) {
            return CompletableFuture.completedStage(ShellOutcome.status("no session to resume"));
        }
        // pass the whole (absolute-sorted) list to the picker: the picker owns live search across
        // every session and pagination (RESUME_PAGE_SIZE) over the result, so an old session is
        // reachable by typing its title instead of being hidden beyond a fixed-size recent window
        return env.io()
                .pick("resume session", labels, RESUME_PAGE_SIZE)
                .thenApply(selected -> selected.isEmpty()
                        ? ShellOutcome.status("resume cancelled")
                        : doResume(WHITESPACE.split(selected.get())[0], env));
    }

    private String labelOf(final SessionStore.SessionInfo session) {
        final var summary = sessionStore.summary(session.id());
        return session.id() + "  (" + session.sizeBytes() + " bytes)"
                + (summary.isBlank() ? "" : " -- " + SlashRegistry.firstLine(summary, RESUME_SUMMARY_WIDTH));
    }

    private ShellOutcome doResume(final String id, final ShellEnv env) {
        env.session().sessionId(id);
        env.transcript().reset();
        final var markdown = env.transcript().replay(sessionStore.load(id));
        env.frontend().onReplay(markdown);
        return ShellOutcome.status("resumed session " + id);
    }

    private ShellOutcome context(final String arg, final ShellEnv env) {
        final var request = parseContext(arg);
        final var mode = request.mode();
        final var path = request.path();

        // context operations: /context compact|prune triggers the user-level context operation,
        // deferred when a turn is running
        if ("compact".equals(mode) || "prune".equals(mode)) {
            if (!path.isBlank()) {
                return ShellOutcome.status("/context " + CONTEXT_MODES_HINT);
            }
            if (env.session().busy()) {
                final var queued = env.session().runtime().loop().requestUserContextOperation(mode, null, 0, false);
                return ShellOutcome.status(
                        queued
                                ? mode + " scheduled - applied at the next agent checkpoint"
                                : "a context operation is already pending this turn");
            }
            final var sessionId = env.session().sessionId();
            final var runtime = env.session().runtime();
            if ("prune".equals(mode)) {
                final var applied = agentSessionService.prune(sessionId, runtime);
                return ShellOutcome.status(applied.message());
            }
            final var applied = agentSessionService.compact(sessionId, false, runtime);
            return ShellOutcome.status(applied.message());
        }

        final String markdown;
        if ("llm-payload".equals(mode) || "next-request".equals(mode)) {
            final var preview = "next-request".equals(mode);
            final var loop = env.session().runtime().loop();
            final var payload = preview ? loop.previewLlmPayload(env.session().sessionId()) : loop.lastLlmPayload();
            if (payload == null) {
                return ShellOutcome.status(
                        "no LLM request was sent yet in this session - run a turn, or use /context transcript");
            }
            markdown = renderLlmPayload(
                    payload,
                    preview ? NEXT_REQUEST_NOTE : LAST_REQUEST_NOTE,
                    env.session().runtime().usage(),
                    pretty);
        } else {
            markdown = renderTranscript(agentSessionService.context(
                    env.session().sessionId(), env.session().runtime()));
        }
        return emit(markdown, path, env.session().runtime().workdir());
    }

    /**
     * The persisted view: the system prompt plus one line per stored message. Cheap to read but
     * not what the model sees - {@code persistContext} drops the system message and the volatile
     * tails. Use {@code llm-payload} for the real thing.
     */
    static String renderTranscript(final AgentSessionService.ContextDump dump) {
        final var sb = new StringBuilder();
        sb.append("**System prompt**\n\n```text\n").append(dump.systemPrompt()).append("\n```\n");
        sb.append("\n**Session ")
                .append(dump.sessionId())
                .append(" (")
                .append(dump.messages().size())
                .append(" messages)**\n\n");
        for (final var message : dump.messages()) {
            if ("assistant".equals(message.role())
                    && message.content() != null
                    && message.content().isBlank()) {
                continue;
            }
            final var toolName = message.toolName();
            sb.append("- **")
                    .append(message.role())
                    .append(toolName == null ? "" : " (" + toolName + ")")
                    .append("**: ")
                    .append(SlashRegistry.firstLine(String.valueOf(message.content()), 120))
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * The model-facing view: every message with its full content (the volatile tail included),
     * the assistant tool calls with their raw arguments and the wire tool schemas. This is what
     * the user asked for when debugging a surprising answer, so nothing is truncated - a dump of
     * a 100k context is a file, not a scrollback.
     */
    static String renderLlmPayload(
            final AgentLoop.LlmPayload payload,
            final String note,
            final UsageTracker usage,
            final PrettyJsonMapper pretty) {
        final var sb = new StringBuilder();
        sb.append("# LLM payload\n\n").append(note).append("\n\n");
        sb.append("**Session** ")
                .append(payload.sessionId())
                .append(" | **Model** ")
                .append(payload.model())
                .append(" | **Messages** ")
                .append(payload.messages().size())
                .append(" | **Tools** ")
                .append(payload.tools().size())
                .append(" | **Tool choice** ")
                .append(payload.toolChoice())
                .append('\n');
        if (usage != null) {
            final var measurement = usage.lastContextMeasurement();
            final var estimated = measurement.tokens();
            sb.append("\n**Context footprint** ~")
                    .append(estimated)
                    .append(" tokens (")
                    .append(Math.round(usage.contextPercent(estimated)))
                    .append("% of ")
                    .append(usage.usableWindow())
                    .append(" usable, window ")
                    .append(usage.windowOrigin())
                    .append(")\n");
            sb.append("**Measurement source** ")
                    .append(measurement.source())
                    .append("; pending additions ~")
                    .append(measurement.pendingTokens())
                    .append(" tokens\n");
            final var lastReal = usage.lastRealPromptTokens();
            if (lastReal > 0) {
                sb.append("**Last real (provider-reported input)** ")
                        .append(lastReal)
                        .append(" prompt tokens of the last anchored request (calibration ratio ")
                        .append(String.format(Locale.ROOT, "%.2f", usage.calibrationRatio()))
                        .append(")\n");
            } else {
                sb.append(
                        "**Last real (provider-reported input)** no matching request anchor; using the indicated estimate source\n");
            }
        }

        sb.append("\n## Messages (").append(payload.messages().size()).append(")\n\n");
        final var toolNamesByCallId = Message.toolNames(payload.messages());
        var index = 0;
        for (final var message : payload.messages()) {
            index++;
            final var content = message.content() == null ? "" : String.valueOf(message.content());
            sb.append("### ")
                    .append(index)
                    .append(". ")
                    .append(message.role())
                    .append(label(message, toolNamesByCallId))
                    .append(" - ")
                    .append(content.length())
                    .append(" chars\n\n");
            if (!content.isEmpty()) {
                sb.append(fence(content, "text")).append("\n\n");
            }
            if (message.toolCalls() != null) {
                for (final var call : message.toolCalls()) {
                    if (call == null || call.function() == null) {
                        continue;
                    }
                    sb.append("**tool_call** ")
                            .append(call.function().name())
                            .append(call.id() == null ? "" : " [" + call.id() + "]")
                            .append('\n');
                    final var arguments = call.function().arguments();
                    if (arguments != null && !arguments.isBlank()) {
                        sb.append(fence(arguments, "json")).append("\n\n");
                    }
                }
            }
        }

        sb.append("## Tools (")
                .append(payload.tools().size())
                .append(")\n\n")
                .append('~')
                .append(payload.toolsTokens())
                .append(" tokens estimated for all the tool schemas.\n\n");
        for (final var tool : payload.tools()) {
            if (tool == null || tool.function() == null) {
                continue;
            }
            final var function = tool.function();
            sb.append("### ")
                    .append(function.name())
                    .append(" (")
                    .append(tool.type() == null ? "function" : tool.type())
                    .append(")\n\n");
            if (function.description() != null && !function.description().isBlank()) {
                sb.append(function.description()).append("\n\n");
            }
            final var parameters = json(function.parameters(), pretty);
            sb.append("**parameters** (~")
                    .append(TokenEstimator.estimate(parameters))
                    .append(" tokens estimated)\n\n")
                    .append(fence(parameters, "json"))
                    .append("\n\n");
        }
        return sb.toString();
    }

    /** The optional qualifiers of a message header: tool name, call id and error flag. */
    private static String label(final Message message, final Map<String, String> toolNamesByCallId) {
        final var name = Message.toolName(message, toolNamesByCallId);
        final var sb = new StringBuilder();
        if (name != null) {
            sb.append(" (").append(name).append(')');
        }
        if (message.toolCallId() != null) {
            sb.append(" [").append(message.toolCallId()).append(']');
        }
        if (message.others() != null && Boolean.TRUE.equals(message.others().get("is_error"))) {
            sb.append(" [error]");
        }
        return sb.toString();
    }

    private static String json(final Object instance, final PrettyJsonMapper pretty) {
        if (instance == null) {
            return "null";
        }
        if (pretty != null) {
            try {
                return pretty.toString(instance);
            } catch (final RuntimeException ignored) {
                // not serializable by this mapper: the toString() below is still readable
            }
        }
        return String.valueOf(instance);
    }

    /**
     * Fences arbitrary model-produced content: the fence grows past the longest backtick run so
     * a message quoting markdown cannot break out of its block and truncate the dump.
     */
    private static String fence(final String content, final String language) {
        var longest = 0;
        var run = 0;
        for (var i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '`') {
                longest = Math.max(longest, ++run);
            } else {
                run = 0;
            }
        }
        final var ticks = "`".repeat(Math.max(3, longest + 1));
        return ticks + language + "\n" + content + "\n" + ticks;
    }

    /** Stdout when no path is given, a file otherwise - same contract for every mode. */
    private static ShellOutcome emit(final String markdown, final String path, final Path workdir) {
        if (path == null || path.isBlank()) {
            return ShellOutcome.transcript(markdown);
        }
        try {
            final var target = workdir.resolve(path).normalize();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, markdown);
            return ShellOutcome.status("context written to " + target);
        } catch (final IOException e) {
            return ShellOutcome.error("context failed: " + e.getMessage());
        }
    }
}
