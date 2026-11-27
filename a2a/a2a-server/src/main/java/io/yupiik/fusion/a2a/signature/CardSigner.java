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

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.model.AgentCard;
import io.yupiik.fusion.a2a.model.AgentCardSignature;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.internal.JsonStrings;
import io.yupiik.fusion.jwt.Jws;
import io.yupiik.fusion.jwt.PrivateKeyLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.List;

/**
 * Signs an {@link AgentCard} with a JSON Web Signature, as section 8.4 of the specification describes.
 * <p>
 * Signing is optional - the specification says a card <b>MAY</b> be signed - so this does nothing until
 * {@code fusion.a2a.signatureKey} points at a private key. It is worth turning on for an agent other agents must trust:
 * a client is expected to verify a signature before trusting a card, and an unsigned one gives it nothing to check.
 * <p>
 * The signed bytes come from {@link AgentCardPayload}, so what is signed is exactly what another implementation
 * recomputes from the served JSON.
 */
@ApplicationScoped
public class CardSigner {
    private final AgentCardPayload payloads;
    private final Jws jws;
    private final String keyLocation;
    private final String algorithm;
    private final String kid;
    private final String jku;

    public CardSigner(final AgentCardPayload payloads, final Jws jws, final A2AConfiguration configuration) {
        this.payloads = payloads;
        this.jws = jws;
        this.keyLocation = configuration == null ? null : blankToNull(configuration.signatureKey());
        this.algorithm = configuration == null || blankToNull(configuration.signatureAlgorithm()) == null
                ? Jws.ES256
                : configuration.signatureAlgorithm().strip();
        this.kid = configuration == null ? null : blankToNull(configuration.signatureKid());
        this.jku = configuration == null ? null : blankToNull(configuration.signatureJku());
    }

    /**
     * @return {@code true} when a key is configured, i.e. when {@link #sign(AgentCard)} does something.
     */
    public boolean isEnabled() {
        return keyLocation != null;
    }

    /**
     * @param card the card to sign, its own {@code signatures} are replaced.
     * @return the signed card, or {@code card} itself when no key is configured.
     */
    public AgentCard sign(final AgentCard card) {
        if (!isEnabled()) {
            return card;
        }

        final var protectedHeader = jws.base64Url(header(card).getBytes(UTF_8));
        final var payload = jws.base64Url(payloads.of(card));
        final var signature = jws.base64Url(signature((protectedHeader + '.' + payload).getBytes(UTF_8)));

        return withSignatures(card, List.of(new AgentCardSignature(protectedHeader, signature, null)));
    }

    /**
     * The JWS Protected Header, serialized by hand: its bytes are part of the signing input, so the member order must be
     * stable and is not something a mapper should be free to choose.
     */
    protected String header(final AgentCard card) {
        final var out = new StringBuilder("{\"alg\":")
                .append(JsonStrings.escapeChars(algorithm))
                .append(",\"typ\":\"JOSE\"");
        out.append(",\"kid\":").append(JsonStrings.escapeChars(kid == null ? card.version() : kid));
        if (jku != null) {
            out.append(",\"jku\":").append(JsonStrings.escapeChars(jku));
        }
        return out.append('}').toString();
    }

    protected byte[] signature(final byte[] signingInput) {
        try {
            return jws.sign(algorithm, privateKey(), signingInput);
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("Can't sign the agent card: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the PKCS#8 PEM key {@code fusion.a2a.signatureKey} points at.
     */
    protected PrivateKey privateKey() {
        final String pem;
        try {
            pem = Files.readString(Path.of(keyLocation));
        } catch (final IOException ioe) {
            throw new UncheckedIOException("Can't read the signature key '" + keyLocation + "'", ioe);
        }
        try {
            return new PrivateKeyLoader().loadPrivateKey(pem, Jws.ES256.equals(algorithm) ? "EC" : "RSA");
        } catch (final IllegalStateException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Can't load the signature key '" + keyLocation + "', it must be PKCS#8 PEM matching " + algorithm,
                    e);
        }
    }

    private static AgentCard withSignatures(final AgentCard card, final List<AgentCardSignature> signatures) {
        return new AgentCard(
                card.name(),
                card.description(),
                card.supportedInterfaces(),
                card.provider(),
                card.version(),
                card.documentationUrl(),
                card.capabilities(),
                card.securitySchemes(),
                card.securityRequirements(),
                card.defaultInputModes(),
                card.defaultOutputModes(),
                card.skills(),
                List.copyOf(signatures),
                card.iconUrl());
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
