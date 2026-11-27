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
package io.yupiik.ia.agent.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.AgentMode;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.configuration.PermissionMode;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentPolicyTest {
    @Test
    void toolCeilingsIntersectAndAnEmptyCeilingStaysEmpty() {
        assertEquals(
                Set.of("read"),
                SubagentRunner.intersectToolCeilings(
                        Set.of("read", "write"), Set.of("read", "search"), Set.of("read", "bash")));
        assertEquals(Set.of(), SubagentRunner.intersectToolCeilings(Set.of("read"), Set.of("write")));
        assertNull(SubagentRunner.intersectToolCeilings(null, null));
    }

    @Test
    void requestedAndConfiguredTurnLimitsUseTheSmallestPositiveCap() {
        assertEquals(2, SubagentRunner.resolveMaxIterationsCap(5, 100, 2));
        assertEquals(5, SubagentRunner.resolveMaxIterationsCap(5, 100, 8));
        assertEquals(3, SubagentRunner.resolveMaxIterationsCap(null, 100, 3));
        assertEquals(100, SubagentRunner.resolveMaxIterationsCap(0, 100, null));
    }

    @Test
    void nullRunDistinguishesBudgetExhaustionFromCancellation() {
        final var budget = SubagentRunner.nullRunResult("partial", false, true);
        assertEquals(SubagentRegistry.SubagentResult.Status.BUDGET_EXHAUSTED, budget.status());
        assertTrue(budget.budgetExhausted());
        assertFalse(budget.cancelled());

        final var cancelled = SubagentRunner.nullRunResult("partial", true, true);
        assertEquals(SubagentRegistry.SubagentResult.Status.CANCELLED, cancelled.status());
        assertTrue(cancelled.cancelled());
        assertFalse(cancelled.budgetExhausted());
    }

    @Nested
    @FusionSupport(modules = PermissionModule.class)
    class PermissionFork {
        @TempDir
        static Path storageHome;

        @Test
        void childPermissionForkCopiesModesButIsolatesSessionGrants(
                @Fusion final CodingAgentConfiguration configuration) {
            final var parent = new PermissionService(configuration, storageHome);

            parent.setMode(AgentMode.BUILD);
            parent.setPermissionMode(PermissionMode.AUTO_EDIT);
            parent.grantSession(
                    "write_file",
                    Map.of("path", storageHome.resolve("parent.txt").toString()));

            final var child = parent.forkForChild();
            assertEquals(AgentMode.BUILD, child.mode());
            assertEquals(PermissionMode.AUTO_EDIT, child.permissionMode());
            assertTrue(child.sessionGrants().isEmpty(), "parent approvals must not transfer to a child");

            child.grantSession(
                    "write_file",
                    Map.of("path", storageHome.resolve("child.txt").toString()));
            assertEquals(1, child.sessionGrants().size());
            assertEquals(1, parent.sessionGrants().size(), "child approvals must not mutate the parent");
        }
    }

    public static class PermissionModule extends ConfigurationTestModule {
        public PermissionModule() {
            super(Map.of("yca.storageHome", PermissionFork.storageHome.toString()));
        }
    }
}
