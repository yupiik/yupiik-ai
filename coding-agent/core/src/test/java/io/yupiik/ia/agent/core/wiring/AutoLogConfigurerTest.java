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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.test.TestJvm;
import io.yupiik.logging.jul.formatter.InlineFormatter;
import io.yupiik.logging.jul.handler.StandardHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Unit-level coverage of the default inline JUL configuration. The container wiring
 * itself (Start/Stop events, yca.logging.auto precedence, single-install) is covered
 * by the headless TUI tests; here the code paths are exercised directly on the
 * JVM-wide root logger, which is why all tests share the {@code io.yupiik-jul-handler}
 * lock with the TUI suite (the TUI detaches/reattaches the very same handler).
 */
@ResourceLock("io.yupiik-jul-handler")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AutoLogConfigurerTest {
    @Test
    void jdkManagerFallback(@TempDir final Path tmp) throws Exception {
        TestJvm.run(tmp, JdkManagerPeer.class, Map.of("java.util.logging.manager", LogManager.class.getName()));
    }

    public static final class JdkManagerPeer {
        public static void main(final String[] args) {
            try (final var container = ConfiguringContainer.of()
                            .register(new ConfigurationTestModule())
                            .start();
                    final var instance = container.lookup(CodingAgentConfiguration.class)) {
                final var configuration = instance.instance();
                final var tests = new AutoLogConfigurerTest();
                tests.installsInlineRootHandlerByDefault(configuration);
                tests.stopRestoresTheRootLoggerHandlers(configuration);
                tests.secondStartMustNotStackASecondInlineHandler(configuration);
                tests.replacesTheJdkConsoleHandlerSoRecordsArePrintedOnce(configuration);
            }
        }
    }

    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class Defaults {
        @Test
        void leavesYupiikManagerHandlersAlone(@Fusion final CodingAgentConfiguration configuration) {
            assertEquals(
                    "io.yupiik.logging.jul.YupiikLogManager",
                    LogManager.getLogManager().getClass().getName());
            final var root = Logger.getLogger("");
            final var handlers = root.getHandlers();
            final var configurer = new AutoLogConfigurer(configuration);
            try {
                configurer.onStart(new Start());
                assertNull(configurer.installedInlineHandler());
                assertEquals(handlers.length, root.getHandlers().length);
                for (final var handler : handlers) {
                    assertTrue(contains(root.getHandlers(), handler));
                }
            } finally {
                configurer.onStop(new Stop());
            }
        }
    }

    private void installsInlineRootHandlerByDefault(final CodingAgentConfiguration configuration) {
        final var configurer = new AutoLogConfigurer(configuration);
        try {
            configurer.onStart(new Start());
            final var inline = configurer.installedInlineHandler();
            assertNotNull(inline, "the inline handler must be registered after the Start event");
            assertInstanceOf(StandardHandler.class, inline, "it must be the yupiik standard handler");
            assertInstanceOf(InlineFormatter.class, inline.getFormatter(), "it must use the inline formatter");
            assertEquals(Level.INFO, inline.getLevel(), "captured records must start at INFO");
            assertEquals(Level.INFO, Logger.getLogger("").getLevel(), "the root logger level must be INFO");
            assertTrue(
                    contains(Logger.getLogger("").getHandlers(), inline),
                    "the inline handler must be attached to the root logger");
        } finally {
            configurer.onStop(new Stop());
        }
        assertNull(configurer.installedInlineHandler(), "the Stop listener must clear the handler");
    }

    private void stopRestoresTheRootLoggerHandlers(final CodingAgentConfiguration configuration) {
        final var configurer = new AutoLogConfigurer(configuration);
        configurer.onStart(new Start());
        final var inline = configurer.installedInlineHandler();
        assertNotNull(inline);
        configurer.onStop(new Stop());
        assertFalse(
                contains(Logger.getLogger("").getHandlers(), inline),
                "the Stop listener must detach the inline handler from the root logger");
        assertNull(configurer.installedInlineHandler());
    }

    @Nested
    @FusionSupport(modules = DisabledModule.class)
    class Disabled {
        @Test
        void leavesTheRootLoggerAloneWhenAutoIsDisabled(@Fusion final CodingAgentConfiguration configuration) {
            final var configurer = new AutoLogConfigurer(configuration);
            try {
                configurer.onStart(new Start());
                assertNull(configurer.installedInlineHandler(), "auto=false must skip the installation");
                assertFalse(
                        containsStandardHandler(Logger.getLogger("").getHandlers()),
                        "no yupiik handler may be attached when auto is disabled");
            } finally {
                configurer.onStop(new Stop());
            }
        }
    }

    @Test
    void leavesTheRootLoggerAloneWhenAConfigFileIsSet(@TempDir final Path tmp) throws Exception {
        final var logging = tmp.resolve("logging.properties");
        Files.writeString(logging, "handlers=java.util.logging.ConsoleHandler\n.level=WARNING\n");
        TestJvm.run(
                tmp,
                ConfigFilePeer.class,
                Map.of("java.util.logging.config.file", logging.toAbsolutePath().toString()));
    }

    public static final class ConfigFilePeer {
        public static void main(final String[] args) {
            try (final var container = ConfiguringContainer.of()
                            .register(new ConfigurationTestModule())
                            .start();
                    final var instance = container.lookup(CodingAgentConfiguration.class)) {
                final var configuration = instance.instance();
                final var root = Logger.getLogger("");
                final var handlers = root.getHandlers();
                assertEquals(Level.WARNING, root.getLevel(), "JUL must load the explicit configuration at startup");
                assertEquals(1, handlers.length);
                assertInstanceOf(ConsoleHandler.class, handlers[0]);
                final var configurer = new AutoLogConfigurer(configuration);
                try {
                    configurer.onStart(new Start());
                    assertNull(
                            configurer.installedInlineHandler(),
                            "an explicit java.util.logging.config.file must preserve the supplied configuration");
                    assertFalse(containsStandardHandler(root.getHandlers()));
                    assertEquals(Level.WARNING, root.getLevel());
                    assertEquals(1, root.getHandlers().length);
                    assertTrue(contains(root.getHandlers(), handlers[0]), "the configured handler must be preserved");
                } finally {
                    configurer.onStop(new Stop());
                }
            }
        }
    }

    private void secondStartMustNotStackASecondInlineHandler(final CodingAgentConfiguration configuration) {
        // Regression for the native duplicate-logs issue: the GraalVM build can resolve this
        // @ApplicationScoped bean twice and run onStart twice, which used to stack two inline
        // StandardHandlers and print every JUL record twice with identical timestamps.
        final var configurer = new AutoLogConfigurer(configuration);
        final var other = new AutoLogConfigurer(configuration);
        try {
            configurer.onStart(new Start());
            final var first = configurer.installedInlineHandler();
            assertNotNull(first);
            other.onStart(new Start()); // second bean instance, second Start event
            assertEquals(
                    1,
                    countStandardHandlers(Logger.getLogger("").getHandlers()),
                    "a second onStart must not stack a second inline handler");
            assertNotNull(other.installedInlineHandler(), "the second instance still owns the surviving handler");
        } finally {
            configurer.onStop(new Stop());
            other.onStop(new Stop());
        }
    }

    private void replacesTheJdkConsoleHandlerSoRecordsArePrintedOnce(final CodingAgentConfiguration configuration) {
        final var root = Logger.getLogger("");
        final var console = new ConsoleHandler();
        root.addHandler(console);
        final var configurer = new AutoLogConfigurer(configuration);
        try {
            configurer.onStart(new Start());
            assertFalse(
                    contains(root.getHandlers(), console),
                    "the JDK default ConsoleHandler must be removed when the inline handler is installed");
            assertTrue(
                    containsStandardHandler(root.getHandlers()),
                    "the yupiik inline handler must replace the JDK ConsoleHandler");
        } finally {
            configurer.onStop(new Stop());
            root.removeHandler(console);
        }
    }

    private static boolean contains(final Handler[] handlers, final Handler expected) {
        for (final var handler : handlers) {
            if (handler == expected) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsStandardHandler(final Handler[] handlers) {
        for (final var handler : handlers) {
            if (handler instanceof StandardHandler) {
                return true;
            }
        }
        return false;
    }

    private static long countStandardHandlers(final Handler[] handlers) {
        var count = 0;
        for (final var handler : handlers) {
            if (handler instanceof StandardHandler) {
                count++;
            }
        }
        return count;
    }

    public static class DisabledModule extends ConfigurationTestModule {
        public DisabledModule() {
            super(Map.of("yca.logging.auto", "false"));
        }
    }
}
