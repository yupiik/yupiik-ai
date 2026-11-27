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

import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentCardSignature;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jwt.Jws;
import java.security.PublicKey;
import java.util.Map;
import java.util.function.Function;

/**
 * Verifies the JWS signatures of an {@link AgentCard}, i.e. the client side of section 8.4.
 * <p>
 * Section 8.4.3 asks a client to verify before trusting a card, so plug it into
 * {@link io.yupiik.fusion.a2a.client.A2AClient#verifier(CardVerifier, Function)}:
 * <pre>{@code
 * final var keys = Map.of("key-1", publicKey);
 * client.verifier(verifier, keys::get);
 * }</pre>
 * The key resolution is left to the caller - a static map, a JWKS fetched from the {@code jku} header, a trust store -
 * because deciding <em>which</em> keys are trusted is the whole security decision and cannot be defaulted.
 */
@ApplicationScoped
public class CardVerifier {
    private final AgentCardPayload payloads;
    private final Jws jws;
    private final JsonMapper jsons;

    public CardVerifier(final AgentCardPayload payloads, final Jws jws, final JsonMapper jsons) {
        this.payloads = payloads;
        this.jws = jws;
        this.jsons = jsons;
    }

    /**
     * @param jsons a mapper.
     * @return a verifier and its collaborators, for a client built by hand rather than injected.
     */
    public static CardVerifier of(final JsonMapper jsons) {
        final var canonicalizer = new JsonCanonicalizer();
        return new CardVerifier(new AgentCardPayload(jsons, canonicalizer), new Jws(), jsons);
    }

    /**
     * @param card a fetched card.
     * @param keys resolves a {@code kid} - the one of the protected header - to the public key to trust, answering
     *             {@code null} for an unknown one.
     * @return {@code true} when the card carries at least one signature and <b>every</b> signature it carries verifies.
     * A card with no signature answers {@code false}: there is nothing to trust, which is for the caller to decide about.
     */
    public boolean verify(final AgentCard card, final Function<String, PublicKey> keys) {
        if (card.signatures() == null || card.signatures().isEmpty()) {
            return false;
        }
        final var payload = jws.base64Url(payloads.of(card));
        for (final var signature : card.signatures()) {
            if (!verify(signature, payload, keys)) {
                return false;
            }
        }
        return true;
    }

    protected boolean verify(
            final AgentCardSignature signature, final String payload, final Function<String, PublicKey> keys) {
        if (signature.protectedHeader() == null || signature.signature() == null) {
            return false;
        }
        final var header = header(signature);
        if (!(header.get("alg") instanceof String algorithm)
                || (header.containsKey("kid") && !(header.get("kid") instanceof String))
                || header.containsKey("crit")
                || (header.containsKey("b64") && !Boolean.TRUE.equals(header.get("b64")))) {
            // No critical extensions or unencoded payloads are supported by this detached-JWS profile.
            return false;
        }
        final var key = keys.apply((String) header.get("kid"));
        if (key == null) {
            return false; // an unknown key is an untrusted card, not an error
        }
        try {
            return jws.verify(
                    algorithm,
                    key,
                    (signature.protectedHeader() + '.' + payload).getBytes(UTF_8),
                    jws.fromBase64Url(signature.signature()));
        } catch (final IllegalArgumentException e) {
            return false; // a malformed signature does not verify, there is nothing else to say about it
        }
    }

    protected Map<String, Object> header(final AgentCardSignature signature) {
        final var json = new String(jws.fromBase64Url(signature.protectedHeader()), UTF_8);
        if (jsons.fromString(Object.class, json) instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            final var header = (Map<String, Object>) map;
            return header;
        }
        throw new IllegalArgumentException("The JWS protected header must be a JSON object, got " + json);
    }
}
