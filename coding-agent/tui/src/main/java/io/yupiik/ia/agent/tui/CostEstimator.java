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
package io.yupiik.ia.agent.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

/**
 * Optional session cost estimation from a configured pricing table. Entries
 * use the form {@code model-substring=in/out} with USD per million tokens,
 * e.g. {@code gpt-4.1-mini=0.4/1.6}; the first entry whose pattern occurs in
 * the model name wins. Pure and terminal-free so it is unit testable.
 */
public final class CostEstimator {
    private record Price(Pattern model, double in, double out) {}

    private final List<Price> prices = new ArrayList<>();

    public static CostEstimator parse(final List<String> entries) {
        final var estimator = new CostEstimator();
        if (entries == null) {
            return estimator;
        }
        for (final var entry : entries) {
            final var separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                continue;
            }
            final var model = Pattern.compile(
                    Pattern.quote(entry.substring(0, separator).strip().toLowerCase(Locale.ROOT)));
            final var value = entry.substring(separator + 1);
            final var slash = value.indexOf('/');
            try {
                final var in = Double.parseDouble(
                        value.substring(0, slash < 0 ? value.length() : slash).strip());
                final var out = slash < 0
                        ? in
                        : Double.parseDouble(value.substring(slash + 1).strip());
                estimator.prices.add(new Price(model, in, out));
            } catch (final NumberFormatException e) {
                // ignore malformed entries
            }
        }
        return estimator;
    }

    /**
     * @return the estimated cost in USD, or empty when the model has no
     *         configured price
     */
    public OptionalDouble cost(final String model, final long promptTokens, final long completionTokens) {
        if (model == null || model.isBlank() || prices.isEmpty()) {
            return OptionalDouble.empty();
        }
        final var name = model.toLowerCase(Locale.ROOT);
        for (final var price : prices) {
            if (price.model().matcher(name).find()) {
                return OptionalDouble.of(
                        promptTokens * price.in() / 1_000_000.0 + completionTokens * price.out() / 1_000_000.0);
            }
        }
        return OptionalDouble.empty();
    }

    public boolean isEmpty() {
        return prices.isEmpty();
    }
}
