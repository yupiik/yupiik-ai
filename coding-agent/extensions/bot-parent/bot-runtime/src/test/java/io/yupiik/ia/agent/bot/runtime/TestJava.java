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
package io.yupiik.ia.agent.bot.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Child-JVM fixture options, never inherited production runtime arguments. */
public final class TestJava {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TestJava() {}

    public static List<String> javaCommand(final String... arguments) {
        final var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        final var options = System.getProperty("yca.test.childJvmOptions", "-XX:TieredStopAtLevel=1")
                .strip();
        if (!options.isEmpty()) {
            command.addAll(List.of(WHITESPACE.split(options)));
        }
        final var configured = System.getProperty("yca.test.childJvmOptions");
        if (configured != null) {
            command.add("-Dyca.test.childJvmOptions=" + configured);
        }
        command.addAll(List.of(arguments));
        return command;
    }
}
