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
package io.yupiik.ia.agent.github.execution.cache;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;

/** Version-1 manifest GC cursor: reads at most one bounded record, never materializes a manifest. */
final class CacheManifestReferences {
    private CacheManifestReferences() {}

    static Reference next(final Path manifest, final long offset) throws IOException {
        if (offset < 0) {
            throw new IOException("Invalid manifest cursor");
        }
        try (final var channel = FileChannel.open(manifest, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                final var in = new DataInputStream(Channels.newInputStream(channel))) {
            if (offset == 0) {
                if (in.readInt() != 0x59434143 || in.readInt() != 1) {
                    throw new IOException("Unsupported cache manifest format");
                }
            } else {
                channel.position(offset);
            }
            final var kind = in.readUnsignedByte();
            if (kind == 0) {
                if (in.readLong() < 1 || in.readLong() < 0 || in.readLong() < 0 || in.read() != -1) {
                    throw new IOException("Invalid cache manifest footer");
                }
                return new Reference(channel.position(), null, true);
            }
            if (kind < 1 || kind > 3) {
                throw new IOException("Invalid cache manifest record kind");
            }
            final var path = text(in);
            if ((offset == 0 && (kind != 1 || !path.isEmpty()))
                    || (offset != 0 && path.isEmpty())
                    || path.startsWith("/")
                    || path.indexOf(':') >= 0) {
                throw new IOException("Invalid cache manifest path");
            }
            if (!path.isEmpty()) {
                final var parts = path.split("/", -1);
                if (parts.length > 128) {
                    throw new IOException("Cache manifest path too deep");
                }
                for (final var part : parts) {
                    if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                        throw new IOException("Unsafe cache manifest path");
                    }
                }
            }
            final var mode = in.readInt();
            if (mode < 0 || mode > 0777) {
                throw new IOException("Invalid cache manifest mode");
            }
            String digest = null;
            if (kind == 2) {
                if (in.readLong() < 0) {
                    throw new IOException("Invalid cache blob size");
                }
                final var bytes = in.readNBytes(32);
                if (bytes.length != 32) {
                    throw new IOException("Truncated cache blob reference");
                }
                digest = HexFormat.of().formatHex(bytes);
            } else if (kind == 3) {
                final var target = text(in);
                if (target.isEmpty()
                        || target.startsWith("/")
                        || target.indexOf(':') >= 0
                        || target.split("/", -1).length > 128) {
                    throw new IOException("Invalid cache manifest link target");
                }
            }
            return new Reference(channel.position(), digest, false);
        }
    }

    private static String text(final DataInputStream in) throws IOException {
        final var length = in.readInt();
        if (length < 0 || length > 4096) {
            throw new IOException("Invalid cache manifest text length");
        }
        final var bytes = new byte[length];
        in.readFully(bytes);
        final var text = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        if (text.indexOf('\0') >= 0 || text.indexOf('\\') >= 0) {
            throw new IOException("Invalid cache manifest text");
        }
        return text;
    }

    record Reference(long offset, String digest, boolean done) {}
}
