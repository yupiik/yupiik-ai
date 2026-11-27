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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RFC 8785 and the field presence rules of section 8.4.1 of the specification.
 * <p>
 * A signature is only worth something if another implementation computes the same bytes, so the acceptance test is the
 * worked example the specification itself gives.
 */
class JsonCanonicalizerTest {
    private final JsonCanonicalizer canonicalizer = new JsonCanonicalizer();
    private final AgentCardPayload payloads = new AgentCardPayload(null, canonicalizer);

    @Test
    void unpairedSurrogatesAreNotCanonicalJson() {
        assertThrows(IllegalStateException.class, () -> canonicalizer.canonicalize("\ud800"));
        assertThrows(IllegalStateException.class, () -> canonicalizer.canonicalize("\udc00"));
        assertThrows(IllegalStateException.class, () -> canonicalizer.canonicalize(Map.of("\ud800", "value")));
        assertEquals("\"\ud83d\ude00\"", canonicalizer.canonicalize("\ud83d\ude00"));
    }

    @Test
    void specificationWorkedExample() {
        // section 8.4.1, "Example of Default Value Removal": the fragment given there, in a deliberately unsorted order
        final var card = new LinkedHashMap<String, Object>();
        card.put("name", "Example Agent");
        card.put("description", "");
        card.put(
                "capabilities",
                new LinkedHashMap<>(Map.of(
                        "streaming", false,
                        "pushNotifications", false,
                        "extensions", List.of())));
        card.put("skills", List.of());

        // description is REQUIRED so the empty string stays, streaming and pushNotifications are optional and were
        // explicitly set so the false stays, extensions is a plain repeated field so the empty array goes, and skills
        // is
        // REQUIRED so the empty array stays
        assertEquals("""
                        {"capabilities":{"pushNotifications":false,"streaming":false},"description":"","name":"Example Agent","skills":[]}""", canonicalizer.canonicalize(payloads.prepare(card)));
    }

    @Test
    void signaturesAreExcluded() {
        final var card = new LinkedHashMap<String, Object>();
        card.put("name", "a");
        card.put("signatures", List.of(Map.of("protected", "x", "signature", "y")));

        // rule 3: signing the signatures would be circular
        assertEquals("{\"name\":\"a\"}", canonicalizer.canonicalize(payloads.prepare(card)));
    }

    @Test
    void extensionDefaultsAreStrippedButCapabilityOnesAreNot() {
        final var card = new LinkedHashMap<String, Object>();
        card.put(
                "capabilities",
                new LinkedHashMap<>(Map.of(
                        "streaming",
                        false,
                        "extensions",
                        List.of(new LinkedHashMap<>(Map.of("uri", "https://example.com/x", "required", false))))));

        // AgentExtension.required is a plain proto bool, so false is its default and must go; streaming is optional so
        // a
        // deliberate false is content
        assertEquals("""
                        {"capabilities":{"extensions":[{"uri":"https://example.com/x"}],"streaming":false}}""", canonicalizer.canonicalize(payloads.prepare(card)));
    }

    @Test
    void membersAreSortedOnUtf16CodeUnits() {
        final var value = new LinkedHashMap<String, Object>();
        value.put("z", 1);
        value.put("é", 2); // e acute, above z
        value.put("A", 3); // uppercase sorts before lowercase
        value.put("a", 4);

        assertEquals("{\"A\":3,\"a\":4,\"z\":1,\"é\":2}", canonicalizer.canonicalize(value));
    }

    @Test
    void onlyWhatMustBeEscapedIsEscaped() {
        final var value = new LinkedHashMap<String, Object>();
        value.put("k", "a\"b\\c\nd\tefég");

        // the short forms where they exist, lowercase hexadecimal otherwise, and the accent stays literal
        assertEquals("{\"k\":\"a\\\"b\\\\c\\nd\\te\\u0001fég\"}", canonicalizer.canonicalize(value));
    }

    @Test
    void integralNumbers() {
        final var value = new LinkedHashMap<String, Object>();
        value.put("a", 42);
        value.put("b", 9_000_000_000L);
        value.put("c", new BigInteger("123456789012345678901234567890"));
        value.put("d", 2d); // an integral double has only one plain form
        value.put("e", new BigDecimal("7.000"));

        assertEquals(
                "{\"a\":42,\"b\":9000000000,\"c\":123456789012345678901234567890,\"d\":2,\"e\":7}",
                canonicalizer.canonicalize(value));
    }

    @Test
    void fractionalNumbersAreRefused() {
        // emitting 0.1 without the ECMAScript Number::toString algorithm risks bytes nobody else reproduces, so this
        // fails loudly instead of producing a signature which cannot be verified elsewhere
        final var error = assertThrows(
                IllegalStateException.class, () -> canonicalizer.canonicalize(Map.of("params", Map.of("ratio", 0.1d))));

        assertTrue(error.getMessage().contains("$.params.ratio"), error::getMessage);
        assertTrue(error.getMessage().contains("RFC 8785"), error::getMessage);
    }

    @Test
    void everyControlCharacterHasItsForm() {
        final var value = new LinkedHashMap<String, Object>();
        value.put("k", "a\bb\fc\rde");

        // section 3.2.2.2: the short forms where they exist, lowercase hexadecimal otherwise
        assertEquals("{\"k\":\"a\\bb\\fc\\rd\\u001fe\"}", canonicalizer.canonicalize(value));
    }

    @Test
    void everyIntegralWidth() {
        final var value = new LinkedHashMap<String, Object>();
        value.put("a", (short) 7);
        value.put("b", (byte) 8);
        value.put("c", 9f);

        assertEquals("{\"a\":7,\"b\":8,\"c\":9}", canonicalizer.canonicalize(value));
    }

    @Test
    void anArrayIsAnArray() {
        // a structure coming from somewhere else than a mapper can hold a plain Object[]
        assertEquals("{\"a\":[1,\"b\",true]}", canonicalizer.canonicalize(Map.of("a", new Object[] {1, "b", true})));
    }

    @Test
    void aValueTheSchemeCannotRepresentIsRefused() {
        // an unknown type would be serialized differently by every implementation, so it cannot be guessed at
        final var error = assertThrows(
                IllegalStateException.class,
                () -> canonicalizer.canonicalize(Map.of("params", Map.of("when", Instant.EPOCH))));

        assertTrue(error.getMessage().contains("$.params.when"), error::getMessage);
        assertTrue(error.getMessage().contains("Instant"), error::getMessage);
    }

    @Test
    void anEmptyStructureIsStillCanonical() {
        assertEquals("{}", canonicalizer.canonicalize(Map.of()));
        assertEquals("[]", canonicalizer.canonicalize(List.of()));
        assertEquals("null", canonicalizer.canonicalize(null));
        assertEquals("\"\"", canonicalizer.canonicalize(""));
        assertEquals("true", canonicalizer.canonicalize(true));
        assertEquals("0", canonicalizer.canonicalize(0));
    }

    @Test
    void literalsAndNesting() {
        // asList and not List.of: a null is a legitimate JSON value here
        final var items = Arrays.asList(true, false, null, List.of(1, Map.of("b", "c")));

        assertEquals("{\"a\":[true,false,null,[1,{\"b\":\"c\"}]]}", canonicalizer.canonicalize(Map.of("a", items)));
    }
}
