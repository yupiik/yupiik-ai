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
package io.yupiik.ia.agent.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class RunResultContractTest {
    @TempDir
    Path temp;

    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @Fusion
        RunCommand.RunConf configuration;

        private String defaultCorrectionPrompt() {
            return configuration.resultCorrectionPrompt();
        }

        @Test
        void validatesOnlyActualNonblankStringFields(final JsonMapper mapper) throws Exception {
            final var file = temp.resolve("result.json");
            final var contract =
                    new RunResultContract(mapper, file, "summary|failure, evidence", 1000, defaultCorrectionPrompt());
            for (final var json : List.of(
                    "{}",
                    "{\"summary\":\" \"}",
                    "{\"summary\":true,\"evidence\":\"ok\"}",
                    "{\"summary\":\"ok\",\"evidence\":null}",
                    "{\"summary\":\"ok\",\"evidence\":{}}")) {
                Files.writeString(file, json);
                assertNotNull(contract.validationError(), json);
            }
            for (final var field : List.of("summary", "failure")) {
                Files.writeString(file, "{\"" + field + "\":\"actual result\",\"evidence\":\"checked\"}");
                assertNull(contract.validationError());
            }
            assertTrue(contract.correctionPrompt("missing field").contains("summary|failure"));
        }

        @Test
        void rejectsMissingNonregularMalformedAndOversizedArtifacts(final JsonMapper mapper) throws Exception {
            final var file = temp.resolve("result.json");
            final var contract = new RunResultContract(mapper, file, "", 32, defaultCorrectionPrompt());
            assertNotNull(contract.validationError());
            Files.createDirectory(file);
            assertNotNull(contract.validationError());
            Files.delete(file);
            for (final var json : List.of(
                    "",
                    "null",
                    "[]",
                    "\"text\"",
                    "{oops}",
                    "{} {}",
                    "{} , {}",
                    "{}],{}",
                    "{\"nested\":{}} {}",
                    "{} trailing",
                    "{\"long\":\"" + "x".repeat(32) + "\"}")) {
                Files.writeString(file, json);
                assertNotNull(contract.validationError(), json);
            }
            Files.write(file, new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}'});
            assertNotNull(contract.validationError());
            Files.writeString(file, "{}");
            assertNull(new RunResultContract(mapper, file, "", 2, defaultCorrectionPrompt()).validationError());
            assertNotNull(new RunResultContract(mapper, file, "", 1, defaultCorrectionPrompt()).validationError());
        }

        @Test
        void mapperHandlesNestedValuesAndEscapedObjectDelimiters(final JsonMapper mapper) throws Exception {
            final var file = temp.resolve("result.json");
            final var contract = new RunResultContract(mapper, file, "summary", 1000, defaultCorrectionPrompt());
            for (final var summary : List.of("{} {}", "quoted \"}\" and {", "escaped \\", "unicode \u00e9")) {
                Files.writeString(
                        file,
                        " \n"
                                + mapper.toString(Map.of(
                                        "summary",
                                        summary,
                                        "nested",
                                        Map.of("entries", List.of(Map.of("value", "}"), true, 42))))
                                + "\t\n");
                assertNull(contract.validationError(), summary);
            }
        }

        @Test
        void rejectsSymlinksInFileAndParentSegments(final JsonMapper mapper) throws Exception {
            final var directory = Files.createDirectory(temp.resolve("real"));
            final var result = Files.writeString(directory.resolve("result.json"), "{}");
            final var fileLink = Files.createSymbolicLink(temp.resolve("file-link"), result);
            final var directoryLink = Files.createSymbolicLink(temp.resolve("dir-link"), directory);
            assertNotNull(
                    new RunResultContract(mapper, fileLink, "", 1000, defaultCorrectionPrompt()).validationError());
            assertNotNull(new RunResultContract(
                            mapper, directoryLink.resolve("result.json"), "", 1000, defaultCorrectionPrompt())
                    .validationError());
        }

        @Test
        void rejectsInvalidConfiguration(final JsonMapper mapper) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RunResultContract(mapper, temp, "", 0, defaultCorrectionPrompt()));
            for (final var fields : List.of("summary,", "|failure", "summary||failure", "summary, ,failure")) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new RunResultContract(mapper, temp, fields, 1000, defaultCorrectionPrompt()));
            }
        }

        @Test
        void defaultPromptPreservesCorrectionInstructionsAndLiteralData(final JsonMapper mapper) {
            final var file = temp.resolve("{{systemProperty user.home}} & <result>.json");
            final var fields = "summary|failure, {{env SECRET}}&<evidence>";
            final var error = "missing {{file}} & <field>";
            final var contract = new RunResultContract(mapper, file, fields, 1000, defaultCorrectionPrompt());
            assertEquals(
                    "Completion artifact validation failed: " + error + ".\n"
                            + "Write or correct the JSON object at " + file.toAbsolutePath()
                            + " (maximum 1000 bytes).\n"
                            + "Required nonblank string fields (each group requires one alternative): "
                            + "[summary|failure, {{env SECRET}}&<evidence>].\n"
                            + "Repair only this artifact from the existing task context and actual evidence; do not fabricate results. "
                            + "Do not repeat implementation, tests, commits, pushes, or publication. "
                            + "This is the only correction attempt. A chat answer does not replace the artifact.",
                    contract.correctionPrompt(error));
        }
    }

    @Nested
    @FusionSupport(modules = CustomTemplateModule.class)
    class CustomTemplate {
        @Test
        void configuredTemplateRendersAllVariablesWithoutReinterpretingData(
                final JsonMapper mapper, @Fusion final RunCommand.RunConf conf) {
            final var file = temp.resolve("{{validationError}}.json");
            final var contract = new RunResultContract(
                    mapper, file, "{{file}}|summary, evidence", 123, conf.resultCorrectionPrompt());
            assertEquals(
                    "{{env SECRET}};" + file.toAbsolutePath() + ";123;[{{file}}|summary, evidence]",
                    contract.correctionPrompt("{{env SECRET}}"));
            assertEquals(
                    "another error;" + file.toAbsolutePath() + ";123;[{{file}}|summary, evidence]",
                    contract.correctionPrompt("another error"));
        }
    }

    @Test
    void invalidTemplateFailsAtConstruction(final JsonMapper mapper) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RunResultContract(mapper, temp, "", 1000, "{{#if file}}unclosed"));
        assertThrows(RuntimeException.class, () -> new RunResultContract(mapper, temp, "", 1000, "{{env SECRET}}"));
        assertThrows(
                RuntimeException.class,
                () -> new RunResultContract(mapper, temp, "", 1000, "{{systemProperty user.home}}"));
    }

    public static class DefaultsModule extends ConfigurationTestModule {
        public DefaultsModule() {
            super(Map.of("run.prompt", "task"));
        }
    }

    public static class CustomTemplateModule extends ConfigurationTestModule {
        public CustomTemplateModule() {
            super(Map.of(
                    "run.prompt", "task",
                    "run.result-correction-prompt",
                            "{{{validationError}}};{{{file}}};{{maxBytes}};{{{requiredFields}}}"));
        }
    }
}
