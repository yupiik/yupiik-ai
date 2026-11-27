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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemeDetectorTest {
    @Test
    void gtkSettingsKeepAllRegexLineBreaksAndDoNotSplitOnOrdinaryWhitespace() {
        for (final var separator : List.of("\n", "\r", "\r\n", "\u000b", "\f", "\u0085", "\u2028", "\u2029")) {
            assertEquals(
                    Optional.of("dark"),
                    ThemeDetector.gtkFromSettings("[Settings]" + separator + separator
                            + "\tgtk-application-prefer-dark-theme = true  " + separator + separator),
                    separator);
            assertEquals(
                    Optional.of("light"),
                    ThemeDetector.gtkFromSettings("[Settings]" + separator + "gtk-application-prefer-dark-theme=false"),
                    separator);
        }
        assertEquals(Optional.empty(), ThemeDetector.gtkFromSettings(""));
        assertEquals(
                Optional.empty(),
                ThemeDetector.gtkFromSettings("[Settings]\u00a0gtk-application-prefer-dark-theme=true"));
        assertEquals(Optional.empty(), ThemeDetector.gtkFromSettings("x".repeat(100_000)));
    }

    @Test
    void macDark() {
        assertEquals(Optional.of("dark"), ThemeDetector.detect("Mac OS X", Map.of(), command -> {
            assertEquals(List.of("defaults", "read", "-g", "AppleInterfaceStyle"), command);
            return Optional.of(new ThemeDetector.ProcessResult("Dark", 0));
        }));
    }

    @Test
    void macLightWhenKeyAbsent() {
        assertEquals(
                Optional.of("light"),
                ThemeDetector.detect(
                        "macOS", Map.of(), command -> Optional.of(new ThemeDetector.ProcessResult("", 1))));
    }

    @Test
    void macFailureIsEmpty() {
        assertTrue(ThemeDetector.detect("Mac OS X", Map.of(), command -> Optional.empty())
                .isEmpty());
    }

    @Test
    void windowsDark() {
        assertEquals(Optional.of("dark"), ThemeDetector.detect("Windows 11", Map.of(), command -> {
            assertEquals(
                    List.of(
                            "reg",
                            "query",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                            "/v",
                            "AppsUseLightTheme"),
                    command);
            return Optional.of(new ThemeDetector.ProcessResult("    AppsUseLightTheme    REG_DWORD    0x0", 0));
        }));
    }

    @Test
    void windowsLight() {
        assertEquals(
                Optional.of("light"),
                ThemeDetector.detect(
                        "Windows 10",
                        Map.of(),
                        command -> Optional.of(
                                new ThemeDetector.ProcessResult("    AppsUseLightTheme    REG_DWORD    0x1", 0))));
    }

    @Test
    void windowsUnknownValueFallsBackToColorFgBg() {
        assertEquals(
                Optional.of("dark"),
                ThemeDetector.detect(
                        "Windows 10",
                        Map.of("COLORFGBG", "15;0"),
                        command -> Optional.of(new ThemeDetector.ProcessResult("0x2", 0))));
    }

    @Test
    void gnomePreferDark() {
        assertEquals(Optional.of("dark"), ThemeDetector.detect("Linux", Map.of(), command -> {
            assertEquals(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"), command);
            return Optional.of(new ThemeDetector.ProcessResult("'prefer-dark'", 0));
        }));
    }

    @Test
    void gnomeDefaultIsLight() {
        assertEquals(
                Optional.of("light"),
                ThemeDetector.detect(
                        "Linux", Map.of(), command -> Optional.of(new ThemeDetector.ProcessResult("'default'", 0))));
    }

    @Test
    void kdeDark() {
        // gsettings comes first in the cascade: simulate its absence, then kreadconfig5 answers
        assertEquals(Optional.of("dark"), ThemeDetector.detect("Linux", Map.of(), command -> {
            if (command.get(0).equals("gsettings")) {
                return Optional.empty();
            }
            assertEquals(
                    List.of("kreadconfig5", "--file", "kdeglobals", "--group", "General", "--key", "ColorScheme"),
                    command);
            return Optional.of(new ThemeDetector.ProcessResult("BreezeDark", 0));
        }));
    }

    @Test
    void kdeMissingFallsBackToGtkFile(@TempDir final Path home) throws Exception {
        Files.createDirectories(home.resolve(".config/gtk-3.0"));
        Files.writeString(
                home.resolve(".config/gtk-3.0/settings.ini"), "[Settings]\ngtk-application-prefer-dark-theme=true\n");
        // no tool at all: the GTK settings file under $HOME decides
        assertEquals(
                Optional.of("dark"),
                ThemeDetector.detect("Linux", Map.of("HOME", home.toString()), command -> Optional.empty()));
        // no file: empty
        assertEquals(
                Optional.empty(),
                ThemeDetector.detect("Linux", Map.of("HOME", "/does/not/exist"), command -> Optional.empty()));
        // gtk-3.0/settings.ini with prefer-dark=false must yield light
        assertEquals(
                Optional.of("light"),
                ThemeDetector.gtkFromSettings("[Settings]\ngtk-application-prefer-dark-theme=false\n"));
        assertTrue(
                ThemeDetector.gtkFromSettings("[Settings]\nno-theme-line=1\n").isEmpty());
    }

    @Test
    void colorFgBgDarkAndLight() {
        assertEquals(
                Optional.of("dark"),
                ThemeDetector.detect("Linux", Map.of("COLORFGBG", "15;0"), command -> Optional.empty()));
        assertEquals(
                Optional.of("light"),
                ThemeDetector.detect("Linux", Map.of("COLORFGBG", "0;15"), command -> Optional.empty()));
        assertTrue(ThemeDetector.detect("Linux", Map.of(), command -> Optional.empty())
                .isEmpty());
    }

    @Test
    void fallbackKeepsRunningPerOs() {
        // unknown OS: only COLORFGBG is consulted, command runner is never called
        final var calls = new ArrayList<List<String>>();
        final var detected = ThemeDetector.detect("Solaris", Map.of("COLORFGBG", "15;0"), command -> {
            calls.add(command);
            return Optional.empty();
        });
        assertEquals(Optional.of("dark"), detected);
        assertTrue(calls.isEmpty());
        // linux without any tool and without COLORFGBG yields empty
        assertTrue(ThemeDetector.detect("Linux", Map.of("HOME", "/tmp"), command -> Optional.empty())
                .isEmpty());
    }
}
