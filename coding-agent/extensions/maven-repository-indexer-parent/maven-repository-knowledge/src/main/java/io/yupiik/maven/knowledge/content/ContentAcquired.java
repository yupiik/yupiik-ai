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

import io.yupiik.maven.knowledge.Gav;

/**
 * The acquisition result for an artifact: the extracted content plus which source produced it. The
 * {@code content} string is what gets handed to the LLM or written as the knowledge base.
 */
public record ContentAcquired(Gav gav, ContentKind kind, String content) {

    public static ContentAcquired of(final Gav gav, final ContentKind kind, final String content) {
        return new ContentAcquired(gav, kind, content == null ? "" : content);
    }

    public static ContentAcquired none(final Gav gav) {
        return new ContentAcquired(gav, ContentKind.NONE, "");
    }

    public boolean present() {
        return kind != ContentKind.NONE && !content.isBlank();
    }
}
