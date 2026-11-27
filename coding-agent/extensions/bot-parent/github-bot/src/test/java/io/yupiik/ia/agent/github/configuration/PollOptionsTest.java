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
package io.yupiik.ia.agent.github.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.github.command.PollCommand.Options;
import io.yupiik.ia.agent.github.support.GithubConfigurationTestModule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PollOptionsTest {
    @Nested
    @FusionSupport(modules = DefaultsModule.class)
    class Defaults {
        @Test
        void bindsConfiguration(@Fusion final Options root) {
            final var options = root.poll();
            assertTrue(options.search().enabled());
            assertEquals(3600, options.search().reconciliationIntervalSeconds());
            assertEquals(4, options.parallelism());
            assertFalse(options.reviews().enabled());
            assertFalse(options.discussions().enabled());
        }
    }

    public static class DefaultsModule extends GithubConfigurationTestModule {
        public DefaultsModule() {
            super(Map.of());
        }
    }

    @Nested
    @FusionSupport(modules = SearchOverridesModule.class)
    class SearchOverrides {
        @Test
        void bindsConfiguration(@Fusion final Options root) {
            final var options = root.poll();
            assertFalse(options.search().enabled());
            assertEquals(17, options.search().reconciliationIntervalSeconds());
            for (final var seconds : List.of(Integer.MIN_VALUE, -1, 0, 604801, Integer.MAX_VALUE)) {
                assertThrows(IllegalArgumentException.class, () -> new PollOptions.Search(true, seconds));
            }
            assertEquals(1, new PollOptions.Search(true, 1).reconciliationIntervalSeconds());
            assertEquals(604800, new PollOptions.Search(false, 604800).reconciliationIntervalSeconds());
        }
    }

    public static class SearchOverridesModule extends GithubConfigurationTestModule {
        public SearchOverridesModule() {
            super(Map.ofEntries(
                    Map.entry("poll.search.enabled", "false"),
                    Map.entry("poll.search.reconciliationIntervalSeconds", "17")));
        }
    }
}
