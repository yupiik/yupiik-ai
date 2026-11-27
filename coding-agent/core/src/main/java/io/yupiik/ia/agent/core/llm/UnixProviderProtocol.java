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
package io.yupiik.ia.agent.core.llm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Private provider protocol v2: one request per connection, no HTTP parser or destinations on the wire.
 * Request: magic(int), capability(UTF), pinned API(UTF), body length(int), body. Response: status(int), media type(UTF),
 * then length(int)/bytes frames terminated by zero. A truncated exchange is never success.
 */
public final class UnixProviderProtocol {
    public static final int MAGIC = 0x59434132;
    public static final URI ENDPOINT = URI.create("http://yca-native.invalid/v1/chat/completions");
    public static final URI RESPONSES_ENDPOINT = URI.create("http://yca-native.invalid/v1/responses");
    // Launcher wire name; Fusion binds it to NativeProviderConfiguration's yca.native.provider.socket.
    public static final String SOCKET_ENV = "YCA_NATIVE_PROVIDER_SOCKET";
    public static final String API_ENV = "YCA_NATIVE_PROVIDER_API";
    public static final int MAX_REQUEST = 8 * 1024 * 1024;
    public static final long MAX_RESPONSE = 32L * 1024 * 1024;
    public static final int FRAME_SIZE = 16 * 1024;
    public static final Duration MAX_DURATION = Duration.ofMinutes(10);
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("unix-provider-deadline").factory());

    static {
        DEADLINES.setRemoveOnCancelPolicy(true);
    }

    private UnixProviderProtocol() {}

    public static URI endpoint(final ApiFlavor api) {
        if (api == null) {
            throw new IllegalArgumentException("Pinned provider API required");
        }
        return switch (api) {
            case COMPLETIONS -> ENDPOINT;
            case RESPONSES -> RESPONSES_ENDPOINT;
            default -> throw new IllegalArgumentException("Unsupported pinned provider API");
        };
    }

    public static ScheduledFuture<?> deadline(final Duration timeout, final Runnable action) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("Invalid provider deadline");
        }
        return DEADLINES.schedule(action, timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    // Do not use Channels streams here: their shared blocking lock can deadlock the bridge's
    // disconnect watcher against its response writer. SocketChannel itself has independent read/write locks.
    public static DataInputStream input(final SocketChannel channel) {
        return new DataInputStream(new InputStream() {
            @Override
            public int read() throws IOException {
                final var one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }

            @Override
            public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                return channel.read(ByteBuffer.wrap(bytes, offset, length));
            }
        });
    }

    public static DataOutputStream output(final SocketChannel channel) {
        return new DataOutputStream(new OutputStream() {
            @Override
            public void write(final int value) throws IOException {
                write(new byte[] {(byte) value});
            }

            @Override
            public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                final var buffer = ByteBuffer.wrap(bytes, offset, length);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
        });
    }

    public static void close(final AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (final Exception ignored) {
                // Closing the local transport is not evidence that upstream computation stopped.
            }
        }
    }
}
