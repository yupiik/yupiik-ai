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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CloudCredentialsSecretConfiguration;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Harvests secret values from the well-known cloud provider credential files (INI for AWS, JSON for
 * GCP/Azure): {@code yca.secrets.cloudCredentials.aws.locations} (default {@code ~/.aws/credentials} +
 * {@code ~/.aws/config}), {@code .gcp.locations} (default
 * {@code ~/.config/gcloud/application_default_credentials.json}) and {@code .az.locations} (empty by
 * default). Only static, long-lived secret keys are harvested (e.g. {@code aws_secret_access_key},
 * {@code aws_session_token}, {@code client_secret}, {@code refresh_token}, {@code access_token},
 * {@code private_key}); short-lived token caches (AWS SSO, Azure MSAL) and the SQLite gcloud
 * {@code credentials.db} / {@code access_tokens.db} are not scanned by default.
 */
@ApplicationScoped
public class CloudCredentialsSecretValuesContributor implements SecretValuesContributor {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    private static final Logger LOGGER = Logger.getLogger(CloudCredentialsSecretValuesContributor.class.getName());

    /** Default AWS credential locations. */
    private static final List<String> DEFAULT_AWS = List.of("~/.aws/credentials", "~/.aws/config");

    /** Default GCP credential locations. */
    private static final List<String> DEFAULT_GCP = List.of("~/.config/gcloud/application_default_credentials.json");

    /** Default Azure credential locations (empty: only short-lived token caches exist by default). */
    private static final List<String> DEFAULT_AZ = List.of();

    /**
     * Compacted (lower-cased, separators stripped) secret key markers harvested from credential files. A
     * key is treated as a secret when its compacted form contains any of these phrases.
     */
    private static final Set<String> SECRET_KEYS = Set.of(
            "awssecretaccesskey",
            "awssessiontoken",
            "clientsecret",
            "refreshtoken",
            "accesstoken",
            "privatekey",
            "idtoken",
            "sessiontoken",
            "secret");

    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;

    public CloudCredentialsSecretValuesContributor(
            final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this.configuration = configuration;
        this.mapper = mapper;
    }

    @Override
    public Collection<String> values() {
        if (mapper == null) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        for (final var path : awsLocations()) {
            harvest(path, out);
        }
        for (final var path : gcpLocations()) {
            harvest(path, out);
        }
        for (final var path : azLocations()) {
            harvest(path, out);
        }
        return out;
    }

    private List<String> awsLocations() {
        final var cloud = cloud();
        return cloud == null || cloud.aws() == null || empty(cloud.aws().locations())
                ? DEFAULT_AWS
                : cloud.aws().locations();
    }

    private List<String> gcpLocations() {
        final var cloud = cloud();
        return cloud == null || cloud.gcp() == null || empty(cloud.gcp().locations())
                ? DEFAULT_GCP
                : cloud.gcp().locations();
    }

    private List<String> azLocations() {
        final var cloud = cloud();
        return cloud == null || cloud.az() == null || empty(cloud.az().locations())
                ? DEFAULT_AZ
                : cloud.az().locations();
    }

    private CloudCredentialsSecretConfiguration cloud() {
        return configuration == null || configuration.secrets() == null
                ? null
                : configuration.secrets().cloudCredentials();
    }

    private static boolean empty(final List<String> locations) {
        return locations == null || locations.isEmpty();
    }

    /** A leading {@code [section]} line: distinguishes an INI file from a JSON array. */
    private static final Pattern INI_SECTION = Pattern.compile("\\[[A-Za-z0-9_.\\-]+]");

    private void harvest(final String location, final Collection<String> out) {
        final var path = SecretPaths.resolve(configuration, location);
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            final var content = Files.readString(path, StandardCharsets.UTF_8);
            final var trimmed = content.stripLeading();
            // a '{' or a '[' not followed by an INI [section] header means JSON; anything else is INI
            final var json = trimmed.startsWith("{")
                    || (trimmed.startsWith("[") && !INI_SECTION.matcher(trimmed).lookingAt());
            if (json) {
                harvestJson(content, out);
            } else {
                harvestIni(content, out);
            }
        } catch (final IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, () -> "Cannot parse cloud credential file " + path + ": " + e.getMessage());
        }
    }

    private void harvestJson(final String content, final Collection<String> out) {
        final var root = mapper.read(Map.class, new StringReader(content));
        walk("", root, out);
    }

    private void walk(final String key, final Object value, final Collection<String> out) {
        if (value instanceof Map) {
            ((Map<?, ?>) value).forEach((k, v) -> walk(String.valueOf(k), v, out));
        } else if (value instanceof Iterable) {
            for (final var item : (Iterable<?>) value) {
                walk(key, item, out);
            }
        } else if (value instanceof CharSequence s) {
            if (isSecretKey(key)) {
                out.add(s.toString());
            }
        }
    }

    private void harvestIni(final String content, final Collection<String> out) {
        for (final var raw : LINE_BREAK.split(content)) {
            final var line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("[")) {
                continue;
            }
            final var eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            final var key = line.substring(0, eq).trim();
            if (isSecretKey(key)) {
                final var value = line.substring(eq + 1).trim();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            }
        }
    }

    /** Whether a credential key name (case-insensitive, separators stripped) is a secret marker. */
    private static boolean isSecretKey(final String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        final var compact =
                key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", " ");
        final var normalized = compact.replace(" ", "");
        for (final var secret : SECRET_KEYS) {
            if (normalized.contains(secret)) {
                return true;
            }
        }
        return false;
    }
}
