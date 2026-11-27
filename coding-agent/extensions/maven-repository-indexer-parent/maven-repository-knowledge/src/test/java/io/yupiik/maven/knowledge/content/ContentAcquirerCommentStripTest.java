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
package io.yupiik.maven.knowledge.content;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContentAcquirerCommentStripTest {

    @Test
    void stripsAllCommentsButKeepsCode() {
        final var src = """
                /*
                 * Copyright (c) 2026 Example
                 * Licensed under the Apache License, Version 2.0.
                 */
                package a.b;
                // line comment
                /**
                 * Returns the value. <a href="x">link</a> prose.
                 * @param in the input
                 */
                public String get(final String in) { // trailing
                    return "literal // not a comment /* still literal */";
                }
                """;
        final var stripped = ContentAcquirer.stripComments(src);
        assertFalse(stripped.contains("Copyright"), "license header must be dropped");
        assertFalse(stripped.contains("Apache License"), "license terms must be dropped");
        assertTrue(stripped.contains("package a.b;"), "package declaration must be kept");
        assertTrue(stripped.contains("public String get"), "method signature must be kept");
        assertFalse(stripped.contains("<a href"), "javadoc prose must be dropped");
        assertFalse(stripped.contains("@param"), "javadoc tags must be dropped");
        assertFalse(stripped.contains("line comment"), "line comments must be dropped");
        assertTrue(stripped.contains("literal // not a comment"), "string literal must be kept intact");
        assertTrue(stripped.contains("/* still literal */"), "block-comment-looking text in a string is kept");
    }
}
