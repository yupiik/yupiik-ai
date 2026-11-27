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

import io.yupiik.ia.agent.bot.gateway.EgressGateway;
import io.yupiik.ia.agent.bot.runtime.ExecutionPolicy;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Per-attempt Docker resources; all control commands are bounded and run without executor credentials. */
public final class DockerEgress {
    private static final Pattern RUNTIME_ID = Pattern.compile("yca-[a-zA-Z0-9-]+");
    private final String executable;
    private final ExecutorConfiguration.Docker.Egress configuration;
    private final Duration lifetime;
    private final ExecutionPolicy policy;
    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9.-]*");

    public DockerEgress(
            final ExecutionPolicy policy,
            final String executable,
            final ExecutorConfiguration.Docker.Egress configuration,
            final Duration lifetime) {
        this.policy = Objects.requireNonNull(policy);
        if (configuration.image() == null
                || configuration.image().isBlank()
                || configuration.image().startsWith("-")) {
            throw new IllegalArgumentException("Trusted egress gateway image required");
        }
        if (!("127.0.0.1".equals(configuration.dns()) || "::1".equals(configuration.dns()))) {
            throw new IllegalArgumentException("Enforced egress DNS must be numeric loopback (127.0.0.1 or ::1)");
        }
        EgressGateway.validateHosts(configuration.allowedHosts());
        this.executable = executable;
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Enforced egress lifetime must be positive and at most seven days");
        }
        this.lifetime = lifetime;
        this.configuration = new ExecutorConfiguration.Docker.Egress(
                configuration.image(), List.copyOf(configuration.allowedHosts()), configuration.dns());
    }

    public void start(final String runtime) {
        start(runtime, null);
    }

    /** The caller owns the already-created isolated services network and its recovery. */
    public void start(final String runtime, final String servicesNetwork) {
        validateRuntime(runtime);
        if (servicesNetwork != null && !(runtime + "-services-net").equals(servicesNetwork)) {
            throw new IllegalArgumentException("Expected the owned isolated services network");
        }
        if (servicesNetwork == null) {
            final var version =
                    command(false, "version", "--format", "{{.Server.Version}}").trim();
            final int dot = version.indexOf('.');
            if (dot < 1
                    || dot > 3
                    || !version.substring(0, dot).chars().allMatch(Character::isDigit)
                    || Integer.parseInt(version.substring(0, dot)) < 28) {
                throw new IllegalStateException("Enforced egress requires Docker Engine 28 or later");
            }
            // Docker 28+: isolated mode removes the host bridge address, unlike --internal alone.
            final var internalArgs = new ArrayList<>(List.of(
                    "network",
                    "create",
                    "--driver",
                    "bridge",
                    "--internal",
                    "--opt",
                    "com.docker.network.bridge.gateway_mode_ipv4=isolated",
                    "--opt",
                    "com.docker.network.bridge.gateway_mode_ipv6=isolated"));
            internalArgs.addAll(ownership(runtime));
            internalArgs.add(internal(runtime));
            command(false, internalArgs.toArray(String[]::new));
        }
        final var externalArgs = new ArrayList<>(List.of("network", "create", "--driver", "bridge"));
        externalArgs.addAll(ownership(runtime));
        externalArgs.add(external(runtime));
        command(false, externalArgs.toArray(String[]::new));
        final var create = new ArrayList<>(List.of(
                "create",
                "--name",
                gateway(runtime),
                "--network",
                external(runtime),
                "--user",
                "65532:65532",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--read-only",
                "--pids-limit=128",
                "--memory=256m",
                "--cpus=1",
                "--sysctl",
                "net.ipv4.ip_forward=0",
                "--sysctl",
                "net.ipv6.conf.all.forwarding=0"));
        create.addAll(ownership(runtime));
        if (servicesNetwork != null) {
            create.addAll(List.of(
                    "--entrypoint",
                    "java",
                    configuration.image(),
                    "-Xmx128m",
                    "-cp",
                    "@/app/jib-classpath-file",
                    "io.yupiik.ia.agent.bot.gateway.EgressGateway"));
        } else {
            create.add(configuration.image());
        }
        create.addAll(List.of("--lifetime-seconds", Long.toString(lifetime.toSeconds() + 120)));
        create.addAll(configuration.allowedHosts());
        command(false, create.toArray(String[]::new));
        command(
                false,
                "network",
                "connect",
                "--alias",
                "egress",
                servicesNetwork == null ? internal(runtime) : servicesNetwork,
                gateway(runtime));
        command(false, "start", gateway(runtime));
        // Readiness must not outlive a shorter configured executor timeout.
        final long deadline = System.nanoTime()
                + (lifetime.compareTo(Duration.ofSeconds(30)) < 0 ? lifetime : Duration.ofSeconds(30)).toNanos();
        while (System.nanoTime() < deadline) {
            final var health = invoke(
                    Duration.ofNanos(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(3), deadline - System.nanoTime()))),
                    "exec",
                    "--user",
                    "65532:65532",
                    gateway(runtime),
                    "java",
                    "-Xmx32m",
                    "-cp",
                    "@/app/jib-classpath-file",
                    "io.yupiik.ia.agent.bot.gateway.EgressGateway",
                    "--check");
            if (health.code() == 0 && System.nanoTime() <= deadline) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(
                        Math.max(0, Math.min(TimeUnit.MILLISECONDS.toNanos(200), deadline - System.nanoTime())));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Egress startup interrupted", e);
            }
        }
        throw new IllegalStateException("Egress gateway readiness timeout");
    }

    public List<String> agentArguments(final String runtime) {
        validateRuntime(runtime);
        final var args = new ArrayList<>(List.of("--network", internal(runtime), "--dns", configuration.dns()));
        args.addAll(proxyArguments(""));
        return List.copyOf(args);
    }

    /** Proxy environment only: does not attach a second network or override the services DNS sink. */
    public List<String> proxyArguments(final String servicesServerName) {
        if (servicesServerName == null
                || !SERVER_NAME.matcher(servicesServerName).matches()) {
            throw new IllegalArgumentException("Exact services server name required");
        }
        final var nonProxyHosts = "localhost|127.*|[::1]|::1|[0:0:0:0:0:0:0:1]|0:0:0:0:0:0:0:1"
                + (servicesServerName.isEmpty() ? "" : "|" + servicesServerName);
        final var noProxy = "localhost,127.0.0.1,127.0.0.0/8,::1,[::1]"
                + (servicesServerName.isEmpty() ? "" : "," + servicesServerName);
        return List.of(
                "--env",
                "HTTPS_PROXY=http://egress:8080",
                "--env",
                "https_proxy=http://egress:8080",
                "--env",
                "HTTP_PROXY=http://egress:8080",
                "--env",
                "http_proxy=http://egress:8080",
                "--env",
                "ALL_PROXY=",
                "--env",
                "all_proxy=",
                "--env",
                "NO_PROXY=" + noProxy,
                "--env",
                "no_proxy=" + noProxy,
                "--env",
                "JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=egress -Dhttps.proxyPort=8080"
                        + " -Dhttp.proxyHost=egress -Dhttp.proxyPort=8080 -Dhttp.nonProxyHosts=" + nonProxyHosts);
    }

    private List<String> ownership(final String runtime) {
        return policy.labels(runtime, "dependency-egress");
    }

    /** Called only after the agent is stopped. Missing resources are accepted; daemon errors are not. */
    public void remove(final String runtime) {
        validateRuntime(runtime);
        // Cancellation threads can be interrupted; cleanup still needs a bounded opportunity to run.
        final boolean interrupted = Thread.interrupted();
        RuntimeException failure = null;
        try {
            // Verify all resources before deleting any of them.
            final var removals = new ArrayList<String[]>();
            if (owned(runtime, gateway(runtime), false)) {
                removals.add(new String[] {"rm", "--force", gateway(runtime)});
            }
            if (owned(runtime, internal(runtime), true)) {
                removals.add(new String[] {"network", "rm", internal(runtime)});
            }
            if (owned(runtime, external(runtime), true)) {
                removals.add(new String[] {"network", "rm", external(runtime)});
            }
            for (final var args : removals) {
                try {
                    command(true, args);
                } catch (final RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean owned(final String runtime, final String name, final boolean network) {
        final var result = invoke(
                Duration.ofSeconds(10),
                network ? "network" : "container",
                "inspect",
                "--format",
                ExecutionPolicy.inspectFormat(network),
                name);
        if (result.code() == 0
                && policy.ownership(runtime, "dependency-egress")
                        .equals(result.output().strip())) {
            return true;
        }
        if (result.code() != 0) {
            for (final var prefix : List.of("", "Error: ", "Error response from daemon: ")) {
                for (final var kind : network ? List.of("network", "object") : List.of("container", "object")) {
                    if ((prefix + "No such " + kind + ": " + name)
                            .equals(result.output().strip())) {
                        return false;
                    }
                }
                if (network
                        && (prefix + "network " + name + " not found")
                                .equals(result.output().strip())) {
                    return false;
                }
            }
        }
        throw new IllegalStateException("Egress ownership or absence could not be verified");
    }

    private String command(final boolean missingAllowed, final String... arguments) {
        final var result = invoke(Duration.ofSeconds(10), arguments);
        final var text = result.output();
        if (result.code() != 0
                && !(missingAllowed
                        && (text.contains("No such container")
                                || text.contains("No such network")
                                || text.contains("network ") && text.contains(" not found")))) {
            throw new IllegalStateException("Docker egress control failed: " + arguments[0]);
        }
        return text;
    }

    private Result invoke(final Duration budget, final String... arguments) {
        final var args = new ArrayList<String>();
        args.add(executable);
        args.addAll(List.of(arguments));
        Process process = null;
        final var output = new ByteArrayOutputStream();
        try {
            final var builder =
                    DockerControlEnvironment.sanitize(new ProcessBuilder(args)).redirectErrorStream(true);
            final long deadline = System.nanoTime() + budget.toNanos();
            process = builder.start();
            final var running = process;
            final var reader = Thread.ofVirtual().start(() -> {
                try (final var input = running.getInputStream()) {
                    final var buffer = new byte[1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (output.size() < 4096) {
                            output.write(buffer, 0, Math.min(read, 4096 - output.size()));
                        }
                    }
                } catch (final IOException ignored) {
                    // Process close on timeout.
                }
            });
            if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                return new Result(-1, "");
            }
            reader.join(
                    Duration.ofNanos(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(1), deadline - System.nanoTime()))));
            if (reader.isAlive()) {
                return new Result(-1, "");
            }
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("Docker egress control failed", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Docker egress control interrupted", e);
        } finally {
            if (process != null) {
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                } catch (final IOException ignored) {
                    // Process terminated.
                }
            }
        }
    }

    private static void validateRuntime(final String runtime) {
        if (runtime == null
                || runtime.length() > 128
                || !RUNTIME_ID.matcher(runtime).matches()) {
            throw new IllegalArgumentException("Invalid execution identity");
        }
    }

    private static String gateway(final String runtime) {
        return runtime + "-egress";
    }

    private static String internal(final String runtime) {
        return runtime + "-isolated";
    }

    private static String external(final String runtime) {
        return runtime + "-external";
    }

    private record Result(int code, String output) {}
}
