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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class CostEstimatorTest {
    @Test
    void emptyWhenNoPricing() {
        assertTrue(CostEstimator.parse(List.of()).isEmpty());
        assertTrue(CostEstimator.parse(null).isEmpty());
        final var estimator = CostEstimator.parse(List.of());
        assertEquals(OptionalDouble.empty(), estimator.cost("gpt-4", 1000, 1000));
    }

    @Test
    void parsesInAndOutPricing() {
        final var estimator = CostEstimator.parse(List.of("gpt-4.1=2.5/10"));
        assertFalse(estimator.isEmpty());
        // 1M input tokens at $2.5/Mtok + 1M output tokens at $10/Mtok
        assertEquals(
                12.5, estimator.cost("openai/gpt-4.1", 1_000_000, 1_000_000).getAsDouble(), 1e-9);
    }

    @Test
    void singlePriceAppliesToBothDirections() {
        final var estimator = CostEstimator.parse(List.of("claude=3"));
        assertEquals(6.0, estimator.cost("claude-sonnet", 1_000_000, 1_000_000).getAsDouble(), 1e-9);
    }

    @Test
    void modelSubstringMatching() {
        final var estimator = CostEstimator.parse(List.of("mini=1/2"));
        assertTrue(estimator.cost("gpt-4.1-mini-2025", 1_000_000, 0).isPresent());
        assertEquals(OptionalDouble.empty(), estimator.cost("gpt-4.1", 1_000_000, 0));
    }

    @Test
    void malformedEntriesIgnored() {
        final var estimator = CostEstimator.parse(List.of("noequals", "=5", "x=notanumber", "ok=1.5"));
        assertFalse(estimator.isEmpty());
        assertEquals(1.5, estimator.cost("ok", 1_000_000, 0).getAsDouble(), 1e-9);
    }

    @Test
    void zeroTokensCostNothing() {
        final var estimator = CostEstimator.parse(List.of("gpt=2/8"));
        assertEquals(0.0, estimator.cost("gpt", 0, 0).getAsDouble(), 1e-9);
    }
}
