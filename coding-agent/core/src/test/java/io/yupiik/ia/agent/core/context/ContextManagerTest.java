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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.agent.AgentEvent;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatCompletionRequest;
import io.yupiik.ia.agent.core.llm.ChatResult;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.Message;
import io.yupiik.ia.agent.core.llm.PauseEvent;
import io.yupiik.ia.agent.core.llm.ToolCall;
import io.yupiik.ia.agent.core.llm.ToolCall.FunctionCall;
import io.yupiik.ia.agent.core.llm.Usage;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class ContextManagerTest {
    private static final Pattern COMPACTION_PROGRESS =
            Pattern.compile("Compaction in progress... \\(≈[0-9]+ estimated tokens; [a-z-]+\\)");

    @TempDir
    private static Path eventWorkdir;

    private JsonMapper mapper;

    @BeforeEach
    void bindMapper(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    private static Map<String, String> configSettings(final int keepRecent) {
        return configSettings(keepRecent, 128000);
    }

    private static Map<String, String> configSettings(final int keepRecent, final int window) {
        return configSettings(keepRecent, window, Map.of());
    }

    private static Map<String, String> configSettings(
            final int keepRecent, final int window, final Map<String, String> extra) {
        final var entries = new HashMap<String, String>();
        entries.put("yca.storageHome", "x");
        entries.put("yca.workingDir", ".");
        entries.put("yca.provider.providers.length", "1");
        entries.put("yca.provider.providers.0.id", "default");
        entries.put("yca.provider.providers.0.baseUrl", "https://api.openai.com/v1/chat/completions");
        entries.put("yca.provider.providers.0.apiKey", "");
        entries.put("yca.provider.providers.0.model", "m");
        entries.put("yca.provider.providers.0.api", "auto");
        entries.put("yca.provider.providers.0.contextWindow", String.valueOf(window));
        entries.put("yca.provider.providers.0.timeoutSeconds", "120");
        entries.put("yca.pruning.keepRecent", String.valueOf(keepRecent));
        entries.putAll(extra);
        return entries;
    }

    private static int occurrences(final String haystack, final String needle) {
        var count = 0;
        var idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static final String COMPACTED_WRAPPER_HEAD = "<compacted-context>\n";

    /**
     * P5: a pruned failed tool output keeps its failure semantics visible: the cleared marker must
     * say "failed" and the {@code is_error} flag must survive, so a later compaction summary and
     * the model still know the attempt failed instead of looking like an ordinary cleared result.
     */
    @Test
    void compactionTriggerForRelativeCapProvidersKeepsTheBufferModeTrigger() {
        // freeinference-like: a 1M window with a relative (DETECTED_RELATIVE) output cap is not a hard
        // partition, so nothing is reserved and the usable window is 1M - min(20k, 250k) = 980k; the raw
        // buffer trigger (1M - 150k = 850k) is below the 90% safety cap (882k), so opencode parity holds.
        // The relative-cap shape is expressed at the ContextBudget level (detected /v1/models extension,
        // not a configured outputTokens - a configured cap is always absolute).
        assertEquals(
                850_000,
                ContextBudget.compactionTrigger(1_000_000, 15, 16_000, 980_000, 90),
                "raw buffer trigger stays below the safety cap for relative-cap providers");
        assertEquals(
                882_000,
                ContextBudget.compactionTrigger(1_000_000, 0, 0, 980_000, 90),
                "when the raw trigger would exceed the safety cap, the cap wins");
    }

    private static int indexOfFirst(final List<AgentEvent> events, final Class<?> type) {
        for (var i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private ContextManager manager(final CodingAgentConfiguration cfg, final ChatClient chat) {
        return new ContextManager(
                cfg,
                new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                new CompactionCoordinator(
                        cfg,
                        chat,
                        new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                        providerManager(cfg),
                        null,
                        "s1",
                        new BackoffSleeper(),
                        (cfg).resolveWorkingDir()));
    }

    private static ChatClient chatReturning(final String digest) {
        return new ChatClient(null, null, null, Clock.systemUTC(), null) {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> listener,
                    final BooleanSupplier cancelled) {
                return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
            }
        };
    }

    private static ChatClient chatRecording(final String digest, final List<List<Message>> captured) {
        return new ChatClient(null, null, null, Clock.systemUTC(), null) {
            @Override
            public ChatResult chat(
                    final ChatCompletionRequest request,
                    final Consumer<PauseEvent> listener,
                    final BooleanSupplier cancelled) {
                captured.add(List.copyOf(request.messages()));
                return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
            }
        };
    }

    private ProviderManager providerManager(final CodingAgentConfiguration cfg) {
        return new ProviderManager(cfg, mapper);
    }

    @Nested
    @FusionSupport(modules = CompactForOverflowIsNoOpOnSmallConversationsModule.class)
    class CompactForOverflowIsNoOpOnSmallConversations {
        @Test
        void compactForOverflowIsNoOpOnSmallConversations(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            null,
                            new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 3; i++) {
                messages.add(Message.user("step " + i));
            }
            final var result = manager.compactForOverflow(messages);
            assertSame(
                    messages,
                    result.messages(),
                    "a conversation that already fits the keep window must stay untouched");
            assertEquals(CompactionResult.Mode.NONE, result.mode());
        }
    }

    public static class CompactForOverflowIsNoOpOnSmallConversationsModule extends ConfigurationTestModule {
        public CompactForOverflowIsNoOpOnSmallConversationsModule() {
            super(configSettings(6));
        }
    }

    @Nested
    @FusionSupport(modules = SmallWindowTinyTailModule.class)
    class SmallWindowTinyTail {
        @Test
        void forceCompactsAHistoryThatWouldOtherwiseStayUntouched(
                @Fusion final CodingAgentConfiguration configuration) {
            final var digest = "summary: forced";
            // tiny tail budget: without force the keep floor swallows the whole history
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 5; i++) {
                messages.add(Message.user("step " + i));
            }
            // a weighty tail turn: its estimate must exceed the forced (halved) tail budget
            messages.add(Message.user("step 5 " + "y".repeat(4_000)));
            // same history, no force: below the keep floor, compaction is a no-op
            assertEquals(CompactionResult.Mode.NONE, manager.compact(messages).mode());
            // force: halved tail floor lets the compaction actually run
            final var forced = manager.compact(messages, true);
            assertEquals(CompactionResult.Mode.SUMMARY, forced.mode());
            assertTrue(
                    forced.messages().stream()
                            .anyMatch(m -> m.content() instanceof String s && s.contains("<compacted-context>")),
                    "forced compaction must produce a summary");
            assertTrue(
                    forced.messages().stream()
                            .anyMatch(m -> m.content() instanceof String s
                                    && s.contains(
                                            "When a compacted context is provided, treat it as historical state.")),
                    "the digest must carry the historical-state guidance");
        }

        @Test
        void digestOnlyHistoryFoldsIntoOneReplacementDigest(@Fusion final CodingAgentConfiguration configuration) {
            // the exact user dead-end: a previous <compacted-context> digest (the only user message)
            // followed by a long tool-churn turn with no real new user turn. Re-forcing compaction
            // must FOLD: replace the old digest with ONE new digest carrying the previous summary,
            // summarize the tool-churn region away (drop the region) instead of preserving it, so
            // repeated /compact force actually reclaims the middle.
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    return new ChatResult(Message.assistant("folded-into-previous + new tool work", null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            // the previous digest as the only real user content
            messages.add(Message.user(
                    "<compacted-context>\nPrevious context: the old work summary.\n</compacted-context>\n\n"
                            + "When a compacted context is provided, treat it as historical state."));
            // a long tool-churn turn right after the digest: assistant tool calls with results
            messages.add(Message.assistant(
                    "let me check",
                    List.of(new ToolCall("t1", "function", new ToolCall.FunctionCall("bash", "{}"), 0))));
            messages.add(Message.tool("t1", "output1 " + "y".repeat(8_000)));
            messages.add(Message.assistant(
                    "let me check more",
                    List.of(new ToolCall("t2", "function", new ToolCall.FunctionCall("bash", "{}"), 0))));
            messages.add(Message.tool("t2", "output2 " + "z".repeat(8_000)));

            final var result = manager.compact(messages, true);

            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            final var digests = result.messages().stream()
                    .filter(m -> m.content() instanceof String s && s.contains("<compacted-context>"))
                    .toList();
            assertEquals(1, digests.size(), "the fold must produce ONE digest, never a stacked second one");
            final var digest = String.valueOf(digests.getFirst().content());
            assertFalse(
                    digest.contains("Previous context:"),
                    "the prior state is folded by the summarizer, never re-prepended textually");
            assertTrue(
                    digest.contains("folded-into-previous + new tool work"),
                    "the replacement digest must carry the merged output (the prior state is folded inside it)");
            assertTrue(
                    result.messages().stream()
                            .noneMatch(m -> String.valueOf(m.content()).contains("output1")),
                    "the tool-churn region must be summarized away, not preserved after the digest");
            // the folded digest sits exactly at index `from` (just after the system prompt), which is
            // the digestIndex ensureUnderWindow protects: it must not have been spliced by the size fit
            assertEquals(1, result.messages().indexOf(digests.getFirst()), "the digest stays at index `from`");
        }

        @Test
        void repeatedCompactionSendsOnlyThePostDigestDeltaAsPriorStatePlusNewEvents(
                @Fusion final CodingAgentConfiguration configuration) {
            // A second compaction over a history that already contains a digest must NOT re-send the whole
            // original transcript (nor a stacked digest): the summarizer input is prior-state (the previous
            // digest body, once) plus the post-digest ordinary events only. Ordinary messages are never
            // silently dropped, and the prior digest text is fed once as <prior-summary>, not duplicated.
            final var cfg = configuration;
            final var captured = new ArrayList<List<Message>>();
            final var manager = manager(cfg, new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    captured.add(List.copyOf(request.messages()));
                    return new ChatResult(Message.assistant("merged", null), Usage.EMPTY);
                }
            });
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            // a previous digest (whole-message user-role with the historical-state guidance)
            messages.add(Message.user("<compacted-context>\nprior digest body\n</compacted-context>\n\n"
                    + "When a compacted context is provided, treat it as historical state."));
            // ordinary post-digest events that must survive into the summarizer input
            for (var i = 0; i < 8; i++) {
                messages.add(Message.assistant("tool round " + i + "x".repeat(300), null));
                messages.add(Message.user("work turn " + i + " " + "q".repeat(300)));
            }
            messages.add(Message.assistant("final ack", null));
            messages.add(Message.user("latest " + "z".repeat(2_000)));

            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            final var userPrompt = captured.get(0).stream()
                    .filter(m -> "user".equals(m.role()))
                    .map(m -> String.valueOf(m.content()))
                    .findFirst()
                    .orElseThrow();
            // the prior digest body appears exactly once, as previous-state input
            assertTrue(userPrompt.contains("prior digest body"), "the prior digest must anchor the new summary");
            assertEquals(
                    1,
                    occurrences(userPrompt, "prior digest body"),
                    "the prior digest body must appear exactly once, never duplicated");
            // the whole-message digest is filtered from the rendered events (it is the prior state), but the
            // ordinary post-digest events survive verbatim
            assertTrue(userPrompt.contains("work turn 3"), "ordinary events after the digest must be summarized");
            assertTrue(
                    userPrompt.contains("tool round"), "tool/assistant material after the digest must be summarized");
            // the latest user tail is preserved verbatim in the compacted OUTPUT (not re-summarized)
            assertTrue(
                    result.messages().stream().anyMatch(m -> m.content() instanceof String s && s.contains("latest")),
                    "the latest turn must survive in the compacted output");
            // the output digest is a single replacement, not a stacked pair and not prefixed by "Previous context:"
            final var digests = result.messages().stream()
                    .filter(m -> m.content() instanceof String s && s.contains("<compacted-context>"))
                    .toList();
            assertEquals(1, digests.size(), "a repeated compaction must keep ONE digest (replacement, not stacking)");
            final var outDigest = String.valueOf(digests.getFirst().content());
            assertTrue(
                    outDigest.contains("merged") && !outDigest.contains("Previous context:"),
                    "the replacement digest must carry the produced summary without a textual Previous-context header");
        }

        @Test
        void noNewEventsAfterDigestSkipsThePaidCall(@Fusion final CodingAgentConfiguration configuration) {
            // When the region holds only a digest and no new ordinary events to merge, compaction must
            // return Mode.NONE without calling the summarizer (nothing to do).
            final var calls = new AtomicInteger();
            final var cfg = configuration;
            final var manager = manager(cfg, new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    calls.incrementAndGet();
                    return new ChatResult(Message.assistant("x", null), Usage.EMPTY);
                }
            });
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("<compacted-context>\nprior digest body\n</compacted-context>\n\n"
                    + "When a compacted context is provided, treat it as historical state."));
            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.NONE, result.mode(), "no new events -> no summarizer call");
            assertEquals(0, calls.get(), "the paid summarizer call must be skipped");
        }

        @Test
        void deliveredSubagentBlocksSurviveCompactionAsHighRecallAnchors(
                @Fusion final CodingAgentConfiguration configuration) {
            final var digest = "summary: things happened";
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 5; i++) {
                messages.add(Message.user("step " + i));
            }
            // a delivered sub-agent completion that must survive the summarizer as a high-recall anchor
            messages.add(Message.user("<subagent sessionID=\"c1\" state=\"completed\" description=\"child\">\n"
                    + "the child finished a task\n</subagent>"));
            messages.add(Message.user("step 5 " + "y".repeat(4_000)));
            final var forced = manager.compact(messages, true);
            assertEquals(CompactionResult.Mode.SUMMARY, forced.mode());
            // the sub-agent block must be kept verbatim (not paraphrased away) in the compacted output
            assertTrue(
                    forced.messages().stream()
                            .anyMatch(m -> m.content() instanceof String s
                                    && s.startsWith("<subagent sessionID=\"c1\"")
                                    && s.contains("the child finished a task")),
                    "a delivered <subagent> block must be anchored verbatim through compaction: " + forced.messages());
        }

        @Test
        void focusAndKeepLastAreForwardedToTheCompactor(@Fusion final CodingAgentConfiguration configuration) {
            // tiny tail budget + a weighty tail turn (as in the force test) so the compaction actually runs
            final var cfg = configuration;
            final var captured = new ArrayList<List<Message>>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    captured.add(List.copyOf(request.messages()));
                    return new ChatResult(Message.assistant("digest: custom focus", null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 5; i++) {
                messages.add(Message.user("step " + i));
            }
            messages.add(Message.user("step 5 " + "y".repeat(4_000)));
            final var result = manager.compact(messages, false, "my focus", 1);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            final var prompt = captured.get(0).stream()
                    .filter(m -> "user".equals(m.role()))
                    .map(m -> String.valueOf(m.content()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    prompt.contains("my focus"),
                    "the focus hint must be folded into the summarizer prompt so the summary emphasizes it");
            assertTrue(
                    result.messages().size() >= 1
                            && result.messages()
                                    .get(result.messages().size() - 1)
                                    .role()
                                    .equals("user"),
                    "keepLast=1 must keep the final user message verbatim");
        }
    }

    public static class SmallWindowTinyTailModule extends ConfigurationTestModule {
        public SmallWindowTinyTailModule() {
            super(configSettings(6, 3_000, Map.of("yca.compaction.tail.budgetTokens", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = MalformedOrInlineDigestsStayOrdinaryEventsAndSurviveModule.class)
    class MalformedOrInlineDigestsStayOrdinaryEventsAndSurvive {
        @Test
        void malformedOrInlineDigestsStayOrdinaryEventsAndSurvive(
                @Fusion final CodingAgentConfiguration configuration) {
            // An unclosed wrapper must be an ordinary event fed to the summarizer, never silently dropped.
            final var cfg = configuration;
            final var captured = new ArrayList<List<Message>>();
            final var manager = manager(cfg, new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    captured.add(List.copyOf(request.messages()));
                    return new ChatResult(Message.assistant("summarized", null), Usage.EMPTY);
                }
            });
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            // unclosed wrapper placed early so it lands in the summarizable region, not the protected tail
            messages.add(Message.user(COMPACTED_WRAPPER_HEAD + "unclosed digest body + " + "y".repeat(300)));
            for (var i = 0; i < 8; i++) {
                messages.add(Message.user("step " + i + " " + "x".repeat(300)));
                messages.add(Message.assistant("ack " + i, null));
            }
            // a real user turn after
            messages.add(Message.user("real ask " + "q".repeat(2_000)));

            final var result = manager.compact(messages, true);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            final var allPrompts = captured.stream()
                    .flatMap(List::stream)
                    .filter(m -> "user".equals(m.role()))
                    .map(m -> String.valueOf(m.content()))
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(
                    allPrompts.contains("unclosed digest body"),
                    "an unclosed digest wrapper must be treated as an ordinary event and summarized, not dropped");
            // the latest real user turn is preserved verbatim in the compacted output (tail), not re-summarized
            assertTrue(
                    result.messages().stream().anyMatch(m -> m.content() instanceof String s && s.contains("real ask")),
                    "the real user turn must survive in the compacted output");
        }
    }

    public static class MalformedOrInlineDigestsStayOrdinaryEventsAndSurviveModule extends ConfigurationTestModule {
        public MalformedOrInlineDigestsStayOrdinaryEventsAndSurviveModule() {
            super(configSettings(2, 3_000, Map.of("yca.compaction.tail.budgetTokens", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = ShortKeepTinyTailModule.class)
    class ShortKeepTinyTail {
        @Test
        void compactKeepsTheDigestWholeWhenShrinkingTheTailOverflow(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var digest = "summary: " + "x".repeat(1_200);
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 7; i++) {
                messages.add(Message.user("step " + i));
            }
            final var additionalSystem = Message.system("additional instructions " + "z".repeat(2_000));
            messages.add(1, additionalSystem);
            final var latestUser = Message.user("current task " + "u".repeat(2_000));
            messages.add(latestUser);
            messages.add(Message.tool("t8", "a tool output " + "y".repeat(20_000)));
            messages.add(new Message("user", "session state", null, null, null, Map.of("volatileTail", true)));
            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            final var summary = result.messages().stream()
                    .filter(m -> m.content() instanceof String s && s.contains("<compacted-context>"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    summary.content() instanceof String s && s.contains(digest),
                    "the generated digest must survive overflow shrinking intact");
            for (final var m : result.messages()) {
                if (m.content() instanceof String s && s.contains("a tool output")) {
                    assertTrue(
                            s.length() < 20_000 && s.contains("elided"),
                            "the overflowing tail tool output must be spliced down");
                }
            }
            final var first = result.messages().get(0);
            assertEquals("system", first.role());
            assertEquals("system", first.content());
            assertTrue(
                    result.messages().contains(additionalSystem), "all leading system instructions must remain intact");
            assertTrue(
                    result.messages().contains(latestUser),
                    "the actual current request must remain intact despite a later state message");
        }

        @Test
        void compactionSummarizerSpendIsTrackedAsAuxiliary(@Fusion final CodingAgentConfiguration configuration) {
            final var digest = "summary: kept";
            final var cfg = configuration;
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    return new ChatResult(Message.assistant(digest, null), new Usage(11, 7, 18));
                }
            };
            final var tracker = new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC());
            final var manager = new ContextManager(
                    cfg,
                    tracker,
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            tracker,
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 9; i++) {
                messages.add(Message.user("step " + i + " " + "x".repeat(400)));
            }
            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertEquals(1, tracker.auxiliaryRequests(), "exactly one summarizer call must be recorded");
            assertEquals(11, tracker.auxiliaryPromptTokens());
            assertEquals(7, tracker.auxiliaryCompletionTokens());
            assertEquals(11, tracker.promptTokens(), "auxiliary spend must also feed the session totals");
        }
    }

    public static class ShortKeepTinyTailModule extends ConfigurationTestModule {
        public ShortKeepTinyTailModule() {
            super(configSettings(5, 3_000, Map.of("yca.compaction.tail.budgetTokens", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = SummarizerInputCapsToolCallArgumentsGloballyModule.class)
    class SummarizerInputCapsToolCallArgumentsGlobally {
        @Test
        void summarizerInputCapsToolCallArgumentsGlobally(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var digest = "summary: capped";
            final var cfg = configuration;
            final var captured = new ArrayList<ChatCompletionRequest>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled) {
                    captured.add(request);
                    return new ChatResult(Message.assistant(digest, null), Usage.EMPTY);
                }
            };
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 5; i++) {
                messages.add(Message.user("step " + i));
            }
            final var bigArguments = "{\"command\":\"" + "x".repeat(2_000) + "\"}";
            messages.add(Message.assistant(
                    "calling",
                    List.of(new ToolCall("call_1", "function", new ToolCall.FunctionCall("bash", bigArguments), 0))));
            messages.add(Message.tool("call_1", "ok", false));
            messages.add(Message.user("step 5 " + "y".repeat(4_000))); // lourd tail pour déclencher le compact

            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertFalse(captured.isEmpty(), "the summarizer must have been called");
            final var rendered = captured.get(0).messages().stream()
                    .filter(m -> "user".equals(m.role()))
                    .map(m -> String.valueOf(m.content()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(rendered.contains("elided"), "long tool call arguments must be spliced for the summarizer");
            assertFalse(rendered.contains("x".repeat(2_000)), "the full argument blob must not reach the summarizer");
            assertTrue(rendered.contains("[Assistant tool call]: bash("), "tool name must stay visible");
        }
    }

    public static class SummarizerInputCapsToolCallArgumentsGloballyModule extends ConfigurationTestModule {
        public SummarizerInputCapsToolCallArgumentsGloballyModule() {
            super(configSettings(
                    2,
                    3_000,
                    Map.of(
                            "yca.compaction.tail.budgetTokens", "1",
                            "yca.compaction.tail.toolArgumentsCapChars", "30")));
        }
    }

    @Nested
    @FusionSupport(modules = ToolSchemasCountTowardTheTriggersModule.class)
    class ToolSchemasCountTowardTheTriggers {
        @Test
        void toolSchemasCountTowardTheTriggers(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            null,
                            new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 3; i++) {
                messages.add(Message.user("step " + i));
            }
            assertFalse(
                    manager.pruneNeeded(messages),
                    "a tiny conversation is far below the pruning threshold without tool schemas");
            assertTrue(
                    manager.pruneNeeded(messages, 150_000),
                    "the tool-schema tokens must count toward the pruning trigger");
            assertTrue(
                    manager.compactNeeded(messages, 150_000),
                    "the tool-schema tokens must count toward the compaction trigger");
        }
    }

    public static class ToolSchemasCountTowardTheTriggersModule extends ConfigurationTestModule {
        public ToolSchemasCountTowardTheTriggersModule() {
            super(configSettings(6, 128_000, Map.of("yca.pruning.enabled", "true")));
        }
    }

    @Nested
    @FusionSupport(modules = DefaultCompactionModule.class)
    class DefaultCompaction {
        @Test
        void pruneClearsOnlyOldToolOutputsAndProtectsRecentTurns(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("goal"));
            messages.add(Message.tool("t1", "old output " + "x".repeat(6_000)));
            messages.add(Message.tool("t2", "old output " + "y".repeat(6_000)));
            messages.add(Message.user("follow-up"));
            messages.add(Message.assistant("working", null));
            messages.add(Message.tool("t3", "recent output " + "z".repeat(4_000)));
            final var result = manager.prune(messages);
            assertEquals(CompactionResult.Mode.CLEARED, result.mode());
            assertEquals(
                    CompactionCoordinator.PRUNE_CLEARED_MARKER,
                    result.messages().get(2).content(),
                    "the oldest tool output must be cleared");
            assertEquals("t1", result.messages().get(2).toolCallId());
            assertTrue(
                    result.messages().get(3).content().toString().startsWith("old output"),
                    "the newest old tool output is inside the protected token budget");
            assertTrue(
                    result.messages().get(6).content().toString().startsWith("recent output"),
                    "the most recent turn's tool output must stay untouched");
            assertEquals("goal", result.messages().get(1).content());
        }

        @Test
        void pruneKeepsTheFailureMarkerOfClearedErrors(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("goal"));
            messages.add(Message.tool("t1", "command failed: boom " + "x".repeat(6_000), true));
            messages.add(Message.tool("t2", "command failed again " + "y".repeat(6_000), true));
            messages.add(Message.user("follow-up"));
            messages.add(Message.assistant("working", null));
            messages.add(Message.tool("t3", "recent output " + "z".repeat(4_000)));
            final var result = manager.prune(messages);
            assertEquals(CompactionResult.Mode.CLEARED, result.mode());
            assertEquals(
                    CompactionCoordinator.PRUNE_CLEARED_FAILED_MARKER,
                    result.messages().get(2).content(),
                    "a cleared failure must keep the failed marker");
            assertEquals(
                    Boolean.TRUE,
                    result.messages().get(2).others().get("is_error"),
                    "the is_error flag must survive the prune");
        }

        @Test
        void pruneDisablingShrinkKeepsTheLegacyClearBehavior(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("goal"));
            messages.add(Message.tool("t1", "old output " + "{\"data\":\"value\",".repeat(300)));
            messages.add(Message.tool("t2", "old output " + "{\"data\":\"value\",".repeat(300)));
            messages.add(Message.user("follow-up"));
            messages.add(Message.assistant("working", null));
            messages.add(Message.tool("t3", "recent output " + "z".repeat(4_000)));
            final var result = manager.prune(messages);
            assertEquals(CompactionResult.Mode.CLEARED, result.mode());
            assertEquals(
                    CompactionCoordinator.PRUNE_CLEARED_MARKER,
                    result.messages().get(2).content(),
                    "with the shrink pass disabled the old output must be cleared outright");
            assertSame(messages.get(3), result.messages().get(3), "the protected zone must stay untouched");
        }
    }

    public static class DefaultCompactionModule extends ConfigurationTestModule {
        public DefaultCompactionModule() {
            super(configSettings(
                    2,
                    8_000,
                    Map.ofEntries(
                            Map.entry("yca.pruning.protectTurns", "1"),
                            Map.entry("yca.pruning.minPruneTokens", "1000"),
                            Map.entry("yca.pruning.protectRecentTokens", "1000"),
                            Map.entry("yca.pruning.shrinkOutputChars", "0"))));
        }
    }

    @Nested
    @FusionSupport(modules = PruneIsNoOpWhenPrunableVolumeIsBelowTheMinimumModule.class)
    class PruneIsNoOpWhenPrunableVolumeIsBelowTheMinimum {
        @Test
        void pruneIsNoOpWhenPrunableVolumeIsBelowTheMinimum(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("goal"));
            messages.add(Message.tool("t1", "small old output"));
            messages.add(Message.user("follow-up"));
            messages.add(Message.assistant("working", null));
            messages.add(Message.tool("t2", "recent output"));
            final var result = manager.prune(messages);
            assertEquals(CompactionResult.Mode.NONE, result.mode());
            assertSame(messages, result.messages(), "below the churn minimum the history must stay untouched");
        }
    }

    public static class PruneIsNoOpWhenPrunableVolumeIsBelowTheMinimumModule extends ConfigurationTestModule {
        public PruneIsNoOpWhenPrunableVolumeIsBelowTheMinimumModule() {
            super(configSettings(
                    2,
                    8_000,
                    Map.ofEntries(
                            Map.entry("yca.pruning.protectTurns", "1"),
                            Map.entry("yca.pruning.minPruneTokens", "100000"),
                            Map.entry("yca.pruning.protectRecentTokens", "10"))));
        }
    }

    @Nested
    @FusionSupport(modules = PruneShrinksOldVerboseToolOutputsBeforeAnyClearModule.class)
    class PruneShrinksOldVerboseToolOutputsBeforeAnyClear {
        @Test
        void pruneShrinksOldVerboseToolOutputsBeforeAnyClear(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("goal"));
            messages.add(Message.tool("t1", "old output " + "{\"data\":\"value\",".repeat(300)));
            messages.add(Message.tool("t2", "old output " + "{\"data\":\"value\",".repeat(300)));
            messages.add(Message.user("follow-up"));
            messages.add(Message.assistant("working", null));
            messages.add(Message.tool("t3", "recent output " + "z".repeat(4_000)));
            final var result = manager.prune(messages);
            assertEquals(CompactionResult.Mode.SHRUNK, result.mode());
            assertEquals(7, result.messages().size(), "shrinking keeps the message list shape");
            final var shrunk = result.messages().get(2).content().toString();
            assertTrue(shrunk.startsWith("old output "), "the head of the old output must be kept");
            assertTrue(shrunk.contains("chars elided"), "the elision marker must describe what was dropped");
            assertTrue(
                    shrunk.length() < 2_200,
                    "the old output must be capped near " + cfg.pruning().shrinkOutputChars() + " chars");
            assertEquals("t1", result.messages().get(2).toolCallId());
            assertSame(
                    messages.get(3),
                    result.messages().get(3),
                    "the newest old tool output is inside the protected token budget");
            assertSame(
                    messages.get(6),
                    result.messages().get(6),
                    "the most recent turn's tool output must stay untouched");
            assertEquals("system", result.messages().get(0).content());
        }
    }

    public static class PruneShrinksOldVerboseToolOutputsBeforeAnyClearModule extends ConfigurationTestModule {
        public PruneShrinksOldVerboseToolOutputsBeforeAnyClearModule() {
            super(configSettings(
                    2,
                    8_000,
                    Map.ofEntries(
                            Map.entry("yca.pruning.protectTurns", "1"),
                            Map.entry("yca.pruning.minPruneTokens", "1000"),
                            Map.entry("yca.pruning.protectRecentTokens", "1000"))));
        }
    }

    @Nested
    @FusionSupport(modules = PruneClearsWhenShrinkingAloneCannotReclaimEnoughModule.class)
    class PruneClearsWhenShrinkingAloneCannotReclaimEnough {
        @Test
        void pruneClearsWhenShrinkingAloneCannotReclaimEnough(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("goal"));
            // just over the shrink cap but mostly prose: splicing it would reclaim almost nothing
            messages.add(Message.tool("t1", "verbose output " + "p".repeat(2_200)));
            for (var i = 0; i < 8; i++) {
                messages.add(Message.tool("s" + i, "short output " + "p".repeat(450)));
            }
            messages.add(Message.user("follow-up"));
            messages.add(Message.assistant("working", null));
            messages.add(Message.tool("t3", "recent output " + "z".repeat(4_000)));
            final var result = manager.prune(messages);
            assertEquals(CompactionResult.Mode.CLEARED, result.mode());
            assertEquals(
                    CompactionCoordinator.PRUNE_CLEARED_MARKER,
                    result.messages().get(2).content(),
                    "a shrink candidate whose reclaim misses the minimum must fall back to the full clear");
            for (var i = 3; i <= 9; i++) {
                assertEquals(
                        CompactionCoordinator.PRUNE_CLEARED_MARKER,
                        result.messages().get(i).content(),
                        "all old short outputs must be cleared too");
            }
            assertSame(
                    messages.get(10),
                    result.messages().get(10),
                    "the most recent old output is inside the protected token budget");
            assertEquals(
                    "recent output " + "z".repeat(4_000),
                    result.messages().get(13).content());
        }
    }

    public static class PruneClearsWhenShrinkingAloneCannotReclaimEnoughModule extends ConfigurationTestModule {
        public PruneClearsWhenShrinkingAloneCannotReclaimEnoughModule() {
            super(configSettings(
                    2,
                    8_000,
                    Map.ofEntries(
                            Map.entry("yca.compaction.tail.budgetTokens", "1"),
                            Map.entry("yca.pruning.protectTurns", "1"),
                            Map.entry("yca.pruning.minPruneTokens", "700"),
                            Map.entry("yca.pruning.protectRecentTokens", "10"),
                            Map.entry("yca.pruning.shrinkOutputChars", "2000"))));
        }
    }

    @Nested
    @FusionSupport(modules = CompactNeverSplitsAssistantToolCallFromItsResultsModule.class)
    class CompactNeverSplitsAssistantToolCallFromItsResults {
        @Test
        void compactNeverSplitsAssistantToolCallFromItsResults(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var cfg = configuration;
            final var manager = manager(cfg, chatReturning("digest"));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("goal"));
            messages.add(Message.assistant("ack", null));
            messages.add(Message.user("next step"));
            messages.add(Message.assistant(
                    "calling tools", List.of(new ToolCall("t1", "function", new FunctionCall("bash", "{}"), 0))));
            messages.add(Message.tool("t1", "tool result " + "b".repeat(2_000)));
            messages.add(Message.tool("t2", "tool result " + "c".repeat(2_000)));
            messages.add(Message.user("third step"));
            messages.add(Message.assistant("working", null));
            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertEquals(7, result.messages().size(), "system, digest and the whole kept tail");
            assertEquals("assistant", result.messages().get(2).role());
            assertEquals("tool", result.messages().get(3).role());
            assertEquals("t1", result.messages().get(3).toolCallId());
            assertEquals("tool", result.messages().get(4).role());
            assertEquals("user", result.messages().get(5).role());
            assertEquals("assistant", result.messages().get(6).role());
            for (var i = 1; i < result.messages().size(); i++) {
                if ("tool".equals(result.messages().get(i).role())) {
                    assertTrue(
                            "assistant".equals(result.messages().get(i - 1).role())
                                    || "tool"
                                            .equals(result.messages().get(i - 1).role()),
                            "a tool result must never be separated from its assistant tool call");
                }
            }
        }
    }

    public static class CompactNeverSplitsAssistantToolCallFromItsResultsModule extends ConfigurationTestModule {
        public CompactNeverSplitsAssistantToolCallFromItsResultsModule() {
            super(configSettings(5, 16_000, Map.of("yca.compaction.tail.budgetTokens", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = CompactReplaysTheLastUserMessageWhenTheTailMissesItModule.class)
    class CompactReplaysTheLastUserMessageWhenTheTailMissesIt {
        @Test
        void compactReplaysTheLastUserMessageWhenTheTailMissesIt(@Fusion final CodingAgentConfiguration configuration)
                throws Exception {
            final var cfg = configuration;
            final var manager = manager(cfg, chatReturning("digest"));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("first task " + "a".repeat(2_000)));
            messages.add(Message.assistant("ack", null));
            messages.add(Message.user("second task " + "a".repeat(2_000)));
            messages.add(Message.assistant("ack", null));
            messages.add(Message.user("latest task"));
            messages.add(Message.tool("t1", "tool result " + "b".repeat(2_000)));
            messages.add(Message.tool("t2", "tool result " + "b".repeat(2_000)));
            messages.add(Message.tool("t3", "tool result " + "c".repeat(2_000)));
            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            final var last = result.messages().get(result.messages().size() - 1);
            assertEquals("user", last.role());
            assertEquals("latest task", last.content(), "the current task must be replayed after the summary");
        }
    }

    public static class CompactReplaysTheLastUserMessageWhenTheTailMissesItModule extends ConfigurationTestModule {
        public CompactReplaysTheLastUserMessageWhenTheTailMissesItModule() {
            super(configSettings(2, 32_000, Map.of("yca.compaction.tail.budgetTokens", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = CompactReanchorsOnThePreviousSummaryAndAsksForTheStructuredTemplateModule.class)
    class CompactReanchorsOnThePreviousSummaryAndAsksForTheStructuredTemplate {
        @Test
        void compactReanchorsOnThePreviousSummaryAndAsksForTheStructuredTemplate(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var cfg = configuration;
            final var captured = new ArrayList<List<Message>>();
            final var manager = manager(cfg, chatRecording("updated digest", captured));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            messages.add(Message.user("<compacted-context>\nold summary\n</compacted-context>\n\n"
                    + "When a compacted context is provided, treat it as historical state.\n"
                    + "Do not reload, reconstruct, or repeat actions already described in it.\n"
                    + "Continue from the state it represents; only call tools when needed for the current task."));
            messages.add(Message.user("new work " + "q".repeat(2_000)));
            messages.add(Message.assistant("done", null));
            messages.add(Message.user("more work " + "q".repeat(2_000)));
            messages.add(Message.assistant("done", null));
            messages.add(Message.user("even more " + "q".repeat(2_000)));
            messages.add(Message.assistant("done", null));
            messages.add(Message.user("current task"));
            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            final var prompt = captured.get(0).stream()
                    .filter(m -> "user".equals(m.role()))
                    .map(m -> String.valueOf(m.content()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(prompt.contains("<prior-summary>"), "a previous summary must anchor the new one");
            assertTrue(prompt.contains("old summary"));
            final var systemPrompt = String.valueOf(captured.get(0).get(0).content());
            assertTrue(
                    systemPrompt.contains("## Objective"),
                    "the structured template must live in the stable system prompt");
            assertTrue(
                    systemPrompt.contains("structured summary"), "the summarizer system prompt must describe the role");
            assertTrue(
                    systemPrompt.contains("## Work State") && systemPrompt.contains("### Blocked"),
                    "the full SUMMARY_TEMPLATE must be a fixed system-prompt prefix so it is cache-stable");
        }
    }

    public static class CompactReanchorsOnThePreviousSummaryAndAsksForTheStructuredTemplateModule
            extends ConfigurationTestModule {
        public CompactReanchorsOnThePreviousSummaryAndAsksForTheStructuredTemplateModule() {
            super(configSettings(2, 128_000, Map.of("yca.compaction.tail.budgetTokens", "1000")));
        }
    }

    @Nested
    @FusionSupport(
            modules = CompactionTriggerIsCappedToASafetyFractionOfTheUsableWindowForLargeOutputReservesModule.class)
    class CompactionTriggerIsCappedToASafetyFractionOfTheUsableWindowForLargeOutputReserves {
        @Test
        void compactionTriggerIsCappedToASafetyFractionOfTheUsableWindowForLargeOutputReserves(
                @Fusion final CodingAgentConfiguration configuration) {
            // azure_gpt-5.5-like: a 500k window declaring a 128k output cap leaves only 372k usable, yet
            // the raw opencode-style buffer trigger is window - max(15%, 16k) = 425k, above the usable
            // window. The safety cap must pull the trigger down to a real safety margin below the usable
            // window so the gauge cannot cross 100% without ever tripping an automatic compaction.
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            final var usable = manager.usableWindowTokens();
            assertEquals(372_000, usable, "500k window minus a 128k output reserve");
            assertEquals(
                    334_800,
                    manager.compactionTriggerTokens(),
                    "the trigger must never exceed 90% of the usable window (legacy clamp would land it on the usable"
                            + " window = 100% gauge)");
        }
    }

    public static class CompactionTriggerIsCappedToASafetyFractionOfTheUsableWindowForLargeOutputReservesModule
            extends ConfigurationTestModule {
        public CompactionTriggerIsCappedToASafetyFractionOfTheUsableWindowForLargeOutputReservesModule() {
            super(configSettings(
                    6,
                    500_000,
                    Map.of(
                            "yca.provider.providers.0.outputTokens", "128000",
                            "yca.compaction.buffer.percent", "15",
                            "yca.compaction.buffer.minTokens", "16000")));
        }
    }

    @Nested
    @FusionSupport(modules = CompactionTriggerSafetyAtOneHundredPercentModule.class)
    class CompactionTriggerSafetyAtOneHundredPercent {
        @Test
        void compactionTriggerSafetyAtOneHundredPercent(@Fusion final CodingAgentConfiguration configuration) {
            final var full = configuration;
            assertEquals(372_000, manager(full, null).compactionTriggerTokens(), "100% safety = legacy clamp");
        }
    }

    public static class CompactionTriggerSafetyAtOneHundredPercentModule extends ConfigurationTestModule {
        public CompactionTriggerSafetyAtOneHundredPercentModule() {
            super(configSettings(
                    6,
                    500_000,
                    Map.of(
                            "yca.provider.providers.0.outputTokens", "128000",
                            "yca.compaction.buffer.safetyPercent", "100")));
        }
    }

    @Nested
    @FusionSupport(modules = CompactionTriggerSafetyAtSeventyFivePercentModule.class)
    class CompactionTriggerSafetyAtSeventyFivePercent {
        @Test
        void compactionTriggerSafetyAtSeventyFivePercent(@Fusion final CodingAgentConfiguration configuration) {
            final var capped = configuration;
            assertEquals(279_000, manager(capped, null).compactionTriggerTokens(), "75% safety adds headroom");
        }
    }

    public static class CompactionTriggerSafetyAtSeventyFivePercentModule extends ConfigurationTestModule {
        public CompactionTriggerSafetyAtSeventyFivePercentModule() {
            super(configSettings(
                    6,
                    500_000,
                    Map.of(
                            "yca.provider.providers.0.outputTokens", "128000",
                            "yca.compaction.buffer.safetyPercent", "75")));
        }
    }

    @Nested
    @FusionSupport(modules = CompactionTriggerKeepsTheBufferWhenTheOutputReserveIsSmallModule.class)
    class CompactionTriggerKeepsTheBufferWhenTheOutputReserveIsSmall {
        @Test
        void compactionTriggerKeepsTheBufferWhenTheOutputReserveIsSmall(
                @Fusion final CodingAgentConfiguration configuration) {
            // with a default reserve below the buffer floor the raw trigger already sits under the usable
            // window, so the safety cap is a no-op and opencode parity is preserved
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            assertEquals(
                    16_000,
                    manager.compactionTriggerTokens(),
                    "small reserve: buffer trigger (32k - 16k floor) is unchanged");
        }
    }

    public static class CompactionTriggerKeepsTheBufferWhenTheOutputReserveIsSmallModule
            extends ConfigurationTestModule {
        public CompactionTriggerKeepsTheBufferWhenTheOutputReserveIsSmallModule() {
            super(configSettings(
                    6,
                    32_000,
                    Map.of(
                            "yca.compaction.buffer.percent", "15",
                            "yca.compaction.buffer.minTokens", "16000")));
        }
    }

    @Nested
    @FusionSupport(modules = PerProviderCompactionThresholdAndGateOverrideGlobalsPerSessionModule.class)
    class PerProviderCompactionThresholdAndGateOverrideGlobalsPerSession {
        @Test
        void perProviderCompactionThresholdAndGateOverrideGlobalsPerSession(
                @Fusion final CodingAgentConfiguration configuration) {
            // the provider block overrides the global buffer.safetyPercent and gate budgets; the global
            // values apply when no per-provider block is set
            final var cfg = configuration;
            final var manager = manager(cfg, null);
            assertEquals(372_000, manager.usableWindowTokens());
            // global default safety is 90 -> 334_800; the provider override 80 -> 297_600
            assertEquals(297_600, manager.compactionTriggerTokens(null), "provider buffer.safetyPercent must win");
            // an unknown provider id falls back to the default provider (which carries the override) -
            // ProviderManager.provider(id) resolves unknowns to the default, so the override still applies
            assertEquals(
                    297_600,
                    manager.compactionTriggerTokens(new ChatSettings("other-provider", null, null, null)),
                    "unknown provider id resolves to the default provider and its override");
            assertEquals(4, manager.resolvedGate(null).maxAttempts(), "provider gate.maxAttempts must resolve");
            assertEquals(
                    90_000, manager.resolvedGate(null).maxDurationMs(), "provider gate.maxDurationMs must resolve");
            assertEquals(
                    80, manager.resolvedBuffer(null).safetyPercent(), "provider buffer.safetyPercent must resolve");
            assertEquals(
                    4,
                    manager.resolvedGate(new ChatSettings("other-provider", null, null, null))
                            .maxAttempts(),
                    "unknown provider id falls back to the default provider override");
        }
    }

    public static class PerProviderCompactionThresholdAndGateOverrideGlobalsPerSessionModule
            extends ConfigurationTestModule {
        public PerProviderCompactionThresholdAndGateOverrideGlobalsPerSessionModule() {
            super(configSettings(
                    6,
                    500_000,
                    Map.of(
                            "yca.provider.providers.0.outputTokens", "128000",
                            "yca.provider.providers.0.compaction.buffer.safetyPercent", "80",
                            "yca.provider.providers.0.compaction.gate.maxAttempts", "4",
                            "yca.provider.providers.0.compaction.gate.maxDurationMs", "90000")));
        }
    }

    @Nested
    @FusionSupport(modules = AutomaticCompactionLandsBelowTheTriggerNotJustUnderTheUsableWindowModule.class)
    class AutomaticCompactionLandsBelowTheTriggerNotJustUnderTheUsableWindow {
        @Test
        void automaticCompactionLandsBelowTheTriggerNotJustUnderTheUsableWindow(
                @Fusion final CodingAgentConfiguration configuration) {
            // azure-like window: 500k window + 128k output cap -> usable 372k, trigger capped at 90% =
            // 334.8k. An automatic (non-forced, non-urgent) compaction must shrink the kept tail until the
            // estimate is BELOW the trigger - real headroom for the next turns - not merely under the hard
            // usable window (which would still read 100% on the gauge).
            final var cfg = configuration;
            final var tracker = new UsageTracker(providerManager(cfg), null, null, Clock.systemUTC());
            assertEquals(372_000, tracker.usableWindow(), "500k minus 128k output reserve");
            final var chat = chatReturning("digest: heavy history summarized");
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            tracker,
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            assertEquals(334_800, manager.compactionTriggerTokens(), "trigger = 90% of the usable window");

            // a heavy history: many tool outputs in the middle so compaction has something to summarize,
            // and a genuinely big tail so the post-compaction list needs tail spilling to fit the trigger
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 8; i++) {
                messages.add(Message.user("step " + i));
                messages.add(Message.assistant("tool call " + i, null));
                messages.add(Message.tool("call_" + i, "x".repeat(40_000), false));
            }
            messages.add(Message.user("current task " + "y".repeat(200_000)));

            final var result = manager.compact(messages);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());
            assertTrue(
                    tracker.estimateContext(result.messages()) < manager.compactionTriggerTokens(),
                    "automatic compaction must land below the trigger (got "
                            + tracker.estimateContext(result.messages()) + " vs trigger "
                            + manager.compactionTriggerTokens() + ")");
            assertTrue(
                    tracker.estimateContext(result.messages()) >= 0,
                    "the list must still contain the digest and the tail");
        }
    }

    public static class AutomaticCompactionLandsBelowTheTriggerNotJustUnderTheUsableWindowModule
            extends ConfigurationTestModule {
        public AutomaticCompactionLandsBelowTheTriggerNotJustUnderTheUsableWindowModule() {
            super(configSettings(
                    6,
                    500_000,
                    Map.of(
                            "yca.provider.providers.0.outputTokens", "128000",
                            "yca.compaction.buffer.percent", "15",
                            "yca.compaction.buffer.minTokens", "16000",
                            "yca.compaction.tail.budgetTokens", "20000")));
        }
    }

    @Nested
    @FusionSupport(modules = UrgentCompactionTruncatesLocallyWhenTheSummarizerFailsTransientlyModule.class)
    class UrgentCompactionTruncatesLocallyWhenTheSummarizerFailsTransiently {
        @Test
        void urgentCompactionTruncatesLocallyWhenTheSummarizerFailsTransiently(
                @Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var attempts = new AtomicInteger();
            final var delays = new ArrayList<Long>();
            final var chat = new ChatClient() {
                @Override
                public ChatResult chat(
                        final ChatCompletionRequest request,
                        final Consumer<PauseEvent> listener,
                        final BooleanSupplier cancelled)
                        throws IOException {
                    attempts.incrementAndGet();
                    throw new IOException("upstream 500");
                }
            };
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper() {
                                @Override
                                public boolean sleep(final long millis) {
                                    delays.add(millis);
                                    return true;
                                }
                            },
                            cfg.resolveWorkingDir()));
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 8; i++) {
                messages.add(Message.user("old step " + i + " " + "x".repeat(1_000)));
                messages.add(Message.assistant("ack " + i, null));
            }
            messages.add(Message.user("current task"));
            // a normal forced compaction honours the non-destructive contract: transient failure -> skip
            assertEquals(
                    CompactionResult.Mode.NONE, manager.compact(messages, true).mode());
            assertEquals(2, attempts.get(), "normal compaction must exhaust both attempts");
            assertEquals(1, delays.size(), "normal compaction must request retry backoff");
            assertTrue(delays.get(0) > 0, "normal compaction must request positive jittered backoff");
            // an urgent compaction opts out: it falls back to the destructive local truncation
            final var urgent = manager.compact(messages, true, null, 0, true, () -> false, ChatSettings.EMPTY);
            assertEquals(CompactionResult.Mode.TRUNCATE, urgent.mode(), "urgent must truncate, not skip");
            assertEquals(4, attempts.get(), "urgent compaction must also exhaust both attempts");
            assertEquals(2, delays.size(), "urgent compaction must also request retry backoff");
            assertTrue(delays.get(1) > 0, "urgent compaction must request positive jittered backoff");
            assertTrue(urgent.changed(), "urgent compaction must actually reclaim context");
            assertTrue(
                    urgent.error() != null && urgent.error().contains("upstream 500"),
                    "the truncation must still surface the summarizer failure: " + urgent.error());
        }
    }

    public static class UrgentCompactionTruncatesLocallyWhenTheSummarizerFailsTransientlyModule
            extends ConfigurationTestModule {
        public UrgentCompactionTruncatesLocallyWhenTheSummarizerFailsTransientlyModule() {
            super(configSettings(
                    6,
                    128_000,
                    Map.of(
                            "yca.compaction.tail.budgetTokens", "1",
                            "yca.compaction.summarizer.chunkChars", "4000",
                            "yca.llm.retriesMaxAttempts", "2",
                            "yca.pruning.toolOutputMaxChars", "500")));
        }
    }

    @Nested
    @FusionSupport(modules = ProgressReportingModule.class)
    class ProgressReporting {
        @Test
        void compactionEmitsProgressAndResultNoticesOnSuccess(@Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var events = new ArrayList<AgentEvent>();
            final var chat = chatReturning("digest: summarized");
            final var tracker = new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC());
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            tracker,
                            providerManager(cfg),
                            new Emitter() {
                                @Override
                                public <T> void emit(final T event) {
                                    if (event instanceof AgentEvent agentEvent) {
                                        events.add(agentEvent);
                                    }
                                }
                            },
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));

            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 8; i++) {
                messages.add(Message.user("old step " + i + " " + "x".repeat(1_000)));
                messages.add(Message.assistant("ack " + i, null));
            }
            messages.add(Message.user("current task"));

            final var result = manager.compact(messages, true);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());

            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            assertTrue(notices.size() >= 2, "progress + result notices expected, got " + notices);
            final var progress = notices.get(0);
            assertEquals(AgentEvent.Notice.Level.INFO, progress.level());
            assertTrue(
                    progress.message().startsWith("Compaction in progress... (\u2248"),
                    "progress notice must carry the estimated token count: " + progress.message());
            assertTrue(
                    COMPACTION_PROGRESS.matcher(progress.message()).matches(),
                    "progress notice must carry a numeric estimate: " + progress.message());
            assertTrue(
                    notices.stream()
                            .anyMatch(n -> n.message().startsWith("Compacted: context ")
                                    || n.message().startsWith("Compacted: reclaimed ~")),
                    "a compaction result notice must be emitted, got " + notices);
        }

        @Test
        void compactionProgressNoticeUsesTheCalibratedEffectiveContext(
                @Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            final var events = new ArrayList<AgentEvent>();
            final var chat = chatReturning("digest: summarized");
            final var tracker = new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC());
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            tracker,
                            providerManager(cfg),
                            new Emitter() {
                                @Override
                                public <T> void emit(final T event) {
                                    if (event instanceof AgentEvent agentEvent) {
                                        events.add(agentEvent);
                                    }
                                }
                            },
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));

            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 8; i++) {
                messages.add(Message.user("old step " + i + " " + "x".repeat(1_000)));
                messages.add(Message.assistant("ack " + i, null));
            }
            messages.add(Message.user("current task"));

            // anchor on a strict prefix: the anchor was set at the last real API call, after which
            // more turns were appended - so the full list estimates above the anchor and the
            // calibrated path applies (estimate <= anchor would fall back to raw, incl. equality)
            final var anchorList = new ArrayList<>(messages.subList(0, messages.size() - 3));
            final var rawAtAnchor = tracker.estimateContext(anchorList);
            final var raw = tracker.estimateContext(messages);
            assertTrue(raw > rawAtAnchor, "the full list must estimate above the anchor list");
            // provider billed only a fraction of what the raw conservative heuristic guessed for the
            // anchored list: ratio 0.25 clamps to 0.5, so the calibrated figure is strictly below raw
            tracker.anchor(Math.max(1, rawAtAnchor / 4), tracker.contextPayload(anchorList, 0));

            final var result = manager.compact(messages, true);
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());

            final var notices = events.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            final var progress = notices.get(0);
            assertTrue(
                    COMPACTION_PROGRESS.matcher(progress.message()).matches(),
                    "progress notice must carry a numeric estimate: " + progress.message());
            final var real = Math.max(1, rawAtAnchor / 4);
            final var expected = (int) Math.round(real + ((double) real / rawAtAnchor) * (raw - rawAtAnchor));
            assertTrue(
                    progress.message().contains("≈" + expected + " estimated tokens"),
                    "progress notice must report the calibrated effectiveContext (" + expected + "), got: "
                            + progress.message());
        }

        @Test
        void compactionProgressPublishesTheSameCalibratedEstimateIncludingToolTokens(
                @Fusion final CodingAgentConfiguration configuration) {
            final var cfg = configuration;
            // a shared order list between the recording emitter and the published usage consumer: the
            // UsageUpdated must arrive BEFORE the progress notice (the gauge flips before it renders)
            final var ordered = new ArrayList<AgentEvent>();
            final var chat = chatReturning("digest: summarized");
            final var tracker = new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC());
            final var published = new ArrayList<Integer>();
            final var contextManager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            tracker,
                            providerManager(cfg),
                            new Emitter() {
                                @Override
                                public <T> void emit(final T event) {
                                    if (event instanceof AgentEvent agentEvent) {
                                        ordered.add(agentEvent);
                                    }
                                }
                            },
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));

            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 8; i++) {
                messages.add(Message.user("old step " + i + " " + "x".repeat(1_000)));
                messages.add(Message.assistant("ack " + i, null));
            }
            messages.add(Message.user("current task"));

            // Tool schemas participate in both request identity and input occupancy.
            final var raw = tracker.estimateContext(messages);
            final var real = Math.max(1, raw / 4);
            final var toolTokens = 3_000;
            tracker.anchor(real, tracker.contextPayload(messages, toolTokens));
            final var expected = tracker.effectiveContext(messages, toolTokens);
            assertEquals(real, expected);
            assertTrue(
                    tracker.effectiveContext(messages, 0) < expected,
                    "removing schemas must re-estimate a different request");

            final var result = contextManager.compact(
                    messages,
                    true,
                    null,
                    0,
                    false,
                    () -> false,
                    ChatSettings.EMPTY,
                    new CompactionCoordinator.CompactionProgress(
                            toolTokens,
                            effective -> {
                                published.add(effective);
                                // in production the tokensPublished hook routes through
                                // SessionUsageReporter.pushUsageContext -> the same Emitter feeding
                                // `ordered`: mirror that so the ordering assertion below is meaningful
                                ordered.add(new AgentEvent.UsageUpdated(
                                        Usage.EMPTY,
                                        effective,
                                        tracker.contextPercent(effective),
                                        -1,
                                        -1,
                                        null,
                                        "s1",
                                        null,
                                        null,
                                        AgentEvent.UsageUpdated.Quota.NONE,
                                        eventWorkdir));
                            },
                            null,
                            null,
                            eventWorkdir));
            assertEquals(CompactionResult.Mode.SUMMARY, result.mode());

            final var notices = ordered.stream()
                    .filter(AgentEvent.Notice.class::isInstance)
                    .map(AgentEvent.Notice.class::cast)
                    .toList();
            final var progress = notices.get(0);
            assertTrue(
                    COMPACTION_PROGRESS.matcher(progress.message()).matches(),
                    "progress notice must carry a numeric estimate: " + progress.message());
            assertTrue(
                    progress.message().contains("≈" + expected + " estimated tokens"),
                    "progress notice must report the calibrated effectiveContext including the tool tokens (" + expected
                            + "), got: " + progress.message());

            // the publisher received the exact same int the notice prints
            assertEquals(1, published.size(), "the progress hook must publish exactly one estimate: " + published);
            assertEquals(expected, published.get(0), "the published estimate must equal the notice's figure");
            assertTrue(
                    progress.message().contains("≈" + published.get(0) + " estimated tokens"),
                    "notice and published estimate must be byte-identical");

            // the published UsageUpdated precedes the progress notice in the shared event stream
            final var usageIdx = indexOfFirst(ordered, AgentEvent.UsageUpdated.class);
            final var noticeIdx = indexOfFirst(ordered, AgentEvent.Notice.class);
            assertTrue(usageIdx >= 0, "a UsageUpdated must be emitted through the hook: " + ordered);
            assertTrue(
                    usageIdx < noticeIdx,
                    "the UsageUpdated (" + usageIdx + ") must precede the progress notice (" + noticeIdx + "): "
                            + ordered);
            final var usage = (AgentEvent.UsageUpdated) ordered.get(usageIdx);
            assertEquals(
                    expected, usage.estimatedContextTokens(), "the UsageUpdated must carry the published estimate");
            assertEquals("s1", usage.sessionId(), "the UsageUpdated must be routed to the session");
        }
    }

    public static class ProgressReportingModule extends ConfigurationTestModule {
        public ProgressReportingModule() {
            super(configSettings(6, 128_000, Map.of("yca.compaction.tail.budgetTokens", "1")));
        }
    }

    @Nested
    @FusionSupport(modules = IgnoreLlmDisablesSpeculativeCompactionModule.class)
    class IgnoreLlmDisablesSpeculativeCompaction {
        @Test
        void ignoreLlmDisablesSpeculativeCompaction(@Fusion final CodingAgentConfiguration configuration) {
            // speculative policy is opt-in "concurrent"; under ignoreLlm it must never launch (it is
            // LLM-backed by design), even when the global policy asks for it
            final var cfg = configuration;
            final var chat = chatReturning("digest");
            final var manager = new ContextManager(
                    cfg,
                    new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                    new CompactionCoordinator(
                            cfg,
                            chat,
                            new UsageTracker(providerManager(cfg), chat, null, Clock.systemUTC()),
                            providerManager(cfg),
                            null,
                            "s1",
                            new BackoffSleeper(),
                            (cfg).resolveWorkingDir()));
            assertFalse(manager.speculationEnabled(), "speculation must be off under ignoreLlm");
            final var messages = new ArrayList<Message>();
            messages.add(Message.system("system"));
            for (var i = 0; i < 3; i++) {
                messages.add(Message.user("step " + i));
            }
            assertFalse(
                    manager.maybeLaunchSpeculation(
                            messages, 0, () -> false, new ChatSettings("default", "m", null, null)),
                    "launch must be a no-op under ignoreLlm");
            assertNull(
                    manager.adoptSpeculation(messages, 0, () -> false, new ChatSettings("default", "m", null, null)),
                    "adoption must be a no-op under ignoreLlm");
        }
    }

    public static class IgnoreLlmDisablesSpeculativeCompactionModule extends ConfigurationTestModule {
        public IgnoreLlmDisablesSpeculativeCompactionModule() {
            super(configSettings(
                    6,
                    128_000,
                    Map.of(
                            "yca.compaction.mode", "no_llm",
                            "yca.compaction.speculative.policy", "concurrent",
                            "yca.compaction.speculative.startPercent", "80")));
        }
    }
}
