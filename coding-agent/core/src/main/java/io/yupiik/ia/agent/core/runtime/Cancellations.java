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
package io.yupiik.ia.agent.core.runtime;

import java.util.function.BooleanSupplier;

/**
 * Shared cancellation probes.
 *
 * <p>Cancellation is threaded as a probe and a probe is always a real supplier: {@code null} used
 * to mean "no cancellation" here and there, and it then blew up as an
 * {@code unexpected failure: java.lang.NullPointerException ... this.cancelled is null} in the
 * middle of a turn. Callers holding no interrupt flag - a standalone tool invocation, a
 * synchronous {@code /compact}, a background pass - pass {@link #NEVER} instead.</p>
 */
public final class Cancellations {
    /** A probe which never reports cancellation, for callers holding no interrupt flag. */
    public static final BooleanSupplier NEVER = () -> false;

    private Cancellations() {
        // no-op
    }
}
