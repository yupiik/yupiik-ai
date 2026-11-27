package io.yupiik.ia.agent.bot.gateway.transport;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancels forwarding and closes both bodies, including resources published after cancellation. */
public final class ExchangeLease implements AutoCloseable {
    private final HttpExchange exchange;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile CompletableFuture<HttpResponse<InputStream>> future;
    private volatile InputStream body;

    public ExchangeLease(final HttpExchange exchange) {
        this.exchange = exchange;
    }

    public boolean cancelled() {
        return cancelled.get();
    }

    public CompletableFuture<HttpResponse<InputStream>> future() {
        return future;
    }

    public InputStream body() {
        return body;
    }

    public void future(final CompletableFuture<HttpResponse<InputStream>> value) {
        future = value;
        if (cancelled.get()) {
            value.cancel(true);
        }
    }

    public void body(final InputStream value) {
        body = value;
        if (cancelled.get()) {
            close();
        }
    }

    @Override
    public void close() {
        cancelled.set(true);
        if (future != null) {
            future.cancel(true);
        }
        exchange.close();
        final var stream = body;
        if (stream != null) {
            try {
                stream.close();
            } catch (final IOException ignored) {
                // Cancellation carries no diagnostics.
            }
        }
    }
}
