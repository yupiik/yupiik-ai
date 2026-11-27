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
package io.yupiik.llm.client.opencode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.IDN;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Restricted TLS 1.3 transport for the observed OpenCode profile, using JCA primitives.
 * Not a general purpose or audited TLS implementation: no resumption, client authentication,
 * HelloRetryRequest or TLS 1.2. Unsupported states fail closed; JVM policy is never changed.
 * One reader and one writer may operate concurrently. Closing the owned socket interrupts I/O.
 */
final class OpenCodeTlsConnection implements AutoCloseable {
    private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+");

    private static final int LIMIT = 1024 * 1024;
    private static final byte[] EMPTY = new byte[0];
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final String host;
    private final X509TrustManager trust;
    private final KeyPair x25519;
    private final KeyPair kem;
    private final byte[] session = random(32);
    private final InputStream input;
    private final OutputStream output;
    private String hash;
    private String hmac;
    private int hashSize;
    private int suite;
    private Traffic incoming;
    private Traffic outgoing;
    private int group;

    private static final int[] SUITES = {
        0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8, 0xc009, 0xc013, 0xc00a, 0xc014, 0x009c,
        0x009d, 0x002f, 0x0035
    };
    private final Socket socket;
    private final int[] offered;
    private final byte[] address;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final InputStream applicationInput = new ApplicationInput();
    private final OutputStream applicationOutput = new ApplicationOutput();
    private volatile boolean closed;
    private boolean established;
    private int ccsCount;
    private int handshakeRecords;

    private OpenCodeTlsConnection(
            final Socket socket,
            final String host,
            final KeyStore trustStore,
            final List<String> configuredCipherSuites)
            throws Exception {
        this.socket = socket;
        require(host != null && !host.isEmpty(), "Missing TLS hostname");
        final var literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (literal.indexOf(':') >= 0 || NUMERIC_HOST.matcher(literal).matches()) {
            require(literal.indexOf('%') < 0, "Scoped IP literals are unsupported");
            address = InetAddress.ofLiteral(literal).getAddress(); // Never performs DNS resolution.
            this.host = literal;
        } else {
            address = null;
            this.host = dnsName(host);
        }
        final var configured =
                configuredCipherSuites == null ? OpenCodeCipherProfile.DEFAULT : List.copyOf(configuredCipherSuites);
        require(!configured.isEmpty() && configured.size() <= SUITES.length, "Invalid cipher profile");
        offered = new int[configured.size()];
        final var seen = new HashSet<String>();
        for (int i = 0; i < offered.length; i++) {
            final int index = OpenCodeCipherProfile.DEFAULT.indexOf(configured.get(i));
            require(index >= 0 && seen.add(configured.get(i)), "Unsupported or duplicate cipher suite");
            offered[i] = SUITES[index];
        }
        require(Arrays.stream(offered).anyMatch(id -> id >= 0x1301 && id <= 0x1303), "No TLS 1.3 suite offered");
        final var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        trust = Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow();
        x25519 = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        final var generator = KeyPairGenerator.getInstance("ML-KEM");
        generator.initialize(NamedParameterSpec.ML_KEM_768, RANDOM);
        kem = generator.generateKeyPair();
        input = socket.getInputStream();
        output = socket.getOutputStream();
    }

    static OpenCodeTlsConnection connect(
            final Socket connectedSocket,
            final String host,
            final KeyStore trustStore,
            final List<String> configuredCipherSuites)
            throws IOException {
        boolean success = false;
        try {
            final var connection = new OpenCodeTlsConnection(connectedSocket, host, trustStore, configuredCipherSuites);
            connection.handshake();
            connection.established = true;
            connection.transcript.reset();
            success = true;
            return connection;
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("TLS handshake failed", e);
        } finally {
            if (!success) {
                try {
                    connectedSocket.close();
                } catch (final IOException ignored) {
                    // Preserve the handshake failure.
                }
            }
        }
    }

    InputStream input() {
        return applicationInput;
    }

    OutputStream output() {
        return applicationOutput;
    }

    @Override
    public void close() throws IOException {
        // Do not wait for a writer: socket closure must interrupt a blocked write/read.
        closed = true;
        socket.close();
    }

    private IOException failure(final Exception cause) {
        try {
            close();
        } catch (final IOException closeFailure) {
            cause.addSuppressed(closeFailure);
        }
        return cause instanceof IOException io ? io : new IOException("TLS processing failed", cause);
    }

    private static String dnsName(final String value) throws IOException {
        try {
            final var ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            final var name = ascii.endsWith(".") ? ascii.substring(0, ascii.length() - 1) : ascii;
            require(!name.isEmpty() && name.length() <= 253, "Invalid DNS name");
            for (final var label : name.split("\\.", -1)) {
                require(!label.isEmpty() && label.length() <= 63, "Invalid DNS label");
            }
            return name;
        } catch (final IllegalArgumentException e) {
            throw new IOException("Invalid DNS name", e);
        }
    }

    private void handshake() throws Exception {
        final var hello = clientHello();
        record(22, 0x0301, hello);
        transcript.writeBytes(hello);
        final var server = nextHandshake(false);
        require(server[0] == 2, "Expected ServerHello");
        final var reader = new Reader(Arrays.copyOfRange(server, 4, server.length));
        require(reader.u16() == 0x0303, "Invalid ServerHello version");
        final var serverRandom = reader.take(32);
        require(
                !Arrays.equals(
                        serverRandom,
                        HexFormat.of().parseHex("cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c")),
                "HelloRetryRequest unsupported");
        require(Arrays.equals(reader.vector(1), session), "Session echo mismatch");
        suite = reader.u16();
        require(
                suite >= 0x1301 && suite <= 0x1303 && Arrays.stream(offered).anyMatch(id -> id == suite),
                "Unsupported or unoffered cipher suite");
        hash = suite == 0x1302 ? "SHA-384" : "SHA-256";
        hmac = suite == 0x1302 ? "HmacSHA384" : "HmacSHA256";
        hashSize = suite == 0x1302 ? 48 : 32;
        require(reader.u8() == 0, "Invalid compression");
        final var extensions = extensions(reader.vector(2));
        reader.end();
        require(
                extensions.size() == 2
                        && Arrays.equals(extensions.get(43), new byte[] {3, 4})
                        && extensions.containsKey(51),
                "Invalid ServerHello extensions / TLS downgrade");
        final var share = new Reader(extensions.get(51));
        group = share.u16();
        final var key = share.vector(2);
        share.end();
        final byte[] secret;
        if (group == 29) {
            require(key.length == 32, "Invalid X25519 share");
            secret = xSecret(key);
        } else if (group == 4588) {
            require(key.length == 1120, "Invalid hybrid share");
            final var pq = KEM.getInstance("ML-KEM")
                    .newDecapsulator(kem.getPrivate())
                    .decapsulate(Arrays.copyOf(key, 1088))
                    .getEncoded();
            secret = concat(pq, xSecret(Arrays.copyOfRange(key, 1088, 1120)));
        } else {
            throw new IOException("Unsupported selected group");
        }
        require(pending.size() == 0, "Unexpected plaintext handshake suffix");
        transcript.writeBytes(server);
        final var early = extract(new byte[hashSize], new byte[hashSize]);
        final var handshake = extract(derive(early, "derived", digest(EMPTY)), secret);
        final var clientHandshake = derive(handshake, "c hs traffic", digest(transcript.toByteArray()));
        final var serverHandshake = derive(handshake, "s hs traffic", digest(transcript.toByteArray()));
        incoming = new Traffic(serverHandshake);
        outgoing = new Traffic(clientHandshake);
        record(20, 0x0303, new byte[] {1});
        final var ee = nextHandshake(true);
        require(ee[0] == 8, "Expected EncryptedExtensions");
        final var eeReader = new Reader(Arrays.copyOfRange(ee, 4, ee.length));
        final var eeExtensions = extensions(eeReader.vector(2));
        eeReader.end();
        require(
                eeExtensions.keySet().stream().allMatch(id -> id == 0 || id == 16 || id == 10),
                "Unsupported EncryptedExtensions");
        if (eeExtensions.containsKey(0)) {
            require(address == null && eeExtensions.get(0).length == 0, "Invalid server_name acknowledgment");
        }
        if (eeExtensions.containsKey(10)) {
            final var groups = new Reader(eeExtensions.get(10));
            final var supported = groups.vector(2);
            require(supported.length > 0 && supported.length % 2 == 0, "Invalid supported_groups");
            groups.end();
        }
        require(
                !eeExtensions.containsKey(16)
                        || Arrays.equals(
                                eeExtensions.get(16), new byte[] {0, 9, 8, 'h', 't', 't', 'p', '/', '1', '.', '1'}),
                "Expected HTTP/1.1 ALPN");
        transcript.writeBytes(ee);
        final var certificate = nextHandshake(true);
        require(certificate[0] == 11, "Expected Certificate (client authentication unsupported)");
        final var chain = certificates(certificate);
        verifyPeer(chain);
        transcript.writeBytes(certificate);
        final var verify = nextHandshake(true);
        require(verify[0] == 15, "Expected CertificateVerify");
        verifySignature(chain[0], verify);
        transcript.writeBytes(verify);
        final var finished = nextHandshake(true);
        require(finished[0] == 20 && finished.length == 4 + hashSize, "Expected Finished");
        final var expected =
                mac(expand(serverHandshake, "finished", EMPTY, hashSize), digest(transcript.toByteArray()));
        require(
                MessageDigest.isEqual(expected, Arrays.copyOfRange(finished, 4, finished.length)),
                "Server Finished mismatch");
        transcript.writeBytes(finished);
        require(pending.size() == 0, "Unexpected handshake suffix");
        final var master = extract(derive(handshake, "derived", digest(EMPTY)), new byte[hashSize]);
        final var serverApplication = derive(master, "s ap traffic", digest(transcript.toByteArray()));
        final var clientApplication = derive(master, "c ap traffic", digest(transcript.toByteArray()));
        outgoing.write(
                22,
                message(
                        20,
                        mac(expand(clientHandshake, "finished", EMPTY, hashSize), digest(transcript.toByteArray()))));
        incoming = new Traffic(serverApplication);
        outgoing = new Traffic(clientApplication);
    }

    private byte[] clientHello() throws Exception {
        final var ext = new ByteArrayOutputStream();
        // Ordered extension/cipher profile captured from Bun 1.4.4. Fresh keys/random on every connection.
        if (address == null) {
            extension(ext, 0, vector(2, concat(new byte[] {0}, vector(2, host.getBytes(StandardCharsets.US_ASCII)))));
        }
        extension(ext, 23, EMPTY);
        extension(ext, 65281, new byte[] {0});
        extension(ext, 10, vector(2, words(4588, 29, 23, 24)));
        extension(ext, 11, new byte[] {1, 0});
        extension(ext, 35, EMPTY);
        extension(ext, 16, new byte[] {0, 9, 8, 'h', 't', 't', 'p', '/', '1', '.', '1'});
        extension(ext, 5, new byte[] {1, 0, 0, 0, 0});
        extension(
                ext,
                13,
                vector(
                        2,
                        words(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601, 0x0807, 0x0603, 0x0201)));
        extension(ext, 18, EMPTY);
        final var encoded = kem.getPublic().getEncoded();
        // ML-KEM SubjectPublicKeyInfo ends with the 1184-byte encapsulation key.
        final var pq = Arrays.copyOfRange(encoded, encoded.length - 1184, encoded.length);
        final var x = littleEndian(((XECPublicKey) x25519.getPublic()).getU(), 32);
        extension(ext, 51, vector(2, concat(words(4588), vector(2, concat(pq, x)), words(29), vector(2, x))));
        extension(ext, 45, new byte[] {1, 1});
        // TLS 1.2 is present for wire-profile parity only; any selection below 1.3 fails closed.
        extension(ext, 43, new byte[] {4, 3, 4, 3, 3});
        return message(
                1,
                concat(
                        words(0x0303),
                        random(32),
                        vector(1, session),
                        vector(2, words(offered)),
                        new byte[] {1, 0},
                        vector(2, ext.toByteArray())));
    }

    private byte[] xSecret(final byte[] wire) throws Exception {
        final var encoded = wire.clone();
        encoded[31] &= 0x7f;
        reverse(encoded);
        final var publicKey = KeyFactory.getInstance("X25519")
                .generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(1, encoded)));
        final var agreement = KeyAgreement.getInstance("X25519");
        agreement.init(x25519.getPrivate());
        agreement.doPhase(publicKey, true);
        final var secret = agreement.generateSecret();
        require(!MessageDigest.isEqual(secret, new byte[32]), "All-zero X25519 secret");
        return secret;
    }

    private X509Certificate[] certificates(final byte[] handshake) throws Exception {
        final var reader = new Reader(Arrays.copyOfRange(handshake, 4, handshake.length));
        require(reader.vector(1).length == 0, "Unexpected certificate context");
        final var list = new Reader(reader.vector(3));
        reader.end();
        final var certificates = new ArrayList<X509Certificate>();
        final var factory = CertificateFactory.getInstance("X.509");
        while (list.remaining() > 0) {
            require(certificates.size() < 16, "Certificate chain too long");
            final var bytes = new ByteArrayInputStream(list.vector(3));
            certificates.add((X509Certificate) factory.generateCertificate(bytes));
            require(bytes.available() == 0, "Certificate suffix");
            require(extensions(list.vector(2)).isEmpty(), "Certificate entry extensions unsupported");
        }
        require(!certificates.isEmpty(), "Empty certificate chain");
        return certificates.toArray(X509Certificate[]::new);
    }

    private void verifyPeer(final X509Certificate[] chain) throws Exception {
        // TLS 1.3 authentication is independent of the key-exchange group.
        trust.checkServerTrusted(chain, "UNKNOWN");
        chain[0].checkValidity();
        final var usage = chain[0].getKeyUsage();
        require(usage == null || (usage.length > 0 && usage[0]), "Certificate cannot sign");
        final var eku = chain[0].getExtendedKeyUsage();
        require(
                eku == null || eku.contains("1.3.6.1.5.5.7.3.1") || eku.contains("2.5.29.37.0"),
                "Not a server certificate");
        final var names = chain[0].getSubjectAlternativeNames();
        require(names != null, "Certificate has no subjectAltName");
        boolean match = false;
        for (final var name : names) {
            if (name.size() != 2) {
                continue;
            }
            if (address == null && Integer.valueOf(2).equals(name.get(0)) && name.get(1) instanceof String dns) {
                if (dns.startsWith("*.")) {
                    final var suffix = dnsName(dns.substring(2));
                    final int dot = host.indexOf('.');
                    match |= suffix.contains(".")
                            && dot > 0
                            && host.substring(dot + 1).equals(suffix);
                } else {
                    match |= host.equals(dnsName(dns));
                }
            } else if (address != null && Integer.valueOf(7).equals(name.get(0))) {
                final byte[] ip;
                if (name.get(1) instanceof byte[] bytes) {
                    ip = bytes;
                } else if (name.get(1) instanceof String text && text.indexOf('%') < 0) {
                    ip = InetAddress.ofLiteral(text).getAddress();
                } else {
                    continue;
                }
                match |= Arrays.equals(address, ip);
            }
        }
        require(match, "Certificate hostname mismatch");
    }

    private void verifySignature(final X509Certificate certificate, final byte[] handshake) throws Exception {
        final var reader = new Reader(Arrays.copyOfRange(handshake, 4, handshake.length));
        final var algorithm = reader.u16();
        final var bytes = reader.vector(2);
        reader.end();
        final String name;
        final String digestName;
        final int salt;
        switch (algorithm) {
            case 0x0804 -> {
                name = "RSASSA-PSS";
                digestName = "SHA-256";
                salt = 32;
            }
            case 0x0805 -> {
                name = "RSASSA-PSS";
                digestName = "SHA-384";
                salt = 48;
            }
            case 0x0806 -> {
                name = "RSASSA-PSS";
                digestName = "SHA-512";
                salt = 64;
            }
            case 0x0403 -> {
                name = "SHA256withECDSA";
                digestName = null;
                salt = 0;
            }
            case 0x0503 -> {
                name = "SHA384withECDSA";
                digestName = null;
                salt = 0;
            }
            case 0x0603 -> {
                name = "SHA512withECDSA";
                digestName = null;
                salt = 0;
            }
            case 0x0807 -> {
                name = "Ed25519";
                digestName = null;
                salt = 0;
            }
            default -> throw new IOException("Unsupported CertificateVerify algorithm");
        }
        final var key = certificate.getPublicKey();
        if (name.equals("RSASSA-PSS")) {
            require(key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= 2048, "Weak/wrong RSA key");
        } else if (name.endsWith("ECDSA")) {
            final int bits = algorithm == 0x0403 ? 256 : algorithm == 0x0503 ? 384 : 521;
            require(
                    key instanceof ECPublicKey ec
                            && ec.getParams().getCurve().getField().getFieldSize() == bits,
                    "Wrong ECDSA curve");
        }
        final var signature = Signature.getInstance(name);
        if (digestName != null) {
            signature.setParameter(
                    new PSSParameterSpec(digestName, "MGF1", new MGF1ParameterSpec(digestName), salt, 1));
        }
        signature.initVerify(key);
        final var spaces = new byte[64];
        Arrays.fill(spaces, (byte) 32);
        signature.update(concat(
                spaces,
                "TLS 1.3, server CertificateVerify\0".getBytes(StandardCharsets.US_ASCII),
                digest(transcript.toByteArray())));
        require(signature.verify(bytes), "CertificateVerify signature mismatch");
    }

    private byte[] nextHandshake(final boolean encrypted) throws Exception {
        while (true) {
            final var bytes = pending.toByteArray();
            if (bytes.length >= 4) {
                final int length = ((bytes[1] & 255) << 16) | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
                require(length <= LIMIT, "Handshake too large");
                if (bytes.length >= length + 4) {
                    pending.reset();
                    pending.write(bytes, length + 4, bytes.length - length - 4);
                    return Arrays.copyOf(bytes, length + 4);
                }
            }
            require(++handshakeRecords <= 512, "Too many handshake records");
            final var record = encrypted ? incoming.read() : plainRecord();
            require(record.type == 22, "Unexpected record during handshake");
            require(pending.size() + record.bytes.length <= LIMIT, "Handshake buffer too large");
            pending.writeBytes(record.bytes);
        }
    }

    private final class ApplicationInput extends InputStream {
        private byte[] bytes = EMPTY;
        private int position;
        private int tickets;
        private int emptyRecords;
        private boolean eof;

        @Override
        public int read() throws IOException {
            final var one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(final byte[] target, final int offset, final int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            try {
                require(!closed, "TLS connection closed");
                while (position == bytes.length && !eof) {
                    final var packet = incoming.read();
                    if (packet.type == 23) {
                        require(pending.size() == 0, "Interleaved post-handshake message");
                        bytes = packet.bytes;
                        position = 0;
                        if (bytes.length == 0) {
                            require(++emptyRecords <= 32, "Too many empty records");
                        } else {
                            emptyRecords = 0;
                        }
                    } else if (packet.type == 21) {
                        require(pending.size() == 0, "Truncated post-handshake message");
                        require(packet.bytes.length == 2 && packet.bytes[0] == 1, "TLS alert");
                        if (packet.bytes[1] == 90) {
                            // RFC 8446 user_canceled is a warning, followed by close_notify.
                            require(++emptyRecords <= 32, "Too many warning alerts");
                            continue;
                        }
                        require(packet.bytes[1] == 0, "TLS alert " + (packet.bytes[1] & 255));
                        eof = true; // Only an authenticated close_notify is clean EOF.
                    } else if (packet.type == 22) {
                        require(
                                packet.bytes.length > 0 && pending.size() + packet.bytes.length <= 65540,
                                "Post-handshake buffer limit");
                        pending.writeBytes(packet.bytes);
                        while (pending.size() >= 4) {
                            final var all = pending.toByteArray();
                            final var r = new Reader(all);
                            // KeyUpdate explicitly fails closed. Never continue with obsolete keys.
                            require(r.u8() == 4, "Unsupported post-handshake message (including KeyUpdate)");
                            final int size = r.number(3);
                            require(size <= 65536, "Ticket too large");
                            if (r.remaining() < size) {
                                break;
                            }
                            require(++tickets <= 8, "Too many session tickets");
                            validateTicket(r.take(size));
                            pending.reset();
                            pending.write(all, 4 + size, all.length - 4 - size);
                        }
                    } else {
                        throw new IOException("Unexpected application record");
                    }
                }
                if (position == bytes.length) {
                    return -1;
                }
                final int count = Math.min(length, bytes.length - position);
                System.arraycopy(bytes, position, target, offset, count);
                position += count;
                return count;
            } catch (final Exception e) {
                throw failure(e);
            }
        }

        @Override
        public int available() {
            return bytes.length - position;
        }

        @Override
        public void close() throws IOException {
            OpenCodeTlsConnection.this.close();
        }
    }

    private static void validateTicket(final byte[] bytes) throws IOException {
        final var r = new Reader(bytes);
        final var lifetime = r.take(4);
        final long seconds = ((long) (lifetime[0] & 255) << 24)
                | ((long) (lifetime[1] & 255) << 16)
                | ((long) (lifetime[2] & 255) << 8)
                | (lifetime[3] & 255);
        require(seconds <= 604800, "Invalid ticket lifetime");
        r.take(4); // ticket_age_add
        r.vector(1); // ticket_nonce
        require(r.vector(2).length > 0, "Empty ticket");
        final var ext = extensions(r.vector(2));
        // NewSessionTicket may advertise early_data even when this connection did not offer it.
        // Tickets are discarded, so this does not enable resumption or 0-RTT.
        for (final var entry : ext.entrySet()) {
            if (entry.getKey() == 42) {
                require(entry.getValue().length == 4, "Invalid ticket early_data extension");
            }
            // Unknown ticket extensions are ignored as required by TLS 1.3 section 4.6.1.
        }
        r.end(); // Authenticated tickets are validated, then discarded, never resumed.
    }

    private final class ApplicationOutput extends OutputStream {
        @Override
        public void write(final int value) throws IOException {
            write(new byte[] {(byte) value});
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeLock.lock();
            try {
                require(!closed, "TLS connection closed");
                for (int position = offset; position < offset + length; ) {
                    final int count = Math.min(16384, offset + length - position);
                    outgoing.write(23, Arrays.copyOfRange(bytes, position, position + count));
                    position += count;
                }
            } catch (final Exception e) {
                throw failure(e);
            } finally {
                writeLock.unlock();
            }
        }

        @Override
        public void flush() throws IOException {
            // Records are flushed on write; no application buffering.
            require(!closed, "TLS connection closed");
        }

        @Override
        public void close() throws IOException {
            OpenCodeTlsConnection.this.close();
        }
    }

    private Packet plainRecord() throws Exception {
        while (true) {
            final var header = readExactly(5);
            final int size = ((header[3] & 255) << 8) | (header[4] & 255);
            require(
                    header[1] == 3 && header[2] == 3 && size <= 16640 && size > 0 && (header[0] == 23 || size <= 16384),
                    "Invalid TLS record header");
            final var bytes = readExactly(size);
            if (header[0] == 20) {
                require(
                        !established && ++ccsCount <= 2 && Arrays.equals(bytes, new byte[] {1}),
                        "Invalid ChangeCipherSpec");
                continue;
            }
            return new Packet(header[0] & 255, bytes);
        }
    }

    private byte[] readExactly(final int size) throws IOException {
        final var bytes = input.readNBytes(size);
        if (bytes.length != size) {
            throw new EOFException("Truncated TLS connection");
        }
        return bytes;
    }

    private void record(final int type, final int version, final byte[] bytes) throws IOException {
        output.write(concat(new byte[] {(byte) type}, words(version, bytes.length), bytes));
        output.flush();
    }

    private final class Traffic {
        private final byte[] key;
        private final byte[] iv;
        private long sequence;

        private Traffic(final byte[] secret) throws Exception {
            key = expand(secret, "key", EMPTY, suite == 0x1301 ? 16 : 32);
            iv = expand(secret, "iv", EMPTY, 12);
        }

        private Cipher cipher(final int mode, final byte[] header) throws Exception {
            // Conservative per-key cap, below the TLS 1.3 AES-GCM usage bound. No key reuse.
            require(sequence >= 0 && sequence < (1L << 20), "Record limit reached");
            final var nonce = iv.clone();
            final long value = sequence++;
            for (int i = 0; i < 8; i++) {
                nonce[nonce.length - 1 - i] ^= (byte) (value >>> (8 * i));
            }
            final var cipher = Cipher.getInstance(suite == 0x1303 ? "ChaCha20-Poly1305" : "AES/GCM/NoPadding");
            cipher.init(
                    mode,
                    new SecretKeySpec(key, suite == 0x1303 ? "ChaCha20" : "AES"),
                    suite == 0x1303 ? new IvParameterSpec(nonce) : new GCMParameterSpec(128, nonce));
            cipher.updateAAD(header);
            return cipher;
        }

        private Packet read() throws Exception {
            final var record = plainRecord();
            require(record.type == 23 && record.bytes.length >= 17, "Expected encrypted record");
            final var header = concat(new byte[] {23, 3, 3}, words(record.bytes.length));
            final var bytes = cipher(Cipher.DECRYPT_MODE, header).doFinal(record.bytes);
            require(bytes.length <= 16624, "Plaintext record too large");
            int last = bytes.length - 1;
            while (last >= 0 && bytes[last] == 0) {
                last--;
            }
            require(last >= 0 && last <= 16384, "Missing inner content type or oversized content");
            return new Packet(bytes[last] & 255, Arrays.copyOf(bytes, last));
        }

        private void write(final int type, final byte[] bytes) throws Exception {
            require(bytes.length <= 16384, "Plaintext record too large");
            final var header = concat(new byte[] {23, 3, 3}, words(bytes.length + 17));
            output.write(header);
            output.write(cipher(Cipher.ENCRYPT_MODE, header).doFinal(concat(bytes, new byte[] {(byte) type})));
            output.flush();
        }
    }

    private byte[] digest(final byte[] bytes) throws Exception {
        return MessageDigest.getInstance(hash).digest(bytes);
    }

    private byte[] mac(final byte[] key, final byte[] bytes) throws Exception {
        final var mac = Mac.getInstance(hmac);
        mac.init(new SecretKeySpec(key, hmac));
        return mac.doFinal(bytes);
    }

    private byte[] extract(final byte[] salt, final byte[] inputKey) throws Exception {
        return mac(salt, inputKey);
    }

    private byte[] derive(final byte[] secret, final String label, final byte[] contextHash) throws Exception {
        return expand(secret, label, contextHash, hashSize);
    }

    private byte[] expand(final byte[] secret, final String label, final byte[] context, final int length)
            throws Exception {
        final var info = concat(
                words(length), vector(1, ("tls13 " + label).getBytes(StandardCharsets.US_ASCII)), vector(1, context));
        final var result = new ByteArrayOutputStream();
        byte[] previous = EMPTY;
        for (int i = 1; result.size() < length; i++) {
            previous = mac(secret, concat(previous, info, new byte[] {(byte) i}));
            result.writeBytes(previous);
        }
        return Arrays.copyOf(result.toByteArray(), length);
    }

    private static Map<Integer, byte[]> extensions(final byte[] bytes) throws IOException {
        final var reader = new Reader(bytes);
        final var result = new HashMap<Integer, byte[]>();
        while (reader.remaining() > 0) {
            require(result.putIfAbsent(reader.u16(), reader.vector(2)) == null, "Duplicate extension");
        }
        return result;
    }

    private static void extension(final ByteArrayOutputStream out, final int type, final byte[] bytes) {
        out.writeBytes(concat(words(type), vector(2, bytes)));
    }

    private static byte[] message(final int type, final byte[] bytes) {
        return concat(new byte[] {(byte) type}, vector(3, bytes));
    }

    private static byte[] vector(final int width, final byte[] bytes) {
        final var length = new byte[width];
        for (int i = 0; i < width; i++) {
            length[width - i - 1] = (byte) (bytes.length >>> (i * 8));
        }
        return concat(length, bytes);
    }

    private static byte[] words(final int... words) {
        final var out = new ByteArrayOutputStream();
        for (final int word : words) {
            out.write(word >>> 8);
            out.write(word);
        }
        return out.toByteArray();
    }

    private static byte[] concat(final byte[]... arrays) {
        final var out = new ByteArrayOutputStream();
        for (final var array : arrays) {
            out.writeBytes(array);
        }
        return out.toByteArray();
    }

    private static byte[] random(final int size) {
        final var bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] littleEndian(final BigInteger integer, final int length) {
        final var bytes = integer.toByteArray();
        reverse(bytes);
        return Arrays.copyOf(bytes, length);
    }

    private static void reverse(final byte[] bytes) {
        for (int i = 0; i < bytes.length / 2; i++) {
            final byte value = bytes[i];
            bytes[i] = bytes[bytes.length - i - 1];
            bytes[bytes.length - i - 1] = value;
        }
    }

    private static void require(final boolean condition, final String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    private record Packet(int type, byte[] bytes) {}

    private static final class Reader {
        private final byte[] bytes;
        private int position;

        private Reader(final byte[] bytes) {
            this.bytes = bytes;
        }

        private int remaining() {
            return bytes.length - position;
        }

        private int u8() throws IOException {
            require(remaining() > 0, "Truncated structure");
            return bytes[position++] & 255;
        }

        private int u16() throws IOException {
            return number(2);
        }

        private int number(final int width) throws IOException {
            int result = 0;
            for (int i = 0; i < width; i++) {
                result = (result << 8) | u8();
            }
            return result;
        }

        private byte[] take(final int size) throws IOException {
            require(size >= 0 && size <= remaining(), "Truncated vector");
            final var result = Arrays.copyOfRange(bytes, position, position + size);
            position += size;
            return result;
        }

        private byte[] vector(final int width) throws IOException {
            return take(number(width));
        }

        private void end() throws IOException {
            require(remaining() == 0, "Unexpected structure suffix");
        }
    }
}
