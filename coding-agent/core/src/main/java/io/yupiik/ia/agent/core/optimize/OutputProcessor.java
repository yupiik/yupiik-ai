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
package io.yupiik.ia.agent.core.optimize;

import java.util.Set;
import java.util.stream.Stream;

public interface OutputProcessor {

    boolean supports(String command);

    Stream<String> process(String command, Stream<String> lines);

    Set<String> names();

    /**
     * Whether this processor may run on results of the given tool. Processors target shell output by
     * default; tools with their own truncation discipline (read, grep, ...) are left alone so
     * their outputs are not re-truncated a second time.
     */
    default boolean appliesToTool(final String toolName) {
        return "bash".equals(toolName);
    }

    default String displayName() {
        final var simple = getClass().getSimpleName();
        final var fusion = simple.indexOf("$Fusion");
        return fusion < 0 ? simple : simple.substring(0, fusion);
    }

    default int order() {
        return 100;
    }
}
