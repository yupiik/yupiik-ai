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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.llm.Message;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextMeasurementTest {
    @Test
    void equalityUsesRealCountAndOnlyPendingTokensReceiveSafetyPadding() {
        final var measurement = new ContextMeasurement();
        final var base = new ContextMeasurement.Payload("p:m", "tools", List.of("system", "user"), 200);
        measurement.observe(base, 80);
        final var exact = measurement.measure(base);
        assertEquals(80, exact.tokens());
        assertEquals(80, exact.budgetTokens());
        assertEquals("provider-reported", exact.source());
        final var extension = measurement.measure(
                new ContextMeasurement.Payload("p:m", "tools", List.of("system", "user", "reply"), 250));
        assertEquals(100, extension.tokens());
        assertEquals(104, extension.budgetTokens());
        assertEquals(20, extension.pendingTokens());
        assertEquals("provider-anchored", extension.source());
    }

    @Test
    void equalSizedRewriteAndToolChangesAreNotAnExactObservation() {
        final var measurement = new ContextMeasurement();
        measurement.observe(new ContextMeasurement.Payload("p:m", "tools", List.of("old"), 200), 80);
        final var rewritten = measurement.measure(new ContextMeasurement.Payload("p:m", "tools", List.of("new"), 200));
        assertEquals("calibrated", rewritten.source());
        assertEquals(-1, rewritten.lastInputTokens());
        assertEquals(80, rewritten.tokens());
        assertEquals(
                "calibrated",
                measurement
                        .measure(new ContextMeasurement.Payload("p:m", "changed", List.of("old"), 200))
                        .source());
        assertEquals(
                "heuristic",
                measurement
                        .measure(new ContextMeasurement.Payload("other:model", "tools", List.of("old"), 200))
                        .source());
    }

    @Test
    void reductionRetainsCorrectionAndMissingUsageDoesNotEraseIt() {
        final var measurement = new ContextMeasurement();
        final var base = new ContextMeasurement.Payload("p:m", "tools", List.of("old"), 200);
        measurement.observe(base, 80);
        measurement.invalidate();
        final var reduced = new ContextMeasurement.Payload("p:m", "tools", List.of("summary"), 50);
        assertEquals(20, measurement.measure(reduced).tokens());
        measurement.observe(reduced, 0);
        assertEquals(20, measurement.measure(reduced).tokens());
        assertEquals("calibrated", measurement.latest().source());
    }

    @Test
    void payloadCopiesItsShapeAndSubagentsHaveIndependentAnchors() {
        final var messages = new ArrayList<>(List.of("old"));
        final var payload = new ContextMeasurement.Payload("p:m", "tools", messages, 200);
        messages.set(0, "changed");
        assertEquals(List.of("old"), payload.messages());
        final var parent = new UsageTracker(null, null, null, Clock.systemUTC());
        final var history = List.of(Message.user("hello"));
        parent.anchor(100, parent.contextPayload(history, 0));
        final var child = new SubagentUsageTracker(parent, "child");
        assertEquals("heuristic", child.contextMeasurement(history, 0).source());
        assertTrue(child.effectiveContext(history, 0) != 100);
        child.anchor(50, child.contextPayload(history, 0));
        assertEquals(50, child.effectiveContext(history, 0));
        assertEquals(100, parent.effectiveContext(history, 0));
    }
}
