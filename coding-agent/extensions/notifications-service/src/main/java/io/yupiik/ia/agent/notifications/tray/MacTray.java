/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications.tray;

import static io.yupiik.ia.agent.notifications.NotificationPresentation.details;
import static io.yupiik.ia.agent.notifications.NotificationPresentation.preview;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.yupiik.ia.agent.notifications.Notice;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AppKit integration for a process whose OS main thread pumps {@link #poll()}.
 * On a JVM, launch with -XstartOnFirstThread and --enable-native-access=ALL-UNNAMED
 * (or enable native access for the containing module).
 * No AppKit or FFM linker initialization happens merely by loading this class.
 *
 * <p>CGFloat and NSUInteger are
 * double and 64-bit respectively on both supported macOS ABIs. BOOL is passed
 * as a single byte (0 or 1), covering both Intel's signed char and arm64's bool.
 * GraalVM 25 documents macOS FFM upcalls on AArch64, not macOS/x64; the latter
 * therefore requires a JVM or a native-image implementation with that support.</p>
 */
public final class MacTray implements NativeTray {
    // These descriptors must stay in sync with mac/reachability-metadata.json.
    private static final FunctionDescriptor OBJECT = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor OBJECT_ARGUMENT = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor OBJECT_DOUBLE =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_DOUBLE);
    private static final FunctionDescriptor OBJECT_THREE_ARGUMENTS =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor EVENT =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_BYTE);
    private static final FunctionDescriptor VOID = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_ARGUMENT = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor VOID_BYTE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE);
    private static final FunctionDescriptor VOID_LONG = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG);
    private static final GroupLayout SIZE = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE);
    private static final GroupLayout RECT = MemoryLayout.structLayout(SIZE, SIZE);
    private static final FunctionDescriptor OBJECT_RECT = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, RECT);
    private static final FunctionDescriptor WINDOW =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, RECT, JAVA_LONG, JAVA_LONG, JAVA_BYTE);
    private static final FunctionDescriptor VOID_SIZE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, SIZE);
    private static final FunctionDescriptor VOID_ARGUMENT_LONG =
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor BOOL = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS);

    private static final FunctionDescriptor BOOL_LONG = FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor ACTION = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor IS_MAIN = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor NAMED_POINTER = FunctionDescriptor.of(ADDRESS, ADDRESS);
    private static final FunctionDescriptor ALLOCATE_CLASS =
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor ADD_METHOD =
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor CLASS_VOID = FunctionDescriptor.ofVoid(ADDRESS);
    private static final MethodType ACTION_TYPE =
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class);
    private static final Map<Long, MacTray> TARGETS = new ConcurrentHashMap<>();

    private final Thread owner = Thread.currentThread();
    private final Cocoa cocoa;
    private final Map<Long, Notice> noticeItems = new HashMap<>(); // Menu owns these native items.
    private final List<Notice> dismissed = new ArrayList<>();
    private Notice requestedNotice;
    private Notice dialogNotice;
    private MemorySegment dialog = MemorySegment.NULL; // alloc/init ownership, not released on close.
    private MemorySegment dismissButton = MemorySegment.NULL; // Owned by dialog's content view.
    private MemorySegment closeButton = MemorySegment.NULL;
    private boolean dismissDialog;
    private boolean closeDialog;
    private volatile MacEventWait eventWait;
    private Cocoa.Pool cleanupPool;
    private MemorySegment application = MemorySegment.NULL; // Shared, not owned.
    private MemorySegment statusBar = MemorySegment.NULL; // Shared, not owned.
    private MemorySegment statusItem = MemorySegment.NULL; // Retained below.
    private MemorySegment menu = MemorySegment.NULL; // alloc/init ownership.
    private MemorySegment acknowledgeItem = MemorySegment.NULL; // Owned by menu.
    private MemorySegment quitItem = MemorySegment.NULL; // Owned by menu.
    private MemorySegment targetClass = MemorySegment.NULL;
    private MemorySegment target = MemorySegment.NULL; // alloc/init ownership.
    private MemorySegment image = MemorySegment.NULL; // alloc/init ownership.
    private boolean statusMenuDetached;
    private boolean statusItemRemoved;
    private boolean unreadAcknowledged;
    private boolean quitRequested;
    private boolean disposed;

    public MacTray(final Path icon) {
        final var os = System.getProperty("os.name", "");
        if (!"Mac OS X".equals(os) && !"Darwin".equals(os)) {
            throw new UnsupportedOperationException("MacTray requires macOS");
        }
        final var architecture = System.getProperty("os.arch", "");
        if (ADDRESS.byteSize() != 8
                || !("aarch64".equals(architecture) || "x86_64".equals(architecture) || "amd64".equals(architecture))) {
            throw new UnsupportedOperationException("MacTray requires 64-bit Intel or Apple Silicon");
        }
        cocoa = new Cocoa();
        try (final var pool = cocoa.pool()) {
            initialize(icon);
            eventWait = new MacEventWait();
        } catch (final RuntimeException | Error failure) {
            try {
                close();
            } catch (final RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void initialize(final Path icon) {
        application = cocoa.object(cocoa.type("NSApplication"), "sharedApplication");
        // A menu-bar utility, not a Dock application. Never terminate the shared NSApplication.
        if ((byte) cocoa.send(BOOL_LONG, application, "setActivationPolicy:", 1L) == 0) {
            throw new IllegalStateException("Unable to configure an accessory NSApplication");
        }
        cocoa.send(VOID, application, "finishLaunching");

        targetClass =
                cocoa.allocateClass("YCAMacTray_" + UUID.randomUUID().toString().replace("-", ""));
        try {
            final var lookup = MethodHandles.lookup();
            cocoa.addAction(
                    targetClass,
                    "acknowledgeUnread:",
                    lookup.findStatic(MacTray.class, "acknowledgeUnread", ACTION_TYPE));
            cocoa.addAction(targetClass, "quitTray:", lookup.findStatic(MacTray.class, "quitTray", ACTION_TYPE));
            cocoa.addAction(targetClass, "showNotice:", lookup.findStatic(MacTray.class, "showNotice", ACTION_TYPE));
            cocoa.addAction(
                    targetClass,
                    "dismissMenuNotice:",
                    lookup.findStatic(MacTray.class, "dismissMenuNotice", ACTION_TYPE));
            cocoa.addAction(
                    targetClass, "dismissNotice:", lookup.findStatic(MacTray.class, "dismissNotice", ACTION_TYPE));
            cocoa.addAction(targetClass, "closeNotice:", lookup.findStatic(MacTray.class, "closeNotice", ACTION_TYPE));
        } catch (final NoSuchMethodException | IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
        cocoa.registerClass(targetClass);
        target = cocoa.object(targetClass, "new");
        TARGETS.put(target.address(), this);

        menu = cocoa.object(cocoa.type("NSMenu"), "new");
        cocoa.send(VOID_BYTE, menu, "setAutoenablesItems:", (byte) 0);
        acknowledgeItem = addMenuItem("Acknowledge unread", "acknowledgeUnread:");
        quitItem = addMenuItem("Quit", "quitTray:");

        statusBar = cocoa.object(cocoa.type("NSStatusBar"), "systemStatusBar");
        // NSVariableStatusItemLength = -1, a CGFloat rather than a struct.
        statusItem = required((MemorySegment) cocoa.send(OBJECT_DOUBLE, statusBar, "statusItemWithLength:", -1d));
        cocoa.object(statusItem, "retain");
        cocoa.send(VOID_ARGUMENT, statusItem, "setMenu:", menu);
        image = required((MemorySegment) cocoa.send(
                OBJECT_ARGUMENT,
                cocoa.object(cocoa.type("NSImage"), "alloc"),
                "initWithContentsOfFile:",
                cocoa.string(icon.toAbsolutePath().toString())));
        final var button = cocoa.object(statusItem, "button");
        cocoa.send(VOID_ARGUMENT, button, "setImage:", image);
        // Scale the existing 64px app icon down to the menu-bar button instead of loading AWT image code.
        cocoa.send(VOID_LONG, button, "setImageScaling:", 0L);
        update("YCA notifications", false);
    }

    private MemorySegment addMenuItem(final String title, final String action) {
        return addMenuItem(title, action, -1);
    }

    private MemorySegment addMenuItem(final String title, final String action, final long index) {
        final var allocated = cocoa.object(cocoa.type("NSMenuItem"), "alloc");
        final var item = required((MemorySegment) cocoa.send(
                OBJECT_THREE_ARGUMENTS,
                allocated,
                "initWithTitle:action:keyEquivalent:",
                cocoa.string(title),
                cocoa.selector(action),
                cocoa.string("")));
        try {
            cocoa.send(VOID_ARGUMENT, item, "setTarget:", target);
            if (index < 0) {
                cocoa.send(VOID_ARGUMENT, menu, "addItem:", item);
            } else {
                cocoa.send(VOID_ARGUMENT_LONG, menu, "insertItem:atIndex:", item, index);
            }
            return item;
        } finally {
            // The menu retains the item. Keep only a borrowed pointer for detaching its target.
            cocoa.send(VOID, item, "release");
        }
    }

    @Override
    public void updateNotices(final List<Notice> notices) {
        checkThread();
        if (closed()) {
            return;
        }
        final var snapshot = List.copyOf(notices);
        try (final var pool = cocoa.pool()) {
            clearNoticeItems();
            var index = 0L;
            for (final var notice : snapshot) {
                final var item = addMenuItem(preview(notice), "showNotice:", index++);
                noticeItems.put(item.address(), notice);
                setMenuItemImage(
                        item,
                        switch (notice.type()) {
                            case "approval_required" -> "NSLockLockedTemplate";
                            case "input_required" -> "NSUserTemplate";
                            default -> "NSInfo";
                        });
                final var dismissItem = addMenuItem("Dismiss", "dismissMenuNotice:", index++);
                noticeItems.put(dismissItem.address(), notice);
                setMenuItemImage(dismissItem, "NSStopProgressTemplate");
            }
        }
    }

    private void setMenuItemImage(final MemorySegment item, final String name) {
        final var named =
                (MemorySegment) cocoa.send(OBJECT_ARGUMENT, cocoa.type("NSImage"), "imageNamed:", cocoa.string(name));
        if (named.address() == 0) {
            return; // Keep the native text action usable if a stock image is unavailable.
        }
        // Never resize or release AppKit's shared named image. The menu item retains our sized copy.
        final var icon = cocoa.object(named, "copy");
        try (final var geometry = Arena.ofConfined()) {
            cocoa.send(VOID_SIZE, icon, "setSize:", size(geometry, 16, 16));
            cocoa.send(VOID_ARGUMENT, item, "setImage:", icon);
        } finally {
            cocoa.send(VOID, icon, "release");
        }
    }

    @Override
    public List<Notice> dismissed() {
        checkThread();
        final var result = List.copyOf(dismissed);
        dismissed.clear();
        return result;
    }

    private void clearNoticeItems() {
        final var entries = noticeItems.entrySet().iterator();
        while (entries.hasNext()) {
            final var item = MemorySegment.ofAddress(entries.next().getKey());
            cocoa.send(VOID_ARGUMENT, item, "setTarget:", MemorySegment.NULL);
            cocoa.send(VOID_ARGUMENT, menu, "removeItem:", item);
            entries.remove();
        }
    }

    // Run outside upcalls, after AppKit has finished dispatching the action.
    private void processDialogRequests() {
        if (dialog.address() != 0
                && (closeDialog || dismissDialog || (byte) cocoa.send(BOOL, dialog, "isVisible") == 0)) {
            final var dismissedNotice = dismissDialog ? dialogNotice : null;
            disposeDialog();
            if (dismissedNotice != null) {
                dismissed.add(dismissedNotice);
            }
        }
        final var notice = requestedNotice;
        requestedNotice = null;
        if (notice != null && !quitRequested) {
            // Keep the viewed snapshot stable, matching the other native backends.
            if (dialog.address() == 0) {
                openDialog(notice);
            }
            cocoa.send(VOID_BYTE, application, "activateIgnoringOtherApps:", (byte) 1);
            cocoa.send(VOID_ARGUMENT, dialog, "makeKeyAndOrderFront:", MemorySegment.NULL);
        }
    }

    private void openDialog(final Notice notice) {
        try (final var geometry = Arena.ofConfined()) {
            // Fixed, bounded native window: long content grows vertically in the scroll view.
            dialog = required((MemorySegment) cocoa.send(
                    WINDOW,
                    cocoa.object(cocoa.type("NSWindow"), "alloc"),
                    "initWithContentRect:styleMask:backing:defer:",
                    rect(geometry, 0, 0, 640, 450),
                    3L,
                    2L,
                    (byte) 0));
            cocoa.send(VOID_BYTE, dialog, "setReleasedWhenClosed:", (byte) 0);
            dialogNotice = notice;
            cocoa.send(VOID_ARGUMENT, dialog, "setTitle:", cocoa.string("Notification details"));
            final var content = cocoa.object(dialog, "contentView");
            final var scroll = view(geometry, "NSScrollView", 20, 70, 600, 360);
            try {
                cocoa.send(VOID_BYTE, scroll, "setHasVerticalScroller:", (byte) 1);
                cocoa.send(VOID_BYTE, scroll, "setHasHorizontalScroller:", (byte) 0);
                cocoa.send(VOID_BYTE, scroll, "setAutohidesScrollers:", (byte) 1);
                final var text = view(geometry, "NSTextView", 0, 0, 580, 360);
                try {
                    cocoa.send(VOID_BYTE, text, "setEditable:", (byte) 0);
                    cocoa.send(VOID_BYTE, text, "setSelectable:", (byte) 1);
                    cocoa.send(VOID_BYTE, text, "setRichText:", (byte) 0);
                    cocoa.send(VOID_BYTE, text, "setVerticallyResizable:", (byte) 1);
                    cocoa.send(VOID_BYTE, text, "setHorizontallyResizable:", (byte) 0);
                    cocoa.send(VOID_SIZE, text, "setMinSize:", size(geometry, 0, 360));
                    cocoa.send(VOID_SIZE, text, "setMaxSize:", size(geometry, Double.MAX_VALUE, Double.MAX_VALUE));
                    cocoa.send(VOID_LONG, text, "setAutoresizingMask:", 2L); // NSViewWidthSizable.
                    final var container = cocoa.object(text, "textContainer");
                    cocoa.send(VOID_SIZE, container, "setContainerSize:", size(geometry, 580, Double.MAX_VALUE));
                    cocoa.send(VOID_BYTE, container, "setWidthTracksTextView:", (byte) 1);
                    cocoa.send(VOID_LONG, container, "setLineBreakMode:", 0L); // NSLineBreakByWordWrapping.
                    cocoa.send(
                            VOID_ARGUMENT,
                            text,
                            "setFont:",
                            cocoa.send(OBJECT_DOUBLE, cocoa.type("NSFont"), "systemFontOfSize:", 13d));
                    cocoa.send(VOID_ARGUMENT, text, "setString:", cocoa.string(details(notice)));
                    cocoa.send(VOID_ARGUMENT, scroll, "setDocumentView:", text);
                } finally {
                    cocoa.send(VOID, text, "release");
                }
                cocoa.send(VOID_ARGUMENT, content, "addSubview:", scroll);
            } finally {
                cocoa.send(VOID, scroll, "release");
            }
            dismissButton = dialogButton(geometry, content, 390, "Dismiss", "dismissNotice:", "");
            closeButton = dialogButton(geometry, content, 510, "Close", "closeNotice:", "\u001b");
            cocoa.send(VOID, dialog, "center");
        } catch (final RuntimeException | Error failure) {
            try {
                disposeDialog();
            } catch (final RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private MemorySegment dialogButton(
            final Arena geometry,
            final MemorySegment content,
            final double x,
            final String title,
            final String action,
            final String key) {
        final var button = view(geometry, "NSButton", x, 20, 110, 32);
        try {
            cocoa.send(VOID_ARGUMENT, button, "setTitle:", cocoa.string(title));
            cocoa.send(VOID_LONG, button, "setBezelStyle:", 1L);
            cocoa.send(VOID_ARGUMENT, button, "setTarget:", target);
            cocoa.send(VOID_ARGUMENT, button, "setAction:", cocoa.selector(action));
            cocoa.send(VOID_ARGUMENT, button, "setKeyEquivalent:", cocoa.string(key));
            cocoa.send(VOID_LONG, button, "setKeyEquivalentModifierMask:", 0L);
            cocoa.send(VOID_ARGUMENT, content, "addSubview:", button);
            return button;
        } finally {
            cocoa.send(VOID, button, "release");
        }
    }

    private MemorySegment view(
            final Arena arena,
            final String type,
            final double x,
            final double y,
            final double width,
            final double height) {
        return required((MemorySegment) cocoa.send(
                OBJECT_RECT,
                cocoa.object(cocoa.type(type), "alloc"),
                "initWithFrame:",
                rect(arena, x, y, width, height)));
    }

    private static MemorySegment rect(
            final Arena arena, final double x, final double y, final double width, final double height) {
        final var value = arena.allocate(RECT);
        value.set(JAVA_DOUBLE, 0, x);
        value.set(JAVA_DOUBLE, 8, y);
        value.set(JAVA_DOUBLE, 16, width);
        value.set(JAVA_DOUBLE, 24, height);
        return value;
    }

    private static MemorySegment size(final Arena arena, final double width, final double height) {
        final var value = arena.allocate(SIZE);
        value.set(JAVA_DOUBLE, 0, width);
        value.set(JAVA_DOUBLE, 8, height);
        return value;
    }

    private void disposeDialog() {
        if (dismissButton.address() != 0) {
            cocoa.send(VOID_ARGUMENT, dismissButton, "setTarget:", MemorySegment.NULL);
            dismissButton = MemorySegment.NULL;
        }
        if (closeButton.address() != 0) {
            cocoa.send(VOID_ARGUMENT, closeButton, "setTarget:", MemorySegment.NULL);
            closeButton = MemorySegment.NULL;
        }
        if (dialog.address() != 0) {
            cocoa.send(VOID, dialog, "close");
            cocoa.send(VOID, dialog, "release");
            dialog = MemorySegment.NULL;
        }
        dialogNotice = null;
        dismissDialog = false;
        closeDialog = false;
    }

    @Override
    public void update(final String tooltip, final boolean attention) {
        checkThread();
        if (closed()) {
            return;
        }
        try (final var pool = cocoa.pool()) {
            final var button = cocoa.object(statusItem, "button");
            cocoa.send(VOID_ARGUMENT, button, "setTitle:", cocoa.string(attention ? "!" : ""));
            cocoa.send(VOID_BYTE, button, "setHighlighted:", attention ? (byte) 1 : (byte) 0);
            cocoa.send(
                    VOID_ARGUMENT, button, "setToolTip:", tooltip == null ? MemorySegment.NULL : cocoa.string(tooltip));
        }
    }

    @Override
    public void poll() {
        checkThread();
        if (closed()) {
            return;
        }
        try (final var pool = cocoa.pool()) {
            final var deadline = cocoa.object(cocoa.type("NSDate"), "distantPast");
            // Bound the batch and never wait for an event. sendEvent: performs normal AppKit
            // dispatch, including AppKit's own nested tracking loop while a menu is open.
            for (var i = 0; i < 64 && !quitRequested; i++) {
                final var event = (MemorySegment) cocoa.send(
                        EVENT,
                        application,
                        "nextEventMatchingMask:untilDate:inMode:dequeue:",
                        -1L,
                        deadline,
                        cocoa.defaultMode,
                        (byte) 1);
                if (event.address() == 0) {
                    break;
                }
                cocoa.send(VOID_ARGUMENT, application, "sendEvent:", event);
                processDialogRequests();
            }
            cocoa.send(VOID, application, "updateWindows");
        }
    }

    @Override
    public void await(final long timeoutMillis) {
        checkThread();
        if (!closed()) {
            eventWait.await(timeoutMillis);
        }
    }

    @Override
    public void wakeup() {
        final var wait = eventWait;
        if (wait != null) {
            wait.wakeup();
        }
    }

    @Override
    public boolean acknowledged() {
        checkThread();
        final var result = unreadAcknowledged;
        unreadAcknowledged = false;
        return result;
    }

    @Override
    public boolean closed() {
        checkThread();
        return disposed || quitRequested;
    }

    @Override
    public void close() {
        checkThread();
        if (disposed) {
            return;
        }
        quitRequested = true;
        Throwable failure = cleanup(null, () -> {
            if (eventWait != null) {
                eventWait.close();
            }
        });
        failure = cleanup(failure, () -> {
            if (cleanupPool == null) {
                cleanupPool = cocoa.pool();
            }
        });
        if (cleanupPool != null) {
            requestedNotice = null;
            failure = cleanup(failure, this::disposeDialog);
            failure = cleanup(failure, this::clearNoticeItems);
            if (acknowledgeItem.address() != 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID_ARGUMENT, acknowledgeItem, "setTarget:", MemorySegment.NULL);
                    acknowledgeItem = MemorySegment.NULL;
                });
            }
            if (quitItem.address() != 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID_ARGUMENT, quitItem, "setTarget:", MemorySegment.NULL);
                    quitItem = MemorySegment.NULL;
                });
            }
            if (statusItem.address() != 0) {
                if (!statusMenuDetached) {
                    failure = cleanup(failure, () -> {
                        cocoa.send(VOID_ARGUMENT, statusItem, "setMenu:", MemorySegment.NULL);
                        statusMenuDetached = true;
                    });
                }
                if (!statusItemRemoved) {
                    failure = cleanup(failure, () -> {
                        cocoa.send(VOID_ARGUMENT, statusBar, "removeStatusItem:", statusItem);
                        statusItemRemoved = true;
                    });
                }
                if (statusMenuDetached && statusItemRemoved) {
                    failure = cleanup(failure, () -> {
                        cocoa.send(VOID, statusItem, "release");
                        statusItem = MemorySegment.NULL;
                    });
                }
            }
            // Keep borrowed item pointers valid until every target and the status menu are detached.
            if (menu.address() != 0
                    && acknowledgeItem.address() == 0
                    && quitItem.address() == 0
                    && statusItem.address() == 0
                    && noticeItems.isEmpty()) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID, menu, "release");
                    menu = MemorySegment.NULL;
                });
            }
            if (image.address() != 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID, image, "release");
                    image = MemorySegment.NULL;
                });
            }
            if (target.address() != 0
                    && dialog.address() == 0
                    && menu.address() == 0
                    && statusItem.address() == 0
                    && acknowledgeItem.address() == 0
                    && quitItem.address() == 0) {
                failure = cleanup(failure, () -> {
                    cocoa.send(VOID, target, "release");
                    TARGETS.remove(target.address());
                    target = MemorySegment.NULL;
                });
            }
            failure = cleanup(failure, () -> {
                cleanupPool.close();
                cleanupPool = null;
            });
        }
        // Drain autoreleases successfully before disposing the class, then its IMP stubs.
        // On failure keep the arena alive and the remaining handles available for a later close.
        if (failure == null && target.address() == 0 && targetClass.address() != 0) {
            failure = cleanup(null, () -> {
                cocoa.disposeClass(targetClass);
                targetClass = MemorySegment.NULL;
            });
        }
        if (failure == null && targetClass.address() == 0) {
            failure = cleanup(null, () -> {
                cocoa.close();
                disposed = true;
            });
        }
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

    private void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("MacTray is confined to its creating OS main thread");
        }
    }

    private static MemorySegment required(final MemorySegment object) {
        if (object.address() == 0) {
            throw new IllegalStateException("AppKit/Objective-C returned nil");
        }
        return object;
    }

    // These callbacks only record requests. In particular, acknowledging never approves a prompt.
    // No exception may unwind through an FFM upcall into Objective-C.
    private static void acknowledgeUnread(
            final MemorySegment self, final MemorySegment selector, final MemorySegment sender) {
        try {
            final var tray = TARGETS.get(self.address());
            if (tray != null) {
                tray.unreadAcknowledged = true;
            }
        } catch (final Throwable ignored) {
            // Keep the native callback boundary exception-free.
        }
    }

    private static void quitTray(final MemorySegment self, final MemorySegment selector, final MemorySegment sender) {
        try {
            final var tray = TARGETS.get(self.address());
            if (tray != null) {
                tray.quitRequested = true;
            }
        } catch (final Throwable ignored) {
            // Keep the native callback boundary exception-free.
        }
    }

    private static void showNotice(final MemorySegment self, final MemorySegment selector, final MemorySegment sender) {
        try {
            final var tray = TARGETS.get(self.address());
            if (tray != null && !tray.quitRequested) {
                tray.requestedNotice = tray.noticeItems.get(sender.address());
            }
        } catch (final Throwable ignored) {
            // Keep the native callback boundary exception-free.
        }
    }

    private static void dismissMenuNotice(
            final MemorySegment self, final MemorySegment selector, final MemorySegment sender) {
        try {
            final var tray = TARGETS.get(self.address());
            if (tray != null && !tray.quitRequested) {
                final var notice = tray.noticeItems.get(sender.address());
                if (notice != null) {
                    tray.dismissed.add(notice);
                }
            }
        } catch (final Throwable ignored) {
            // Keep the native callback boundary exception-free.
        }
    }

    private static void dismissNotice(
            final MemorySegment self, final MemorySegment selector, final MemorySegment sender) {
        try {
            final var tray = TARGETS.get(self.address());
            if (tray != null && tray.dialogNotice != null && sender.address() == tray.dismissButton.address()) {
                tray.dismissDialog = true;
            }
        } catch (final Throwable ignored) {
            // Keep the native callback boundary exception-free.
        }
    }

    private static void closeNotice(
            final MemorySegment self, final MemorySegment selector, final MemorySegment sender) {
        try {
            final var tray = TARGETS.get(self.address());
            if (tray != null && sender.address() == tray.closeButton.address()) {
                tray.closeDialog = true;
            }
        } catch (final Throwable ignored) {
            // Keep the native callback boundary exception-free.
        }
    }

    private static final class Cocoa implements AutoCloseable {
        private final Arena arena = Arena.ofConfined();
        private final Linker linker;
        private final SymbolLookup objc;
        private final MemorySegment message;
        private final MemorySegment defaultMode;
        private final MethodHandle getClass;
        private final MethodHandle registerSelector;
        private final MethodHandle allocateClass;
        private final MethodHandle addMethod;
        private final MethodHandle registerClass;
        private final MethodHandle disposeClass;
        private final Map<FunctionDescriptor, MethodHandle> messages = new HashMap<>();
        private final Map<String, MemorySegment> selectors = new HashMap<>();

        private Cocoa() {
            try {
                linker = Linker.nativeLinker();
                final var system = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", arena);
                final var isMain = linker.downcallHandle(system.findOrThrow("pthread_main_np"), IS_MAIN);
                if ((int) invoke(isMain) == 0) {
                    throw new IllegalStateException(
                            "Construct MacTray on the OS main thread (JVM: -XstartOnFirstThread)");
                }
                SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", arena);
                final var foundation =
                        SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", arena);
                defaultMode = required(foundation
                        .findOrThrow("NSDefaultRunLoopMode")
                        .reinterpret(ADDRESS.byteSize())
                        .get(ADDRESS, 0));
                objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
                message = objc.findOrThrow("objc_msgSend");
                getClass = function("objc_getClass", NAMED_POINTER);
                registerSelector = function("sel_registerName", NAMED_POINTER);
                allocateClass = function("objc_allocateClassPair", ALLOCATE_CLASS);
                addMethod = function("class_addMethod", ADD_METHOD);
                registerClass = function("objc_registerClassPair", CLASS_VOID);
                disposeClass = function("objc_disposeClassPair", CLASS_VOID);
            } catch (final RuntimeException | Error failure) {
                try {
                    arena.close();
                } catch (final RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private MethodHandle function(final String name, final FunctionDescriptor descriptor) {
            return linker.downcallHandle(objc.findOrThrow(name), descriptor);
        }

        private MemorySegment type(final String name) {
            try (final var strings = Arena.ofConfined()) {
                return required((MemorySegment) invoke(getClass, strings.allocateFrom(name)));
            }
        }

        private MemorySegment selector(final String name) {
            return selectors.computeIfAbsent(name, key -> {
                try (final var strings = Arena.ofConfined()) {
                    return required((MemorySegment) invoke(registerSelector, strings.allocateFrom(key)));
                }
            });
        }

        private Object send(
                final FunctionDescriptor descriptor,
                final MemorySegment receiver,
                final String selector,
                final Object... arguments) {
            final var parameters = new Object[arguments.length + 2];
            parameters[0] = receiver;
            parameters[1] = selector(selector);
            System.arraycopy(arguments, 0, parameters, 2, arguments.length);
            return invoke(messages.computeIfAbsent(descriptor, key -> linker.downcallHandle(message, key)), parameters);
        }

        private MemorySegment object(final MemorySegment receiver, final String selector) {
            return required((MemorySegment) send(OBJECT, receiver, selector));
        }

        private MemorySegment string(final String value) {
            try (final var strings = Arena.ofConfined()) {
                return required((MemorySegment)
                        send(OBJECT_ARGUMENT, type("NSString"), "stringWithUTF8String:", strings.allocateFrom(value)));
            }
        }

        private MemorySegment allocateClass(final String name) {
            try (final var strings = Arena.ofConfined()) {
                return required(
                        (MemorySegment) invoke(allocateClass, type("NSObject"), strings.allocateFrom(name), 0L));
            }
        }

        private void addAction(final MemorySegment type, final String selector, final MethodHandle callback) {
            try (final var strings = Arena.ofConfined()) {
                final var stub = linker.upcallStub(callback, ACTION, arena);
                if ((byte) invoke(addMethod, type, selector(selector), stub, strings.allocateFrom("v@:@")) == 0) {
                    throw new IllegalStateException("Unable to install Objective-C action " + selector);
                }
            }
        }

        private void registerClass(final MemorySegment type) {
            invoke(registerClass, type);
        }

        private void disposeClass(final MemorySegment type) {
            invoke(disposeClass, type);
        }

        private Pool pool() {
            return new Pool(object(type("NSAutoreleasePool"), "new"));
        }

        @Override
        public void close() {
            arena.close();
        }

        private final class Pool implements AutoCloseable {
            private final MemorySegment pool;

            private Pool(final MemorySegment pool) {
                this.pool = pool;
            }

            @Override
            public void close() {
                send(VOID, pool, "drain");
            }
        }

        private static Object invoke(final MethodHandle handle, final Object... arguments) {
            try {
                return handle.invokeWithArguments(arguments);
            } catch (final RuntimeException | Error failure) {
                throw failure;
            } catch (final Throwable failure) {
                throw new IllegalStateException("Objective-C/FFM invocation failed", failure);
            }
        }
    }
}
