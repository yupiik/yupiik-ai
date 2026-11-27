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

import io.yupiik.ia.agent.bot.runtime.ExecutionPolicy;
import io.yupiik.ia.agent.bot.runtime.ProviderBudgetEnvironment;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Isolated per-attempt TLS services and optional credential-free public dependency gateway. */
public final class DockerRestrictedServices {
    private static final Pattern RUNTIME = Pattern.compile("yca-[a-zA-Z0-9-]{1,124}");
    private static final Pattern VERSION = Pattern.compile("([0-9]{1,3})\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?");
    private static final String RUNTIME_LABEL = ExecutionPolicy.RUNTIME_LABEL;
    private static final String COMPONENT_LABEL = ExecutionPolicy.COMPONENT_LABEL;
    private static final String OWNERSHIP = ExecutionPolicy.inspectFormat(true);
    private static final String CONTAINER_OWNERSHIP = ExecutionPolicy.inspectFormat(false);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final ExecutionPolicy policy;
    private final String executable;
    private final ExecutorConfiguration.Docker.Services configuration;
    private final Duration timeout;
    private final DockerEgress dependencyEgress;

    public DockerRestrictedServices(
            final ExecutionPolicy policy,
            final String executable,
            final ExecutorConfiguration.Docker.Services config,
            final Duration timeout) {
        this.policy = Objects.requireNonNull(policy);
        if (executable == null
                || executable.isBlank()
                || timeout == null
                || timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Docker executable and positive bounded services timeout required");
        }
        if (config == null) {
            throw new IllegalArgumentException("Restricted services configuration required");
        }
        config.validate();
        this.executable = executable;
        this.configuration = config;
        this.timeout = timeout;
        final var dependencies = config.dependencyEgress();
        this.dependencyEgress =
                dependencies.enabled() && !dependencies.allowedHosts().isEmpty()
                        ? new DockerEgress(policy, executable, dependencies, timeout)
                        : null;
        if (dependencyEgress != null && "egress".equalsIgnoreCase(config.serverName())) {
            throw new IllegalArgumentException("Services server name conflicts with the dependency proxy alias");
        }
    }

    // Recovery has no invocation configuration or secrets.
    private DockerRestrictedServices(final ExecutionPolicy policy, final String executable, final Duration timeout) {
        this.policy = Objects.requireNonNull(policy);
        this.executable = executable;
        this.timeout = timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10);
        this.configuration = null;
        this.dependencyEgress = null;
    }

    public void start(final String runtimeId, final Path settingsFile, final Map<String, String> gatewaySecrets) {
        validateRuntime(runtimeId);
        final var entrypoint = policy.gateway().mainClass();
        final var secrets = secrets(gatewaySecrets);
        final String user;
        try {
            if (settingsFile == null
                    || !settingsFile.isAbsolute()
                    || settingsFile.toString().contains(",")
                    || !Files.isRegularFile(settingsFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(Path.of(configuration.keyStore()), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Private services settings and TLS store files required");
            }
            final var uid =
                    ((Number) Files.getAttribute(settingsFile, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue();
            final var gid =
                    ((Number) Files.getAttribute(settingsFile, "unix:gid", LinkOption.NOFOLLOW_LINKS)).intValue();
            if (uid == 0) {
                throw new IllegalArgumentException("Services settings must belong to a dedicated non-root user");
            }
            user = uid + ":" + gid;
        } catch (final IOException e) {
            throw new IllegalArgumentException("Unable to inspect private services settings");
        }
        final var requestBudget = ProviderBudgetEnvironment.select(
                new ProviderBudgetConfiguration(
                        gatewaySecrets.get(ProviderBudgetEnvironment.ROOT),
                        gatewaySecrets.get(ProviderBudgetEnvironment.ID),
                        gatewaySecrets.get(ProviderBudgetEnvironment.SLOTS)),
                settingsFile.getParent());
        final var version = VERSION.matcher(
                command(Map.of(), "version", "--format", "{{.Server.Version}}").strip());
        if (!version.matches() || Integer.parseInt(version.group(1)) < 28) {
            throw new IllegalStateException("Restricted services requires Docker Engine 28 or later");
        }
        final var control = command(
                        Map.of(),
                        "network",
                        "inspect",
                        "--format",
                        "{{.Driver}}|{{.Internal}}",
                        configuration.controlNetwork())
                .strip();
        if (!"bridge|false".equals(control)) {
            throw new IllegalStateException(
                    "Restricted services requires an operator-created non-internal bridge control network");
        }
        // Refuse collisions, even with a previous owned attempt: recovery must stop it first.
        if (inspect(runtimeId, false)
                || inspect(runtimeId, true)
                || inspect(runtimeId, runtimeId + "-egress", false, "dependency-egress")
                || inspect(runtimeId, runtimeId + "-external", true, "dependency-egress")) {
            throw new IllegalStateException("Restricted services resources already exist; recovery required");
        }
        command(
                Map.of(),
                "network",
                "create",
                "--driver",
                "bridge",
                "--internal",
                "--ipv6",
                "--label",
                applicationLabel(runtimeId),
                "--label",
                RUNTIME_LABEL + "=" + runtimeId,
                "--label",
                COMPONENT_LABEL + "=services",
                "--opt",
                "com.docker.network.bridge.gateway_mode_ipv4=isolated",
                "--opt",
                "com.docker.network.bridge.gateway_mode_ipv6=isolated",
                network(runtimeId));
        final var args = new ArrayList<>(List.of(
                "create",
                "--name",
                gateway(runtimeId),
                "--label",
                applicationLabel(runtimeId),
                "--label",
                RUNTIME_LABEL + "=" + runtimeId,
                "--label",
                COMPONENT_LABEL + "=services",
                "--network",
                configuration.controlNetwork(),
                "--user",
                user,
                "--read-only",
                "--tmpfs",
                "/tmp:rw,nosuid,nodev,size=32m",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--pids-limit=128",
                "--memory=256m",
                "--cpus=1",
                "--sysctl",
                "net.ipv4.ip_forward=0",
                "--sysctl",
                "net.ipv6.conf.all.forwarding=0",
                "--mount",
                "type=bind,src=" + configuration.keyStore() + ",dst=/services/tls.p12,readonly",
                "--mount",
                "type=bind,src=" + settingsFile + ",dst=/services/config.json,readonly"));
        final var launchSecrets = new HashMap<>(secrets);
        if (!requestBudget.isEmpty()) {
            args.addAll(List.of(
                    "--mount",
                    "type=bind,src=" + requestBudget.get(ProviderBudgetEnvironment.ROOT) + ",dst=/services/budget"));
            launchSecrets.put(ProviderBudgetEnvironment.ROOT, "/services/budget");
        }
        for (final var key : launchSecrets.keySet().stream().sorted().toList()) {
            args.addAll(List.of("--env", key));
        }
        args.addAll(List.of(
                "--entrypoint",
                "java",
                configuration.image(),
                "-Xmx128m",
                "-cp",
                "@/app/jib-classpath-file",
                entrypoint,
                "--config",
                "/services/config.json"));
        command(launchSecrets, args.toArray(String[]::new));
        command(
                Map.of(),
                "network",
                "connect",
                "--alias",
                configuration.serverName(),
                network(runtimeId),
                gateway(runtimeId));
        command(Map.of(), "start", gateway(runtimeId));
        final long deadline = System.nanoTime()
                + (timeout.compareTo(Duration.ofSeconds(30)) < 0 ? timeout : Duration.ofSeconds(30)).toNanos();
        final var running = invoke(
                Map.of(),
                Duration.ofNanos(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(2), deadline - System.nanoTime()))),
                "container",
                "inspect",
                "--format",
                "{{.State.Running}}",
                gateway(runtimeId));
        if (running.code() != 0
                || running.truncated()
                || !"true".equals(running.output().strip())) {
            throw new IllegalStateException("Restricted services gateway did not remain running");
        }
        while (System.nanoTime() < deadline) {
            final var budget = Duration.ofNanos(Math.min(TimeUnit.SECONDS.toNanos(2), deadline - System.nanoTime()));
            if (budget.isNegative() || budget.isZero()) {
                break;
            }
            final var probe = invoke(
                    Map.of(),
                    budget,
                    "exec",
                    "--user",
                    user,
                    gateway(runtimeId),
                    "java",
                    "-Xmx32m",
                    "-cp",
                    "@/app/jib-classpath-file",
                    entrypoint,
                    "--check",
                    "/services/config.json");
            if (probe.code() == 0 && !probe.truncated() && System.nanoTime() <= deadline) {
                if (dependencyEgress != null) {
                    dependencyEgress.start(runtimeId, network(runtimeId));
                }
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(
                        Math.max(0, Math.min(TimeUnit.MILLISECONDS.toNanos(100), deadline - System.nanoTime())));
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Restricted services readiness interrupted");
            }
        }
        throw new IllegalStateException("Restricted services TLS readiness deadline exceeded");
    }

    public List<String> agentArguments(final String runtimeId) {
        validateRuntime(runtimeId);
        final var args = new ArrayList<>(List.of(
                "--network",
                network(runtimeId),
                "--dns",
                "127.0.0.1",
                "--label",
                applicationLabel(runtimeId),
                "--label",
                RUNTIME_LABEL + "=" + runtimeId,
                "--label",
                COMPONENT_LABEL + "=agent"));
        for (final var key : List.of(
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "ALL_PROXY",
                "NO_PROXY",
                "http_proxy",
                "https_proxy",
                "all_proxy",
                "no_proxy",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS")) {
            if (dependencyEgress == null || "JDK_JAVA_OPTIONS".equals(key) || "_JAVA_OPTIONS".equals(key)) {
                args.addAll(List.of("--env", key + "="));
            }
        }
        if (dependencyEgress != null) {
            args.addAll(dependencyEgress.proxyArguments(configuration.serverName()));
            args.addAll(List.of(
                    "--env",
                    "MAVEN_USER_HOME=/attempt/storage/.m2",
                    "--env",
                    "MAVEN_OPTS=-Duser.home=/attempt/storage"));
        }
        return List.copyOf(args);
    }

    /** Must only be called after confirmed agent termination; absence is accepted, ambiguity is not. */
    public static void stopOwned(
            final ExecutionPolicy policy, final String executable, final Duration timeout, final String runtimeId) {
        validateRuntime(runtimeId);
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Positive cleanup timeout required");
        }
        final boolean interrupted = Thread.interrupted();
        try {
            new DockerRestrictedServices(policy, executable, timeout).remove(runtimeId);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void remove(final String runtimeId) {
        // Verify both before mutating either; never delete an unrelated colliding resource.
        final boolean container = inspect(runtimeId, false);
        final boolean net = inspect(runtimeId, true);
        final boolean proxy = inspect(runtimeId, runtimeId + "-egress", false, "dependency-egress");
        final boolean external = inspect(runtimeId, runtimeId + "-external", true, "dependency-egress");
        if (proxy) {
            command(Map.of(), "rm", "--force", runtimeId + "-egress");
        }
        if (external) {
            command(Map.of(), "network", "rm", runtimeId + "-external");
        }
        if (container) {
            command(Map.of(), "rm", "--force", gateway(runtimeId));
        }
        if (net) {
            command(Map.of(), "network", "rm", network(runtimeId));
        }
    }

    private boolean inspect(final String runtimeId, final boolean network) {
        final var name = network ? network(runtimeId) : gateway(runtimeId);
        return inspect(runtimeId, name, network, "services");
    }

    private boolean inspect(final String runtimeId, final String name, final boolean network, final String component) {
        final var result = invoke(
                Map.of(),
                network ? "network" : "container",
                "inspect",
                "--format",
                network ? OWNERSHIP : CONTAINER_OWNERSHIP,
                name);
        if (result.code() == 0
                && !result.truncated()
                && policy.ownership(runtimeId, component).equals(result.output().strip())) {
            return true;
        }
        if (result.code() != 0 && !result.truncated() && missing(result.output().strip(), name, network)) {
            return false;
        }
        throw new IllegalStateException("Restricted services ownership or absence could not be verified");
    }

    private boolean missing(final String output, final String name, final boolean network) {
        for (final var prefix : List.of("", "Error: ", "Error response from daemon: ")) {
            for (final var kind : network ? List.of("network", "object") : List.of("container", "object")) {
                if ((prefix + "No such " + kind + ": " + name).equals(output)) {
                    return true;
                }
            }
            if (network && (prefix + "network " + name + " not found").equals(output)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> secrets(final Map<String, String> supplied) {
        if (supplied == null) {
            throw new IllegalArgumentException("Gateway secrets required");
        }
        final var result = new HashMap<String, String>();
        final var allowedTokens = policy.gateway().allowedTokens();
        for (final var entry : supplied.entrySet()) {
            if ((!allowedTokens.contains(entry.getKey())
                            && !ProviderBudgetEnvironment.KEYS.contains(entry.getKey())
                            && !configuration.keyStorePasswordEnv().equals(entry.getKey()))
                    || entry.getValue() == null
                    || entry.getValue().indexOf('\0') >= 0
                    || (!"YCA_SERVICES_UPSTREAM_TOKEN".equals(entry.getKey())
                            && entry.getValue().isBlank())
                    || (allowedTokens.contains(entry.getKey())
                            && !"YCA_SERVICES_UPSTREAM_TOKEN".equals(entry.getKey())
                            && !TOKEN.matcher(entry.getValue()).matches())) {
                throw new IllegalArgumentException("Unsupported gateway secret variable");
            }
            result.put(entry.getKey(), entry.getValue());
        }
        final var requiredTokens = policy.gateway().requiredTokens();
        if (!result.keySet().containsAll(requiredTokens) || !result.containsKey(configuration.keyStorePasswordEnv())) {
            throw new IllegalArgumentException("Complete gateway secrets required");
        }
        return Map.copyOf(result);
    }

    private String command(final Map<String, String> secrets, final String... arguments) {
        final var result = invoke(secrets, arguments);
        if (result.code() != 0 || result.truncated()) {
            throw new IllegalStateException("Docker restricted services control failed: " + arguments[0]);
        }
        return result.output();
    }

    private Result invoke(final Map<String, String> secrets, final String... arguments) {
        return invoke(
                secrets, timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10), arguments);
    }

    private Result invoke(final Map<String, String> secrets, final Duration budget, final String... arguments) {
        final var args = new ArrayList<String>();
        args.add(executable);
        args.addAll(List.of(arguments));
        Process process = null;
        final var output = new ByteArrayOutputStream();
        try {
            final var builder = DockerControlEnvironment.sanitize(new ProcessBuilder(args))
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(secrets);
            final long deadline = System.nanoTime() + budget.toNanos();
            // Only inspect needs diagnostics to prove exact absence. Never retain create/start output.
            final boolean inspect = List.of(arguments).contains("inspect");
            if (inspect) {
                builder.redirectErrorStream(true);
            }
            process = builder.start();
            final var running = process;
            final var reader = Thread.ofVirtual().start(() -> {
                try (final var input = running.getInputStream()) {
                    final var buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (output.size() < 4097) {
                            output.write(buffer, 0, Math.min(count, 4097 - output.size()));
                        }
                    }
                } catch (final IOException ignored) {
                    // Bounded shutdown closes pipes.
                }
            });
            if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return new Result(-1, "", true);
            }
            reader.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            if (reader.isAlive()) {
                return new Result(-1, "", true);
            }
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8), output.size() > 4096);
        } catch (final IOException e) {
            throw new IllegalStateException("Docker restricted services control failed");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Docker restricted services control interrupted");
        } finally {
            if (process != null) {
                close(process);
            }
        }
    }

    private void close(final Process process) {
        final var descendants = process.descendants().toList();
        for (final var child : descendants.reversed()) {
            child.destroyForcibly();
        }
        boolean interrupted = Thread.interrupted();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try {
            for (final var child : descendants) {
                try {
                    child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (final InterruptedException e) {
                    interrupted = true;
                } catch (final ExecutionException | TimeoutException ignored) {
                    // Bound local cleanup, without claiming remote resource termination.
                }
            }
            process.destroyForcibly();
            for (final var stream :
                    List.of(process.getOutputStream(), process.getInputStream(), process.getErrorStream())) {
                try {
                    stream.close();
                } catch (final IOException ignored) {
                    // Client terminated.
                }
            }
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                interrupted = true;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void validateRuntime(final String runtimeId) {
        if (runtimeId == null || !RUNTIME.matcher(runtimeId).matches()) {
            throw new IllegalArgumentException("Invalid execution identity");
        }
    }

    private String applicationLabel(final String runtimeId) {
        return "app=" + policy.applicationId();
    }

    private static String gateway(final String runtimeId) {
        return runtimeId + "-services";
    }

    private static String network(final String runtimeId) {
        return runtimeId + "-services-net";
    }

    private record Result(int code, String output, boolean truncated) {}
}
