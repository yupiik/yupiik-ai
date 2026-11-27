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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DockerOutputProcessorTest {
    private final DockerOutputProcessor processor = new DockerOutputProcessor();

    @Test
    void supportsDockerOnly() {
        assertTrue(processor.supports("docker ps"));
        assertTrue(processor.supports("docker compose up"));
        assertFalse(processor.supports("kubectl get pods"));
    }

    @Test
    void keepsEssentialPsColumns() {
        final var out = processor
                .process(
                        "docker ps",
                        List.of(
                                "CONTAINER ID   IMAGE     COMMAND   CREATED   STATUS         PORTS     NAMES",
                                "abc123         nginx:1    \"/x\"      2m ago    Up 2 minutes   80->80    web")
                                .stream())
                .toList();
        assertEquals(List.of("CONTAINER ID  IMAGE  STATUS  NAMES", "abc123  nginx:1  Up 2 minutes  web"), out);
    }

    @Test
    void dedupesLogsTail() {
        final var out = processor
                .process("docker logs web", List.of("line1", "line1", "line1", "line2").stream())
                .toList();
        assertEquals(List.of("3 x line1", "line2"), out);
    }

    @Test
    void composeKeepsServiceLines() {
        final var out = processor
                .process(
                        "docker compose up",
                        List.of(
                                "[+] Running 2/2",
                                " ✔ Container web  Started",
                                " ✔ Container db  Running",
                                "some noise")
                                .stream())
                .toList();
        assertTrue(out.stream().anyMatch(l -> l.contains("Started")));
        assertFalse(out.stream().anyMatch(l -> l.equals("some noise")));
    }
}
