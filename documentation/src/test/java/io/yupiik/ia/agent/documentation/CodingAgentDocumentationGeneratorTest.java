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
package io.yupiik.ia.agent.documentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.documentation.CliDocumentationGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingAgentDocumentationGeneratorTest {
    @Test
    void cliDiscoveryDoesNotIncludeCompanionCommands(@TempDir final Path root) throws Exception {
        new CliDocumentationGenerator(root, Map.of("outputBase", root.toString())).run();

        final var index = Files.readString(root.resolve("index.adoc"));
        for (final var command : new String[] {"cli", "run", "tui", "web", "acp"}) {
            assertTrue(index.contains("== " + command + "\n"), index);
            assertTrue(Files.isRegularFile(root.resolve(command + ".adoc")), command);
        }
        for (final var command : new String[] {"serve", "publish"}) {
            assertFalse(index.contains("== " + command + "\n"), index);
            assertFalse(Files.exists(root.resolve(command + ".adoc")), command);
        }
    }

    @Test
    void generatesToolDescriptionsAndOtherReferencesUnderExplicitSourceRoot(@TempDir final Path root) throws Exception {
        final var source = Files.createDirectory(root.resolve("site"));
        new CodingAgentDocumentationGenerator(source, Map.of("output", "references")).run();

        final var output = source.resolve("references");
        final var tools = Files.readString(output.resolve("embedded-tools.adoc"));
        assertTrue(tools.contains("=== `bash`\n\n"), tools);
        assertTrue(tools.contains("=== `read`\n\n"), tools);
        assertTrue(tools.contains("NOTE: Read-only tool: it never modifies the working copy."), tools);
        assertTrue(tools.contains(".JSON schema\n====\n[source,json]\n----\n"), tools);
        assertFalse(tools.contains("No description."), tools);
        assertTrue(Files.readString(output.resolve("slash-commands.adoc")).contains("/tools"));
        assertTrue(Files.readString(output.resolve("prompt-helpers.adoc")).contains("{{"));
        assertFalse(Files.exists(root.resolve("references")), "Outputs must stay under the explicit source root");
    }
}
