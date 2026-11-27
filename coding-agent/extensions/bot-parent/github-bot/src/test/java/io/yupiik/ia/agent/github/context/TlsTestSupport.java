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
package io.yupiik.ia.agent.github.context;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.support.TestJava;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Files owned by the calling test class's @TempDir, read-only after construction. */
final class TlsTestSupport {
    private TlsTestSupport() {}

    static Material create(final Path directory, final String password) throws Exception {
        Files.createDirectories(directory);
        final var store = directory.resolve("tls.p12");
        final var certificate = directory.resolve("tls.pem");
        final var log = directory.resolve("keytool.log");
        final var process = new ProcessBuilder(TestJava.keytoolCommand(
                        "-genkeypair",
                        "-alias",
                        "localhost",
                        "-keyalg",
                        "EC",
                        "-groupname",
                        "secp256r1",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost,ip:127.0.0.1",
                        "-validity",
                        "2",
                        "-storetype",
                        "PKCS12",
                        "-keystore",
                        store.toString(),
                        "-storepass",
                        password,
                        "-noprompt"))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Certificate generation exceeded fixture deadline");
            assertEquals(0, process.exitValue(), () -> "Certificate generation failed; see " + log);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        final var keys = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(store)) {
            keys.load(input, password.toCharArray());
        }
        // Export the same self-signed leaf for native Git without another keytool JVM.
        Files.writeString(
                certificate,
                "-----BEGIN CERTIFICATE-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(keys.getCertificate("localhost").getEncoded())
                        + "\n-----END CERTIFICATE-----\n",
                StandardCharsets.US_ASCII);
        return new Material(store, certificate);
    }

    record Material(Path store, Path certificate) {}
}
