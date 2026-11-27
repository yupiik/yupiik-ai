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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.logging.jul.formatter.InlineFormatter;
import io.yupiik.logging.jul.handler.StandardHandler;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Default JUL configuration of the runtime: once the startup is complete, the
 * root logger logs <em>inline</em> on the standard streams instead of the JDK
 * {@code ConsoleHandler} default. "Inline" means a single line per record -
 * timestamp, level and logger name and message on one row, stack traces appended
 * after - produced by the {@link InlineFormatter} on a {@link StandardHandler}
 * (INFO and below on {@code System.out}, WARNING and above on {@code System.err}).
 * The JDK default {@code ConsoleHandler} (attached to the root logger by the JDK
 * itself as soon as the {@link LogManager} initializes) is removed so each record
 * is printed exactly once, in the inline format.
 * <p>
 * The installation runs on the {@link Start} event with the earliest order
 * ({@link Integer#MIN_VALUE}) so it happens <em>after</em> the
 * {@link HackStdIo} takeover ({@link StdIoControl} installs on the
 * {@code ConfigurationRegistration} event, which always fires before
 * {@code Start}): the handler is then created against the streams owned by the
 * terminal, so the CLI REPL keeps logging inside its prompt sink and the `run`
 * command on its owned streams.
 * <p>
 * The user configuration is always respected and wins over this default:
 * <ul>
 *   <li>{@code yca.logging.auto=false} disables the whole takeover;</li>
 *   <li>a {@code -Djava.util.logging.config.file} property <em>and</em> the JDK
 *       default (non-subclassed) {@link LogManager} means JUL was given an
 *       explicit configuration file - the JDK already applied it - so nothing
 *       is overridden here.</li>
 * </ul>
 * <p>
 * The installed handler is exposed through {@link #installedInlineHandler()} so
 * terminal-owning frontends (the TUI) can detach it while they capture the
 * records into their own UI and re-attach it on exit; {@link #onStop} removes
 * whatever is still attached when the container closes so repeated boots in one
 * JVM (tests) never accumulate handlers on the JVM-wide root logger.
 */
@ApplicationScoped
public class AutoLogConfigurer {
    /**
     * The inline handler installed on the root logger by {@link #onStart}, or
     * {@code null} when the configuration skipped the installation. Stored on
     * this instance so a terminal-owning frontend can detach it (and
     * {@link #onStop} can restore a clean state): not a registry, at most one
     * handler is ever installed and it belongs to this container's instance.
     */
    private Handler installed;

    private final CodingAgentConfiguration configuration;

    /** Bean proxy constructor: leaves the real field unset, the active instance is created by the injection point. */
    protected AutoLogConfigurer() {
        this.configuration = null;
    }

    public AutoLogConfigurer(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    public void onStart(@OnEvent @Order(Integer.MIN_VALUE) final Start start) {
        if (!configuration.logging().auto() || shouldKeepJdkDefaults()) {
            return;
        }
        final var root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        // the JDK attaches its default ConsoleHandler to the root logger when the
        // LogManager initializes; without removing it every record would print twice
        // (once JDK-formatted, once inline) until a terminal-owning frontend or the
        // process exit replaces the setup
        // Detect duplicates too: the GraalVM-native build can resolve this @ApplicationScoped
        // bean twice (two runtime contexts), running onStart twice and stacking two inline
        // StandardHandlers - every JUL record would then print twice with the identical
        // timestamp on both lines. StandardHandler is only ever installed by this configurer
        // (or an explicit user logging.properties, which is excluded above via
        // shouldKeepJdkDefaults), so removing any pre-existing one before adding ours keeps
        // exactly one inline handler on the root whatever the graph resolution count.
        for (final var previous : root.getHandlers()) {
            if (previous instanceof ConsoleHandler || previous instanceof StandardHandler) {
                root.removeHandler(previous);
            }
        }
        final var handler = new StandardHandler();
        handler.setLevel(Level.INFO);
        handler.setFormatter(new InlineFormatter());
        root.addHandler(handler);
        installed = handler;
    }

    /**
     * Whether JUL already carries an explicit user configuration that this
     * default must not fight: a {@code java.util.logging.config.file} system
     * property with the plain JDK {@link LogManager} (the property was applied
     * by the JDK). A customized {@link LogManager} with no configuration file
     * (e.g. the yupiik-logging manager) still gets the inline fallback so
     * headless commands log readably out of the box.
     */
    private static boolean shouldKeepJdkDefaults() {
        if (System.getProperty("java.util.logging.config.file") != null
                && LogManager.getLogManager().getClass() == LogManager.class) {
            return true;
        }
        // The YupiikLogManager (activated via `-Djava.util.logging.manager=...YupiikLogManager`,
        // as the GraalVM-native build does) already installs its own inline handler on the loggers
        // it manages and prints every record exactly once in the inline format. Adding our own
        // StandardHandler here on top would publish each record through BOTH the Yupiik-managed
        // path and this root handler, printing every line twice with identical timestamps (the
        // native "MCP ready / CodeGraph load duplicated" bug). Let the manager's own inline output
        // win, exactly like an explicit JUL configuration would. The TUI tolerates a null
        // installed handler (it guards with `installedInlineHandler() != null`) and wires its own
        // log-redirect handlers regardless.
        return LogManager.getLogManager().getClass().getName().equals("io.yupiik.logging.jul.YupiikLogManager");
    }

    /**
     * @return the root inline handler installed by this configurer, or {@code null}
     *         when the installation was skipped (not yet started,
     *         {@code yca.logging.auto=false} or an explicit JUL configuration).
     */
    public Handler installedInlineHandler() {
        return installed;
    }

    public void onStop(@OnEvent @Order(Integer.MAX_VALUE) final Stop stop) {
        final var handler = installed;
        if (handler != null) {
            Logger.getLogger("").removeHandler(handler);
            installed = null;
        }
    }
}
