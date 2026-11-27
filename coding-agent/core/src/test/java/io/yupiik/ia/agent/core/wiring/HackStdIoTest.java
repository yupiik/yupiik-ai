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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.ia.agent.core.cli.prompt.PromptOutput;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

// Resource locks do not exclude unrelated tests whose JUL handlers write to the captured streams.
@Isolated("Captures JVM-wide streams, including output from unrelated JUL writers")
@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock("java.util.logging")
class HackStdIoTest {
    private PrintStream originalOut;
    private PrintStream originalErr;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void passesThroughToThePristineStreamsWhileNoTargetIsRegistered() {
        final var hack = new HackStdIo();
        try {
            hack.install();
            System.out.print("hello\n");
            System.err.println("diag");
            assertEquals("hello\n", out.toString(StandardCharsets.UTF_8), "raw writes must reach the pristine stdout");
            assertEquals(
                    "diag" + System.lineSeparator(),
                    err.toString(StandardCharsets.UTF_8),
                    "raw writes must reach the pristine stderr");
        } finally {
            hack.uninstall();
        }
    }

    @Test
    void routesWritesToTheTargetPerCompleteLine() {
        final var hack = new HackStdIo();
        final var printed = new ArrayList<String>();
        final var errPrinted = new ArrayList<String>();
        final var target = new PromptOutput() {
            @Override
            public boolean active() {
                return true;
            }

            @Override
            public void print(final String text) {
                printed.add(text);
            }

            @Override
            public void printErr(final String text) {
                errPrinted.add(text);
            }
        };
        try {
            hack.install();
            hack.streamTarget(target);
            // The pristine buffers are JVM-global under a parallel fork: a background log from a test that does not
            // share our @ResourceLock could have landed in the moment between System capture and the routed section
            // below. Reset them so the assertions only constrain what THIS test routed, never unrelated output.
            out.reset();
            err.reset();
            System.out.print("a\nb\n"); // byte[] path: split on each newline
            System.out.print("tail"); // unterminated: forwarded as is
            System.out.flush();
            System.out.write('z'); // single byte: forwarded as text
            System.out.write('\n');
            System.err.print("err"); // stderr routes to printErr
            assertEquals(List.of("a\n", "b\n", "tail", "z", "\n"), printed, "stdout lines must reach the sink");
            assertEquals(List.of("err"), errPrinted, "stderr must reach the sink's err branch");
            assertEquals("", out.toString(StandardCharsets.UTF_8), "routed writes must not reach the pristine stdout");
            assertEquals("", err.toString(StandardCharsets.UTF_8), "routed writes must not reach the pristine stderr");
        } finally {
            hack.streamTarget(null);
            hack.uninstall();
        }
    }

    @Test
    void outAndErrResolveThePristineStreamWhileInstalledAndTheLiveOneOtherwise() {
        final var hack = new HackStdIo();
        try {
            hack.install();
            final var capturedOut = System.out;
            final var capturedErr = System.err;
            assertInstanceOf(HackStdIo.HijackedPrintStream.class, capturedOut, "System.out must be the wrapper");
            assertSame(
                    ((HackStdIo.HijackedPrintStream) capturedOut).pristine(),
                    hack.out(),
                    "the pristine stream must be handed out while the wrapper still owns System.out");
            assertSame(
                    ((HackStdIo.HijackedPrintStream) capturedErr).pristine(),
                    hack.err(),
                    "the pristine stream must be handed out while the wrapper still owns System.err");
            final var swapped = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            System.setOut(swapped);
            assertSame(swapped, hack.out(), "the live stream must win as soon as the wrapper is no longer System.out");
        } finally {
            hack.uninstall();
        }
    }

    @Test
    void uninstallRestoresTheOriginalStreamsAndCanInstallAgain() {
        final var capturedOut = System.out;
        final var capturedErr = System.err;
        final var hack = new HackStdIo();
        hack.install();
        assertNotSame(capturedOut, System.out, "installing must swap System.out");
        assertNotSame(capturedErr, System.err, "installing must swap System.err");
        hack.uninstall();
        assertSame(capturedOut, System.out, "uninstalling must restore System.out");
        assertSame(capturedErr, System.err, "uninstalling must restore System.err");
        assertFalse(hack.installed(), "the control must report uninstalled");
        hack.install();
        assertTrue(hack.installed(), "a fresh takeover must be possible after uninstall");
        hack.uninstall();
        assertSame(capturedOut, System.out, "the second uninstall must restore System.out again");
        assertSame(capturedErr, System.err, "the second uninstall must restore System.err again");
    }

    @Test
    void installIsIdempotentAndYieldsToAnotherOwner() {
        final var first = new HackStdIo();
        final var second = new HackStdIo();
        first.install();
        final var wrapper = System.out;
        first.install();
        assertSame(wrapper, System.out, "installing twice must keep a single wrapper");
        assertInstanceOf(HackStdIo.HijackedPrintStream.class, wrapper, "System.out must still be the first wrapper");
        second.install();
        assertSame(wrapper, System.out, "a control must never take over streams another runtime already hijacked");
        try {
            first.uninstall();
            second.install();
            assertInstanceOf(
                    HackStdIo.HijackedPrintStream.class,
                    System.out,
                    "the second control must be able to install once the first released the streams");
        } finally {
            second.uninstall();
        }
    }

    @Test
    void controlCtorDoesNotInstall() {
        final var capturedOut = System.out;
        final var control = new StdIoControl(Optional.empty());
        assertSame(capturedOut, System.out, "the control ctor must not take the streams over");
        assertFalse(control.hackStdIo().installed(), "the produced control must start uninstalled");
    }

    @Test
    void controlInstallsWhenAutoIsDefaultedOrEnabled() {
        final var control = new StdIoControl(Optional.empty());
        try {
            control.install(registration(Map.of()));
            assertInstanceOf(
                    HackStdIo.HijackedPrintStream.class,
                    System.out,
                    "an unset yca.logging.auto must default to a takeover");
            final var wrapper = System.out;
            assertTrue(control.hackStdIo().installed(), "the produced control must be the installed one");
            control.install(registration(Map.of("yca.logging.auto", "true")));
            assertSame(wrapper, System.out, "an explicit true (or a second event) must keep the same single wrapper");
        } finally {
            control.uninstall(new Stop());
        }
    }

    @Test
    void controlLeavesTheStreamsAloneWhenAutoIsDisabled() {
        final var capturedOut = System.out;
        final var control = new StdIoControl(Optional.empty());
        control.install(registration(Map.of("yca.logging.auto", "false")));
        assertSame(capturedOut, System.out, "yca.logging.auto=false must never install");
        assertFalse(
                System.out instanceof HackStdIo.HijackedPrintStream,
                "no wrapper may be created for a disabled takeover");
        assertFalse(control.hackStdIo().installed(), "the control must stay uninstalled");
    }

    @Test
    void controlLeavesProtocolStreamsAloneForAcp() {
        final var capturedOut = System.out;
        final var capturedErr = System.err;
        final var control = new StdIoControl(Optional.of(new Args(List.of("acp"))));
        try {
            control.install(registration(Map.of("yca.logging.auto", "true")));
            assertFalse(control.hackStdIo().installed(), "ACP must skip the terminal takeover even with auto logging");
            assertSame(capturedOut, System.out);
            assertSame(capturedErr, System.err);
        } finally {
            control.uninstall(new Stop());
        }
        assertSame(capturedOut, System.out);
        assertSame(capturedErr, System.err);
    }

    @Test
    void controlStopListenerRestoresTheStreams() {
        final var capturedOut = System.out;
        final var capturedErr = System.err;
        final var control = new StdIoControl(Optional.empty());
        control.install(registration(Map.of()));
        assertInstanceOf(HackStdIo.HijackedPrintStream.class, System.out, "the takeover must be active");
        control.uninstall(new Stop());
        assertSame(capturedOut, System.out, "the Stop listener must restore System.out");
        assertSame(capturedErr, System.err, "the Stop listener must restore System.err");
        assertFalse(
                System.out instanceof HackStdIo.HijackedPrintStream,
                "the wrapper must be gone after the Stop listener ran");
        assertFalse(control.hackStdIo().installed(), "the control must report uninstalled");
    }

    private static ConfigurationRegistration registration(final Map<String, String> values) {
        return new ConfigurationRegistration(Configuration.of(values), ignored -> {
            // no extra source to add in a unit test
        });
    }
}
