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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns the GMenu and GSimpleActionGroup exported by the GLib AppIndicator backend. */
public final class GioNotificationMenu implements AutoCloseable {
    private static final FunctionDescriptor POINTER = FunctionDescriptor.of(ADDRESS);
    private static final FunctionDescriptor POINTER_POINTER = FunctionDescriptor.of(ADDRESS, ADDRESS);
    private static final FunctionDescriptor POINTER_POINTER_POINTER = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_POINTER = FunctionDescriptor.ofVoid(ADDRESS);
    private static final FunctionDescriptor VOID_POINTER_POINTER = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_POINTER_INT = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT);
    private static final FunctionDescriptor VOID_POINTER_INT_POINTER =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor ACTION = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor CONNECT =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodHandle ACTIVATE;
    private static GioNotificationMenu active;

    static {
        try {
            ACTIVATE = MethodHandles.lookup().findStatic(GioNotificationMenu.class, "activate", ACTION.toMethodType());
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Arena arena = Arena.ofConfined();
    private final Linker linker = Linker.nativeLinker();
    private final Map<String, MethodHandle> functions = new HashMap<>();
    private final Map<Long, MenuAction> callbacks = new HashMap<>();
    private final List<String> noticeActions = new ArrayList<>();
    private final Consumer<Notice> open;
    private final Consumer<Notice> dismiss;
    private final SymbolLookup gio;
    private MemorySegment menu;
    private MemorySegment actions;
    private final MemorySegment stub;
    private String summary = "YCA - no unread activity";
    private long sequence;
    private Throwable callbackFailure;

    public GioNotificationMenu(
            final Consumer<Notice> open,
            final Consumer<Notice> dismiss,
            final Runnable acknowledge,
            final Runnable quit) {
        this.open = open;
        this.dismiss = dismiss;
        try {
            if (active != null) {
                throw new IllegalStateException("A Gio notification menu is already active in this process.");
            }
            active = this;
            gio = SymbolLookup.libraryLookup("libgio-2.0.so.0", arena);
            stub = linker.upcallStub(ACTIVATE, ACTION, arena);
            menu = (MemorySegment) call("g_menu_new", POINTER);
            actions = (MemorySegment) call("g_simple_action_group_new", POINTER);
            try (final var strings = Arena.ofConfined()) {
                append(summary, null, null, strings);
                addAction("acknowledge", acknowledge, strings);
                append("Acknowledge unread activity (not approval)", "acknowledge", null, strings);
                addAction("quit", quit, strings);
                append("Quit notifications service", "quit", null, strings);
            }
        } catch (final RuntimeException | Error failure) {
            try {
                close();
            } catch (final RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /** Borrowed native menu pointer; the indicator retains its own reference. */
    public MemorySegment menu() {
        return menu;
    }

    /** Borrowed native action group pointer; the indicator retains its own reference. */
    public MemorySegment actions() {
        return actions;
    }

    public void updateNotices(final List<Notice> notices) {
        // Retire names rather than reusing row indices: a delayed remote action must not target a newer notice.
        try (final var strings = Arena.ofConfined()) {
            for (final var name : noticeActions) {
                final var nativeName = strings.allocateFrom(name);
                final var action = (MemorySegment)
                        call("g_action_map_lookup_action", POINTER_POINTER_POINTER, actions, nativeName);
                final var callback = callbacks.get(action.address());
                if (callback != null) {
                    disconnect(callback);
                    callbacks.remove(action.address());
                }
                call("g_action_map_remove_action", VOID_POINTER_POINTER, actions, nativeName);
            }
            noticeActions.clear();
            call("g_menu_remove_all", VOID_POINTER, menu);
            append(summary, null, null, strings);
            for (final var notice : notices) {
                final var id = ++sequence;
                final var openName = "open-" + id;
                final var dismissName = "dismiss-" + id;
                noticeActions.add(openName);
                noticeActions.add(dismissName);
                addAction(openName, () -> open.accept(notice), strings);
                addAction(dismissName, () -> dismiss.accept(notice), strings);
                final var icon =
                        switch (notice.type()) {
                            case "approval_required" -> "dialog-warning";
                            case "input_required" -> "dialog-question";
                            default -> "emblem-ok";
                        };
                append(NotificationPresentation.preview(notice), openName, icon, strings);
                append("Dismiss", dismissName, "edit-clear", strings);
            }
            append("Acknowledge unread activity (not approval)", "acknowledge", null, strings);
            append("Quit notifications service", "quit", null, strings);
        }
    }

    public void updateSummary(final String text) {
        if (summary.equals(text)) {
            return;
        }
        try (final var strings = Arena.ofConfined()) {
            final var item = (MemorySegment)
                    call("g_menu_item_new", POINTER_POINTER_POINTER, strings.allocateFrom(text), MemorySegment.NULL);
            try {
                call("g_menu_remove", VOID_POINTER_INT, menu, 0);
                call("g_menu_insert_item", VOID_POINTER_INT_POINTER, menu, 0, item);
                summary = text;
            } finally {
                call("g_object_unref", VOID_POINTER, item);
            }
        }
    }

    public void checkCallback() {
        if (callbackFailure instanceof RuntimeException exception) {
            throw exception;
        }
        if (callbackFailure instanceof Error error) {
            throw error;
        }
    }

    private void addAction(final String name, final Runnable callback, final Arena strings) {
        final var action = (MemorySegment)
                call("g_simple_action_new", POINTER_POINTER_POINTER, strings.allocateFrom(name), MemorySegment.NULL);
        try {
            final var signal = (long) call(
                    "g_signal_connect_data",
                    CONNECT,
                    action,
                    strings.allocateFrom("activate"),
                    stub,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    0);
            call("g_action_map_add_action", VOID_POINTER_POINTER, actions, action);
            callbacks.put(action.address(), new MenuAction(action, signal, callback));
        } finally {
            call("g_object_unref", VOID_POINTER, action);
        }
    }

    private void append(final String label, final String action, final String icon, final Arena strings) {
        final var item = (MemorySegment) call(
                "g_menu_item_new",
                POINTER_POINTER_POINTER,
                strings.allocateFrom(label),
                action == null ? MemorySegment.NULL : strings.allocateFrom("indicator." + action));
        try {
            if (icon != null) {
                final var image =
                        (MemorySegment) call("g_themed_icon_new", POINTER_POINTER, strings.allocateFrom(icon));
                try {
                    call("g_menu_item_set_icon", VOID_POINTER_POINTER, item, image);
                } finally {
                    call("g_object_unref", VOID_POINTER, image);
                }
            }
            call("g_menu_append_item", VOID_POINTER_POINTER, menu, item);
        } finally {
            call("g_object_unref", VOID_POINTER, item);
        }
    }

    @Override
    public void close() {
        if (!arena.scope().isAlive()) {
            return;
        }
        // Disconnect even if a native exporter retains the group beyond the indicator's lifetime.
        final var iterator = callbacks.values().iterator();
        while (iterator.hasNext()) {
            disconnect(iterator.next());
            iterator.remove();
        }
        if (actions.address() != 0) {
            call("g_object_unref", VOID_POINTER, actions);
            actions = MemorySegment.NULL;
        }
        if (menu.address() != 0) {
            call("g_object_unref", VOID_POINTER, menu);
            menu = MemorySegment.NULL;
        }
        callbacks.clear();
        arena.close();
        if (active == this) {
            active = null;
        }
    }

    private static void activate(final MemorySegment action, final MemorySegment parameter, final MemorySegment data) {
        if (active != null) {
            try {
                final var callback = active.callbacks.get(action.address());
                if (callback != null) {
                    callback.callback().run();
                }
            } catch (final RuntimeException | Error failure) {
                active.callbackFailure = failure;
            }
        }
    }

    private void disconnect(final MenuAction action) {
        call(
                "g_signal_handler_disconnect",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG),
                action.pointer(),
                action.signal());
    }

    private record MenuAction(MemorySegment pointer, long signal, Runnable callback) {}

    private Object call(final String name, final FunctionDescriptor descriptor, final Object... args) {
        try {
            final var handle = functions.computeIfAbsent(
                    name,
                    key -> linker.downcallHandle(
                            gio.find(key).orElseThrow(() -> new IllegalStateException("Missing native symbol: " + key)),
                            descriptor));
            return handle.invokeWithArguments(args);
        } catch (final RuntimeException | Error e) {
            throw e;
        } catch (final Throwable e) {
            throw new IllegalStateException("Native call failed: " + name, e);
        }
    }
}
