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
package io.yupiik.maven.knowledge.manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.skill.SkillWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestWriterTest {

    private static final Gav GAV = Gav.of("com.example", "widget", "3");

    @Test
    void inlinesSimpleSkillsAndReferencesLargeOnes(@TempDir final Path tmp) throws Exception {
        final var writer = new SkillWriter(tmp);
        final var simple = "---\nname: simple\ndescription: brief\n---\n# Simple\n\nShort body.";
        final var large = "---\nname: large\ndescription: big\n---\n# Large\n\n"
                + "x".repeat(ManifestWriter.INLINE_MAX_CHARS + 100);

        writer.write(GAV, "simple", simple);
        writer.write(GAV, "large", large);

        final var manifestWriter = new ManifestWriter(tmp);
        manifestWriter.write(GAV, List.of("simple", "large"));

        final var manifestText = Files.readString(manifestWriter.manifestPath(GAV));
        // the simple skill is inlined verbatim (its body is present, no reference marker)
        assertTrue(manifestText.contains("Simple"), "simple skill body should be inlined");
        assertTrue(manifestText.contains("Short body."), "simple skill content should be inlined");
        assertFalse(manifestText.contains("### simple\n\nReferenced from"), "simple skill must not be referenced");
        // the large skill is referenced with a relative-to-manifest path, not inlined
        assertTrue(
                manifestText.contains("### large\n\nReferenced from `yca/large/SKILL.md`."),
                "large skill should be referenced relative to the manifest");
        assertFalse(manifestText.contains("x".repeat(50)), "large skill body must not be inlined");
        // the relative reference target resolves to the written skill file
        assertTrue(Files.isRegularFile(tmp.resolve("com/example/widget/3/yca/large/SKILL.md")));
    }
}
