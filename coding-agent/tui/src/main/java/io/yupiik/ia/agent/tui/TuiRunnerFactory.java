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

import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.time.Duration;

/**
 * Creates the {@link TuiRunner} used by the TUI command. Isolated in a bean so
 * tests can substitute a headless runner backed by an in-memory terminal.
 */
@ApplicationScoped
public class TuiRunnerFactory {
    /**
     * Any-motion mouse tracking ({@code ESC[?1003h}), enabled with
     * {@code TuiConfig.Builder.mouseMotion(true)} so the tab-bar hover tooltip receives
     * MOVE events. TamboUI then owns the enable/disable protocol ({@code ?1000h/1002h/1003h/
     * 1015h/1006h} on, the matching {@code ?...l} on exit) and a wrapped
     * {@link MouseReportCoalescingBackend} re-assembles a report split by a parser timeout,
     * so no mouse bytes ever leak into the prompt.
     */
    static final String MOUSE_MOTION_DISABLE = "\u001b[?1003l";

    public TuiRunner create(final TuiCommand.TuiConf conf) throws Exception {
        return create(conf, false);
    }

    public TuiRunner create(final TuiCommand.TuiConf conf, final boolean hideCursor) throws Exception {
        // TamboUI's backend creation is public and returns the JLine backend (or whatever is
        // registered on the classpath); wrapping it re-assembles split SGR mouse reports at the
        // byte level so neither the parser nor the input loop ever emits their printable tail.
        final var runner = TuiRunner.create(TuiConfig.builder()
                // TamboUI's native mouse capture with any-motion: ?1000h/?1002h/?1003h/?1015h/?1006h
                .mouseCapture(true)
                .mouseMotion(true)
                // bracketed paste (ESC[?2004h): ctrl+shift+v / middle-click pastes arrive as a
                // single PasteEvent preserving newlines instead of a keystroke burst
                .bracketedPaste(true)
                .tickRate(Duration.ofMillis(100))
                // the event loop only wakes on real events after the idle poll expires;
                // a longer timeout cuts idle wakeups without hurting input latency (events
                // surface instantly through the non-blocking queue drain)
                .pollTimeout(Duration.ofMillis(100))
                .bindings(conf.bindings().toBindings())
                .hideCursor(hideCursor)
                .backend(new MouseReportCoalescingBackend(
                        BackendFactory.create(MouseReportCoalescingBackend.class.getClassLoader())))
                .build());
        return runner;
    }
}
