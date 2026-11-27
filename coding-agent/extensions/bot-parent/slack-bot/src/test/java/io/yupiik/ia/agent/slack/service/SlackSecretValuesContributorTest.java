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
package io.yupiik.ia.agent.slack.service;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.SecretRedactionMode;
import io.yupiik.ia.agent.core.security.SecretRedactor;
import io.yupiik.ia.agent.core.security.SecretRegistry;
import io.yupiik.ia.agent.core.security.SecretSessionModes;
import io.yupiik.ia.agent.core.security.SecretVault;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.support.SlackConfigurationTestModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = SlackSecretValuesContributorTest.SettingsModule.class)
class SlackSecretValuesContributorTest {
    @Fusion
    private SlackConfiguration configuration;

    private static final String PASSWORD_ENV = "slack.executor.docker.services.keyStorePasswordEnv";
    private static final Map<String, String> SETTINGS = Map.of(
            "slack.appTokenEnv",
            "CUSTOM_SOCKET",
            "slack.botTokenEnv",
            "CUSTOM_WEB",
            PASSWORD_ENV,
            "CUSTOM_TLS",
            "CUSTOM_SOCKET",
            "socket-opaque-123456",
            "CUSTOM_WEB",
            "web-opaque-234567",
            "OPENAI_API_KEY",
            "model-opaque-345678",
            "CUSTOM_TLS",
            "docker-opaque-456789",
            "YCA_SERVICES_KEYSTORE_PASSWORD",
            "kube-opaque-567890");
    private static final Set<String> CREDENTIAL_KEYS =
            Set.of("CUSTOM_SOCKET", "CUSTOM_WEB", "OPENAI_API_KEY", "CUSTOM_TLS", "YCA_SERVICES_KEYSTORE_PASSWORD");

    @Test
    void contributesProgrammaticValuesToSharedRedaction() {
        final var source = Configuration.of(SETTINGS);
        final var contributor = contributor(source);
        final var expected = CREDENTIAL_KEYS.stream().map(SETTINGS::get).toList();
        assertTrue(Set.copyOf(contributor.values()).equals(Set.copyOf(expected)), "Incomplete credential harvest");

        final var modes = new SecretSessionModes();
        modes.set("slack-test", SecretRedactionMode.REDACT);
        final var registry = new SecretRegistry(null, List.of(), new SecretVault(), List.of(contributor));
        final var redactor = new SecretRedactor(registry, modes);
        final var text = String.join(" ", expected);
        final var masked = redactor.redact(text, "slack-test");
        for (final var secret : expected) {
            assertFalse(masked.contains(secret), "Configured credential was not redacted");
        }
        assertTrue(text.equals(redactor.restore(masked, "slack-test")), "Redaction did not round-trip");
    }

    @Test
    void defaultsToFixedTlsNameAndSkipsAbsentOrBlankValuesAndKeys() {
        final var settings = new HashMap<>(SETTINGS);
        settings.remove(PASSWORD_ENV);
        settings.remove("CUSTOM_TLS");
        settings.put("CUSTOM_SOCKET", " \t");
        settings.remove("CUSTOM_WEB");
        settings.put("OPENAI_API_KEY", "");
        final var defaults = contributor(Configuration.of(settings));
        assertTrue(
                Set.copyOf(defaults.values()).equals(Set.of(SETTINGS.get("YCA_SERVICES_KEYSTORE_PASSWORD"))),
                "Default TLS credential was not harvested independently");

        settings.put(PASSWORD_ENV, " \t");
        final var delegate = Configuration.of(settings);
        final Configuration guarded = name -> {
            assertFalse(name.isBlank(), "Blank configuration key was queried");
            return delegate.get(name);
        };
        assertTrue(
                Set.copyOf(contributor(guarded).values()).equals(Set.copyOf(defaults.values())),
                "Blank TLS key prevented fixed Kubernetes credential harvesting");
    }

    @Test
    void isolatesEveryFailingLookupWithoutCredentialDiagnostics() {
        final var records = new ArrayList<LogRecord>();
        final var handler = new Handler() {
            @Override
            public void publish(final LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        final var loggers = List.of(
                Logger.getLogger(SlackSecretValuesContributor.class.getName()),
                Logger.getLogger(SecretRegistry.class.getName()));
        final var levels = loggers.stream().map(Logger::getLevel).toList();
        loggers.forEach(logger -> {
            logger.addHandler(handler);
            logger.setLevel(Level.ALL);
        });
        final var diagnosticSecret = "exception-credential-678901";
        try {
            final var failingKeys = new ArrayList<>(CREDENTIAL_KEYS);
            failingKeys.add(PASSWORD_ENV);
            final var delegate = Configuration.of(SETTINGS);
            for (final var failingKey : failingKeys) {
                final Configuration failing = name -> {
                    if (failingKey.equals(name)) {
                        throw new IllegalStateException(diagnosticSecret);
                    }
                    return delegate.get(name);
                };
                final var contributor = contributor(failing);
                final var expected = CREDENTIAL_KEYS.stream()
                        .filter(key -> !key.equals(failingKey))
                        .filter(key -> !PASSWORD_ENV.equals(failingKey) || !"CUSTOM_TLS".equals(key))
                        .map(SETTINGS::get)
                        .collect(toSet());
                assertTrue(
                        Set.copyOf(contributor.values()).equals(expected), "Lookup failure prevented other harvesting");
                // The registry logs uncaught contributor failures at FINE, including their messages.
                final var registry = new SecretRegistry(null, List.of(), new SecretVault(), List.of(contributor));
                registry.scan();
            }
            assertTrue(records.isEmpty(), "Credential harvesting emitted diagnostics");
        } finally {
            for (var i = 0; i < loggers.size(); i++) {
                loggers.get(i).removeHandler(handler);
                loggers.get(i).setLevel(levels.get(i));
            }
            handler.close();
        }
    }

    public static class SettingsModule extends SlackConfigurationTestModule {
        public SettingsModule() {
            super(SETTINGS);
        }
    }

    private SlackSecretValuesContributor contributor(final Configuration source) {
        return new SlackSecretValuesContributor(configuration, source);
    }
}
