package io.yupiik.fusion.mcp.demo;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ai.mcp.client.HttpMcpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@FusionSupport
class ClientInteroperabilityTest {
    @Test
    void extractedClientDiscoversAndCallsTheServer(@Fusion final URI endpoint, @Fusion final JsonMapper mapper)
            throws Exception {
        try (final var delegate = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .build();
                final var http = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(delegate))) {
            try (final var client = new HttpMcpClient("demo", endpoint.toString(), null, List.of(), 10, mapper, http)) {
                client.initialize();
                final var tools = client.listTools();
                assertFalse(tools.isEmpty());
                assertTrue(tools.stream().anyMatch(tool -> "demo/greet".equals(tool.name())), tools.toString());
                assertTrue(client.callTool("demo/greet", Map.of("name", "monorepo"))
                        .contains("monorepo"));
            }
            // Closing the protocol client must not close its borrowed HTTP client.
            assertFalse(http.isTerminated());
        }
    }
}
