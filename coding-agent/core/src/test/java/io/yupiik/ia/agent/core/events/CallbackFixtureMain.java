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
package io.yupiik.ia.agent.core.events;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Dependency-free callback fixture used by {@link CallbackRunnerTest} as the hook "external program". It reads the
 * event JSON piped on stdin and echoes it back to a temp file (so the test can assert the <em>input</em> contract)
 * then behaves according to the `mode` argument:
 *
 * <ul>
 *   <li>{@code forbid} — prints a valid {@code HookResponse} {@code forbid} decision, exit 0</li>
 *   <li>{@code allow} — prints a valid {@code allow} decision, exit 0</li>
 *   <li>{@code empty} — prints nothing, exit 0 (noop)</li>
 *   <li>{@code invalid} — prints non-JSON garbage, exit 0</li>
 *   <li>{@code exit} — prints nothing, exits 3</li>
 *   <li>{@code slow} — sleeps longer than the callback timeout so the runner times it out</li>
 * </ul>
 *
 * <p>Deliberately dependency-free (no JUnit, no fusion) so it can be launched as plain {@code java -cp ... } with
 * {@code java.home} and runs in the IDE without the surefire fork.</p>
 */
public class CallbackFixtureMain {
    private CallbackFixtureMain() {
        // no-op
    }

    static void main(final String[] args) throws Exception {
        final var input = readAll();
        final var echoTarget = System.getProperty("fixture.echo");
        if (echoTarget != null && !echoTarget.isBlank()) {
            Files.writeString(Path.of(echoTarget), input, StandardCharsets.UTF_8);
        }
        final var mode = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "forbid" -> {
                System.out.print("{\"decision\":\"forbid\",\"reason\":\"blocked by test\"}");
                System.exit(0);
            }
            case "allow" -> {
                System.out.print("{\"decision\":\"allow\"}");
                System.exit(0);
            }
            case "empty" -> System.exit(0);
            case "invalid" -> {
                System.out.print("this is not json {");
                System.exit(0);
            }
            case "exit" -> System.exit(3);
            case "slow" -> {
                Thread.sleep(60_000);
                System.exit(0);
            }
            default -> System.exit(2);
        }
    }

    private static String readAll() throws IOException {
        final var buffer = new StringBuilder();
        final var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var line = reader.readLine();
        while (line != null) {
            buffer.append(line);
            line = reader.readLine();
        }
        return buffer.toString();
    }
}
