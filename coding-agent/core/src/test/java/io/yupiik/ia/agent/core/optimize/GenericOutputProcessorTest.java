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
package io.yupiik.ia.agent.core.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GenericOutputProcessorTest {
    @Nested
    @FusionSupport(modules = NormalSettings.class)
    class Normal {

        @Test
        void compactsBlankRunsAndTrailingWhitespace(@Fusion final CodingAgentConfiguration configuration) {
            final var out = new GenericOutputProcessor(configuration)
                    .process(
                            "ls -la",
                            Stream.of(
                                    "total 0",
                                    "",
                                    "   ",
                                    "drwxr-xr-x  user  staff   ",
                                    "drwxr-xr-x  user  staff  ",
                                    "",
                                    "",
                                    "done  ",
                                    "  "))
                    .toList();

            assertEquals(List.of("total 0", "", "drwxr-xr-x  user  staff", "drwxr-xr-x  user  staff", "", "done"), out);
        }

        @Test
        void dropsLeadingAndTrailingBlankLines(@Fusion final CodingAgentConfiguration configuration) {
            final var out = new GenericOutputProcessor(configuration)
                    .process("echo hello", Stream.of("", "", "hello", "", ""))
                    .toList();
            assertEquals(List.of("hello"), out);
        }

        @Test
        void stripsAnsi(@Fusion final CodingAgentConfiguration configuration) {
            final var out = new GenericOutputProcessor(configuration)
                    .process("ls", Stream.of("\u001B[31mred\u001B[0m text"))
                    .toList();
            assertEquals(List.of("red text"), out);
        }
    }

    @Nested
    @FusionSupport(modules = TruncatedSettings.class)
    class Truncated {
        @Test
        void truncatesLargeOutputs(@Fusion final CodingAgentConfiguration configuration) {
            final var lines = Stream.iterate(0, i -> i + 1).limit(50).map(i -> "line-" + i);
            final var out = new GenericOutputProcessor(configuration)
                    .process("cat big.txt", lines)
                    .toList();

            assertEquals(11, out.size());
            assertEquals("line-0", out.get(0));
            assertTrue(out.get(6).startsWith("[... "));
            assertTrue(out.get(6).endsWith(" lines truncated ...]"));
            assertEquals("line-49", out.get(10));
        }
    }

    public static class NormalSettings extends ConfigurationTestModule {
        public NormalSettings() {
            super(Map.of(
                    "yca.storageHome",
                    "x",
                    "yca.workingDir",
                    ".",
                    "yca.optimize.enabled",
                    "true",
                    "yca.optimize.genericMaxLines",
                    "400",
                    "yca.optimize.maxEntries",
                    "20",
                    "yca.optimize.disabled",
                    ""));
        }
    }

    public static class TruncatedSettings extends ConfigurationTestModule {
        public TruncatedSettings() {
            super(Map.of(
                    "yca.storageHome",
                    "x",
                    "yca.workingDir",
                    ".",
                    "yca.optimize.enabled",
                    "true",
                    "yca.optimize.genericMaxLines",
                    "10",
                    "yca.optimize.maxEntries",
                    "20",
                    "yca.optimize.disabled",
                    ""));
        }
    }
}
