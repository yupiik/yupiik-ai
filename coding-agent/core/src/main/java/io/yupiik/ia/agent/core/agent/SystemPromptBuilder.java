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

import io.yupiik.ia.agent.core.configuration.AgentConfiguration;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.context.ContextBudget;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.ModelsResponse;
import io.yupiik.ia.agent.core.maven.MavenRepositoryKnowledgeService;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.session.SessionMetadata;
import io.yupiik.ia.agent.core.skills.SkillLocator;
import io.yupiik.ia.agent.core.todo.TodoStore;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class SystemPromptBuilder {
    private static final Pattern RELATIVE_ROOT_PREFIX = Pattern.compile("^\\.?/");
    private static final Pattern QUESTION_PHRASE =
            Pattern.compile(".*(how (do|can|to|would)|what (about|if)|why (not|do)|where (do|can)).*");
    private static final Pattern CHANGE_VERB =
            Pattern.compile("\\b(implement|fix|add|refactor|create|change|remove|delete|migrate|rewrite|rename|"
                    + "update|modify|patch|write|build|scaffold|port|extract|introduce|enable|disable|wire|simplify|"
                    + "move|split|rework)\\w*\\b");

    /**
     * Files that used to be inlined eagerly into the system prompt as {@code <project-file>} blocks. Removed:
     * README.md/CLAUDE.md are project *content*, not agent instructions - eager inlining of a large README (this
     * project's is ~34 KB) can silently swallow the instruction prefix under {@code capSystemPrompt} (which keeps
     * the tail) and leave the model with only noise. Like opencode, we read project docs on demand with the
     * {@code read} tool instead. AGENTS.md (a real instructions file) is still injected.
     */
    private static final List<String> SIBLING_CONTEXT_FILES = List.of();

    /**
     * Hard ceiling on the assembled system prompt (rules + environment + project AGENTS.md + skills
     * + agent prompt). It is message[0], preserved verbatim through compaction, so unbounded growth
     * here would alone push a request over the window in a deep repository. Tail-kept with a leading
     * marker so the durable instructions appended last (agent prompt, PLAN mode tail) survive.
     */
    private static final int SYSTEM_PROMPT_CAP = 32_000;

    private static final String WORKSPACE_ELISION = "[...leading workspace context elided...]\n";

    private final CodingAgentConfiguration configuration;
    private final MemoryStore memoryStore;
    private final TodoStore todoStore;
    private final PermissionService permissionService;
    private final AgentProvider agentProvider;
    private final ProviderManager providerManager;
    private final TodoPromptResolver todoPromptResolver;
    private final MavenRepositoryKnowledgeService mavenRepositoryKnowledge;
    private final SecretRedactor secretRedactor;
    /** The session's per-tab selection (provider/model); when set the prompt caps follow it. */
    protected volatile ChatSettings settings;

    /**
     * The session's resolved working/project directory, set exactly once when the session runtime is
     * created (never mid-conversation) so the stable system-prompt core stays byte-identical across
     * turns and provider prefix caches keep hitting. Required and immutable.
     */
    private final Path workdir;

    /**
     * The durable {@code <memory>} section of the system prompt, computed once per session - lazily
     * on the first {@link #build(String, String)} - and reused verbatim on every subsequent turn.
     * The current session never re-reads {@code MEMORY.md} after this snapshot is taken: mid-session
     * file writes ({@code memory_write}, extraction) are durable for the <em>next</em> session but
     * never rewrite this section, so the system-prompt prefix stays byte-identical across turns and
     * provider prefix caches keep hitting. A fresh {@link SystemPromptBuilder} is created for every
     * session runtime - including a resume - so the snapshot is naturally rebuilt on reopen.
     * {@code null} until the first build; {@code ""} when memory is disabled or empty.
     */
    private String cachedMemorySection;
    /** Metadata only, frozen for this session just like its durable memory snapshot. */
    private String cachedSkillsCatalog;
    /** Rendered once at successful setup, including the client headers and root-policy explanation. */
    private String retainedContext = "";

    private boolean keepDefaultInstructions = true;
    private boolean contextConfigured;

    /** Configure once before publishing a runtime. Directory context never changes permission rules. */
    public void configureSessionContext(final List<Path> directories, final SessionMetadata.Instructions instructions) {
        if (contextConfigured) {
            throw new IllegalStateException("Session context is immutable");
        }
        final var roots = new LinkedHashSet<Path>();
        for (final var root : directories) {
            if (root == null || !root.isAbsolute() || !root.equals(root.normalize()) || !Files.isDirectory(root)) {
                throw new IllegalArgumentException("Invalid additional directory");
            }
            if (!workdir.equals(root)) {
                roots.add(root);
            }
        }
        // Render once before installation: headers, separators and the cwd explanation count too.
        final var context = new StringBuilder();
        if (!roots.isEmpty()) {
            context.append("\nAdditional workspace directories (context only):\n");
            roots.forEach(root -> context.append("- ").append(root).append('\n'));
            context.append("Relative paths still resolve against ")
                    .append(workdir)
                    .append(
                            ". Directory context grants no access: tool permissions, approvals and ceilings still apply.\n");
        }
        if (instructions != null) {
            context.append("\nClient system instructions (")
                    .append(instructions.mode())
                    .append("):\n");
            if ("append".equals(instructions.mode())) {
                context.append(
                        "These supplement the required agent instructions; those instructions take precedence on conflict.\n");
            }
            instructions.text().forEach(text -> context.append(text).append('\n'));
        }
        final var capturedContext = context.toString();
        final var keepDefaults = instructions == null || "append".equals(instructions.mode());
        final var selection = settings;
        if (requiredContextBudget(capturedContext, keepDefaults, selection) > resolvedSystemPromptCap(selection)) {
            throw new IllegalArgumentException("Session context exceeds system prompt budget");
        }
        retainedContext = capturedContext;
        keepDefaultInstructions = keepDefaults;
        contextConfigured = true;
    }

    /** A later selector must not silently discard accepted session instructions to fit a smaller model budget. */
    public void validateChatSettings(final ChatSettings selection) {
        if (requiredContextBudget(retainedContext, keepDefaultInstructions, selection)
                > resolvedSystemPromptCap(selection)) {
            throw new IllegalArgumentException("Selected model cannot accommodate the session instructions");
        }
    }

    private long requiredContextBudget(final String context, final boolean keepDefaults, final ChatSettings selection) {
        if (context.isEmpty()) {
            return 0; // Preserve legacy capping when no client context was supplied.
        }
        long required =
                keepDefaults ? defaultInstructions(selection, AgentMode.BUILD).length() : 0;
        if (permissionService != null) {
            // A later mode switch or ordinary prompt must still fit, even if setup starts in BUILD.
            final var planDefaults = keepDefaults
                    ? defaultInstructions(selection, AgentMode.PLAN).length()
                    : 0;
            final var planPolicySize =
                    Math.max(planPolicy(true).length(), planPolicy(false).length());
            required = Math.max(required, (long) planDefaults + planPolicySize);
        }
        return required + context.length() + WORKSPACE_ELISION.length();
    }

    /** The owning session's required, immutable working directory. */
    public Path effectiveWorkdir() {
        return workdir;
    }

    /** Attach the owning session's model/provider selection so this builder resolves the right caps. */
    public void attach(final ChatSettings sessionSettings) {
        this.settings = sessionSettings;
    }

    /** The session's effective provider: explicit per-tab override, else the process default. */
    private ProviderConfiguration provider(final ChatSettings sessionSettings) {
        if (providerManager == null) {
            return null;
        }
        if (sessionSettings != null && sessionSettings.providerId() != null) {
            final var byId = providerManager.provider(sessionSettings.providerId());
            if (byId != null) {
                return byId;
            }
        }
        return providerManager.current();
    }

    public SystemPromptBuilder(
            final CodingAgentConfiguration configuration,
            final MemoryStore memoryStore,
            final TodoStore todoStore,
            final PermissionService permissionService,
            final AgentProvider agentProvider,
            final ProviderManager providerManager,
            final TodoPromptResolver todoPromptResolver,
            final MavenRepositoryKnowledgeService mavenRepositoryKnowledge,
            final SecretRedactor secretRedactor,
            final Path workdir) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.configuration = configuration;
        this.memoryStore = memoryStore;
        this.todoStore = todoStore;
        this.permissionService = permissionService;
        this.agentProvider = agentProvider;
        this.providerManager = providerManager;
        this.todoPromptResolver = todoPromptResolver;
        this.mavenRepositoryKnowledge = mavenRepositoryKnowledge;
        this.secretRedactor = secretRedactor;
    }

    protected SystemPromptBuilder(final Path workdir) {
        this.workdir = Objects.requireNonNull(workdir, "workdir");
        this.configuration = null;
        this.memoryStore = null;
        this.todoStore = null;
        this.permissionService = null;
        this.agentProvider = null;
        this.providerManager = null;
        this.todoPromptResolver = null;
        this.mavenRepositoryKnowledge = null;
        this.secretRedactor = null;
    }

    public String build() {
        return build(null);
    }

    /**
     * Builds the system prompt.
     *
     * @param prompt the current (first) user message; may be null
     * @return the assembled system prompt
     */
    public String build(final String prompt) {
        return build(prompt, null);
    }

    private String defaultInstructions(final ChatSettings selection, final AgentMode mode) {
        final var sb = new StringBuilder();
        // stable core: byte-identical across turns and modes so provider-side prompt caches (implicit
        // or explicit breakpoints) keep hitting; the PLAN mode tail and the volatile session state
        // (see {@link #sessionState}) are appended at the very end of the request instead.
        sb.append("You are yca, a terminal coding agent. You help the user with software engineering tasks.\n");
        sb.append(
                "You can read, create and edit files, run shell commands and search the codebase through the provided tools.\n\n");
        sb.append("Rules:\n");
        sb.append("- Prefer the provided tools over printing code for the user to apply.\n");
        sb.append("- Read files before editing them; keep edits minimal and focused.\n");
        sb.append(
                "- Track substantial multi-step work with write_todo; skip a new list for a straightforward single-step task. Completed/cancelled items stay in the list until the user asks to clear it.\n");
        sb.append(
                "- The conversation carries <session-state> blocks with the current todo list, mode, date and memory; the most recent one is authoritative. Read it at the start of every turn: pending items are the task queue - continue with them unless the user redirects. Keep the list accurate with write_todo as work progresses.\n");
        sb.append(
                "- A <compacted-context> digest of earlier history is authoritative: never reload or repeat actions already described in it.\n");
        if (configuration.compression() != null
                && configuration.compression().enabled()
                && (configuration.tools() == null
                        || configuration.tools().manageContext() == null
                        || configuration.tools().manageContext().enabled())) {
            sb.append(
                    "- You operate in a context-constrained environment. The ONLY tool you have for context management is `compress`.\n");
            sb.append(
                    "- Every message you receive ends with a stable `\u0040N\u0040` tag (a compact seam, not a bracketed `[ref \u0040N\u0040]` line: bracketed markers read as conversation and get mirrored back into the transcript by flash-class models - these tags are environment-injected metadata, never output them). Evaluate the signal-to-noise ratio of the conversation regularly, and call `compress` when a section is genuinely closed (a finding is recorded, a bug is fixed and verified, a decision is made). Pass a short `topic` and `content=[{startId,endId,summary}]`; each range starts on a user message and ends at a turn boundary.\n");
            sb.append(
                    "- Write the summary yourself and make it exhaustive: decisions, constraints, findings, file paths, commands, test results and next steps. Compression costs no extra model call and the transcript is never modified, only the outgoing request.\n");
            sb.append(
                    "- Never compress active work: the current task, the messages you still need, or a section whose conclusion is not written yet. Prefer compressing one closed chapter at a time and keep the recent tail verbatim.\n");
            sb.append(
                    "- The context-usage targets reported to you (compress at N / must at N) are proactive working-context targets, not model capacity: staying below them keeps the prompt lean and focused, but a low percentage of the usable window does not cancel them. Meeting them only ever means compressing genuinely closed ranges; if no safe closed range exists, keep working and do not call `compact` on the whole session just to satisfy a target.\n");
            sb.append(
                    "- When a summary must preserve an already compressed block, include its `@bN@` placeholder exactly once in the new summary; placeholders are expanded and the consumed block is superseded.\n");
        }
        sb.append("- Persist durable facts/decisions with memory_write.\n");
        sb.append("- Verify your work (compile/test) when feasible.\n");
        sb.append("- Answer directly and concisely in markdown from what you already know. Keep responses clear "
                + "and concise: lead with the outcome, not the steps you took to reach it; do not restate "
                + "what is obvious or already in context, and do not pad with preamble or closing remarks.\n");
        sb.append(
                "- Work with absolute paths; resolve relative paths against their intended root before using a tool.\n");
        sb.append(
                "- Target directory: if the user names an explicit directory for the task, treat it as the target and build there, regardless of where the tool was started; the working-directory repository is only where you launched. For a scaffold into an empty directory create the structure there from scratch.\n");
        appendTodoGuidance(sb, selection, mode);
        sb.append('\n');
        sb.append("You may invoke a tool only if its exact name appears in the `tools` field of this request. "
                + "Do not invent, abbreviate, or guess tool names: any other name will be rejected. "
                + "If the task needs a capability none of the provided tools offers, tell the user "
                + "and propose the closest available tool instead of calling an unknown one.\n");
        sb.append('\n');
        sb.append("Tool usage policy:\n");
        // opencode harness rules (packages/core/src/plugin/system-prompt): dedicated tools first, shell only as
        // fallback, and never noisy echo/printf separator chaining. The survey-rewrite invitation that used to live
        // here taught the model the forbidden `ls -la && echo "---" && ...` form and was removed (the in-process
        // override mechanism still exists at runtime; it simply is not advertised as a prompt pattern).
        sb.append("- Prefer dedicated tools over shell commands; fall back to the shell when a tool cannot do "
                + "what you need.\n");
        sb.append("- Do not chain shell commands with separators like `echo \"====\";` or `printf '---'`; the "
                + "output becomes noisy in a way that makes the user's side of the conversation worse.\n");
        sb.append("- Maximize use of parallel tool calls: you can call multiple independent tools in one reply, "
                + "so N independent read-only calls cost one round-trip. Always batch them where possible - for "
                + "example start with `read .` + `glob **/*.md` (or any needed reads) together, and only sequence "
                + "a call that depends on a previous result.\n");
        sb.append("- When locating a bug, use a targeted content search that returns useful source and test context "
                + "as well as paths, so you can act on the results without a separate discovery-only round when "
                + "the evidence is already sufficient. Read more when needed for a safe change.\n");
        // Bookkeeping (todo-list maintenance) is cheap locally but each separate tool-only reply costs an extra
        // model round-trip (a fresh reasoning pass). Fold todo updates into the reply that already issues the
        // substantive tool call for that step, and don't spin a dedicated reply just to maintain the list.
        sb.append("- Fold bookkeeping into real work: update the todo list in the same reply as the tool call "
                + "that completes that step, never in a separate reply whose only job is maintaining the list. "
                + "One reply should both act and record, not act then reward with a standalone todo-only round.\n");
        sb.append(
                "- Avoid tiny repeated slices: when you need more context around a hit, read a larger window instead of many 30-line chunks.\n");
        sb.append(
                "- Avoid repeating the same tool with the same parameters once you have useful results: use the result "
                        + "to take the next step (\u2192 pick one match, read that file, then act) instead of searching again in a loop.\n");
        sb.append('\n');
        return sb.toString();
    }

    /** Build system-level instructions, preserving required defaults in append mode. */
    public String build(final String prompt, final AgentConfiguration agent) {
        final var selection = settings;
        validateChatSettings(selection);
        final var cap = resolvedSystemPromptCap(selection);
        final var mode = permissionService == null ? AgentMode.BUILD : permissionService.mode();
        // Override replaces only default prose, not environment/AGENTS/skills/mode policy.
        final var defaults = keepDefaultInstructions ? defaultInstructions(selection, mode) : "";
        final var sb = new StringBuilder(defaults);
        sb.append("Environment:\n");
        sb.append("- Working directory: ").append(workdir).append('\n');
        sb.append("- Platform: ")
                .append(System.getProperty("os.name"))
                .append(' ')
                .append(System.getProperty("os.version"))
                .append('\n');
        sb.append("- Java: ").append(System.getProperty("java.version")).append('\n');
        final var project = projectContext(workdir);
        if (!project.isBlank()) {
            sb.append('\n').append(project).append('\n');
        }
        if (configuration.skills() != null && configuration.skills().enabled()) {
            if (cachedSkillsCatalog == null) {
                cachedSkillsCatalog = SkillLocator.availableSkillsCatalog(configuration, workdir);
            }
            if (!cachedSkillsCatalog.isBlank()) {
                sb.append('\n').append(cachedSkillsCatalog).append('\n');
            }
        }
        if (mavenRepositoryKnowledge != null) {
            final var sessionSkills = mavenRepositoryKnowledge.sessionSkillBlocks(workdir);
            if (!sessionSkills.isEmpty()) {
                sb.append('\n');
                for (final var block : sessionSkills) {
                    sb.append(block).append('\n');
                }
            }
        }
        if (agent != null && agentProvider != null) {
            final var agentPrompt = agentProvider.resolvePrompt(agent);
            if (agentPrompt != null && !agentPrompt.isBlank()) {
                sb.append("\n\n<agent name=\"")
                        .append(agent.name())
                        .append("\">\n")
                        .append(agentPrompt)
                        .append("\n</agent>\n");
            }
        }
        // durable memory lives in the stable prefix (message[0]) as a session-frozen snapshot built
        // once on the first build and reused verbatim on every turn. The current session never
        // re-reads MEMORY.md after this snapshot, so mid-session file writes (memory_write, extraction)
        // are durable for the next session but never rewrite this section - the system-prompt prefix,
        // and the provider prompt cache over it, stay byte-identical across turns. There is no
        // mid-session memory refresh: a memory_write persists directly to disk and is already present
        // in this turn's conversation.
        final var memory = cachedMemorySection(prompt);
        if (memory != null && !memory.isBlank()) {
            sb.append('\n').append(memory).append('\n');
        }
        final var modeTail = mode == AgentMode.PLAN ? planPolicy(taskImpliesChanges(prompt)) : "";
        if (retainedContext.isEmpty()) {
            return capSystemPrompt(sb.append(modeTail).toString(), cap);
        }
        // Only workspace content is tail-capped; accepted client text, defaults and mode policy survive intact.
        final var workspaceBudget = cap - defaults.length() - retainedContext.length() - modeTail.length();
        return defaults + capWorkspace(sb.substring(defaults.length()), workspaceBudget) + retainedContext + modeTail;
    }

    private String capWorkspace(final String workspace, final int maxChars) {
        if (maxChars < WORKSPACE_ELISION.length()) {
            throw new IllegalArgumentException("Session context exceeds system prompt budget");
        }
        if (workspace.length() <= maxChars) {
            return workspace;
        }
        final var tailSize = maxChars - WORKSPACE_ELISION.length();
        return WORKSPACE_ELISION + workspace.substring(workspace.length() - tailSize);
    }

    private String planPolicy(final boolean changes) {
        final var sb = new StringBuilder();
        sb.append('\n');
        sb.append(
                "Mode: PLAN. Repository mutations are forbidden by default: no file creation, edits or deletions, and no state-changing shell commands - write_todo is the only state-changing tool allowed.\n");
        final var hints = configuration.permissions() != null
                        && configuration.permissions().bash() != null
                ? configuration.permissions().bash().readOnlyPromptHints()
                : null;
        if (hints != null && !hints.isBlank()) {
            sb.append(hints).append('\n');
        }
        if (changes) {
            sb.append(
                    "Todos are the plan, not a progress report: every item stays 'pending' - statuses only advance in build mode, once a step is actually executed.\n");
            sb.append(
                    "Analyze the codebase and deliver a detailed, concrete implementation plan (files to touch, steps, approach and trade-offs, risks), ready for the user to review before anything is applied.\n");
            sb.append(
                    "Record the plan with write_todo and keep the list in sync with the discussion: whenever a follow-up changes the plan (scope, steps, approach, trade-offs), update it in the same turn so it always mirrors the latest agreed plan. Completed or cancelled items from earlier build sessions are progress history: keep them.\n");
        } else {
            // informational request (explain/what/how/describe/...): no todo ceremony, no forced plan
            // deliverable - explore as needed and answer directly, like the non-plan harnesses do.
            sb.append(
                    "The request is informational: explore the codebase as needed and answer directly - no plan or todo list is required.\n");
        }
        return sb.toString();
    }

    /**
     * Renders the volatile session state (mode, date, todo list): injected on the
     * wire as a trailing message of the request - never part of the system prompt -
     * so mutating state never invalidates the cached prefix. The mode line is the authoritative
     * per-request signal: it is re-rendered fresh regardless of how the mode was reached (slash
     * command, startup flag or session restore), so the model never has to infer it from stale
     * conversation artifacts. The durable memory is {@link #memorySection(String) part of the
     * stable system prefix}, built once per session and never re-appended mid-session: it is
     * invisible to the current session after the prefix is assembled, so nothing is re-sent
     * here. Callers enclose the result in {@code <session-state>} tags.
     *
     * @param prompt the current user prompt used to select relevant memories; may be null
     * @return the rendered session state, without the enclosing tags
     */
    public String sessionState(final String prompt) {
        final var sb = new StringBuilder();
        sb.append("Date: ").append(LocalDate.now()).append('\n');
        if (permissionService != null) {
            sb.append("Mode: ").append(permissionService.mode().name()).append('\n');
            if (permissionService.mode() == AgentMode.PLAN) {
                sb.append(
                        "Plan mode: if this turn changes the plan, call write_todo to sync the todo list before answering.\n");
            }
        }
        sb.append("Current todo list:\n")
                .append(todoStore == null ? "(empty)" : todoStore.render())
                .append('\n');
        return sb.toString();
    }

    /**
     * Renders only the part of the session state that is likely to have changed since the last
     * request: the todo list. Date and mode are stable within a turn/session and are already
     * covered by the stable prefix or the dedup of {@code appendStateIfChanged}; the memory is
     * part of the session-initial stable prefix and never re-appended, so it is absent here too.
     * Callers that want the full state use {@link #sessionState(String)} (first tail of a turn).
     */
    public String sessionStateDelta() {
        final var sb = new StringBuilder();
        sb.append("Date: ").append(LocalDate.now()).append('\n');
        if (permissionService != null) {
            sb.append("Mode: ").append(permissionService.mode().name()).append('\n');
        }
        if (todoStore != null) {
            final var rendered = todoStore.render(5); // wire view: pending + 5 most recent done
            if (!rendered.isBlank() && !"(empty)".equals(rendered)) {
                sb.append("Current todo list:\n").append(rendered).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * The durable memory section, {@code "&lt;memory&gt;...&lt;/memory&gt;"} (capped and prompt-ranked).
     * Empty when memory is disabled or not stored.
     * <p>
     * The ranking query is the user prompt enriched with the current todo list: durable facts
     * relevant to the active plan surface even when the last user message is terse.
     */
    public String memorySection(final String prompt) {
        if (memoryStore == null) {
            return "";
        }
        final var query = new StringBuilder(prompt == null ? "" : prompt);
        if (todoStore != null) {
            final var todos = todoStore.render();
            if (!todos.isBlank() && !"(empty)".equals(todos)) {
                query.append(' ').append(todos);
            }
        }
        return memoryStore.promptSection(
                query.toString(), resolvedMemoryBudget(), workdir, currentPaths(query.toString()));
    }

    /**
     * The session-frozen {@code <memory>} section: computed once on the first {@link #build} call
     * (with that turn's prompt) and cached for the session lifetime. Every subsequent turn reuses
     * this exact section - {@code MEMORY.md} is never re-read after the snapshot, so mid-session
     * writes cannot rewrite the prompt's memory. Returns the cached section or {@code ""}.
     * Thread-safe: only the first caller computes; concurrent first calls may both compute, the
     * winner wins (idempotent - same store, deterministic ranking).
     */
    private String cachedMemorySection(final String prompt) {
        var cached = cachedMemorySection;
        if (cached == null) {
            cached = memorySection(prompt);
            cachedMemorySection = cached;
        }
        return cached;
    }

    /**
     * Extracts candidate code-path hints (relative {@code src/...} paths, {@code .java}/etc. file
     * names, package segments) from the current turn's text, passed to the memory ranker so bullets
     * anchored to the code being worked on are surfaced. Best-effort: a turn with no obvious paths
     * yields an empty list (ranking falls back to topic/sentence relevance).
     */
    private static List<String> currentPaths(final String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        final var p = prompt;
        final var matcher = PathRef.PATTERN.matcher(p);
        while (matcher.find()) {
            final var hit = matcher.group().strip();
            if (hit.length() >= 4 && !hit.contains("://") && !hit.contains(" ") && !hit.endsWith(".")) {
                out.add(
                        hit.startsWith("/") || hit.startsWith("./")
                                ? RELATIVE_ROOT_PREFIX.matcher(hit).replaceFirst("")
                                : hit);
            }
        }
        return out.stream().distinct().limit(8).toList();
    }

    private static final class PathRef {
        // matches relative source paths / file names (src/main/App.java, package/Thing.java, build.xml),
        // with a word-boundary start and a known source extension; avoids URLs and quoted prose.
        private static final Pattern PATTERN = Pattern.compile(
                "(?<![\\w/.])(?:\\./)?(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\\.(?:java|kt|kts|scala|groovy|xml|properties|yml|yaml|json|js|ts|tsx|jsx|css|scss|md|gradle|toml|adoc|sql)");
    }

    /**
     * The underlying memory store, or {@code null} when memory is disabled.
     */
    public MemoryStore memoryStore() {
        return memoryStore;
    }

    /**
     * Memory injection budget: when {@code memory.maxRatio > 0} the configured
     * {@code memory.maxChars} is capped by {@code maxRatio} of the resolved system
     * prompt cap (so a small context window does not waste its budget on memory),
     * and a budget below 1000 chars disables memory entirely (auto-off: extraction
     * is skipped too, the store keeps the facts untouched).
     */
    public int resolvedMemoryBudget() {
        if (memoryStore == null) {
            return 0;
        }
        final var memory = configuration.memory();
        if (!memory.enabled()) {
            return 0;
        }
        final var maxChars = Math.max(0, memory.maxChars());
        if (memory.maxRatio() <= 0) {
            return maxChars;
        }
        final var ratioBudget = (int) Math.floor(resolvedSystemPromptCap() * memory.maxRatio());
        final var budget = Math.min(maxChars, ratioBudget);
        return budget < 1_000 ? 0 : budget;
    }

    /**
     * Resolves the effective system prompt max character budget for the current provider/model, following the
     * cascade: per-model {@code behavior.systemPromptMaxChars} > per-provider {@code tuning.systemPromptMaxChars} >
     * the global {@code llm.systemPromptMaxChars} > a value derived from the provider context window (big windows keep
     * {@link #SYSTEM_PROMPT_CAP}, small ones shrink to leave the usable input budget room for the messages and tools).
     * The derived value reserves the declared output budget from the window (see {@link ContextBudget}), so a model
     * declaring a large completion budget gets a proportionally smaller prompt.
     */
    public int resolvedSystemPromptCap() {
        return resolvedSystemPromptCap(settings);
    }

    private int resolvedSystemPromptCap(final ChatSettings settings) {
        final var provider = providerManager == null ? null : provider(settings);
        final var model = provider == null
                ? null
                : (settings != null
                                && settings.model() != null
                                && !settings.model().isBlank()
                        ? settings.model()
                        : provider.model());
        final var perModel =
                provider == null || model == null ? 0 : providerManager.systemPromptMaxChars(provider, model);
        if (perModel > 0) {
            return perModel;
        }
        final var global = configuration == null || configuration.llm() == null
                ? 0
                : configuration.llm().systemPromptMaxChars();
        if (global > 0) {
            return global;
        }
        // effective window: per-model override first, then provider value (both explicit); 0 = unknown
        final var window = provider == null
                ? 0
                : model == null ? provider.contextWindow() : providerManager.contextWindowOverride(provider, model);
        if (window <= 0) {
            return SYSTEM_PROMPT_CAP;
        }
        // mirror UsageTracker.usableWindow(): reserve the declared output cap (per-model then provider), else
        // min(20k, window / 4); detected values are not consulted here since the prompt is built before the
        // /v1/models lookup and only an explicit window drives this derivation
        final var outputOverride = provider == null ? 0 : providerManager.outputTokensOverride(provider, model);
        final var declaredOutput =
                outputOverride > 0 ? outputOverride : (provider == null ? 0 : provider.outputTokens());
        // both figures are explicit configuration, hence absolute hard limits for the budget
        final var usable = ContextBudget.usableWindow(
                new ModelsResponse.TokenCount(window, true, ModelsResponse.SourceType.CONFIGURED, "provider-config"),
                declaredOutput > 0
                        ? new ModelsResponse.TokenCount(
                                declaredOutput, true, ModelsResponse.SourceType.CONFIGURED, "provider-config")
                        : null);
        // budget roughly half of the usable input for the system prompt, ~4 chars per token, clamped to the hard cap
        return Math.max(2_000, Math.min(SYSTEM_PROMPT_CAP, (usable / 2) * 4));
    }

    /**
     * Keeps the assembled system prompt within {@code maxChars} characters, trimming the
     * front (the generic preamble) and keeping the tail (environment, project context, skills,
     * agent instructions and the PLAN mode tail) which carries the most load-bearing durable instructions.
     */
    private static String capSystemPrompt(final String prompt, final int maxChars) {
        if (prompt.length() <= maxChars) {
            return prompt;
        }
        return "[...system prompt exceeded the " + maxChars
                + " char budget; " + (prompt.length() - maxChars) + " leading chars elided...]\n"
                + prompt.substring(prompt.length() - maxChars);
    }

    /** The global {@code yca.todos.guidance}, or {@code auto} when unset. */
    private String globalGuidance() {
        if (configuration == null || configuration.todos() == null) {
            return "auto";
        }
        final var guidance = configuration.todos().guidance();
        return guidance == null || guidance.isBlank() ? "auto" : guidance;
    }

    private void appendTodoGuidance(final StringBuilder sb, final ChatSettings selection, final AgentMode agentMode) {
        if (todoPromptResolver == null) {
            return;
        }
        final var provider = provider(selection);
        final var baseUrl = provider == null ? null : provider.baseUrl();
        // a provider configuring its own guidance wins; the providers configuring none inherit the
        // global yca.todos.guidance (blank there means the usual 'auto' family detection)
        final var mode = provider != null && provider.todoPromptConfigured() ? provider.todoPrompt() : globalGuidance();
        final var guidance = todoPromptResolver.resolve(mode, baseUrl, agentMode);
        if (!guidance.isBlank()) {
            sb.append("<todo-guidance>\n").append(guidance).append("\n</todo-guidance>\n");
        }
    }

    private String projectContext(final Path base) {
        final var sb = new StringBuilder();
        for (final var path : agentsFiles(base)) {
            if (path == null) {
                continue;
            }
            append(sb, "AGENTS.md", path);
        }
        for (final var name : SIBLING_CONTEXT_FILES) {
            final var path = base.resolve(name);
            if (Files.isRegularFile(path)) {
                append(sb, name, path);
            }
        }
        return sb.toString();
    }

    private void append(final StringBuilder sb, final String label, final Path path) {
        try {
            var content = Files.readString(path);
            if (secretRedactor != null) {
                // never let a hardcoded key in AGENTS.md/README/skills reach the provider
                content = secretRedactor.redact(content, null);
            }
            sb.append("<project-file name=\"")
                    .append(label)
                    .append("\" path=\"")
                    .append(path.toAbsolutePath().normalize())
                    .append("\">\n")
                    .append(content)
                    .append("\n</project-file>\n");
        } catch (final IOException e) {
            // skip unreadable
        }
    }

    private List<Path> agentsFiles(final Path base) {
        final var found = new ArrayList<Path>();
        var dir = base;
        while (dir != null) {
            final var candidate = dir.resolve("AGENTS.md");
            if (Files.isRegularFile(candidate)) {
                found.add(candidate);
                break;
            }
            dir = dir.getParent();
        }
        final var global = firstGlobalAgentsFile();
        if (global != null) {
            found.add(global);
        }
        return found;
    }

    private Path firstGlobalAgentsFile() {
        for (final var global : globalAgentsFiles()) {
            if (global != null) {
                return global;
            }
        }
        return null;
    }

    private List<Path> globalAgentsFiles() {
        final var home = configuration.resolveHome();
        final var list = new ArrayList<Path>();
        addIfRegular(list, Path.of(configuration.resolveStorageHome()).resolve("AGENTS.md"));
        addIfRegular(list, Path.of(home, ".config", "opencode", "AGENTS.md"));
        addIfRegular(list, Path.of(home, ".claude", "AGENTS.md"));
        return list;
    }

    /**
     * Conservative heuristic deciding whether the user request implies code changes (plan mandate) or is
     * purely informational (answer directly). Change-verbs anywhere in a short prompt win; anything else -
     * explain/what/how/describe/overview/questions - is informational. Conservative by design: an ambiguous
     * prompt falls through to "informational", which is the cheaper wrong call (the model can still plan if
     * the user asks to).
     */
    private static boolean taskImpliesChanges(final String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        final var lower = prompt.toLowerCase();
        final var matcher = CHANGE_VERB.matcher(lower);
        while (matcher.find()) {
            // questions about *how to* change (how do I add X?) are still informational; bare
            // imperatives ("fix X", "add Y") imply work
            final var before = lower.substring(Math.max(0, matcher.start() - 18), matcher.start());
            if (!QUESTION_PHRASE.matcher(before).matches()) {
                return true;
            }
        }
        return false;
    }

    private static void addIfRegular(final List<Path> list, final Path path) {
        if (Files.isRegularFile(path)) {
            list.add(path);
        }
    }
}
