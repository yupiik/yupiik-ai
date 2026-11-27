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
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the exact bytes an {@link AgentCard} signature covers, following the three rules of section 8.4.1 of the
 * specification:
 * <ol>
 *     <li>the JSON respects the Protocol Buffer field presence semantics of section 5.7,</li>
 *     <li>it is canonicalized with RFC 8785,</li>
 *     <li>the {@code signatures} member is excluded, to avoid a circular dependency.</li>
 * </ol>
 * Both {@link CardSigner} and {@link CardVerifier} go through it, which is what makes a signature this server produces
 * verifiable elsewhere - and one produced elsewhere verifiable here.
 */
@ApplicationScoped
public class AgentCardPayload {
    /**
     * The members which must survive even when they hold a Protocol Buffer default value, i.e. the ones the proto marks
     * {@code REQUIRED} - always present - or {@code optional} - present because they were explicitly set, which is the
     * only reason a nullable component of the model is non {@code null} in the first place.
     * <p>
     * Paths use {@code []} for the members of a repeated field. Everything not listed here is dropped when it holds a
     * default, per rule 1: "Default values MUST be omitted unless the field is marked as REQUIRED or has the optional
     * keyword". In practice the value this actually removes from a card built by this server is the
     * {@code required: false} of an {@code AgentExtension}.
     */
    protected static final Set<String> KEEP_DEFAULTS = Set.of(
            // AgentCard, REQUIRED
            "name",
            "description",
            "supportedInterfaces",
            "version",
            "capabilities",
            "defaultInputModes",
            "defaultOutputModes",
            "skills",
            // AgentCard, optional
            "documentationUrl",
            "iconUrl",
            // AgentCapabilities, optional
            "capabilities.streaming",
            "capabilities.pushNotifications",
            "capabilities.extendedAgentCard",
            // AgentInterface, REQUIRED
            "supportedInterfaces[].url",
            "supportedInterfaces[].protocolBinding",
            "supportedInterfaces[].protocolVersion",
            // AgentProvider, REQUIRED
            "provider.url",
            "provider.organization",
            // AgentSkill, REQUIRED
            "skills[].id",
            "skills[].name",
            "skills[].description",
            "skills[].tags");

    private static final String SIGNATURES = "signatures";

    private final JsonMapper jsons;
    private final JsonCanonicalizer canonicalizer;

    public AgentCardPayload(final JsonMapper jsons, final JsonCanonicalizer canonicalizer) {
        this.jsons = jsons;
        this.canonicalizer = canonicalizer;
    }

    /**
     * @param card the card to sign or to verify.
     * @return the canonical UTF-8 bytes the signature covers.
     */
    public byte[] of(final AgentCard card) {
        // going through the mapper rather than reading the record means the payload is what the wire carries, and
        // Fusion omitting the null components already implements "optional fields not set MUST be omitted"
        final var structure = jsons.fromString(Object.class, jsons.toString(card));
        return canonicalizer.canonicalize(prepare(structure)).getBytes(UTF_8);
    }

    /**
     * Applies rules 1 and 3: drops {@code signatures} and every member holding a Protocol Buffer default which the proto
     * does not force to be present.
     *
     * @param structure the parsed card.
     * @return the structure to canonicalize.
     */
    public Object prepare(final Object structure) {
        if (!(structure instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("An agent card must be a JSON object, got " + structure);
        }
        return strip(map, "");
    }

    protected Object strip(final Object value, final String path) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .filter(entry -> !path.isEmpty() || !SIGNATURES.equals(String.valueOf(entry.getKey())))
                    .filter(entry -> !isDefault(entry.getValue())
                            || KEEP_DEFAULTS.contains(
                                    path.isEmpty() ? String.valueOf(entry.getKey()) : path + '.' + entry.getKey()))
                    .collect(
                            LinkedHashMap<String, Object>::new,
                            (result, entry) -> {
                                final var name = String.valueOf(entry.getKey());
                                result.put(name, strip(entry.getValue(), path.isEmpty() ? name : path + '.' + name));
                            },
                            Map::putAll);
        }

        if (value instanceof List<?> list) {
            return list.stream().map(it -> strip(it, path + "[]")).toList();
        }
        return value;
    }

    /**
     * @param value a member value.
     * @return {@code true} when it is the Protocol Buffer default of its type: an empty string, an empty collection, an
     * empty object, {@code false} or zero.
     */
    protected boolean isDefault(final Object value) {
        return switch (value) {
            case null -> true;
            case String string -> string.isEmpty();
            case Boolean bool -> !bool.booleanValue();
            case Number number -> number.doubleValue() == 0d;
            case Map<?, ?> map -> map.isEmpty();
            case List<?> list -> list.isEmpty();
            default -> false;
        };
    }
}
