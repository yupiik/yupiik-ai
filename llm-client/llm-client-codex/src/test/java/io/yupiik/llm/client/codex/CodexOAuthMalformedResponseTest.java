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
package io.yupiik.llm.client.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.llm.client.test.TestJsonMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Hardening coverage: a malformed OAuth token JSON must fail with a typed, diagnosable exception. */
@ExtendWith(TestJsonMapper.class)
class CodexOAuthMalformedResponseTest {

    private HttpServer issuer;
    private String issuerUrl;
    private ExtendedHttpClient client;
    private HttpClient delegate;

    @BeforeEach
    void start() throws IOException {
        issuer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuer.start();
        issuerUrl = "http://localhost:" + issuer.getAddress().getPort();
        delegate = HttpClient.newBuilder().build();
        client = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate));
    }

    @AfterEach
    void stop() {
        if (issuer != null) {
            issuer.stop(0);
            issuer = null;
        }
        if (client != null) {
            client.close();
            delegate.shutdownNow();
            delegate.close();
            delegate = null;
            client = null;
        }
    }

    @Test
    void malformedDeviceAuthBodyThrowsTypedExceptionWithExcerpt(final JsonMapper mapper) {
        issuer.createContext(
                "/api/accounts/deviceauth/usercode", exchange -> respond(exchange, 200, "<html>login required</html>"));
        final var oauth = new CodexOAuthClient(mapper, client, issuerUrl);

        final var e = assertThrows(CodexOAuthMalformedResponseException.class, oauth::deviceCode);
        assertTrue(e.getMessage().contains("<html>login required</html>"), "got: " + e.getMessage());
        assertEquals("<html>login required</html>", e.bodyExcerpt());
    }

    @Test
    void malformedTokenPollBodyThrowsTypedException(final JsonMapper mapper) {
        issuer.createContext(
                "/api/accounts/deviceauth/usercode",
                exchange -> respond(
                        exchange, 200, "{\"device_auth_id\":\"da-1\",\"user_code\":\"AB12-CD34\",\"interval\":1}"));
        issuer.createContext(
                "/api/accounts/deviceauth/token",
                exchange -> respond(exchange, 200, "{\"authorization_code\":\"thecode\",\"code_verifier\":"));
        final var oauth = new CodexOAuthClient(mapper, client, issuerUrl);

        final var e = assertThrows(
                CodexOAuthMalformedResponseException.class,
                () -> oauth.poll(new CodexOAuthClient.DeviceCodeChallenge("da-1", "AB12-CD34", 1, "url"), b -> false));
        assertTrue(e.getMessage().contains("is not a JSON object"), "got: " + e.getMessage());
        assertTrue(e.bodyExcerpt().contains("authorization_code"), "excerpt: " + e.bodyExcerpt());
    }

    private static void respond(final HttpExchange exchange, final int status, final String body) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
