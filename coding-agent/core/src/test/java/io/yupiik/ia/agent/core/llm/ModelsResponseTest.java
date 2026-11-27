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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
class ModelsResponseTest {
    @Test
    void flatVendorKeyIsRead(final JsonMapper mapper) {
        final var res = mapper.fromString(
                ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"owned_by\":\"x\",\"context_window\":8192}]}");
        final var window = res.data().get(0).contextWindow();
        assertEquals(8192, window.value());
        assertTrue(window.absolute());
        assertEquals(ModelsResponse.SourceType.DETECTED_ABSOLUTE, window.sourceType());
        assertEquals("/context_window", window.sourcePointer());
    }

    @Test
    void llamaCppMetaNCtxIsRead(final JsonMapper mapper) {
        final var res = mapper.fromString(ModelsResponse.class, """
                {"models":[{"name":"qwen3.8-27b","model":"qwen3.8-27b","modified_at":"","size":"","digest":"","type":"model","description":"","tags":[""],"capabilities":["completion"],"parameters":"","details":{"parent_model":"","format":"gguf","family":"","families":[""],"parameter_size":"","quantization_level":""}}],"object":"list","data":[{"id":"qwen3.8-27b","aliases":["qwen3.8-27b"],"tags":[],"object":"model","created":1788896358,"owned_by":"llamacpp","meta":{"vocab_type":2,"n_vocab":248320,"n_ctx":196608,"n_ctx_train":262144,"n_embd":5120,"n_params":27320697856,"size":16453443584,"ftype":"Q4_K - Medium"}}]}""");
        final var window = res.data().get(0).contextWindow();
        assertEquals(196608, window.value());
        assertTrue(window.absolute(), "meta.n_ctx is the enforced runtime KV-cache slot, a hard limit");
        assertEquals(ModelsResponse.SourceType.DETECTED_ABSOLUTE, window.sourceType());
        assertEquals("/meta/n_ctx", window.sourcePointer());
    }

    @Test
    void nCtxTrainIsUsedAsBestEffortUpperBound(final JsonMapper mapper) {
        final var small =
                mapper.fromString(ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"meta\":{\"n_ctx_train\":4096}}]}");
        final var smallWindow = small.data().get(0).contextWindow();
        assertEquals(4096, smallWindow.value());
        assertFalse(smallWindow.absolute(), "training metadata is relative, not a hard limit");
        final var big = mapper.fromString(
                ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"meta\":{\"n_ctx_train\":262144}}]}");
        final var bigWindow = big.data().get(0).contextWindow();
        assertEquals(262144, bigWindow.value());
        assertEquals("/meta/n_ctx_train", bigWindow.sourcePointer());
    }

    @Test
    void noVendorDataIsNull(final JsonMapper mapper) {
        final var res = mapper.fromString(ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"owned_by\":\"x\"}]}");
        assertNull(res.data().get(0).contextWindow());
        assertNull(res.data().get(0).maxTokens());
    }

    @Test
    void flatKeysHavePriorityOverNested(final JsonMapper mapper) {
        final var res = mapper.fromString(
                ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"context_length\":4096,\"meta\":{\"n_ctx\":8192}}]}");
        final var window = res.data().get(0).contextWindow();
        assertEquals(4096, window.value());
        assertEquals("/context_length", window.sourcePointer());
    }

    @Test
    void sglangContextLengthAndMaxOutputLengthAreRead(final JsonMapper mapper) {
        final var res = mapper.fromString(
                ModelsResponse.class,
                "{\"data\":[{\"id\":\"deepseek-v4-flash\",\"owned_by\":\"sglang\",\"context_length\":1000000,\"max_output_length\":393216}]}");
        final var window = res.data().get(0).contextWindow();
        assertEquals(1_000_000, window.value(), "context_length is a token total window");
        final var maxTokens = res.data().get(0).maxTokens();
        assertEquals(393_216, maxTokens.value(), "max_output_length is a token output cap");
        assertFalse(maxTokens.absolute(), "max_output_length is a gateway-specific field, not a hard limit");
        assertEquals(ModelsResponse.SourceType.DETECTED_RELATIVE, maxTokens.sourceType());
        assertEquals("/max_output_length", maxTokens.sourcePointer());
    }

    @Test
    void topProviderNestedWindowAndMaxTokensAreRead(final JsonMapper mapper) {
        final var res = mapper.fromString(
                ModelsResponse.class,
                "{\"data\":[{\"id\":\"kilo-auto/free\",\"owned_by\":\"kilo\",\"context_length\":256000,\"top_provider\":{\"context_length\":256000,\"max_completion_tokens\":32768}}]}");
        final var window = res.data().get(0).contextWindow();
        assertEquals(256_000, window.value(), "top_provider.context_length is a token total window");
        final var maxTokens = res.data().get(0).maxTokens();
        assertEquals(32_768, maxTokens.value(), "top_provider.max_completion_tokens is a token output cap");
        assertTrue(maxTokens.absolute());
        assertEquals("/top_provider/max_completion_tokens", maxTokens.sourcePointer());
    }

    @Test
    void topProviderIsUsedWhenNoFlatVendorValueExists(final JsonMapper mapper) {
        final var noFlat = mapper.fromString(
                ModelsResponse.class,
                "{\"data\":[{\"id\":\"m\",\"owned_by\":\"x\",\"top_provider\":{\"context_length\":131072,\"max_completion_tokens\":65536}}]}");
        final var window = noFlat.data().get(0).contextWindow();
        assertEquals(131_072, window.value(), "falls back to top_provider.context_length");
        final var maxTokens = noFlat.data().get(0).maxTokens();
        assertEquals(65_536, maxTokens.value(), "falls back to top_provider.max_completion_tokens");
    }

    @Test
    void maxInputAndMaxOutputTokensAreReadIndependently(final JsonMapper mapper) {
        // LiteLLM style metadata: the two caps are per-side limits, not a partition of the window
        final var res = mapper.fromString(ModelsResponse.class, """
                {"data":[{"id":"qwen3.8-27b","object":"model","created":1677610602,"owned_by":"openai","max_input_tokens":131072,"max_output_tokens":131072}],"object":"list"}""");
        final var model = res.data().get(0);
        final var window = model.contextWindow();
        assertEquals(131_072, window.value());
        assertTrue(window.absolute());
        assertEquals("/max_input_tokens", window.sourcePointer());
        final var maxTokens = model.maxTokens();
        assertEquals(131_072, maxTokens.value());
        assertTrue(maxTokens.absolute());
        assertEquals("/max_output_tokens", maxTokens.sourcePointer());
    }

    @Test
    void maxOutputTokensYieldsToDocumentedOutputCaps(final JsonMapper mapper) {
        final var res = mapper.fromString(ModelsResponse.class, """
                {"data":[{"id":"m","max_tokens":16384,"max_output_tokens":65536}]}""");
        final var maxTokens = res.data().get(0).maxTokens();
        assertEquals(16_384, maxTokens.value(), "the documented max_tokens cap wins over the LiteLLM field");
        assertEquals("/max_tokens", maxTokens.sourcePointer());
    }

    @Test
    void outputTokenLimitIsRelativeWhenNoDocumentedCapExists(final JsonMapper mapper) {
        final var res =
                mapper.fromString(ModelsResponse.class, "{\"data\":[{\"id\":\"m\",\"output_token_limit\":8192}]}");
        final var maxTokens = res.data().get(0).maxTokens();
        assertEquals(8_192, maxTokens.value());
        assertFalse(maxTokens.absolute());
        assertEquals(ModelsResponse.SourceType.DETECTED_RELATIVE, maxTokens.sourceType());
        assertEquals("/output_token_limit", maxTokens.sourcePointer());
    }

    @Test
    void reasoningCatalogMetadataIsRead(final JsonMapper mapper) {
        final var res = mapper.fromString(ModelsResponse.class, """
                {"data":[{"id":"gpt-6.1-sol","default_reasoning_level":"low",
                "supported_reasoning_levels":[
                  {"effort":"low","description":"Fast"},
                  {"effort":"xhigh"},
                  "ultra",
                  {"description":"missing effort"},
                  {"effort":" "}
                ]}]}""");
        final var model = res.data().get(0);
        assertEquals("low", model.defaultReasoningLevel());
        assertEquals(List.of("low", "xhigh", "ultra"), model.supportedReasoningLevels());
    }

    @Test
    void reasoningCatalogMetadataIsOptionalAndTolerant(final JsonMapper mapper) {
        final var absent = mapper.fromString(ModelsResponse.class, "{\"data\":[{\"id\":\"m\"}]}");
        assertNull(absent.data().get(0).defaultReasoningLevel());
        assertTrue(absent.data().get(0).supportedReasoningLevels().isEmpty());

        final var malformed = mapper.fromString(
                ModelsResponse.class,
                "{\"data\":[{\"id\":\"m\",\"default_reasoning_level\":42,"
                        + "\"supported_reasoning_levels\":\"high\"}]}");
        assertNull(malformed.data().get(0).defaultReasoningLevel());
        assertTrue(malformed.data().get(0).supportedReasoningLevels().isEmpty());
    }
}
