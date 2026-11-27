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

/**
 * Maven coordinates: {@code groupId:artifactId:version}. Immutable and value-based.
 */
public record Gav(String groupId, String artifactId, String version) {

    public static Gav of(final String groupId, final String artifactId, final String version) {
        return new Gav(groupId, artifactId, version);
    }

    /**
     * Parse a {@code groupId:artifactId:version} string. The version is optional; when absent it is null.
     */
    public static Gav parse(final String coord) {
        final var parts = coord.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid GAV: '" + coord + "', expected groupId:artifactId[:version]");
        }
        final var version = parts.length >= 3 && !parts[2].isBlank() ? parts[2] : null;
        return new Gav(parts[0], parts[1], version);
    }

    public String coordinate() {
        if (version == null || version.isBlank()) {
            return groupId + ":" + artifactId;
        }
        return groupId + ":" + artifactId + ":" + version;
    }

    public String toPath() {
        return groupId.replace('.', '/') + '/' + artifactId + '/' + version;
    }
}
