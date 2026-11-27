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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.configuration.ModelVariantConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatCompletionRequestTuningTest {

    private static ChatCompletionRequest request() {
        return new ChatCompletionRequest(
                "test-model", List.of(Message.user("hi")), null, null, null, null, null, null, null, null);
    }

    private static ModelVariantConfiguration tuning(final Integer maxTokens) {
        return new ModelVariantConfiguration("default", null, null, null, maxTokens, "", "");
    }

    @Test
    void maxTokensSentinelIsMappedToNull() {
        // -1 is the documented "unset" sentinel on maxTokens; it must never reach the wire
        // as max_tokens:-1 (freeinference rejects it with "Invalid JSON or schema in request body")
        final var tuned = request().withTuning(tuning(-1));
        assertNull(tuned.maxTokens(), "maxTokens=-1 must be treated as unset (null)");

        final var zero = request().withTuning(tuning(0));
        assertNull(zero.maxTokens(), "maxTokens=0 must be treated as unset (null)");

        // a positive value still applies
        final var positive = request().withTuning(tuning(4096));
        assertEquals(Integer.valueOf(4096), positive.maxTokens());
    }

    @Test
    void explicitRequestMaxTokensWinsOverTuning() {
        final var req = new ChatCompletionRequest(
                "test-model",
                List.of(Message.user("hi")),
                null,
                null,
                null,
                null,
                null,
                Integer.valueOf(1024),
                null,
                null);
        final var tuned = req.withTuning(tuning(-1));
        assertEquals(Integer.valueOf(1024), tuned.maxTokens(), "an explicit request budget wins");
    }

    @Test
    void tuningTopPAndTopKAreAppliedPutIfAbsent() {
        final var tuning = new ModelVariantConfiguration("default", null, 0.95, 20.0, -1, "", "");
        final var tuned = request().withTuning(tuning);
        assertEquals(0.95, tuned.topP());
        assertEquals(20.0, tuned.others().get("top_k"));
    }

    @Test
    void tuningExtraObjectIsMergedPutIfAbsent() {
        final var tuning = new ModelVariantConfiguration(
                "default", null, null, null, -1, "", "{\"thinking\":{\"type\":\"enabled\"},\"top_k\":42}");
        final var tuned = request().withTuning(tuning);
        assertEquals(Map.of("type", "enabled"), tuned.others().get("thinking"));
        // top_k from extra is applied too
        assertEquals(42L, tuned.others().get("top_k"));
    }

    @Test
    void existingOthersValuesAreNotOverriddenByTuning() {
        final var req = new ChatCompletionRequest(
                "test-model",
                List.of(Message.user("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("top_k", 33.0));
        final var tuned =
                req.withTuning(new ModelVariantConfiguration("default", null, 0.95, 20.0, -1, "", "{\"top_k\":42}"));
        // existing others.top_k wins over both the typed tuning.topK AND the extra
        assertEquals(33.0, tuned.others().get("top_k"));
        // topP is a typed field, unrelated to others: it is applied (no existing request topP)
        assertEquals(0.95, tuned.topP());
    }

    @Test
    void malformedExtraIsSilentlySkipped() {
        final var tuning = new ModelVariantConfiguration("default", null, null, null, -1, "", "{not valid json");
        final var tuned = request().withTuning(tuning);
        assertNull(tuned.others(), "a malformed extra must be ignored, not fail the tuning");
        assertNull(tuned.maxTokens());
        final var bad = request().withTuning(new ModelVariantConfiguration("default", null, null, null, -1, "", "[]"));
        assertNull(bad.others(), "a non-object extra must be ignored");
    }

    @Test
    void noTuningLeavesRequestUntouched() {
        final var req = request().withTuning(null);
        assertNull(req.maxTokens());
        assertNull(req.others());
    }

    @Test
    void nestedAndArrayExtraParses() {
        final var tuning = new ModelVariantConfiguration(
                "default",
                null,
                null,
                null,
                -1,
                "",
                "{\"list\":[1,2,3],\"flag\":true,\"none\":null," + "\"inner\":{\"a\":\"b\",\"n\":-5,\"f\":1.5}}");
        final var tuned = request().withTuning(tuning);
        final var others = tuned.others();
        assertEquals(List.of(1L, 2L, 3L), others.get("list"));
        assertEquals(Boolean.TRUE, others.get("flag"));
        assertTrue(others.containsKey("none"));
        assertNull(others.get("none"));
        assertEquals(Map.of("a", "b", "n", -5L, "f", 1.5), others.get("inner"));
        assertFalse(others.isEmpty());
    }
}
