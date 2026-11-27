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
package io.yupiik.ia.agent.core.notifications;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.NotificationsOnConfiguration;
import io.yupiik.ia.agent.core.permissions.ApprovalGate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Fires an OS notification when a turn ends or needs input, opt-in via {@code yca.notifications.enabled} (disabled by
 * default). A custom {@code yca.notifications.command} replaces the built-in OS integration and receives the
 * payload as environment variables; otherwise a best-effort default sink per OS is used. Every path is async
 * (run on the shared executor) and swallows failures so a notification can never fail or slow a turn.
 *
 * <p>The runtime command is resolved once ({@link #defaultSink()} lazily at first delivery) and then the
 * resolved executable is used directly on every later delivery - no per-call OS detection. The custom command
 * is taken verbatim from the configuration; the default sink derives the OS tool and always appends
 * {@code <title> <body>} as positional arguments.</p>
 *
 * <p>On Linux the notify-send sink additionally receives the
 * {@code x-canonical-private-synchronous} hint so notifications of the same session replace each other in the
 * notification center (it takes a string key, unlike {@code --replace-id} which needs a numeric id), and
 * {@code -n <appIconPath>} to brand the popup with the YCA
 * icon (bundled and materialized to the temp directory at runtime). {@code -i} keeps its status-icon meaning
 * (emblem-default / dialog-error). All notify-send-specific flags are scoped to that sink.</p>
 */
@ApplicationScoped
public class NotificationService {
    private static final Pattern CONTROL_OR_WHITESPACE = Pattern.compile("[\\p{Cntrl}\\s]+");
    private static final Logger LOGGER = Logger.getLogger(NotificationService.class.getName());

    /** Bundled app-icon resource, materialized to a temp file so notify-send {@code -n} can reference it. */
    private static final String APP_ICON_RESOURCE = "/io/yupiik/ia/agent/core/notifications/yca.png";

    public static final String ENV_SESSION = "YCA_NOTIFICATION_SESSION";
    public static final String ENV_TITLE = "YCA_NOTIFICATION_TITLE";
    public static final String ENV_MODEL = "YCA_NOTIFICATION_MODEL";
    public static final String ENV_PROVIDER = "YCA_NOTIFICATION_PROVIDER";
    public static final String ENV_SUCCESS = "YCA_NOTIFICATION_SUCCESS";
    public static final String ENV_BODY = "YCA_NOTIFICATION_BODY";
    public static final String ENV_TYPE = "YCA_NOTIFICATION_TYPE";
    public static final String ENV_TOOL = "YCA_NOTIFICATION_TOOL";
    public static final String ENV_ORIGIN = "YCA_NOTIFICATION_ORIGIN";
    public static final String ENV_SOURCE = "YCA_NOTIFICATION_SOURCE";
    public static final String ENV_TIMESTAMP = "YCA_NOTIFICATION_TIMESTAMP";

    private final AtomicLong eventTimestamp = new AtomicLong();

    private final boolean enabled;
    private final NotificationsOnConfiguration on;
    private final boolean custom;
    private final String[] sink;
    /** True when the resolved default sink is the Linux notify-send tool (only it gets the -h hint and -n). */
    private final boolean notifySend;

    private final ExecutorService executor;
    private final ExecFn exec;
    /** Optional override for the temp dir the app icon is materialized into (defaults to java.io.tmpdir). */
    private final Path iconDir;
    /** Lazily resolved default-sink app-icon argv fragment (null until resolved, empty array when skipped). */
    private volatile String[] appIconArgs;

    @FunctionalInterface
    protected interface ExecFn {
        Process start(List<String> command, Map<String, String> env) throws Exception;
    }

    public NotificationService(final CodingAgentConfiguration configuration, final ExecutorService executor) {
        this(configuration, executor, NotificationService::processBuilderStart);
    }

    protected NotificationService(
            final CodingAgentConfiguration configuration, final ExecutorService executor, final ExecFn exec) {
        this(configuration, executor, exec, null);
    }

    protected NotificationService(
            final CodingAgentConfiguration configuration,
            final ExecutorService executor,
            final ExecFn exec,
            final Path iconDir) {
        final var notifications =
                configuration == null || configuration.notifications() == null ? null : configuration.notifications();
        this.enabled = notifications != null && notifications.enabled();
        this.on = notifications == null ? new NotificationsOnConfiguration(null, null) : notifications.on();
        final var cfgCommand =
                notifications == null || notifications.command() == null ? null : notifications.command();
        this.custom = cfgCommand != null && !cfgCommand.isEmpty();
        this.sink = custom ? cfgCommand.toArray(String[]::new) : defaultSink();
        this.notifySend = !custom && sink.length > 0 && sink[0].contains("notify-send");
        this.executor = executor;
        this.exec = exec;
        this.iconDir = iconDir;
    }

    /**
     * Sends a turn-end notification, best-effort and asynchronous. No-op when notifications are disabled, and
     * never throws: any failure is logged and dropped, so the turn is never impacted.
     */
    public void onTurnEnd(
            final String sessionId,
            final String title,
            final String model,
            final String provider,
            final boolean success,
            final String body) {
        if (!enabled || !on.event().turnEnd()) {
            return;
        }
        deliver(
                sessionId,
                title,
                body,
                success ? "emblem-default" : "dialog-error",
                env(sessionId, title, model, provider, success, body));
    }

    /** Pending input: only counts and session labels, never question text or choices. */
    public void onInputRequired(
            final String sessionId, final String sessionTitle, final int questionCount, final String source) {
        if (!enabled || !("btw".equals(source) ? on.tools().btw() : on.tools().ask_user())) {
            return;
        }
        final var count = Math.max(1, questionCount);
        interaction(
                sessionId,
                sessionTitle,
                "input_required",
                "",
                source,
                "Input required: " + count + (count == 1 ? " question waiting." : " questions waiting.")
                        + "\nReturn to YCA to answer.",
                null);
    }

    /** Pending approval: only the tool label, never its arguments or permission details. */
    public void onApprovalRequired(
            final String sessionId,
            final String sessionTitle,
            final String tool,
            final String source,
            final ApprovalGate.Origin origin) {
        if (!enabled || !on.event().approval()) {
            return;
        }
        final var label = label(tool, 64);
        interaction(
                sessionId,
                sessionTitle,
                "approval_required",
                label,
                source,
                (origin == ApprovalGate.Origin.SANDBOX
                                ? "Sandbox approval required"
                                : "Tool permission approval required")
                        + (label.isEmpty() ? "" : " for " + label) + ".\nReturn to YCA to review.",
                origin);
    }

    /** Clears existing session reminders in custom sinks, without creating a desktop popup. */
    public void onSessionInteraction(final String sessionId) {
        if (!enabled || !custom || sessionId == null || sessionId.isBlank()) {
            return;
        }
        deliver(
                sessionId,
                "",
                "",
                "",
                Map.of(
                        ENV_SESSION, sessionId,
                        ENV_TYPE, "session_acknowledged",
                        ENV_SOURCE, "main",
                        ENV_TITLE, "",
                        ENV_BODY, "",
                        ENV_MODEL, "",
                        ENV_PROVIDER, "",
                        ENV_SUCCESS, "",
                        ENV_TOOL, ""));
    }

    private void interaction(
            final String sessionId,
            final String sessionTitle,
            final String type,
            final String tool,
            final String source,
            final String message,
            final ApprovalGate.Origin origin) {
        final var title = label(sessionId, 64)
                + (sessionTitle == null || sessionTitle.isBlank() ? "" : " - " + label(sessionTitle, 120));
        final var body = ("btw".equals(source) ? "/btw: " : "") + message;
        final var env = new HashMap<>(Map.of(
                ENV_SESSION,
                nullToEmpty(sessionId),
                ENV_TITLE,
                title,
                ENV_MODEL,
                "",
                ENV_PROVIDER,
                "",
                ENV_SUCCESS,
                "",
                ENV_BODY,
                body,
                ENV_TYPE,
                type,
                ENV_TOOL,
                tool,
                ENV_SOURCE,
                "btw".equals(source) ? "btw" : "main"));
        if (origin != null) {
            env.put(ENV_ORIGIN, origin.name());
        }
        deliver(sessionId, title, body, "dialog-warning", env);
    }

    private static String label(final String value, final int limit) {
        if (value == null) {
            return "";
        }
        final var normalized =
                CONTROL_OR_WHITESPACE.matcher(value).replaceAll(" ").strip();
        return normalized.substring(0, Math.min(limit, normalized.length()));
    }

    private static String escapeMarkup(final String value) {
        return nullToEmpty(value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void deliver(
            final String sessionId,
            final String title,
            final String body,
            final String icon,
            final Map<String, String> env) {
        // Stamp before scheduling: publisher processes can start or arrive out of order.
        // Keep successive events distinct even when the wall clock has not ticked yet.
        final var timestamp =
                eventTimestamp.updateAndGet(previous -> Math.max(System.currentTimeMillis(), previous + 1));
        final var stamped = new HashMap<>(env);
        stamped.put(ENV_TIMESTAMP, Long.toString(timestamp));
        final var payload = Map.copyOf(stamped);
        try {
            if (executor == null) {
                runAndSwallow(() -> deliverNow(sessionId, title, body, icon, payload));
            } else {
                executor.execute(() -> runAndSwallow(() -> deliverNow(sessionId, title, body, icon, payload)));
            }
        } catch (final Throwable e) {
            LOGGER.log(Level.FINE, "notification could not be scheduled", e);
        }
    }

    private void deliverNow(
            final String sessionId,
            final String title,
            final String body,
            final String icon,
            final Map<String, String> env) {
        final List<String> argv;
        if (custom) {
            // the custom command receives the payload via env only, its argv stays as configured.
            argv = List.of(sink);
        } else if (notifySend) {
            // notify-send sink: <-h string:x-canonical-private-synchronous:<sessionId>> <--i statusIcon>
            // [<-n appIcon>] <title> <body>. The synchronous hint makes same-session notifications replace each
            // other in the center (it takes a string key, unlike --replace-id which needs a numeric id), -i keeps
            // its status-icon meaning, and -n carries the YCA app brand. The custom-command path above is the
            // supported override for full configurability.
            final var appIcon = appIconArgs();
            final var args = new ArrayList<String>(sink.length + 8);
            args.addAll(List.of(sink));
            args.add("-h");
            args.add("string:x-canonical-private-synchronous:" + replaceId(sessionId));
            args.add("-i");
            args.add(icon);
            if (appIcon.length > 0) {
                args.add("-n");
                args.add(appIcon[1]);
            }
            args.add(escapeMarkup(title));
            args.add(escapeMarkup(body));
            argv = List.copyOf(args);
        } else {
            // other OS default sinks keep their existing <-i <icon>> <title> <body> shape untouched.
            final var args = new String[sink.length + 4]; // sink + (-i icon) + title + body
            System.arraycopy(sink, 0, args, 0, sink.length);
            args[sink.length] = "-i";
            args[sink.length + 1] = icon;
            args[sink.length + 2] = title == null ? "" : title;
            args[sink.length + 3] = body == null ? "" : body;
            argv = List.of(args);
        }
        run(argv, env);
    }

    private static String replaceId(final String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "yca";
        }
        return sessionId;
    }

    /**
     * Returns the notify-send {@code -n} app-icon argv fragment as {@code {"-n", <iconPath>}}, resolved once and
     * reused on every delivery. An empty array means the app icon is skipped (resource missing or materialization
     * failure) - never an error.
     */
    private String[] appIconArgs() {
        var args = appIconArgs;
        if (args == null) {
            synchronized (this) {
                args = appIconArgs;
                if (args == null) {
                    args = resolveAppIconArgs();
                    appIconArgs = args;
                }
            }
        }
        return args;
    }

    private String[] resolveAppIconArgs() {
        try (final var in = NotificationService.class.getResourceAsStream(APP_ICON_RESOURCE)) {
            if (in == null) {
                return new String[0];
            }
            final var dir = iconDir();
            Files.createDirectories(dir);
            final var icon = dir.resolve("yca.png");
            try (final var out = Files.newOutputStream(icon)) {
                in.transferTo(out);
            }
            return new String[] {"-n", icon.toString()};
        } catch (final Throwable e) {
            // best-effort: a missing/unwritable icon must never fail the notification, just drop the brand icon.
            LOGGER.log(Level.FINE, "could not materialize the notification app-icon", e);
            return new String[0];
        }
    }

    private Path iconDir() {
        if (iconDir != null) {
            return iconDir;
        }
        final var base = System.getProperty("java.io.tmpdir");
        return Path.of(base == null || base.isBlank() ? "/tmp" : base).resolve("yca-icons");
    }

    private void run(final List<String> argv, final Map<String, String> env) {
        try {
            final var process = exec.start(argv, env);
            if (process != null) {
                try {
                    if (!process.waitFor(30, TimeUnit.SECONDS)) {
                        process.destroy();
                    } else if (process.exitValue() != 0) {
                        LOGGER.warning(() -> "Failed to deliver notification: exit-code=" + process.exitValue() + "\n"
                                + tryReadError(process));
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (final Throwable e) {
            LOGGER.log(Level.FINE, "notification could not be delivered", e);
        }
    }

    private String tryReadError(final Process p) {
        try {
            var content = toString(p.getErrorStream());
            if (content != null) {
                return content;
            }
            // else try stdout
            content = toString(p.getInputStream());
            if (content != null) {
                return content;
            }
        } catch (final Throwable t) {
            // best effort
        }
        return "";
    }

    private String toString(final InputStream stream) throws IOException {
        if (stream == null) {
            return null;
        }
        try (stream) {
            final var content = new String(stream.readAllBytes(), UTF_8);
            if (!content.isBlank()) {
                return content;
            }
        }
        return null;
    }

    private static void runAndSwallow(final Runnable task) {
        try {
            task.run();
        } catch (final Throwable e) {
            // best-effort: never surface a notification failure, but keep it debuggable
            LOGGER.log(Level.FINEST, "notification run failed", e);
        }
    }

    private static Map<String, String> env(
            final String sessionId,
            final String title,
            final String model,
            final String provider,
            final boolean success,
            final String body) {
        return Map.of(
                ENV_SESSION, nullToEmpty(sessionId),
                ENV_TITLE, nullToEmpty(title),
                ENV_MODEL, nullToEmpty(model),
                ENV_PROVIDER, nullToEmpty(provider),
                ENV_SUCCESS, Boolean.toString(success),
                ENV_BODY, nullToEmpty(body),
                ENV_TYPE, "turn_end");
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static Process processBuilderStart(final List<String> argv, final Map<String, String> env)
            throws Exception {
        final var builder = new ProcessBuilder(argv);
        builder.environment().putAll(env);
        return builder.start();
    }

    /**
     * Resolves the default OS sink tool, most specific first. Computed once at construction and reused on every
     * delivery - no per-call OS detection. Native image and headless JVM paths use the portable OS process tool
     * (AWT {@code SystemTray} is skipped there, matching the clipboard's native-image guard).
     */
    private static String[] defaultSink() {
        final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new String[] {"powershell.exe"};
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new String[] {"osascript", "-e", "display notification"};
        }
        return new String[] {"notify-send", "--app-name=yca"};
    }
}
