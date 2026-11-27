/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications.tray;

import static io.yupiik.ia.agent.notifications.NotificationPresentation.details;
import static io.yupiik.ia.agent.notifications.NotificationPresentation.preview;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemorySegment.NULL;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import io.yupiik.ia.agent.notifications.Notice;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Win64 tray. All operations except wakeup belong to the constructing desktop thread. */
public final class WindowsTray implements NativeTray {
    // Windows uses LLP64 on both x86_64 and ARM64: LONG/BOOL/UINT are 32 bits,
    // WPARAM/LPARAM/LRESULT and handles are 64 bits, and WCHAR is little-endian UTF-16.
    // No linker or Windows DLL is initialized by loading this class.
    private static final ValueLayout.OfChar WCHAR = ValueLayout.JAVA_CHAR.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final StructLayout WINDOW_CLASS = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            JAVA_INT.withName("style"),
            ADDRESS.withName("lpfnWndProc"),
            JAVA_INT.withName("cbClsExtra"),
            JAVA_INT.withName("cbWndExtra"),
            ADDRESS.withName("hInstance"),
            ADDRESS.withName("hIcon"),
            ADDRESS.withName("hCursor"),
            ADDRESS.withName("hbrBackground"),
            ADDRESS.withName("lpszMenuName"),
            ADDRESS.withName("lpszClassName"),
            ADDRESS.withName("hIconSm"));
    private static final StructLayout NOTIFY_ICON = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hWnd"),
            JAVA_INT.withName("uID"),
            JAVA_INT.withName("uFlags"),
            JAVA_INT.withName("uCallbackMessage"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hIcon"),
            MemoryLayout.sequenceLayout(128, WCHAR).withName("szTip"),
            JAVA_INT.withName("dwState"),
            JAVA_INT.withName("dwStateMask"),
            MemoryLayout.sequenceLayout(256, WCHAR).withName("szInfo"),
            JAVA_INT.withName("uVersion"),
            MemoryLayout.sequenceLayout(64, WCHAR).withName("szInfoTitle"),
            JAVA_INT.withName("dwInfoFlags"),
            MemoryLayout.sequenceLayout(4, JAVA_INT).withName("guidItem"),
            ADDRESS.withName("hBalloonIcon"));
    private static final StructLayout POINT = MemoryLayout.structLayout(JAVA_INT.withName("x"), JAVA_INT.withName("y"));
    private static final StructLayout MESSAGE = MemoryLayout.structLayout(
            ADDRESS.withName("hwnd"),
            JAVA_INT.withName("message"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("wParam"),
            JAVA_LONG.withName("lParam"),
            JAVA_INT.withName("time"),
            POINT.withName("pt"),
            JAVA_INT.withName("lPrivate"));
    private static final FunctionDescriptor WINDOW_PROC =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
    private static final MethodType WINDOW_PROC_TYPE = WINDOW_PROC.toMethodType();
    private static final ConcurrentHashMap<Long, WindowsTray> WINDOWS = new ConcurrentHashMap<>();
    private static final ThreadLocal<WindowsTray> CONSTRUCTING = new ThreadLocal<>();
    private static final int TRAY_MESSAGE = 0x8001; // WM_APP + 1
    private static final int WAKEUP_MESSAGE = 0x8002; // WM_APP + 2, retained in the queue until poll
    private static final int QS_ALLINPUT = 0x04ff;
    private static final int MWMO_INPUTAVAILABLE = 0x0004;
    private static final int ACKNOWLEDGE = 1;
    private static final int QUIT = 2;
    private static final int FIRST_NOTICE = 3;
    private static final int DISMISS = 100;
    private static final int DETAILS = 101;
    private static final long RETRY_NANOS = 1_000_000_000L;

    private final Thread owner;
    private final Arena arena;
    private final ReentrantLock wakeupLock = new ReentrantLock();
    // Only accessed under wakeupLock; unlike window, never changed by ordinary desktop operations.
    private MemorySegment wakeupWindow = NULL;
    private Api api;
    private MemorySegment instance = NULL;
    private MemorySegment className = NULL;
    private MemorySegment window = NULL;
    private MemorySegment menu = NULL;
    private MemorySegment dialog = NULL;
    private MemorySegment dialogCallback = NULL;
    private Notice dialogNotice;
    private List<Notice> notices = List.of();
    private final List<Notice> dismissals = new ArrayList<>();
    private MemorySegment normalIcon = NULL;
    private MemorySegment attentionIcon = NULL;
    private MemorySegment iconData;
    private MemorySegment message;
    private int taskbarCreated;
    private boolean registered;
    private boolean iconAdded;
    private boolean version4;
    private boolean menuRequested;
    private boolean menuOpen;
    private boolean acknowledged;
    private boolean closed;
    private boolean disposed;
    private long nextRetry;
    private Throwable callbackFailure;

    public WindowsTray(final Path icon) {
        final var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows")
                || ADDRESS.byteSize() != 8
                || !(arch.equals("amd64") || arch.equals("x86_64") || arch.equals("aarch64") || arch.equals("arm64"))) {
            throw new UnsupportedOperationException("WindowsTray requires 64-bit Windows (x86_64 or ARM64)");
        }
        owner = Thread.currentThread();
        arena = Arena.ofShared(); // DLL symbols must also be callable by the wakeup thread.
        try {
            api = new Api(arena);
            initialize(icon);
        } catch (final Throwable failure) {
            try {
                close();
            } catch (final Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failed("initialize Windows tray", failure);
        }
    }

    private void initialize(final Path icon) throws Throwable {
        instance = (MemorySegment) api.getModuleHandle.invokeExact(NULL);
        requireHandle(instance, "GetModuleHandleW");
        className = wide(arena, "YCA.Notifications." + UUID.randomUUID());
        final var callback = api.linker.upcallStub(
                MethodHandles.lookup().findStatic(WindowsTray.class, "windowProc", WINDOW_PROC_TYPE),
                WINDOW_PROC,
                arena);
        final var windowClass = arena.allocate(WINDOW_CLASS);
        windowClass.set(JAVA_INT, offset(WINDOW_CLASS, "cbSize"), (int) WINDOW_CLASS.byteSize());
        windowClass.set(ADDRESS, offset(WINDOW_CLASS, "lpfnWndProc"), callback);
        windowClass.set(ADDRESS, offset(WINDOW_CLASS, "hInstance"), instance);
        windowClass.set(ADDRESS, offset(WINDOW_CLASS, "lpszClassName"), className);
        if ((short) api.registerClass.invokeExact(windowClass) == 0) {
            throw nativeFailure("RegisterClassExW");
        }
        registered = true;
        taskbarCreated = (int) api.registerWindowMessage.invokeExact(wide(arena, "TaskbarCreated"));
        requireSuccess(taskbarCreated, "RegisterWindowMessageW");
        normalIcon = (MemorySegment) api.loadImage.invokeExact(
                NULL, wide(arena, icon.toAbsolutePath().toString()), 1, 0, 0, 0x50); // ICON, LOADFROMFILE | DEFAULTSIZE
        attentionIcon = (MemorySegment) api.loadIcon.invokeExact(NULL, MemorySegment.ofAddress(32515)); // IDI_WARNING
        requireHandle(normalIcon, "LoadImageW(YCA icon)");
        requireHandle(attentionIcon, "LoadIconW(warning)");

        final var previous = CONSTRUCTING.get();
        CONSTRUCTING.set(this);
        try {
            // Hidden WS_EX_TOOLWINDOW, no parent: HWND_MESSAGE would miss TaskbarCreated broadcasts.
            window = (MemorySegment)
                    api.createWindow.invokeExact(0x80, className, className, 0, 0, 0, 0, 0, NULL, NULL, instance, NULL);
        } finally {
            if (previous == null) {
                CONSTRUCTING.remove();
            } else {
                CONSTRUCTING.set(previous);
            }
        }
        requireHandle(window, "CreateWindowExW");
        WINDOWS.put(window.address(), this);
        wakeupLock.lock();
        try {
            wakeupWindow = window;
        } finally {
            wakeupLock.unlock();
        }
        checkCallback();
        dialogCallback = api.linker.upcallStub(
                MethodHandles.lookup().findStatic(WindowsTray.class, "dialogProc", WINDOW_PROC_TYPE),
                WINDOW_PROC,
                arena);
        rebuildMenu(List.of());
        message = arena.allocate(MESSAGE);
        iconData = arena.allocate(NOTIFY_ICON);
        iconData.set(JAVA_INT, offset(NOTIFY_ICON, "cbSize"), (int) NOTIFY_ICON.byteSize());
        iconData.set(ADDRESS, offset(NOTIFY_ICON, "hWnd"), window);
        iconData.set(JAVA_INT, offset(NOTIFY_ICON, "uID"), 1);
        // NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP; no transient balloon notification.
        iconData.set(JAVA_INT, offset(NOTIFY_ICON, "uFlags"), 0x87);
        iconData.set(JAVA_INT, offset(NOTIFY_ICON, "uCallbackMessage"), TRAY_MESSAGE);
        iconData.set(ADDRESS, offset(NOTIFY_ICON, "hIcon"), normalIcon);
        writeUtf16(tooltipBuffer(), "YCA notifications");
        addIcon(); // Explorer might not be running yet; poll retries until it is available.
    }

    @Override
    public void updateNotices(final List<Notice> updated) {
        checkThread();
        if (closed || disposed) {
            return;
        }
        try {
            rebuildMenu(List.copyOf(updated));
        } catch (final Throwable failure) {
            throw failed("update Windows notification menu", failure);
        }
    }

    private void rebuildMenu(final List<Notice> updated) throws Throwable {
        // AppendMenu copies its strings. Neither menu text nor snapshots accumulate in the tray arena.
        final var replacement = (MemorySegment) api.createPopupMenu.invokeExact();
        requireHandle(replacement, "CreatePopupMenu");
        try (final var scratch = Arena.ofConfined()) {
            for (int i = 0; i < updated.size(); i++) {
                // Ampersands are menu mnemonics in Win32, not literal text unless doubled.
                // Text markers avoid introducing owned HBITMAPs or owner-drawn menu callbacks.
                // Each snapshot has adjacent preview/dismiss commands, not a session-id lookup.
                requireSuccess(
                        (int) api.appendMenu.invokeExact(
                                replacement,
                                0,
                                FIRST_NOTICE + 2L * i,
                                wide(scratch, "[i] " + preview(updated.get(i)).replace("&", "&&"))),
                        "AppendMenuW(notice)");
                requireSuccess(
                        (int) api.appendMenu.invokeExact(
                                replacement, 0, FIRST_NOTICE + 2L * i + 1, wide(scratch, "    [x] Dismiss")),
                        "AppendMenuW(dismiss)");
            }
            if (!updated.isEmpty()) {
                requireSuccess(
                        (int) api.appendMenu.invokeExact(replacement, 0x800, 0L, NULL), "AppendMenuW(separator)");
            }
            requireSuccess(
                    (int) api.appendMenu.invokeExact(
                            replacement, 0, (long) ACKNOWLEDGE, wide(scratch, "Acknowledge unread")),
                    "AppendMenuW(acknowledge)");
            requireSuccess(
                    (int) api.appendMenu.invokeExact(replacement, 0, (long) QUIT, wide(scratch, "Quit")),
                    "AppendMenuW(quit)");
            if (menu.address() != 0) {
                requireSuccess((int) api.destroyMenu.invokeExact(menu), "DestroyMenu");
            }
        } catch (final Throwable failure) {
            final var destroyed = (int) api.destroyMenu.invokeExact(replacement);
            throw failure;
        }
        menu = replacement;
        notices = updated;
    }

    @Override
    public List<Notice> dismissed() {
        checkThread();
        final var result = List.copyOf(dismissals);
        dismissals.clear();
        return result;
    }

    @Override
    public void update(final String tooltip, final boolean attention) {
        checkThread();
        if (closed || disposed) {
            return;
        }
        try {
            iconData.set(ADDRESS, offset(NOTIFY_ICON, "hIcon"), attention ? attentionIcon : normalIcon);
            writeUtf16(tooltipBuffer(), tooltip);
            if (iconAdded && (int) api.notifyIcon.invokeExact(1, iconData) == 0) { // NIM_MODIFY
                iconAdded = false;
            }
            if (!iconAdded) {
                addIcon();
            }
            checkCallback();
        } catch (final Throwable failure) {
            throw failed("update Windows tray", failure);
        }
    }

    @Override
    public void poll() {
        checkThread();
        checkCallback();
        if (closed || disposed) {
            return;
        }
        try {
            // Filter on our HWNDs so unrelated windows on this thread keep their messages.
            // A finite batch also prevents continuous native traffic starving the desktop loop.
            for (int count = 0; count < 256 && !closed; count++) {
                // Alternate preference so a busy dialog cannot starve shutdown/wakeup on the tray.
                final var first = count % 2 == 0 || dialog.address() == 0 ? window : dialog;
                final var second = first.address() == window.address() ? dialog : window;
                if ((int) api.peekMessage.invokeExact(message, first, 0, 0, 1) == 0) { // PM_REMOVE
                    if (second.address() == 0 || (int) api.peekMessage.invokeExact(message, second, 0, 0, 1) == 0) {
                        break;
                    }
                }
                if (message.get(JAVA_INT, offset(MESSAGE, "message")) == 0x12) { // WM_QUIT
                    closed = true;
                    break;
                }
                if (dialog.address() == 0 || (int) api.isDialogMessage.invokeExact(dialog, message) == 0) {
                    final var translated = (int) api.translateMessage.invokeExact(message);
                    final var dispatched = (long) api.dispatchMessage.invokeExact(message);
                }
                checkCallback();
            }
            if (menuRequested && !closed && !menuOpen) {
                menuRequested = false;
                showMenu();
            }
            if (!closed && !iconAdded && System.nanoTime() - nextRetry >= 0) {
                addIcon();
            }
            checkCallback();
        } catch (final Throwable failure) {
            throw failed("poll Windows tray", failure);
        }
    }

    @Override
    public void await(final long timeoutMillis) {
        checkThread();
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        checkCallback();
        if (closed || disposed) {
            return;
        }
        // INFINITE is reserved; clamp finite waits to the largest finite DWORD timeout.
        long waitMillis = timeoutMillis == 0 ? 0xffff_ffffL : Math.min(timeoutMillis, 0xffff_fffeL);
        if (!iconAdded) {
            final var remaining = nextRetry - System.nanoTime();
            if (remaining <= 0) {
                return; // Only poll performs the retry.
            }
            waitMillis = Math.min(waitMillis, (remaining + 999_999L) / 1_000_000L);
        }
        try {
            // INPUTAVAILABLE also observes queued messages that PeekMessage has already inspected.
            final var result =
                    (int) api.msgWait.invokeExact(0, NULL, (int) waitMillis, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
            if (result == -1) { // WAIT_FAILED
                throw nativeFailure("MsgWaitForMultipleObjectsEx");
            }
            checkCallback();
        } catch (final Throwable failure) {
            throw failed("await Windows tray events", failure);
        }
    }

    @Override
    public void wakeup() {
        wakeupLock.lock();
        try {
            if (wakeupWindow.address() == 0) {
                return;
            }
            // PostMessage queues the wakeup even when await has not started yet.
            requireSuccess(
                    (int) api.postMessage.invokeExact(wakeupWindow, WAKEUP_MESSAGE, 0L, 0L), "PostMessageW(wakeup)");
        } catch (final Throwable failure) {
            throw failed("wake Windows tray", failure);
        } finally {
            wakeupLock.unlock();
        }
    }

    @Override
    public boolean acknowledged() {
        checkThread();
        final var result = acknowledged;
        acknowledged = false;
        return result;
    }

    @Override
    public boolean closed() {
        checkThread();
        return closed;
    }

    @Override
    public void close() {
        checkThread();
        wakeupLock.lock();
        try {
            closeLocked();
        } finally {
            wakeupLock.unlock();
        }
    }

    private void closeLocked() {
        if (disposed) {
            return;
        }
        closed = true;
        wakeupWindow = NULL; // Stop posts before destroying the HWND or unloading its DLL.
        try {
            if (api != null) {
                closeDialog();
                if (iconData != null) {
                    // A false result is normal when Explorer has already exited.
                    final var removed = (int) api.notifyIcon.invokeExact(2, iconData); // NIM_DELETE
                    iconAdded = false;
                }
                if (window.address() != 0) {
                    final var handle = window;
                    requireSuccess((int) api.destroyWindow.invokeExact(handle), "DestroyWindow");
                    WINDOWS.remove(handle.address(), this);
                    window = NULL;
                }
                if (registered) {
                    requireSuccess((int) api.unregisterClass.invokeExact(className, instance), "UnregisterClassW");
                    registered = false;
                }
                if (menu.address() != 0) {
                    requireSuccess((int) api.destroyMenu.invokeExact(menu), "DestroyMenu");
                    menu = NULL;
                }
                if (normalIcon.address() != 0) {
                    requireSuccess((int) api.destroyIcon.invokeExact(normalIcon), "DestroyIcon(YCA)");
                    normalIcon = NULL;
                }
            }
            // The warning LoadIconW handle is shared and MUST NOT be passed to DestroyIcon.
            // Keep the callback and DLLs alive until both the window and its class are gone.
            // If destruction fails, retain the arena so a later close can retry safely.
            arena.close();
            disposed = true;
        } catch (final Throwable failure) {
            throw failed("close Windows tray", failure);
        }
    }

    private void addIcon() throws Throwable {
        nextRetry = System.nanoTime() + RETRY_NANOS;
        iconAdded = (int) api.notifyIcon.invokeExact(0, iconData) != 0; // NIM_ADD
        version4 = false;
        if (iconAdded) {
            iconData.set(JAVA_INT, offset(NOTIFY_ICON, "uVersion"), 4);
            version4 = (int) api.notifyIcon.invokeExact(4, iconData) != 0; // NIM_SETVERSION on EVERY add
        }
    }

    private void showMenu() throws Throwable {
        final var menuNotices = notices; // Keep the exact snapshots represented by this menu invocation.
        menuOpen = true;
        try (final var scratch = Arena.ofConfined()) {
            final var point = scratch.allocate(POINT);
            requireSuccess((int) api.getCursorPos.invokeExact(point), "GetCursorPos");
            final var foreground = (int) api.setForegroundWindow.invokeExact(window);
            // TrackPopupMenuEx has its own modal menu loop; PeekMessage itself never waits.
            final var command = (int) api.trackPopupMenu.invokeExact(
                    menu,
                    0x182, // RETURNCMD | NONOTIFY | RIGHTBUTTON
                    point.get(JAVA_INT, 0),
                    point.get(JAVA_INT, 4),
                    window,
                    NULL);
            if (command == ACKNOWLEDGE) {
                acknowledged = true; // Only a reminder acknowledgement, never an approval response.
            } else if (command == QUIT) {
                closed = true;
            } else if (command >= FIRST_NOTICE && (command - FIRST_NOTICE) / 2 < menuNotices.size()) {
                final var noticeCommand = command - FIRST_NOTICE;
                final var selected = menuNotices.get(noticeCommand / 2);
                if (noticeCommand % 2 == 0) {
                    showDetails(selected);
                } else {
                    dismissals.add(selected); // Exact snapshot, just like the details dialog's Dismiss.
                }
            }
            // Required for reliable dismissal/reopening of notification-area shortcut menus.
            final var posted = (int) api.postMessage.invokeExact(window, 0, 0L, 0L); // WM_NULL
            if (iconAdded && dialog.address() == 0) {
                final var focused = (int) api.notifyIcon.invokeExact(3, iconData); // NIM_SETFOCUS
            }
        } finally {
            menuOpen = false;
            menuRequested = false;
        }
    }

    private void showDetails(final Notice selected) throws Throwable {
        if (dialog.address() != 0) {
            final var foreground = (int) api.setForegroundWindow.invokeExact(dialog);
            return; // Never replace the snapshot behind an open Dismiss button.
        }
        dialogNotice = selected;
        try (final var scratch = Arena.ofConfined()) {
            final var template = dialogTemplate(scratch);
            final var previous = CONSTRUCTING.get();
            CONSTRUCTING.set(this);
            try {
                dialog = (MemorySegment) api.createDialog.invokeExact(instance, template, window, dialogCallback, 0L);
            } finally {
                if (previous == null) {
                    CONSTRUCTING.remove();
                } else {
                    CONSTRUCTING.set(previous);
                }
            }
            requireHandle(dialog, "CreateDialogIndirectParamW");
            WINDOWS.put(dialog.address(), this);
            checkCallback();
            final var text =
                    details(selected).replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
            requireSuccess(
                    (int) api.setDlgItemText.invokeExact(dialog, DETAILS, wide(scratch, text)), "SetDlgItemTextW");
            final var shown = (int) api.showWindow.invokeExact(dialog, 5); // SW_SHOW, no nested modal loop
            final var foreground = (int) api.setForegroundWindow.invokeExact(dialog);
        } catch (final Throwable failure) {
            try {
                closeDialog();
            } catch (final Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void closeDialog() throws Throwable {
        if (dialog.address() != 0) {
            final var handle = dialog;
            requireSuccess((int) api.destroyWindow.invokeExact(handle), "DestroyWindow(details)");
            WINDOWS.remove(handle.address(), this);
            dialog = NULL;
        }
        dialogNotice = null;
    }

    // Standard dialog templates are WORD packed, with DWORD-aligned structure starts.
    // The native dialog manager owns the font and controls, released by DestroyWindow.
    private static MemorySegment dialogTemplate(final Arena scratch) {
        final var buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x80c800c0); // POPUP | CAPTION | SYSMENU | DS_MODALFRAME | DS_SETFONT
        buffer.putInt(0);
        buffer.putShort((short) 3);
        buffer.putShort((short) 0).putShort((short) 0).putShort((short) 460).putShort((short) 280);
        buffer.putShort((short) 0).putShort((short) 0); // no menu, default dialog class
        templateText(buffer, "YCA notification details");
        buffer.putShort((short) 9);
        templateText(buffer, "Segoe UI");
        // EDIT: CHILD | VISIBLE | BORDER | TABSTOP | VSCROLL | MULTILINE | READONLY | AUTOVSCROLL
        // No horizontal scrolling: multiline edit controls must wrap long received text.
        templateControl(buffer, 0x50a10844, 10, 10, 440, 230, DETAILS, 0x81, "");
        templateControl(buffer, 0x50010000, 290, 250, 75, 20, DISMISS, 0x80, "Dismiss");
        templateControl(buffer, 0x50010001, 375, 250, 75, 20, 2, 0x80, "Close"); // IDCANCEL, default button
        final var result = scratch.allocate(buffer.position(), 4);
        result.copyFrom(MemorySegment.ofArray(buffer.array()).asSlice(0, buffer.position()));
        return result;
    }

    private static void templateControl(
            final ByteBuffer buffer,
            final int style,
            final int x,
            final int y,
            final int width,
            final int height,
            final int id,
            final int controlClass,
            final String title) {
        while (buffer.position() % 4 != 0) {
            buffer.put((byte) 0);
        }
        buffer.putInt(style).putInt(0);
        buffer.putShort((short) x).putShort((short) y).putShort((short) width).putShort((short) height);
        buffer.putShort((short) id).putShort((short) 0xffff).putShort((short) controlClass);
        templateText(buffer, title);
        buffer.putShort((short) 0); // no creation data
    }

    private static void templateText(final ByteBuffer buffer, final String value) {
        for (int i = 0; i < value.length(); i++) {
            buffer.putChar(value.charAt(i));
        }
        buffer.putChar('\0');
    }

    // DLGPROC returns INT_PTR on Win64. FALSE delegates unhandled messages to the dialog manager.
    private static long dialogProc(final MemorySegment hwnd, final int msg, final long wParam, final long lParam) {
        WindowsTray tray = null;
        try {
            tray = WINDOWS.get(hwnd.address());
            if (tray == null && msg == 0x110) { // WM_INITDIALOG
                tray = CONSTRUCTING.get();
                if (tray != null) {
                    tray.dialog = hwnd;
                    WINDOWS.put(hwnd.address(), tray);
                }
                return 1;
            }
            if (tray == null) {
                return 0;
            }
            if (msg == 0x111 && (wParam >>> 16) == 0) { // WM_COMMAND / BN_CLICKED; Escape -> IDCANCEL
                final var command = (int) (wParam & 0xffff);
                if (command == DISMISS || command == 2) {
                    if (command == DISMISS && tray.dialogNotice != null) {
                        tray.dismissals.add(tray.dialogNotice); // exact snapshot, not a session lookup
                        tray.dialogNotice = null;
                    }
                    tray.closeDialog();
                    return 1;
                }
            }
            if (msg == 0x10) { // WM_CLOSE (X/Alt+F4), never dismiss
                tray.closeDialog();
                return 1;
            }
            if (msg == 0x82) { // WM_NCDESTROY, including owner destruction
                WINDOWS.remove(hwnd.address(), tray);
                tray.dialog = NULL;
                tray.dialogNotice = null;
            }
            return 0;
        } catch (final Throwable failure) {
            if (tray != null) {
                tray.callbackFailure = failure;
                tray.closed = true;
            }
            return 0; // Never unwind a Java exception through the native dialog manager.
        }
    }

    // Static, unbound upcall target: also registered as a directUpcall in reachability-metadata.json.
    // Absolutely no Throwable may escape an FFM upcall (the VM would terminate).
    private static long windowProc(final MemorySegment hwnd, final int msg, final long wParam, final long lParam) {
        WindowsTray tray = null;
        try {
            tray = WINDOWS.get(hwnd.address());
            if (tray == null) {
                tray = CONSTRUCTING.get(); // CreateWindowExW calls us before returning its HWND.
            }
            if (tray == null) {
                return 0;
            }
            if (msg == tray.taskbarCreated && tray.taskbarCreated != 0) {
                tray.iconAdded = false;
                tray.nextRetry = System.nanoTime();
                return 0;
            }
            if (msg == TRAY_MESSAGE) {
                final var event = (int) (lParam & 0xffff);
                if (event == 0x7b || (!tray.version4 && event == 0x205)) { // WM_CONTEXTMENU / legacy WM_RBUTTONUP
                    tray.menuRequested = true;
                }
                return 0;
            }
            if (msg == WAKEUP_MESSAGE) {
                if (tray.menuOpen) {
                    // Leave the menu's native modal loop so the caller can process updates/shutdown.
                    final var ended = (int) tray.api.endMenu.invokeExact();
                }
                return 0;
            }
            if (msg == 0x10 || msg == 0x2 || (msg == 0x16 && wParam != 0)) { // CLOSE / DESTROY / ENDSESSION
                tray.closed = true;
                return 0;
            }
            final var result = (long) tray.api.defWindowProc.invokeExact(hwnd, msg, wParam, lParam);
            if (msg == 0x82) { // WM_NCDESTROY: also handle destruction initiated outside close().
                tray.wakeupLock.lock();
                try {
                    tray.wakeupWindow = NULL;
                } finally {
                    tray.wakeupLock.unlock();
                }
                WINDOWS.remove(hwnd.address(), tray);
                if (tray.window.address() == hwnd.address()) {
                    tray.window = NULL;
                }
                tray.closed = true;
            }
            return result;
        } catch (final Throwable failure) {
            if (tray != null) {
                tray.callbackFailure = failure;
                tray.closed = true;
            }
            return 0;
        }
    }

    private MemorySegment tooltipBuffer() {
        return iconData.asSlice(offset(NOTIFY_ICON, "szTip"), 128 * WCHAR.byteSize());
    }

    /** Writes a terminated UTF-16LE string without splitting a surrogate pair or retaining old text. */
    private static void writeUtf16(final MemorySegment buffer, final String value) {
        buffer.fill((byte) 0);
        if (value == null) {
            return;
        }
        final var capacity = buffer.byteSize() / WCHAR.byteSize() - 1;
        int output = 0;
        for (int i = 0; i < value.length() && output < capacity; i++) {
            final var c = value.charAt(i);
            if (c == 0) {
                break;
            }
            if (Character.isHighSurrogate(c)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                if (output + 2 > capacity) {
                    break;
                }
                buffer.setAtIndex(WCHAR, output++, c);
                buffer.setAtIndex(WCHAR, output++, value.charAt(++i));
            } else {
                buffer.setAtIndex(WCHAR, output++, Character.isSurrogate(c) ? '\ufffd' : c);
            }
        }
    }

    private static MemorySegment wide(final Arena arena, final String value) {
        final var result = arena.allocate(((long) value.length() + 1) * WCHAR.byteSize(), WCHAR.byteAlignment());
        writeUtf16(result, value);
        return result;
    }

    private static long offset(final MemoryLayout layout, final String field) {
        return layout.byteOffset(groupElement(field));
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("WindowsTray is confined to its constructing desktop thread");
        }
    }

    private void checkCallback() {
        if (callbackFailure != null) {
            throw failed("dispatch Windows tray callback", callbackFailure);
        }
    }

    private void requireHandle(final MemorySegment handle, final String operation) throws Throwable {
        if (handle.address() == 0) {
            throw nativeFailure(operation);
        }
    }

    private void requireSuccess(final int result, final String operation) throws Throwable {
        if (result == 0) {
            throw nativeFailure(operation);
        }
    }

    private IllegalStateException nativeFailure(final String operation) throws Throwable {
        return new IllegalStateException(operation + " failed (Win32 error "
                + Integer.toUnsignedString((int) api.getLastError.invokeExact()) + ")");
    }

    private static IllegalStateException failed(final String operation, final Throwable cause) {
        return new IllegalStateException("Unable to " + operation, cause);
    }

    /** DLLs and their symbols belong to the tray's arena, not a static initializer. */
    private static final class Api {
        private static final FunctionDescriptor HANDLE_FROM_POINTER = FunctionDescriptor.of(ADDRESS, ADDRESS);
        private static final FunctionDescriptor LAST_ERROR = FunctionDescriptor.of(JAVA_INT);
        private static final FunctionDescriptor REGISTER_CLASS = FunctionDescriptor.of(JAVA_SHORT, ADDRESS);
        private static final FunctionDescriptor BOOL_TWO_POINTERS = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
        private static final FunctionDescriptor CREATE_WINDOW = FunctionDescriptor.of(
                ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS,
                ADDRESS, ADDRESS);
        private static final FunctionDescriptor BOOL_POINTER = FunctionDescriptor.of(JAVA_INT, ADDRESS);
        private static final FunctionDescriptor LOAD_ICON = FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS);
        private static final FunctionDescriptor LOAD_IMAGE =
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
        private static final FunctionDescriptor NOTIFY_ICON = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS);
        private static final FunctionDescriptor PEEK_MESSAGE =
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT);
        private static final FunctionDescriptor DISPATCH_MESSAGE = FunctionDescriptor.of(JAVA_LONG, ADDRESS);
        private static final FunctionDescriptor CREATE_MENU = FunctionDescriptor.of(ADDRESS);
        private static final FunctionDescriptor APPEND_MENU =
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS);
        private static final FunctionDescriptor TRACK_MENU =
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
        private static final FunctionDescriptor POST_MESSAGE =
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
        private static final FunctionDescriptor MSG_WAIT =
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT);

        private final Linker linker;
        private static final FunctionDescriptor CREATE_DIALOG =
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
        private static final FunctionDescriptor SET_DLG_ITEM_TEXT =
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
        private static final FunctionDescriptor SHOW_WINDOW = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);

        private final MethodHandle createDialog;
        private final MethodHandle isDialogMessage;
        private final MethodHandle setDlgItemText;
        private final MethodHandle showWindow;
        private final MethodHandle endMenu;
        private final MethodHandle getModuleHandle;
        private final MethodHandle getLastError;
        private final MethodHandle registerClass;
        private final MethodHandle unregisterClass;
        private final MethodHandle createWindow;
        private final MethodHandle destroyWindow;
        private final MethodHandle defWindowProc;
        private final MethodHandle loadIcon;
        private final MethodHandle loadImage;
        private final MethodHandle destroyIcon;
        private final MethodHandle registerWindowMessage;
        private final MethodHandle notifyIcon;
        private final MethodHandle peekMessage;
        private final MethodHandle translateMessage;
        private final MethodHandle dispatchMessage;
        private final MethodHandle createPopupMenu;
        private final MethodHandle appendMenu;
        private final MethodHandle destroyMenu;
        private final MethodHandle getCursorPos;
        private final MethodHandle setForegroundWindow;
        private final MethodHandle trackPopupMenu;
        private final MethodHandle postMessage;
        private final MethodHandle msgWait;

        private Api(final Arena arena) {
            linker = Linker.nativeLinker();
            final var kernel = SymbolLookup.libraryLookup("kernel32.dll", arena);
            final var user = SymbolLookup.libraryLookup("user32.dll", arena);
            final var shell = SymbolLookup.libraryLookup("shell32.dll", arena);
            getModuleHandle = bind(kernel, "GetModuleHandleW", HANDLE_FROM_POINTER);
            getLastError = bind(kernel, "GetLastError", LAST_ERROR);
            registerClass = bind(user, "RegisterClassExW", REGISTER_CLASS);
            unregisterClass = bind(user, "UnregisterClassW", BOOL_TWO_POINTERS);
            createWindow = bind(user, "CreateWindowExW", CREATE_WINDOW);
            destroyWindow = bind(user, "DestroyWindow", BOOL_POINTER);
            defWindowProc = bind(user, "DefWindowProcW", WINDOW_PROC);
            loadIcon = bind(user, "LoadIconW", LOAD_ICON);
            loadImage = bind(user, "LoadImageW", LOAD_IMAGE);
            destroyIcon = bind(user, "DestroyIcon", BOOL_POINTER);
            registerWindowMessage = bind(user, "RegisterWindowMessageW", BOOL_POINTER);
            notifyIcon = bind(shell, "Shell_NotifyIconW", NOTIFY_ICON);
            peekMessage = bind(user, "PeekMessageW", PEEK_MESSAGE);
            translateMessage = bind(user, "TranslateMessage", BOOL_POINTER);
            dispatchMessage = bind(user, "DispatchMessageW", DISPATCH_MESSAGE);
            createPopupMenu = bind(user, "CreatePopupMenu", CREATE_MENU);
            appendMenu = bind(user, "AppendMenuW", APPEND_MENU);
            destroyMenu = bind(user, "DestroyMenu", BOOL_POINTER);
            getCursorPos = bind(user, "GetCursorPos", BOOL_POINTER);
            setForegroundWindow = bind(user, "SetForegroundWindow", BOOL_POINTER);
            trackPopupMenu = bind(user, "TrackPopupMenuEx", TRACK_MENU);
            postMessage = bind(user, "PostMessageW", POST_MESSAGE);
            msgWait = bind(user, "MsgWaitForMultipleObjectsEx", MSG_WAIT);
            createDialog = bind(user, "CreateDialogIndirectParamW", CREATE_DIALOG);
            isDialogMessage = bind(user, "IsDialogMessageW", BOOL_TWO_POINTERS);
            setDlgItemText = bind(user, "SetDlgItemTextW", SET_DLG_ITEM_TEXT);
            showWindow = bind(user, "ShowWindow", SHOW_WINDOW);
            endMenu = bind(user, "EndMenu", LAST_ERROR);
        }

        private MethodHandle bind(final SymbolLookup library, final String name, final FunctionDescriptor descriptor) {
            return linker.downcallHandle(library.findOrThrow(name), descriptor);
        }
    }
}
