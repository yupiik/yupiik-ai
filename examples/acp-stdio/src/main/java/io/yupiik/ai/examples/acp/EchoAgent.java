package io.yupiik.ai.examples.acp;

import io.yupiik.ai.acp.protocol.Envelope;
import io.yupiik.ai.acp.protocol.ErrorResponse;
import io.yupiik.ai.acp.protocol.RpcError;
import io.yupiik.ai.acp.server.AcpConnection;
import io.yupiik.fusion.framework.api.main.Awaiter;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/** Minimal framing/dispatch example, not a full coding agent. Streams remain application owned. */
@DefaultScoped
public class EchoAgent implements Awaiter {
    private final JsonMapper mapper;

    public EchoAgent(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void await() {
        try (final var nulls = mapper.as(JsonMapper.Configuring.class)
                .orElseThrow()
                .serializeNulls()
                .build()) {
            final var connection = new AcpConnection(mapper, nulls, System.out, () -> true);
            connection.read(System.in, () -> false, message -> {
                if (message.id() == null) {
                    return;
                }
                if ("initialize".equals(message.method())) {
                    connection.send(Envelope.of(
                            "2.0",
                            message.id(),
                            null,
                            null,
                            Map.of(
                                    "protocolVersion",
                                    1,
                                    "agentCapabilities",
                                    Map.of(),
                                    "agentInfo",
                                    Map.of("name", "echo", "version", "1.0")),
                            null));
                } else {
                    connection.send(new ErrorResponse("2.0", message.id(), RpcError.of(-32601, "Method not found")));
                }
            });
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
