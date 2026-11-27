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
package io.yupiik.fusion.a2a.signature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.AgentCapabilities;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentInterface;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.Keys;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jwt.Jws;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The JWS Protected Header the signer builds, and what happens when the key is not the one it was told about.
 * <p>
 * {@code CardSignatureTest} covers the round trip. This is about the configuration surface - {@code signatureKid},
 * {@code signatureJku}, a default algorithm - and about failing loudly rather than publishing a card nobody can verify.
 */
@FusionSupport
class CardSignerTest {
    @Test
    void theKidDefaultsToTheAgentVersion(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var signer = signer(
                jsons,
                Configurations.configuration()
                        .signature(Keys.writePem(work, Keys.ec()), Jws.ES256)
                        .build());

        assertEquals("{\"alg\":\"ES256\",\"typ\":\"JOSE\",\"kid\":\"2.1.0\"}", signer.header(card("2.1.0")));
    }

    @Test
    void theKidAndTheJkuCanBeConfigured(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var signer = signer(
                jsons,
                Configurations.configuration()
                        .signature(Keys.writePem(work, Keys.ec()), Jws.ES256)
                        .signatureKid("key-1")
                        .signatureJku("https://agent.example.com/.well-known/jwks.json")
                        .build());

        // the jku is where a caller fetches the public key, so it has to travel inside the signed header
        assertEquals(
                "{\"alg\":\"ES256\",\"typ\":\"JOSE\",\"kid\":\"key-1\","
                        + "\"jku\":\"https://agent.example.com/.well-known/jwks.json\"}",
                signer.header(card("1.0.0")));
    }

    @Test
    void aBlankKidOrJkuIsNoneAtAll(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var signer = signer(
                jsons,
                Configurations.configuration()
                        .signature(Keys.writePem(work, Keys.ec()), Jws.ES256)
                        .signatureKid("  ")
                        .signatureJku("  ")
                        .build());

        assertEquals("{\"alg\":\"ES256\",\"typ\":\"JOSE\",\"kid\":\"1.0.0\"}", signer.header(card("1.0.0")));
    }

    @Test
    void theAlgorithmDefaultsToEs256(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var signer = signer(
                jsons,
                Configurations.configuration()
                        .signature(Keys.writePem(work, Keys.ec()), "  ")
                        .build());

        assertEquals("{\"alg\":\"ES256\",\"typ\":\"JOSE\",\"kid\":\"1.0.0\"}", signer.header(card("1.0.0")));
        assertTrue(signer.isEnabled());
    }

    @Test
    void aBlankKeyLocationDisablesSigning(@Fusion final JsonMapper jsons) {
        final var signer = signer(
                jsons, Configurations.configuration().signature("  ", Jws.ES256).build());

        assertFalse(signer.isEnabled(), "a blank location is no location");
        assertNull(signer.sign(card("1.0.0")).signatures());
    }

    @Test
    void aMissingKeyFileIsReported(@Fusion final JsonMapper jsons, @TempDir final Path work) {
        final var missing = work.resolve("nope.pem").toString();
        final var signer = signer(
                jsons,
                Configurations.configuration().signature(missing, Jws.ES256).build());

        final var error = assertThrows(UncheckedIOException.class, () -> signer.sign(card("1.0.0")));
        assertTrue(error.getMessage().contains(missing), error::getMessage);
    }

    @Test
    void aKeyWhichIsNotTheConfiguredAlgorithmIsReported(@Fusion final JsonMapper jsons, @TempDir final Path work)
            throws Exception {
        // an EC key with RS256: the key factory cannot even read it, and publishing an unverifiable card would be worse
        final var signer = signer(
                jsons,
                Configurations.configuration()
                        .signature(Keys.writePem(work, Keys.ec()), Jws.RS256)
                        .build());

        final var error = assertThrows(IllegalStateException.class, () -> signer.sign(card("1.0.0")));
        assertTrue(error.getMessage().contains("PKCS#8"), error::getMessage);
        assertTrue(error.getMessage().contains(Jws.RS256), error::getMessage);
    }

    @Test
    void aFileWhichIsNotAKeyIsReported(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var file = work.resolve("garbage.pem");
        Files.writeString(file, "this is not base64 at all!!");
        final var signer = signer(
                jsons,
                Configurations.configuration()
                        .signature(file.toString(), Jws.ES256)
                        .build());

        final var error = assertThrows(IllegalStateException.class, () -> signer.sign(card("1.0.0")));
        assertTrue(error.getMessage().contains(file.toString()), error::getMessage);
    }

    @Test
    void aKeyTheAlgorithmCannotSignWithIsReported(@Fusion final JsonMapper jsons, @TempDir final Path work)
            throws Exception {
        final var rsa = Keys.rsa();
        // ES256 with an RSA key: the key parses on its own but no ECDSA signature can be initialized with it
        final var signer =
                new CardSigner(
                        new AgentCardPayload(jsons, new JsonCanonicalizer()),
                        new Jws(),
                        Configurations.configuration()
                                .signature(Keys.writePem(work, Keys.ec()), Jws.ES256)
                                .build()) {
                    @Override
                    protected PrivateKey privateKey() {
                        return rsa.getPrivate();
                    }
                };

        final var error = assertThrows(IllegalStateException.class, () -> signer.sign(card("1.0.0")));
        assertTrue(error.getMessage().contains("Can't sign the agent card"), error::getMessage);
    }

    @Test
    void theSignedCardKeepsEverythingElse(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var signer = signer(
                jsons,
                Configurations.configuration()
                        .signature(Keys.writePem(work, Keys.ec()), Jws.ES256)
                        .build());
        final var card = card("1.0.0");

        final var signed = signer.sign(card);

        assertEquals(1, signed.signatures().size());
        assertEquals(card.name(), signed.name());
        assertEquals(card.supportedInterfaces(), signed.supportedInterfaces());
        assertEquals(card.capabilities(), signed.capabilities());
        assertEquals(card.defaultOutputModes(), signed.defaultOutputModes());
        // both members are base64url, and JOSE wants them unpadded
        final var signature = signed.signatures().get(0);
        assertFalse(signature.protectedHeader().contains("="), signature::protectedHeader);
        assertFalse(signature.signature().contains("="), signature::signature);
        assertEquals(
                "{\"alg\":\"ES256\",\"typ\":\"JOSE\",\"kid\":\"1.0.0\"}",
                new String(new Jws().fromBase64Url(signature.protectedHeader()), UTF_8));
    }

    private static CardSigner signer(final JsonMapper jsons, final A2AConfiguration configuration) {
        return new CardSigner(new AgentCardPayload(jsons, new JsonCanonicalizer()), new Jws(), configuration);
    }

    private static AgentCard card(final String version) {
        return new AgentCard(
                "signed-agent",
                "An agent whose card is signed.",
                List.of(AgentInterface.of("https://agent.example.com/a2a", AgentInterface.JSONRPC, "1.0")),
                null,
                version,
                null,
                new AgentCapabilities(true, true, null, false),
                null,
                null,
                List.of("text/plain"),
                List.of("text/plain"),
                List.of(),
                null,
                null);
    }
}
