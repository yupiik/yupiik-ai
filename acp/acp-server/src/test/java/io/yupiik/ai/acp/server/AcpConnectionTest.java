package io.yupiik.ai.acp.server;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ai.acp.protocol.Envelope;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

@FusionSupport
class AcpConnectionTest {
    @Test
    void invalidFramesAreReportedAndValidFramesContinue(@Fusion final JsonMapper mapper) throws Exception {
        final var output = new ByteArrayOutputStream();
        try (final var nulls = mapper.as(JsonMapper.Configuring.class)
                        .orElseThrow()
                        .serializeNulls()
                        .build();
                final var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            final var connection = new AcpConnection(mapper, nulls, stream, () -> true);
            final var received = new ArrayList<Envelope>();
            connection.read(
                    new ByteArrayInputStream(("bad\n[]\n{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"initialize\"}\n"
                                    + "{\"jsonrpc\":\"2.0\",\"id\":9007199254740993,\"method\":\"initialize\",\"params\":{}}\n"
                                    + "{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{}}\n")
                            .getBytes(StandardCharsets.UTF_8)),
                    () -> false,
                    received::add);
            assertEquals(3, received.size());
            assertTrue(received.getFirst().hasId());
            assertNull(received.getFirst().id());
            assertEquals("9007199254740993", received.get(1).id().toString());
            assertFalse(received.getLast().hasId());
            final var errors = output.toString(StandardCharsets.UTF_8).lines().toList();
            assertEquals(2, errors.size());
            assertTrue(errors.getFirst().contains("-32700"));
            errors.forEach(line -> assertTrue(line.contains("\"id\":null"), line));
        }
    }

    @Test
    void nullIdsAndStructuredErrorDataRoundTrip(@Fusion final JsonMapper mapper) throws Exception {
        final var output = new ByteArrayOutputStream();
        try (final var nulls = mapper.as(JsonMapper.Configuring.class)
                        .orElseThrow()
                        .serializeNulls()
                        .build();
                final var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            final var connection = new AcpConnection(mapper, nulls, stream, () -> true);
            connection.read(
                    new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"initialize\"}\n"
                            .getBytes(StandardCharsets.UTF_8)),
                    () -> false,
                    request -> connection.send(Envelope.of("2.0", request.id(), null, null, Map.of("ok", true), null)));
            final var reply = mapper.fromString(
                    Envelope.class, output.toString(StandardCharsets.UTF_8).strip());
            assertTrue(reply.hasId());
            assertNull(reply.id());
            final var error = mapper.fromString(
                    Envelope.class,
                    "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32000,\"message\":\"failed\",\"data\":{\"retry\":true}}}");
            assertEquals(Map.of("retry", true), error.error().data());
            assertTrue(mapper.toString(error).contains("\"retry\":true"));
        }
    }

    @Test
    void concurrentOutputNeverInterleavesFrames(@Fusion final JsonMapper mapper) throws Exception {
        final var output = new ByteArrayOutputStream();
        try (final var nulls = mapper.as(JsonMapper.Configuring.class)
                        .orElseThrow()
                        .serializeNulls()
                        .build();
                final var stream = new PrintStream(output, true, StandardCharsets.UTF_8);
                final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            final var connection = new AcpConnection(mapper, nulls, stream, () -> true);
            for (var i = 0; i < 100; i++) {
                final var id = i;
                workers.submit(() ->
                        assertTrue(connection.send(Envelope.of("2.0", id, null, null, Map.of("ok", true), null))));
            }
        }
        final var lines = output.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(100, lines.size());
        assertEquals(
                100,
                lines.stream()
                        .map(line -> mapper.fromString(Envelope.class, line).id())
                        .distinct()
                        .count());
    }

    @Test
    void cancellationDoesNotCloseCallerOutput(@Fusion final JsonMapper mapper) {
        final var output = new ByteArrayOutputStream();
        try (final var nulls = mapper.as(JsonMapper.Configuring.class)
                        .orElseThrow()
                        .serializeNulls()
                        .build();
                final var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            assertTrue(new AcpConnection(mapper, nulls, stream, () -> false)
                    .send(Envelope.of("2.0", 1, null, null, Map.of(), null)));
            assertEquals(0, output.size());
            stream.print("still usable");
            assertEquals("still usable", output.toString(StandardCharsets.UTF_8));
        }
    }
}
