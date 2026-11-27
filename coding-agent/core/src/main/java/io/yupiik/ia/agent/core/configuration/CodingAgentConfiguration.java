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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.nio.file.Path;
import java.util.List;

@RootConfiguration("yca")
@JsonModel
@ApplicationScoped
public record CodingAgentConfiguration(
        @Property(
                defaultValue = "\"~/.yupiik/coding-agent\"",
                documentation = "Storage root for sessions, memory, todos and the global config file.")
        String storageHome,

        @Property(
                documentation =
                        "Home/root used to resolve the global (user-level) context locations such as ~/.config/opencode and ~/.claude (AGENTS.md, skills). Defaults to the JVM user.home.")
        String home,

        @Property(
                defaultValue = "\".\"",
                documentation =
                        "Working directory from which project files (AGENTS.md hierarchy, skills) are discovered. Defaults to the current directory.")
        String workingDir,

        @Property(
                documentation =
                        "Target project directory the current task is about (where project files should be created or read). Defaults to the working directory. When it points at a fresh/empty directory, the agent behaves like a neutral scaffolder: it does not index an unrelated code graph nor inject an unrelated repository README.")
        String projectDir,

        @Property(
                documentation =
                        "OpenAI compatible provider settings. Either a list of providers (providers + defaultProvider) or the convenience flat scalars below acting as a single implicit 'default' provider.")
        ProviderListConfiguration provider,

        @Property(
                documentation =
                        "Model overrides for compaction and pruning. Empty values fall back to the provider model.")
        ModelConfiguration model,

        @Property(documentation = "LLM client resilience settings: retries, rate limiting and concurrency.")
        LlmConfiguration llm,

        @Property(documentation = "Context compaction settings.")
        CompactionConfiguration compaction,

        @Property(documentation = "Context pruning settings (applied before compaction).")
        PruningConfiguration pruning,

        @Property(
                documentation =
                        "Model-driven context compression settings (compress tool, range summaries and usage nudges).")
        CompressConfiguration compression,

        @Property(documentation = "Auto memory settings.") MemoryConfiguration memory,

        @Property(documentation = "Todo list settings (guidance injection and auto-continuation on unfinished work).")
        TodoConfiguration todos,

        @Property(documentation = "Tool output optimization settings (rtk style token reduction).")
        OptimizeConfiguration optimize,

        @Property(documentation = "Tool permission settings.")
        PermissionsConfiguration permissions,

        @Property(
                documentation =
                        "OS sandbox policy. Only trusted global configuration or CLI/environment/system settings may configure it. Use --sandbox true|false in any mode. See link:/yupiik-ai/coding-agent/sandboxing/overview.html[Sandboxing] and link:/yupiik-ai/coding-agent/sandboxing/helper.html[Sandbox helper] for installation, backend requirements and limitations.")
        SandboxConfiguration sandbox,

        @Property(documentation = "MCP integration settings.")
        McpConfiguration mcp,

        @Property(documentation = "Embedded Maven repository knowledge indexer settings (tool + skill loading).")
        MavenRepositoryKnowledgeConfiguration mavenRepositoryKnowledge,

        @Property(documentation = "Structural code graph settings (symbol/call index of the project).")
        StructuralCodeGraphConfiguration structuralCodeGraph,

        @Property(documentation = "Session retention settings (age based cleanup and compression).")
        SessionConfiguration sessions,

        @Property(documentation = "Skills loading settings.")
        SkillsConfiguration skills,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Custom agents. Each agent is mainly a prompt: inline text, or a file path whose content is used. Select one with --agent on run, /agent in the TUI, or a @<name> prompt prefix.")
        List<AgentConfiguration> agents,

        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.AgentMode.PLAN",
                documentation =
                        "Agent access mode: build (all tools, any action) or plan (read-only tools only). Can be switched at runtime with /mode.")
        AgentMode mode,

        @Property(documentation = "Shared HTTP client settings used by webfetch, websearch and MCP servers over HTTP.")
        HttpClientConfiguration httpClient,

        @Property(documentation = "Agent tool settings (built-in tool server and web search).")
        ToolsConfiguration tools,

        @Property(documentation = "Sub-agent settings (spawn_subagent, check_subagents).")
        SubagentConfiguration subagents,

        @Property(
                defaultValue = "Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 2)",
                documentation =
                        "Thread count of the shared application scheduler (ScheduledExecutorService) used by the code graph and the TUI git branch poller. Defaults to the CPU count clamped to 1..2 so both consumers get a thread on typical machines; set 1 to stay on a single thread.")
        int schedulerThreads,

        @Property(documentation = "Metrics display settings (LLM calls, thinking duration, tool duration).")
        MetricsConfiguration metrics,

        @Property(
                defaultValue = "10000",
                documentation =
                        "Maximum line length in characters the TUI syntax highlighter tokenizes. Longer lines render as a single plain un-styled span to avoid pathological cost.")
        int maxLineLength,

        @Property(
                defaultValue = "java.util.List.of(\"continue\", \"go\")",
                documentation =
                        "Prompts that resume the previous turn instead of sending a message: the agent loop is re-run on the existing history with no user message added or persisted (zero token overhead). Matching is case-insensitive on the stripped input.")
        List<String> resumeAliases,

        @Property(documentation = "Slash command tunables (e.g. side questions).")
        SlashCommandConfiguration slashcommands,

        @Property(documentation = "Interactive prompt line rendering (CLI REPL).")
        PromptTemplateConfiguration prompt,

        @Property(
                documentation =
                        "Speaker labels rendered in the terminal transcript headers (CLI, TUI, run). UI only: the LLM conversation always keeps its hardcoded user/assistant roles regardless of these templates.")
        LabelsConfiguration labels,

        @Property(
                documentation =
                        "java.util.logging settings: root level, record decoration and whether the runtime takes over System.out/System.err so nothing can corrupt the interactive prompt.")
        LoggingConfiguration logging,

        @Property(documentation = "External-program hook callbacks bound to events (see the hooks reference).")
        HooksConfiguration hooks,

        @Property(documentation = "OS turn-end notifications (opt-in, disabled by default).")
        NotificationsConfiguration notifications,

        @Property(documentation = "Secret redaction: detect local secrets and keep them off the LLM wire.")
        SecretRedactionConfiguration secrets,

        @Property(documentation = "Shared presentation settings for CLI, TUI and web.")
        ViewConfiguration view) {

    /** Whether the typed input is a resume alias (see {@code yca.resumeAliases}). */
    public boolean isResumeAlias(final String input) {
        if (input == null || resumeAliases == null) {
            return false;
        }
        final var stripped = input.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        for (final var alias : resumeAliases) {
            if (alias != null && alias.strip().equalsIgnoreCase(stripped)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Expands a leading {@code ~/} to the JVM user home ({@link System#getProperty user.home}),
     * leaving the value unchanged otherwise. Central home expansion for every configured path that
     * accepts a {@code ~/...} shorthand (storage home, home root, env files, Maven settings, local
     * repository, agent/secret files): call this instead of re-implementing the expansion inline. A
     * bare {@code ~} (no trailing slash) or null/blank is returned as-is.
     *
     * @param value the configured path, may be null
     * @return {@code value} with a leading {@code ~/} replaced by {@code user.home}, else {@code value}
     */
    public static String expandHome(final String value) {
        if (value != null && value.startsWith("~/")) {
            return System.getProperty("user.home") + value.substring(1);
        }
        return value;
    }

    public String resolveStorageHome() {
        var home = storageHome == null || storageHome.isBlank() ? "~/.yupiik/coding-agent" : storageHome;
        return expandHome(home);
    }

    public Path resolveWorkingDir() {
        final var value = workingDir == null || workingDir.isBlank() ? "." : workingDir;
        return Path.of(value).toAbsolutePath().normalize();
    }

    public Path resolveProjectDir() {
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).toAbsolutePath().normalize();
        }
        return resolveWorkingDir();
    }

    public String resolveHome() {
        final var value = home == null || home.isBlank() ? System.getProperty("user.home") : home;
        return expandHome(value);
    }
}
