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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class JsOutputProcessorTest {
    @Nested
    @FusionSupport(modules = NormalSettings.class)
    class Normal {

        private JsOutputProcessor processor;

        @BeforeEach
        void init(@Fusion final CodingAgentConfiguration configuration) {
            processor = new JsOutputProcessor(configuration);
        }

        @Test
        void supportsFamilies() {
            assertTrue(processor.supports("tsc --noEmit"));
            assertTrue(processor.supports("eslint src --ext .ts"));
            assertTrue(processor.supports("biome check ."));
            assertTrue(processor.supports("next build"));
            assertFalse(processor.supports("npm test"));
            assertFalse(processor.supports("git status"));
        }

        @Test
        void groupsTscErrorsPerFile() {
            final var out = processor
                    .process(
                            "tsc --noEmit",
                            List.of(
                                    "src/a.ts(5,10): error TS2322: Type 'string' is not assignable to type 'number'.",
                                    "src/a.ts(8,1): error TS2339: Property 'x' does not exist on type 'Y'.",
                                    "src/b.ts(2,2): error TS2554: Expected 2 arguments, but got 1.")
                                    .stream())
                    .toList();
            assertEquals(
                    List.of(
                            "src/a.ts (2):",
                            "  L5:10 error TS2322: Type 'string' is not assignable to type 'number'.",
                            "  L8:1 error TS2339: Property 'x' does not exist on type 'Y'.",
                            "src/b.ts (1):",
                            "  L2:2 error TS2554: Expected 2 arguments, but got 1."),
                    out);
        }

        @Test
        void groupsEslintViolationsByRule() {
            final var out = processor
                    .process(
                            "eslint src",
                            List.of(
                                    "src/a.ts:1:1  error  'x' is defined but never used "
                                            + " @typescript-eslint/no-unused-vars",
                                    "src/a.ts:4:1  error  Missing semicolon  semi")
                                    .stream())
                    .toList();
            assertTrue(out.contains("src/a.ts (2):"));
            assertTrue(out.stream().anyMatch(l -> l.contains("@typescript-eslint/no-unused-vars")));
            assertTrue(out.stream().anyMatch(l -> l.contains("semi")));
        }

        @Test
        void keepsSummaryLines() {
            final var out = processor
                    .process(
                            "tsc --noEmit",
                            List.of(
                                    "error TS18003: No inputs were found in config file 'tsconfig.json'.",
                                    "src/a.ts(1,1): error TS2322: Type 'string' is not assignable.")
                                    .stream())
                    .toList();
            assertTrue(out.contains("error TS18003: No inputs were found in config file 'tsconfig.json'."));
            assertTrue(out.stream().anyMatch(l -> l.contains("src/a.ts")));
        }
    }

    @Nested
    @FusionSupport(modules = CappedSettings.class)
    class Capped {
        @Test
        void capsEntriesAtMaxEntries(@Fusion final CodingAgentConfiguration configuration) {
            final var processor = new JsOutputProcessor(configuration);
            final var lines = new ArrayList<String>();
            for (var i = 0; i < 10; i++) {
                lines.add("src/f" + i + ".ts(1,1): error TS1000: e" + i);
            }
            final var out = processor.process("tsc --noEmit", lines.stream()).toList();
            assertTrue(out.size() <= 7); // 3 files + truncation notes
            assertTrue(out.stream().anyMatch(l -> l.contains("more")));
        }
    }

    public static class NormalSettings extends ConfigurationTestModule {
        public NormalSettings() {
            super(Map.of("yca.optimize.maxEntries", "20"));
        }
    }

    public static class CappedSettings extends ConfigurationTestModule {
        public CappedSettings() {
            super(Map.of("yca.optimize.maxEntries", "3"));
        }
    }
}
