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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.security.MavenSecretService;
import io.yupiik.ia.agent.core.test.TestJvm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the {@code {{mavenServerPassword "serverId"}}} dynamic-header helper: it resolves a Maven
 * settings.xml server password, deciphering a Maven 4 ciphertext in memory (clear text and
 * {@code ${env.X}} / {@code ${sys.Y}} pass through), and fails loudly on a missing server, a
 * missing password or a Maven 3 legacy {@code {base64}} value. Uses a fake {@code .m2} in a temp dir
 * with a plaintext-file master source so the test is independent of the process environment.
 */
@FusionSupport(modules = MavenServerPasswordTemplateHelperTest.MavenModule.class)
class MavenServerPasswordTemplateHelperTest {

    private static final String MASTER = "YCA_HELPER_MASTER_SECRET";
    private static final String SERVER_PASSWORD = "YCA_HELPER_SERVER_PASSWORD";
    // Synthetic public credentials; independent OpenSSL 4.0.3 libcrypto vector (2026-11-16), not a Java round trip.
    // Precomputed to avoid test-time encryption; production decryption must retain all 310,000 iterations.
    // Recipe: MASTER and SERVER_PASSWORD as UTF-8 without newlines; salt=202122232425262728292a2b2c2d2e2f,
    // IV=303132333435363738393a3b (fixed for this vector only, never for production encryption).
    // PKCS5_PBKDF2_HMAC with EVP_sha512(), 310000 iterations, 32-byte key; EVP_aes_256_gcm(),
    // no padding, empty AAD, 16-byte tag. Wrap standard Base64(IV || salt || ciphertext || tag)
    // in the Maven prefix/suffix below. Use libcrypto's EVP API: OpenSSL enc does not support GCM.
    private static final String SERVER_CIPHERTEXT =
            "{[name=master,cipher=AES/GCM/NoPadding,version=4.0]MDEyMzQ1Njc4OTo7ICEiIyQlJicoKSorLC0uL3wGicWimiArTLNFI2W3zICctKr13Oq7RQTgB8zlUzRZm/EehIWxTODOhg==}";

    @TempDir
    static Path tmp;

    @Fusion
    CodingAgentConfiguration configuration;

    @Test
    void decryptsMaven4CiphertextAndPlainPassword() throws Exception {
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, SERVER_CIPHERTEXT, """
                <server>
                  <id>other</id>
                  <username>u</username>
                  <password>clear-text</password>
                </server>
                """);

        final var helper = helper();
        // the ciphered server is deciphered in memory
        assertEquals(SERVER_PASSWORD, render(helper, "{{mavenServerPassword \"central\"}}"));
        // the clear-text server is returned as-is
        assertEquals("clear-text", render(helper, "{{mavenServerPassword \"other\"}}"));
    }

    @Test
    void resolvesSystemPropertyPlaceholder() throws Exception {
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, "${sys.test.never.set.placeholder}", """
                <server>
                  <id>prop</id>
                  <username>u</username>
                  <password>${sys.yca.test.server.password}</password>
                </server>
                """);
        TestJvm.run(
                tmp,
                SystemPropertyPeer.class,
                Map.of("yca.test.server.password", SERVER_PASSWORD),
                tmp.toAbsolutePath().toString());
    }

    public static final class SystemPropertyPeer {
        public static void main(final String[] args) throws Exception {
            try (final var container = ConfiguringContainer.of()
                            .register(new MavenModule(Path.of(args[0])))
                            .start();
                    final var instance = container.lookup(MavenServerPasswordTemplateHelper.class)) {
                final var helper = instance.instance();
                // a resolved ${sys.NAME} placeholder expands to the system property value
                assertEquals(SERVER_PASSWORD, render(helper, "{{mavenServerPassword \"prop\"}}"));
                // an unresolved ${sys.NAME} placeholder resolves to an empty value and fails the render
                final var e = assertThrows(
                        RuntimeException.class, () -> render(helper, "{{mavenServerPassword \"central\"}}"));
                assertTrue(e.getMessage().contains("central"), e.getMessage());
            }
        }
    }

    @Test
    void resolvesEnvPlaceholderWhenPresent() throws Exception {
        final var value = System.getenv("YCA_TEST_MAVEN_SERVER_PASSWORD");
        if (value == null) {
            // cannot mutate the process environment portably; skip when the CI env has no sample value
            return;
        }
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, "${env.YCA_TEST_MAVEN_SERVER_PASSWORD}", "");
        assertEquals(value, render(helper(), "{{mavenServerPassword \"central\"}}"));
    }

    @Test
    void missingServerFailsTheRender() throws Exception {
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, SERVER_PASSWORD, "");
        final var helper = helper();
        final var e = assertThrows(RuntimeException.class, () -> render(helper, "{{mavenServerPassword \"absent\"}}"));
        assertTrue(e.getMessage().contains("absent"), e.getMessage());
    }

    @Test
    void legacyCiphertextFailsWithReEncryptHint() throws Exception {
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, "{BteqUEnqHecHM7MZfnj9FwLcYbdInWxou1C929Txa0A=}", "");
        final var helper = helper();
        final var e = assertThrows(RuntimeException.class, () -> render(helper, "{{mavenServerPassword \"central\"}}"));
        assertTrue(e.getMessage().contains("mvnenc"), e.getMessage());
    }

    @Test
    void rejectedWhenNoMasterSecretIsResolvable() throws Exception {
        // a settings-security4.xml with no resolvable source -> must fail rather than leak ciphertext
        final var m2 = new FakeM2(tmp);
        m2.writeSettings(MASTER, SERVER_CIPHERTEXT, "");
        // overwrite the security file produced by writeSettings with a dispatcher carrying no source
        Files.writeString(m2.security(), """
                <settingsSecurity>
                  <defaultDispatcher>master</defaultDispatcher>
                  <configurations>
                    <configuration>
                      <name>master</name>
                    </configuration>
                  </configurations>
                </settingsSecurity>
                """);
        final var helper = helper();
        final var e = assertThrows(RuntimeException.class, () -> render(helper, "{{mavenServerPassword \"central\"}}"));
        assertTrue(e.getMessage().contains("master secret"), e.getMessage());
    }

    private MavenServerPasswordTemplateHelper helper() {
        return new MavenServerPasswordTemplateHelper(configuration, new MavenSecretService());
    }

    public static class MavenModule extends ConfigurationTestModule {
        public MavenModule() {
            this(tmp);
        }

        private MavenModule(final Path directory) {
            super(Map.of(
                    "yca.secrets.maven.settings",
                            directory.resolve(".m2/settings.xml").toString(),
                    "yca.secrets.maven.masterSecret",
                            directory.resolve(".m2/settings-security4.xml").toString()));
        }
    }

    private static String render(final MavenServerPasswordTemplateHelper helper, final String source) {
        final Function<HelperContext, Object> fn = helper;
        final Template template = new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(Map.of(helper.name(), fn)), source));
        return template.render(Map.of());
    }

    /** A minimal fake Maven tree (a {@code .m2} clone) with settings.xml and settings-security4.xml. */
    private static final class FakeM2 {
        private final Path root;

        private FakeM2(final Path tmp) throws Exception {
            root = tmp.resolve(".m2");
            Files.createDirectories(root);
        }

        private Path settings() {
            return root.resolve("settings.xml");
        }

        private Path security() {
            return root.resolve("settings-security4.xml");
        }

        private void writeSettings(final String master, final String centralPassword, final String extraServers)
                throws Exception {
            final var masterFile = root.resolve("master.txt");
            Files.writeString(masterFile, master);
            Files.writeString(security(), """
                    <settingsSecurity>
                      <defaultDispatcher>master</defaultDispatcher>
                      <configurations>
                        <configuration>
                          <name>master</name>
                          <properties>
                            <property>
                              <name>source</name>
                              <value>%s</value>
                            </property>
                            <property>
                              <name>cipher</name>
                              <value>AES/GCM/NoPadding</value>
                            </property>
                          </properties>
                        </configuration>
                      </configurations>
                    </settingsSecurity>
                    """.formatted(masterFile.toAbsolutePath()));
            Files.writeString(settings(), """
                    <settings>
                      <servers>
                        <server>
                          <id>central</id>
                          <username>u</username>
                          <password>%s</password>
                        </server>
                        %s
                      </servers>
                    </settings>
                    """.formatted(centralPassword, extraServers));
        }
    }
}
