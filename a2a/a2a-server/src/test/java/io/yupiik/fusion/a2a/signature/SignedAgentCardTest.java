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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.service.AgentCardService;
import io.yupiik.fusion.a2a.service.SkillService;
import io.yupiik.fusion.a2a.test.Keys;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jwt.Jws;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The card the server publishes when signing is configured: {@link AgentCardService} must sign what it serves, and refuse
 * a configuration where it could not.
 */
@FusionSupport
class SignedAgentCardTest {
    @Test
    void theServedCardIsSignedAndVerifies(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var keys = Keys.ec();
        final var card =
                cards(jsons, Keys.configurationOf(work, keys, Jws.ES256)).card(null);

        assertEquals(1, card.signatures().size());
        // and it verifies against the exact JSON a caller receives, which is what makes the signature useful
        assertTrue(CardVerifier.of(jsons)
                .verify(jsons.fromString(AgentCard.class, jsons.toString(card)), kid -> keys.getPublic()));
    }

    @Test
    void anUnsignedCardStaysUnsigned(@Fusion final JsonMapper jsons) {
        assertNull(cards(jsons, Keys.configuration(null, Jws.ES256)).card(null).signatures());
    }

    @Test
    void signingNeedsAFixedPublicUrl(@Fusion final JsonMapper jsons, @TempDir final Path work) throws Exception {
        final var configuration = Keys.configuration(Keys.writePem(work, Keys.ec()), Jws.ES256, null);

        // without a public url the interface urls come from each request, so the signed bytes would not be the served
        // ones and nobody could ever verify the card - fail at startup rather than publish something broken
        final var error = assertThrows(IllegalStateException.class, () -> cards(jsons, configuration));
        assertTrue(error.getMessage().contains("fusion.a2a.publicUrl"), error::getMessage);
    }

    private AgentCardService cards(final JsonMapper jsons, final A2AConfiguration configuration) {
        final var signer =
                new CardSigner(new AgentCardPayload(jsons, new JsonCanonicalizer()), new Jws(), configuration);
        // an anonymous subclass reaches the protected no-skill constructor, the card content is not what this asserts
        return new AgentCardService(configuration, new SkillService(null, null, null) {}, List.of(), signer);
    }
}
