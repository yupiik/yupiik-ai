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
package io.yupiik.ia.agent.bot.runtime.configuration;

import io.yupiik.fusion.framework.build.api.configuration.ConfigurationModel;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.ia.agent.core.shell.ToolOutputVisibility;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Trusted launcher settings, separate from queue state and operation options. */
@ConfigurationModel
@JsonModel
public record ExecutorConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration.Backend.DOCKER",
                documentation = "Executor backend: DOCKER, NONO or KUBERNETES.")
        Backend backend,

        @Property(
                defaultValue = "\"\"",
                documentation = "Private attempt root. Kubernetes requires this to be the mounted root of its dedicated"
                        + " attempt PVC; never use the queue PVC.")
        String attemptRoot,

        @Property(defaultValue = "3600", documentation = "Maximum execution duration in seconds.")
        int timeoutSeconds,

        @Property(defaultValue = "30", documentation = "Maximum executor control request duration in seconds.")
        int commandTimeoutSeconds,

        @Property(
                defaultValue = "\"shown\"",
                documentation = "Agent tool visibility: shown prints requested arguments, streams and results; "
                        + "collapsed prints lifecycle summaries; hidden suppresses ordinary tool activity. "
                        + "Shown includes shell commands and can expose sensitive tool data in execution logs.")
        String showToolOutput,

        @Property(documentation = "Docker launcher settings.")
        Docker docker,

        @Property(documentation = "Nono launcher settings.") Nono nono,

        @Property(documentation = "Kubernetes launcher and client settings.")
        Kubernetes kubernetes,

        @Property(documentation = "Credential relay settings.")
        Relay relay) {
    private static final Pattern PROVIDER_PATH = Pattern.compile("(?:/[A-Za-z0-9_-]+)*");

    public ExecutorConfiguration {
        final var visibility = ToolOutputVisibility.parse(showToolOutput);
        if (visibility == null) {
            throw new IllegalArgumentException("executor.showToolOutput must be shown, collapsed or hidden");
        }
        showToolOutput = visibility.name().toLowerCase(Locale.ROOT);
    }

    @JsonModel
    public enum Backend {
        DOCKER,
        NONO,
        KUBERNETES
    }

    @ConfigurationModel
    @JsonModel
    public record Docker(
            @Property(defaultValue = "\"docker\"", documentation = "Docker CLI executable.")
            String executable,

            @Property(defaultValue = "\"yca-agent:latest\"", documentation = "Trusted agent container image.")
            String image,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Optional pre-created network for unrestricted mode; not an egress security boundary.")
            String network,

            @Property(documentation = "Optional enforced CONNECT gateway. Null disables enforcement.")
            Egress egress,

            @Property(documentation = "Optional isolated TLS attempt services gateway. Null disables this mode.")
            Services services,

            @Property(defaultValue = "false", documentation = "Disable Docker daemon container logs for the agent.")
            boolean disableContainerLogs) {
        public Docker {
            // Fusion materializes nested records even when no nested property was supplied.
            if (egress != null
                    && (egress.image() == null || egress.image().isBlank())
                    && (egress.allowedHosts() == null || egress.allowedHosts().isEmpty())) {
                egress = null;
            }
            if (services != null
                    && blank(services.image())
                    && blank(services.controlNetwork())
                    && blank(services.keyStore())
                    && blank(services.providerTarget())
                    && "YCA_SERVICES_KEYSTORE_PASSWORD".equals(services.keyStorePasswordEnv())
                    && "attempt-services.yca.internal".equals(services.serverName())) {
                services = null;
            }
            if (services != null) {
                if (egress != null || !blank(network)) {
                    throw new IllegalArgumentException("Restricted services cannot use egress or an agent network");
                }
                services.validate();
            }
        }

        private static boolean blank(final String value) {
            return value == null || value.isBlank();
        }

        @ConfigurationModel
        @JsonModel
        public record Services(
                @Property(documentation = "Trusted services gateway image, preferably pinned by digest.")
                String image,

                @Property(documentation = "Operator-created trusted bridge network, never attached to the agent.")
                String controlNetwork,

                @Property(documentation = "Private dispatcher PKCS12 TLS key store path.")
                String keyStore,

                @Property(
                        defaultValue = "\"YCA_SERVICES_KEYSTORE_PASSWORD\"",
                        documentation = "Gateway TLS password environment variable name.")
                String keyStorePasswordEnv,

                @Property(
                        defaultValue = "\"attempt-services.yca.internal\"",
                        documentation = "TLS gateway DNS alias on the isolated attempt network.")
                String serverName,

                @Property(
                        documentation =
                                "Fixed numeric HTTP provider origin with optional path prefix, for example http://172.20.0.1:8080/v1.")
                String providerTarget,

                @Property(
                        documentation = "Public dependency CONNECT gateway for HTTPS on port 443. Defaults allow GitHub"
                                + " source/API/archive/raw/object downloads (github.com, api.github.com,"
                                + " codeload.github.com, objects.githubusercontent.com, raw.githubusercontent.com),"
                                + " Maven Central artifacts (repo.maven.apache.org, repo1.maven.org), npm packages"
                                + " (registry.npmjs.org), and Docker Hub registry/auth/image layers (docker.io,"
                                + " *.docker.io, production.cloudflare.docker.com,"
                                + " docker-images-prod.6aa30f8b08e16409b46e0173d6de2f56.r2.cloudflarestorage.com)."
                                + " Azul hosts support selecting a project-compatible JDK using version/platform metadata"
                                + " (api.azul.com; https://api.azul.com/metadata/v1/docs/swagger) and downloading Zulu"
                                + " binaries (cdn.azul.com; https://cdn.azul.com/zulu/bin/). Apache subdomains"
                                + " (*.apache.org) support build tooling such as Maven, including current releases"
                                + " (https://maven.apache.org/download.cgi) and archived project-compatible versions"
                                + " (https://archive.apache.org/dist/maven/). This is a host policy, not a URL-path"
                                + " allowlist; links illustrate intended use and do not restrict paths. Wildcards match"
                                + " subdomains, not the apex. Every resolved address must pass public-IP validation"
                                + " before connecting. An explicit allowedHosts list replaces defaults; an empty list"
                                + " or enabled=false disables dependency downloads. Omitted hosts retain defaults.")
                Egress dependencyEgress) {
            private static final List<String> DEPENDENCY_HOSTS = List.of(
                    "github.com",
                    "api.github.com",
                    "codeload.github.com",
                    "objects.githubusercontent.com",
                    "raw.githubusercontent.com",
                    "repo.maven.apache.org",
                    "repo1.maven.org",
                    "registry.npmjs.org",
                    "docker.io",
                    "*.docker.io",
                    "production.cloudflare.docker.com",
                    "docker-images-prod.6aa30f8b08e16409b46e0173d6de2f56.r2.cloudflarestorage.com",
                    "cdn.azul.com",
                    "api.azul.com",
                    "*.apache.org");
            private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
            private static final Pattern ENV = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
            private static final Pattern DNS = Pattern.compile(
                    "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
            private static final Pattern IPV4 = Pattern.compile("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
            private static final Set<String> RESERVED_ENV = Set.of(
                    "PATH",
                    "HOME",
                    "JAVA_TOOL_OPTIONS",
                    "JDK_JAVA_OPTIONS",
                    "_JAVA_OPTIONS",
                    "ENV",
                    "BASH_ENV",
                    "SHELLOPTS",
                    "BASHOPTS",
                    "CDPATH",
                    "IFS",
                    "SSH_AUTH_SOCK",
                    "YCA_CONTEXT_TOKEN",
                    "YCA_CONTEXT_GIT_TOKEN",
                    "YCA_SERVICES_PROVIDER_TOKEN",
                    "YCA_SERVICES_UPSTREAM_TOKEN");

            public Services {
                keyStorePasswordEnv =
                        keyStorePasswordEnv == null ? "YCA_SERVICES_KEYSTORE_PASSWORD" : keyStorePasswordEnv;
                serverName = serverName == null ? "attempt-services.yca.internal" : serverName;
                dependencyEgress = dependencyEgress == null
                        ? new Egress(image, DEPENDENCY_HOSTS, "127.0.0.1", true)
                        : new Egress(
                                blank(dependencyEgress.image()) ? image : dependencyEgress.image(),
                                dependencyEgress.allowedHosts() == null
                                        ? DEPENDENCY_HOSTS
                                        : dependencyEgress.allowedHosts(),
                                dependencyEgress.dns() == null ? "127.0.0.1" : dependencyEgress.dns(),
                                dependencyEgress.enabled());
            }

            public Services(
                    final String image,
                    final String controlNetwork,
                    final String keyStore,
                    final String keyStorePasswordEnv,
                    final String serverName,
                    final String providerTarget) {
                this(image, controlNetwork, keyStore, keyStorePasswordEnv, serverName, providerTarget, null);
            }

            public void validate() {
                if (blank(image)
                        || image.length() > 512
                        || image.startsWith("-")
                        || image.chars().anyMatch(Character::isWhitespace)
                        || blank(controlNetwork)
                        || !NAME.matcher(controlNetwork).matches()
                        || blank(keyStore)
                        || keyStore.length() > 4096
                        || keyStore.contains(",")
                        || !Path.of(keyStore).isAbsolute()
                        || !ENV.matcher(keyStorePasswordEnv).matches()
                        || keyStorePasswordEnv.startsWith("DOCKER_")
                        || keyStorePasswordEnv.startsWith("LD_")
                        || keyStorePasswordEnv.startsWith("DYLD_")
                        || keyStorePasswordEnv.startsWith("XDG_")
                        || RESERVED_ENV.contains(keyStorePasswordEnv)
                        || serverName.length() > 253
                        || !DNS.matcher(serverName).matches()
                        || blank(providerTarget)
                        || providerTarget.length() > 2048) {
                    throw new IllegalArgumentException("Incomplete or invalid restricted services configuration");
                }
                try {
                    final var uri = new URI(providerTarget);
                    final var host = uri.getHost();
                    if (!"http".equals(uri.getScheme())
                            || host == null
                            || uri.getRawUserInfo() != null
                            || uri.getRawQuery() != null
                            || uri.getRawFragment() != null
                            || uri.getPort() < 1
                            || uri.getPort() > 65535
                            || !PROVIDER_PATH.matcher(uri.getRawPath()).matches()
                            || !uri.normalize().equals(uri)) {
                        throw new IllegalArgumentException("Fixed numeric HTTP provider target required");
                    }
                    if (!IPV4.matcher(host).matches()) {
                        throw new IllegalArgumentException("Numeric private provider target required");
                    }
                    final var octets = host.split("\\.");
                    for (final var octet : octets) {
                        if (Integer.parseInt(octet) > 255
                                || !Integer.toString(Integer.parseInt(octet)).equals(octet)) {
                            throw new IllegalArgumentException("Canonical numeric provider target required");
                        }
                    }
                    final int first = Integer.parseInt(octets[0]);
                    final int second = Integer.parseInt(octets[1]);
                    if (!(first == 127
                            || first == 10
                            || (first == 172 && second >= 16 && second <= 31)
                            || (first == 192 && second == 168))) {
                        throw new IllegalArgumentException("Private numeric provider target required");
                    }
                } catch (final URISyntaxException e) {
                    throw new IllegalArgumentException("Invalid numeric HTTP provider target");
                }
            }
        }

        @ConfigurationModel
        @JsonModel
        public record Egress(
                @Property(
                        documentation = "Trusted gateway image, preferably pinned by digest. Required when configured.")
                String image,

                @Property(
                        documentation = "DNS hostnames allowed for public HTTPS CONNECT on port 443; exact hosts or"
                                + " *.domain suffixes, never IPs, URLs or paths. Wildcards match subdomains, not the"
                                + " apex. The gateway does not inspect tunnel paths; every resolved address must pass"
                                + " public-IP validation before connecting. In services mode, omitted hosts use the"
                                + " dependencyEgress defaults, an explicit list replaces them, and an empty list or"
                                + " enabled=false disables dependency downloads.")
                List<String> allowedHosts,

                @Property(
                        defaultValue = "\"127.0.0.1\"",
                        documentation =
                                "Agent DNS sink address; numeric IPv4 or IPv6 loopback. No external resolver is permitted in enforced mode.")
                String dns,

                @Property(
                        // Keep this expression textual in cross-module Fusion metadata (a boolean literal is lost).
                        defaultValue = "java.lang.Boolean.TRUE",
                        documentation = "Enable the public dependency gateway in services mode.")
                boolean enabled) {
            public Egress(final String image, final List<String> allowedHosts, final String dns) {
                this(image, allowedHosts, dns, true);
            }
        }
    }

    @ConfigurationModel
    @JsonModel
    public record Nono(
            @Property(defaultValue = "\"nono\"", documentation = "Nono CLI executable.")
            String executable,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Absolute trusted agent executable launcher within the Nono runtime root (not a JAR).")
            String agentExecutable,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Dedicated credential-free Nono runtime root containing JDK, agent and tools.")
            String runtimeRoot) {}

    @ConfigurationModel
    @JsonModel
    public record Kubernetes(
            @Property(defaultValue = "\"yca\"", documentation = "Dedicated Kubernetes executor namespace.")
            String namespace,

            @Property(
                    defaultValue = "\"yca-attempts\"",
                    documentation = "Dedicated attempt PVC claim, containing no dispatcher state.")
            String claim,

            @Property(defaultValue = "\"yca-agent:latest\"", documentation = "Trusted agent container image.")
            String image,

            @Property(
                    defaultValue = "\"yca-agent\"",
                    documentation = "Unprivileged executor service account with token automount disabled.")
            String serviceAccount,

            @Property(
                    defaultValue = "java.lang.Long.valueOf(10001L)",
                    documentation = "Unprivileged executor user identifier.")
            long uid,

            @Property(
                    defaultValue = "java.lang.Long.valueOf(10001L)",
                    documentation = "Unprivileged executor group identifier.")
            long gid,

            @Property(
                    defaultValue = "\"\"",
                    documentation = "Kubernetes API URL; empty retains Fusion in-cluster discovery.")
            String master,

            @Property(
                    defaultValue = "\"/var/run/secrets/kubernetes.io/serviceaccount/token\"",
                    documentation = "Token file path, or none to disable token authentication.")
            String token,

            @Property(
                    defaultValue = "\"/var/run/secrets/kubernetes.io/serviceaccount/ca.crt\"",
                    documentation = "CA PEM content or certificate file path.")
            String certificates,

            @Property(defaultValue = "\"\"", documentation = "Client authentication private key PEM content.")
            String privateKey,

            @Property(defaultValue = "\"\"", documentation = "Client authentication X509 certificate PEM content.")
            String privateKeyCertificate,

            @Property(defaultValue = "false", documentation = "Disable Kubernetes certificate validation (insecure).")
            boolean skipTls,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Private dispatcher JSON kubeconfig path. External exec/auth-provider plugins are rejected.")
            String kubeconfig) {}

    @ConfigurationModel
    @JsonModel
    public record Relay(
            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Optional numeric private listening address for a per-attempt OpenAI-compatible credential"
                                    + " relay; empty disables. Agent configuration receives only the ephemeral relay"
                                    + " token.")
            String bind,

            @Property(
                    defaultValue = "\"\"",
                    documentation =
                            "Relay base URL reachable from the selected sandbox, without port/path; the ephemeral port"
                                    + " is appended. Operator-managed routing and egress required.")
            String advertised) {}
}
