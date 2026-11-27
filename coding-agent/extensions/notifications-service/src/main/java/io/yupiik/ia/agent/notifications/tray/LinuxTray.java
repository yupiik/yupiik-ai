/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications.tray;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.yupiik.ia.agent.notifications.Notice;
import io.yupiik.ia.agent.notifications.NotificationPresentation;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** GTK3 details with GLib/Gio or legacy GTK AppIndicator menus; no AWT or JNI bridge. */
public final class LinuxTray implements NativeTray {
    private static final FunctionDescriptor INT_POINTER_POINTER = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor POINTER_POINTER_POINTER_INT =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor POINTER = FunctionDescriptor.of(ADDRESS);
    private static final FunctionDescriptor POINTER_POINTER = FunctionDescriptor.of(ADDRESS, ADDRESS);
    private static final FunctionDescriptor POINTER_POINTER_INT = FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor POINTER_INT = FunctionDescriptor.of(ADDRESS, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_INT = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_POINTER = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_POINTER = FunctionDescriptor.ofVoid(ADDRESS);
    private static final FunctionDescriptor VOID_POINTER_POINTER_INT =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_INT_INT =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_INT_POINTER =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor POINTER_POINTER_POINTER = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor INT = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor INT_INT = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor INT_POINTER = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor INT_POINTER_INT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_POINTER_POINTER_POINTER =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor LONG_POINTER_POINTER_POINTER_POINTER_POINTER_INT =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodType ACTION_TYPE =
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class);
    private static final MethodType TICK_TYPE = MethodType.methodType(int.class, MemorySegment.class);
    private static final MethodHandle ACKNOWLEDGE;
    private static final MethodHandle QUIT;
    private static final MethodHandle TICK;
    private static final MethodHandle OPEN;
    private static final MethodHandle DISMISS;
    private static final MethodHandle RESPONSE;

    static {
        try {
            final var lookup = MethodHandles.lookup();
            ACKNOWLEDGE = lookup.findStatic(LinuxTray.class, "acknowledge", ACTION_TYPE);
            QUIT = lookup.findStatic(LinuxTray.class, "quit", ACTION_TYPE);
            TICK = lookup.findStatic(LinuxTray.class, "tick", TICK_TYPE);
            OPEN = lookup.findStatic(LinuxTray.class, "open", ACTION_TYPE);
            DISMISS = lookup.findStatic(LinuxTray.class, "dismiss", ACTION_TYPE);
            RESPONSE = lookup.findStatic(LinuxTray.class, "response", VOID_POINTER_INT_POINTER.toMethodType());
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static LinuxTray active;
    // GObject retains the registered AppIndicator GType and its native code pointers for the process lifetime.
    // Keep its library loaded, and never register the incompatible implementation in the same process.
    private static String initializedBackend;
    private static SymbolLookup initializedLibrary;
    private static Arena initializedLibraryArena;
    private final Arena arena = Arena.ofShared();
    private final Linker linker = Linker.nativeLinker();
    private final Map<String, MethodHandle> functions = new HashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private SymbolLookup gtk;
    private SymbolLookup indicatorLibrary;
    private Arena indicatorArena;
    private String indicatorBackend;
    private boolean gioIndicator;
    private GioNotificationMenu gioMenu;
    private MethodHandle wakeup;
    private MemorySegment context = MemorySegment.NULL;
    private MemorySegment tickStub = MemorySegment.NULL;
    private MemorySegment timeoutSource = MemorySegment.NULL;
    private boolean timeoutSourceDestroyed;
    private MemorySegment indicator = MemorySegment.NULL;
    private MemorySegment menu = MemorySegment.NULL;
    private MemorySegment heading = MemorySegment.NULL;
    private MemorySegment openStub = MemorySegment.NULL;
    private MemorySegment dismissStub = MemorySegment.NULL;
    private MemorySegment responseStub = MemorySegment.NULL;
    private MemorySegment dialog = MemorySegment.NULL;
    private final Map<Long, Notice> menuNotices = new HashMap<>();
    private final List<MemorySegment> noticeItems = new ArrayList<>();
    private final List<Notice> dismissed = new ArrayList<>();
    private Notice requested;
    private Notice viewed;
    private Throwable callbackFailure;
    private final String iconPath;
    private boolean menuDestroyed;
    private boolean acknowledged;
    private boolean closed;

    public LinuxTray(final Path icon) {
        this(icon, Backend.AUTO);
    }

    public LinuxTray(final Path icon, final Backend backend) {
        iconPath = icon.toAbsolutePath().toString();
        try {
            gtk = SymbolLookup.libraryLookup("libgtk-3.so.0", arena);
            loadIndicator(backend);
            if ((int) call(gtk, "gtk_init_check", INT_POINTER_POINTER, MemorySegment.NULL, MemorySegment.NULL) == 0) {
                throw new IllegalStateException(
                        "GTK cannot connect to a desktop display; run serve in the graphical session.");
            }
            if (active != null) {
                throw new IllegalStateException("A Linux tray is already active in this process.");
            }
            active = this;
            context = (MemorySegment) call(gtk, "g_main_context_default", POINTER);
            // Resolve once on the owner thread: wakeup must never access the owner-confined function cache.
            wakeup = linker.downcallHandle(
                    gtk.find("g_main_context_wakeup")
                            .orElseThrow(
                                    () -> new IllegalStateException("Missing native symbol: g_main_context_wakeup")),
                    VOID_POINTER);
            tickStub = linker.upcallStub(TICK, INT_POINTER, arena);
            openStub = linker.upcallStub(OPEN, VOID_POINTER_POINTER, arena);
            dismissStub = linker.upcallStub(DISMISS, VOID_POINTER_POINTER, arena);
            responseStub = linker.upcallStub(RESPONSE, VOID_POINTER_INT_POINTER, arena);
            retainIndicatorLibrary();
            indicator = (MemorySegment) call(
                    indicatorLibrary,
                    "app_indicator_new",
                    POINTER_POINTER_POINTER_INT,
                    arena.allocateFrom("yca-notifications"),
                    arena.allocateFrom(iconPath),
                    0);
            if (indicator.address() == 0) {
                throw new IllegalStateException("AppIndicator creation failed.");
            }
            if (gioIndicator) {
                gioMenu = new GioNotificationMenu(
                        notice -> requested = notice, dismissed::add, () -> acknowledged = true, () -> closed = true);
                call(indicatorLibrary, "app_indicator_set_menu", VOID_POINTER_POINTER, indicator, gioMenu.menu());
                call(indicatorLibrary, "app_indicator_set_actions", VOID_POINTER_POINTER, indicator, gioMenu.actions());
            } else {
                menu = (MemorySegment) call(gtk, "gtk_menu_new", POINTER);
                call(gtk, "g_object_ref_sink", POINTER_POINTER, menu);
                heading = item("YCA - no unread activity", null);
                call(gtk, "gtk_widget_set_sensitive", VOID_POINTER_INT, heading, 0);
                item("Acknowledge unread activity (not approval)", ACKNOWLEDGE);
                item("Quit notifications service", QUIT);
                call(gtk, "gtk_widget_show_all", VOID_POINTER, menu);
                call(indicatorLibrary, "app_indicator_set_menu", VOID_POINTER_POINTER, indicator, menu);
            }
            setIcon("app_indicator_set_attention_icon", "dialog-warning", arena);
            call(indicatorLibrary, "app_indicator_set_status", VOID_POINTER_INT, indicator, 1);
        } catch (final RuntimeException | Error failure) {
            try {
                close();
            } catch (final RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void loadIndicator(final Backend backend) {
        final var candidates =
                switch (backend) {
                    case AUTO ->
                        List.of(
                                "libayatana-appindicator-glib.so.2",
                                "libayatana-appindicator3.so.1",
                                "libappindicator3.so.1");
                    case GLIB -> List.of("libayatana-appindicator-glib.so.2");
                    case GTK3 -> List.of("libayatana-appindicator3.so.1", "libappindicator3.so.1");
                };
        if (initializedBackend != null) {
            if (!candidates.contains(initializedBackend)) {
                throw new IllegalStateException(
                        "Changing the initialized Linux AppIndicator backend requires a process restart.");
            }
            indicatorLibrary = initializedLibrary;
            indicatorBackend = initializedBackend;
            gioIndicator = initializedBackend.equals("libayatana-appindicator-glib.so.2");
            return;
        }
        final var unavailable = new ArrayList<String>();
        for (final var name : candidates) {
            final var candidateArena = Arena.ofShared();
            boolean selected = false;
            try {
                final SymbolLookup candidate;
                try {
                    candidate = SymbolLookup.libraryLookup(name, candidateArena);
                } catch (final IllegalArgumentException missing) {
                    unavailable.add(name + " cannot be loaded");
                    continue;
                }
                final var modern = name.equals("libayatana-appindicator-glib.so.2");
                final var required = new ArrayList<>(List.of(
                        "app_indicator_new",
                        "app_indicator_set_menu",
                        "app_indicator_set_status",
                        "app_indicator_set_title",
                        "app_indicator_set_icon",
                        "app_indicator_set_attention_icon"));
                if (modern) {
                    required.add("app_indicator_set_actions");
                    required.add("app_indicator_set_tooltip");
                }
                final var missing = required.stream()
                        .filter(symbol -> candidate.find(symbol).isEmpty())
                        .toList();
                if (!missing.isEmpty()) {
                    unavailable.add(name + " is missing symbols: " + String.join(", ", missing));
                    continue;
                }
                indicatorLibrary = candidate;
                indicatorBackend = name;
                indicatorArena = candidateArena;
                gioIndicator = modern;
                selected = true;
                return;
            } finally {
                if (!selected) {
                    candidateArena.close();
                }
            }
        }
        throw new IllegalStateException("No usable Linux AppIndicator backend: " + String.join(" AND ", unavailable));
    }

    private void retainIndicatorLibrary() {
        if (initializedBackend == null) {
            initializedBackend = indicatorBackend;
            initializedLibrary = indicatorLibrary;
            initializedLibraryArena = indicatorArena;
            indicatorArena = null;
        }
    }

    private void setIcon(final String function, final String icon, final Arena strings) {
        if (gioIndicator) {
            // Unlike the legacy ABI, GLib requires the third icon-description argument.
            call(
                    indicatorLibrary,
                    function,
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
                    indicator,
                    strings.allocateFrom(icon),
                    MemorySegment.NULL);
        } else {
            call(indicatorLibrary, function, VOID_POINTER_POINTER, indicator, strings.allocateFrom(icon));
        }
    }

    @Override
    public void updateNotices(final List<Notice> notices) {
        if (gioMenu != null) {
            gioMenu.updateNotices(notices);
            return;
        }
        for (final var widget : noticeItems) {
            call(gtk, "gtk_widget_destroy", VOID_POINTER, widget);
        }
        noticeItems.clear();
        menuNotices.clear();
        try (final var strings = Arena.ofConfined()) {
            int position = 1;
            for (final var notice : notices) {
                final var icon =
                        switch (notice.type()) {
                            case "approval_required" -> "dialog-warning";
                            case "input_required" -> "dialog-question";
                            default -> "emblem-ok";
                        };
                noticeItem(notice, NotificationPresentation.preview(notice), icon, openStub, position++, strings);
                noticeItem(notice, "Dismiss", "edit-clear", dismissStub, position++, strings);
            }
        }
        call(gtk, "gtk_widget_show_all", VOID_POINTER, menu);
    }

    @Override
    public List<Notice> dismissed() {
        final var result = List.copyOf(dismissed);
        dismissed.clear();
        return result;
    }

    private void noticeItem(
            final Notice notice,
            final String text,
            final String icon,
            final MemorySegment callback,
            final int position,
            final Arena strings) {
        // GTK3 exports these icons through AppIndicator; the tray host can still hide them.
        final var widget = (MemorySegment)
                call(gtk, "gtk_image_menu_item_new_with_label", POINTER_POINTER, strings.allocateFrom(text));
        final var image = (MemorySegment)
                call(gtk, "gtk_image_new_from_icon_name", POINTER_POINTER_INT, strings.allocateFrom(icon), 1);
        call(gtk, "gtk_image_menu_item_set_image", VOID_POINTER_POINTER, widget, image);
        call(gtk, "gtk_image_menu_item_set_always_show_image", VOID_POINTER_INT, widget, 1);
        call(gtk, "gtk_menu_shell_insert", VOID_POINTER_POINTER_INT, menu, widget, position);
        noticeItems.add(widget);
        menuNotices.put(widget.address(), notice);
        connect(widget, "activate", callback, strings);
    }

    @Override
    public void update(final String tooltip, final boolean attention) {
        try (final var strings = Arena.ofConfined()) {
            if (gioMenu != null) {
                gioMenu.updateSummary(tooltip);
                call(
                        indicatorLibrary,
                        "app_indicator_set_tooltip",
                        VOID_POINTER_POINTER_POINTER_POINTER,
                        indicator,
                        MemorySegment.NULL,
                        strings.allocateFrom(tooltip),
                        strings.allocateFrom(""));
            } else {
                call(gtk, "gtk_menu_item_set_label", VOID_POINTER_POINTER, heading, strings.allocateFrom(tooltip));
            }
            call(
                    indicatorLibrary,
                    "app_indicator_set_title",
                    VOID_POINTER_POINTER,
                    indicator,
                    strings.allocateFrom(tooltip));
            setIcon("app_indicator_set_icon", attention ? "dialog-warning" : iconPath, strings);
            call(indicatorLibrary, "app_indicator_set_status", VOID_POINTER_INT, indicator, attention ? 2 : 1);
        }
    }

    @Override
    public void poll() {
        checkCallback();
        // Bound dispatch work so a busy desktop cannot starve mailbox processing or shutdown.
        for (int i = 0; i < 64 && (int) call(gtk, "gtk_events_pending", INT) != 0; i++) {
            call(gtk, "gtk_main_iteration_do", INT_INT, 0);
        }
        checkCallback();
        if (requested != null && !closed) {
            final var notice = requested;
            requested = null;
            showDetails(notice);
        }
    }

    private void showDetails(final Notice notice) {
        if (dialog.address() != 0) {
            call(gtk, "gtk_window_present", VOID_POINTER, dialog);
            return;
        }
        dialog = (MemorySegment) call(gtk, "gtk_dialog_new", POINTER);
        viewed = notice;
        try (final var strings = Arena.ofConfined()) {
            call(gtk, "gtk_window_set_title", VOID_POINTER_POINTER, dialog, strings.allocateFrom("YCA notification"));
            call(gtk, "gtk_window_set_modal", VOID_POINTER_INT, dialog, 1);
            call(gtk, "gtk_window_set_default_size", VOID_POINTER_INT_INT, dialog, 560, 360);
            call(gtk, "gtk_dialog_add_button", POINTER_POINTER_POINTER_INT, dialog, strings.allocateFrom("Dismiss"), 1);
            call(gtk, "gtk_dialog_add_button", POINTER_POINTER_POINTER_INT, dialog, strings.allocateFrom("Close"), -7);
            call(gtk, "gtk_dialog_set_default_response", VOID_POINTER_INT, dialog, -7);
            final var content = (MemorySegment) call(gtk, "gtk_dialog_get_content_area", POINTER_POINTER, dialog);
            final var scroll = (MemorySegment) call(
                    gtk, "gtk_scrolled_window_new", POINTER_POINTER_POINTER, MemorySegment.NULL, MemorySegment.NULL);
            call(
                    gtk,
                    "gtk_scrolled_window_set_policy",
                    VOID_POINTER_INT_INT,
                    scroll,
                    2,
                    1); // never horizontal, automatic vertical
            call(gtk, "gtk_widget_set_vexpand", VOID_POINTER_INT, scroll, 1);
            final var label = (MemorySegment) call(
                    gtk,
                    "gtk_label_new",
                    POINTER_POINTER,
                    strings.allocateFrom(NotificationPresentation.details(notice)));
            call(gtk, "gtk_label_set_line_wrap", VOID_POINTER_INT, label, 1);
            call(gtk, "gtk_label_set_line_wrap_mode", VOID_POINTER_INT, label, 2); // word/character wrapping
            call(gtk, "gtk_label_set_max_width_chars", VOID_POINTER_INT, label, 64);
            call(gtk, "gtk_label_set_selectable", VOID_POINTER_INT, label, 1);
            call(gtk, "gtk_container_set_border_width", VOID_POINTER_INT, scroll, 16);
            call(gtk, "gtk_container_add", VOID_POINTER_POINTER, scroll, label);
            call(gtk, "gtk_container_add", VOID_POINTER_POINTER, content, scroll);
            connect(dialog, "response", responseStub, strings);
            call(gtk, "gtk_widget_show_all", VOID_POINTER, dialog);
            call(gtk, "gtk_window_present", VOID_POINTER, dialog);
        }
    }

    private void closeDialog() {
        if (dialog.address() != 0) {
            call(gtk, "gtk_widget_destroy", VOID_POINTER, dialog);
            dialog = MemorySegment.NULL;
            viewed = null;
        }
    }

    private void checkCallback() {
        rethrow(callbackFailure);
        if (gioMenu != null) {
            gioMenu.checkCallback();
        }
    }

    private void connect(
            final MemorySegment widget, final String signal, final MemorySegment stub, final Arena strings) {
        call(
                gtk,
                "g_signal_connect_data",
                LONG_POINTER_POINTER_POINTER_POINTER_POINTER_INT,
                widget,
                strings.allocateFrom(signal),
                stub,
                MemorySegment.NULL,
                MemorySegment.NULL,
                0);
    }

    @Override
    public void await(final long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        if (closed) {
            return;
        }
        Throwable failure = null;
        try {
            if (timeoutMillis > 0) {
                // GLib takes an unsigned 32-bit millisecond interval; never wrap a long timeout.
                timeoutSource = (MemorySegment)
                        call(gtk, "g_timeout_source_new", POINTER_INT, (int) Math.min(timeoutMillis, 0xffff_ffffL));
                timeoutSourceDestroyed = false;
                call(
                        gtk,
                        "g_source_set_callback",
                        VOID_POINTER_POINTER_POINTER_POINTER,
                        timeoutSource,
                        tickStub,
                        MemorySegment.NULL,
                        MemorySegment.NULL);
                call(gtk, "g_source_attach", INT_POINTER_POINTER, timeoutSource, context);
            }
            // Do not hold lifecycleLock here: publishers must be able to wake a blocked iteration.
            // GLib also preserves a wakeup issued before this iteration starts.
            call(gtk, "g_main_context_iteration", INT_POINTER_INT, context, 1);
        } catch (final RuntimeException | Error e) {
            failure = e;
        } finally {
            failure = cleanupTimeoutSource(failure);
        }
        rethrow(failure);
    }

    @Override
    public void wakeup() {
        lifecycleLock.lock();
        try {
            if (closed || !arena.scope().isAlive() || context.address() == 0 || wakeup == null) {
                return;
            }
            try {
                wakeup.invokeExact(context);
            } catch (final RuntimeException | Error e) {
                throw e;
            } catch (final Throwable e) {
                throw new IllegalStateException("Native call failed: g_main_context_wakeup", e);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public boolean acknowledged() {
        final var result = acknowledged;
        acknowledged = false;
        return result;
    }

    @Override
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (!arena.scope().isAlive()) {
                return;
            }
            closed = true;
            Throwable failure = cleanupTimeoutSource(null);
            failure = cleanup(failure, this::closeDialog);
            if (indicator.address() != 0) {
                failure = cleanup(
                        failure,
                        () -> call(indicatorLibrary, "app_indicator_set_status", VOID_POINTER_INT, indicator, 0));
                failure = cleanup(failure, () -> {
                    call(gtk, "g_object_unref", VOID_POINTER, indicator);
                    indicator = MemorySegment.NULL;
                });
            }
            if (menu.address() != 0) {
                if (!menuDestroyed) {
                    failure = cleanup(failure, () -> {
                        call(gtk, "gtk_widget_destroy", VOID_POINTER, menu);
                        menuDestroyed = true;
                    });
                }
                if (menuDestroyed) {
                    failure = cleanup(failure, () -> {
                        call(gtk, "g_object_unref", VOID_POINTER, menu);
                        menu = MemorySegment.NULL;
                    });
                }
            }
            if (indicator.address() == 0 && gioMenu != null) {
                failure = cleanup(failure, () -> {
                    gioMenu.close();
                    gioMenu = null;
                });
            }
            // Keep callback stubs and the singleton alive while native callback objects may survive.
            if (indicator.address() == 0
                    && menu.address() == 0
                    && gioMenu == null
                    && timeoutSource.address() == 0
                    && dialog.address() == 0) {
                if (indicatorArena != null) {
                    failure = cleanup(failure, () -> {
                        indicatorArena.close();
                        indicatorArena = null;
                    });
                }
                if (indicatorArena == null) {
                    failure = cleanup(failure, arena::close);
                }
                if (active == this && !arena.scope().isAlive()) {
                    active = null;
                }
            }
            rethrow(failure);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private Throwable cleanupTimeoutSource(final Throwable failure) {
        var result = failure;
        if (timeoutSource.address() != 0) {
            if (!timeoutSourceDestroyed) {
                result = cleanup(result, () -> {
                    call(gtk, "g_source_destroy", VOID_POINTER, timeoutSource);
                    timeoutSourceDestroyed = true;
                });
            }
            if (timeoutSourceDestroyed) {
                result = cleanup(result, () -> {
                    call(gtk, "g_source_unref", VOID_POINTER, timeoutSource);
                    timeoutSource = MemorySegment.NULL;
                });
            }
        }
        return result;
    }

    private static void rethrow(final Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable cleanup(final Throwable failure, final Runnable action) {
        try {
            action.run();
        } catch (final RuntimeException | Error cleanupFailure) {
            if (failure == null) {
                return cleanupFailure;
            }
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        return failure;
    }

    private MemorySegment item(final String text, final MethodHandle callback) {
        final var widget =
                (MemorySegment) call(gtk, "gtk_menu_item_new_with_label", POINTER_POINTER, arena.allocateFrom(text));
        call(gtk, "gtk_menu_shell_append", VOID_POINTER_POINTER, menu, widget);
        if (callback != null) {
            final var stub = linker.upcallStub(callback, VOID_POINTER_POINTER, arena);
            call(
                    gtk,
                    "g_signal_connect_data",
                    LONG_POINTER_POINTER_POINTER_POINTER_POINTER_INT,
                    widget,
                    arena.allocateFrom("activate"),
                    stub,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    0);
        }
        return widget;
    }

    private static void acknowledge(final MemorySegment widget, final MemorySegment data) {
        if (active != null) {
            active.acknowledged = true;
        }
    }

    private static void open(final MemorySegment widget, final MemorySegment data) {
        if (active != null) {
            active.requested = active.menuNotices.get(widget.address());
        }
    }

    private static void response(final MemorySegment widget, final int response, final MemorySegment data) {
        if (active != null) {
            try {
                if (response == 1 && active.viewed != null) {
                    active.dismissed.add(active.viewed);
                }
                active.closeDialog();
            } catch (final RuntimeException | Error failure) {
                active.callbackFailure = failure;
            }
        }
    }

    private static void dismiss(final MemorySegment widget, final MemorySegment data) {
        if (active != null) {
            try {
                final var notice = active.menuNotices.get(widget.address());
                if (notice != null) {
                    active.dismissed.add(notice);
                }
            } catch (final RuntimeException | Error failure) {
                active.callbackFailure = failure;
            }
        }
    }

    private static void quit(final MemorySegment widget, final MemorySegment data) {
        if (active != null) {
            active.closed = true;
        }
    }

    private static int tick(final MemorySegment data) {
        return 0;
    }

    /** Native menu ABI selection; AUTO prefers GLib/Gio and falls back to legacy GTK3 libraries. */
    public enum Backend {
        AUTO,
        GLIB,
        GTK3;

        /** Fusion configuration converter; accepts CLI spelling and enum names without native initialization. */
        public static Backend fromConfigurationString(final String value) {
            return switch (value == null ? "" : value.toUpperCase(Locale.ROOT)) {
                case "AUTO" -> AUTO;
                case "GLIB" -> GLIB;
                case "GTK3" -> GTK3;
                default ->
                    throw new IllegalArgumentException(
                            "notification-service-linux-backend must be one of: auto, glib, gtk3.");
            };
        }
    }

    private Object call(
            final SymbolLookup library, final String name, final FunctionDescriptor descriptor, final Object... args) {
        try {
            final var handle = functions.computeIfAbsent(
                    name,
                    key -> linker.downcallHandle(
                            library.find(key)
                                    .orElseThrow(() -> new IllegalStateException("Missing native symbol: " + key)),
                            descriptor));
            return handle.invokeWithArguments(args);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException("Native call failed: " + name, e);
        }
    }
}
