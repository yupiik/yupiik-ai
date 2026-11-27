package io.yupiik.ai.examples.jre;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherTest {
    @Test
    void launcherStartsAndClosesTheSharedListener(@TempDir final Path directory) throws Exception {
        final var output = directory.resolve("stdout.log");
        final var error = directory.resolve("stderr.log");
        final var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dfusion.http-server.host=127.0.0.1",
                        "-Dfusion.http-server.port=0",
                        "-Dfusion.launcher.useHook=true",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "io.yupiik.fusion.framework.api.main.Launcher")
                .redirectOutput(output.toFile())
                .redirectError(error.toFile())
                .start();
        try {
            final var address = awaitAddress(process, output, error);
            assertEquals("127.0.0.1", address.getHost());
            assertTrue(address.getPort() > 0);
            try (final var http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()) {
                final var response = http.send(
                        HttpRequest.newBuilder(address.resolve("/.well-known/agent-card.json"))
                                .timeout(Duration.ofSeconds(5))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("capabilities"), response.body());
                assertTrue(process.isAlive(), "Launcher must await the shared HTTP listener");
            }
            process.destroy();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Launcher shutdown hook did not release the server");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Launcher subprocess did not terminate");
            }
        }
    }

    private URI awaitAddress(final Process process, final Path output, final Path error) throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && process.isAlive()) {
            final var address = Files.readString(output)
                    .lines()
                    .filter(line -> line.startsWith("Listening on "))
                    .map(line -> URI.create(line.substring("Listening on ".length())))
                    .findFirst();
            if (address.isPresent()) {
                return address.orElseThrow();
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        return fail("Launcher did not report its shared listener: " + Files.readString(error));
    }
}
