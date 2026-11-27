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
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

/**
 * A section of communication content: text, a file or a structured JSON value.
 * Exactly one of {@code text}, {@code raw}, {@code url} and {@code data} must be present.
 * The polymorphic data branch is captured with extensions so its explicit JSON null value
 * remains distinguishable from absence. Serialization uses the generated Fusion codec.
 *
 * @param text the textual content.
 * @param raw inline file content, base64 encoded.
 * @param url where the file content can be fetched.
 * @param metadata free form metadata about this part.
 * @param filename the file name when this part carries a file.
 * @param mediaType the media type of the content.
 * @param extensions the optional data branch and additional wire properties.
 */
@JsonModel
public record Part(
        @Property(documentation = "The textual content.") String text,

        @Property(documentation = "The inline file content, base64 encoded.")
        String raw,

        @Property(documentation = "Where the file content can be fetched.")
        String url,

        @Property(documentation = "Free form metadata about this part.")
        Map<String, Object> metadata,

        @Property(documentation = "The file name when the part carries a file.")
        String filename,

        @Property(documentation = "The media type of the content.")
        String mediaType,

        @JsonOthers Map<String, Object> extensions) {
    public Part {
        if ((text == null ? 0 : 1)
                        + (raw == null ? 0 : 1)
                        + (url == null ? 0 : 1)
                        + (extensions != null && extensions.containsKey("data") ? 1 : 0)
                != 1) {
            throw new IllegalArgumentException("A part must contain exactly one content branch");
        }
    }

    public static Part of(
            final String text,
            final String raw,
            final String url,
            final Object data,
            final Map<String, Object> metadata,
            final String filename,
            final String mediaType) {
        return new Part(
                text,
                raw,
                url,
                metadata,
                filename,
                mediaType,
                data == null ? Map.of() : Collections.singletonMap("data", data));
    }

    public Object data() {
        return extensions == null ? null : extensions.get("data");
    }

    public boolean hasData() {
        return extensions != null && extensions.containsKey("data");
    }

    public boolean hasSingleContent() {
        return (text == null ? 0 : 1) + (raw == null ? 0 : 1) + (url == null ? 0 : 1) + (hasData() ? 1 : 0) == 1;
    }

    public static Part text(final String text) {
        return text("text/plain", text);
    }

    public static Part text(final String mediaType, final String text) {
        return new Part(text, null, null, null, null, mediaType, Map.of());
    }

    public static Part raw(final String mediaType, final byte[] content, final String filename) {
        return new Part(null, Base64.getEncoder().encodeToString(content), null, null, filename, mediaType, Map.of());
    }

    public static Part url(final String mediaType, final String url, final String filename) {
        return new Part(null, null, url, null, filename, mediaType, Map.of());
    }

    /** Select the data branch, including when its value is JSON null. */
    public static Part data(final Object data) {
        return new Part(null, null, null, null, null, "application/json", Collections.singletonMap("data", data));
    }

    public byte[] rawBytes() {
        return raw == null ? null : Base64.getDecoder().decode(raw);
    }
}
