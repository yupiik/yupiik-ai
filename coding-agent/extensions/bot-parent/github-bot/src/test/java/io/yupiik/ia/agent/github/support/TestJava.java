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
package io.yupiik.ia.agent.github.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Commands for short-lived test JVMs, never production launchers or inherited runtime arguments.
 * Set {@code yca.test.childJvmOptions} to whitespace-separated JVM options (no shell quoting),
 * or to an empty value to disable the defaults. Arguments needing spaces belong in the caller's
 * argument list. Explicit scenario options must follow these defaults, notably processor matrices.
 * No processor cap is imposed: that could hide concurrency bugs.
 */
public final class TestJava {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final String OPTIONS = "yca.test.childJvmOptions";

    private TestJava() {}

    public static List<String> javaCommand(final String... arguments) {
        final var command = command("java", "");
        // Nested compiled test fixtures use the same configuration without any environment injection.
        final var configured = System.getProperty(OPTIONS);
        if (configured != null) {
            command.add("-D" + OPTIONS + "=" + configured);
        }
        command.addAll(List.of(arguments));
        return command;
    }

    public static List<String> keytoolCommand(final String... arguments) {
        final var command = command("keytool", "-J");
        command.addAll(List.of(arguments));
        return command;
    }

    private static List<String> command(final String executable, final String prefix) {
        final var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        final var options =
                System.getProperty(OPTIONS, "-XX:TieredStopAtLevel=1").strip();
        if (!options.isEmpty()) {
            for (final var option : WHITESPACE.split(options)) {
                command.add(prefix + option);
            }
        }
        return command;
    }
}
