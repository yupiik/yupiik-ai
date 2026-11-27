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
package io.yupiik.ia.agent.github.client;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Internal validation shared by GraphQL clients, not an externally supported API. */
final class GraphqlResponses {
    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]*");

    private GraphqlResponses() {}

    static String text(final String value) {
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    static String positive(final BigDecimal value) {
        return positiveDecimal(value);
    }

    // GitHub's BigInt review identity permits a numeric or decimal string representation.
    static String positiveDecimal(final Object value) {
        if (!(value instanceof Number || value instanceof String)
                || !POSITIVE_ID.matcher(value.toString()).matches()) {
            throw invalid();
        }
        return value.toString();
    }

    static int number(final BigDecimal value) {
        try {
            return Integer.parseInt(positive(value));
        } catch (final NumberFormatException e) {
            throw invalid();
        }
    }

    static int integer(final BigDecimal value) {
        if (value == null) {
            throw invalid();
        }
        try {
            return value.intValueExact();
        } catch (final ArithmeticException e) {
            throw invalid();
        }
    }

    static void repository(final String value) {
        if (value == null || !REPOSITORY_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Expected owner/repository");
        }
    }

    static String repositoryName(final String value) {
        final var name = text(value);
        if (!REPOSITORY_NAME.matcher(name).matches()) {
            throw invalid();
        }
        return name;
    }

    static void cursor(final String value) {
        if (value != null && (value.isBlank() || value.length() > 4096)) {
            throw new IllegalArgumentException("Invalid GraphQL cursor");
        }
    }

    static IllegalStateException invalid() {
        return new IllegalStateException("Invalid GitHub GraphQL response");
    }
}
