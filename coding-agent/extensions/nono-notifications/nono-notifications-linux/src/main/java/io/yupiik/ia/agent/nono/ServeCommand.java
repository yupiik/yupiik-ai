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
package io.yupiik.ia.agent.nono;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.http.server.spi.Endpoint;
import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.nono.ui.ApprovalUi;
import io.yupiik.ia.agent.nono.ui.NotifySendApprovalUi;
import io.yupiik.ia.agent.nono.ui.ZenityApprovalUi;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

@DefaultScoped
@Command(
        name = {"nono", "serve"},
        description = "Start the experimental host-side nono approval companion (no sandbox launcher).")
public class ServeCommand implements Runnable {
    private final Conf conf;
    private final JsonMapper mapper;

    public ServeCommand(final Conf conf, final JsonMapper mapper) {
        this.conf = conf;
        this.mapper = mapper;
    }

    @Override
    public void run() {
        if (!System.getProperty("os.name").equals("Linux")
                || conf.timeoutSeconds() < 2
                || conf.timeoutSeconds() > 600) {
            throw new IllegalArgumentException("Linux and a 2..600 second timeout are required");
        }
        try (final var ui = createUi();
                final var executor = Executors.newVirtualThreadPerTaskExecutor();
                final var timer = Executors.newSingleThreadScheduledExecutor();
                final var state = new PrivateState(
                        conf.directory() == null || conf.directory().isBlank() ? null : Path.of(conf.directory()),
                        mapper)) {
            try (final var coordinator = new ApprovalCoordinator(ui, message -> {}, state, conf.timeoutSeconds());
                    final var server = new JreHttpServer(
                            new JreHttpServerConfiguration(conf.host(), 0, false, executor),
                            List.of(Endpoint.of(
                                    request -> true,
                                    new ApprovalEndpoint(state, coordinator, timer, mapper)::handle,
                                    0)))) {
                server.start();
                final var hook = new Thread(
                        () -> {
                            coordinator.close();
                            server.close();
                            try {
                                state.close();
                            } catch (final IOException error) {
                                System.err.println(
                                        "Private companion cleanup failed; remove the launch files after stopping the sandbox.");
                            }
                        },
                        "nono-companion-shutdown");
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    state.publish(conf.host(), server.port(), conf.timeoutSeconds());
                    // Print only the private directory, never the backend URL or credentials.
                    System.out.println("Nono approval companion ready; private files in " + state.directory());
                    server.await();
                } finally {
                    coordinator.close();
                    try {
                        Runtime.getRuntime().removeShutdownHook(hook);
                    } catch (final IllegalStateException ignored) {
                        // JVM is shutting down.
                    }
                }
            }
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (final IOException error) {
            throw new IllegalStateException("Cannot start nono companion: " + error.getMessage(), error);
        }
    }

    private ApprovalUi createUi() {
        return switch (conf.ui()) {
            case ZENITY -> new ZenityApprovalUi(Path.of(conf.zenity()));
            case NOTIFY_SEND -> new NotifySendApprovalUi(Path.of(conf.notifySend()));
        };
    }

    public enum Ui {
        ZENITY,
        NOTIFY_SEND
    }

    @RootConfiguration("-")
    public record Conf(
            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Private launch directory for credentials and audit; cleaned on shutdown. "
                                    + "By default creates a fresh directory under ~/.yupiik/coding-agent/nono. "
                                    + "An override must not exist and needs an existing private parent. Keep outside sandbox grants.")
            String directory,

            @Property(defaultValue = "\"/usr/bin/zenity\"", documentation = "Trusted absolute Zenity executable path.")
            String zenity,

            @Property(defaultValue = "60", documentation = "Maximum end-to-end approval budget in seconds (2..600).")
            int timeoutSeconds,

            @Property(
                    defaultValue = "\"127.0.0.1\"",
                    documentation = "Numeric loopback bind address, e.g. 127.0.0.1 or ::1.")
            String host,

            @Property(
                    defaultValue = "ServeCommand.Ui.ZENITY",
                    documentation = "Approval UI: ZENITY or NOTIFY_SEND (requires notification actions).")
            Ui ui,

            @Property(
                    defaultValue = "\"/usr/bin/notify-send\"",
                    documentation = "Trusted absolute notify-send executable path.")
            String notifySend) {
        public Conf {
            if (ui == null) {
                throw new IllegalArgumentException("UI is required");
            }
            if (host == null
                    || host.contains("[")
                    || host.contains("%")
                    || !InetAddress.ofLiteral(host).isLoopbackAddress()) {
                throw new IllegalArgumentException("Host must be a numeric loopback address without brackets or scope");
            }
        }
    }
}
