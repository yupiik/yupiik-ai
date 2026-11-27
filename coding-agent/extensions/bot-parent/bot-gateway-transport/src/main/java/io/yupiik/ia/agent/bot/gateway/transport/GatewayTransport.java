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
package io.yupiik.ia.agent.bot.gateway.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/** Shared fixed-target transport mechanisms. Callers retain routing and authorization policy. */
public final class GatewayTransport {
    private GatewayTransport() {}

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    private static final Pattern FIXED_PATH = Pattern.compile("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+");

    public static InputStream bounded(final InputStream input, final long maximum) {
        return new FilterInputStream(input) {
            private long count;

            @Override
            public int read() throws IOException {
                final int value = super.read();
                if (value >= 0 && ++count > maximum) {
                    throw new IOException("Limit exceeded");
                }
                return value;
            }

            @Override
            public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                final int read = in.read(bytes, offset, (int) Math.min(length, maximum - count + 1));
                if (read > 0 && (count += read) > maximum) {
                    throw new IOException("Limit exceeded");
                }
                return read;
            }
        };
    }

    public static void fail(final HttpExchange exchange, final int status) {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(status, -1);
        } catch (final IOException | RuntimeException ignored) {
            // Connection may already be closed by its deadline.
        } finally {
            exchange.close();
        }
    }

    public static String secret(final Configuration configuration, final String name) {
        final var value = configuration.get(name).orElse(null);
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid services credential");
        }
        return value;
    }

    public static SSLContext tls(final String keyStore, final String serverName, final String password)
            throws IOException {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Missing services keystore credential");
        }
        final var chars = password.toCharArray();
        try (final var input = Files.newInputStream(Path.of(keyStore))) {
            final var store = KeyStore.getInstance("PKCS12");
            store.load(input, chars);
            int keys = 0;
            for (final var aliases = store.aliases(); aliases.hasMoreElements(); ) {
                final var alias = aliases.nextElement();
                if (store.isKeyEntry(alias)) {
                    keys++;
                    final var cert = (X509Certificate) store.getCertificate(alias);
                    cert.checkValidity();
                    if (cert.getBasicConstraints() >= 0
                            || cert.getSubjectAlternativeNames() == null
                            || cert.getSubjectAlternativeNames().stream()
                                    .noneMatch(san ->
                                            Integer.valueOf(2).equals(san.get(0)) && serverName.equals(san.get(1)))) {
                        throw new GeneralSecurityException();
                    }
                }
            }
            if (keys != 1) {
                throw new GeneralSecurityException();
            }
            final var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, chars);
            final var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            final var context = SSLContext.getInstance("TLS");
            context.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
            return context;
        } catch (final GeneralSecurityException invalid) {
            throw new IOException("Invalid services TLS configuration");
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    public static URI target(final String value, final boolean origin) {
        try {
            final var uri = URI.create(value);
            final var host = uri.getHost();
            if (!"http".equals(uri.getScheme())
                    || host == null
                    || !IPV4.matcher(host).matches()
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() < 1
                    || uri.getPort() > 65535
                    || (origin
                            ? !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                            : !FIXED_PATH.matcher(uri.getRawPath()).matches())) {
                throw new IllegalArgumentException();
            }
            final var parts = host.split("\\.");
            final var octets = new int[4];
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255 || !Integer.toString(octets[i]).equals(parts[i])) {
                    throw new IllegalArgumentException();
                }
            }
            if (!(octets[0] == 127
                    || octets[0] == 10
                    || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                    || (octets[0] == 192 && octets[1] == 168))) {
                throw new IllegalArgumentException();
            }
            for (final var segment : uri.getRawPath().split("/")) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException();
                }
            }
            return uri;
        } catch (final RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid trusted services target");
        }
    }

    public static HttpsConfigurator configurator(final SSLContext tls, final String serverName) {
        return new HttpsConfigurator(tls) {
            @Override
            public void configure(final HttpsParameters parameters) {
                final var ssl = tls.getDefaultSSLParameters();
                ssl.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                ssl.setSNIMatchers(List.of(new SNIMatcher(0) {
                    @Override
                    public boolean matches(final SNIServerName name) {
                        return name instanceof SNIHostName host && serverName.equals(host.getAsciiName());
                    }
                }));
                parameters.setSSLParameters(ssl);
            }
        };
    }

    public static HttpClient client(final ExecutorService executor, final int timeoutSeconds) {
        return HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override
                    public List<Proxy> select(final URI uri) {
                        return List.of(Proxy.NO_PROXY);
                    }

                    @Override
                    public void connectFailed(final URI uri, final SocketAddress address, final IOException failure) {
                        // No destination or credential diagnostics.
                    }
                })
                .build();
    }

    public static void check(final SSLContext context, final String serverName, final int port) throws IOException {
        try (final var plain = new Socket()) {
            plain.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            try (final var socket =
                    (SSLSocket) context.getSocketFactory().createSocket(plain, serverName, port, true)) {
                socket.setSoTimeout(2000);
                final var parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new SNIHostName(serverName)));
                socket.setSSLParameters(parameters);
                socket.startHandshake();
            }
        }
    }
}
