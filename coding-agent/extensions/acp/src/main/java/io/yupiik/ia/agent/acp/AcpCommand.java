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

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.ia.agent.core.llm.ApiFlavor;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.ia.agent.core.llm.ChatRoutes;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.llm.CodexLoginService;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@DefaultScoped
@Command(
        name = "acp",
        description =
                "Run the Agent Client Protocol v1 over stdio (for Jetbrains Air and other ACP clients). All modes accept --sandbox true|false (yca.sandbox.enabled); sandbox policy is independent of tool permissions.")
public class AcpCommand implements Runnable {
    // Shared by the adapter and the same-package generated Fusion configuration factory.
    static final List<String> DEFAULT_ELICITATION_SENSITIVE_MARKERS = List.of(
            "passphrase",
            "recoverycode",
            "backupcode",
            "payment",
            "creditcard",
            "debitcard",
            "cardnumber",
            "cardverification",
            "securitycode",
            "onetimecode");
    static final List<String> DEFAULT_ELICITATION_SENSITIVE_WORDS = List.of("pin", "cvv", "cvc", "otp");

    private final AcpServer server;

    private final AcpStdio stdio;
    private final Conf conf;
    private final ProviderManager providers;
    private final ChatClient chat;
    private final CodexLoginService login;
    private final ExecutorService executor;

    public AcpCommand(
            final Conf conf,
            final AcpServer server,
            final AcpStdio stdio,
            final ProviderManager providers,
            final ChatClient chat,
            final CodexLoginService login,
            final ExecutorService executor) {
        this.conf = conf;
        this.providers = providers;
        this.stdio = stdio;
        this.server = server;
        this.chat = chat;
        this.login = login;
        this.executor = executor;
    }

    @Override
    public void run() {
        final var selection = new ChatSettings(conf.provider(), conf.model(), conf.variant(), null);
        final var settings = "default".equalsIgnoreCase(selection.variant()) ? selection.withVariant(null) : selection;
        if (settings.providerId() != null
                && providers.providers().stream()
                        .noneMatch(provider -> settings.providerId().equals(provider.id()))) {
            throw new IllegalArgumentException("Unknown ACP provider: " + settings.providerId());
        }
        if (conf.login()) {
            if (conf.workspace() != null && !conf.enterprise()) {
                throw new IllegalArgumentException("Workspace selection requires enterprise login.");
            }
            login(settings);
            return; // Login-only processes never open the JSON-RPC server or protocol streams.
        }
        if (settings.variant() != null) {
            final var available = chat.variants(settings).stream()
                    .map(variant -> variant.name())
                    .toList();
            if (!available.contains(settings.variant())) {
                throw new IllegalArgumentException("Unknown ACP variant '" + settings.variant() + "' for model '"
                        + chat.model(settings) + "'. Available variants: default"
                        + (available.isEmpty() ? "" : ", " + String.join(", ", available)));
            }
        }
        server.run(System.in, stdio.protocolOutput(), settings, conf.sandbox());
    }

    private void login(final ChatSettings settings) {
        final var provider = chat.provider(settings);
        if (login == null
                || provider == null
                || (provider.api() != ApiFlavor.CODEX
                        && ChatRoutes.detectFlavor(provider.baseUrl()) != ApiFlavor.CODEX)) {
            throw new IllegalArgumentException("Terminal login requires an existing configured Codex provider.");
        }
        final var live = new AtomicBoolean(true);
        final var attempt = login.newAttempt(
                new CodexLoginService.LoginRequest(
                        provider.id() == null || provider.id().isBlank() ? "default" : provider.id(),
                        conf.device(),
                        conf.enterprise(),
                        conf.workspace()),
                new CodexLoginService.LoginListener() {
                    @Override
                    public void browser(final String url) {
                        instruction("Authorize Codex in your browser:\n" + url);
                    }

                    @Override
                    public void device(final CodexLoginService.DeviceChallenge challenge) {
                        instruction("Authorize Codex at:\n" + challenge.verificationUrl() + "\nUser code: "
                                + challenge.userCode());
                    }

                    private void instruction(final String text) {
                        if (live.get()) {
                            System.err.println(text);
                            if (System.err.checkError()) {
                                throw new IllegalStateException("Authentication instruction delivery failed");
                            }
                        }
                    }
                });
        final var hook = new Thread(
                () -> {
                    live.set(false);
                    attempt.cancel();
                    try {
                        attempt.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    } catch (final Exception ignored) {
                        // Best effort bounded process shutdown, never claim an uncooperative worker stopped.
                    }
                },
                "yca-acp-login-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            attempt.start(executor);
            attempt.drained().toCompletableFuture().get();
            final var result = attempt.completion().toCompletableFuture().get();
            if (result.state() != CodexLoginService.LoginState.SUCCEEDED || !result.credentialsCommitted()) {
                throw new IllegalStateException("Codex login failed or was cancelled.");
            }
        } catch (final InterruptedException interrupted) {
            attempt.cancel();
            try {
                attempt.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
            } catch (final Exception ignored) {
                // The owning process exits with failure; do not close the borrowed executor/client.
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Codex login was interrupted.");
        } catch (final ExecutionException failure) {
            throw new IllegalStateException("Codex login failed.");
        } finally {
            live.set(false);
            attempt.close();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (final IllegalStateException ignored) {
                // Process shutdown already owns the hook.
            }
        }
    }

    @RootConfiguration("-")
    public record Conf(
            @Property(
                    documentation =
                            "Configured provider id for ACP chat sessions; defaults to the normal YCA selection.")
            String provider,

            @Property(
                    documentation =
                            "Main chat model override for ACP sessions; background models retain their JSON configuration.")
            String model,

            @Property(
                    documentation =
                            "Named tuning variant for the effective chat model; default restores configured defaults. Background models retain their JSON configuration.")
            String variant,

            @Property(
                    documentation = "Run only Codex login for the existing configured provider, then exit.",
                    defaultValue = "false")
            boolean login,

            @Property(
                    documentation = "Use the Codex device authorization flow for login-only mode.",
                    defaultValue = "false")
            boolean device,

            @Property(
                    documentation = "Enroll Codex Agent Identity after login in login-only mode.",
                    defaultValue = "false")
            boolean enterprise,

            @Property(documentation = "Workspace/organization to use for enterprise login-only enrollment.")
            String workspace,

            @Property(
                    defaultValue = "io.yupiik.ia.agent.acp.AcpCommand.DEFAULT_ELICITATION_SENSITIVE_MARKERS",
                    documentation = "ACP-only additional sensitive form markers, matched as literal substrings after "
                            + "retaining only ASCII letters/digits and ignoring case. Replaces the ACP defaults, "
                            + "not the common rules from yca.secrets.environment. An empty list disables only these additions; "
                            + "it does not approve credential collection.")
            List<String> elicitationSensitiveMarkers,

            @Property(
                    defaultValue = "io.yupiik.ia.agent.acp.AcpCommand.DEFAULT_ELICITATION_SENSITIVE_WORDS",
                    documentation = "ACP-only additional sensitive form words (PIN, CVV, CVC, OTP by default), matched "
                            + "literally as whole words ignoring case, with punctuation/underscores as separators, never as "
                            + "regular expressions. Replaces the ACP defaults, not the common rules from "
                            + "yca.secrets.environment. An empty list disables only "
                            + "these additions; generic credential forms remain unsupported.")
            List<String> elicitationSensitiveWords,

            @Property(
                    defaultValue = "null",
                    documentation = "Override sandbox enablement for ACP sessions; defaults to yca.sandbox.enabled. "
                            + "Sandbox policy is independent of tool permissions.")
            Boolean sandbox) {
        public Conf(
                final String provider,
                final String model,
                final String variant,
                final boolean login,
                final boolean device,
                final boolean enterprise,
                final String workspace,
                final List<String> elicitationSensitiveMarkers,
                final List<String> elicitationSensitiveWords) {
            this(
                    provider,
                    model,
                    variant,
                    login,
                    device,
                    enterprise,
                    workspace,
                    elicitationSensitiveMarkers,
                    elicitationSensitiveWords,
                    null);
        }
    }
}
