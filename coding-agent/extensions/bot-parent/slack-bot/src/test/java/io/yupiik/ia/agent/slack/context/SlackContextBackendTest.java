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
package io.yupiik.ia.agent.slack.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import io.yupiik.fusion.kubernetes.client.KubernetesClientConfiguration;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.bot.runtime.execution.DockerExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.execution.KubernetesExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.execution.NonoExecutionBackend;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.slack.configuration.AgentSlackContextConfiguration;
import io.yupiik.ia.agent.slack.context.agent.SlackContextClient;
import io.yupiik.ia.agent.slack.context.agent.SlackContextTool;
import io.yupiik.ia.agent.slack.service.SlackExecutionPolicy;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real backend handoff with fake launchers/API, not Docker, Nono or a Kubernetes cluster. */
@Timeout(15)
@FusionSupport(modules = SlackConfigurationTestModule.class)
class SlackContextBackendTest {
    @Fusion
    private JsonMapper json;

    @TempDir
    private static Path testWorkdir;

    private static final String DIRECTORY = "/attempt/storage/slack-context";
    private static final String BINDING = "CBINDING";

    @TempDir
    Path home;

    @Test
    void dockerMountsPreparedContextUnderTheSameWritableStorageDirectory() throws Exception {
        final var attempt = attempt();
        nonRoot(attempt);
        final var original = Files.readString(attempt.resolve("configuration.json"));
        final var executable = script("docker", """
            for last; do :; done
            if [ "$2" = inspect ]; then
              case "$last" in
                *-services|*-egress) printf 'Error: No such container: %s\\n' "$last"; exit 1 ;;
                *-services-net|*-external) printf 'Error: No such network: %s\\n' "$last"; exit 1 ;;
              esac
            fi
            printf '%s\\n' "$@" > "$0.args"
            """);
        final var backend = new DockerExecutionBackend(
                SlackExecutionPolicy.POLICY,
                executable.toString(),
                "trusted-image",
                Duration.ofSeconds(5),
                "",
                null,
                null,
                "hidden",
                false);
        assertEquals(0, backend.execute(attempt, "yca-slack-context", "summary", Map.of(), Map.of()));
        final var args = Files.readAllLines(Path.of(executable + ".args"));
        assertTrue(args.contains("type=bind,src=" + attempt.resolve("storage") + ",dst=/attempt/storage"));
        assertTrue(args.contains("type=bind,src=" + attempt.resolve("configuration.json")
                + ",dst=/attempt/configuration.json,readonly"));
        assertEquals("/attempt/configuration.json", option(args, "--yca-configuration-json"));
        assertEquals("/attempt/storage", option(args, "--yca-storage-home"));
        assertEquals("false", option(args, "--yca-configuration-project-enabled"));
        assertEquals(original, Files.readString(attempt.resolve("configuration.json")));
        {
            final var context = context(json, attempt.resolve("configuration.json"));
            assertEquals(DIRECTORY, context.get("directory"));
            assertUsable(json, context, attempt.resolve("storage/slack-context"));
        }
    }

    @Test
    void nonoRecursivelyRelocatesContextAndGrantsItsExistingStorageMailbox() throws Exception {
        final var attempt = attempt();
        nonRoot(attempt);
        final var original = Files.readString(attempt.resolve("configuration.json"));
        final var runtime = Files.createDirectories(home.resolve("runtime/bin")).getParent();
        final var agent = Files.writeString(runtime.resolve("agent"), "trusted agent");
        final var state = Files.createDirectory(
                home.resolve("nono-state"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var executable = script("nono", """
            if [ "$1" = run ]; then printf '%s\\n' "$@" > "$0.args"; exit 0; fi
            printf '[{"session_id":"0123456789abcdef","status":"exited","exit_code":0}]'
            """);
        {
            final var backend = new NonoExecutionBackend(
                    json,
                    executable.toString(),
                    agent,
                    runtime,
                    state,
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(3),
                    "hidden");
            assertEquals(0, backend.execute(attempt, "yca-slack-context", "summary", Map.of(), Map.of()));
            final var args = Files.readAllLines(Path.of(executable + ".args"));
            final var staged = state.resolve("yca-slack-context/configuration.json");
            assertEquals(staged.toString(), option(args, "--yca-configuration-json"));
            assertEquals(attempt.resolve("storage").toString(), option(args, "--yca-storage-home"));
            assertEquals("false", option(args, "--yca-configuration-project-enabled"));
            final var document = json.fromString(AgentConfigurationDocument.class, Files.readString(staged));
            final var context = map(document.configuration().get("slackContext"));
            assertEquals(attempt.resolve("storage/slack-context").toString(), context.get("directory"));
            assertEquals(BINDING, context.get("channel"));
            assertEquals(
                    List.of(
                            Map.of(
                                    "mailbox",
                                    attempt.resolve("storage/slack-context").toString()),
                            List.of(attempt.resolve("storage/slack-context/request.json")
                                    .toString())),
                    document.configuration().get("nested"));
            assertEquals(
                    "outside /attempt-other/slack-context",
                    document.configuration().get("literal"));
            assertEquals(
                    "Repair " + attempt.resolve("storage/slack-context"),
                    map(document.completionSettings().get("run")).get("result-correction-prompt"));
            final var profile = map(
                    json.fromString(Object.class, Files.readString(state.resolve("yca-slack-context/profile.json"))));
            final var writable = (List<?>) map(profile.get("filesystem")).get("allow");
            assertTrue(writable.contains(attempt.resolve("storage").toString()));
            assertFalse(writable.contains(attempt.toString()));
            assertEquals(original, Files.readString(attempt.resolve("configuration.json")));
            assertUsable(json, context, Path.of(context.get("directory").toString()));
        }
    }

    @Test
    void kubernetesMountsContextFromTheSameAttemptPvcSubPathWithoutRewritingConfiguration() throws Exception {
        final var attempt = attempt();
        final var original = Files.readString(attempt.resolve("configuration.json"));
        try (final var cluster = new Cluster();
                final var client = cluster.client()) {
            final var backend = new KubernetesExecutionBackend(
                    json,
                    client,
                    "bots",
                    "attempts",
                    "trusted-image",
                    home,
                    1000,
                    1000,
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(3),
                    "yca-agent",
                    "hidden");
            assertEquals(0, backend.execute(attempt, "yca-slack-context", "summary", Map.of(), Map.of()));
            final var job = map(json.fromString(Object.class, cluster.createdJob));
            final var pod = map(map(map(job.get("spec")).get("template")).get("spec"));
            final var agent = map(((List<?>) pod.get("containers")).getFirst());
            final var mounts = (List<?>) agent.get("volumeMounts");
            final var storage = mounts.stream()
                    .map(SlackContextBackendTest::map)
                    .filter(mount -> "/attempt/storage".equals(mount.get("mountPath")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("attempt", storage.get("name"));
            assertEquals("renamed attempt with spaces/storage", storage.get("subPath"));
            assertEquals(false, storage.get("readOnly"));
            final var config = mounts.stream()
                    .map(SlackContextBackendTest::map)
                    .filter(mount -> "/attempt/configuration.json".equals(mount.get("mountPath")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("renamed attempt with spaces/configuration.json", config.get("subPath"));
            assertEquals(true, config.get("readOnly"));
            assertTrue(((List<?>) pod.get("volumes"))
                    .stream()
                            .map(SlackContextBackendTest::map)
                            .anyMatch(volume -> "attempt".equals(volume.get("name"))
                                    && Map.of("claimName", "attempts").equals(volume.get("persistentVolumeClaim"))));
            final var args =
                    ((List<?>) agent.get("args")).stream().map(Object::toString).toList();
            assertEquals("/attempt/configuration.json", option(args, "--yca-configuration-json"));
            assertEquals("/attempt/storage", option(args, "--yca-storage-home"));
            assertEquals("false", option(args, "--yca-configuration-project-enabled"));
            assertEquals(original, Files.readString(attempt.resolve("configuration.json")));
            final var context = context(json, attempt.resolve("configuration.json"));
            assertEquals(DIRECTORY, context.get("directory"));
            final var mountedMailbox =
                    home.resolve(storage.get("subPath").toString()).resolve("slack-context");
            assertEquals(attempt.resolve("storage/slack-context"), mountedMailbox);
            assertUsable(json, context, mountedMailbox);
        }
    }

    private Path attempt() throws Exception {
        final var attempt = Files.createDirectory(home.resolve("renamed attempt with spaces"));
        for (final var child : List.of("storage", "workspace", "output")) {
            Files.createDirectory(attempt.resolve(child));
        }
        Files.createDirectory(
                attempt.resolve("storage/slack-context"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        // Worker-prepared shape, with nested maps/lists to guard Nono's recursive relocation.
        Files.writeString(attempt.resolve("configuration.json"), """
            {"yca":{"slackContext":{"enabled":true,"directory":"/attempt/storage/slack-context",
            "channel":"CBINDING","timeoutSeconds":3},
            "nested":[{"mailbox":"/attempt/storage/slack-context"},["/attempt/storage/slack-context/request.json"]],
            "literal":"outside /attempt-other/slack-context"},
            "run":{"result-correction-prompt":"Repair /attempt/storage/slack-context"}}
            """);
        return attempt;
    }

    private void nonRoot(final Path attempt) throws Exception {
        assumeTrue("Linux".equals(System.getProperty("os.name")));
        assumeTrue(
                ((Number) Files.getAttribute(attempt, "unix:uid")).longValue() != 0,
                "Production Docker/Nono executors deliberately reject root-owned attempts");
    }

    private Path script(final String name, final String body) throws Exception {
        final var executable = home.resolve(name + ".sh");
        Files.writeString(executable, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return executable;
    }

    private Map<?, ?> context(final JsonMapper json, final Path file) throws Exception {
        return map(json.fromString(AgentConfigurationDocument.class, Files.readString(file))
                .configuration()
                .get("slackContext"));
    }

    private void assertUsable(final JsonMapper json, final Map<?, ?> context, final Path hostMailbox) {
        assertEquals(true, context.get("enabled"));
        assertEquals(BINDING, context.get("channel"));
        assertEquals(3, ((Number) context.get("timeoutSeconds")).intValue());
        // Docker/Kubernetes resolve the container path via the captured storage mount; Nono uses it directly.
        final var tool = new SlackContextTool(new SlackContextClient(
                new AgentSlackContextConfiguration(
                        true,
                        hostMailbox.toString(),
                        context.get("channel").toString(),
                        ((Number) context.get("timeoutSeconds")).intValue()),
                json));
        assertTrue(tool.usable(testWorkdir), "The launched agent's directory must address the prepared host mailbox");
    }

    private String option(final List<String> args, final String key) {
        final int index = args.indexOf(key);
        assertTrue(index >= 0, key);
        return args.get(index + 1);
    }

    private static Map<?, ?> map(final Object value) {
        return (Map<?, ?>) value;
    }

    /** Minimal loopback API for the real Kubernetes client's Job/Secret lifecycle. */
    private static final class Cluster implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private volatile String createdJob;
        private volatile boolean job;
        private volatile boolean secret;

        private Cluster() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    final var path = exchange.getRequestURI().getPath();
                    final var method = exchange.getRequestMethod();
                    final var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    int status = 200;
                    String output = "{}";
                    if ("POST".equals(method)) {
                        status = 201;
                        if (path.endsWith("/jobs")) {
                            createdJob = body;
                            job = true;
                        } else {
                            secret = true;
                        }
                    } else if ("DELETE".equals(method)) {
                        if (path.contains("/jobs/")) {
                            job = false;
                        } else {
                            secret = false;
                        }
                    } else if (path.endsWith("/pods")) {
                        output = job ? """
                            {"items":[{"metadata":{"name":"yca-pod"},"status":{"phase":"Succeeded",
                            "containerStatuses":[{"state":{"terminated":{"exitCode":0}}}]}}]}
                            """ : "{\"items\":[]}";
                    } else if (path.endsWith("/log")) {
                        output = "agent output\n";
                    } else if ((path.contains("/jobs/") && !job) || (path.contains("/secrets/") && !secret)) {
                        status = 404;
                    }
                    final var bytes = output.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        private KubernetesClient client() {
            return new KubernetesClient(new KubernetesClientConfiguration()
                    .setMaster("http://127.0.0.1:" + server.getAddress().getPort())
                    .setTokenEvaluator(() -> "test-cluster-token"));
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
