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
package io.yupiik.fusion.a2a.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@FusionSupport
class PartTest {
    @Test
    void creationFactoriesPreserveEachBranch(@Fusion final JsonMapper mapper) {
        for (final var part : List.of(
                Part.text("hello"),
                Part.raw("text/plain", new byte[] {97}, "a.txt"),
                Part.url("text/plain", "https://example.com/a.txt", "a.txt"),
                Part.of(null, null, null, Map.of("ok", true), Map.of("source", "test"), null, "application/json"))) {
            assertEquals(part, mapper.fromString(Part.class, mapper.toString(part)));
            assertTrue(part.hasSingleContent());
        }
    }

    @Test
    void generatedCodecPreservesTheNullDataBranch(@Fusion final JsonMapper mapper) {
        final var decoded = mapper.fromString(Part.class, "{\"data\":null,\"vendor/optional\":null}");
        assertTrue(decoded.hasData());
        assertNull(decoded.data());
        assertEquals(decoded, mapper.fromString(Part.class, mapper.toString(decoded)));
        assertEquals(Part.data(null), mapper.fromString(Part.class, mapper.toString(Part.data(null))));
        assertFalse(mapper.fromString(Part.class, "{\"text\":\"hello\"}").hasData());
    }

    @Test
    void nestedPartsStayTypedAndPreservePresence(@Fusion final JsonMapper mapper) {
        final var message = Message.of(Role.ROLE_USER, List.of(Part.data(null), Part.text("hello")));
        final var artifact = Artifact.data("output", null);
        final var decodedMessage = mapper.fromString(Message.class, mapper.toString(message));
        assertEquals(message, decodedMessage);
        assertTrue(decodedMessage.parts().getFirst().hasData());
        assertNull(decodedMessage.parts().getFirst().data());
        assertEquals(artifact, mapper.fromString(Artifact.class, mapper.toString(artifact)));
    }

    @Test
    void invalidOneofBranchesAreRejected(@Fusion final JsonMapper mapper) {
        for (final var json :
                List.of("{}", "{\"text\":\"hello\",\"raw\":\"YQ==\"}", "{\"text\":\"hello\",\"data\":null}")) {
            assertThrows(RuntimeException.class, () -> mapper.fromString(Part.class, json), json);
        }
    }
}
