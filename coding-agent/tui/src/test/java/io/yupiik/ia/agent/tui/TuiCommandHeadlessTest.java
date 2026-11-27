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
package io.yupiik.ia.agent.tui;

import static java.time.Duration.ofMillis;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.tamboui.style.Color;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.PasteEvent;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.agent.AgentLoop;
import io.yupiik.ia.agent.core.agent.SubagentRegistry;
import io.yupiik.ia.agent.core.cli.AskUserAnswersFormatter;
import io.yupiik.ia.agent.core.cli.FormatDatePromptHelper;
import io.yupiik.ia.agent.core.cli.RunCommand;
import io.yupiik.ia.agent.core.llm.InBandToolCallExtractor;
import io.yupiik.ia.agent.core.llm.MarkupToolCallScanner;
import io.yupiik.ia.agent.core.question.Question;
import io.yupiik.ia.agent.core.session.SessionEntry;
import io.yupiik.ia.agent.core.shell.SessionRuntimeFactory;
import io.yupiik.ia.agent.core.shell.ShellOutcome;
import io.yupiik.ia.agent.core.shell.ShellRegistry;
import io.yupiik.ia.agent.core.shell.SpeakerLabels;
import io.yupiik.ia.agent.core.shell.TurnRunner;
import io.yupiik.ia.agent.core.shell.btw.BtwAnswerer;
import io.yupiik.ia.agent.core.shell.slash.SlashCommand;
import io.yupiik.ia.agent.core.shell.slash.SlashRegistry;
import io.yupiik.ia.agent.core.wiring.AutoLogConfigurer;
import io.yupiik.ia.agent.test.TestJsonMapper;
import io.yupiik.ia.agent.tui.prompt.SlashCommandCompleter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Boots the real fusion bean graph through autodiscovery (core + json + tui modules)
 * with a programmatically registered MapConfigSource pointing at {@link MockLlmServer}
 * and drives the TUI headlessly through the production {@link AgentLoop}.
 *
 * <p>The container and the mock server are shared across the tests of this class and
 * created in {@code @BeforeAll} so the once-per-class wiring cost is never attributed
 * to a single test.
 */
// TuiCommand redirects the JVM-wide "io.yupiik" java.util.logging logger to the UI by installing
// its own handler, and it SKIPS that installation when the logger already has a handler or already
// has useParentHandlers=false (TuiCommand.redirectLoggerToUi). Two TuiCommand instances booting
// concurrently in the same JVM would therefore make the loser silently drop log redirection, which
// breaks the assertions of both TUI test classes. The suite runs test classes concurrently (see
// the surefire configurationParameters in the root pom), so both classes take the same lock and
// never boot a TuiCommand at the same time.
@ResourceLock("io.yupiik-jul-handler")
@ExtendWith(TestJsonMapper.class)
class TuiCommandHeadlessTest {
    private static final long TIMEOUT_MS = Duration.ofSeconds(5).toMillis();

    @TempDir
    static Path temp;

    private static MockLlmServer llm;
    private static RuntimeContainer container;
    private static TuiDeps deps;

    private HeadlessBackend backend;
    private long textPollMillis = 50;
    private CountDownLatch quietTextPoll;
    private final AtomicReference<TuiRunner> tuiRef = new AtomicReference<>();
    private Thread uiThread;
    /** Render cycles observed through the headless runner (post-render processor), incl. no-op frames. */
    private final AtomicLong renderCycles = new AtomicLong();

    @BeforeAll
    static void boot() throws IOException {
        llm = new MockLlmServer();
        Files.createDirectories(temp.resolve("home").resolve("sessions"));
        container = bootContainer();
        try (final var depsInstance = container.lookup(TuiDeps.class)) {
            deps = depsInstance.instance();
        }
    }

    private static RuntimeContainer bootContainer() {
        return bootContainer(Map.of());
    }

    private static RuntimeContainer bootContainer(final Map<String, String> extra) {
        final var config = new LinkedHashMap<>(Map.of(
                "yca.storageHome",
                temp.resolve("home").toString(),
                // YcaConfigurationProducer looks the file up in the configuration before falling
                // back to the resolved "yca.storage.home" (via the fusion configuration, not a system
                // property), hence pointing it at a file that does not exist: this container wires
                // the *real* agent graph, and reading the developer's ~/.yupiik/coding-agent/
                // configuration.json would make the test call the configured provider (real tokens)
                // and run real tools. No system property involved, and the listener registering the
                // source below has a higher priority (Integer.MIN_VALUE) so the value is already
                // visible when the producer asks for it.
                "yca.configuration.json",
                temp.resolve("configuration.json").toString(),
                "yca.provider.baseUrl",
                llm.baseUrl(),
                "yca.provider.apiKey",
                "test-key",
                "yca.provider.model",
                "test-model",
                "yca.structuralCodeGraph.enabled",
                "false"));
        config.putAll(extra);
        return ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class,
                        ArgsConfigSource.class,
                        () -> new ArgsConfigSource(List.of("--run-prompt", "wiring"))))
                .register(new FusionListener<ConfigurationRegistration>() {
                    @Override
                    public Type eventType() {
                        return ConfigurationRegistration.class;
                    }

                    @Override
                    public int priority() {
                        // before YcaConfigurationProducer (Integer.MIN_VALUE + 1): it reads
                        // yca.configuration.json while the configuration is being built, so the
                        // source above must already be registered when it does
                        return Integer.MIN_VALUE;
                    }

                    @Override
                    public void onEvent(
                            final RuntimeContainer container, final ConfigurationRegistration registration) {
                        registration.addSource().accept(new MapConfigSource(config));
                    }
                })
                .start();
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (container != null) {
            container.close();
        }
        if (llm != null) {
            llm.close();
        }
    }

    private JsonMapper formatMapper;

    @BeforeEach
    void start(final JsonMapper mapper) {
        backend = new HeadlessBackend();
        formatMapper = mapper;
    }

    @AfterEach
    void stop() throws InterruptedException {
        final var tui = tuiRef.get();
        if (tui != null && tui.isRunning()) {
            tui.quit();
        }
        if (uiThread != null) {
            uiThread.join(TIMEOUT_MS);
        }
    }

    @Test
    void wiresTheFullBeanGraph() {
        try (final var cmd = container.lookup(TuiCommand.class);
                final var run = container.lookup(RunCommand.class);
                final var runtimeFactory = container.lookup(SessionRuntimeFactory.class);
                final var completer = container.lookup(SlashCommandCompleter.class);
                final var factory = container.lookup(TuiRunnerFactory.class)) {
            assertNotNull(cmd.instance());
            assertNotNull(run.instance());
            assertNotNull(runtimeFactory.instance());
            assertNotNull(completer.instance());
            assertNotNull(factory.instance());
        }
    }

    @Test
    void jsonConfigurationIncludesThemeAndExistingTuiSettings() throws Exception {
        final var file = temp.resolve("theme-configuration.json");
        Files.writeString(
                file, "{\"yca\":{\"tui\":{\"theme\":{\"name\":\"light\"},\"accent\":\"#123456\",\"history\":false}}}");
        try (final var isolated = bootContainer(
                        Map.of("yca.configuration.json", file.toString(), "yca.workingDir", temp.toString()));
                final var configuration = isolated.lookup(TuiConfiguration.class)) {
            assertEquals("light", configuration.instance().theme().name());
            assertEquals("#123456", configuration.instance().accent());
            assertFalse(configuration.instance().history());
        }
    }

    @Test
    void themeConfigurationPrecedenceAndSwitchPersistence() throws Exception {
        final var cases = List.of(
                Map.of("name", "light", "persisted", "dark", "legacy", "dark", "expected", "light"),
                Map.of("name", "", "persisted", "light", "legacy", "dark", "expected", "light"),
                Map.of("name", "", "persisted", "invalid", "legacy", "light", "expected", "light"),
                Map.of("name", "", "persisted", "dark", "legacy", "light", "expected", "dark"),
                Map.of("persisted", "missing", "legacy", "light", "expected", "light"),
                Map.of("persisted", "light", "legacy", "dark", "expected", "light"));
        for (final var testCase : cases) {
            final var home = Files.createTempDirectory(temp, "theme-");
            if (!"missing".equals(testCase.get("persisted"))) {
                Files.writeString(home.resolve("theme"), testCase.get("persisted"));
            }
            final var config = new LinkedHashMap<String, String>();
            config.put("yca.storageHome", home.toString());
            if (testCase.containsKey("name")) {
                config.put("yca.tui.theme.name", testCase.get("name"));
            }
            try (final var isolated = bootContainer(config)) {
                final var command = newUiCommand(
                        isolated,
                        lookupDeps(isolated),
                        new TuiCommand.TuiConf("", "", KeyBindingPreset.STANDARD, true, testCase.get("legacy")));
                tuiRef.set(null);
                backend = new HeadlessBackend();
                uiThread = new Thread(command::run, "theme-test");
                uiThread.setDaemon(true);
                uiThread.start();
                awaitTui();
                awaitText("yca coding agent");
                tuiRef.get().dispatch(new PasteEvent("/theme"));
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                awaitText("theme: " + testCase.get("expected"));
                final var next = "light".equals(testCase.get("expected")) ? "dark" : "light";
                tuiRef.get().dispatch(new PasteEvent("/theme " + next));
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                awaitText("theme: " + next);
                assertEquals(next, Files.readString(home.resolve("theme")));
                tuiRef.get().quit();
                uiThread.join(TIMEOUT_MS);
                assertFalse(uiThread.isAlive());
            }
        }
    }

    @Test
    void themeSwitchWaitsForTheRenderThread() throws Exception {
        final var home = Files.createTempDirectory(temp, "queued-theme-");
        Files.writeString(home.resolve("theme"), "dark");
        try (final var isolated = bootContainer(Map.of("yca.storageHome", home.toString()));
                final var commands = isolated.lookup(TuiSlashCommands.class)) {
            final var command = newUiCommand(isolated, lookupDeps(isolated));
            backend = new HeadlessBackend();
            uiThread = new Thread(command::run, "queued-theme-test");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            tuiRef.get().runLater(() -> {
                entered.countDown();
                try {
                    if (!release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("render-thread barrier timed out");
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            try {
                assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
                // Slash actions run off the UI thread. Even while rendering is paused,
                // requesting a theme must return without mutating the theme or its caches.
                commands.instance().commands().stream()
                        .filter(c -> "/theme".equals(c.spec().name()))
                        .findFirst()
                        .orElseThrow()
                        .action()
                        .apply("light", null)
                        .toCompletableFuture()
                        .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                assertEquals("dark", Files.readString(home.resolve("theme")));
            } finally {
                release.countDown();
            }
            awaitText("theme: light");
            assertEquals("light", Files.readString(home.resolve("theme")));
            tuiRef.get().quit();
            uiThread.join(TIMEOUT_MS);
            assertFalse(uiThread.isAlive());
        }
    }

    @Test
    void screenBackgroundFollowsThemeUnlessExplicitlyConfigured() throws Exception {
        // option 1: auto/blank keep the terminal background (real transparency in Terminator),
        // an explicit hex paints a translucent veil blended toward the DETECTED terminal
        // background; stub the detector so the expected colors are deterministic (dark terminal)
        final var previousDetector = ThemeDetector.DETECTOR;
        ThemeDetector.DETECTOR = () -> Optional.of("dark");
        try {
            final var hexVeil = TuiCommand.hexOf(TuiColors.blend("#123456", "#0c0c0c", 0.8));
            final var halfHexVeil = TuiCommand.hexOf(TuiColors.blend("#123456", "#0c0c0c", 0.5));
            final var cases = List.of(
                    Map.of("label", "default", "dark", "", "light", ""),
                    Map.of("label", "auto", "background", " AuTo ", "dark", "", "light", ""),
                    Map.of("label", "hex", "background", "#123456", "dark", hexVeil, "light", hexVeil),
                    Map.of(
                            "label",
                            "hexDimmed",
                            "background",
                            "#123456",
                            "opacity",
                            "0.5",
                            "dark",
                            halfHexVeil,
                            "light",
                            halfHexVeil),
                    Map.of("label", "blank", "background", " ", "dark", "", "light", ""));
            for (final var testCase : cases) {
                final var config = new LinkedHashMap<String, String>();
                config.put(
                        "yca.storageHome",
                        Files.createTempDirectory(temp, "background-").toString());
                if (testCase.containsKey("background")) {
                    config.put("yca.tui.background", testCase.get("background"));
                }
                if (testCase.containsKey("opacity")) {
                    config.put("yca.tui.backgroundOpacity", testCase.get("opacity"));
                }
                try (final var isolated = bootContainer(config)) {
                    final var command = newUiCommand(isolated, lookupDeps(isolated));
                    tuiRef.set(null);
                    backend = new HeadlessBackend();
                    uiThread = new Thread(command::run, "background-test");
                    uiThread.setDaemon(true);
                    uiThread.start();
                    awaitTui();
                    awaitText("yca coding agent");
                    assertScreenBackground(testCase.get("dark"), testCase.get("label") + " dark");

                    tuiRef.get().dispatch(new PasteEvent("/theme light"));
                    tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                    awaitText("theme: light");
                    assertScreenBackground(testCase.get("light"), testCase.get("label") + " light");

                    tuiRef.get().dispatch(new PasteEvent("/theme dark"));
                    tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                    awaitText("theme: dark");
                    assertScreenBackground(testCase.get("dark"), testCase.get("label") + " dark again");
                    tuiRef.get().quit();
                    uiThread.join(TIMEOUT_MS);
                    assertFalse(uiThread.isAlive());
                }
            }
        } finally {
            ThemeDetector.DETECTOR = previousDetector;
        }
    }

    private void assertScreenBackground(final String expected, final String message) throws InterruptedException {
        // An empty transcript cell, away from panels and text, exposes the viewport background.
        final var expectedColor = expected.isEmpty() ? null : Color.hex(expected);
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (!Objects.equals(expectedColor, backend.bg(50, 20).orElse(null)) && System.nanoTime() < deadline) {
            backend.awaitFrame(50);
        }
        assertEquals(" ", backend.symbol(50, 20), message);
        assertEquals(expectedColor, backend.bg(50, 20).orElse(null), message);
    }

    @Test
    void themeNameAutoUsesDetectedPreferenceWithPersistedFallback() throws Exception {
        final var home = Files.createTempDirectory(temp, "theme-auto-");
        final var previous = ThemeDetector.DETECTOR;
        try {
            final var cases = List.of(
                    Map.of("detected", "dark", "persisted", "", "expected", "dark"),
                    Map.of("detected", "dark", "persisted", "light", "expected", "dark"),
                    Map.of("detected", "light", "persisted", "dark", "expected", "light"),
                    Map.of("detected", "", "persisted", "light", "expected", "light"),
                    Map.of("detected", "", "persisted", "dark", "legacy", "light", "expected", "dark"),
                    Map.of("detected", "", "persisted", "missing", "legacy", "light", "expected", "light"));
            for (final var testCase : cases) {
                final var homeDir = Files.createTempDirectory(temp, "theme-auto-case-");
                final var persisted = testCase.get("persisted");
                if (!"missing".equals(persisted)) {
                    Files.writeString(homeDir.resolve("theme"), persisted);
                }
                final var detected = testCase.get("detected");
                ThemeDetector.DETECTOR =
                        () -> detected == null || detected.isBlank() ? Optional.empty() : Optional.of(detected);
                final var config = new LinkedHashMap<String, String>();
                config.put("yca.storageHome", homeDir.toString());
                config.put("yca.tui.theme.name", "auto");
                try (final var isolated = bootContainer(config)) {
                    final var command = newUiCommand(
                            isolated,
                            lookupDeps(isolated),
                            new TuiCommand.TuiConf(
                                    "",
                                    "",
                                    KeyBindingPreset.STANDARD,
                                    true,
                                    testCase.get("legacy") == null ? "dark" : testCase.get("legacy")));
                    tuiRef.set(null);
                    backend = new HeadlessBackend();
                    uiThread = new Thread(command::run, "theme-auto-test");
                    uiThread.setDaemon(true);
                    uiThread.start();
                    awaitTui();
                    awaitText("yca coding agent");
                    tuiRef.get().dispatch(new PasteEvent("/theme"));
                    tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                    awaitText("theme: " + testCase.get("expected"));
                    tuiRef.get().quit();
                    uiThread.join(TIMEOUT_MS);
                    assertFalse(uiThread.isAlive());
                }
            }
        } finally {
            ThemeDetector.DETECTOR = previous;
        }
    }

    @Test
    void answersThroughRealAgentLoop() throws Exception {
        final var command = newUiCommand(container, deps);

        uiThread = new Thread(command::run, "tui-headless-test");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        tuiRef.get().dispatch(new PasteEvent("say hi to the mock"));
        awaitText("say hi to the mock");
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("hello from mock");
        awaitText("ready");

        final var sessions = temp.resolve("home").resolve("sessions");
        assertTrue(Files.isDirectory(sessions), "expected a sessions dir under " + sessions);
        try (final var stream = Files.list(sessions)) {
            assertTrue(stream.findAny().isPresent(), "expected a session file under " + sessions);
        }
        assertTrue(
                llm.requests().stream().anyMatch(r -> r.contains("say hi to the mock")),
                "expected the prompt to reach the mock LLM");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    /**
     * Reproduces a crash in {@code TuiCommand.confirmAnswers}: a single (non-multiple) question with
     * no predefined options has no dropdown, so the only answer is the free-text field. The old logic
     * stored the (null) "selected" option into the answers map, and {@code ConcurrentHashMap} rejects
     * null values with a {@link NullPointerException}. The answer must come from the typed text.
     */
    @Test
    void confirmAnswersStoresFreeTextForNoOptionQuestion() throws Exception {
        final var command = newUiCommand(container, deps);

        final var answers = new ConcurrentHashMap<String, Object>();
        final var answered = new AtomicBoolean();
        final var latch = new CountDownLatch(1);
        final var prompt = newFeedbackPromptReflection(
                command, List.of(new Question("q1", "what?", List.of(), false)), answers, answered, latch);

        // the user types a free-text answer (no options to pick)
        final var own = promptQuestionReflection(prompt, 0);
        setTextArea(own, "my typed answer");

        invokeConfirmAnswers(command, new UiState(deps.thinkTags(), Clock.systemUTC()), prompt);

        assertEquals("my typed answer", answers.get("q1"), "a no-options free-text answer must be stored");
        assertTrue(answered.get(), "confirm marks the prompt as answered");
        assertEquals(0, latch.getCount(), "confirm counts down the wait latch");
    }

    // --- reflection access to the private TuiCommand feedback internals, since the
    // FeedbackPrompt / FeedbackQuestion types and confirmAnswers have no public seam ---

    private static Object newFeedbackPromptReflection(
            final TuiCommand command,
            final List<Question> raw,
            final Map<String, Object> answers,
            final AtomicBoolean answered,
            final CountDownLatch latch)
            throws Exception {
        final var ctor = nestedClass("FeedbackPrompt")
                .getDeclaredConstructor(List.class, CountDownLatch.class, Map.class, AtomicBoolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(raw, latch, answers, answered);
    }

    @SuppressWarnings("unchecked")
    private static Object promptQuestionReflection(final Object prompt, final int index) throws Exception {
        final var questions = nestedClass("FeedbackPrompt").getDeclaredMethod("questions");
        questions.setAccessible(true);
        return ((List<Object>) questions.invoke(prompt)).get(index);
    }

    private static void setTextArea(final Object feedbackQuestion, final String text) throws Exception {
        final var field = nestedClass("FeedbackQuestion").getDeclaredField("ownAnswer");
        field.setAccessible(true);
        final var state = field.get(feedbackQuestion);
        final var setText = state.getClass().getMethod("setText", String.class);
        setText.invoke(state, text);
    }

    private static void invokeConfirmAnswers(final TuiCommand command, final UiState state, final Object prompt)
            throws Exception {
        final var method =
                TuiCommand.class.getDeclaredMethod("confirmAnswers", UiState.class, nestedClass("FeedbackPrompt"));
        method.setAccessible(true);
        method.invoke(command, state, prompt);
    }

    private static Class<?> nestedClass(final String simpleName) throws ClassNotFoundException {
        return Class.forName("io.yupiik.ia.agent.tui.TuiCommand$" + simpleName);
    }

    @Test
    void errorBannerUsesErrorColoredBorder() throws Exception {
        final var command = newUiCommand(container, deps);

        uiThread = new Thread(command::run, "tui-headless-error");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();

        tuiRef.get().dispatch(new PasteEvent("/agent definitely-not-an-agent"));
        awaitText("/agent definitely-not-an-agent");
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("unknown agent: definitely-not-an-agent");
        awaitText("Alt+E for details");

        final var lines = backend.text().split("\n", -1);
        final var errorRow = IntStream.range(0, lines.length)
                .filter(i -> lines[i].contains("Alt+E for details"))
                .findFirst()
                .orElseThrow();
        // the error banner is a full-width rounded box: border rows on either side of the text
        assertEquals("╭", backend.symbol(0, errorRow - 1));
        assertEquals("╰", backend.symbol(0, errorRow + 1));
        final var expected = Color.hex("#f87171");
        assertEquals(expected, backend.fg(0, errorRow - 1).orElseThrow());
        assertEquals(expected, backend.fg(0, errorRow + 1).orElseThrow());
    }

    @Test
    void textWaitsAllowQuietIntervalsBeforePromptChanges() throws Exception {
        textPollMillis = 20;
        final var command = newUiCommand(container, deps);
        uiThread = new Thread(command::run, "tui-headless-delayed-input");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        drainFrames();

        // A static screen emits no frames. A delayed update must still be allowed
        // the full overall deadline, rather than failing after one quiet poll.
        try (final var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            final var paste = scheduler.schedule(
                    afterQuietTextPoll(() -> tuiRef.get().dispatch(new PasteEvent("delayed-input-marker"))),
                    3 * textPollMillis,
                    TimeUnit.MILLISECONDS);
            awaitText("delayed-input-marker");
            paste.get();
            drainFrames();

            final var clear = scheduler.schedule(
                    afterQuietTextPoll(() -> tuiRef.get().dispatch(KeyEvent.ofChar('u', KeyModifiers.CTRL))),
                    3 * textPollMillis,
                    TimeUnit.MILLISECONDS);
            awaitTextGone("delayed-input-marker");
            clear.get();
        }
    }

    @Test
    void idleTicksAreSkippedButHeartbeatAndMutationsStillRepaint() throws Exception {
        // dedicated container with a shortened safety repaint (120ms) so the test observes the
        // idle-skip and the heartbeat while staying well under half a second
        try (final var isolated = bootContainer(Map.of("yca.tui.heartbeatMillis", "120"))) {
            final var isolatedDeps = lookupDeps(isolated);
            assertEquals(120, isolatedDeps.tuiConfiguration().heartbeatMillis());
            final var command = newUiCommand(isolated, isolatedDeps);

            uiThread = new Thread(command::run, "tui-headless-heartbeat");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");
            drainFrames();

            // idle: no render cycle, no terminal write while the screen cannot change
            final var baseCycles = renderCycles.get();
            assertFalse(backend.awaitFrame(50), "an idle tick must not repaint the frame");
            assertEquals(baseCycles, renderCycles.get(), "an idle tick must not run a render cycle");
            // ...until the safety heartbeat forces a render cycle even though the frame is a no-op
            awaitRenderCycle(baseCycles, 200);
            // a no-op heartbeat frame diffed away by TamboUI: still no terminal write
            assertFalse(backend.awaitFrame(30), "a no-op heartbeat must not write to the terminal");

            // a background mutation still paints promptly: the slash error is delivered on the
            // render thread through runLater and drawn by the next tick, not by a busy spinner
            tuiRef.get().dispatch(new PasteEvent("/agent definitely-not-an-agent"));
            awaitText("/agent definitely-not-an-agent");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("unknown agent: definitely-not-an-agent");
            awaitText("Alt+E for details");

            // the error banner is static: once painted, idle silence resumes
            drainFrames();
            assertFalse(backend.awaitFrame(50), "a static screen must not keep repainting");
        }
    }

    /** Awaits the first render cycle above {@code base}, bounded (no fixed sleeping). */
    private void awaitRenderCycle(final long base, final long timeoutMillis) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (renderCycles.get() == base) {
            if (System.nanoTime() > deadline) {
                fail("no render cycle within " + timeoutMillis + "ms (heartbeat did not fire)");
            }
            backend.awaitFrame(5);
        }
    }

    /** Consumes any in-flight repaint so timing-sensitive assertions start from a quiet frame. */
    private void drainFrames() throws InterruptedException {
        while (backend.awaitFrame(20)) {
            // bounded: stops as soon as a 20ms window contains no frame
        }
    }

    @Test
    void typingBracketedPromptPreservesEveryCharacter() throws Exception {
        // regression: "[mermaid]" typed cold must land in the prompt verbatim. The old
        // interface-level SgrTailGuard ate the leading "[m" as a mouse-tail shape; the mouse
        // junk is now fixed at the byte level (MouseReportCoalescingBackend), so the prompt
        // is a plain insert and bracket text always survives.
        final var command = newUiCommand(container, deps);
        uiThread = new Thread(command::run, "tui-headless-bracket-typing");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        for (final var c : "[mermaid]".toCharArray()) {
            tuiRef.get().dispatch(KeyEvent.ofChar(c));
        }
        awaitText("[mermaid]");

        tuiRef.get().quit();
    }

    private static TuiDeps lookupDeps(final RuntimeContainer container) {
        try (final var depsInstance = container.lookup(TuiDeps.class)) {
            return depsInstance.instance();
        }
    }

    private TuiCommand newUiCommand(final RuntimeContainer container, final TuiDeps d) {
        return newUiCommand(container, d, new TuiCommand.TuiConf("", "", KeyBindingPreset.STANDARD, true, "dark"));
    }

    private TuiCommand newUiCommand(final RuntimeContainer container, final TuiDeps d, final TuiCommand.TuiConf conf) {
        return newUiCommand(container, d, conf, null);
    }

    private TuiCommand newUiCommand(
            final RuntimeContainer container,
            final TuiDeps d,
            final TuiCommand.TuiConf conf,
            final SlashRegistry recordingRegistry) {
        // share the container-managed instances with the SlashRegistry so "/tabs"
        // dispatched through it mutates the same TabManager the TUI renders from
        try (final var tabManagerInstance = container.lookup(TabManager.class);
                final var slashCommandsInstance = container.lookup(TuiSlashCommands.class);
                final var shellRegistry = container.lookup(ShellRegistry.class);
                final var slashRegistry = container.lookup(SlashRegistry.class);
                final var turnRunner = container.lookup(TurnRunner.class);
                final var runtimeFactory = container.lookup(SessionRuntimeFactory.class);
                final var scheduler = container.lookup(ScheduledExecutorService.class);
                final var autoLogConfigurer = container.lookup(AutoLogConfigurer.class)) {
            final var tabManager = tabManagerInstance.instance();
            final var tuiSlashCommands = slashCommandsInstance.instance();
            // a fresh executor per run: TuiCommand.run() shuts the executor down when the TUI
            // quits, and the container-managed ExecutorService bean is also used by the
            // agent beans (TurnRunner, ChatClient...). Passing a dedicated one lets every test
            // reuse the shared @BeforeAll container without poisoning the other tests.
            final var runExecutor = Executors.newVirtualThreadPerTaskExecutor();
            return new TuiCommand(
                    conf,
                    d.tuiConfiguration(),
                    d.configuration(),
                    shellRegistry.instance(),
                    runtimeFactory.instance(),
                    d.sessionStore(),
                    d.sessionLifecycle(),
                    d.chatClient(),
                    d.modelStyleService(),
                    new SpeakerLabels(
                            List.of(new FormatDatePromptHelper(Clock.systemDefaultZone())),
                            d.configuration(),
                            d.chatClient()),
                    autoLogConfigurer.instance(),
                    d.mcpManager(),
                    runExecutor,
                    d.providerManager(),
                    d.agentProvider(),
                    d.agentSessionService(),
                    turnRunner.instance(),
                    d.thinkTags(),
                    headlessFactory(),
                    tuiSlashCommands,
                    recordingRegistry == null ? slashRegistry.instance() : recordingRegistry,
                    Clock.systemUTC(),
                    new AskUserCardRenderer(new AskUserAnswersFormatter(formatMapper)),
                    new ToolOutputFormat(formatMapper),
                    new BtwAnswerer(
                            d.chatClient(),
                            d.sessionStore(),
                            d.configuration(),
                            d.executor(),
                            new InBandToolCallExtractor(new MarkupToolCallScanner()),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null),
                    tabManager,
                    new SubagentRegistry(d.configuration(), d.providerManager()),
                    new ScrollService(d.tuiConfiguration()),
                    scheduler.instance(),
                    formatMapper,
                    null);
        }
    }

    private TuiRunnerFactory headlessFactory() {
        return new TuiRunnerFactory() {
            @Override
            public TuiRunner create(final TuiCommand.TuiConf tuiConf, final boolean hideCursor) throws Exception {
                final var runner = TuiRunner.create(TuiConfig.builder()
                        .backend(backend)
                        .mouseCapture(false)
                        .rawMode(false)
                        .alternateScreen(false)
                        .bracketedPaste(false)
                        .shutdownHook(false)
                        .tickRate(ofMillis(20))
                        // observes every render cycle, even the no-op frames an idle heartbeat
                        // produces (TamboUI diffs them away so the backend never draws them)
                        .postRenderProcessor(frame -> renderCycles.incrementAndGet())
                        .build());
                tuiRef.set(runner);
                return runner;
            }
        };
    }

    private void awaitTui() throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (tuiRef.get() == null) {
            assertTrue(System.nanoTime() < deadline, "tui runner was not created within " + TIMEOUT_MS + "ms");
            backend.awaitFrame(50);
        }
    }

    private void awaitText(final String expected) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        var screen = backend.text();
        while (!screen.contains(expected)) {
            if (System.nanoTime() > deadline) {
                final var message =
                        "did not see '" + expected + "' within " + TIMEOUT_MS + "ms; last screen:\n" + screen;
                throw new AssertionError(message);
            }
            // Frame signals are only wake-ups, not the condition: unchanged frames
            // are suppressed, and a draw can race with the preceding screen snapshot.
            awaitTextFrame();
            screen = backend.text();
        }
    }

    private void awaitTextGone(final String expected) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        var screen = backend.text();
        while (screen.contains(expected)) {
            if (System.nanoTime() > deadline) {
                final var message =
                        "'" + expected + "' never disappeared within " + TIMEOUT_MS + "ms; last screen:\n" + screen;
                throw new AssertionError(message);
            }
            awaitTextFrame();
            screen = backend.text();
        }
    }

    /** Arms a fresh quiet-boundary handshake for each scheduled mutation. */
    private Callable<Void> afterQuietTextPoll(final Runnable mutation) {
        final var quiet = new CountDownLatch(1);
        quietTextPoll = quiet;
        return () -> {
            assertTrue(quiet.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "text helper never crossed a quiet poll");
            mutation.run();
            return null;
        };
    }

    private void awaitTextFrame() throws InterruptedException {
        if (!backend.awaitFrame(textPollMillis) && quietTextPoll != null) {
            quietTextPoll.countDown();
        }
    }

    @Test
    void sessionOptionReplaysPersistedSession() throws Exception {
        final var sessionId = "sresume";
        deps.sessionStore().append(sessionId, SessionEntry.of(1, "user", "resumed question", null, null));
        deps.sessionStore().append(sessionId, SessionEntry.of(2, "assistant", "resumed assistant reply", null, null));
        final var command = newUiCommand(
                container, deps, new TuiCommand.TuiConf(sessionId, "", KeyBindingPreset.STANDARD, true, "dark"));

        uiThread = new Thread(command::run, "tui-headless-session");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        // the persisted history is replayed into the initial tab, not fetched from the model
        awaitText("resumed assistant reply");
        awaitText("ready");
        final var requestsBefore = llm.requests().size();
        assertEquals(requestsBefore, llm.requests().size(), "resuming a session at launch must not call the model");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void initialPromptResumeAliasRestartsTheLoopOnThePersistedHistory() throws Exception {
        final var sessionId = "sinitialresume";
        deps.sessionStore().append(sessionId, SessionEntry.of(1, "user", "already asked", null, null));
        deps.sessionStore().append(sessionId, SessionEntry.of(2, "assistant", "already answered", null, null));
        final var requestsBefore = llm.requests().size();
        // `continue` is a resume alias by default (yca.resumeAliases): the turn restarts the
        // loop on the persisted history, so it reaches the model without any new user message
        final var command = newUiCommand(
                container,
                deps,
                new TuiCommand.TuiConf(
                        sessionId, "", KeyBindingPreset.STANDARD, true, true, "dark", List.of("continue"), null));

        uiThread = new Thread(command::run, "tui-headless-initial-resume");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("already answered");
        // the loop ran again on its own: the mock is called and its answer joins the transcript
        awaitText("hello from mock");

        final var sent = llm.requests().subList(requestsBefore, llm.requests().size());
        assertFalse(sent.isEmpty(), "the injected resume turn must call the model");
        assertTrue(
                sent.stream().anyMatch(it -> it.contains("already asked")),
                "the resumed turn must replay the persisted history");
        assertTrue(
                deps.sessionStore().load(sessionId).stream()
                        .noneMatch(
                                e -> "user".equals(e.role()) && "continue".contentEquals(String.valueOf(e.content()))),
                "a resume turn must not persist the alias as a user message");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void initialPromptListIsSubmittedInOrder() throws Exception {
        final var requestsBefore = llm.requests().size();
        // two prompts: the first one starts a turn and the second one waits for that turn
        // to end, so they are two turns and not one merged type-ahead turn
        final var command = newUiCommand(
                container,
                deps,
                new TuiCommand.TuiConf(
                        "",
                        "",
                        KeyBindingPreset.STANDARD,
                        true,
                        true,
                        "dark",
                        List.of("injected first", "injected second"),
                        null));

        uiThread = new Thread(command::run, "tui-headless-initial-list");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("injected second");
        awaitText("ready");

        final var sent = List.copyOf(
                llm.requests().subList(requestsBefore, llm.requests().size()));
        final var firstTurn = sent.stream()
                .filter(it -> it.contains("injected first"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the first injected prompt never reached the model"));
        final var secondTurn = sent.stream()
                .filter(it -> it.contains("injected second"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the queued prompt was never submitted: " + sent));
        assertTrue(sent.indexOf(firstTurn) < sent.indexOf(secondTurn), "injected prompts must run in the given order");
        assertFalse(
                firstTurn.contains("injected second"),
                "the second prompt must run as its own turn, not merged into the first one");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void initialPromptSlashEntryDoesNotStallTheQueue() throws Exception {
        // {@code /mode build} is a slash command: it starts no turn (no TurnDone event), so the
        // following entry must be released when the command outcome is applied, not when a turn ends
        final var requestsBefore = llm.requests().size();
        final var command = newUiCommand(
                container,
                deps,
                new TuiCommand.TuiConf(
                        "",
                        "",
                        KeyBindingPreset.STANDARD,
                        true,
                        true,
                        "dark",
                        List.of("/mode build", "hello from the script"),
                        null));

        uiThread = new Thread(command::run, "tui-headless-initial-slash");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("mode: build");
        awaitText("hello from the script");
        awaitText("ready");

        final var sent = List.copyOf(
                llm.requests().subList(requestsBefore, llm.requests().size()));
        assertFalse(sent.isEmpty(), "the prompt following the slash command never reached the model: " + sent);
        assertTrue(
                sent.stream().anyMatch(it -> it.contains("hello from the script")),
                "the queue must advance past a slash command - got: " + sent);

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void mistypedCommandRestoresInputWithoutSendingOrRecordingIt(final boolean initial, final boolean correct)
            throws Exception {
        final var sessionId = "routing-" + initial + "-" + correct;
        final var original = "/modle keep-this-argument";
        final var requestsBefore = llm.requests().size();
        final var command = newUiCommand(
                container,
                deps,
                new TuiCommand.TuiConf(
                        sessionId,
                        "",
                        KeyBindingPreset.STANDARD,
                        true,
                        true,
                        "dark",
                        initial ? List.of(original, "must-not-release-startup-queue") : List.of(),
                        null));
        uiThread = new Thread(command::run, "tui-routing-restore");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        if (!initial) {
            awaitText("yca coding agent");
            tuiRef.get().dispatch(new PasteEvent(original));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        }

        awaitText("Unknown command /modle - did you mean");
        awaitText("/model");
        awaitText("Send as normal input");
        awaitText("Mistyped command - I'll fix it");
        assertEquals(requestsBefore, llm.requests().size(), "routing must wait for the picker before sending");
        assertTrue(deps.sessionStore().load(sessionId).isEmpty(), "an unresolved input is not a session entry");
        if (correct) {
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.DOWN));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("fix the command and submit again");
        } else {
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        }
        awaitTextGone("Send as normal input");
        awaitText(original);

        // Editing proves that the original is in the input, with its cursor restored at the end.
        tuiRef.get().dispatch(KeyEvent.ofChar('!'));
        awaitText(original + "!");
        tuiRef.get().dispatch(KeyEvent.ofChar('u', KeyModifiers.CTRL));
        awaitTextGone(original);
        if (!correct) {
            assertFalse(backend.text().contains("fix the command and submit again"));
        }
        assertEquals(requestsBefore, llm.requests().size());
        assertTrue(deps.sessionStore().load(sessionId).isEmpty());

        // Cross a real turn boundary: a stopped startup queue must not resume afterwards,
        // and neither the rejected text nor the local correction notification may leak to the model.
        tuiRef.get().dispatch(new PasteEvent("routing recovery prompt"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("hello from mock");
        awaitText("ready");
        final var sent = List.copyOf(
                llm.requests().subList(requestsBefore, llm.requests().size()));
        assertFalse(sent.isEmpty());
        for (final var request : sent) {
            assertTrue(request.contains("routing recovery prompt"));
            assertFalse(request.contains(original));
            assertFalse(request.contains("must-not-release-startup-queue"));
            assertFalse(request.contains("fix the command and submit again"));
        }
        assertEquals(
                List.of("routing recovery prompt"),
                deps.sessionStore().load(sessionId).stream()
                        .filter(entry -> "user".equals(entry.role()))
                        .map(entry -> String.valueOf(entry.content()))
                        .toList());
        tuiRef.get().quit();
        uiThread.join(TIMEOUT_MS);
        assertFalse(uiThread.isAlive());
        assertFalse(Files.readAllLines(temp.resolve("home/history")).contains(original));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousInputCanBeSentVerbatimAndStartupQueueContinues(final boolean initial) throws Exception {
        final var sessionId = "routing-accept-" + initial;
        final var original = "/modle explain this literally " + initial;
        final var requestsBefore = llm.requests().size();
        final var command = newUiCommand(
                container,
                deps,
                new TuiCommand.TuiConf(
                        sessionId,
                        "",
                        KeyBindingPreset.STANDARD,
                        true,
                        true,
                        "dark",
                        initial ? List.of(original, "accepted startup successor") : List.of(),
                        null));
        uiThread = new Thread(command::run, "tui-routing-accept");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        if (!initial) {
            awaitText("yca coding agent");
            tuiRef.get().dispatch(new PasteEvent(original));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        }
        awaitText("Send as normal input");
        assertEquals(requestsBefore, llm.requests().size());
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitTextGone("Send as normal input");
        awaitText("hello from mock");
        if (initial) {
            awaitText("accepted startup successor");
        }
        awaitText("ready");

        final var sent = List.copyOf(
                llm.requests().subList(requestsBefore, llm.requests().size()));
        assertFalse(sent.isEmpty());
        assertTrue(sent.getFirst().contains(original));
        assertFalse(sent.getFirst().contains("accepted startup successor"));
        assertEquals(
                initial ? List.of(original, "accepted startup successor") : List.of(original),
                deps.sessionStore().load(sessionId).stream()
                        .filter(entry -> "user".equals(entry.role()))
                        .map(entry -> String.valueOf(entry.content()))
                        .toList());
        tuiRef.get().quit();
        uiThread.join(TIMEOUT_MS);
        assertFalse(uiThread.isAlive());
        assertEquals(
                1L,
                Files.readAllLines(temp.resolve("home/history")).stream()
                        .filter(original::equals)
                        .count(),
                "accepted input is recorded once, not again when its turn starts");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tmp/project/file.txt explain this", "/123 is literal input", "/mode/config.json"})
    void slashPathsAndNonCommandTokensReachTheModelWithoutAPicker(final String text) throws Exception {
        final var requestsBefore = llm.requests().size();
        final var command = newUiCommand(container, deps);
        uiThread = new Thread(command::run, "tui-routing-path");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");
        tuiRef.get().dispatch(new PasteEvent(text));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("hello from mock");
        awaitText("ready");
        assertTrue(llm.requests().subList(requestsBefore, llm.requests().size()).stream()
                .anyMatch(request -> request.contains(text)));
        assertFalse(backend.text().contains("Send as normal input"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/btw", "/btw history-only question"})
    void modalCommandsAndShiftTabAreRecalledAndDeduplicatedInHistory(final String btw) throws Exception {
        final var home = Files.createTempDirectory(temp, "routing-history-");
        try (final var isolated = bootContainer(Map.of("yca.storageHome", home.toString()))) {
            final var isolatedDeps = lookupDeps(isolated);
            final var requestsBefore = llm.requests().size();
            final var command = newUiCommand(
                    isolated,
                    isolatedDeps,
                    new TuiCommand.TuiConf("routing-history", "", KeyBindingPreset.STANDARD, true, "dark"));
            uiThread = new Thread(command::run, "tui-routing-history");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // Both a real /btw overlay and its usage early return must be in history.
            tuiRef.get().dispatch(new PasteEvent(btw));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            if ("/btw".equals(btw)) {
                awaitText("usage: /btw <question>");
            } else {
                awaitText("hello from mock");
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
                awaitTextGone("hello from mock");
                assertFalse(
                        backend.text().contains("history-only question"), "the side question is not a transcript turn");
            }
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.UP));
            tuiRef.get().dispatch(KeyEvent.ofChar('!'));
            awaitText(btw + "!");
            tuiRef.get().dispatch(KeyEvent.ofChar('u', KeyModifiers.CTRL));
            awaitTextGone(btw + "!");
            tuiRef.get().dispatch(new PasteEvent("/executing-tools"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("(no tool executing)");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("(no tool executing)");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.UP));
            awaitText("/executing-tools");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("(no tool executing)");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
            awaitTextGone("(no tool executing)");

            // The modal-only command must not have been appended to the transcript.
            assertFalse(backend.text().contains("/executing-tools"));
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT));
            awaitText("mode: build");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.UP));
            tuiRef.get().dispatch(KeyEvent.ofChar('!'));
            awaitText("/mode build!");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.BACKSPACE));
            awaitTextGone("/mode build!");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            // A visible draft after resubmission is an event-loop barrier, not another history entry.
            tuiRef.get().dispatch(new PasteEvent("history-dedup-draft"));
            awaitText("history-dedup-draft");
            final var sent = List.copyOf(
                    llm.requests().subList(requestsBefore, llm.requests().size()));
            if ("/btw".equals(btw)) {
                assertTrue(sent.isEmpty(), "the usage early return must not invoke an LLM");
            } else {
                assertFalse(sent.isEmpty());
                assertTrue(sent.stream().allMatch(request -> request.contains("history-only question")));
            }
            assertTrue(isolatedDeps.sessionStore().load("routing-history").isEmpty());

            tuiRef.get().quit();
            uiThread.join(TIMEOUT_MS);
            assertFalse(uiThread.isAlive());
            assertEquals(List.of("/mode build", "/executing-tools", btw), Files.readAllLines(home.resolve("history")));
        }
    }

    @Test
    void registeredCatalogRoutesToCommandsWithoutExecutingTheirRealActions() throws Exception {
        final var received = new CopyOnWriteArrayList<String>();
        try (final var registry = container.lookup(SlashRegistry.class)) {
            final var catalog = registry.instance().specs();
            final var recording = new SlashRegistry(List.of(() -> catalog.stream()
                    .map(spec -> SlashCommand.of(spec, (arg, env) -> {
                        final var input = spec.name() + " " + arg;
                        received.add(input);
                        return CompletableFuture.completedStage(ShellOutcome.status("recorded " + input));
                    }))
                    .toList()));
            final var requestsBefore = llm.requests().size();
            final var command = newUiCommand(
                    container,
                    deps,
                    new TuiCommand.TuiConf("routing-catalog", "", KeyBindingPreset.STANDARD, true, "dark"),
                    recording);
            uiThread = new Thread(command::run, "tui-routing-catalog");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // These three are handled locally before registry dispatch; their real modal paths
            // have dedicated tests. All other actions, including destructive ones, are replaced.
            final var inputs = catalog.stream()
                    .filter(spec ->
                            !List.of("btw", "executing-tools", "editplan").contains(spec.command()))
                    .map(spec -> spec.name() + " catalog/argument")
                    .toList();
            assertFalse(inputs.isEmpty());
            for (final var input : inputs) {
                tuiRef.get().dispatch(new PasteEvent(input));
                tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
                awaitText("recorded " + input);
                assertFalse(backend.text().contains("Send as normal input"));
            }
            assertEquals(inputs, received);
            assertEquals(requestsBefore, llm.requests().size());
            assertTrue(deps.sessionStore().load("routing-catalog").isEmpty());
            tuiRef.get().quit();
            uiThread.join(TIMEOUT_MS);
            assertFalse(uiThread.isAlive());
            final var history = Files.readAllLines(temp.resolve("home/history"));
            for (final var input : inputs) {
                assertEquals(1L, history.stream().filter(input::equals).count(), input);
            }
        }
    }

    @Test
    void tabsSlashOpensOverlayThroughRealRegistry() throws Exception {
        final var command = newUiCommand(container, deps);

        uiThread = new Thread(command::run, "tui-tabs-list");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();

        // dispatch through the real SlashRegistry: "/tabs" (no arg) opens the modal
        tuiRef.get().dispatch(new PasteEvent("/tabs"));
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
        awaitText("1-9 switch");

        // Esc closes the overlay again
        tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ESCAPE));
        awaitTextGone("1-9 switch");

        // quit the TUI so run() unwinds before the container closes
        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }

    @Test
    void exitingLogsEveryOpenedSessionWithItsTabTitle() throws Exception {
        // remembering the session(s) of a run matters most for multi-tab TUI sessions: the exit
        // must name each persisted session with the tab title so it can be resumed afterwards
        final var command = newUiCommand(
                container, deps, new TuiCommand.TuiConf("exit-session-a", "", KeyBindingPreset.STANDARD, true, "dark"));

        final var events = new CopyOnWriteArrayList<String>();
        final var logger = Logger.getLogger(TuiCommand.class.getName());
        final var handler = new Handler() {
            {
                setLevel(Level.ALL);
            }

            @Override
            public void publish(final LogRecord record) {
                events.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        // marks when the TUI released the terminal: the session lines must only be emitted after
        // that point (on the restored screen), or the alternate-screen buffer would swallow them
        backend.onClose(() -> events.add("terminal released"));
        logger.addHandler(handler);
        try {
            uiThread = new Thread(command::run, "tui-headless-exit-log");
            uiThread.setDaemon(true);
            uiThread.start();
            awaitTui();
            awaitText("yca coding agent");

            // first tab reuses the deterministic session id and titles itself from the prompt
            tuiRef.get().dispatch(new PasteEvent("first tab prompt"));
            awaitText("first tab prompt");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("hello from mock");
            awaitText("ready");

            // second tab: fresh session, its own title
            tuiRef.get().dispatch(KeyEvent.ofChar('n', KeyModifiers.CTRL));
            tuiRef.get().dispatch(new PasteEvent("second tab topic"));
            awaitText("second tab topic");
            tuiRef.get().dispatch(KeyEvent.ofKey(KeyCode.ENTER));
            awaitText("hello from mock");
            awaitText("ready");

            tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
            uiThread.join(TIMEOUT_MS);
            assertFalse(uiThread.isAlive(), "the TUI thread should have exited");

            assertTrue(
                    events.contains("session exit-session-a (first tab prompt)"),
                    "expected an exit log naming the first session with its tab title, got: " + events);
            assertTrue(
                    events.stream().anyMatch(it -> it.startsWith("session ") && it.endsWith("(second tab topic)")),
                    "expected an exit log naming the second session with its tab title, got: " + events);
            final var sessionLines =
                    events.stream().filter(it -> it.startsWith("session ")).toList();
            final var released = events.lastIndexOf("terminal released");
            assertTrue(released >= 0, "expected the terminal released marker, got: " + events);
            for (final var sessionLine : sessionLines) {
                assertTrue(
                        events.indexOf(sessionLine) > released,
                        "the session logs must be emitted only after the terminal is released, got: " + events);
            }
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void capturedLogsLandInTheContentViewAndNotify() throws Exception {
        final var command = newUiCommand(container, deps);

        uiThread = new Thread(command::run, "tui-headless-logs");
        uiThread.setDaemon(true);
        uiThread.start();
        awaitTui();
        awaitText("yca coding agent");

        // emitted after the TUI ran: run() already detached the inline root handler
        // (AutoLogConfigurer) and attached the content-view capture handlers
        final var logger = Logger.getLogger("io.yupiik.ia.agent.core.codegraph.StructuralCodeGraph");
        logger.info("captured info log");
        awaitText("captured info log");
        // inline log shape: [label][LEVEL][short logger] message on the same row
        awaitText("[INFO][core.codegraph.StructuralCodeGraph] captured info log");

        logger.warning("captured warning log");
        awaitText("captured warning log");

        logger.severe("captured severe log");
        awaitText("captured severe log");
        awaitText("Alt+E for details");

        tuiRef.get().dispatch(KeyEvent.ofChar('c', KeyModifiers.CTRL));
        uiThread.join(TIMEOUT_MS);
    }
}
