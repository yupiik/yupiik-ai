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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ClipboardTest {
    @Test
    void macUsesPbcopy() {
        assertArrayEquals(new String[] {"pbcopy"}, Clipboard.nativeCommand("Mac OS X", Map.of()));
        assertArrayEquals(new String[] {"pbcopy"}, Clipboard.nativeCommand("darwin", Map.of()));
    }

    @Test
    void waylandUsesWlCopy() {
        assertArrayEquals(
                new String[] {"wl-copy"}, Clipboard.nativeCommand("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0")));
    }

    @Test
    void x11UsesXclip() {
        assertArrayEquals(
                new String[] {"xclip", "-selection", "clipboard"},
                Clipboard.nativeCommand("Linux", Map.of("DISPLAY", ":0")));
    }

    @Test
    void wslUsesClipExeEvenWhenDisplayIsSet() {
        assertArrayEquals(
                new String[] {"clip.exe"},
                Clipboard.nativeCommand("Linux", Map.of("WSL_DISTRO_NAME", "Ubuntu", "DISPLAY", ":0")));
        assertArrayEquals(new String[] {"clip.exe"}, Clipboard.nativeCommand("Linux", Map.of("WSLENV", "x")));
    }

    @Test
    void plainLinuxWithoutDisplayHasNoNativeTool() {
        assertNull(Clipboard.nativeCommand("Linux", Map.of()));
        assertNull(Clipboard.nativeCommand(null, Map.of("DISPLAY", "")));
    }
}
