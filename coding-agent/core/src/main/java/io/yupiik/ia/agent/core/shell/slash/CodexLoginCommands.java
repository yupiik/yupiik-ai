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
import io.yupiik.ia.agent.core.cli.slash.SlashCommandSpec;
import io.yupiik.ia.agent.core.cli.slash.SlashGroup;
import io.yupiik.ia.agent.core.llm.CodexAuthTemplateHelper;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.ia.agent.core.shell.ShellEnv;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Codex subscription login commands: {@code /codex login <provider> [--device] [--enterprise]
 * [--workspace <org-id>]} runs the
 * ChatGPT/Codex OAuth flow for a provider and persists the resulting session to
 * {@code <storageHome>/providers/<provider>/codex.auth.json} so the {@code {{codexAuth}}}
 * dynamic-header helper (see {@link CodexAuthTemplateHelper}) can refresh/use it on request.
 *
 * <p>The browser PKCE flow (default) prints an authorize URL and waits for the loopback callback; the
 * device-code flow ({@code --device}) prints a verification URL and code and polls until the user
 * authorizes. Both are suitable for headless terminals; the transcript carries the link so a TUI can
 * render it clickable and the web surface can open it in a new tab.</p>
 */
@ApplicationScoped
public class CodexLoginCommands implements SlashCommandSet {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final CodexLoginService loginService;

    public CodexLoginCommands(final CodexLoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public String title() {
        return "Codex authentication";
    }

    @Override
    public List<SlashCommand> commands() {
        final SlashCommand login = SlashCommand.of(
                new SlashCommandSpec(
                        "/codex",
                        "login <provider> [--device] [--enterprise] [--workspace <org-id>]",
                        "Sign in a codex-flavored provider through the ChatGPT/Codex OAuth browser flow (default; --device uses the device-code flow, like the Codex CLI's --device-auth) and cache the session. --enterprise additionally enrolls the account in the Codex Agent Identity flow (for managed/company plans), switching requests to a signed AgentAssertion header. --workspace pins the enterprise enrollment to a specific workspace/organization (must be one the account belongs to).",
                        SlashGroup.OTHER),
                (arg, env) -> CompletableFuture.supplyAsync(() -> runLogin(arg == null ? "" : arg.strip(), env)));
        return List.of(login);
    }

    /** Returns the single value following {@code option} (eg. --workspace X), else null. */
    static String optionValue(final List<String> tokens, final String option) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (option.equalsIgnoreCase(tokens.get(i))) {
                return tokens.get(i + 1);
            }
        }
        return null;
    }

    private ShellOutcome runLogin(final String arg, final ShellEnv env) {
        final var parts = arg.isEmpty() ? List.<String>of() : List.of(WHITESPACE.split(arg));
        final var tokens = new ArrayList<String>();
        for (final var p : parts) {
            if (!p.isBlank()) {
                tokens.add(p);
            }
        }
        if (!tokens.isEmpty() && "login".equalsIgnoreCase(tokens.get(0))) {
            tokens.remove(0); // strip the subcommand word
        }
        final var device = tokens.stream().anyMatch("--device"::equalsIgnoreCase);
        final var enterprise = tokens.stream().anyMatch("--enterprise"::equalsIgnoreCase);
        // --workspace <org-id>: pin the enterprise enrollment to a specific workspace/organization
        final var forcedWorkspace = optionValue(tokens, "--workspace");
        final var provider =
                tokens.stream().filter(p -> !p.startsWith("--")).findFirst().orElse("");
        if (provider.isBlank()) {
            return ShellOutcome.error("/codex login expects a provider name, eg. /codex login default");
        }
        try {
            if (enterprise) {
                final var store = loginService.signInAgent(
                        provider,
                        device,
                        url -> {
                            if (env != null) {
                                env.frontend().openExternalUrl(url);
                                env.frontend().transcript("<" + url + ">\n\nWaiting for authorization...");
                            }
                        },
                        b -> false,
                        forcedWorkspace);
                return ShellOutcome.status("Codex enterprise (Agent Identity) login complete for provider '"
                        + provider + "' (account: " + (store.accountId() == null ? "unknown" : store.accountId())
                        + (store.agentIdentity() != null
                                        && store.agentIdentity().planType() != null
                                ? ", plan: " + store.agentIdentity().planType()
                                : "")
                        + "). Requests will use the signed AgentAssertion header.");
            }
            final var store = loginService.signIn(
                    provider,
                    device,
                    url -> {
                        if (env != null) {
                            env.frontend().openExternalUrl(url);
                            env.frontend().transcript("<" + url + ">\n\nWaiting for authorization...");
                        }
                    },
                    b -> false);
            return ShellOutcome.status("Codex login complete for provider '" + provider + "' (account: "
                    + (store.accountId() == null ? "unknown" : store.accountId()) + ").");
        } catch (final RuntimeException e) {
            return ShellOutcome.error("codex login failed: " + e.getMessage());
        }
    }
}
