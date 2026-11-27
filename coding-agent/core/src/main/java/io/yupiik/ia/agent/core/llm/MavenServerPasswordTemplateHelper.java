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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.helper.SafeString;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.security.MavenSecretService;
import io.yupiik.ia.agent.core.security.SecretPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Provides the {@code mavenServerPassword} handlebars helper used by the provider {@code dynamicHeaders}
 * to build an authentication header from a Maven settings.xml server credential.
 *
 * <p>Usage: {@code {{mavenServerPassword "serverId"}}}. The server id is looked up in the configured Maven
 * settings file ({@code yca.secrets.maven.settings}, default {@code ~/.m2/settings.xml}) and its password is
 * returned. The stored value is used as-is when it is clear text or a Maven {@code ${env.X}} / {@code ${sys.Y}}
 * placeholder, and a Maven 4 ciphertext is deciphered in memory (see {@link MavenSecretService}). A Maven 3
 * legacy {@code {base64}} password cannot be recovered and fails the render with a {@code mvnenc} hint.</p>
 *
 * <p>The returned value is a {@link SafeString} so it renders raw into the header. A missing server, missing
 * password or an unresolvable credential fails the render (an {@link IllegalArgumentException}), so a
 * configuration error surfaces loudly instead of silently sending an empty header - and, because the
 * deciphered value never leaves the JVM except into the request header, the plaintext credential is not
 * written to disk.</p>
 */
@ApplicationScoped
public class MavenServerPasswordTemplateHelper implements CustomDynamicHeaderTemplateHelper {

    private final CodingAgentConfiguration configuration;
    private final MavenSecretService mavenSecretService;

    public MavenServerPasswordTemplateHelper(
            final CodingAgentConfiguration configuration, final MavenSecretService mavenSecretService) {
        this.configuration = configuration;
        this.mavenSecretService = mavenSecretService;
    }

    @Override
    public String name() {
        return "mavenServerPassword";
    }

    @Override
    public Object apply(final HelperContext context) {
        final var args = context.args();
        // a bare helper call ({{mavenServerPassword}}) is a ThisHelperPart: the current data (a Map) is
        // passed as a single "this" argument and must not be treated as a server id
        final var serverId = args.size() == 1 && args.get(0) instanceof Map ? null : String.valueOf(args.get(0));
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException(
                    "mavenServerPassword expects a server id argument, e.g. " + "{{mavenServerPassword \"central\"}}");
        }
        final var settings = resolveSettings();
        if (settings == null || !Files.isRegularFile(settings)) {
            throw new IllegalArgumentException("Cannot look up Maven server '" + serverId + "': settings file "
                    + settings + " is not readable " + "(configure yca.secrets.maven.settings)");
        }
        final var password = mavenSecretService.resolveServerPassword(settings, serverId);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Maven server '" + serverId + "' has an empty password in " + settings);
        }
        return new SafeString(password);
    }

    private Path resolveSettings() {
        if (configuration == null
                || configuration.secrets() == null
                || configuration.secrets().maven() == null) {
            return null;
        }
        final var maven = configuration.secrets().maven();
        if (maven.settings() == null || maven.settings().isBlank()) {
            return null;
        }
        return SecretPaths.resolve(configuration, maven.settings());
    }
}
