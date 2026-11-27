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
package io.yupiik.ia.agent.github.context;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.configuration.ContextConfiguration;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextConfigurationTest {
    @Test
    void acceptsExplicitHttpsOriginsAndNormalizesOnlyTheRootSlash() {
        assertEquals(
                URI.create("https://worker.example.invalid"),
                ContextConfiguration.origin("https://worker.example.invalid/"));
        assertEquals(
                URI.create("https://worker.example.invalid:8443"),
                ContextConfiguration.origin("https://worker.example.invalid:8443"));
        assertEquals(URI.create("https://[::1]:8443"), ContextConfiguration.origin("https://[::1]:8443/"));
        assertDoesNotThrow(() -> configuration("127.0.0.1", 0, "", "git", 1, 1024 * 1024));
        assertDoesNotThrow(() -> configuration(
                "127.0.0.1", 65535, "https://worker.example.invalid", "git", 4096, 16L * 1024 * 1024 * 1024));
    }

    @Test
    void rejectsImplicitInsecureCredentialedOrNonOriginAdvertisedEndpoints() {
        for (final var value : List.of(
                "http://worker.example.invalid",
                "//worker.example.invalid",
                "worker.example.invalid",
                "https://user:password@worker.example.invalid",
                "https://worker.example.invalid/context",
                "https://worker.example.invalid?route=one",
                "https://worker.example.invalid#fragment",
                "https:///missing-host",
                " ")) {
            assertThrows(IllegalArgumentException.class, () -> ContextConfiguration.origin(value), value);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> configuration("127.0.0.1", 0, value, "git", 256, 1024 * 1024),
                    value);
        }
    }

    @Test
    void rejectsInvalidListenerExecutableAndBudgetBounds() {
        assertThrows(IllegalArgumentException.class, () -> configuration(null, 0, "", "git", 1, 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> configuration(" ", 0, "", "git", 1, 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> configuration("127.0.0.1", -1, "", "git", 1, 1024 * 1024));
        assertThrows(
                IllegalArgumentException.class, () -> configuration("127.0.0.1", 65536, "", "git", 1, 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> configuration("127.0.0.1", 0, null, "git", 1, 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> configuration("127.0.0.1", 0, "", " ", 1, 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> configuration("127.0.0.1", 0, "", null, 1, 1024 * 1024));
        for (final int requests : new int[] {0, -1, 4097}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> configuration("127.0.0.1", 0, "", "git", requests, 1024 * 1024));
        }
        for (final long bytes : new long[] {0, 1024 * 1024 - 1, 16L * 1024 * 1024 * 1024 + 1}) {
            assertThrows(IllegalArgumentException.class, () -> configuration("127.0.0.1", 0, "", "git", 1, bytes));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContextConfiguration("127.0.0.1", 0, "", null, "git", "", 1, 1024 * 1024, 67108864L));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContextConfiguration("127.0.0.1", 0, "", "", "git", null, 1, 1024 * 1024, 67108864L));
    }

    @Test
    void validatesHttpBufferCapIncludingExactArraySafeBounds() {
        for (final long cap : new long[] {Long.MIN_VALUE, -1, 0, Integer.MAX_VALUE - 7L, Long.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ContextConfiguration("127.0.0.1", 0, "", "", "git", "", 1, 1024 * 1024, cap));
        }
        for (final long cap : new long[] {1, 67108864L, Integer.MAX_VALUE - 8L}) {
            assertEquals(
                    cap,
                    new ContextConfiguration("127.0.0.1", 0, "", "", "git", "", 1, 1024 * 1024, cap)
                            .maxHttpBufferBytes());
        }
    }

    private static ContextConfiguration configuration(
            final String bind,
            final int port,
            final String advertised,
            final String git,
            final int requests,
            final long bytes) {
        return new ContextConfiguration(bind, port, advertised, "", git, "", requests, bytes, 67108864L);
    }
}
