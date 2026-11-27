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
import io.yupiik.fusion.framework.api.main.CliLauncher;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StdioTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "DeFaUlT", "not-a-variant"})
    void launchedCommandHasOnlyProtocolOnStdout(final String variant, @TempDir final Path tmp) throws Exception {
        try (final var mapper = CodecsTest.mapper()) {
            final var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-XX:TieredStopAtLevel=1");
            command.add("-Duser.home=" + tmp);
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(CliLauncher.class.getName());
            command.addAll(List.of(
                    "acp",
                    // Exercise protocol stdout routing independently of OS sandbox policy.
                    "--sandbox",
                    "false",
                    "--yca-storageHome",
                    tmp.resolve("store").toString(),
                    "--yca-home",
                    tmp.toString(),
                    "--yca-workingDir",
                    tmp.toString(),
                    "--yca-mavenRepositoryKnowledge-enabled",
                    "false",
                    "--yca-structuralCodeGraph-enabled",
                    "false"));
            if (!variant.isEmpty()) {
                command.addAll(List.of("--variant", variant));
            }
            final var process = new ProcessBuilder(command).start();
            final var readers = Executors.newVirtualThreadPerTaskExecutor();
            final var stderr = readers.submit(() -> {
                try {
                    return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            final var writer = new PrintWriter(process.getOutputStream(), true);
            final var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            try {
                writer.println(
                        "{\"jsonrpc\":\"2.0\",\"id\":9007199254740993,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1,\"clientCapabilities\":{}}}");
                if ("not-a-variant".equals(variant)) {
                    writer.close();
                    assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                    final var logs = stderr.get(5, TimeUnit.SECONDS);
                    assertTrue(logs.contains("Unknown ACP variant 'not-a-variant'"), logs);
                    assertTrue(logs.contains("Available variants: default"), logs);
                    assertNull(reader.readLine(), "Startup failure must not pollute protocol stdout");
                    return;
                }
                final var line = readers.submit(() -> {
                            try {
                                return reader.readLine();
                            } catch (final Exception e) {
                                throw new IllegalStateException(e);
                            }
                        })
                        .get(30, TimeUnit.SECONDS);
                assertNotNull(line);
                final var response = mapper.fromString(Envelope.class, line);
                assertEquals("9007199254740993", response.id().toString(), line);
                assertNotNull(response.result(), line);
                writer.println("{broken");
                final var error = mapper.fromString(Envelope.class, reader.readLine());
                assertEquals(-32700, error.error().code());
                writer.println("{\"jsonrpc\":\"2.0\",\"id\":\"new\",\"method\":\"session/new\",\"params\":{\"cwd\":"
                        + mapper.toString(tmp.toString()) + ",\"mcpServers\":[]}}");
                Envelope created;
                do {
                    created = mapper.fromString(Envelope.class, reader.readLine());
                    if (created.id() == null) {
                        assertEquals("session/update", created.method(), created.toString());
                    }
                } while (created.id() == null);
                assertEquals("new", created.id());
                assertNull(created.error(), created.toString());
                assertNotNull(created.result(), created.toString());
                writer.close();
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), stderr.get(5, TimeUnit.SECONDS));
                String rest;
                while ((rest = reader.readLine()) != null) {
                    mapper.fromString(Envelope.class, rest);
                }
                final var logs = stderr.get(5, TimeUnit.SECONDS);
                for (final var phase : List.of("startup", "shutdown")) {
                    assertTrue(logs.contains("direct-" + phase), logs);
                    assertTrue(logs.contains("info-" + phase), logs);
                    assertTrue(logs.contains("warning-" + phase), logs);
                }
            } finally {
                try {
                    // Kill before closing a reader blocked on the process pipe.
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "ACP process leaked");
                    writer.close();
                    reader.close();
                } finally {
                    readers.shutdownNow();
                    assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS), "ACP readers leaked");
                }
            }
        }
    }
}
