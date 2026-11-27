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
package io.yupiik.maven.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.maven.knowledge.content.ContentPart;
import io.yupiik.maven.knowledge.write.KnowledgeWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeFactoryTest {

    @TempDir
    Path tmp;

    private static final Gav WIDGET = Gav.of("com.example", "widget", "3");

    private Path indexPath() {
        return tmp.resolve("com/example/widget/3/widget-3-knowledge-index.md");
    }

    @Test
    void writesIndexSiblingsAndApi() throws Exception {
        final var factory = new KnowledgeFactory(new KnowledgeWriter(tmp));
        factory.writeArtifact(
                WIDGET,
                List.of(ContentPart.of("sources-0", "package com.example.widget; public class Widget{}")),
                "Artifact API:\n### com.example.widget.Widget\n- kind: class\n",
                "",
                List.of("com.example.widget.Widget"),
                List.of());
        final var dir = tmp.resolve("com/example/widget/3");
        assertTrue(Files.isRegularFile(dir.resolve("widget-3-knowledge-index.md")));
        assertTrue(Files.isRegularFile(dir.resolve("widget-3-knowledge-sources-0.md")));
        assertTrue(Files.isRegularFile(dir.resolve("widget-3-knowledge-api.md")));
        assertTrue(Files.isRegularFile(dir.resolve("widget-3-knowledge-api.md")), "api always written");

        final var index = Files.readString(indexPath());
        assertTrue(index.startsWith("---\nname: widget\n"), "frontmatter name");
        assertTrue(index.contains("## Entry points"), "entry points section");
        assertTrue(index.contains("- `com.example.widget.Widget`"), "entry point FQN");
        assertTrue(index.contains("## Knowledge"), "knowledge section");
        assertTrue(index.contains("- `api`"), "api sibling referenced");
        assertTrue(index.contains("- `sources-0`"), "sources-0 sibling referenced");
        assertFalse(index.contains("## Skill"), "no Skill notice without skills");
    }

    @Test
    void foldsModuleConfigIntoIndex() throws Exception {
        final var factory = new KnowledgeFactory(new KnowledgeWriter(tmp));
        factory.writeArtifact(
                WIDGET,
                List.of(),
                "",
                "## Mojo help\n- `project` default=${project.basedir} - the project dir",
                List.of(),
                List.of());
        final var index = Files.readString(indexPath());
        assertTrue(index.contains("## Mojo help"), "module config must be folded into the index");
        assertTrue(index.contains("`project`"), "mojo parameter present");
    }

    @Test
    void writesSkillSiblingsAndSkillIndexWhenProvided() throws Exception {
        final var factory = new KnowledgeFactory(new KnowledgeWriter(tmp));
        factory.writeArtifact(
                WIDGET,
                List.of(),
                "",
                "",
                List.of(),
                List.of(new KnowledgeFactory.SkillDoc("usage", "---\nname: usage\n---\n# usage\n")));
        final var dir = tmp.resolve("com/example/widget/3");
        assertTrue(Files.isRegularFile(dir.resolve("widget-3-knowledge-skill-usage.md")), "skill file written");
        assertTrue(Files.isRegularFile(dir.resolve("widget-3-knowledge-skill.md")), "skill index written");
        final var index = Files.readString(indexPath());
        assertTrue(index.contains("## Skill"), "Skill notice present when skills exist");
    }

    @Test
    void neverOverwritesHandWrittenSkill() throws Exception {
        final var writer = new KnowledgeWriter(tmp);
        final var factory = new KnowledgeFactory(writer);
        // simulate a hand-written skill already present
        Files.createDirectories(tmp.resolve("com/example/widget/3"));
        Files.writeString(tmp.resolve("com/example/widget/3/widget-3-knowledge-skill-mine.md"), "# handwritten\n");
        factory.writeArtifact(
                WIDGET,
                List.of(),
                "",
                "",
                List.of(),
                List.of(new KnowledgeFactory.SkillDoc("mine", "---\n---\ngenerated")));
        final var file = Files.readString(tmp.resolve("com/example/widget/3/widget-3-knowledge-skill-mine.md"));
        assertEquals("# handwritten\n", file, "hand-written file must survive regeneration");
    }

    @Test
    void apiFallsBackToNoSurfaceNotice() throws Exception {
        final var factory = new KnowledgeFactory(new KnowledgeWriter(tmp));
        factory.writeArtifact(WIDGET, List.of(), "", "", List.of(), List.of());
        final var api = Files.readString(tmp.resolve("com/example/widget/3/widget-3-knowledge-api.md"));
        assertTrue(api.contains("No public API detected."), "blank api written back as a notice");
    }
}
