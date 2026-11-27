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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SkillDocumentTest {
    @Test
    void splitsOnlyClosedLeadingFrontMatter() {
        for (final var newline : new String[] {"\n", "\r\n"}) {
            for (final var closing : new String[] {"---", "..."}) {
                final var document =
                        SkillDocument.parse("---" + newline + "name: test" + newline + closing + newline + "# Body");
                assertEquals("name: test" + newline, document.metadata());
                assertEquals("# Body", document.body());
            }
        }
        assertEquals("# Body", SkillDocument.parse("\uFEFF---\n---\n# Body").body());
        assertEquals("", SkillDocument.parse("---\nname: test\n---").body());
    }

    @Test
    void preservesDocumentsWithoutCompleteFrontMatter() {
        for (final var source : new String[] {
            "",
            "# Body\n\n**bold**",
            "---\nname: test",
            "# Body\n---\ntext",
            "---\nname: test\n---not a delimiter\n# Body"
        }) {
            final var document = SkillDocument.parse(source);
            assertNull(document.metadata());
            assertEquals(source, document.body());
        }
    }
}
