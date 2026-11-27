/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.slack.transport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.httpclient.core.ExtendedHttpClient;
import io.yupiik.fusion.httpclient.core.ExtendedHttpClientConfiguration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.slack.support.SlackJsonMapper;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Minimal real HTTP/1.1 + RFC6455 loopback peer; no timing sleeps or external server dependency. */
final class SlackTransportFixture implements AutoCloseable {
    // Loopback I/O is not the timing behavior under test. Allow scheduler/JIT contention in parallel runs;
    // reconnect delays are checked separately through ManualScheduler without waiting for wall-clock time.
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(5);

    final JsonMapper json = SlackJsonMapper.create();
    final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(IO_TIMEOUT)
            .build();
    final ExtendedHttpClient extended = new ExtendedHttpClient(new ExtendedHttpClientConfiguration().setDelegate(http));
    final ManualScheduler scheduler = new ManualScheduler();
    final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    final BlockingQueue<Session> sessions = new LinkedBlockingQueue<>();
    final BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
    final BlockingQueue<Request> responded = new LinkedBlockingQueue<>();
    final Set<Request> expectedDisconnectedResponses = ConcurrentHashMap.newKeySet();
    private final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
    private volatile Function<Request, Response> responder = request -> new Response(200, Map.of(), "{\"ok\":true}");

    SlackTransportFixture() throws IOException {
        workers.submit(() -> {
            try {
                while (!listener.isClosed()) {
                    final var socket = listener.accept();
                    var submitted = false;
                    try {
                        sockets.add(socket);
                        socket.setTcpNoDelay(true);
                        workers.submit(() -> serve(socket));
                        submitted = true;
                    } finally {
                        if (!submitted) {
                            sockets.remove(socket);
                            socket.close();
                        }
                    }
                }
            } catch (final IOException | RejectedExecutionException closed) {
                if (!listener.isClosed()) {
                    failures.add(closed);
                }
            }
        });
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/api/");
    }

    String socketUrl() {
        return "ws://127.0.0.1:" + listener.getLocalPort() + "/socket?ticket=secret-ticket";
    }

    void respond(final Function<Request, Response> handler) {
        responder = handler;
    }

    void socketDiscovery() {
        respond(request -> new Response(200, Map.of(), "{\"ok\":true,\"url\":\"" + socketUrl() + "\"}"));
    }

    SlackWebApi api(final int requestLimit, final int responseLimit) {
        return new SlackWebApi(
                json,
                extended,
                new SlackWebApi.Settings(base(), IO_TIMEOUT, requestLimit, responseLimit),
                () -> "xapp-secret",
                () -> "xoxb-secret");
    }

    SlackSocketMode socket(final int limit, final SlackSocketMode.EnvelopeHandler handler) {
        return new SlackSocketMode(
                json,
                api(8192, 8192),
                http,
                scheduler,
                new SlackSocketMode.Settings(IO_TIMEOUT, Duration.ofMillis(10), Duration.ofMillis(80), limit),
                handler,
                () -> 0.5);
    }

    <T> T take(final BlockingQueue<T> queue) throws InterruptedException {
        final var value = queue.poll(IO_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(value, () -> "Missing fixture event; failures=" + failures);
        return value;
    }

    private void serve(final Socket socket) {
        try {
            final var input = new BufferedInputStream(socket.getInputStream());
            final var requestLine = line(input).split(" ");
            final var headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            String header;
            while (!(header = line(input)).isEmpty()) {
                final var colon = header.indexOf(':');
                headers.put(
                        header.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        header.substring(colon + 1).strip());
            }
            final var length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            final var request = new Request(
                    requestLine[0],
                    requestLine[1],
                    headers,
                    new String(input.readNBytes(length), StandardCharsets.UTF_8));
            requests.add(request);
            if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                final var digest = MessageDigest.getInstance("SHA-1")
                        .digest((headers.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream()
                        .write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                                        + "Connection: Upgrade\r\nSec-WebSocket-Accept: "
                                        + Base64.getEncoder().encodeToString(digest)
                                        + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                final var session = new Session(socket);
                sessions.add(session);
                try {
                    session.read(input);
                } catch (final SocketException disconnected) {
                    // WebSocket.abort() (including protocol/size rejection) need not send a
                    // close frame or TCP EOF. A reset while reading an established session
                    // is also a peer disconnect, not a fixture HTTP/protocol failure.
                } finally {
                    session.closed.complete(null);
                }
            } else {
                final var response = responder.apply(request);
                final var bytes = response.body().getBytes(StandardCharsets.UTF_8);
                final var head = new StringBuilder("HTTP/1.1 ")
                        .append(response.status())
                        .append(" Fixture\r\n")
                        .append("Content-Length: ")
                        .append(bytes.length)
                        .append("\r\nConnection: close\r\n");
                response.headers()
                        .forEach((key, value) ->
                                head.append(key).append(": ").append(value).append("\r\n"));
                try {
                    socket.getOutputStream()
                            .write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(bytes);
                    socket.getOutputStream().flush();
                } catch (final SocketException disconnected) {
                    if (!expectedDisconnectedResponses.remove(request)) {
                        throw disconnected;
                    }
                    // Cancellation may close discovery before the intentionally delayed response.
                    // The test needs the completed response attempt, not a successful peer write.
                }
                responded.add(request);
            }
        } catch (final Exception failure) {
            if (!socket.isClosed() && !listener.isClosed() && !(failure instanceof EOFException)) {
                failures.add(failure);
            }
        } finally {
            sockets.remove(socket);
            try {
                socket.close();
            } catch (final IOException ignored) {
                // Best-effort fixture cleanup.
            }
        }
    }

    private String line(final InputStream input) throws IOException {
        final var bytes = new ByteArrayOutputStream();
        while (bytes.size() < 16384) {
            final var value = input.read();
            if (value < 0) {
                throw new EOFException();
            }
            if (value == '\n') {
                return bytes.toString(StandardCharsets.US_ASCII).stripTrailing();
            }
            bytes.write(value);
        }
        throw new IOException("Fixture header too large");
    }

    @Override
    public void close() {
        final var deadline = System.nanoTime() + IO_TIMEOUT.toNanos();
        // Each cleanup runs even if another resource fails. Stop the client before any
        // potentially blocking delegate close, and share one budget across termination waits.
        assertAll(
                listener::close,
                () -> assertAll(sockets.stream().map(socket -> socket::close)),
                scheduler::shutdownNow,
                workers::shutdownNow,
                http::shutdownNow,
                () -> assertTrue(
                        workers.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                        "Fixture threads did not exit"),
                () -> assertTrue(
                        http.awaitTermination(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))),
                        "HTTP client did not exit"),
                () -> {
                    // ExtendedHttpClient owns this same delegate. Its blocking close is safe
                    // only after termination; shutdownNow above already requested cleanup otherwise.
                    if (http.isTerminated()) {
                        extended.close();
                    }
                },
                json::close,
                () -> assertTrue(failures.isEmpty(), () -> "Fixture failures: " + failures));
    }

    record Request(String method, String path, Map<String, String> headers, String body) {}

    record Response(int status, Map<String, String> headers, String body) {}

    final class Session {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> pongs = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final Socket socket;

        private Session(final Socket socket) {
            this.socket = socket;
        }

        void text(final String value, final boolean last, final boolean continuation) throws IOException {
            frame((last ? 0x80 : 0) | (continuation ? 0 : 1), value.getBytes(StandardCharsets.UTF_8));
        }

        void frame(final int opcode, final byte[] bytes) throws IOException {
            final var output = socket.getOutputStream();
            output.write(opcode);
            if (bytes.length < 126) {
                output.write(bytes.length);
            } else {
                output.write(126);
                output.write(bytes.length >>> 8);
                output.write(bytes.length & 0xff);
            }
            output.write(bytes);
            output.flush();
        }

        void disconnect() throws IOException {
            socket.close();
        }

        private void read(final InputStream input) throws IOException {
            while (!socket.isClosed()) {
                final var opcode = input.read();
                if (opcode < 0) {
                    return;
                }
                final var head = input.read();
                var length = head & 0x7f;
                if (length == 126) {
                    length = (input.read() << 8) | input.read();
                }
                if (length == 127) {
                    throw new IOException("Fixture refuses large client frame");
                }
                final var mask = (head & 0x80) != 0 ? input.readNBytes(4) : new byte[4];
                final var payload = input.readNBytes(length);
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
                if ((opcode & 0xf) == 1) {
                    messages.add(new String(payload, StandardCharsets.UTF_8));
                }
                if ((opcode & 0xf) == 10) {
                    pongs.add(new String(payload, StandardCharsets.UTF_8));
                }
                if ((opcode & 0xf) == 8) {
                    return;
                }
            }
        }
    }

    /** The test explicitly releases scheduled work; no clock races or background timer threads. */
    static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        final BlockingQueue<Task> tasks = new LinkedBlockingQueue<>();

        private ManualScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
            if (isShutdown()) {
                throw new RejectedExecutionException();
            }
            final var task = new Task(command, unit.toNanos(delay));
            tasks.add(task);
            return task;
        }

        @Override
        public List<Runnable> shutdownNow() {
            final var pending = new ArrayList<Runnable>();
            tasks.forEach(task -> {
                task.cancel(false);
                pending.add(task);
            });
            tasks.clear();
            super.shutdownNow();
            return pending;
        }

        static final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
            final long delay;

            private Task(final Runnable command, final long delay) {
                super(command, null);
                this.delay = delay;
            }

            @Override
            public long getDelay(final TimeUnit unit) {
                return unit.convert(delay, TimeUnit.NANOSECONDS);
            }

            @Override
            public int compareTo(final Delayed other) {
                return Long.compare(delay, other.getDelay(TimeUnit.NANOSECONDS));
            }
        }
    }
}
