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
package io.yupiik.ia.agent.core.tools.builtin.bashoverride;

import java.util.regex.Pattern;

/** Statically compiled patterns shared by the read-only bash reimplementations (avoids per-call recompilation). */
final class BashRegex {
    private BashRegex() {}

    /** A non-negative integer, eg. {@code 0}, {@code 12}. */
    static final Pattern NON_NEG_INT = Pattern.compile("\\d+");

    /** {@code sed -n 'a,bp'} range print. */
    static final Pattern SED_RANGE = Pattern.compile("\\d+,\\d+p");

    /** {@code wc} flag group made only of {@code l}, {@code c}, {@code w}, eg. {@code -lcw}. */
    static final Pattern WC_FLAGS = Pattern.compile("-[lcw]+");

    /** A {@code wc} flag group containing a specific letter: {@code l}, {@code c}, or {@code w}. */
    static final Pattern WC_FLAGS_CONTAIN_L = Pattern.compile(".*l.*");

    static final Pattern WC_FLAGS_CONTAIN_C = Pattern.compile(".*c.*");
    static final Pattern WC_FLAGS_CONTAIN_W = Pattern.compile(".*w.*");

    /** A find {@code -type} target: {@code f} or {@code d}. */
    static final Pattern FIND_TYPE = Pattern.compile("[fd]");
}
