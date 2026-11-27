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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Lazy, keyed completion cache: provider-scoped slices (models, variants) are
 * isolated per provider so switching tabs/providers never shows a stale list, and
 * application-scoped slices (tools, servers, sessions) load once and only refresh
 * sessions when a tab is created or closed.
 */
class CompletionCacheTest {

    @Test
    void modelsAreKeyedByProvider() {
        final var cache = new CompletionCache(null);
        assertFalse(cache.modelsLoaded("prov-a"));
        assertFalse(cache.modelsLoaded("prov-b"));

        cache.models("prov-a", List.of("z-ai/glm-5.3-flash"));
        assertTrue(cache.modelsLoaded("prov-a"));
        assertFalse(cache.modelsLoaded("prov-b"), "another provider's list must stay empty");
        assertTrue(cache.models("prov-a").contains("z-ai/glm-5.3-flash"));
        assertTrue(cache.models("prov-b").isEmpty());

        // a different provider lands independently; the first is untouched
        cache.models("prov-b", List.of("glm-5.3"));
        assertTrue(cache.models("prov-a").contains("z-ai/glm-5.3-flash"));
        assertTrue(cache.models("prov-b").contains("glm-5.3"));
    }

    @Test
    void beginModelsLoadGuardsDuplicateFetches() {
        final var cache = new CompletionCache(null);
        assertTrue(cache.beginModelsLoad("p"));
        assertFalse(cache.beginModelsLoad("p"), "an in-flight fetch must not be duplicated");
        cache.endModelsLoad("p");
        assertTrue(cache.beginModelsLoad("p"), "after end (and before landing) a new fetch is allowed");
        cache.models("p", List.of("m"));
        assertFalse(cache.beginModelsLoad("p"), "a loaded provider must not be fetched again");
    }

    @Test
    void variantsAreKeyedByProviderAndModel() {
        final var cache = new CompletionCache(null);
        cache.variants("p-a", "m1", List.of("default", "fast"));
        assertTrue(cache.variantsLoaded("p-a", "m1"));
        assertFalse(cache.variantsLoaded("p-a", "m2"));
        assertFalse(cache.variantsLoaded("p-b", "m1"));
        assertTrue(cache.variants("p-a", "m1").contains("fast"));
        assertTrue(cache.variants("p-a", "m2").isEmpty());
    }

    @Test
    void beginVariantsLoadGuardsDuplicateFetches() {
        final var cache = new CompletionCache(null);
        assertTrue(cache.beginVariantsLoad("p", "m"));
        assertFalse(cache.beginVariantsLoad("p", "m"));
        cache.endVariantsLoad("p", "m");
        cache.variants("p", "m", List.of("default"));
        assertFalse(cache.beginVariantsLoad("p", "m"), "a loaded model's variants are not re-fetched");
    }

    @Test
    void applicationScopedSlicesLoadOnceUntilRefreshed() {
        final var cache = new CompletionCache(null);
        assertTrue(cache.beginToolsLoad());
        assertFalse(cache.beginToolsLoad());
        cache.tools(List.of("a", "b"));
        assertTrue(cache.toolsLoaded());
        assertFalse(cache.beginToolsLoad(), "tools are application-scoped and only load once");

        assertTrue(cache.beginServersLoad());
        cache.servers(List.of("srv"));
        assertFalse(cache.beginServersLoad());

        assertTrue(cache.beginSessionsLoad());
        cache.sessions(List.of("s1"));
        cache.endSessionsLoad();
        assertTrue(cache.sessionsLoaded());
        assertFalse(cache.beginSessionsLoad(), "sessions are loaded once until a tab changes");
        cache.refreshSessions();
        assertFalse(cache.sessionsLoaded(), "refreshing sessions drops the loaded flag for a re-fetch");
        assertTrue(cache.beginSessionsLoad());
    }

    @Test
    void mutationsFireTheChangeCallback() {
        final var changed = new boolean[] {false};
        final var cache = new CompletionCache(() -> changed[0] = true);
        cache.models("p", List.of("m"));
        assertTrue(changed[0], "a landed models list must notify the shell to re-complete");
        changed[0] = false;
        cache.servers(List.of("s"));
        assertTrue(changed[0]);
    }

    @Test
    void refreshSessionsClearsTheCachedList() {
        final var cache = new CompletionCache(null);
        cache.sessions(List.of("s1", "s2"));
        cache.refreshSessions();
        assertTrue(cache.sessions().isEmpty());
        assertFalse(cache.sessionsLoaded());
    }

    @Test
    void clearProviderDropsAllProviderSlices() {
        final var cache = new CompletionCache(null);
        cache.models("p", List.of("m"));
        cache.variants("p", "m", List.of("default"));
        cache.clearProvider();
        assertFalse(cache.modelsLoaded("p"));
        assertFalse(cache.variantsLoaded("p", "m"));
    }
}
