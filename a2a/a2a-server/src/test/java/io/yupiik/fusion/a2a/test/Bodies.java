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
package io.yupiik.fusion.a2a.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts on a raw payload, for the cases the typed client cannot express: identifiers and timestamps are generated so a
 * response can rarely be compared as a whole.
 */
public final class Bodies {
    private Bodies() {
        // no-op
    }

    /**
     * @param body     a response payload.
     * @param snippets what it must contain.
     */
    public static void contains(final String body, final String... snippets) {
        for (final var snippet : snippets) {
            assertTrue(body.contains(snippet), () -> "Missing '" + snippet + "' in " + body);
        }
    }
}
