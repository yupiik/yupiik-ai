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
package io.yupiik.llm.client.codex.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.test.TestJsonMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** JSON round-trip of the Codex session fields, including the api key, provenance and last refresh. */
@ExtendWith(TestJsonMapper.class)
class CodexTokenStoreTest {

    @Test
    void roundTripsNewFields(final JsonMapper mapper) {
        final CodexTokenStore store = new CodexTokenStore("acc", "ref", "account", 123L, "id")
                .withProvenance("https://auth.openai.com", "app_EMoamEEZ73f0CkXaXp7hrann")
                .withApiKey("openai-api-key-1");
        final String json = mapper.toString(store);
        Assertions.assertTrue(json.contains("openai_api_key"), json);
        final CodexTokenStore back = mapper.fromString(CodexTokenStore.class, json);
        assertEquals("acc", back.accessToken());
        assertEquals("openai-api-key-1", back.openaiApiKey());
        assertEquals("https://auth.openai.com", back.issuer());
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", back.clientId());
    }

    @Test
    void defaultFieldsAreNull(final JsonMapper mapper) {
        final CodexTokenStore store = new CodexTokenStore("acc", null, "a", 1L, null);
        assertNull(store.openaiApiKey());
        assertNull(store.issuer());
        assertNull(store.clientId());
        assertNull(store.lastRefreshMillis());
        final CodexTokenStore back = mapper.fromString(CodexTokenStore.class, mapper.toString(store));
        assertNull(back.openaiApiKey());
        assertNull(back.issuer());
    }
}
