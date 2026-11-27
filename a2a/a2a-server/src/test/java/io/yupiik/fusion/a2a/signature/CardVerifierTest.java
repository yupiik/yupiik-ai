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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.model.AgentCapabilities;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentCardSignature;
import io.yupiik.fusion.a2a.model.AgentInterface;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.Keys;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jwt.Jws;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The client side of section 8.4: a card is trusted only when it carries at least one signature and <b>every</b>
 * signature it carries verifies against a key the caller decided to trust.
 * <p>
 * Everything which does not verify - an unknown key, a malformed signature, an algorithm this implementation does not
 * know - answers {@code false} rather than throwing: an untrusted card is an outcome the caller acts on, not an error.
 */
@FusionSupport
class CardVerifierTest {
    @Test
    void aCardWithoutAnySignatureIsNotTrusted(@Fusion final JsonMapper jsons) {
        final var verifier = CardVerifier.of(jsons);

        assertFalse(verifier.verify(card("1.0.0", null), kid -> null), "there is nothing to trust");
        assertFalse(verifier.verify(card("1.0.0", List.of()), kid -> null));
    }

    @Test
    void everySignatureMustVerify(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var keys = Keys.ec();
        final var signed = sign(jsons, work, keys);
        final var verifier = CardVerifier.of(jsons);

        assertTrue(verifier.verify(signed, kid -> keys.getPublic()));

        // a second signature nobody can check makes the whole card untrusted, whatever the first one says
        final var partly = card(
                "1.0.0",
                List.of(
                        signed.signatures().get(0),
                        new AgentCardSignature(signed.signatures().get(0).protectedHeader(), "AAAA", null)));
        assertFalse(verifier.verify(partly, kid -> keys.getPublic()));
    }

    @Test
    void anIncompleteSignatureDoesNotVerify(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var keys = Keys.ec();
        final var reference = sign(jsons, work, keys).signatures().get(0);
        final var verifier = CardVerifier.of(jsons);

        assertFalse(verifier.verify(
                card("1.0.0", List.of(new AgentCardSignature(null, reference.signature(), null))),
                kid -> keys.getPublic()));
        assertFalse(verifier.verify(
                card("1.0.0", List.of(new AgentCardSignature(reference.protectedHeader(), null, null))),
                kid -> keys.getPublic()));
    }

    @Test
    void anUnknownKeyIsAnUntrustedCard(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var signed = sign(jsons, work, Keys.ec());

        assertFalse(CardVerifier.of(jsons).verify(signed, kid -> null), "and that is not an error");
    }

    @Test
    void aSignatureFromAnotherKeyDoesNotVerify(@Fusion final JsonMapper jsons, @TempDir final Path work)
            throws Exception {
        final var signed = sign(jsons, work, Keys.ec());
        final var other = Keys.ec();

        assertFalse(CardVerifier.of(jsons).verify(signed, kid -> other.getPublic()));
    }

    @Test
    void anAlgorithmThisImplementationDoesNotKnow(@Fusion final JsonMapper jsons, @TempDir final Path work)
            throws Exception {
        final var keys = Keys.ec();
        final var reference = sign(jsons, work, keys).signatures().get(0);
        final var jws = new Jws();
        final var header = jws.base64Url("{\"alg\":\"HS256\",\"typ\":\"JOSE\",\"kid\":\"1.0.0\"}".getBytes(UTF_8));

        // jcaAlgorithm refuses it, and a card signed with something we cannot check is simply not trusted
        assertFalse(CardVerifier.of(jsons)
                .verify(
                        card("1.0.0", List.of(new AgentCardSignature(header, reference.signature(), null))),
                        kid -> keys.getPublic()));
    }

    @Test
    void aMalformedSignatureDoesNotVerify(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var keys = Keys.ec();
        final var reference = sign(jsons, work, keys).signatures().get(0);
        final var verifier = CardVerifier.of(jsons);
        final PublicKey key = keys.getPublic();

        for (final var signature : List.of("not base64!!", "AAAA", "")) {
            assertFalse(
                    verifier.verify(
                            card(
                                    "1.0.0",
                                    List.of(new AgentCardSignature(reference.protectedHeader(), signature, null))),
                            kid -> key),
                    () -> "'" + signature + "' should not verify");
        }
    }

    @Test
    void aProtectedHeaderWhichIsNotAJsonObjectIsRefused(@Fusion final JsonMapper jsons) {
        final var jws = new Jws();
        final var header = jws.base64Url("\"just a string\"".getBytes(UTF_8));
        final var verifier = CardVerifier.of(jsons);
        final var card = card("1.0.0", List.of(new AgentCardSignature(header, "AAAA", null)));

        // this one throws rather than answering false: the card is not untrusted, it is not an A2A card at all
        final var error = assertThrows(IllegalArgumentException.class, () -> verifier.verify(card, kid -> null));
        assertTrue(error.getMessage().contains("must be a JSON object"), error::getMessage);
    }

    @Test
    void aHeaderWithoutAKidLooksTheKeyUpWithNull(@Fusion final JsonMapper jsons, @TempDir final Path work)
            throws Exception {
        final var keys = Keys.ec();
        final var reference = sign(jsons, work, keys).signatures().get(0);
        final var header = new Jws().base64Url("{\"alg\":\"ES256\",\"typ\":\"JOSE\"}".getBytes(UTF_8));
        final var card = card("1.0.0", List.of(new AgentCardSignature(header, reference.signature(), null)));

        // the resolver decides what an absent kid means, so it has to be called - here it trusts nothing
        assertFalse(CardVerifier.of(jsons).verify(card, kid -> {
            assertTrue(kid == null, () -> "Unexpected kid " + kid);
            return null;
        }));
    }

    private static AgentCard sign(final JsonMapper jsons, final Path work, final KeyPair keys) throws Exception {
        return new CardSigner(
                        new AgentCardPayload(jsons, new JsonCanonicalizer()),
                        new Jws(),
                        Configurations.configuration()
                                .signature(Keys.writePem(work, keys), Jws.ES256)
                                .build())
                .sign(card("1.0.0", null));
    }

    @Test
    void unsupportedProtectedHeaderSemanticsAreRejectedEvenWithAValidSignature(@Fusion final JsonMapper jsons)
            throws Exception {
        final var keys = Keys.ec();
        final var jws = new Jws();
        final var payload = jws.base64Url(new AgentCardPayload(jsons, new JsonCanonicalizer()).of(card("1.0.0", null)));
        for (final var suffix : List.of(
                ",\"crit\":[\"extension\"],\"extension\":true",
                ",\"crit\":[]",
                ",\"crit\":[\"b64\"],\"b64\":false",
                ",\"b64\":false",
                ",\"b64\":\"true\"",
                ",\"kid\":42")) {
            final var header = jws.base64Url(("{\"alg\":\"ES256\"" + suffix + '}').getBytes(UTF_8));
            final var signature =
                    jws.base64Url(jws.sign("ES256", keys.getPrivate(), (header + '.' + payload).getBytes(UTF_8)));
            assertFalse(
                    CardVerifier.of(jsons)
                            .verify(
                                    card("1.0.0", List.of(new AgentCardSignature(header, signature, null))),
                                    kid -> keys.getPublic()),
                    suffix);
        }
    }

    private static AgentCard card(final String version, final List<AgentCardSignature> signatures) {
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
                signatures,
                null);
    }
}
