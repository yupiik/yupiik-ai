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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TestJavaTest {
    @Test
    void defaultsAreTestOnlyAndDoNotCopyParentRuntimeOptions() throws Exception {
        assertEquals(
                expected("java", "-XX:TieredStopAtLevel=1|-XX:ActiveProcessorCount=3|Main|argument with spaces")
                        + expected("keytool", "-J-XX:TieredStopAtLevel=1|-list"),
                probe(null));
    }

    @Test
    void emptyConfigurationDisablesDefaults() throws Exception {
        assertEquals(
                expected("java", "-Dyca.test.childJvmOptions=|-XX:ActiveProcessorCount=3|Main|argument with spaces")
                        + expected("keytool", "-list"),
                probe(""));
    }

    @Test
    void configurationReplacesDefaultsAndScenarioOptionsRemainLast() throws Exception {
        final var options = "  -XX:TieredStopAtLevel=2   -XX:ActiveProcessorCount=2  ";
        assertEquals(
                expected(
                                "java",
                                "-XX:TieredStopAtLevel=2|-XX:ActiveProcessorCount=2|-Dyca.test.childJvmOptions="
                                        + options + "|-XX:ActiveProcessorCount=3|Main|argument with spaces")
                        + expected("keytool", "-J-XX:TieredStopAtLevel=2|-J-XX:ActiveProcessorCount=2|-list"),
                probe(options));
    }

    private static String expected(final String executable, final String arguments) {
        return Path.of(System.getProperty("java.home"), "bin", executable) + "|" + arguments + "\n";
    }

    private static String probe(final String options) throws Exception {
        final var command = TestJava.javaCommand();
        // Configure only an isolated JVM, including when Maven itself supplied an override.
        command.removeIf(it -> it.startsWith("-Dyca.test.childJvmOptions="));
        if (options != null) {
            command.add("-Dyca.test.childJvmOptions=" + options);
        }
        command.addAll(List.of(
                "-Dyca.test.parentOnly=must-not-be-copied",
                "-Xmx64m",
                "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName()));
        final var process = new ProcessBuilder(command).start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Test JVM probe timed out");
            final var error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), error);
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(final String... arguments) {
            System.out.println(String.join(
                    "|", TestJava.javaCommand("-XX:ActiveProcessorCount=3", "Main", "argument with spaces")));
            System.out.println(String.join("|", TestJava.keytoolCommand("-list")));
        }
    }
}
