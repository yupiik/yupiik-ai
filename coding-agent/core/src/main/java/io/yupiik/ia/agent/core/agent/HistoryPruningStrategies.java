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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.ToolCall;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Zero-LLM history pruning strategies (dedup, supersede, purge) applied to the conversation before
 * the context budget check. Pure list transforms: each strategy mutates the passed {@code messages}
 * list in place and returns the number of changes it made.
 */
public class HistoryPruningStrategies {
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;

    public HistoryPruningStrategies(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this.configuration = configuration;
        this.mapper = mapper;
    }

    /**
     * Applies the enabled zero-LLM pruning strategies in their documented order and returns the
     * kinds of the strategies that changed the history, in execution order.
     */
    public List<String> applyZeroLlmStrategies(final List<Message> messages) {
        final var p = configuration.pruning();
        final var applied = new ArrayList<String>(4);
        if (p.deduplicateToolCalls() && deduplicateToolCalls(messages) > 0) {
            applied.add("dedup");
        }
        if (p.supersedeOverwrittenWrites() && supersedeOverwrittenWrites(messages) > 0) {
            applied.add("supersede");
        }
        if (p.purgeFailedToolInputs() && purgeFailedToolInputs(messages) > 0) {
            applied.add("purge-errors");
        }
        if (p.purgeDeliveredSubagents() && purgeDeliveredSubagents(messages) > 0) {
            applied.add("purge-subagents");
        }
        return applied;
    }

    /** Tools whose calls/results are never deduplicated, superseded or input-purged (state/decisions). */
    private static final Set<String> PROTECTED_CONTEXT_TOOLS =
            Set.of("write_file", "edit_file", "apply_patch", "todo_write", "todo_read", "todo_update", "memory_write");

    /** Write tools whose inputs can be superseded by a later read of the same path. */
    private static final Set<String> WRITE_CONTEXT_TOOLS = Set.of("write_file", "edit_file", "apply_patch");

    /** Normalizes tool-call arguments for signature comparison (sorted JSON or raw stripped form). */
    public String normalizedArguments(final String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        try {
            final var json = mapper.read(Map.class, new StringReader(arguments));
            final var sorted = new TreeMap<String, Object>();
            if (json != null) {
                sorted.putAll(((Map<?, ?>) json)
                        .entrySet().stream()
                                .collect(
                                        LinkedHashMap<String, Object>::new,
                                        (collected, entry) ->
                                                collected.put(String.valueOf(entry.getKey()), entry.getValue()),
                                        Map::putAll));
            }
            return String.valueOf(sorted);
        } catch (final RuntimeException e) {
            // not JSON (eg. a freeform text argument): keep the raw form, dedup still works on exact matches
            return arguments.strip();
        }
    }

    /** Deduplicates identical tool calls keeping the latest occurrence. Returns the number of replacements. */
    public int deduplicateToolCalls(final List<Message> messages) {
        // last index per signature (excluding protected tools); signatures are resolved across the
        // whole list so identical calls far apart collapse too
        final var lastBySignature = new LinkedHashMap<String, Integer>();
        // callId -> index of its tool result, for O(1) replacement in the second pass
        final var resultByCallId = new HashMap<String, Integer>();
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if ("tool".equals(message.role()) && message.toolCallId() != null) {
                resultByCallId.putIfAbsent(message.toolCallId(), i);
            }
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call == null
                        || call.function() == null
                        || PROTECTED_CONTEXT_TOOLS.contains(call.function().name())) {
                    continue;
                }
                final var signature = call.function().name()
                        + '\n'
                        + normalizedArguments(call.function().arguments());
                lastBySignature.put(signature, i);
            }
        }
        var replaced = 0;
        // second pass: replace the tool result of any assistant call whose signature was seen later
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call == null
                        || call.function() == null
                        || PROTECTED_CONTEXT_TOOLS.contains(call.function().name())) {
                    continue;
                }
                final var signature = call.function().name()
                        + '\n'
                        + normalizedArguments(call.function().arguments());
                final var last = lastBySignature.get(signature);
                if (last != null && last != i) {
                    // replace the matching tool result (same call id) with a marker; the assistant
                    // call itself is left in place to keep the call/result pairing unambiguous
                    final var j = resultByCallId.get(call.id());
                    if (j != null && j > i) {
                        messages.set(
                                j,
                                Message.tool(
                                        call.id(),
                                        "[duplicate tool call pruned - same tool and arguments as the last call, keeping the latest output]"));
                        replaced++;
                    }
                }
            }
        }
        return replaced;
    }

    /** Replaces write inputs overwritten by a later read of the same file, keeping the write confirmation. */
    public int supersedeOverwrittenWrites(final List<Message> messages) {
        // track read paths seen after this position; a write whose path is read later is superseded
        final var readsAfter = new HashSet<String>();
        var replaced = 0;
        for (var i = messages.size() - 1; i >= 0; i--) {
            final var message = messages.get(i);
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call == null || call.function() == null) {
                    continue;
                }
                final var name = call.function().name();
                if ("read".equals(name) || "grep".equals(name) || "glob".equals(name)) {
                    final var path = toolFilePath(call);
                    if (path != null) {
                        readsAfter.add(path);
                    }
                } else if (WRITE_CONTEXT_TOOLS.contains(name)
                        && toolFilePath(call) != null
                        && readsAfter.contains(toolFilePath(call))) {
                    // only the input is superseded; the (small) confirmation output is kept
                    final var newCall = new ToolCall(
                            call.id(),
                            call.type(),
                            new ToolCall.FunctionCall(
                                    call.function().name(),
                                    "[write superseded by later read - file content already captured]"),
                            call.index());
                    // re-read the current message: an earlier call of the same message may already
                    // have been superseded
                    messages.set(i, replaceToolCall(messages.get(i), call.id(), newCall));
                    replaced++;
                }
            }
        }
        return replaced;
    }

    /** Returns a copy of {@code message} with the tool call of {@code callId} replaced by {@code replacement}. */
    private static Message replaceToolCall(final Message message, final String callId, final ToolCall replacement) {
        return new Message(
                message.role(),
                message.content(),
                message.name(),
                message.toolCalls().stream()
                        .map(c -> callId.equals(c == null ? null : c.id()) ? replacement : c)
                        .toList(),
                message.toolCallId(),
                message.others());
    }

    private String toolFilePath(final ToolCall call) {
        if (call == null || call.function() == null || call.function().arguments() == null) {
            return null;
        }
        try {
            final var json =
                    mapper.read(Map.class, new StringReader(call.function().arguments()));
            final var path = json == null ? null : json.get("path");
            return path == null ? null : String.valueOf(path);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * Purges the (potentially large) input of failed tool calls after {@code purgeFailedToolInputsTurns}
     * user turns, keeping the error message. Returns the number of purged inputs.
     */
    public int purgeFailedToolInputs(final List<Message> messages) {
        final var turns = configuration.pruning().purgeFailedToolInputsTurns();
        final var failedCallIds = new HashSet<String>();
        for (final var message : messages) {
            if ("tool".equals(message.role())
                    && message.others() != null
                    && Boolean.TRUE.equals(message.others().get("is_error"))) {
                failedCallIds.add(message.toolCallId());
            }
        }
        if (failedCallIds.isEmpty()) {
            return 0;
        }
        var purged = 0;
        for (var i = 0; i < messages.size(); i++) {
            final var message = messages.get(i);
            if (!"assistant".equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            // count user turns between this call and the end
            var userTurnsAfter = 0;
            for (var j = i + 1; j < messages.size(); j++) {
                if ("user".equals(messages.get(j).role())) {
                    userTurnsAfter++;
                }
            }
            if (turns > 0 && userTurnsAfter < turns) {
                continue;
            }
            for (final var call : message.toolCalls()) {
                if (call == null || call.function() == null || !failedCallIds.contains(call.id())) {
                    continue;
                }
                if (call.function().arguments() == null
                        || call.function().arguments().isBlank()
                        || call.function().arguments().equals("{}")) {
                    continue;
                }
                final var newCall = new ToolCall(
                        call.id(),
                        call.type(),
                        new ToolCall.FunctionCall(call.function().name(), "{}"),
                        call.index());
                messages.set(i, replaceToolCall(messages.get(i), call.id(), newCall));
                purged++;
            }
        }
        return purged;
    }

    /**
     * Truncates old delivered <subagent> blocks to their first line (the sessionID/state/description
     * metadata), keeping the "what just finished" signal without retaining the whole summary. The
     * {@code last two} deliveries are always kept verbatim so the model still sees the most recent
     * completed sub-agent's full outcome. Returns the number of blocks truncated.
     */
    public int purgeDeliveredSubagents(final List<Message> messages) {
        if (!configuration.pruning().purgeDeliveredSubagents()) {
            return 0;
        }
        final var turns = configuration.pruning().purgeDeliveredSubagentsTurns();
        // locate delivered blocks (user-role messages opened by the <subagent marker) and index them
        final var delivered = new ArrayList<Integer>();
        for (int i = 0; i < messages.size(); i++) {
            final var m = messages.get(i);
            final var content = m.content();
            if (m.role() != null
                    && "user".equals(m.role())
                    && content instanceof String s
                    && s.startsWith("<subagent ")) {
                delivered.add(i);
            }
        }
        if (delivered.size() <= 2) {
            return 0; // always keep the last two verbatim
        }
        var purged = 0;
        final int keepLast = 2;
        for (var k = 0; k < delivered.size() - keepLast; k++) {
            final var idx = delivered.get(k);
            final var message = messages.get(idx);
            // count user turns strictly after this block
            var userTurnsAfter = 0;
            for (int j = idx + 1; j < messages.size(); j++) {
                if ("user".equals(messages.get(j).role())) {
                    userTurnsAfter++;
                }
            }
            if (turns > 0 && userTurnsAfter < turns) {
                continue;
            }
            final var content = message.content() instanceof String s2 ? s2 : "";
            final var nl = content.indexOf('\n');
            if (nl <= 0) {
                continue; // nothing to truncate (single-line already)
            }
            final var metadataLine = content.substring(0, nl);
            final var compact = metadataLine + "\n[delivered sub-agent content pruned; see /subagents "
                    + Integer.toHexString(content.hashCode()) + "]";
            // Message is immutable: swap in a rebuilt message with the compacted body, keeping every
            // other field (role user) unchanged so the block is still a plain delivered message.
            messages.set(
                    idx,
                    new Message(
                            message.role(),
                            compact,
                            message.name(),
                            message.toolCalls(),
                            message.toolCallId(),
                            message.others()));
            purged++;
        }
        return purged;
    }
}
