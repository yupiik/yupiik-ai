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
package io.yupiik.ia.agent.core.tools;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public interface EmbeddedTool {
    String name();

    String description(Path workdir);

    ToolCategory category();

    Map<String, Object> schema();

    default boolean readonly() {
        return false;
    }

    /** Projects availability for the explicitly supplied session root, without shared mutable state. */
    default boolean usable(final Path workdir) {
        Objects.requireNonNull(workdir, "workdir");
        return true;
    }

    ToolResult execute(Map<String, Object> args, ToolContext context);
}
