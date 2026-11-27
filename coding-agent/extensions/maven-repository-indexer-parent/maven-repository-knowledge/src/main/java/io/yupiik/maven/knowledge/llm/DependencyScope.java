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
package io.yupiik.maven.knowledge.llm;

import java.util.Locale;
import java.util.Map;

/**
 * Maven dependency scope, exposed so the project-deps resolution can surface every scope (not only compile)
 * and order the deduplicated coordinates from the most to the least relevant ({@link #order()}).
 */
public enum DependencyScope {

    /** {@code provided} - available at compile time but shipped by the runtime container. */
    PROVIDED(0),

    /** {@code compile} - the default scope. */
    COMPILE(1),

    /** {@code test} - only used by tests. */
    TEST(2),

    /** {@code runtime} - needed at runtime but not at compile time. */
    RUNTIME(3),

    /** {@code system} - resolved from the local filesystem. */
    SYSTEM(4),

    /** {@code import} - used to import a dependencyManagement BOM. */
    IMPORT(5);

    /** Stable priority used to sort the deduplicated coordinates, lower means "handle first". */
    private final int priority;

    DependencyScope(final int priority) {
        this.priority = priority;
    }

    public int order() {
        return priority;
    }

    private static final Map<String, DependencyScope> BY_NAME = Map.of(
            "provided", PROVIDED,
            "compile", COMPILE,
            "test", TEST,
            "runtime", RUNTIME,
            "system", SYSTEM,
            "import", IMPORT);

    /**
     * Parse a raw Maven scope string. Unknown/blank values map to {@code RUNTIME}, which sorts after
     * {@code TEST} so de-duplicated coordinates still land in the "other" tail (after the important scopes).
     */
    public static DependencyScope parse(final String raw) {
        return BY_NAME.getOrDefault(raw == null ? "" : raw.toLowerCase(Locale.ROOT), RUNTIME);
    }
}
