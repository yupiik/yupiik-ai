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
package io.yupiik.ia.agent.core.tools.builtin;

import static io.yupiik.ia.agent.core.tools.Schemas.str;
import static io.yupiik.ia.agent.core.tools.ToolArgs.arg;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.memory.MemoryProductionPolicy;
import io.yupiik.ia.agent.core.memory.MemoryStore;
import io.yupiik.ia.agent.core.tools.EmbeddedTool;
import io.yupiik.ia.agent.core.tools.Schemas;
import io.yupiik.ia.agent.core.tools.ToolArgs;
import io.yupiik.ia.agent.core.tools.ToolCategory;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class MemoryWriteTool implements EmbeddedTool {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private final MemoryStore memoryStore;
    private final MemoryProductionPolicy memoryProductionPolicy;

    public MemoryWriteTool(final MemoryStore memoryStore) {
        this(memoryStore, null);
    }

    public MemoryWriteTool(final MemoryStore memoryStore, final MemoryProductionPolicy memoryProductionPolicy) {
        this.memoryStore = memoryStore;
        this.memoryProductionPolicy = memoryProductionPolicy;
    }

    @Override
    public String name() {
        return "memory_write";
    }

    @Override
    public String description(final Path workdir) {
        return "Persist a durable fact, decision or user preference to memory (global or project scope). "
                + "By default the fact is stored as an explicit keep directive (importance 5, never auto-pruned); "
                + "set keep=false for an ordinary lower-priority observation.";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.AGENT;
    }

    @Override
    public Map<String, Object> schema() {
        return Schemas.object(
                Map.of(
                        "fact", str("The fact to remember, one sentence."),
                        "scope", str("`global` or `project`, default `project`."),
                        "keep",
                                Schemas.bool("Whether this is an explicit keep directive (default true): importance 5, "
                                        + "bounded-no-prune. false stores it as a normal p2 observation."),
                        "p",
                                Schemas.intProp(
                                        "Optional explicit importance weight 1..5 (overrides keep). 5 = keep directive."),
                        "topic", str("Optional space-separated topic keywords to aid retrieval (eg. `maven build`)."),
                        "path",
                                str(
                                        "Optional relative code path/package the fact is anchored to (eg. `src/main/App.java`).")),
                "fact");
    }

    @Override
    public ToolResult execute(final Map<String, Object> args, final ToolContext context) {
        final var fact = arg(args, "fact");
        if (fact == null || fact.isBlank()) {
            return ToolResult.error("fact is required");
        }
        final var sessionId = context == null ? null : context.sessionId();
        if (memoryProductionPolicy != null && sessionId != null && !memoryProductionPolicy.enabled(sessionId)) {
            return ToolResult.ok("memory production is disabled for this session - not stored");
        }
        final var global = "global".equalsIgnoreCase(arg(args, "scope") == null ? "project" : arg(args, "scope"));
        // default to an explicit keep directive (p5); keep=false or an explicit p override downgrades it.
        // (optBool does not honor its default when the key is absent, so default the absence here)
        final var keep = !args.containsKey("keep") || ToolArgs.optBool(args, "keep", true);
        final long explicitP = ToolArgs.optInt(args, "p", 0);
        final int p = explicitP >= 1 && explicitP <= 5 ? (int) explicitP : (keep ? 5 : 2);
        final var topic = splitTopic(arg(args, "topic"));
        final var path = optional(arg(args, "path"));
        final var stored = MemoryStore.withMeta(p, topic, path, fact.strip());
        if (stored == null) {
            return ToolResult.error("fact is required");
        }
        final var added = memoryStore.append(global, List.of(stored), context.projectDir());
        return added.isEmpty()
                ? ToolResult.ok("Already remembered (or skipped).")
                : ToolResult.ok("Remembered (" + (global ? "global" : "project") + "): " + added.get(0));
    }

    private static List<String> splitTopic(final String topic) {
        if (topic == null || topic.isBlank()) {
            return List.of();
        }
        final var tokens = WHITESPACE.split(topic.trim());
        return tokens.length == 0 ? List.of() : List.of(tokens);
    }

    private static String optional(final String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
