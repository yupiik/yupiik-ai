/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.notifications.tray;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Main-thread CoreFoundation wait companion to {@link MacTray#poll()}.
 * Construct, await and close on the OS main thread (-XstartOnFirstThread on a JVM).
 * Only {@link #wakeup()} may be called from other threads. Loading this class does
 * not load native libraries or initialize the native linker.
 *
 * <p>This helper does not dispatch NSEvents: the owner must continue to call poll
 * before waiting and after returning. Returns can be spurious, including after
 * another source is handled. Wakeups coalesce and remain pending until an await
 * returns, even if a nested AppKit run loop has already handled our source.</p>
 */
public final class MacEventWait implements AutoCloseable {
    // Keep these signatures in sync with mac-wait/reachability-metadata.json.
    private static final FunctionDescriptor IS_MAIN = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor GET_MAIN = FunctionDescriptor.of(ADDRESS);
    private static final FunctionDescriptor CREATE_SOURCE = FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor ADD_SOURCE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor POINTER_VOID = FunctionDescriptor.ofVoid(ADDRESS);
    private static final FunctionDescriptor RUN = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE, JAVA_BYTE);
    private static final FunctionDescriptor PERFORM = FunctionDescriptor.ofVoid(ADDRESS);
    private static final MethodType PERFORM_TYPE = MethodType.methodType(void.class, MemorySegment.class);
    // CFRunLoopSourceContext, version 0, on both supported 64-bit macOS ABIs.
    // CFIndex version; void *info; retain; release; copyDescription; equal; hash;
    // schedule; cancel; perform. All callbacks except perform are NULL.
    private static final MemoryLayout SOURCE_CONTEXT = MemoryLayout.structLayout(
            JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final long PERFORM_OFFSET = 9 * ADDRESS.byteSize();

    private final Thread owner = Thread.currentThread();
    private final ReentrantLock lock = new ReentrantLock();
    private final Arena arena;
    private final MethodHandle signal;
    private final MethodHandle wake;
    private final MethodHandle invalidate;
    private final MethodHandle release;
    private final MethodHandle run;
    private final MemorySegment runLoop;
    private final MemorySegment defaultMode;
    private MemorySegment source = MemorySegment.NULL;
    private boolean pending;
    private boolean closed;
    private boolean disposed;

    public MacEventWait() {
        final var os = System.getProperty("os.name", "");
        if (!"Mac OS X".equals(os) && !"Darwin".equals(os)) {
            throw new UnsupportedOperationException("MacEventWait requires macOS");
        }
        final var architecture = System.getProperty("os.arch", "");
        if (ADDRESS.byteSize() != 8
                || !("aarch64".equals(architecture) || "x86_64".equals(architecture) || "amd64".equals(architecture))) {
            throw new UnsupportedOperationException("MacEventWait requires 64-bit Intel or Apple Silicon");
        }
        // Publishers use the source and the downcall handles, so their scope must be shared.
        arena = Arena.ofShared();
        try {
            final var linker = Linker.nativeLinker();
            final var system = SymbolLookup.libraryLookup("/usr/lib/libSystem.B.dylib", arena);
            if ((int) linker.downcallHandle(system.findOrThrow("pthread_main_np"), IS_MAIN)
                            .invokeExact()
                    == 0) {
                throw new IllegalStateException(
                        "Construct MacEventWait on the OS main thread (JVM: -XstartOnFirstThread)");
            }
            final var core = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena);
            runLoop = (MemorySegment) linker.downcallHandle(core.findOrThrow("CFRunLoopGetMain"), GET_MAIN)
                    .invokeExact();
            defaultMode = core.findOrThrow("kCFRunLoopDefaultMode")
                    .reinterpret(ADDRESS.byteSize())
                    .get(ADDRESS, 0);
            if (runLoop.address() == 0 || defaultMode.address() == 0) {
                throw new IllegalStateException("CoreFoundation returned a NULL run loop or default mode");
            }
            signal = linker.downcallHandle(core.findOrThrow("CFRunLoopSourceSignal"), POINTER_VOID);
            wake = linker.downcallHandle(core.findOrThrow("CFRunLoopWakeUp"), POINTER_VOID);
            invalidate = linker.downcallHandle(core.findOrThrow("CFRunLoopSourceInvalidate"), POINTER_VOID);
            release = linker.downcallHandle(core.findOrThrow("CFRelease"), POINTER_VOID);
            run = linker.downcallHandle(core.findOrThrow("CFRunLoopRunInMode"), RUN);
            final var context = arena.allocate(SOURCE_CONTEXT);
            final var callback = MethodHandles.lookup().findStatic(MacEventWait.class, "perform", PERFORM_TYPE);
            context.set(ADDRESS, PERFORM_OFFSET, linker.upcallStub(callback, PERFORM, arena));
            source = (MemorySegment) linker.downcallHandle(core.findOrThrow("CFRunLoopSourceCreate"), CREATE_SOURCE)
                    .invokeExact(MemorySegment.NULL, 0L, context);
            if (source.address() == 0) {
                throw new IllegalStateException("Unable to create a CoreFoundation wakeup source");
            }
            linker.downcallHandle(core.findOrThrow("CFRunLoopAddSource"), ADD_SOURCE)
                    .invokeExact(runLoop, source, defaultMode);
        } catch (final Throwable failure) {
            try {
                close();
            } catch (final Throwable cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure(failure);
        }
    }

    /**
     * Wait for a native run-loop source, a wakeup or the timeout. Zero waits
     * indefinitely; negative timeouts are rejected. A wakeup before this call
     * causes an immediate return. This method does not dispatch queued NSEvents.
     */
    public void await(final long timeoutMillis) {
        checkThread();
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be nonnegative");
        }
        lock.lock();
        try {
            if (closed) {
                return;
            }
            if (pending) {
                pending = false;
                return;
            }
        } finally {
            lock.unlock();
        }
        // Never hold the lock while running native callbacks. Signaling the source
        // is sticky even if wakeup races between releasing the lock and entering CF.
        try {
            final var seconds = timeoutMillis == 0 ? Double.MAX_VALUE : timeoutMillis / 1_000d;
            final int result = (int) run.invokeExact(defaultMode, seconds, (byte) 1);
        } catch (final Throwable failure) {
            throw failure(failure);
        }
        lock.lock();
        try {
            // Any wakeup preceding this return has now been satisfied. A later
            // publisher acquires the lock afterwards and queues the next wakeup.
            pending = false;
        } finally {
            lock.unlock();
        }
    }

    /** Thread-safe, coalescing wakeup; harmless after close has begun. */
    public void wakeup() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            pending = true;
            signal.invokeExact(source);
            wake.invokeExact(runLoop);
        } catch (final Throwable failure) {
            throw failure(failure);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        checkThread();
        lock.lock();
        try {
            if (disposed) {
                return;
            }
            closed = true;
            // Invalidation removes the source from all modes before the callback
            // arena is freed. Only the owner can run or close, so no perform
            // callback can still be in flight here. The lock excludes publishers.
            if (source.address() != 0) {
                invalidate.invokeExact(source);
                release.invokeExact(source);
                source = MemorySegment.NULL;
            }
            arena.close();
            disposed = true;
        } catch (final Throwable failure) {
            // Keep native handles/stubs alive if cleanup failed; close can retry.
            throw failure(failure);
        } finally {
            lock.unlock();
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("MacEventWait is confined to its creating OS main thread");
        }
    }

    private static RuntimeException failure(final Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException exception) {
            return exception;
        }
        return new IllegalStateException("CoreFoundation event wait failed", failure);
    }

    // No Java state or exceptions cross this direct native upcall boundary.
    private static void perform(final MemorySegment info) {
        // A handled source makes CFRunLoopRunInMode return; Java owns pending state.
    }
}
