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
package io.yupiik.ai.acp.protocol;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import java.util.Map;

@JsonModel
public record Content(
        String type,
        String text,
        String uri,
        String name,
        String title,
        String description,
        String mimeType,
        @JsonOthers Map<String, Object> extensions) {
    public static Content of(
            final String type,
            final String text,
            final String uri,
            final String name,
            final String title,
            final String description,
            final String mimeType) {
        return new Content(type, text, uri, name, title, description, mimeType, null);
    }

    public static Content text(final String value) {
        return new Content("text", value, null, null, null, null, null, null);
    }
}
