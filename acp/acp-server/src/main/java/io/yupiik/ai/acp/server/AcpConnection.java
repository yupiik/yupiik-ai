package io.yupiik.ai.acp.server;

import io.yupiik.ai.acp.protocol.Envelope;
import io.yupiik.ai.acp.protocol.ErrorResponse;
import io.yupiik.ai.acp.protocol.RpcError;
import io.yupiik.fusion.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Newline-delimited ACP connection with strict envelope validation and serialized output.
 * Streams and both mappers remain caller owned. The second mapper must be preconfigured to serialize nulls.
 * Dispatch executes on the reader thread; handlers choose their own
 * admission, session ordering and request cancellation policy. No global stdout or agent runtime is accessed. */
public final class AcpConnection {
    private final JsonMapper mapper;
    private final JsonMapper nullsMapper;
    private final PrintStream output;
    private final BooleanSupplier writable;
    private final ReentrantLock writeLock = new ReentrantLock();

    public AcpConnection(
            final JsonMapper mapper,
            final JsonMapper nullsMapper,
            final PrintStream output,
            final BooleanSupplier writable) {
        this.mapper = mapper;
        this.nullsMapper = nullsMapper;
        this.output = output;
        this.writable = writable;
    }

    public void read(final InputStream input, final BooleanSupplier stopped, final Consumer<Envelope> handler)
            throws IOException {
        // Do not close the reader: input ownership belongs to the hosting application.
        final var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()));
        String line;
        while (!stopped.getAsBoolean() && (line = reader.readLine()) != null) {
            final Object decoded;
            try {
                decoded = mapper.fromString(Object.class, line);
            } catch (final RuntimeException e) {
                error(-32700, "Parse error");
                continue;
            }
            if (!(decoded instanceof Map<?, ?> fields) || !validEnvelope(fields)) {
                error(-32600, "Invalid Request");
                continue;
            }
            final Envelope envelope;
            try {
                envelope = mapper.fromString(Envelope.class, line);
            } catch (final RuntimeException e) {
                error(-32600, "Invalid Request");
                continue;
            }
            handler.accept(envelope);
        }
    }
    /** @return false on a broken output stream; never closes either caller-owned stream. */
    public boolean send(final Object message) {
        writeLock.lock();
        try {
            if (!writable.getAsBoolean()) {
                return true;
            }
            if (message instanceof ErrorResponse error && error.id() == null) {
                output.println(nullsMapper.toString(error));
            } else {
                output.println(mapper.toString(message));
            }
            output.flush();
            return !output.checkError();
        } finally {
            writeLock.unlock();
        }
    }

    private void error(final int code, final String message) {
        send(new ErrorResponse("2.0", null, RpcError.of(code, message)));
    }

    private static boolean validEnvelope(final Map<?, ?> fields) {
        if (!"2.0".equals(fields.get("jsonrpc"))) {
            return false;
        }
        final var id = fields.get("id");
        if (id != null && !(id instanceof String) && !integral(id, Long.MIN_VALUE, Long.MAX_VALUE)) {
            return false;
        }
        final var result = fields.containsKey("result");
        final var error = fields.containsKey("error");
        if (fields.containsKey("method")) {
            return fields.get("method") instanceof String method
                    && !method.isBlank()
                    && !result
                    && !error
                    && (!fields.containsKey("params") || fields.get("params") instanceof Map<?, ?>);
        }
        if (!fields.containsKey("id") || result == error || fields.containsKey("params")) {
            return false;
        }
        return !error
                || (fields.get("error") instanceof Map<?, ?> detail
                        && integral(detail.get("code"), Integer.MIN_VALUE, Integer.MAX_VALUE)
                        && detail.get("message") instanceof String);
    }

    public static boolean integral(final Object value, final long min, final long max) {
        try {
            final long number;
            if (value instanceof BigDecimal decimal) {
                number = decimal.longValueExact();
            } else if (value instanceof BigInteger integer) {
                number = integer.longValueExact();
            } else if (value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long) {
                number = ((Number) value).longValue();
            } else {
                return false;
            }
            return number >= min && number <= max;
        } catch (final ArithmeticException invalid) {
            return false;
        }
    }
}
