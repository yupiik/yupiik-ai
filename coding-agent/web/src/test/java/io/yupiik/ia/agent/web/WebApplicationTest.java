package io.yupiik.ia.agent.web;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebApplicationTest {
    @Test
    void generatedApplicationWiringStartsAndServesTheRpcEndpoint(@TempDir final Path home) throws Exception {
        final var errors = home.resolve("stderr.log");
        final var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dyca.home=" + home,
                        "-Dyca.storageHome=" + home.resolve("state"),
                        "-Dyca.configuration.json=" + home.resolve("configuration.json"),
                        "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        "io.yupiik.fusion.framework.api.main.CliLauncher",
                        "web",
                        "--port",
                        "0",
                        "--host",
                        "127.0.0.1",
                        "--openBrowser",
                        "false")
                .directory(home.toFile())
                .redirectError(errors.toFile())
                .start();
        final var reader = process.inputReader(StandardCharsets.UTF_8);
        try (final var http = HttpClient.newHttpClient()) {
            final var listening = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try {
                    listening.complete(reader.readLine());
                } catch (final Exception error) {
                    listening.completeExceptionally(error);
                }
            });
            final var banner = listening.get(20, TimeUnit.SECONDS);
            assertNotNull(banner, () -> readErrors(errors));
            assertTrue(banner.startsWith("web UI listening on "), () -> banner + "\n" + readErrors(errors));
            final var base = banner.substring("web UI listening on ".length());
            final var result = http.send(
                    HttpRequest.newBuilder(URI.create(base + "api/rpc"))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sessions.list\",\"params\":{}}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, result.statusCode(), result.body());
            assertTrue(result.body().contains("\"result\""), result.body());
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
            reader.close();
        }
    }

    private String readErrors(final Path errors) {
        try {
            return Files.readString(errors);
        } catch (final Exception error) {
            return error.toString();
        }
    }
}
