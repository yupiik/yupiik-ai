/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications.tray;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

import io.yupiik.ia.agent.notifications.Notice;
import io.yupiik.ia.agent.notifications.NotificationPresentation;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Tests the exported native model/actions directly, without requiring a desktop or AppIndicator. */
@EnabledOnOs(OS.LINUX)
class GioNotificationMenuTest {
    @Test
    void exportsPairedRowsIconsAndExactSnapshotActions() {
        try (final var gio = new NativeGio()) {
            final var opened = new ArrayList<Notice>();
            final var dismissed = new ArrayList<Notice>();
            final var acknowledged = new AtomicBoolean();
            final var quit = new AtomicBoolean();
            try (final var menu = new GioNotificationMenu(
                    opened::add, dismissed::add, () -> acknowledged.set(true), () -> quit.set(true))) {
                final var first = new Notice(1, "one", "Approval & <plain>", "approval_required", "main", 1, null);
                final var second = new Notice(1, "one", "Question", "input_required", "btw", 2, null);
                final var third = new Notice(1, "two", "Complete", "turn_end", "main", 3, null);
                final var notices = List.of(first, second, third);
                menu.updateNotices(notices);
                assertEquals(9, gio.count(menu));
                for (int i = 0; i < notices.size(); i++) {
                    assertEquals(
                            NotificationPresentation.preview(notices.get(i)), gio.attribute(menu, 1 + 2 * i, "label"));
                    assertEquals("Dismiss", gio.attribute(menu, 2 + 2 * i, "label"));
                    assertTrue(gio.hasAttribute(menu, 1 + 2 * i, "icon"));
                    assertTrue(gio.hasAttribute(menu, 2 + 2 * i, "icon"));
                }
                final var oldAction = gio.attribute(menu, 4, "action");
                gio.activate(menu, oldAction);
                assertTrue(opened.isEmpty());
                final var replacement = new Notice(1, "one", "Newer question", "input_required", "btw", 4, null);
                menu.updateNotices(List.of(first, replacement, third));
                assertEquals(List.of(second), dismissed);
                assertFalse(gio.hasAction(menu, oldAction));
                assertNotEquals(oldAction, gio.attribute(menu, 4, "action"));
                gio.activate(menu, gio.attribute(menu, 3, "action"));
                assertEquals(List.of(replacement), opened);
                assertEquals(List.of(second), dismissed);
                gio.activate(menu, gio.attribute(menu, 7, "action"));
                gio.activate(menu, gio.attribute(menu, 8, "action"));
                assertTrue(acknowledged.get());
                assertTrue(quit.get());
                menu.checkCallback();
                for (int i = 0; i < 100; i++) {
                    menu.updateNotices(notices);
                }
                menu.updateSummary("YCA - 2 unread sessions");
                menu.updateSummary("YCA - 2 unread sessions");
                assertEquals("YCA - 2 unread sessions", gio.attribute(menu, 0, "label"));
                menu.updateNotices(List.of());
                assertEquals(3, gio.count(menu));
                assertEquals("YCA - 2 unread sessions", gio.attribute(menu, 0, "label"));
            }
            // Releasing native action owners also releases the callback singleton.
            try (final var menu = new GioNotificationMenu(opened::add, dismissed::add, () -> {}, () -> {})) {
                assertEquals(3, gio.count(menu));
            }
        }
    }

    @Test
    void containsCallbackFailuresAtNativeBoundary() {
        try (final var gio = new NativeGio();
                final var menu = new GioNotificationMenu(
                        notice -> {},
                        notice -> {},
                        () -> {
                            throw new IllegalStateException("callback failed");
                        },
                        () -> {})) {
            gio.activate(menu, "indicator.acknowledge");
            assertEquals(
                    "callback failed",
                    assertThrows(IllegalStateException.class, menu::checkCallback)
                            .getMessage());
        }
    }

    @Test
    void disconnectsActionsRetainedByNativeExporters() {
        try (final var gio = new NativeGio()) {
            final var acknowledged = new AtomicBoolean();
            final var menu =
                    new GioNotificationMenu(notice -> {}, notice -> {}, () -> acknowledged.set(true), () -> {});
            final var actions = menu.actions();
            gio.call("g_object_ref", FunctionDescriptor.of(ADDRESS, ADDRESS), actions);
            try {
                menu.close();
                gio.call(
                        "g_action_group_activate_action",
                        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
                        actions,
                        gio.arena.allocateFrom("acknowledge"),
                        MemorySegment.NULL);
                assertFalse(acknowledged.get());
            } finally {
                menu.close();
                gio.call("g_object_unref", FunctionDescriptor.ofVoid(ADDRESS), actions);
            }
        }
    }

    @SuppressWarnings("restricted") // Tests the native GMenu/GAction boundary, just like the production FFM adapter.
    private static final class NativeGio implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final SymbolLookup symbols;

        private NativeGio() {
            SymbolLookup loaded;
            try {
                loaded = SymbolLookup.libraryLookup("libgio-2.0.so.0", arena);
            } catch (final IllegalArgumentException unavailable) {
                arena.close();
                abort("Native Gio library is not installed.");
                throw unavailable;
            }
            symbols = loaded;
        }

        private int count(final GioNotificationMenu menu) {
            return (int) call("g_menu_model_get_n_items", FunctionDescriptor.of(JAVA_INT, ADDRESS), menu.menu());
        }

        private MemorySegment value(final GioNotificationMenu menu, final int index, final String name) {
            return (MemorySegment) call(
                    "g_menu_model_get_item_attribute_value",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    menu.menu(),
                    index,
                    arena.allocateFrom(name),
                    MemorySegment.NULL);
        }

        private String attribute(final GioNotificationMenu menu, final int index, final String name) {
            final var value = value(menu, index, name);
            assertNotEquals(0, value.address());
            try {
                final var text = (MemorySegment) call(
                        "g_variant_get_string",
                        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
                        value,
                        MemorySegment.NULL);
                return text.reinterpret(4096).getString(0);
            } finally {
                call("g_variant_unref", FunctionDescriptor.ofVoid(ADDRESS), value);
            }
        }

        private boolean hasAttribute(final GioNotificationMenu menu, final int index, final String name) {
            final var value = value(menu, index, name);
            if (value.address() == 0) {
                return false;
            }
            call("g_variant_unref", FunctionDescriptor.ofVoid(ADDRESS), value);
            return true;
        }

        private void activate(final GioNotificationMenu menu, final String action) {
            call(
                    "g_action_group_activate_action",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS),
                    menu.actions(),
                    arena.allocateFrom(action.substring("indicator.".length())),
                    MemorySegment.NULL);
        }

        private boolean hasAction(final GioNotificationMenu menu, final String action) {
            return (int) call(
                            "g_action_group_has_action",
                            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                            menu.actions(),
                            arena.allocateFrom(action.substring("indicator.".length())))
                    != 0;
        }

        private Object call(final String name, final FunctionDescriptor descriptor, final Object... args) {
            try {
                return Linker.nativeLinker()
                        .downcallHandle(symbols.find(name).orElseThrow(), descriptor)
                        .invokeWithArguments(args);
            } catch (final RuntimeException | Error e) {
                throw e;
            } catch (final Throwable e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            arena.close();
        }
    }
}
