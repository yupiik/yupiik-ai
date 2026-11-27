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
package io.yupiik.ia.agent.github.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.execution.DockerExecutionBackend;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in real-daemon mount/security/lifecycle smoke test; does not emulate an LLM. */
@EnabledIfSystemProperty(named = "github.bot.docker.live", matches = "true")
class DockerLiveTest {
    @TempDir
    Path home;

    @Test
    @EnabledIfSystemProperty(named = "github.bot.docker.egress.image", matches = ".+")
    void enforcedGatewayAllowsConnectButBlocksDirectAndUnlistedDestinations() throws Exception {
        final var image = "yca-egress-live-agent:" + ProcessHandle.current().pid();
        final var context = Files.createDirectory(home.resolve("egress-image"));
        Files.writeString(
                context.resolve("Dockerfile"),
                "FROM python:3-alpine\nCOPY probe.py /probe.py\nENTRYPOINT [\"python\",\"/probe.py\"]\n");
        Files.writeString(context.resolve("probe.py"), """
                import socket, ssl
                def blocked(host, port):
                    try:
                        s = socket.create_connection((host, port), timeout=2)
                    except OSError:
                        return
                    s.close()
                    raise AssertionError('direct route unexpectedly available')
                blocked('1.1.1.1', 443)
                blocked('169.254.169.254', 80)
                blocked('api.github.com', 443)
                def connect(authority):
                    s = socket.create_connection(('egress', 8080), timeout=5)
                    s.sendall(('CONNECT ' + authority + ' HTTP/1.1\\r\\nHost: ' + authority + '\\r\\n\\r\\n').encode())
                    response = b''
                    while not response.endswith(b'\\r\\n\\r\\n'):
                        response += s.recv(1)
                    return s, response
                denied, response = connect('example.com:443')
                assert b'403 Forbidden' in response
                denied.close()
                allowed, response = connect('api.github.com:443')
                assert b'200 Connection Established' in response
                with ssl.create_default_context().wrap_socket(allowed, server_hostname='api.github.com') as tls:
                    tls.sendall(b'GET / HTTP/1.1\\r\\nHost: api.github.com\\r\\nUser-Agent: yca-egress-test\\r\\nConnection: close\\r\\n\\r\\n')
                    assert tls.recv(1024).startswith(b'HTTP/')
                """);
        final var build = new ProcessBuilder("docker", "build", "-t", image, context.toString())
                .inheritIO()
                .start();
        assertTrue(build.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, build.exitValue());
        try {
            final var attempt = Files.createDirectory(home.resolve("egress-attempt"));
            Files.writeString(attempt.resolve("configuration.json"), "{}");
            for (final var name : List.of("storage", "workspace", "output")) {
                Files.createDirectory(attempt.resolve(name));
            }
            final var backend = new DockerExecutionBackend(
                    GithubExecutionPolicy.POLICY,
                    "docker",
                    image,
                    Duration.ofSeconds(40),
                    "",
                    new ExecutorConfiguration.Docker.Egress(
                            System.getProperty("github.bot.docker.egress.image"),
                            List.of("api.github.com"),
                            "127.0.0.1"),
                    null,
                    "shown",
                    false);
            assertEquals(
                    0,
                    backend.execute(
                            attempt,
                            "yca-egress-live-" + ProcessHandle.current().pid(),
                            "probe",
                            Map.of(),
                            Map.of()));
        } finally {
            final var remove = new ProcessBuilder("docker", "image", "rm", image)
                    .inheritIO()
                    .start();
            assertTrue(remove.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, remove.exitValue());
        }
    }

    @Test
    void realContainerIsNonRootRestrictedAndStops() throws Exception {
        final var image = "yca-bot-security-test:" + ProcessHandle.current().pid();
        final var context = Files.createDirectory(home.resolve("image"));
        Files.writeString(
                context.resolve("Dockerfile"),
                "FROM alpine:3.19\nCOPY entrypoint.sh /entrypoint.sh\nENTRYPOINT [\"/bin/sh\",\"/entrypoint.sh\"]\n");
        Files.writeString(context.resolve("entrypoint.sh"), """
                set -eu
                test "$(id -u)" != 0
                test "$GH_TOKEN" = "synthetic-agent-only"
                test -z "${HOST_ONLY_SECRET:-}"
                test ! -e /var/run/docker.sock
                test ! -e /host-state
                test ! -e /proc/1/root/host-state
                test ! -e /attempt/state.json
                if touch /attempt/configuration.json 2>/dev/null; then exit 31; fi
                if touch /root/read-only 2>/dev/null; then exit 32; fi
                grep -q '^NoNewPrivs:[[:space:]]*1' /proc/self/status
                grep -q '^CapEff:[[:space:]]*0000000000000000' /proc/self/status
                printf 'ok' > /attempt/output/security-result
                printf 'real Docker restrictions checked\\n'
                """);
        final var build = new ProcessBuilder("docker", "build", "--pull=false", "-t", image, context.toString())
                .inheritIO()
                .start();
        assertTrue(build.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, build.exitValue());
        try {
            final var attempt = Files.createDirectory(home.resolve("attempt"));
            Files.writeString(attempt.resolve("configuration.json"), "{}");
            for (final var name : List.of("storage", "workspace", "output")) {
                Files.createDirectory(attempt.resolve(name));
            }
            final var runtime = "yca-live-" + ProcessHandle.current().pid();
            final var backend = new DockerExecutionBackend(
                    GithubExecutionPolicy.POLICY,
                    "docker",
                    image,
                    Duration.ofSeconds(20),
                    "",
                    null,
                    null,
                    "shown",
                    false);
            assertEquals(
                    0,
                    backend.execute(
                            attempt,
                            runtime,
                            "synthetic prompt",
                            Map.of("GH_TOKEN", "synthetic-agent-only"),
                            Map.of()));
            backend.stop(runtime);
            assertEquals("ok", Files.readString(attempt.resolve("output/security-result")));
            Files.writeString(context.resolve("entrypoint.sh"), "sleep 120\n");
            final var rebuild = new ProcessBuilder("docker", "build", "--pull=false", "-t", image, context.toString())
                    .inheritIO()
                    .start();
            assertTrue(rebuild.waitFor(60, TimeUnit.SECONDS));
            assertEquals(0, rebuild.exitValue());
            // A real timeout must stop the container before the caller can discard its attempt.
            Files.delete(attempt.resolve("execution.log"));
            final var timed = new DockerExecutionBackend(
                    GithubExecutionPolicy.POLICY,
                    "docker",
                    image,
                    Duration.ofMillis(300),
                    "",
                    null,
                    null,
                    "shown",
                    false);
            assertThrows(
                    IllegalStateException.class,
                    () -> timed.execute(attempt, runtime + "-timeout", "timeout", Map.of(), Map.of()));
            timed.stop(runtime + "-timeout");
        } finally {
            final var remove = new ProcessBuilder("docker", "image", "rm", image)
                    .inheritIO()
                    .start();
            assertTrue(remove.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, remove.exitValue());
        }
    }
}
