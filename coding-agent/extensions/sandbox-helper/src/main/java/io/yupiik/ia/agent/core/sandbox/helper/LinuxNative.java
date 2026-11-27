/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox.helper;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Linux-only libc calls. Only a disposable helper process may call {@link #exec}.
 *
 * <p>The C equivalents below assume the supported little-endian Linux LP64 ABI (x86_64 or aarch64):
 * pointers, {@code long}, {@code size_t} and {@code ssize_t} occupy eight bytes, {@code int} four,
 * and {@code short} two. Shared headers and libc declarations are:
 * <pre>{@code
 * #include <errno.h>
 * #include <stddef.h>
 * #include <stdint.h>
 * #include <linux/audit.h>
 * #include <linux/filter.h>
 * #include <linux/seccomp.h>
 * #include <sys/prctl.h>
 * #include <unistd.h>
 *
 * // Declared by the headers; these correspond to the FFM function descriptors:
 * int prctl(int option, ...);
 * int execve(const char *path, char *const argv[], char *const envp[]);
 * ssize_t write(int fd, const void *buffer, size_t count);
 * void _exit(int status);
 * }</pre>
 * <p>The linker resolves these symbols from the default native lookup. For {@code prctl}, only
 * the first argument is fixed; the four variadic arguments are passed as LP64 words.
 * Capturing {@code errno} adds a leading Java-only call-state segment to the method handle,
 * not to the C prototype, and snapshots the calling thread's errno before Java resumes.
 * {@code Arena} owns the native allocations; C snippets omit allocation failure handling and
 * use pseudocode where Java strings, collections or exceptions have no direct C equivalent.
 */
@SuppressWarnings("restricted")
public final class LinuxNative {
    private static final FunctionDescriptor PRCTL =
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final FunctionDescriptor EXECVE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor EXIT = FunctionDescriptor.ofVoid(JAVA_INT);
    private static final FunctionDescriptor WRITE = FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG);
    private static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final long ERRNO_OFFSET = CALL_STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private static final MemoryLayout FPROG =
            MemoryLayout.structLayout(JAVA_SHORT, MemoryLayout.paddingLayout(6), ADDRESS);
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle PRCTL_CALL = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("prctl"),
            PRCTL,
            Linker.Option.firstVariadicArg(1),
            Linker.Option.captureCallState("errno"));
    private static final MethodHandle EXECVE_CALL = LINKER.downcallHandle(
            LINKER.defaultLookup().findOrThrow("execve"), EXECVE, Linker.Option.captureCallState("errno"));
    private static final MethodHandle WRITE_CALL =
            LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow("write"), WRITE);
    private static final MethodHandle EXIT_CALL =
            LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow("_exit"), EXIT);

    private LinuxNative() {
        // No instances.
    }

    /**
     * Disables process dumpability after checking the supported ABI.
     * <pre>{@code
     * // requireArchitecture() runs first; state is FFM-only storage.
     * if (prctl(PR_SET_DUMPABLE, 0UL, 0UL, 0UL, 0UL) != 0) {
     *     int saved_errno = errno;
     *     // Java: throw IllegalStateException with saved_errno.
     * }
     * }</pre>
     */
    public static void protectBridge() throws Throwable {
        requireArchitecture();
        try (final var arena = Arena.ofConfined()) {
            final var state = arena.allocate(CALL_STATE);
            if ((int) PRCTL_CALL.invokeExact(state, 4, 0L, 0L, 0L, 0L) != 0) {
                throw new IllegalStateException(
                        "prctl(PR_SET_DUMPABLE, 0) failed, errno=" + state.get(JAVA_INT, ERRNO_OFFSET));
            }
        }
    }

    /**
     * Replaces this JVM, never starts a post-filter subprocess. Does not return on success.
     * All buffers, pointer arrays, diagnostics and native bindings are prepared before restricting
     * the current OS thread. The C control flow is:
     * <pre>{@code
     * // Pseudocode: resolve command[0], build UTF-8 NUL-terminated path,
     * // argv and envp ("key=value" strings), and construct the filter first.
     * struct sock_fprog *program = build_filter();
     * if (prctl(PR_SET_NO_NEW_PRIVS, 1UL, 0UL, 0UL, 0UL) != 0) {
     *     int saved_errno = errno;
     *     report_errno(privileges_error, saved_errno);
     * } else if (prctl(PR_SET_SECCOMP, (unsigned long) SECCOMP_MODE_FILTER,
     *                  (unsigned long) program, 0UL, 0UL) != 0) {
     *     int saved_errno = errno;
     *     report_errno(filter_error, saved_errno);
     * } else {
     *     int result = execve(path, argv, envp);
     *     int saved_errno = errno; // execve returns only on failure
     *     report_errno(exec_error, saved_errno);
     * }
     * _exit(125);
     * }</pre>
     * <p>Java additionally catches foreign-call failures, writes a preallocated diagnostic and
     * invokes {@code _exit(125)} in {@code finally}. There is no C exception equivalent.
     * No TSYNC is requested: a successful execve removes sibling JVM threads; libc's Linux
     * {@code _exit} uses {@code exit_group} to terminate them on failure.
     */
    public static void exec(final List<String> command, final Map<String, String> environment) throws Throwable {
        final var x86 = requireArchitecture();
        final var executable = resolve(command.getFirst(), environment);
        try (final var arena = Arena.ofConfined()) {
            final var path = arena.allocateFrom(executable);
            final var argv = strings(arena, command);
            final var envp = strings(
                    arena,
                    environment.entrySet().stream()
                            .map(entry -> entry.getKey() + '=' + entry.getValue())
                            .toList());
            final var program = filter(arena, x86);
            final var state = arena.allocate(CALL_STATE);
            final var privilegesError =
                    arena.allocateFrom("Sandbox helper: PR_SET_NO_NEW_PRIVS failed, errno=0000000000\n");
            final var filterError = arena.allocateFrom("Sandbox helper: PR_SET_SECCOMP failed, errno=0000000000\n");
            final var execError = arena.allocateFrom("Sandbox helper: execve failed, errno=0000000000\n");
            final var foreignError = arena.allocateFrom("Sandbox helper: foreign call failed during seccomp/execve\n");
            // Everything is allocated and linked before restricting this OS thread. Do not
            // use TSYNC: sibling JVM threads disappear when execve replaces the process.
            try {
                if ((int) PRCTL_CALL.invokeExact(state, 38, 1L, 0L, 0L, 0L) != 0) {
                    reportErrno(privilegesError, state);
                } else if ((int) PRCTL_CALL.invokeExact(state, 22, 2L, program.address(), 0L, 0L) != 0) {
                    reportErrno(filterError, state);
                } else {
                    final int result = (int) EXECVE_CALL.invokeExact(state, path, argv, envp);
                    reportErrno(execError, state);
                }
            } catch (final Throwable failure) {
                // No exception formatting, Java I/O, allocation or unfiltered retry here.
                writeError(foreignError);
            } finally {
                // Never unwind through Java shutdown or leave an unfiltered sibling alive
                // if seccomp/execve failed. _exit is libc exit_group on Linux.
                EXIT_CALL.invokeExact(125);
            }
            throw new IllegalStateException("libc _exit unexpectedly returned");
        }
    }

    /**
     * Formats the captured errno without allocating or calling libc first.
     * Here {@code size} includes the final newline and NUL, and {@code saved_errno} is read from
     * the FFM call-state layout's named errno field, not from a hard-coded native offset.
     * <pre>{@code
     * int error = saved_errno;
     * for (size_t offset = size - 3; offset >= size - 12; --offset) {
     *     message[offset] = (char) ('0' + error % 10);
     *     error /= 10;
     * }
     * write_error(message, size);
     * }</pre>
     */
    private static void reportErrno(final MemorySegment message, final MemorySegment state) throws Throwable {
        var errno = state.get(JAVA_INT, ERRNO_OFFSET);
        // Fixed-width decimal digits in a preallocated native buffer, before any other libc call.
        for (var offset = message.byteSize() - 3; offset >= message.byteSize() - 12; offset--) {
            message.set(JAVA_BYTE, offset, (byte) ('0' + errno % 10));
            errno /= 10;
        }
        writeError(message);
    }

    /**
     * Writes the diagnostic once, excluding its trailing NUL; short writes and failures are ignored.
     * <pre>{@code
     * ssize_t written = write(STDERR_FILENO, message, size - 1);
     * }</pre>
     */
    private static void writeError(final MemorySegment message) throws Throwable {
        // Best-effort diagnostic; even a failed/short write must lead directly to _exit.
        final long written = (long) WRITE_CALL.invokeExact(2, message, message.byteSize() - 1);
    }

    /**
     * Checks Java OS/architecture properties, pointer width and byte order before using the ABI.
     * The corresponding C build assumptions are shown below; the Java check rejects unsupported
     * configurations at runtime instead of using preprocessor errors or static assertions.
     * <pre>{@code
     * #if !defined(__linux__) || !(defined(__x86_64__) || defined(__aarch64__))
     * #error Unsupported OS or architecture
     * #endif
     * #if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
     * #error Unsupported byte order
     * #endif
     * _Static_assert(sizeof(void *) == 8, "64-bit pointers required");
     * // Return true for x86_64, false for aarch64 to select syscall/audit constants.
     * }</pre>
     */
    private static boolean requireArchitecture() {
        final var arch = System.getProperty("os.arch", "");
        if (!"Linux".equals(System.getProperty("os.name"))
                || ADDRESS.byteSize() != 8
                || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN
                || !("amd64".equals(arch) || "x86_64".equals(arch) || "aarch64".equals(arch))) {
            throw new UnsupportedOperationException(
                    "Sandbox helper requires Linux x86_64 or aarch64 (64-bit little endian)");
        }
        return !"aarch64".equals(arch);
    }

    private static String resolve(final String command, final Map<String, String> environment) {
        if (command.contains("/")) {
            return command;
        }
        for (final var directory :
                environment.getOrDefault("PATH", "/usr/bin:/bin").split(":", -1)) {
            final var candidate = Path.of(directory.isEmpty() ? "." : directory).resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalArgumentException("Sandbox executable not found: " + command);
    }

    /**
     * Builds the native argv/envp representation. Allocation and string conversion in this C
     * pseudocode stand for arena-owned, aligned memory and UTF-8 encoding; embedded NULs are
     * rejected in the Java input before encoding. Closing the arena frees all these allocations.
     * <pre>{@code
     * char **pointers = arena_alloc((count + 1) * sizeof(char *), _Alignof(char *));
     * for (size_t i = 0; i < count; ++i) {
     *     pointers[i] = arena_utf8_string(strings[i]); // includes trailing NUL
     * }
     * pointers[count] = NULL;
     * return pointers;
     * }</pre>
     */
    private static MemorySegment strings(final Arena arena, final List<String> strings) {
        final var pointers = arena.allocate((strings.size() + 1L) * ADDRESS.byteSize(), ADDRESS.byteAlignment());
        for (var i = 0; i < strings.size(); i++) {
            if (strings.get(i).contains("\0")) {
                throw new IllegalArgumentException("NUL in execve argument or environment");
            }
            pointers.setAtIndex(ADDRESS, i, arena.allocateFrom(strings.get(i)));
        }
        pointers.setAtIndex(ADDRESS, strings.size(), MemorySegment.NULL);
        return pointers;
    }

    /**
     * Builds classic BPF instructions and serializes them into Linux's native seccomp structures.
     * The Java {@code int[]} entries hold unsigned instruction fields as raw bits. Their C layout
     * and the equivalent final copy are:
     * <pre>{@code
     * // From <linux/filter.h>:
     * // struct sock_filter { __u16 code; __u8 jt; __u8 jf; __u32 k; };
     * // struct sock_fprog { unsigned short len; struct sock_filter *filter; };
     * _Static_assert(sizeof(struct sock_filter) == 8, "filter stride");
     * _Static_assert(offsetof(struct sock_filter, code) == 0, "code offset");
     * _Static_assert(offsetof(struct sock_filter, jt) == 2, "jt offset");
     * _Static_assert(offsetof(struct sock_filter, jf) == 3, "jf offset");
     * _Static_assert(offsetof(struct sock_filter, k) == 4, "k offset");
     * _Static_assert(offsetof(struct sock_fprog, len) == 0, "len offset");
     * _Static_assert(offsetof(struct sock_fprog, filter) == 8, "pointer offset");
     * _Static_assert(sizeof(struct sock_fprog) == 16, "program size");
     *
     * // Pseudocode: code[] is the instruction list built below.
     * struct sock_filter *instructions = arena_alloc(count * 8, 8);
     * for (size_t i = 0; i < count; ++i) {
     *     instructions[i] = (struct sock_filter) {
     *         .code = (uint16_t) code[i][0], .jt = (uint8_t) code[i][1],
     *         .jf = (uint8_t) code[i][2], .k = (uint32_t) code[i][3]
     *     };
     * }
     * struct sock_fprog *program = arena_alloc(sizeof(*program), _Alignof(struct sock_fprog));
     * program->len = (unsigned short) count;
     * program->filter = instructions;
     * return program;
     * }</pre>
     * <p>The FPROG layout explicitly inserts six bytes of padding after the two-byte len so the
     * pointer is eight-byte aligned. The instruction allocation uses eight-byte alignment (more
     * than the structure's required four); its eight-byte stride has no padding between fields.
     * Setting ADDRESS stores the native pointer, not the Java segment object.
     *
     * <p>The instruction list uses {@code BPF_LD | BPF_W | BPF_ABS} (0x20),
     * {@code BPF_JMP | BPF_JEQ | BPF_K} (0x15), {@code BPF_JMP | BPF_JSET | BPF_K} (0x45),
     * {@code BPF_ALU | BPF_AND | BPF_K} (0x54) and {@code BPF_RET | BPF_K} (0x06).
     * Absolute offsets 0, 4, 16 and 24 address {@code seccomp_data.nr}, {@code arch}, and the
     * low words of {@code args[0]} and {@code args[1]}. Audit values are {@code AUDIT_ARCH_X86_64}
     * (0xc000003e) or {@code AUDIT_ARCH_AARCH64} (0xc00000b7); bit 0x40000000 identifies x32
     * syscall numbers. Return values are {@code SECCOMP_RET_KILL_PROCESS} (0x80000000),
     * {@code SECCOMP_RET_ERRNO} (0x00050000) ORed with errno, and
     * {@code SECCOMP_RET_ALLOW} (0x7fff0000); 1 is EPERM and 38 is ENOSYS on both supported ABIs.
     */
    private static MemorySegment filter(final Arena arena, final boolean x86) {
        final var code = new ArrayList<int[]>();
        instruction(code, 0x20, 0, 0, 4); // seccomp_data.arch
        instruction(code, 0x15, 1, 0, x86 ? 0xc000003e : 0xc00000b7);
        instruction(code, 0x06, 0, 0, 0x80000000); // KILL_PROCESS: incompatible ABI
        instruction(code, 0x20, 0, 0, 0); // seccomp_data.nr
        if (x86) {
            instruction(code, 0x45, 0, 1, 0x40000000); // x32 ABI
            instruction(code, 0x06, 0, 0, 0x80000000);
        }
        // mount, umount2, pivot_root, chroot, unshare, setns,
        // ptrace, process_vm_readv/writev, bpf, perf_event_open, keyctl/add_key/request_key,
        // open_by_handle_at/name_to_handle_at, userfaultfd, kexec_load, module calls,
        // reboot, swapon/swapoff, fanotify_init, kexec_file_load.
        final var denied = x86
                ? new int[] {
                    165, 166, 155, 161, 272, 308, 101, 310, 311, 321, 298, 250, 248, 249, 304, 303, 323, 246, 175, 313,
                    176, 169, 167, 168, 300, 320
                }
                : new int[] {
                    40, 39, 41, 51, 97, 268, 117, 270, 271, 280, 241, 219, 217, 218, 265, 264, 282, 104, 105, 273, 106,
                    142, 224, 225, 262, 294
                };
        for (final var syscall : denied) {
            block(code, syscall, 1);
        }
        // Common modern syscall numbers: io_uring (all operations), new mount API,
        // pidfd_getfd, mount_setattr and open_tree_attr.
        for (final var syscall : new int[] {425, 426, 427, 428, 429, 430, 431, 432, 433, 438, 442, 467}) {
            block(code, syscall, 1);
        }
        block(code, 435, 38); // clone3 => ENOSYS so libc can fall back to clone
        instruction(code, 0x15, 0, 3, x86 ? 56 : 220); // clone
        instruction(code, 0x20, 0, 0, 16); // args[0], low word
        instruction(code, 0x45, 0, 1, 0x7e020000); // all CLONE_NEW* namespaces
        instruction(code, 0x06, 0, 0, 0x00050001); // ERRNO | EPERM
        instruction(code, 0x20, 0, 0, 0);
        // JDK channel initialization and subprocess plumbing require anonymous stream
        // pairs. These cannot connect to controller paths. Deny every other family/type,
        // especially Unix DGRAM/SEQPACKET; direct AF_UNIX socket creation stays forbidden.
        instruction(code, 0x15, 0, 8, x86 ? 53 : 199); // socketpair
        instruction(code, 0x20, 0, 0, 16); // family
        instruction(code, 0x15, 1, 0, 1); // AF_UNIX
        instruction(code, 0x06, 0, 0, 0x00050001);
        instruction(code, 0x20, 0, 0, 24); // type
        instruction(code, 0x54, 0, 0, 0xf); // ignore SOCK_CLOEXEC and SOCK_NONBLOCK
        instruction(code, 0x15, 1, 0, 1); // SOCK_STREAM only
        instruction(code, 0x06, 0, 0, 0x00050001);
        instruction(code, 0x06, 0, 0, 0x7fff0000);
        instruction(code, 0x15, 0, 4, x86 ? 41 : 198); // socket
        instruction(code, 0x20, 0, 0, 16);
        instruction(code, 0x15, 2, 0, 2); // AF_INET
        instruction(code, 0x15, 1, 0, 10); // AF_INET6
        instruction(code, 0x06, 0, 0, 0x00050001); // includes AF_UNIX
        instruction(code, 0x06, 0, 0, 0x7fff0000); // ALLOW
        final var instructions = arena.allocate(code.size() * 8L, 8);
        for (var i = 0; i < code.size(); i++) {
            final var op = code.get(i);
            final var offset = i * 8L;
            instructions.set(JAVA_SHORT, offset, (short) op[0]);
            instructions.set(JAVA_BYTE, offset + 2, (byte) op[1]);
            instructions.set(JAVA_BYTE, offset + 3, (byte) op[2]);
            instructions.set(JAVA_INT, offset + 4, op[3]);
        }
        final var program = arena.allocate(FPROG);
        program.set(JAVA_SHORT, 0, (short) code.size());
        program.set(ADDRESS, 8, instructions);
        return program;
    }

    /**
     * Appends a syscall equality test followed by an errno return; a mismatch skips the return.
     * The C equivalent uses an instruction array with a running index:
     * <pre>{@code
     * code[count++] = (struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, syscall, 0, 1);
     * code[count++] = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | error);
     * }</pre>
     */
    private static void block(final List<int[]> code, final int syscall, final int errno) {
        instruction(code, 0x15, 0, 1, syscall);
        instruction(code, 0x06, 0, 0, 0x00050000 | errno);
    }

    /**
     * Appends the logical fields of one classic BPF instruction; serialization happens in filter.
     * In C, appending to an already allocated instruction array would be:
     * <pre>{@code
     * code[count++] = (struct sock_filter) {
     *     .code = (uint16_t) op, .jt = (uint8_t) yes,
     *     .jf = (uint8_t) no, .k = (uint32_t) value
     * };
     * }</pre>
     */
    private static void instruction(
            final List<int[]> code, final int op, final int yes, final int no, final int value) {
        code.add(new int[] {op, yes, no, value});
    }
}
