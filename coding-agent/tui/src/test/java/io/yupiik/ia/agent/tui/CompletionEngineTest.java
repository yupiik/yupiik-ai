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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.shell.slash.ArgCompletion;
import io.yupiik.ia.agent.core.shell.slash.CompletionEngine;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashCommandSet;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionEngineTest {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** The same shape ConfigCommands registers for /model. */
    private static final ArgCompletion MODEL_COMPLETION = (arg, token, env) -> {
        if (arg == null || arg.isBlank()) {
            return ArgCompletion.fuzzyResult("model", env.models(), token);
        }
        return ArgCompletion.fuzzyResult("variant", env.variants(WHITESPACE.split(arg.strip())[0]), token);
    };

    /** The same shape ConfigCommands registers for /metrics. */
    private static final ArgCompletion METRICS_COMPLETION = (arg, token, env) -> {
        if ("session".equals(ArgCompletion.previousToken(arg))) {
            return ArgCompletion.fuzzyResult("option", List.of("on", "off"), token);
        }
        return ArgCompletion.fuzzyResult("option", List.of("show", "hide", "compact", "full", "session"), token);
    };

    /** The same shape MaintainCommands registers for /mcp. */
    private static final ArgCompletion MCP_COMPLETION = (arg, token, env) -> {
        if ("reset".equals(ArgCompletion.previousToken(arg))) {
            return ArgCompletion.fuzzyResult("server", env.servers(), token);
        }
        return ArgCompletion.fuzzyResult("option", List.of("status", "reset"), token);
    };

    private static SlashRegistry registry() {
        final SlashCommandSet set = () -> List.of(
                spec("/model", MODEL_COMPLETION, SlashGroup.CONFIG),
                spec(
                        "/variant",
                        (arg, token, env) -> ArgCompletion.fuzzyResult("variant", env.variants(), token),
                        SlashGroup.CONFIG),
                spec("/provider", ArgCompletion.from("provider", ArgCompletion.Env::providers), SlashGroup.CONFIG),
                spec("/agent", ArgCompletion.from("agent", ArgCompletion.Env::agents), SlashGroup.CONFIG),
                spec("/resume", ArgCompletion.from("session", ArgCompletion.Env::sessions), SlashGroup.SESSION),
                spec("/tools", ArgCompletion.from("tool", ArgCompletion.Env::tools), SlashGroup.INSPECT),
                spec("/mode", ArgCompletion.options("build", "plan"), SlashGroup.CONFIG),
                spec(
                        "/permissions",
                        ArgCompletion.options("ask", "auto_edit", "yolo", "none", "show"),
                        SlashGroup.CONFIG),
                spec("/thinking", ArgCompletion.options("show", "hide"), SlashGroup.CONFIG),
                spec("/tool-output", ArgCompletion.options("show", "collapse", "hide"), SlashGroup.CONFIG),
                spec("/metrics", METRICS_COMPLETION, SlashGroup.CONFIG),
                spec("/mcp", MCP_COMPLETION, SlashGroup.MAINTAIN),
                spec("/theme", ArgCompletion.options("light", "dark"), SlashGroup.CONFIG),
                spec("/tabs", ArgCompletion.options("last", "+", "-"), SlashGroup.SESSION),
                spec(
                        "/subagents",
                        ArgCompletion.wholeArgument("subagent", ArgCompletion.Env::subagents),
                        SlashGroup.SESSION),
                spec("/compact", ArgCompletion.options("force"), SlashGroup.MAINTAIN),
                spec("/snapshot", ArgCompletion.options("save", "cancel", "restore", "status"), SlashGroup.MAINTAIN),
                spec("/context", ArgCompletion.PATH, SlashGroup.INSPECT),
                spec("/editplan", ArgCompletion.PATH, SlashGroup.MAINTAIN));
        return new SlashRegistry(List.of(set));
    }

    private static SlashCommand spec(final String name, final ArgCompletion completion, final SlashGroup group) {
        return SlashCommand.of(new SlashCommandSpec(name, null, name + " cmd", group), completion, (arg, env) -> null);
    }

    private static ArgCompletion.Env env() {
        return new ArgCompletion.Env() {
            @Override
            public List<String> models() {
                // >= 9 models so popup viewport/scrolling exercises the 8-row window,
                // including exact-prefix duplicates (the glm-5.3 flash family) that
                // make Tab/Enter semantics observable
                return List.of(
                        "glm-5.3-flash",
                        "glm-5.3-flash-turbo",
                        "gpt-5",
                        "gpt-5-mini",
                        "gpt-5-nano",
                        "qwen3-coder",
                        "qwen3-coder-32b",
                        "qwen3-coder-14b",
                        "claude-sonnet-4",
                        "claude-opus-4",
                        "deepseek-v3",
                        "deepseek-r1");
            }

            @Override
            public List<String> providers() {
                return List.of("openai", "ollama");
            }

            @Override
            public List<String> agents() {
                return List.of("coder", "reviewer", "tester");
            }

            @Override
            public List<String> sessions() {
                return List.of("11111111-aaaa (2026-09-01 10:00)", "22222222-bbbb (2026-09-02 11:00)");
            }

            @Override
            public List<String> tools() {
                return List.of("read", "write_file", "bash");
            }

            @Override
            public List<String> subagents() {
                return List.of("subagent-1", "subagent-2", "code reviewer");
            }

            @Override
            public List<String> servers() {
                return List.of("docs", "remote");
            }

            @Override
            public List<String> variants(final String model) {
                return "fast".equals(model) ? List.of("default", "deep") : List.of("default", "fast");
            }

            @Override
            public List<String> variants() {
                // the "current model"'s variants, as consumed by /variant
                return List.of("default", "fast", "deep");
            }
        };
    }

    private static CompletionEngine engine() {
        return new CompletionEngine(registry(), env());
    }

    @Test
    void completesAndReplacesTheWholeSubagentName() {
        final var text = "/subagents code rev";
        final var suggestion = engine().suggest(text, text.length());
        assertEquals(List.of("code reviewer"), suggestion.items());
        assertEquals(11, suggestion.replaceStart());
        assertEquals(text.length(), suggestion.replaceEnd());
        assertEquals(
                "/subagents code reviewer",
                text.substring(0, suggestion.replaceStart())
                        + suggestion.items().getFirst()
                        + text.substring(suggestion.replaceEnd()));
        final var middle = engine().suggest("/subagents code rev-old", text.length());
        assertEquals("/subagents code rev-old".length(), middle.replaceEnd());
        assertEquals(List.of("code reviewer"), middle.items());
    }

    @Test
    void emptyTextHasNoSuggestion() {
        assertTrue(engine().suggest("", 0).empty());
    }

    @Test
    void completesSlashCommandPrefix() {
        final var s = engine().suggest("/per", 4);
        assertTrue(s.items().contains("/permissions"));
        assertEquals(0, s.replaceStart());
        assertEquals(4, s.replaceEnd());
    }

    /**
     * Regression for the /editplan Enter swallow: a fully-typed slash command
     * yields a suggestion whose highlighted candidate replaces the whole typed
     * command with itself, i.e. applying it is a textual no-op. The TUI/web
     * Enter handlers rely on detecting this so a single Enter submits the
     * command instead of being silently consumed to "accept" the identical
     * candidate.
     */
    @Test
    void fullyTypedCommandSuggestionIsATextualNoOp() {
        final var s = engine().suggest("/editplan", 9);
        assertTrue(s.items().contains("/editplan"), "a fully typed command must be its own candidate");
        assertEquals(0, s.replaceStart());
        assertEquals(9, s.replaceEnd());
        final var choice = s.items().get(0);
        // applying the inverse of the skipped span must reproduce the same text
        assertEquals(
                "/editplan",
                "/editplan"
                        .substring(0, s.replaceStart())
                        .concat(choice)
                        .concat("/editplan".substring(s.replaceEnd())));
    }

    /**
     * The mirror: a partial prefix (e.g. "/per" -> "/permissions") still
     * changes the line, so Enter must keep accepting the expansion rather than
     * submitting a half-typed command.
     */
    @Test
    void partialCommandSuggestionChangesTheLine() {
        final var s = engine().suggest("/per", 4);
        assertEquals(0, s.replaceStart());
        assertEquals(4, s.replaceEnd());
        final var choice = s.items().get(0);
        final var applied =
                "/per".substring(0, s.replaceStart()).concat(choice).concat("/per".substring(s.replaceEnd()));
        assertNotEquals("/per", applied, "a partial prefix expansion must differ from the typed text");
    }

    @Test
    void completesModelParam() {
        final var s = engine().suggest("/model gpt", 10);
        assertTrue(s.items().contains("gpt-5-mini"));
        assertTrue(s.items().contains("gpt-5"));
        assertEquals(7, s.replaceStart());
        assertEquals(10, s.replaceEnd());
        assertEquals("model", s.label());
    }

    @Test
    void completesVariantAsSecondModelToken() {
        final var s = engine().suggest("/model gpt-5 f", 13);
        assertTrue(s.items().contains("fast"));
        assertTrue(s.items().contains("default"));
        assertEquals("variant", s.label());
    }

    @Test
    void variantCompletionIsDrivenByTheTypedModel() {
        // "fast" typed model resolves a different variant set than "gpt-5"
        final var forFast = engine().suggest("/model fast f", 13);
        assertTrue(forFast.items().contains("default"), "variant list must reflect the typed model");
        assertFalse(forFast.items().contains("fast"));
        final var forGpt5 = engine().suggest("/model gpt-5 f", 14);
        assertTrue(forGpt5.items().contains("fast"), "gpt-5 typed model keeps its own variants");
        assertFalse(forGpt5.items().contains("deep"));
    }

    @Test
    void completesVariantForCurrentModel() {
        // /variant is contextual to the current model: it completes the env's
        // current-model variants (variants()) regardless of any typed model
        final var s = engine().suggest("/variant d", 10);
        assertTrue(s.items().contains("default"));
        assertTrue(s.items().contains("deep"));
        assertEquals("variant", s.label());
        final var fast = engine().suggest("/variant f", 10);
        assertTrue(fast.items().contains("fast"));
        assertFalse(fast.items().contains("deep"));
    }

    @Test
    void completesModeParam() {
        final var s = engine().suggest("/mode pl", 8);
        assertTrue(s.items().contains("plan"));
    }

    @Test
    void completesPermissionsParam() {
        final var ask = engine().suggest("/permissions a", 14);
        assertTrue(ask.items().contains("ask"));
        assertFalse(ask.items().contains("all"));
        final var y = engine().suggest("/permissions y", 14);
        assertTrue(y.items().contains("yolo"), y.items().toString());
        final var auto = engine().suggest("/permissions auto", 17);
        assertTrue(auto.items().contains("auto_edit"));
        final var n = engine().suggest("/permissions n", 14);
        assertTrue(n.items().contains("none"));
        final var s = engine().suggest("/permissions sh", 15);
        assertTrue(s.items().contains("show"));
    }

    @Test
    void infixTypedTextDoesNotOfferCandidate() {
        // containment is deliberate: "olo" is inside "yolo" and does match, just
        // like "/model mini" -> "gpt-5-mini". The important invariant is the
        // *span*: accepting replaces only the typed token, never duplicating it.
        final var infix = engine().suggest("/permissions olo", 16);
        assertTrue(infix.items().contains("yolo"), infix.items().toString());
        assertEquals(13, infix.replaceStart());
        assertEquals(16, infix.replaceEnd());
        // a fully typed candidate still completes the argument (y -> yolo)
        final var typed = engine().suggest("/permissions yolo", 17);
        assertTrue(typed.items().contains("yolo"), typed.items().toString());
        assertEquals(13, typed.replaceStart());
        assertEquals(17, typed.replaceEnd());
        // typing "yo" (a prefix) still offers yolo, with the span covering only the token
        final var yo = engine().suggest("/permissions yo", 15);
        assertTrue(yo.items().contains("yolo"), yo.items().toString());
        assertEquals(13, yo.replaceStart());
        assertEquals(15, yo.replaceEnd());
    }

    @Test
    void permissionTokenReplacesOnlyTheTypedToken() {
        // "/permissions y" - the span must cover only the current token (13..14),
        // never the whole line, so an acceptance cannot duplicate text
        final var s = engine().suggest("/permissions y", 14);
        assertEquals(List.of("yolo"), s.items(), s.items().toString());
        assertEquals(13, s.replaceStart());
        assertEquals(14, s.replaceEnd());
        // mid-token caret: the span covers the token the caret is inside
        final var mid = engine().suggest("/permissions as", 15);
        assertEquals(List.of("ask"), mid.items(), mid.items().toString());
        assertEquals(13, mid.replaceStart());
        assertEquals(15, mid.replaceEnd());
    }

    @Test
    void completesThinkingParam() {
        final var s = engine().suggest("/thinking h", 11);
        assertTrue(s.items().contains("hide"));
    }

    @Test
    void completesToolOutputParam() {
        final var s = engine().suggest("/tool-output ", 13);
        assertTrue(s.items().contains("show"));
        assertTrue(s.items().contains("collapse"));
        assertTrue(s.items().contains("hide"));
    }

    @Test
    void completesMetricsFirstToken() {
        final var s = engine().suggest("/metrics com", 12);
        assertTrue(s.items().contains("compact"));
        assertFalse(s.items().contains("show"), "the token filters the closed set");
    }

    @Test
    void completesMetricsSessionSubcommand() {
        final var s = engine().suggest("/metrics session o", 18);
        assertTrue(s.items().contains("on"));
        assertTrue(s.items().contains("off"));
        assertFalse(s.items().contains("show"));
    }

    @Test
    void completesSnapshots() {
        final var s = engine().suggest("/snapshot s", 11);
        assertTrue(s.items().contains("save"));
        assertTrue(s.items().contains("status"));
    }

    @Test
    void completesMcpStatusAndResetServer() {
        final var status = engine().suggest("/mcp s", 6);
        assertTrue(status.items().contains("status"));
        final var reset = engine().suggest("/mcp reset r", 12);
        assertTrue(reset.items().contains("remote"));
    }

    @Test
    void completesThemeAndTabs() {
        final var theme = engine().suggest("/theme l", 8);
        assertTrue(theme.items().contains("light"));
        final var tabs = engine().suggest("/tabs la", 8);
        assertTrue(tabs.items().contains("last"));
    }

    @Test
    void completesSubagentIds() {
        final var s = engine().suggest("/subagents subagent-", 20);
        assertTrue(s.items().contains("subagent-1"));
        assertTrue(s.items().contains("subagent-2"));
    }

    @Test
    void completesAgentNameOverPrefix() {
        final var s = engine().suggest("@cod", 4);
        assertTrue(s.items().contains("coder"));
    }

    @Test
    void fuzzyContainsMatching() {
        final var s = engine().suggest("/model mini", 11);
        assertTrue(s.items().contains("gpt-5-mini"));
    }

    @Test
    void exactPrefixDuplicatesStillBothMatch() {
        // two candidates share the exact typed prefix: both must survive the
        // prefixes match (a containment regression would drop one of them)
        final var s = engine().suggest("/model glm-5.3", 14);
        assertTrue(s.items().contains("glm-5.3-flash"), s.items().toString());
        assertTrue(s.items().contains("glm-5.3-flash-turbo"), s.items().toString());
        // a partly typed duplicate keeps both too
        final var partial = engine().suggest("/model glm", 10);
        assertTrue(partial.items().contains("glm-5.3-flash"), partial.items().toString());
        assertTrue(
                partial.items().contains("glm-5.3-flash-turbo"), partial.items().toString());
    }

    @Test
    void noMatchIsEmpty() {
        assertTrue(engine().suggest("/mode zzz", 9).empty());
    }

    @Test
    void pathCompletionLooksUpFilesystem(@TempDir final Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main"));
        Files.createDirectories(root.resolve("src/test"));
        final var s = CompletionEngine.pathCandidates("src/m", root);
        assertEquals(List.of("src/main/"), s);
    }

    @Test
    void pathCompletionWithoutTokenListsDirectory(@TempDir final Path root) throws IOException {
        Files.writeString(root.resolve("entry.txt"), "entry");
        assertEquals(List.of("entry.txt"), CompletionEngine.pathCandidates("", root));
    }

    @Test
    void exactPrefixKeepsBothCandidatesHighlightable() {
        // "glm" matches both glm-5.3-flash and glm-5.3-flash-turbo: an exact-prefix
        // duplicate must not collapse to a single candidate nor dead-end the popup
        final var s = engine().suggest("/model glm", 10);
        assertTrue(s.items().contains("glm-5.3-flash"), s.items().toString());
        assertTrue(s.items().contains("glm-5.3-flash-turbo"), s.items().toString());
        // and the exact candidate itself stays in the list when fully typed
        final var exact = engine().suggest("/model glm-5.3-flash", 20);
        assertTrue(exact.items().contains("glm-5.3-flash"), exact.items().toString());
        assertTrue(exact.items().contains("glm-5.3-flash-turbo"), exact.items().toString());
    }

    @Test
    void tabSeparatedTokenCompletesLikeSpaceSeparated() {
        // the tokenization must treat any whitespace (tab included) as a token
        // boundary, so /model<tab>glm completes the model - not the whole line
        final var s = engine().suggest("/model\tglm", 10);
        assertTrue(s.items().contains("glm-5.3-flash"), s.items().toString());
        assertEquals(7, s.replaceStart());
        assertEquals(10, s.replaceEnd());
        assertEquals("model", s.label());
    }
}
