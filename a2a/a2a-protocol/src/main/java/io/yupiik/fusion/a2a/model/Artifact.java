/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An output of a {@link Task}.
 * <p>
 * This is where the <em>results</em> of a task go - a report, a generated file, a structured answer - as opposed to
 * {@link Message}s which carry the conversation.
 *
 * @param artifactId  the identifier of the artifact, it must be unique within the task. It is also the key used to
 *                    append chunks to a streamed artifact.
 * @param name        a human readable name.
 * @param description a human readable description.
 * @param parts       the content, there must be at least one part.
 * @param metadata    free form metadata.
 * @param extensions  the URIs of the extensions which contributed to this artifact.
 */
@JsonModel
public record Artifact(
        @Property(
                documentation =
                        "The identifier of the artifact, it must be unique within the task. It is also the key used to append chunks to a streamed artifact.")
        String artifactId,

        @Property(documentation = "A human readable name.") String name,

        @Property(documentation = "A human readable description.")
        String description,

        @Property(documentation = "The content, there must be at least one part.")
        List<Part> parts,

        @Property(documentation = "Free form metadata.") Map<String, Object> metadata,

        @Property(documentation = "The URIs of the extensions which contributed to this artifact.")
        List<String> extensions) {
    /**
     * @param name the artifact name.
     * @param text the textual content.
     * @return a text artifact with a generated identifier.
     */
    public static Artifact text(final String name, final String text) {
        return of(name, List.of(Part.text(text)));
    }

    /**
     * @param name the artifact name.
     * @param data any JSON value.
     * @return a structured artifact with a generated identifier.
     */
    public static Artifact data(final String name, final Object data) {
        return of(name, List.of(Part.data(data)));
    }

    /**
     * @param name  the artifact name.
     * @param parts its content.
     * @return the artifact, with a generated {@code artifactId}.
     */
    public static Artifact of(final String name, final List<Part> parts) {
        return new Artifact(UUID.randomUUID().toString(), name, null, parts, null, null);
    }

    /**
     * @param id the identifier to use.
     * @return this artifact with {@code id} as {@code artifactId}, which is how consecutive chunks of a streamed
     * artifact are correlated.
     */
    public Artifact withId(final String id) {
        return new Artifact(id, name, description, parts, metadata, extensions);
    }
}
