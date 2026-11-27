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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ai.acp.protocol.*;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.session.SessionStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = OptionConfigurationTestModule.class)
class FramingTest {
    @Fusion
    private CodingAgentConfiguration configuration;

    @Fusion
    private AcpCommand.Conf commandOptions;

    @Test
    void acceptsLargeMessagesCrLfAndFinalLineWithoutNewline() throws Exception {
        try (final var mapper = CodecsTest.mapper()) {
            final var largeId = "x".repeat(8 * 1024 * 1024 + 1);
            final var input = "{\"jsonrpc\":\"2.0\",\"id\":\"" + largeId
                    + "\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{}}}\r\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":\"final\",\"method\":\"session/prompt\",\"params\":{}}";
            final var output = exchange(input.getBytes(StandardCharsets.UTF_8));
            final var lines = output.lines().toList();
            assertEquals(2, lines.size());
            final var initialized = mapper.fromString(Envelope.class, lines.getFirst());
            assertEquals(largeId, initialized.id());
            assertNotNull(initialized.result());
            assertEquals(
                    "final", mapper.fromString(Envelope.class, lines.getLast()).id());
        }
    }

    @Test
    void rejectsMalformedUtf8() throws Exception {
        assertEquals("", exchange(new byte[] {(byte) 0xff, '\n'}));
    }

    private String exchange(final byte[] bytes) throws Exception {
        try (final var mapper = CodecsTest.mapper();
                final var support = new OptionTestSupport(configuration, commandOptions, mapper, settings -> {
                    fail("Framing must not discover models");
                    return null;
                });
                final var input = new ByteArrayInputStream(bytes);
                final var output = new ByteArrayOutputStream();
                final var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            new AcpServer(
                            mapper,
                            null,
                            null,
                            null,
                            new SessionStore() {},
                            null,
                            null,
                            null,
                            support.options,
                            null,
                            null,
                            List.of(),
                            null,
                            null,
                            null,
                            support.providers,
                            support.configuration,
                            null,
                            null,
                            support.commandOptions)
                    .run(input, stream, ChatSettings.EMPTY);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
