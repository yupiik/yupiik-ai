package io.yupiik.ai.examples.tomcat;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ai.http.server.OwnedOrBorrowedHttpServer;
import io.yupiik.fusion.http.server.api.WebServer;
import io.yupiik.fusion.http.server.impl.tomcat.TomcatWebServer;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@FusionSupport
class CohostingTest {
    @Test
    void streamingTimeoutIsDisabledByTheExplicitTomcatIntegration(@Fusion final WebServer server) {
        assertEquals(
                "0",
                server.unwrap(TomcatWebServer.class)
                        .tomcat()
                        .getConnector()
                        .getProperty("asyncTimeout")
                        .toString());
    }

    @Test
    void bothProtocolsShareOneListener(
            @Fusion final OwnedOrBorrowedHttpServer a2a, @Fusion final OwnedOrBorrowedHttpServer mcp) throws Exception {
        assertTrue(a2a.port() > 0);
        assertEquals(a2a.port(), mcp.port());
        try (final var http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()) {
            final var base = "http://localhost:" + a2a.port();
            final var mcpResponse = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/mcp"))
                            .timeout(Duration.ofSeconds(30))
                            .header("content-type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, mcpResponse.statusCode(), mcpResponse.body());
            assertTrue(mcpResponse.body().contains("protocolVersion"), mcpResponse.body());
            final var a2aResponse = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/.well-known/agent-card.json"))
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, a2aResponse.statusCode(), a2aResponse.body());
            assertTrue(a2aResponse.body().contains("capabilities"), a2aResponse.body());
            a2a.close();
            mcp.close(); // Both borrowed the application server: neither may shut it down.
            assertEquals(
                    200,
                    http.send(
                                    HttpRequest.newBuilder(URI.create(base + "/.well-known/agent-card.json"))
                                            .timeout(Duration.ofSeconds(30))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding())
                            .statusCode());
        }
    }
}
