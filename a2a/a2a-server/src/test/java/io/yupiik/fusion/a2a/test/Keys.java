/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.test;

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Signing keys for the tests: generated on the fly rather than checked in, so there is no private key in the repository
 * and nothing to rotate.
 */
public final class Keys {
    /**
     * The PEM label, kept out of the surrounding dashes so no secret scanner mistakes this source for a leaked key.
     */
    private static final String PEM_LABEL = "PRIVATE KEY";

    private Keys() {
        // no-op
    }

    /**
     * @return a P-256 key pair, what {@code ES256} signs with.
     */
    public static KeyPair ec() throws GeneralSecurityException {
        final var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /**
     * @return a 2048 bit RSA key pair, what {@code RS256} signs with.
     */
    public static KeyPair rsa() throws GeneralSecurityException {
        final var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /**
     * @param work the directory to write into.
     * @param keys the pair whose private key is written.
     * @return the path of the PKCS#8 PEM file, i.e. what {@code fusion.a2a.signatureKey} points at.
     */
    public static String writePem(final Path work, final KeyPair keys) throws IOException {
        final var pem = "-----BEGIN " + PEM_LABEL + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'})
                        .encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END "
                + PEM_LABEL + "-----\n";
        final var file = work.resolve("signing-key.pem");
        Files.writeString(file, pem);
        return file.toString();
    }

    /**
     * @param signatureKey       the key location.
     * @param signatureAlgorithm {@code ES256} or {@code RS256}.
     * @return a configuration whose signing attributes are set, everything else being the documented defaults.
     */
    public static A2AConfiguration configuration(final String signatureKey, final String signatureAlgorithm) {
        return configuration(signatureKey, signatureAlgorithm, "https://agent.example.com");
    }

    /**
     * @param work      the directory to write the key into.
     * @param keys      the pair to sign with.
     * @param algorithm {@code ES256} or {@code RS256}.
     * @return a signing configuration, key file included.
     */
    public static A2AConfiguration configurationOf(final Path work, final KeyPair keys, final String algorithm)
            throws IOException {
        return configuration(writePem(work, keys), algorithm);
    }

    /**
     * @param publicUrl the base url to advertise, {@code null} to assert what happens without one.
     */
    public static A2AConfiguration configuration(
            final String signatureKey, final String signatureAlgorithm, final String publicUrl) {
        return new A2AConfiguration(
                "signed-agent",
                "An agent whose card is signed.",
                "1.0.0",
                null,
                null,
                null,
                null,
                "text/plain",
                "text/plain",
                publicUrl,
                null,
                true,
                true,
                false,
                true,
                "PT5M",
                "PT30M",
                "PT30S",
                300,
                signatureKey,
                signatureAlgorithm,
                null,
                null);
    }
}
