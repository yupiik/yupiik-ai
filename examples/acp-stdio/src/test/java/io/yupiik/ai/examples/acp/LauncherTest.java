package io.yupiik.ai.examples.acp;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ai.acp.protocol.Envelope;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherTest {
    @Test
    void launcherDispatchesUntilEofWithoutPollutingStdout(@TempDir final Path directory) throws Exception {
        final var output = directory.resolve("stdout.log");
        final var error = directory.resolve("stderr.log");
        final var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "io.yupiik.fusion.framework.api.main.Launcher")
                .redirectOutput(output.toFile())
                .redirectError(error.toFile())
                .start();
        try {
            try (final var input = process.getOutputStream()) {
                input.write(("""
                        {"jsonrpc":"2.0","id":9007199254740993,"method":"initialize","params":{"protocolVersion":1}}
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        {"jsonrpc":"2.0","id":"unsupported","method":"unknown"}
                        invalid-json
                        """).getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Launcher must exit on stdin EOF");
            assertEquals(0, process.exitValue(), Files.readString(error));
            final var lines = Files.readAllLines(output);
            assertEquals(3, lines.size(), lines.toString());
            try (final var container = ConfiguringContainer.of().start();
                    final var mapper = container.lookup(JsonMapper.class)) {
                final var initialized = mapper.instance().fromString(Envelope.class, lines.getFirst());
                assertEquals("9007199254740993", initialized.id().toString());
                assertNotNull(initialized.result());
                assertNull(initialized.error());
                final var unknown = mapper.instance().fromString(Envelope.class, lines.get(1));
                assertEquals("unsupported", unknown.id());
                assertEquals(-32601, unknown.error().code());
                final var invalid = mapper.instance().fromString(Envelope.class, lines.get(2));
                assertNull(invalid.id());
                assertEquals(-32700, invalid.error().code());
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Launcher subprocess did not terminate");
            }
        }
    }
}
