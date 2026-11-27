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

/**
 * One named slice of an artifact's acquired knowledge. Short names are stored without extension; the writer uses
 * them to build {@code <artifactId>-<version>-knowledge-<shortName>.md} files. For example a javadoc-only artifact is
 * split into {@code javadoc-<qualifiedClassName>} parts and sources are chunked into {@code sources-0},
 * {@code sources-1}, ...
 *
 * @param shortName sibling short name (no extension, no {@code -knowledge-} prefix)
 * @param content   markdown/text body for this part
 */
public record ContentPart(String shortName, String content) {

    public static ContentPart of(final String shortName, final String content) {
        return new ContentPart(shortName, content);
    }
}
