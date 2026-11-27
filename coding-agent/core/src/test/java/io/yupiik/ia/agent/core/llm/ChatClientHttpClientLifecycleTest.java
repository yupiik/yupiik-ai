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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.wiring.ProviderManager;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = ConfigurationTestModule.class)
class ChatClientHttpClientLifecycleTest {
    @Test
    void closeLeavesInjectedHttpClientOpenForItsOwner(
            final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        // ExtendedHttpClient does not close an explicitly supplied raw delegate.
        try (final var delegate = HttpClient.newHttpClient()) {
            final var httpClient = new TrackingHttpClient(delegate);
            try (httpClient) {
                try (final var client = new ChatClient(
                        configuration,
                        mapper,
                        new ProviderManager(configuration, mapper),
                        Clock.systemUTC(),
                        httpClient)) {
                    assertNotNull(client);
                    assertEquals(0, httpClient.closeCalls);
                }
                assertEquals(0, httpClient.closeCalls, "ChatClient must not close the caller-owned HTTP client");
            }
            assertEquals(1, httpClient.closeCalls, "the owner must close the HTTP client");
        }
    }

    private static class TrackingHttpClient extends ExtendedHttpClient {
        private int closeCalls;

        private TrackingHttpClient(final HttpClient delegate) {
            super(new ExtendedHttpClientConfiguration().setDelegate(delegate));
        }

        @Override
        public void close() {
            closeCalls++;
            super.close();
        }
    }
}
