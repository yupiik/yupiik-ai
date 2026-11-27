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
package io.yupiik.ia.agent.core.optimize;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KubectlOutputProcessorTest {
    private final KubectlOutputProcessor processor = new KubectlOutputProcessor();

    @Test
    void supportsKubectlOnly() {
        assertTrue(processor.supports("kubectl get pods"));
        assertFalse(processor.supports("docker ps"));
        assertFalse(processor.supports("git status"));
    }

    @Test
    void flagsDegradedRows() {
        final var out = processor
                .process(
                        "kubectl get pods",
                        List.of(
                                "NAME   READY   STATUS    RESTARTS   AGE",
                                "web    1/1     Running   0          5m",
                                "db     0/1     CrashLoopBackOff   3   2m")
                                .stream())
                .toList();
        assertTrue(out.contains("NAME   READY   STATUS    RESTARTS   AGE"));
        assertTrue(out.contains("web    1/1     Running   0          5m"));
        assertTrue(out.stream().anyMatch(l -> l.startsWith("⚠") && l.contains("db")));
    }

    @Test
    void jsonOutputPassesThrough() {
        final var lines = List.of("{\"items\":[]}");
        final var out =
                processor.process("kubectl get pods -o json", lines.stream()).toList();
        assertTrue(out.contains("{\"items\":[]}"));
    }
}
