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
package io.yupiik.ia.agent.bot.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExecutionPolicyTest {
    @Test
    void labelsDependOnExplicitAuthorityNotRuntimePrefix() {
        final var policy = new ExecutionPolicy(
                "fixture.instance", new ExecutionPolicy.Gateway("fixture.Gateway", Set.of(), Set.of()));
        for (final var name : List.of("yca-example", "yca-slack-example", "unbranded")) {
            assertEquals(
                    List.of(
                            "--label",
                            "app=fixture.instance",
                            "--label",
                            ExecutionPolicy.RUNTIME_LABEL + '=' + name,
                            "--label",
                            ExecutionPolicy.COMPONENT_LABEL + "=agent"),
                    policy.labels(name, "agent"));
            assertEquals("fixture.instance|" + name + "|agent", policy.ownership(name, "agent"));
        }
    }

    @Test
    void gatewayContractIsImmutableValidatedAndDeterministic() {
        final var allowed = new HashSet<>(Set.of("TOKEN_Z", "TOKEN_A"));
        final var gateway = new ExecutionPolicy.Gateway("fixture.Gateway", allowed, Set.of("TOKEN_A"));
        allowed.clear();
        assertEquals(Set.of("TOKEN_A", "TOKEN_Z"), gateway.allowedTokens());
        assertThrows(
                UnsupportedOperationException.class,
                () -> gateway.allowedTokens().clear());
        assertEquals(
                new ExecutionPolicy.Gateway("fixture.Gateway", Set.of("TOKEN_A", "TOKEN_Z"), Set.of("TOKEN_A"))
                        .toString(),
                gateway.toString());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPolicy.Gateway("--main", Set.of(), Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionPolicy.Gateway("fixture.Gateway", Set.of("TOKEN"), Set.of("OTHER")));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPolicy("invalid|owner", gateway));
    }
}
