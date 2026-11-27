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
package io.yupiik.ia.agent.bot.runtime.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.yupiik.ia.agent.bot.runtime.ExecutionPolicy;
import io.yupiik.ia.agent.bot.runtime.ProviderBudgetEnvironment;
import io.yupiik.ia.agent.bot.runtime.TestScript;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.testing.UnlinkingTempDirDeletionStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DockerRestrictedServicesTest {
    @TempDir(deletionStrategy = UnlinkingTempDirDeletionStrategy.class)
    Path home;

    @ParameterizedTest
    @MethodSource("io.yupiik.ia.agent.bot.runtime.execution.ExecutionPolicies#runtimes")
    void providerBudgetEnvironmentIsMappedOnlyIntoTheServicesGateway(final ExecutionPolicy policy, final String runtime)
            throws Exception {
        nonRoot();
        final var privateRoot = Files.createDirectory(home.resolve("controller"));
        final var settings = Files.move(settings(), privateRoot.resolve("settings.json"));
        final var budget = Files.createDirectory(
                home.resolve("budget"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        final var supplied = new HashMap<>(secrets(policy));
        supplied.put(ProviderBudgetEnvironment.ROOT, budget.toString());
        supplied.put(ProviderBudgetEnvironment.ID, "shared-provider_42");
        supplied.put(ProviderBudgetEnvironment.SLOTS, "7");
        final var executable = fake(policy, true, "");
        final var services = new DockerRestrictedServices(
                policy, executable.toString(), configuration("http://172.20.0.1:8080/v1"), Duration.ofMillis(500));
        services.start(runtime, settings, supplied);

        final var args = Files.readAllLines(Path.of(executable + ".create.args"));
        final var mount = "type=bind,src=" + budget + ",dst=/services/budget";
        assertEquals(1, args.stream().filter(mount::equals).count());
        assertEquals("--mount", args.get(args.indexOf(mount) - 1));
        final var environment = Files.readAllLines(Path.of(executable + ".create.env"));
        assertTrue(environment.contains(ProviderBudgetEnvironment.ROOT + "=/services/budget"));
        assertTrue(environment.contains(ProviderBudgetEnvironment.ID + "=shared-provider_42"));
        assertTrue(environment.contains(ProviderBudgetEnvironment.SLOTS + "=7"));
        for (final var key : ProviderBudgetEnvironment.KEYS) {
            assertEquals(1, args.stream().filter(key::equals).count());
            assertEquals("--env", args.get(args.indexOf(key) - 1));
        }
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals(
                1,
                calls.stream().filter(call -> call.contains(budget.toString())).count());
        assertTrue(calls.stream()
                .filter(call -> call.contains(budget.toString()))
                .allMatch(call -> call.startsWith("create --name " + runtime + "-services ") && call.contains(mount)));
        for (final var value : secrets().values()) {
            assertFalse(String.join("\n", calls).contains(value), "Secret value leaked into command arguments");
        }
        final var agent = String.join(" ", services.agentArguments(runtime));
        assertFalse(agent.contains(budget.toString()));
        assertFalse(agent.contains("/services/budget"));
        for (final var key : ProviderBudgetEnvironment.KEYS) {
            assertFalse(agent.contains(key));
        }
        assertEquals(budget.toString(), supplied.get(ProviderBudgetEnvironment.ROOT));
    }

    @ParameterizedTest
    @MethodSource("io.yupiik.ia.agent.bot.runtime.execution.ExecutionPolicies#runtimes")
    void unsafeAndOrphanProviderBudgetsFailBeforeAnyDockerCommand(final ExecutionPolicy policy, final String runtime)
            throws Exception {
        nonRoot();
        final var privateRoot = Files.createDirectory(home.resolve("controller"));
        final var settings = Files.move(settings(), privateRoot.resolve("settings.json"));
        final var budget = Files.createDirectory(home.resolve("budget"));
        final var leafLink = Files.createSymbolicLink(home.resolve("budget-link"), budget);
        final var child = Files.createDirectory(budget.resolve("child"));
        final var executable = fake(policy, true, "");
        final var services = new DockerRestrictedServices(
                policy, executable.toString(), configuration("http://172.20.0.1:8080/v1"), Duration.ofMillis(500));
        final var invalid = new ArrayList<Map<String, String>>();
        for (final var root : List.of(
                "relative-budget",
                home.resolve("missing").toString(),
                home.resolve("tls.p12").toString(),
                home.resolve("budget,unsafe").toString(),
                budget.resolve("..").resolve("budget").toString(),
                privateRoot.toString(),
                home.toString(),
                leafLink.toString(),
                leafLink.resolve(child.getFileName()).toString())) {
            invalid.add(Map.of(
                    ProviderBudgetEnvironment.ROOT, root,
                    ProviderBudgetEnvironment.ID, "shared-provider_42",
                    ProviderBudgetEnvironment.SLOTS, "7"));
        }
        invalid.add(Map.of(ProviderBudgetEnvironment.ID, "orphan-id"));
        invalid.add(Map.of(ProviderBudgetEnvironment.SLOTS, "7"));
        invalid.add(Map.of(
                ProviderBudgetEnvironment.ROOT, "",
                ProviderBudgetEnvironment.ID, "orphan-id",
                ProviderBudgetEnvironment.SLOTS, "7"));
        invalid.add(Map.of(ProviderBudgetEnvironment.ROOT, budget.toString()));
        invalid.add(Map.of(
                ProviderBudgetEnvironment.ROOT, budget.toString(),
                ProviderBudgetEnvironment.ID, "invalid/id",
                ProviderBudgetEnvironment.SLOTS, "7"));
        invalid.add(Map.of(
                ProviderBudgetEnvironment.ROOT, budget.toString(),
                ProviderBudgetEnvironment.ID, "shared-provider_42",
                ProviderBudgetEnvironment.SLOTS, "0"));
        for (final var values : invalid) {
            final var supplied = new HashMap<>(secrets(policy));
            supplied.putAll(values);
            assertThrows(IllegalArgumentException.class, () -> services.start(runtime, settings, supplied));
            assertFalse(Files.exists(Path.of(executable + ".calls")));
            assertFalse(Files.exists(Path.of(executable + ".container")));
            assertFalse(Files.exists(Path.of(executable + ".network")));
        }
    }

    @ParameterizedTest
    @MethodSource("io.yupiik.ia.agent.bot.runtime.execution.ExecutionPolicies#runtimes")
    void explicitPolicyLabelsAllServicesResourcesAndAgentArguments(final ExecutionPolicy policy, final String runtime)
            throws Exception {
        nonRoot();
        final var settings = settings();
        final var executable =
                fake(policy, false, "if [ \"$1\" = create ]; then printf '%s\\n' \"$@\" > \"$0.create.args\"; fi\n");
        final var services = new DockerRestrictedServices(
                policy, executable.toString(), configuration("http://172.20.0.1:8080/v1"), Duration.ofMillis(500));
        final var supplied = new HashMap<>(secrets(policy));
        services.start(runtime, settings, supplied);
        final var app = policy.applicationId();
        final var labelled = new ArrayList<>(Files.readAllLines(Path.of(executable + ".calls")).stream()
                .filter(call -> call.startsWith("network create ") || call.startsWith("create "))
                .toList());
        labelled.add(String.join(" ", services.agentArguments(runtime)));
        assertEquals(3, labelled.size());
        for (final var call : labelled) {
            assertTrue(call.contains("--label app=" + app + " --label"));
            assertTrue(call.contains("--label io.yupiik.yca.bot.runtime=" + runtime + " --label"));
            assertFalse(call.contains("io.yupiik.yca.github-bot.runtime="));
        }
        final var agent = services.agentArguments(runtime);
        assertTrue(agent.contains("io.yupiik.yca.bot.component=agent"));
        assertTrue(Files.readAllLines(Path.of(executable + ".create.args"))
                .contains("io.yupiik.yca.bot.component=services"));
    }

    @ParameterizedTest
    @MethodSource("io.yupiik.ia.agent.bot.runtime.execution.ExecutionPolicies#runtimes")
    void foreignApplicationWithSameRuntimeNeverDeletesResources(final ExecutionPolicy policy, final String runtime)
            throws Exception {
        final var other = policy == ExecutionPolicies.CONTEXT ? ExecutionPolicies.PROVIDER : ExecutionPolicies.CONTEXT;
        for (final var suffix : List.of("-services", "-services-net", "-egress", "-external")) {
            final var component =
                    suffix.equals("-egress") || suffix.equals("-external") ? "dependency-egress" : "services";
            final var executable = fake(
                    policy,
                    false,
                    "for last; do :; done\n"
                            + "if [ \"$2\" = inspect ] && [ \"$last\" = " + runtime + suffix
                            + " ]; then printf '" + other.ownership(runtime, component) + "\\n'; exit 0; fi\n");
            Files.createFile(Path.of(executable + ".container"));
            Files.createFile(Path.of(executable + ".network"));
            final var error = assertThrows(
                    IllegalStateException.class,
                    () -> DockerRestrictedServices.stopOwned(
                            policy, executable.toString(), Duration.ofMillis(500), runtime));
            assertEquals("Restricted services ownership or absence could not be verified", error.getMessage());
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            assertTrue(calls.stream()
                    .anyMatch(call -> call.contains(" inspect --format ") && call.endsWith(" " + runtime + suffix)));
            assertFalse(calls.stream().anyMatch(call -> call.startsWith("rm ") || call.startsWith("network rm ")));
        }
    }

    @ParameterizedTest
    @MethodSource("io.yupiik.ia.agent.bot.runtime.execution.ExecutionPolicies#runtimes")
    void policySelectsEntrypointAndAllowedSecretsForLaunchAndReadiness(
            final ExecutionPolicy policy, final String runtime) throws Exception {
        nonRoot();
        final var executable = fake(policy, true, "");
        final var config = configuration("http://172.20.0.1:8080/v1");
        final var supplied = new HashMap<>(secrets(policy));
        if (!policy.gateway().requiredTokens().contains("YCA_SERVICES_UPSTREAM_TOKEN")) {
            supplied.remove("YCA_SERVICES_UPSTREAM_TOKEN");
        }
        new DockerRestrictedServices(policy, executable.toString(), config, Duration.ofSeconds(1))
                .start(runtime, settings(), supplied);
        final var entrypoint = policy.gateway().mainClass();
        final var args = Files.readAllLines(Path.of(executable + ".create.args"));
        assertTrue(args.contains(entrypoint));
        assertTrue(args.contains("type=bind,src=" + home.resolve("tls.p12") + ",dst=/services/tls.p12,readonly"));
        for (final var token : secrets().keySet()) {
            assertEquals(supplied.containsKey(token), args.contains(token), token);
        }
        assertFalse(args.stream().anyMatch(arg -> arg.contains("tls-secret") || arg.contains("p".repeat(43))));
        assertTrue(Files.readAllLines(Path.of(executable + ".calls")).stream()
                .anyMatch(call ->
                        call.startsWith("exec ") && call.contains(entrypoint + " --check /services/config.json")));
        DockerRestrictedServices.stopOwned(policy, executable.toString(), Duration.ofSeconds(1), runtime);
    }

    @Test
    void contextPolicyUsesGithubEntrypoint() throws Exception {
        nonRoot();
        final var executable = fake(true, "");
        final var services = new DockerRestrictedServices(
                ExecutionPolicies.CONTEXT,
                executable.toString(),
                configuration("http://172.20.0.1:8080/v1"),
                Duration.ofSeconds(1));
        services.start("yca-legacy-fixture", settings(), secrets());
        final var entrypoint = "io.yupiik.ia.agent.github.execution.RestrictedServicesGateway";
        assertTrue(Files.readAllLines(Path.of(executable + ".create.args")).contains(entrypoint));
        assertTrue(Files.readAllLines(Path.of(executable + ".calls")).stream()
                .anyMatch(call ->
                        call.startsWith("exec ") && call.contains(entrypoint + " --check /services/config.json")));
        DockerRestrictedServices.stopOwned(
                ExecutionPolicies.CONTEXT, executable.toString(), Duration.ofSeconds(1), "yca-legacy-fixture");
    }

    @ParameterizedTest
    @MethodSource("io.yupiik.ia.agent.bot.runtime.execution.ExecutionPolicies#runtimes")
    void policyRequiresItsSecretsAndRejectsUnapprovedSecretsBeforeDocker(
            final ExecutionPolicy policy, final String runtime) throws Exception {
        nonRoot();
        final var settings = settings();
        final var executable = fake(policy, false, "");
        final var services = new DockerRestrictedServices(
                policy, executable.toString(), configuration("http://172.20.0.1:8080/v1"), Duration.ofSeconds(1));
        for (final var key : policy.gateway().requiredTokens()) {
            final var missing = new HashMap<>(secrets(policy));
            missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> services.start(runtime, settings, missing), key);
        }
        for (final var key : List.of("GH_TOKEN", "YCA_CONTEXT_TOKEN", "YCA_CONTEXT_GIT_TOKEN", "UNAPPROVED_TOKEN")) {
            if (!policy.gateway().allowedTokens().contains(key)) {
                final var forbidden = new HashMap<>(secrets(policy));
                forbidden.put(key, "x".repeat(43));
                assertThrows(IllegalArgumentException.class, () -> services.start(runtime, settings, forbidden), key);
            }
        }
        assertFalse(Files.exists(Path.of(executable + ".calls")));
    }

    @Test
    void fakeDockerCapturesDetailsOnlyWhenRequestedButAlwaysRecordsCallsAndResources() throws Exception {
        for (final var captureDetails : List.of(false, true)) {
            final var executable = fake(captureDetails, "");
            final var commands = List.of(
                    List.of("network", "create", "yca-fixture-services-net"),
                    List.of("network", "create", "yca-fixture-external"),
                    List.of("create", "--name", "yca-fixture-services"),
                    List.of("create", "--name", "yca-fixture-egress"),
                    List.of("run", "agent"));
            for (final var command : commands) {
                final var argv = new ArrayList<String>();
                argv.add(executable.toString());
                argv.addAll(command);
                assertEquals(0, new ProcessBuilder(argv).inheritIO().start().waitFor());
            }
            assertEquals(
                    commands.stream().map(command -> String.join(" ", command)).toList(),
                    Files.readAllLines(Path.of(executable + ".calls")));
            for (final var suffix : List.of(".network", ".container", ".yca-fixture-external", ".yca-fixture-egress")) {
                assertTrue(Files.exists(Path.of(executable + suffix)), suffix);
            }
            for (final var kind : List.of("create", "proxy", "run")) {
                assertEquals(captureDetails, Files.exists(Path.of(executable + "." + kind + ".args")), kind);
                assertEquals(captureDetails, Files.exists(Path.of(executable + "." + kind + ".env")), kind);
            }
        }
    }

    @Test
    void dependencyProxySharesOnlyTheServicesNetworkAndBypassesOnlyTheServicesName() throws Exception {
        nonRoot();
        final var executable = fake(true, "");
        final var config = defaultConfiguration();
        assertEquals(
                0,
                new DockerExecutionBackend(
                                ExecutionPolicies.CONTEXT,
                                executable.toString(),
                                "agent",
                                Duration.ofSeconds(3),
                                "",
                                null,
                                config,
                                "shown",
                                false)
                        .execute(
                                attempt(),
                                "yca-dependencies",
                                "review",
                                Map.of(),
                                Map.of(),
                                settings(),
                                secrets(),
                                null));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals(
                2, calls.stream().filter(c -> c.startsWith("network create ")).count());
        assertTrue(
                calls.contains("network connect --alias egress yca-dependencies-services-net yca-dependencies-egress"));
        final var proxy = Files.readAllLines(Path.of(executable + ".proxy.args"));
        assertEquals("yca-dependencies-external", proxy.get(proxy.indexOf("--network") + 1));
        assertTrue(proxy.containsAll(List.of(
                "--read-only",
                "--cap-drop=ALL",
                "net.ipv4.ip_forward=0",
                "net.ipv6.conf.all.forwarding=0",
                "*.docker.io",
                "github.com")));
        assertFalse(proxy.contains("--mount"));
        assertFalse(Files.readString(Path.of(executable + ".proxy.env")).contains("YCA_SERVICES_"));
        final var agent = Files.readAllLines(Path.of(executable + ".run.args"));
        assertEquals(1, agent.stream().filter("--network"::equals).count());
        assertTrue(agent.contains("HTTPS_PROXY=http://egress:8080"));
        assertTrue(agent.contains("NO_PROXY=localhost,127.0.0.1,127.0.0.0/8,::1,[::1],attempt-services.yca.internal"));
        assertTrue(agent.contains("no_proxy=localhost,127.0.0.1,127.0.0.0/8,::1,[::1],attempt-services.yca.internal"));
        assertTrue(
                agent.stream()
                        .anyMatch(
                                a -> a.startsWith("JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=egress")
                                        && a.endsWith(
                                                "-Dhttp.nonProxyHosts=localhost|127.*|[::1]|::1|[0:0:0:0:0:0:0:1]|0:0:0:0:0:0:0:1|attempt-services.yca.internal")));
        assertTrue(agent.contains("MAVEN_USER_HOME=/attempt/storage/.m2"));
        assertTrue(agent.contains("MAVEN_OPTS=-Duser.home=/attempt/storage"));
        assertEquals(
                List.of(
                        "rm --force yca-dependencies-egress",
                        "network rm yca-dependencies-external",
                        "rm --force yca-dependencies-services",
                        "network rm yca-dependencies-services-net"),
                calls.subList(calls.size() - 4, calls.size()));
    }

    @Test
    void emptyHostsAndExplicitDisableKeepServicesConfinementWithoutAProxy() {
        for (final var policy : List.of(
                new ExecutorConfiguration.Docker.Egress(null, List.of(), null, true),
                new ExecutorConfiguration.Docker.Egress(null, List.of("github.com"), null, false))) {
            final var config = new ExecutorConfiguration.Docker.Services(
                    "gateway",
                    "trusted-control",
                    home.resolve("tls.p12").toString(),
                    null,
                    null,
                    "http://172.20.0.1:8080/v1",
                    policy);
            final var args = new DockerRestrictedServices(
                            ExecutionPolicies.CONTEXT, "docker", config, Duration.ofSeconds(1))
                    .agentArguments("yca-disabled");
            assertEquals("127.0.0.1", args.get(args.indexOf("--dns") + 1));
            assertEquals(1, args.stream().filter("--network"::equals).count());
            assertTrue(args.contains("HTTPS_PROXY="));
            assertFalse(args.contains("HTTPS_PROXY=http://egress:8080"));
        }
    }

    @Test
    void customServicesNameIsTheOnlyProxyBypassAndReservedAliasIsRejected() {
        final var config = new ExecutorConfiguration.Docker.Services(
                "gateway",
                "trusted-control",
                home.resolve("tls.p12").toString(),
                null,
                "custom-services.yca.internal",
                "http://172.20.0.1:8080/v1");
        final var args = new DockerRestrictedServices(
                        ExecutionPolicies.CONTEXT, "docker", config, Duration.ofSeconds(1))
                .agentArguments("yca-custom");
        assertTrue(args.contains("NO_PROXY=localhost,127.0.0.1,127.0.0.0/8,::1,[::1],custom-services.yca.internal"));
        assertFalse(args.contains("NO_PROXY="));
        assertEquals(
                1, args.stream().filter(a -> a.startsWith("JAVA_TOOL_OPTIONS=")).count());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerRestrictedServices(
                        ExecutionPolicies.CONTEXT,
                        "docker",
                        new ExecutorConfiguration.Docker.Services(
                                "gateway",
                                "trusted-control",
                                home.resolve("tls.p12").toString(),
                                null,
                                "egress",
                                "http://172.20.0.1:8080/v1"),
                        Duration.ofSeconds(1)));
    }

    @Test
    void foreignDependencyResourcesPreventAnyCleanupMutation() throws Exception {
        for (final var name : List.of("yca-owner-egress", "yca-owner-external")) {
            final var executable = fake(
                    false,
                    "for last; do :; done\n"
                            + "if [ \"$2\" = inspect ] && [ \"$last\" = " + name
                            + " ]; then printf 'yca-other|dependency-egress\\n'; exit 0; fi\n");
            Files.createFile(Path.of(executable + ".container"));
            Files.createFile(Path.of(executable + ".network"));
            assertThrows(
                    IllegalStateException.class,
                    () -> DockerRestrictedServices.stopOwned(
                            ExecutionPolicies.CONTEXT, executable.toString(), Duration.ofSeconds(1), "yca-owner"));
            assertFalse(Files.readAllLines(Path.of(executable + ".calls")).stream()
                    .anyMatch(c -> c.startsWith("rm ") || c.startsWith("network rm ")));
        }
    }

    @Test
    void dependencyPartialStartupAndRestartCleanupAreOwnedAndOrdered() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final var failure : List.of(
                "if [ \"$1\" = create ] && [ \"$3\" = yca-partial-egress ]; then exit 1; fi\n",
                "if [ \"$1 $2 $4\" = 'network connect egress' ]; then exit 1; fi\n",
                "if [ \"$1 $2\" = 'start yca-partial-egress' ]; then exit 1; fi\n")) {
            final var executable = fake(false, failure);
            assertThrows(
                    IllegalStateException.class,
                    () -> new DockerExecutionBackend(
                                    ExecutionPolicies.CONTEXT,
                                    executable.toString(),
                                    "agent",
                                    Duration.ofSeconds(2),
                                    "",
                                    null,
                                    defaultConfiguration(),
                                    "shown",
                                    false)
                            .execute(
                                    attempt(), "yca-partial", "review", Map.of(), Map.of(), settings, secrets(), null));
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            assertFalse(calls.stream().anyMatch(c -> c.startsWith("run ")));
            assertTrue(calls.indexOf("network rm yca-partial-external")
                    < calls.indexOf("network rm yca-partial-services-net"));
        }
        final var executable = fake(false, "");
        for (final var suffix : List.of(".container", ".network", ".yca-restart-egress", ".yca-restart-external")) {
            Files.createFile(Path.of(executable + suffix));
        }
        new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT,
                        executable.toString(),
                        "agent",
                        Duration.ofSeconds(2),
                        "",
                        null,
                        null,
                        "shown",
                        false)
                .stop("yca-restart");
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertTrue(calls.indexOf("rm --force yca-restart-egress") < calls.indexOf("network rm yca-restart-external"));
        assertTrue(calls.indexOf("network rm yca-restart-external")
                < calls.indexOf("network rm yca-restart-services-net"));
    }

    private ExecutorConfiguration.Docker.Services defaultConfiguration() {
        return new ExecutorConfiguration.Docker.Services(
                "trusted-gateway",
                "trusted-control",
                home.resolve("tls.p12").toString(),
                null,
                null,
                "http://172.20.0.1:8080/v1");
    }

    @Test
    void normalizesOnlyUnconfiguredRecordsAndRejectsPartialOrConflictingConfiguration() {
        assertNull(new ExecutorConfiguration.Docker(
                        "docker",
                        "agent",
                        "",
                        null,
                        new ExecutorConfiguration.Docker.Services(null, null, null, null, null, null),
                        false)
                .services());
        final var config = configuration("http://172.20.0.1:8080/v1");
        assertEquals("YCA_SERVICES_KEYSTORE_PASSWORD", config.keyStorePasswordEnv());
        assertEquals("attempt-services.yca.internal", config.serverName());
        assertDoesNotThrow(() -> new ExecutorConfiguration.Docker("docker", "agent", "", null, config, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutorConfiguration.Docker("docker", "agent", "trusted", null, config, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT,
                        "docker",
                        "agent",
                        Duration.ofSeconds(1),
                        "trusted",
                        null,
                        config,
                        "shown",
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT,
                        "docker",
                        "agent",
                        Duration.ofSeconds(1),
                        "",
                        new ExecutorConfiguration.Docker.Egress("egress", List.of("github.com"), "127.0.0.1"),
                        config,
                        "shown",
                        false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutorConfiguration.Docker(
                        "docker",
                        "agent",
                        "",
                        null,
                        new ExecutorConfiguration.Docker.Services("gateway", null, null, null, null, null),
                        false));
        for (final var variable : List.of(
                "HOME",
                "DOCKER_HOST",
                "JAVA_TOOL_OPTIONS",
                "LD_PRELOAD",
                "BASH_ENV",
                "bad=name",
                "YCA_CONTEXT_TOKEN")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DockerRestrictedServices(
                            ExecutionPolicies.CONTEXT,
                            "docker",
                            new ExecutorConfiguration.Docker.Services(
                                    "gateway",
                                    "trusted",
                                    home.resolve("tls.p12").toString(),
                                    variable,
                                    null,
                                    "http://172.20.0.1/v1"),
                            Duration.ofSeconds(1)));
        }
    }

    @Test
    void acceptsOnlyFixedNumericHttpProviderOriginsWithPathPrefix() {
        for (final var target : List.of(
                "http://172.20.0.1:8080/v1",
                "http://127.0.0.1:8080",
                "http://10.0.0.1:80/v1/chat/completions",
                "http://192.168.1.1:65535/api/v1")) {
            assertDoesNotThrow(() -> configuration(target).validate(), target);
        }
        for (final var target : List.of(
                "http://127.0.0.1:80/v1/",
                "http://[fd00::1]:8080/v1",
                "http://[::1]:80",
                "http://8.8.8.8:80/v1",
                "http://127.0.0.1/v1",
                "https://172.20.0.1/v1",
                "http://provider/v1",
                "http://localhost/v1",
                "http://127.1/v1",
                "http://127.000.0.1/v1",
                "http://256.0.0.1/v1",
                "http://user@172.20.0.1/v1",
                "http://172.20.0.1/v1?x=1",
                "http://172.20.0.1/v1#fragment",
                "http://172.20.0.1/v1/../other",
                "http://172.20.0.1/%2fother",
                "http://172.20.0.1:0/v1",
                "http://[fe80::1%25eth0]/v1")) {
            assertThrows(
                    IllegalArgumentException.class, () -> configuration(target).validate(), target);
        }
    }

    @Test
    void constructsExactIsolationAndKeepsGatewaySecretsOffArgvAndAgentEnvironment() throws Exception {
        nonRoot();
        final var executable = fake(true, "");
        final var settings = settings();
        final var attempt = attempt();
        final var backend = backend(executable, Duration.ofSeconds(5));
        assertEquals(
                0,
                backend.execute(
                        attempt,
                        "yca-services-test",
                        "review",
                        Map.of("YCA_CONTEXT_TOKEN", "ephemeral-agent-token"),
                        Map.of(),
                        settings,
                        secrets(),
                        null));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        final var network = calls.stream()
                .filter(c -> c.startsWith("network create "))
                .findFirst()
                .orElseThrow();
        assertEquals(
                "network create --driver bridge --internal --ipv6 --label app=fixture.context --label"
                        + " io.yupiik.yca.bot.runtime=yca-services-test --label"
                        + " io.yupiik.yca.bot.component=services --opt"
                        + " com.docker.network.bridge.gateway_mode_ipv4=isolated --opt"
                        + " com.docker.network.bridge.gateway_mode_ipv6=isolated"
                        + " yca-services-test-services-net",
                network);
        final var create = Files.readAllLines(Path.of(executable + ".create.args"));
        assertEquals("trusted-control", create.get(create.indexOf("--network") + 1));
        final var uid = Files.getAttribute(settings, "unix:uid");
        final var gid = Files.getAttribute(settings, "unix:gid");
        assertEquals(uid + ":" + gid, create.get(create.indexOf("--user") + 1));
        assertTrue(create.containsAll(List.of(
                "--read-only",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--pids-limit=128",
                "--memory=256m",
                "--cpus=1",
                "/tmp:rw,nosuid,nodev,size=32m")));
        assertTrue(create.contains("type=bind,src=" + home.resolve("tls.p12") + ",dst=/services/tls.p12,readonly"));
        assertTrue(create.contains("type=bind,src=" + settings + ",dst=/services/config.json,readonly"));
        assertEquals(
                List.of(
                        "--entrypoint",
                        "java",
                        "trusted-gateway",
                        "-Xmx128m",
                        "-cp",
                        "@/app/jib-classpath-file",
                        "io.yupiik.ia.agent.github.execution.RestrictedServicesGateway",
                        "--config",
                        "/services/config.json"),
                create.subList(create.indexOf("--entrypoint"), create.size()));
        assertTrue(calls.indexOf("network connect --alias attempt-services.yca.internal yca-services-test-services-net"
                        + " yca-services-test-services")
                < calls.indexOf("start yca-services-test-services"));
        final var check = "exec --user " + uid + ":" + gid
                + " yca-services-test-services java -Xmx32m -cp @/app/jib-classpath-file"
                + " io.yupiik.ia.agent.github.execution.RestrictedServicesGateway --check"
                + " /services/config.json";
        assertEquals(
                List.of(check),
                calls.stream().filter(c -> c.startsWith("exec ")).toList());
        assertTrue(calls.indexOf(check) > calls.indexOf("start yca-services-test-services"));
        assertTrue(create.contains("app=fixture.context"));
        final var run = Files.readAllLines(Path.of(executable + ".run.args"));
        assertEquals("yca-services-test-services-net", run.get(run.indexOf("--network") + 1));
        assertEquals(1, run.stream().filter("--network"::equals).count());
        assertEquals("127.0.0.1", run.get(run.indexOf("--dns") + 1));
        assertTrue(run.contains("io.yupiik.yca.bot.component=agent"));
        assertTrue(run.contains("app=fixture.context"));
        for (final var proxy : List.of(
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "ALL_PROXY",
                "NO_PROXY",
                "http_proxy",
                "https_proxy",
                "all_proxy",
                "no_proxy")) {
            assertTrue(run.contains(proxy + "="));
        }
        assertFalse(run.contains("-p"));
        for (final var forbidden : List.of(
                "--publish",
                "--add-host",
                "docker.sock",
                "/services/config.json",
                "/services/tls.p12",
                "trusted-control",
                "YCA_SERVICES_PROVIDER_TOKEN",
                "YCA_SERVICES_KEYSTORE_PASSWORD")) {
            assertFalse(run.toString().contains(forbidden), forbidden);
        }
        final var env = Files.readString(Path.of(executable + ".create.env"));
        secrets().forEach((key, value) -> assertTrue(env.contains(key + "=" + value)));
        final var agentEnv = Files.readString(Path.of(executable + ".run.env"));
        for (final var value : secrets().values()) {
            assertFalse(calls.toString().contains(value));
            assertFalse(create.toString().contains(value));
            assertFalse(agentEnv.contains(value));
            assertFalse(Files.readString(attempt.resolve("execution.log")).contains(value));
        }
        assertTrue(agentEnv.contains("YCA_CONTEXT_TOKEN=ephemeral-agent-token"));
        assertTrue(calls.indexOf("rm --force yca-services-test-services")
                < calls.indexOf("network rm yca-services-test-services-net"));
    }

    @Test
    void rejectsBundlesInUnknownModeAndRequiresExplicitBundleInServicesMode() throws Exception {
        final var executable = fake(false, "");
        final var legacy = new DockerExecutionBackend(
                ExecutionPolicies.CONTEXT,
                executable.toString(),
                "agent",
                Duration.ofSeconds(1),
                "",
                null,
                null,
                "shown",
                false);
        assertThrows(
                IllegalArgumentException.class,
                () -> legacy.execute(
                        home, "yca-mode", "review", Map.of(), Map.of(), home.resolve("settings"), secrets(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> legacy.execute(home, "yca-mode", "review", Map.of(), Map.of(), null, secrets(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> backend(executable, Duration.ofSeconds(1))
                        .execute(home, "yca-mode", "review", Map.of(), Map.of()));
        assertFalse(Files.exists(Path.of(executable + ".calls")));
    }

    @Test
    void cleanupWorksAfterRestartWithoutServicesConfigurationAndNeverDeletesUnknownResources() throws Exception {
        final var executable = fake(false, "");
        Files.createFile(Path.of(executable + ".container"));
        Files.createFile(Path.of(executable + ".network"));
        new DockerExecutionBackend(
                        ExecutionPolicies.CONTEXT,
                        executable.toString(),
                        "agent",
                        Duration.ofSeconds(2),
                        "",
                        null,
                        null,
                        "shown",
                        false)
                .stop("yca-recovery");
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertTrue(calls.contains("rm --force yca-recovery-services"));
        assertTrue(calls.contains("network rm yca-recovery-services-net"));
        for (final var answer : List.of(
                "printf 'fixture.context|yca-other|services\\n'",
                "printf 'fixture.context|yca-recovery|agent\\n'",
                "printf 'daemon unavailable\\n'; exit 1",
                "printf 'Error: No such container: yca-other\\n'; exit 1")) {
            final var collision = fake(
                    false,
                    "if [ \"$1\" = container ] && [ \"$3\" = --format ] && [ \"$4\" != '{{.State.Running}}'"
                            + " ]; then "
                            + answer + "; exit 0; fi\n");
            assertThrows(
                    IllegalStateException.class,
                    () -> DockerRestrictedServices.stopOwned(
                            ExecutionPolicies.CONTEXT, collision.toString(), Duration.ofSeconds(1), "yca-recovery"));
            assertFalse(Files.readString(Path.of(collision + ".calls")).contains("rm "));
        }
    }

    @Test
    void ambiguousAgentTerminationRetainsServicesResources() throws Exception {
        final var executable =
                fake(false, "if [ \"$4\" != \"${4%State.Running*}\" ]; then printf 'unknown\\n'; exit 0; fi\n");
        assertThrows(
                IllegalStateException.class,
                () -> new DockerExecutionBackend(
                                ExecutionPolicies.CONTEXT,
                                executable.toString(),
                                "agent",
                                Duration.ofSeconds(1),
                                "",
                                null,
                                null,
                                "shown",
                                false)
                        .stop("yca-uncertain"));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals(1, calls.size());
        assertFalse(calls.toString().contains("rm "));
    }

    @Test
    void networkOwnershipIsCheckedBeforeRemovingEvenAnOwnedGateway() throws Exception {
        final var executable = fake(
                false,
                "if [ \"$1 $2\" = 'network inspect' ]; then printf 'fixture.context|yca-other|services\\n'; exit 0; fi\n");
        Files.createFile(Path.of(executable + ".container"));
        assertThrows(
                IllegalStateException.class,
                () -> DockerRestrictedServices.stopOwned(
                        ExecutionPolicies.CONTEXT, executable.toString(), Duration.ofSeconds(1), "yca-owner"));
        assertFalse(Files.readString(Path.of(executable + ".calls")).contains("rm "));
    }

    @Test
    void timeoutCleansOnlyAfterSuccessfulAgentStopAndRetainsOnKillFailure() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final boolean killFails : List.of(false, true)) {
            final var executable = fake(false, """
              if [ "$1" = run ]; then exec sleep 60; fi
              if [ "$4" != "${4%State.Running*}" ]; then printf 'fixture.context|%s|agent|true\\n' "$5"; exit 0; fi
              """ + (killFails ? "if [ \"$1\" = kill ]; then exit 1; fi\n" : ""));
            final var attempt = attempt();
            assertTimeoutPreemptively(
                    Duration.ofSeconds(5),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> backend(executable, Duration.ofMillis(300))
                                    .execute(
                                            attempt,
                                            "yca-timeout",
                                            "review",
                                            Map.of(),
                                            Map.of(),
                                            settings,
                                            secrets(),
                                            null)));
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            assertTrue(calls.contains("kill yca-timeout"));
            if (killFails) {
                assertFalse(calls.stream().anyMatch(c -> c.startsWith("rm ") || c.startsWith("network rm ")));
            } else {
                assertTrue(calls.indexOf("kill yca-timeout") < calls.indexOf("rm --force yca-timeout-services"));
            }
        }
    }

    @Test
    void rejectsOldDaemonBadControlNetworkAndUnapprovedSecretsBeforeAgentLaunch() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final var override : List.of(
                "if [ \"$1\" = version ]; then printf '27.5.0\\n'; exit 0; fi\n",
                "if [ \"$1\" = version ]; then printf '28.0.0\\nextra'; exit 0; fi\n",
                "if [ \"$1 $2\" = 'network inspect' ] && [ \"$5\" = trusted-control ]; then printf"
                        + " 'host|false\\n"
                        + "'; exit 0; fi\n",
                "if [ \"$1 $2\" = 'network inspect' ] && [ \"$5\" = trusted-control ]; then printf"
                        + " 'bridge|true\\n"
                        + "'; exit 0; fi\n")) {
            final var executable = fake(false, override);
            final var gateway = new DockerRestrictedServices(
                    ExecutionPolicies.CONTEXT,
                    executable.toString(),
                    configuration("http://172.20.0.1:8080/v1"),
                    Duration.ofSeconds(2));
            assertThrows(IllegalStateException.class, () -> gateway.start("yca-old", settings, secrets()));
            final var calls = Files.readString(Path.of(executable + ".calls"));
            assertFalse(calls.contains("network create"));
            assertFalse(calls.contains("create --name"));
        }
        final var executable = fake(false, "");
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerRestrictedServices(
                                ExecutionPolicies.CONTEXT,
                                executable.toString(),
                                configuration("http://172.20.0.1:8080/v1"),
                                Duration.ofSeconds(1))
                        .start("yca-secrets", settings, Map.of("GH_TOKEN", "forbidden")));
        assertFalse(Files.exists(Path.of(executable + ".calls")));
    }

    @Test
    void partialStartFailureCleansOnlyOwnedResourcesWithoutLaunchingAgent() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final var stage : List.of("create", "connect", "start")) {
            final var executable = fake(
                    false,
                    stage.equals("connect")
                            ? "if [ \"$1 $2\" = 'network connect' ]; then exit 1; fi\n"
                            : "if [ \"$1\" = '" + stage + "' ]; then exit 1; fi\n");
            final var attempt = attempt();
            assertThrows(
                    IllegalStateException.class,
                    () -> backend(executable, Duration.ofSeconds(2))
                            .execute(attempt, "yca-partial", "review", Map.of(), Map.of(), settings, secrets(), null));
            final var calls = Files.readString(Path.of(executable + ".calls"));
            assertFalse(calls.contains("run --rm"));
            assertTrue(calls.contains("network rm yca-partial-services-net"));
            if (!stage.equals("create")) {
                assertTrue(calls.contains("rm --force yca-partial-services"));
            }
        }
    }

    @Test
    void controlDeadlineKillsClientAndDescendantWithoutLeakingDiagnostics() throws Exception {
        final var executable = fake(
                false,
                "if [ \"$1\" = container ]; then printf '%s' \"$$\" > \"$0.pid\"; sleep 60 & printf '%s'"
                        + " \"$!\" > \"$0.child\"; wait; fi\n");
        assertTimeoutPreemptively(
                Duration.ofSeconds(4),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> DockerRestrictedServices.stopOwned(
                                ExecutionPolicies.CONTEXT,
                                executable.toString(),
                                Duration.ofMillis(200),
                                "yca-deadline")));
        for (final var suffix : List.of(".pid", ".child")) {
            final long pid = Long.parseLong(Files.readString(Path.of(executable + suffix)));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void tlsReadinessRetriesAndDeadlineFailureCleansWithoutAgentLaunch() throws Exception {
        nonRoot();
        final var settings = settings();
        for (final var override :
                List.of("if [ \"$1\" = exec ]; then exit 1; fi\n", "if [ \"$1\" = exec ]; then exec sleep 60; fi\n")) {
            final var executable = fake(false, override);
            // Leave enough client budget to reach the readiness probe under parallel reactor load.
            // The hung probe still must time out, never launch the agent, and clean owned resources.
            assertTimeoutPreemptively(
                    Duration.ofSeconds(6),
                    () -> assertThrows(
                            IllegalStateException.class,
                            () -> backend(executable, Duration.ofSeconds(1))
                                    .execute(
                                            attempt(),
                                            "yca-notready",
                                            "review",
                                            Map.of(),
                                            Map.of(),
                                            settings,
                                            secrets(),
                                            null)));
            final var calls = Files.readAllLines(Path.of(executable + ".calls"));
            assertTrue(calls.stream().anyMatch(c -> c.startsWith("exec --user ")));
            assertFalse(calls.stream().anyMatch(c -> c.startsWith("run ")));
            assertTrue(calls.contains("rm --force yca-notready-services"));
            assertTrue(calls.contains("network rm yca-notready-services-net"));
        }
        final var executable =
                fake(false, "if [ \"$1\" = exec ] && [ ! -f \"$0.probed\" ]; then : > \"$0.probed\"; exit 1; fi\n");
        assertEquals(
                0,
                backend(executable, Duration.ofSeconds(3))
                        .execute(attempt(), "yca-retry", "review", Map.of(), Map.of(), settings, secrets(), null));
        final var calls = Files.readAllLines(Path.of(executable + ".calls"));
        assertEquals(2, calls.stream().filter(c -> c.startsWith("exec --user ")).count());
    }

    @Test
    void requiresCompleteOpaqueCredentialsButAcceptsAnonymousUpstreamAndCustomTlsPassword() throws Exception {
        nonRoot();
        final var settings = settings();
        final var executable = fake(true, "");
        final var gateway = new DockerRestrictedServices(
                ExecutionPolicies.CONTEXT,
                executable.toString(),
                configuration("http://172.20.0.1:8080/v1"),
                Duration.ofSeconds(1));
        for (final var key : secrets().keySet()) {
            final var missing = new HashMap<>(secrets());
            missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> gateway.start("yca-missing", settings, missing));
        }
        for (final var key : List.of("YCA_CONTEXT_TOKEN", "YCA_CONTEXT_GIT_TOKEN", "YCA_SERVICES_PROVIDER_TOKEN")) {
            final var invalid = new HashMap<>(secrets());
            invalid.put(key, "control-secret");
            assertThrows(IllegalArgumentException.class, () -> gateway.start("yca-invalid", settings, invalid));
        }
        assertFalse(Files.exists(Path.of(executable + ".calls")));
        final var anonymous = new HashMap<>(secrets());
        anonymous.put("YCA_SERVICES_UPSTREAM_TOKEN", "");
        anonymous.put("TLS_PASSWORD", anonymous.remove("YCA_SERVICES_KEYSTORE_PASSWORD"));
        final var custom = new ExecutorConfiguration.Docker.Services(
                "trusted-gateway",
                "trusted-control",
                home.resolve("tls.p12").toString(),
                "TLS_PASSWORD",
                null,
                "http://172.20.0.1:8080/v1",
                new ExecutorConfiguration.Docker.Egress(null, List.of(), null, false));
        new DockerRestrictedServices(ExecutionPolicies.CONTEXT, executable.toString(), custom, Duration.ofSeconds(1))
                .start("yca-anonymous", settings, anonymous);
        assertTrue(Files.readString(Path.of(executable + ".create.env")).contains("YCA_SERVICES_UPSTREAM_TOKEN=\n"));
    }

    @Test
    void settingsCannotBeMountedIntoAgent() throws Exception {
        nonRoot();
        settings();
        final var attempt = attempt();
        final var settings = Files.writeString(attempt.resolve("workspace/settings.json"), "{}");
        final var executable = fake(false, "");
        assertThrows(
                IllegalArgumentException.class,
                () -> backend(executable, Duration.ofSeconds(1))
                        .execute(attempt, "yca-mount", "review", Map.of(), Map.of(), settings, secrets(), null));
        assertFalse(Files.exists(Path.of(executable + ".calls")));
    }

    private ExecutorConfiguration.Docker.Services configuration(final String target) {
        return new ExecutorConfiguration.Docker.Services(
                "trusted-gateway",
                "trusted-control",
                home.resolve("tls.p12").toString(),
                null,
                null,
                target,
                new ExecutorConfiguration.Docker.Egress(null, List.of(), null, false));
    }

    private DockerExecutionBackend backend(final Path executable, final Duration timeout) {
        return new DockerExecutionBackend(
                ExecutionPolicies.CONTEXT,
                executable.toString(),
                "agent",
                timeout,
                "",
                null,
                configuration("http://172.20.0.1:8080/v1"),
                "shown",
                false);
    }

    private Map<String, String> secrets() {
        return Map.of(
                "YCA_CONTEXT_TOKEN",
                "c".repeat(43),
                "YCA_CONTEXT_GIT_TOKEN",
                "g".repeat(43),
                "YCA_SERVICES_PROVIDER_TOKEN",
                "p".repeat(43),
                "YCA_SERVICES_UPSTREAM_TOKEN",
                "upstream-secret",
                "YCA_SERVICES_KEYSTORE_PASSWORD",
                "tls-secret");
    }

    private Map<String, String> secrets(final ExecutionPolicy policy) {
        final var supplied = new HashMap<>(secrets());
        supplied.keySet()
                .removeIf(key -> !policy.gateway().allowedTokens().contains(key)
                        && !"YCA_SERVICES_KEYSTORE_PASSWORD".equals(key));
        return supplied;
    }

    private void nonRoot() throws Exception {
        assumeTrue(((Number) Files.getAttribute(home, "unix:uid")).intValue() != 0);
    }

    private Path settings() throws Exception {
        Files.writeString(home.resolve("tls.p12"), "test TLS store");
        return Files.writeString(home.resolve("private-settings.json"), "{}");
    }

    private Path attempt() throws Exception {
        final var directory = Files.createTempDirectory(home, "attempt-");
        Files.writeString(directory.resolve("configuration.json"), "{}");
        for (final var name : List.of("storage", "workspace", "output")) {
            Files.createDirectory(directory.resolve(name));
        }
        return directory;
    }

    private Path fake(final boolean captureDetails, final String override) throws Exception {
        return fake(ExecutionPolicies.CONTEXT, captureDetails, override);
    }

    private Path fake(final ExecutionPolicy policy, final boolean captureDetails, final String override)
            throws Exception {
        final var executable = Files.createTempDirectory(home, "docker-").resolve("docker.sh");
        TestScript.write(
                executable,
                """
            #!/bin/sh
            printf '%s\\n' "$*" >> "$0.calls"
            """ + "app='" + policy.applicationId() + "'\n" + override + "capture_details="
                        + captureDetails + "\n" + """
            for last; do :; done
            case "$last" in
              *-egress|*-external)
                runtime=${last%-egress}; runtime=${runtime%-external}
                case "$1 $2" in
                  'container inspect'|'network inspect')
                    if [ -f "$0.$last" ]; then printf '%s|%s|dependency-egress\\n' "$app" "$runtime"; exit 0; fi
                    printf 'Error: No such object: %s\\n' "$last"; exit 1 ;;
                  'network create') : > "$0.$last"; exit 0 ;;
                  'network rm') rm "$0.$last"; exit 0 ;;
                esac
                if [ "$1" = rm ]; then rm "$0.$last"; exit 0; fi ;;
            esac
            if [ "$1" = create ] && [ "$3" = "${3%-egress}-egress" ]; then
              : > "$0.$3"
              if [ "$capture_details" = true ]; then
                printf '%s\\n' "$@" > "$0.proxy.args"
                env > "$0.proxy.env"
              fi
              exit 0
            fi
            runtime=${last%-services-net}
            runtime=${runtime%-services}
            case "$1 $2" in
              'network inspect')
                if [ "$last" = trusted-control ]; then printf 'bridge|false\\n'; exit 0; fi
                if [ -f "$0.network" ]; then printf '%s|%s|services\\n' "$app" "$runtime"; else printf 'Error: No such network: %s\\n' "$last"; exit 1; fi ;;
              'container inspect')
                if [ "$4" = '{{.State.Running}}' ]; then
                  case "$last" in *-services) printf 'true\\n';; *) printf 'false\\n';; esac
                elif [ "$4" != "${4%State.Running*}" ]; then
                  printf '%s|%s|agent|false\\n' "$app" "$last"
                elif [ -f "$0.container" ]; then printf '%s|%s|services\\n' "$app" "$runtime"
                else printf 'Error: No such container: %s\\n' "$last"; exit 1; fi ;;
              'network create') : > "$0.network" ;;
              'network rm') rm "$0.network" ;;
            esac
            if [ "$1" = version ]; then printf '28.1.0\\n'; fi
            if [ "$1" = create ]; then : > "$0.container"; fi
            if [ "$1" = rm ]; then rm "$0.container"; fi
            if [ "$capture_details" = true ] && { [ "$1" = create ] || [ "$1" = run ]; }; then
              printf '%s\\n' "$@" > "$0.$1.args"
              env > "$0.$1.env"
            fi
            exit 0
            """);
        return executable;
    }
}
