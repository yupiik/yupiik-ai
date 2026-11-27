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
package io.yupiik.llm.client.codex;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Minimal pure-JDK Ed25519 helpers for the Codex enterprise Agent Identity flow. Ed25519 is exposed
 * through the JDK {@code SunEC} provider (EdEC keys + {@code Signature.Ed25519}), so no external
 * dependency is required.
 *
 * <p>The stored private key is the standard 48-byte PKCS#8 DER (base64) that OpenAI's Codex CLI also
 * persists to {@code ~/.codex/auth.json}; the 32-byte Ed25519 seed is the trailing 32 bytes of that
 * blob, so a stored private key can be rebuilt for signing. The public key advertised to the auth API
 * is the canonical OpenSSH {@code ssh-ed25519 AAAA...} form that {@code codex-rs/agent-identity}
 * generates. When only the private seed is available the matching public point is derived by Ed25519
 * base-point scalar multiplication (unified addition formula, a = -1), which is validated against the
 * JDK's own Ed25519 public-key generation in the unit tests.</p>
 */
public final class Ed25519Util {

    /** The 48-byte PKCS#8 DER for Ed25519: fixed 16-byte prefix + 32-byte seed. */
    private static final int SEED_OFFSET = 16;

    private static final String SSH_ALGORITHM = "ssh-ed25519";

    // edwards25519 curve constants (RFC 8032).
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(new BigInteger("19"));
    private static final BigInteger D =
            new BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555");
    private static final BigInteger B_X =
            new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202");
    private static final BigInteger B_Y =
            new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960");

    private Ed25519Util() {
        // utility
    }

    /** A freshly generated Ed25519 key, holding the PKCS#8 private key and the ssh public key. */
    public record KeyMaterial(String privateKeyPkcs8Base64, String publicKeySsh) {

        /** The 32-byte Ed25519 seed extracted from the PKCS#8 private key. */
        public byte[] seed() {
            return seedOf(privateKeyPkcs8Base64);
        }
    }

    /** Generates a new Ed25519 key material (a random keypair, like codex's random seed sampling). */
    public static KeyMaterial generate() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            final KeyPair pair = generator.generateKeyPair();
            final byte[] pkcs8 = pair.getPrivate().getEncoded();
            return new KeyMaterial(Base64.getEncoder().encodeToString(pkcs8), sshPublicKey(pair.getPublic()));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate an Ed25519 key: " + e.getMessage(), e);
        }
    }

    /** Signs {@code payload} with the Ed25519 private key, returning the standard base64 signature. */
    public static String sign(final String privateKeyPkcs8Base64, final String payload) {
        try {
            final var signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey(privateKeyPkcs8Base64));
            signature.update(payload.getBytes(UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign with the agent identity key: " + e.getMessage(), e);
        }
    }

    /** Verifies (for tests and diagnostics) a signature against the public key derived from the private key. */
    public static boolean verify(final String privateKeyPkcs8Base64, final String payload, final String signatureB64) {
        try {
            final var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(privateKeyPkcs8Base64));
            verifier.update(payload.getBytes(UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureB64));
        } catch (final GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** The {@code ssh-ed25519 AAAA...} public key for a stored PKCS#8 private key. */
    static String sshPublicKey(final String privateKeyPkcs8Base64) {
        return sshPublicKey(publicKey(privateKeyPkcs8Base64));
    }

    /** The raw 32-byte Ed25519 public key encoding (little-endian y with the a-bit as the high bit). */
    static byte[] rawPublicKey(final String privateKeyPkcs8Base64) {
        return rawPublicKeyBytes(publicKey(privateKeyPkcs8Base64));
    }

    // ----- internals -----

    private static byte[] seedOf(final String pkcs8Base64) {
        final byte[] pkcs8 = Base64.getDecoder().decode(pkcs8Base64);
        if (pkcs8.length < SEED_OFFSET + 32) {
            throw new IllegalArgumentException("Unexpected Ed25519 PKCS#8 length: " + pkcs8.length);
        }
        return Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length);
    }

    private static PrivateKey privateKey(final String pkcs8Base64) {
        try {
            final var spec = new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seedOf(pkcs8Base64));
            return KeyFactory.getInstance("Ed25519").generatePrivate(spec);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Cannot rebuild the Ed25519 private key: " + e.getMessage(), e);
        }
    }

    /** Derives the Ed25519 public key that corresponds to a stored PKCS#8 private seed. */
    private static PublicKey publicKey(final String pkcs8Base64) {
        try {
            final var spec = new EdECPublicKeySpec(NamedParameterSpec.ED25519, publicPoint(seedOf(pkcs8Base64)));
            return KeyFactory.getInstance("Ed25519").generatePublic(spec);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive the Ed25519 public key: " + e.getMessage(), e);
        }
    }

    private static String sshPublicKey(final PublicKey publicKey) {
        final var raw = rawPublicKeyBytes(publicKey);
        final var blob = new ByteArrayOutputStream();
        writeSshString(blob, SSH_ALGORITHM.getBytes(UTF_8));
        writeSshString(blob, raw);
        return SSH_ALGORITHM + " " + Base64.getEncoder().encodeToString(blob.toByteArray());
    }

    private static byte[] rawPublicKeyBytes(final PublicKey publicKey) {
        final var point = ((EdECPublicKey) publicKey).getPoint();
        final var bytes = littleEndian(point.getY(), 32);
        if (point.isXOdd()) {
            bytes[31] = (byte) (bytes[31] | 0x80);
        }
        return bytes;
    }

    /**
     * Computes {@code [seed]B} on edwards25519 (RFC 8032): clamps the seed, reduces it mod {@code l},
     * then performs scalar multiplication of the base point using the complete (unified) addition
     * formula with {@code a = -1}. Validated against the JDK's Ed25519 public-key output in the tests.
     */
    private static EdECPoint publicPoint(final byte[] seed) {
        final byte[] hash = sha512(seed);
        final byte[] h = new byte[32];
        System.arraycopy(hash, 0, h, 0, 32);
        h[0] = (byte) (h[0] & 248);
        h[31] = (byte) (h[31] & 63);
        h[31] = (byte) (h[31] | 64);
        final BigInteger scalar = byteArrayToBigIntLe(h).mod(L);

        BigInteger rx = BigInteger.ZERO; // identity point (0, 1)
        BigInteger ry = BigInteger.ONE;
        BigInteger ax = B_X;
        BigInteger ay = B_Y;
        for (int bit = 255; bit >= 0; bit--) {
            // double: result = result + result (complete formula handles equal operands)
            final BigInteger[] dbl = add(rx, ry, rx, ry);
            rx = dbl[0];
            ry = dbl[1];
            if (scalar.testBit(bit)) {
                final BigInteger[] sum = add(rx, ry, ax, ay);
                rx = sum[0];
                ry = sum[1];
            }
        }
        return new EdECPoint(rx.testBit(0), ry);
    }

    /**
     * Unified (complete) point addition for edwards25519 with {@code a = -1}, in affine coordinates:
     * <pre>
     *   x3 = (x1*y2 + y1*x2) / (1 + d*x1*x2*y1*y2)
     *   y3 = (y1*y2 + x1*x2) / (1 - d*x1*x2*y1*y2)
     * </pre>
     * This formula is complete on the curve, so it also serves for doubling ({@code [2]P = P + P}).
     */
    private static BigInteger[] add(
            final BigInteger x1, final BigInteger y1, final BigInteger x2, final BigInteger y2) {
        final BigInteger x1y2 = x1.multiply(y2).mod(P);
        final BigInteger y1x2 = y1.multiply(x2).mod(P);
        final BigInteger y1y2 = y1.multiply(y2).mod(P);
        final BigInteger x1x2 = x1.multiply(x2).mod(P);
        final BigInteger dTerm = D.multiply(x1x2).multiply(y1y2).mod(P);

        final BigInteger x3 =
                x1y2.add(y1x2).multiply(BigInteger.ONE.add(dTerm).modInverse(P)).mod(P);
        final BigInteger y3 = y1y2.add(x1x2)
                .multiply(BigInteger.ONE.subtract(dTerm).modInverse(P))
                .mod(P);
        return new BigInteger[] {x3, y3};
    }

    private static BigInteger byteArrayToBigIntLe(final byte[] bytes) {
        final byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            reversed[bytes.length - 1 - i] = bytes[i];
        }
        return new BigInteger(1, reversed);
    }

    private static final BigInteger L =
            BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));

    private static byte[] sha512(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(input);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] littleEndian(final BigInteger value, final int length) {
        final byte[] big = value.toByteArray();
        final byte[] out = new byte[length];
        for (int i = 0; i < big.length && i < length; i++) {
            out[i] = big[big.length - 1 - i];
        }
        return out;
    }

    private static void writeSshString(final ByteArrayOutputStream out, final byte[] value) {
        out.write((value.length >>> 24) & 0xff);
        out.write((value.length >>> 16) & 0xff);
        out.write((value.length >>> 8) & 0xff);
        out.write(value.length & 0xff);
        out.write(value, 0, value.length);
    }
}
