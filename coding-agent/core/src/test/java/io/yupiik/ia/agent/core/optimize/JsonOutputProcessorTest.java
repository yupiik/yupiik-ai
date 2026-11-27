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

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestJsonMapper.class)
@FusionSupport(modules = ConfigurationTestModule.class)
class JsonOutputProcessorTest {
    private JsonOutputProcessor processor;

    @BeforeEach
    void init(@Fusion final CodingAgentConfiguration configuration) {
        processor = new JsonOutputProcessor(configuration, null);
    }

    @Test
    void supportsFamilies() {
        assertTrue(processor.supports("kubectl get pods -o json"));
        assertTrue(processor.supports("docker inspect foo"));
        assertTrue(processor.supports("gh api repos/foo"));
        assertTrue(processor.supports("aws s3 ls --output json"));
        assertFalse(processor.supports("git status"));
    }

    @Test
    void passthroughOnNonJson(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        final var p = new JsonOutputProcessor(configuration, mapper);
        final var out =
                p.process("kubectl get pods", Stream.of("NAME READY", "a 1/1")).toList();
        assertEquals(List.of("NAME READY", "a 1/1"), out);
    }

    @Test
    void condensesKubectlJson(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        final var p = new JsonOutputProcessor(configuration, mapper);
        final var json = """
            [{"metadata":{"name":"web-0","namespace":"default"},"status":{"phase":"Running"}},
             {"metadata":{"name":"db-0","namespace":"default"},"status":{"phase":"Pending"}}]
            """;
        final var out =
                p.process("kubectl get pods -o json", Stream.of(json.strip())).collect(joining("\n"));
        assertTrue(out.contains("metadata.name=web-0"));
        assertTrue(out.contains("status.phase=Running"));
        assertTrue(out.contains("metadata.name=db-0"));
        assertFalse(out.contains("\"metadata\""));
    }

    @Test
    void condensesDockerInspect(final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        final var p = new JsonOutputProcessor(configuration, mapper);
        final var json = """
            [{"Id":"abc123","Image":"nginx:1.25","State":{"Status":"running"},"NetworkSettings":{"Ports":{"80/tcp":null}}}]
            """;
        final var out =
                p.process("docker inspect abc123", Stream.of(json.strip())).collect(joining("\n"));
        assertTrue(out.contains("Id=abc123"));
        assertTrue(out.contains("Image=nginx:1.25"));
    }

    @Test
    void malformedJsonFallsBackToPassthrough(
            final JsonMapper mapper, @Fusion final CodingAgentConfiguration configuration) {
        final var p = new JsonOutputProcessor(configuration, mapper);
        final var out =
                p.process("kubectl get pods -o json", Stream.of("{not json")).toList();
        assertEquals(List.of("{not json"), out);
    }
}
