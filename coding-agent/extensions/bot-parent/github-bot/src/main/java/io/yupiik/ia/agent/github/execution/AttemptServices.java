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

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.ProviderBudgetEnvironment;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.core.configuration.ProviderBudgetConfiguration;
import io.yupiik.ia.agent.github.store.TaskStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Creates a secret-free gateway manifest and replaces all provider credentials in the sandbox handoff. */
public final class AttemptServices implements AutoCloseable {
    private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+");
    private static final Pattern CONTEXT_PATH = Pattern.compile("/context/[0-9a-f-]{36}/rpc");
    private static final Pattern PROVIDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SERVICE_PATH = Pattern.compile("(/[A-Za-z0-9_-]+)*");

    private static final BotTemplates TEMPLATES = new BotTemplates(Map.of("mavenProxy", """
            <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
              <localRepository>/attempt/storage/.m2/repository</localRepository>
              <proxies>
                <proxy>
                  <id>worker-public-dependencies</id>
                  <active>true</active>
                  <protocol>http</protocol>
                  <host>egress</host>
                  <port>8080</port>
                  <nonProxyHosts>localhost|127.*|\\[::1\\]|::1|\\[0:0:0:0:0:0:0:1\\]|0:0:0:0:0:0:0:1|{{{servicesHost}}}</nonProxyHosts>
                </proxy>
              </proxies>
            </settings>
            """));

    private final Path settings;
    private Map<String, String> secrets;

    public AttemptServices(
            final Path configuration,
            final Path privateDirectory,
            final JsonMapper json,
            final ExecutorConfiguration.Docker.Services services,
            final String privateEndpoint,
            final String advertisedEndpoint,
            final Map<String, String> contextCredentials,
            final Configuration sources,
            final ProviderBudgetConfiguration budgetConfiguration,
            final Duration lifetime)
            throws IOException {
        if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(privateDirectory, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(privateDirectory)
                        .equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IllegalArgumentException("Private services require owner-only worker storage");
        }
        final var upstream = URI.create(privateEndpoint);
        final var advertised = URI.create(advertisedEndpoint);
        if (!"http".equals(upstream.getScheme())
                || upstream.getHost() == null
                || !NUMERIC_HOST.matcher(upstream.getHost()).matches()
                || "0.0.0.0".equals(upstream.getHost())
                || upstream.getHost().startsWith("127.")
                || upstream.getPort() < 1
                || upstream.getRawQuery() != null
                || upstream.getRawFragment() != null
                || upstream.getRawUserInfo() != null
                || !CONTEXT_PATH.matcher(upstream.getPath()).matches()
                || !upstream.getPath().equals(advertised.getPath())
                || !"https".equals(advertised.getScheme())
                || !services.serverName().equals(advertised.getHost())
                || advertised.getPort() != 8443
                || advertised.getRawQuery() != null
                || advertised.getRawFragment() != null
                || advertised.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Private services require the exact isolated TLS origin and a numeric worker listener");
        }
        final var original = json.fromString(AgentConfigurationDocument.class, Files.readString(configuration));
        final var yca = original.configuration();
        final var providerRoot = TaskStore.map(yca.get("provider"));
        final var selected = select(providerRoot);
        final var flavor = String.valueOf(selected.getOrDefault("api", "AUTO")).toUpperCase(Locale.ROOT);
        if (!List.of("AUTO", "COMPLETIONS").contains(flavor)
                || !(selected.get("baseUrl") instanceof String base)
                || !endpoint(base).equals(endpoint(services.providerTarget()))
                || !(selected.get("model") instanceof String model)
                || model.isBlank()) {
            throw new IllegalArgumentException(
                    "Private services require one explicitly matched chat-completions provider and model");
        }
        final var keyStorePassword = sources.get(services.keyStorePasswordEnv()).orElse(null);
        if (keyStorePassword == null || keyStorePassword.isBlank()) {
            throw new IllegalArgumentException(
                    "Private services require the configured TLS password environment variable");
        }
        final var key = selected.get("apiKey") instanceof String value && !value.isBlank()
                ? value
                : sources.get("OPENAI_API_KEY").orElse("");
        if (key.length() > 8192 || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid private provider credential");
        }
        final var random = new byte[32];
        new SecureRandom().nextBytes(random);
        final var capability = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        final var inferencePath = "/inference/" + UUID.randomUUID() + "/chat/completions";
        final var id = selected.get("id") instanceof String value
                        && PROVIDER_ID.matcher(value).matches()
                ? value
                : "default";
        final var safe = new LinkedHashMap<String, Object>();
        safe.put("id", id);
        safe.put("api", "COMPLETIONS");
        safe.put("baseUrl", "https://" + services.serverName() + ":8443" + inferencePath);
        safe.put("apiKey", capability);
        safe.put("model", model);
        // Never copy headers, dynamic templates, alternate routes, model providers, or arbitrary authentication fields.
        for (final var field : List.of("contextWindow", "outputTokens", "timeoutSeconds", "maxConcurrent")) {
            if (selected.get(field) instanceof Number value) {
                safe.put(field, value);
            }
        }
        final var rewritten = new LinkedHashMap<>(yca);
        rewritten.put("provider", Map.of("defaultProvider", id, "providers", List.of(safe)));
        rewritten.remove("model");
        // In an unwrapped document, settings also supplies configuration(). Do not copy root run options into yca.
        if (original.yca() == null) {
            rewritten.remove("run");
        }
        final var timeout = selected.get("timeoutSeconds") instanceof Number number
                ? Math.max(1, Math.min(900, number.intValue()))
                : 300;
        final var gateway = new RestrictedServicesGateway.Settings(
                services.serverName(),
                8443,
                "/services/tls.p12",
                services.keyStorePasswordEnv(),
                upstream.getPath().substring(0, upstream.getPath().length() - 4),
                "http://" + upstream.getRawAuthority(),
                inferencePath,
                endpoint(services.providerTarget()),
                lifetime.toSeconds(),
                timeout,
                4L * 1024 * 1024,
                1024L * 1024 * 1024);
        final var credentials = new LinkedHashMap<>(contextCredentials);
        credentials.put("YCA_SERVICES_PROVIDER_TOKEN", capability);
        credentials.put("YCA_SERVICES_UPSTREAM_TOKEN", key);
        credentials.put(services.keyStorePasswordEnv(), keyStorePassword);
        credentials.putAll(
                ProviderBudgetEnvironment.select(budgetConfiguration, configuration.getParent(), privateDirectory));
        settings = privateDirectory.resolve("services.json");
        Files.writeString(settings, json.toString(gateway), StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS);
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(
                configuration,
                json.toString(new AgentConfigurationDocument(rewritten, original.completionSettings())),
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
        if (services.dependencyEgress().enabled()
                && !services.dependencyEgress().allowedHosts().isEmpty()) {
            stageMavenProxy(configuration.getParent().resolve("storage"), services.serverName());
        }
        secrets = Map.copyOf(credentials);
    }

    private static void stageMavenProxy(final Path storage, final String servicesHost) throws IOException {
        final var directoryPermissions =
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
        if (!Files.exists(storage, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(storage, directoryPermissions);
        }
        if (!Files.isDirectory(storage, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Dependency settings require a private attempt storage directory");
        }
        final var maven = storage.resolve(".m2");
        if (!Files.exists(maven, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(maven, directoryPermissions);
        }
        if (!Files.isDirectory(maven, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Maven settings require a real private directory");
        }
        Files.setPosixFilePermissions(maven, PosixFilePermissions.fromString("rwx------"));
        final var settings = maven.resolve("settings.xml");
        if (Files.exists(settings, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Maven settings must be a regular file");
        }
        // DNS-only, operator-validated servicesHost cannot introduce XML markup. Replace
        // generated settings atomically; never truncate a possible preexisting hard link.
        final var temporary = Files.createTempFile(maven, ".worker-proxy-", ".xml");
        try {
            Files.writeString(
                    temporary,
                    TEMPLATES.render("mavenProxy", Map.of("servicesHost", servicesHost)),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.move(temporary, settings, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Object> select(final Map<String, Object> provider) {
        if (provider.get("providers") instanceof List<?> entries && !entries.isEmpty()) {
            if (entries.size() > 64 || entries.stream().anyMatch(it -> !(it instanceof Map<?, ?>))) {
                throw new IllegalArgumentException("Invalid private provider selection");
            }
            final var id = provider.get("defaultProvider");
            return entries.stream()
                    .map(TaskStore::map)
                    .filter(it -> id != null && id.equals(it.get("id")))
                    .findFirst()
                    .orElseGet(() -> TaskStore.map(entries.getFirst()));
        }
        return provider;
    }

    private static String endpoint(final String base) {
        final var uri = URI.create(base);
        if (!"http".equals(uri.getScheme())
                || uri.getHost() == null
                || !NUMERIC_HOST.matcher(uri.getHost()).matches()
                || uri.getPort() < 1
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !SERVICE_PATH.matcher(uri.getRawPath()).matches()) {
            throw new IllegalArgumentException(
                    "Private provider must be an explicit numeric HTTP target on the trusted control network");
        }
        final var value = uri.toASCIIString();
        return value.endsWith("/chat/completions") ? value : value + "/chat/completions";
    }

    public Path settings() {
        return settings;
    }

    /** Only for the private gateway launch environment; never add this map to the agent environment. */
    public Map<String, String> credentials() {
        return secrets;
    }

    @Override
    public void close() {
        secrets = Map.of();
        // The broker owns the private manifest and deletes it with its attempt-bound storage.
    }
}
