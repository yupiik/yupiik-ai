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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.wiring.HackStdIo;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
@ResourceLock("java.util.logging")
class AcpStdioTest {
    @TempDir
    Path temporary;

    @Test
    void acpThenNonAcpContainerDoesNotSuppressTerminalTakeover() {
        final var originalOut = System.out;
        final var originalErr = System.err;
        try {
            try (final var container = container(Optional.of(new Args(List.of("acp"))), true)
                            .start();
                    final var configuration = container.lookup(Configuration.class);
                    final var stdio = container.lookup(AcpStdio.class);
                    final var hack = container.lookup(HackStdIo.class)) {
                // Configuration is lazy: invoke it to emit the real registration event.
                assertEquals(Optional.of("true"), configuration.instance().get("yca.logging.auto"));
                assertSame(originalOut, stdio.instance().protocolOutput());
                assertSame(originalErr, System.out, "ACP diagnostics must use stderr");
                assertSame(originalErr, System.err);
                assertFalse(hack.instance().installed(), "ACP must not install terminal wrappers");
                // Repeated configuration events must not capture redirected stdout as protocol output.
                container.emit(new ConfigurationRegistration(configuration.instance(), ignored -> {}));
                assertSame(originalOut, stdio.instance().protocolOutput());
            }
            assertSame(originalOut, System.out, "ACP container shutdown must restore stdout");
            assertSame(originalErr, System.err);

            try (final var container = container(Optional.of(new Args(List.of("run"))), false)
                            .start();
                    final var configuration = container.lookup(Configuration.class);
                    final var hack = container.lookup(HackStdIo.class)) {
                assertEquals(Optional.of("true"), configuration.instance().get("yca.logging.auto"));
                assertTrue(hack.instance().installed(), "a later non-ACP container must install normally");
                assertSame(originalOut, hack.instance().out());
                assertSame(originalErr, hack.instance().err());
            }
            assertSame(originalOut, System.out, "non-ACP shutdown must also restore stdout");
            assertSame(originalErr, System.err);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @ParameterizedTest
    @MethodSource("nonAcpArguments")
    void onlyTheAcpCommandSkipsTakeover(final Optional<Args> args) {
        final var originalOut = System.out;
        final var originalErr = System.err;
        try {
            try (final var container = container(args, false).start();
                    final var configuration = container.lookup(Configuration.class);
                    final var hack = container.lookup(HackStdIo.class);
                    final var stdio = container.lookup(AcpStdio.class)) {
                assertEquals(Optional.of("true"), configuration.instance().get("yca.logging.auto"));
                assertTrue(hack.instance().installed());
                assertThrows(IllegalStateException.class, () -> stdio.instance().protocolOutput());
            }
            assertSame(originalOut, System.out);
            assertSame(originalErr, System.err);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static Stream<Optional<Args>> nonAcpArguments() {
        return Stream.of(
                Optional.empty(),
                Optional.of(new Args(List.of())),
                Optional.of(new Args(List.of("cli", "acp"))),
                Optional.of(new Args(List.of("acp-other"))));
    }

    @Test
    void restoreYieldsToAReplacementStreamAndIsIdempotent() {
        final var originalOut = System.out;
        final var originalErr = System.err;
        final var stdio = new AcpStdio(Optional.of(new Args(List.of("acp"))));
        try (final var replacement = new PrintStream(new ByteArrayOutputStream())) {
            stdio.reserve(new ConfigurationRegistration(Configuration.of(Map.of()), ignored -> {}));
            assertSame(originalOut, stdio.protocolOutput());
            System.setOut(replacement);
            stdio.restore(new Stop());
            assertSame(replacement, System.out, "shutdown must not overwrite another stream owner");
            assertSame(originalErr, System.err);
            assertThrows(IllegalStateException.class, stdio::protocolOutput);
            stdio.restore(new Stop());
            assertSame(replacement, System.out);
        } finally {
            stdio.restore(new Stop());
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private ConfiguringContainer container(final Optional<Args> args, final boolean acp) {
        final var originalOut = System.out;
        final var originalErr = System.err;
        final var configurationProbe = new FusionListener<ConfigurationRegistration>() {
            @Override
            public Type eventType() {
                return ConfigurationRegistration.class;
            }

            @Override
            public int priority() {
                return Integer.MIN_VALUE + 1;
            }

            @Override
            public void onEvent(final RuntimeContainer container, final ConfigurationRegistration event) {
                assertSame(
                        acp ? originalErr : originalOut,
                        System.out,
                        "protocol stdout must be reserved before configuration consumers run");
                event.addSource()
                        .accept(new MapConfigSource(Map.of(
                                "yca.logging.auto",
                                "true",
                                "yca.storageHome",
                                temporary.toString(),
                                "yca.storage.home",
                                temporary.toString(),
                                "yca.workingDir",
                                temporary.toString())));
            }
        };
        final var shutdownProbe = new FusionListener<Stop>() {
            @Override
            public Type eventType() {
                return Stop.class;
            }

            @Override
            public int priority() {
                return Integer.MAX_VALUE - 1;
            }

            @Override
            public void onEvent(final RuntimeContainer container, final Stop event) {
                if (acp) {
                    assertSame(originalErr, System.out, "shutdown diagnostics must still avoid protocol stdout");
                }
            }
        };
        final var configuring = ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class,
                        ArgsConfigSource.class,
                        () -> new ArgsConfigSource(args.map(Args::args).orElseGet(List::of))))
                .register(configurationProbe)
                .register(shutdownProbe);
        args.ifPresent(value ->
                configuring.register(new ProvidedInstanceBean<>(DefaultScoped.class, Args.class, () -> value)));
        return configuring;
    }
}
