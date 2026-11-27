package io.yupiik.ia.agent.bot.gateway.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class GatewayTransportTest {
    @Test
    void boundsBothScalarAndBulkReads() throws Exception {
        try (final var stream = GatewayTransport.bounded(new ByteArrayInputStream(new byte[] {1, 2, 3}), 2)) {
            assertEquals(1, stream.read());
            assertEquals(2, stream.read());
            assertThrows(IOException.class, stream::read);
        }
        try (final var stream = GatewayTransport.bounded(new ByteArrayInputStream(new byte[] {1, 2, 3}), 2)) {
            assertThrows(IOException.class, stream::readAllBytes);
        }
        try (final var stream = GatewayTransport.bounded(new ByteArrayInputStream(new byte[] {1, 2}), 2)) {
            assertArrayEquals(new byte[] {1, 2}, stream.readAllBytes());
        }
    }

    @Test
    void targetsAreFixedNumericPrivateOriginsOrPaths() {
        for (final var host : new String[] {"127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1"}) {
            assertEquals(
                    URI.create("http://" + host + ":8080/v1/chat/completions"),
                    GatewayTransport.target("http://" + host + ":8080/v1/chat/completions", false));
            assertNotNull(GatewayTransport.target("http://" + host + ":8080", true));
        }
        for (final var target : new String[] {
            "http://example.com:80/v1",
            "http://8.8.8.8:80/v1",
            "http://127.00.0.1:80/v1",
            "http://127.0.0.1:80/a/../b",
            "http://127.0.0.1:80/v1?url=evil",
            "http://127.0.0.1:80/v1#fragment",
            "https://127.0.0.1:80/v1"
        }) {
            assertThrows(IllegalArgumentException.class, () -> GatewayTransport.target(target, false), target);
        }
        assertThrows(IllegalArgumentException.class, () -> GatewayTransport.target("http://127.0.0.1:80/v1", true));
    }

    @Test
    void directClientDoesNotFollowRedirectsOrUseAmbientProxies() throws Exception {
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor();
                final var client = GatewayTransport.client(executor, 2)) {
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
            assertEquals(List.of(Proxy.NO_PROXY), client.proxy().orElseThrow().select(URI.create("http://127.0.0.1")));
        }
        assertThrows(IllegalArgumentException.class, () -> GatewayTransport.secret(key -> Optional.of("bad"), "token"));
        assertEquals("a".repeat(43), GatewayTransport.secret(key -> Optional.of("a".repeat(43)), "token"));
    }
}
