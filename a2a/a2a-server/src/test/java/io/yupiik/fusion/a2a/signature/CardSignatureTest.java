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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.model.AgentCapabilities;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentInterface;
import io.yupiik.fusion.a2a.model.AgentSkill;
import io.yupiik.fusion.a2a.test.Configurations;
import io.yupiik.fusion.a2a.test.Keys;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jwt.Jws;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Signing and verifying an agent card, i.e. section 8.4 end to end.
 * <p>
 * The signer and the verifier share the canonicalizer, so a green round trip only proves self consistency - what pins the
 * bytes to the specification is {@link JsonCanonicalizerTest}.
 */
@FusionSupport
class CardSignatureTest {
    @Test
    void es256RoundTrip(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        roundTrip(jsons, work, Jws.ES256, Keys.ec());
    }

    @Test
    void rs256RoundTrip(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        roundTrip(jsons, work, Jws.RS256, Keys.rsa());
    }

    @Test
    void tamperingBreaksTheSignature(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var keys = Keys.ec();
        final var signed = signer(jsons, work, Jws.ES256, keys).sign(card("1.0.0"));
        final var verifier = CardVerifier.of(jsons);

        assertTrue(verifier.verify(signed, kid -> keys.getPublic()));

        // one changed member does not verify, which is the whole point of signing the card
        final var tampered = new AgentCard(
                "impostor",
                signed.description(),
                signed.supportedInterfaces(),
                signed.provider(),
                signed.version(),
                signed.documentationUrl(),
                signed.capabilities(),
                signed.securitySchemes(),
                signed.securityRequirements(),
                signed.defaultInputModes(),
                signed.defaultOutputModes(),
                signed.skills(),
                signed.signatures(),
                signed.iconUrl());
        assertFalse(verifier.verify(tampered, kid -> keys.getPublic()));
    }

    @Test
    void anUnknownKeyIsNotTrusted(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var signed = signer(jsons, work, Jws.ES256, Keys.ec()).sign(card("1.0.0"));

        // no key for that kid: the card is simply not trusted, and that is not an error
        assertFalse(CardVerifier.of(jsons).verify(signed, kid -> null));
    }

    @Test
    void anUnsignedCardIsNotTrusted(@Fusion final JsonMapper jsons) {
        assertFalse(CardVerifier.of(jsons).verify(card("1.0.0"), kid -> null));
    }

    @Test
    void signingIsOffByDefault(@Fusion final JsonMapper jsons) {
        final var signer = new CardSigner(
                payloads(jsons), new Jws(), Configurations.configuration().build());

        assertFalse(signer.isEnabled());
        assertNull(signer.sign(card("1.0.0")).signatures(), "an unconfigured signer must not touch the card");
    }

    private void roundTrip(final JsonMapper jsons, final Path work, final String algorithm, final KeyPair keys)
            throws Exception {
        final var signed = signer(jsons, work, algorithm, keys).sign(card("2.1.0"));

        assertEquals(1, signed.signatures().size());
        final var signature = signed.signatures().get(0);

        // the protected header is what section 8.4.2 describes, and kid defaults to the agent version
        final var header = new String(new Jws().fromBase64Url(signature.protectedHeader()), UTF_8);
        assertEquals("{\"alg\":\"" + algorithm + "\",\"typ\":\"JOSE\",\"kid\":\"2.1.0\"}", header);

        assertTrue(CardVerifier.of(jsons).verify(signed, kid -> "2.1.0".equals(kid) ? keys.getPublic() : null));
    }

    private CardSigner signer(final JsonMapper jsons, final Path work, final String algorithm, final KeyPair keys)
            throws Exception {
        return new CardSigner(payloads(jsons), new Jws(), Keys.configuration(Keys.writePem(work, keys), algorithm));
    }

    private AgentCardPayload payloads(final JsonMapper jsons) {
        return new AgentCardPayload(jsons, new JsonCanonicalizer());
    }

    private AgentCard card(final String version) {
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
                List.of(AgentSkill.of("do/it", "Do it", "Does it.", List.of("demo"))),
                null,
                null);
    }
}
