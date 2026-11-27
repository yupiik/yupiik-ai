/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
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
package io.yupiik.ia.agent.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenCodeCapabilitiesTest {
    @Test
    void preservesOrderAndDefensivelyCopiesSuites() {
        final var source = new ArrayList<>(List.of("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384"));
        final var capabilities = new OpenCodeCapabilities(source);
        source.clear();
        assertEquals(List.of("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384"), capabilities.cipherSuites());
        assertThrows(
                UnsupportedOperationException.class,
                () -> capabilities.cipherSuites().clear());
    }

    @Test
    void absentSuitesAndLegacyCapabilitiesRemainUnset() {
        assertNull(new OpenCodeCapabilities(null).cipherSuites());
        assertNull(new ProviderCapabilities(null, null).opencode());
    }
}
