package io.yupiik.ai.http.server;

import io.yupiik.fusion.framework.api.main.Awaiter;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.http.server.api.WebServer;
import io.yupiik.fusion.http.server.spi.Endpoint;
import io.yupiik.fusion.httpserver.jre.JreHttpServer;
import io.yupiik.fusion.httpserver.jre.JreHttpServerConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hosting lifecycle. Only the fallback is owned here; an injected server is borrowed.
 * Cohosted protocols share this application-scoped host and its single fallback listener.
 * Lazy Fusion wiring starts no HTTP listener for clients or ordinary CLI commands. */
@ApplicationScoped
public class OwnedOrBorrowedHttpServer implements Awaiter, AutoCloseable {
    private final WebServer external;
    private final JreHttpServer owned;
    private final ExecutorService executor;

    public OwnedOrBorrowedHttpServer(
            final Optional<WebServer> server, final HttpHostingConfiguration hosting, final List<Endpoint> endpoints) {
        this.external = server == null ? null : server.orElse(null);
        if (server == null) { // generated lazy proxy
            owned = null;
            executor = null;
            return;
        }
        if (external == null) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                owned = new JreHttpServer(
                        new JreHttpServerConfiguration(hosting.host(), hosting.port(), true, executor), endpoints);
                owned.start();
            } catch (final RuntimeException failure) {
                executor.shutdownNow();
                throw failure;
            }
        } else {
            owned = null;
            executor = null;
        }
    }

    public int port() {
        return external == null ? owned.port() : external.configuration().port();
    }

    public String host() {
        return external == null
                ? owned.configuration().host()
                : external.configuration().host();
    }

    @Override
    public void await() {
        if (external != null) {
            external.await();
        } else {
            try {
                owned.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Destroy
    @Override
    public void close() {
        if (owned != null) {
            try {
                owned.close();
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
