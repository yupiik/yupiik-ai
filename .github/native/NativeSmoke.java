/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Provider-free smoke checks, executable with the Java source-file launcher. */
public class NativeSmoke {
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected native executable and isolated smoke directory");
        }
        final var binary = Path.of(args[0]).toRealPath();
        final var home = Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        Files.writeString(home.resolve("configuration.json"), "{}");
        run(binary, home, "completion", List.of("completion", "bash"), "");
        if (Files.size(home.resolve("completion.stdout.log")) == 0) {
            throw new IllegalStateException("CLI completion output is empty");
        }
        run(binary, home, "acp", List.of("acp"), """
                {"jsonrpc":"2.0","id":9007199254740993,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}
                """);
        final var acp = Files.readString(home.resolve("acp.stdout.log")).strip();
        if (acp.lines().count() != 1 ||
                !Pattern.compile("\"id\"\\s*:\\s*9007199254740993(?=\\s*[,}])").matcher(acp).find() ||
                !Pattern.compile("\"protocolVersion\"\\s*:\\s*1(?=\\s*[,}])").matcher(acp).find() ||
                acp.contains("\"error\"")) {
            throw new IllegalStateException("ACP initialize did not preserve the numeric id and protocol: " + acp);
        }
        final var web = process(binary, home, "web", List.of(
                "web", "--port", "0", "--host", "127.0.0.1", "--openBrowser", "false")).start();
        try {
            web.getOutputStream().close();
            final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            String url = null;
            while (url == null && System.nanoTime() < deadline) {
                final var banner = Files.readString(home.resolve("web.stdout.log"));
                url = banner.lines().filter(line -> line.startsWith("web UI listening on "))
                        .map(line -> line.substring("web UI listening on ".length())).findFirst().orElse(null);
                if (url == null) {
                    if (!web.isAlive()) {
                        throw new IllegalStateException("Web process exited: " + Files.readString(home.resolve("web.stderr.log")));
                    }
                    Thread.sleep(100);
                }
            }
            if (url == null) {
                throw new IllegalStateException("Web startup timed out");
            }
            final var uri = URI.create(url);
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() <= 0) {
                throw new IllegalStateException("Expected an actual loopback HTTP listener: " + url);
            }
            try (final var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                final var ui = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (ui.statusCode() != 200 || !ui.body().toLowerCase(Locale.ROOT).contains("<html")) {
                    throw new IllegalStateException("Bundled web UI was not served: HTTP " + ui.statusCode());
                }
                final var rpc = client.send(HttpRequest.newBuilder(uri.resolve("api/rpc"))
                                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"sessions.list\",\"params\":{}}"))
                                .build(), HttpResponse.BodyHandlers.ofString());
                if (rpc.statusCode() != 200 || !rpc.body().contains("\"result\"") || rpc.body().contains("\"error\"")) {
                    throw new IllegalStateException("Web JSON-RPC failed: HTTP " + rpc.statusCode() + ": " + rpc.body());
                }
            }
        } finally {
            stop(web);
        }
        System.out.println("Native CLI, ACP large-id/EOF, bundled web UI and JSON-RPC smoke checks passed without provider calls.");
    }

    private static void run(final Path binary, final Path home, final String label,
                            final List<String> arguments, final String input) throws Exception {
        final var process = process(binary, home, label, arguments).start();
        try {
            try (final var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(label + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(label + " failed: " + Files.readString(home.resolve(label + ".stderr.log")));
            }
        } finally {
            stop(process);
        }
    }

    private static ProcessBuilder process(final Path binary, final Path home, final String label,
                                          final List<String> arguments) {
        final var command = new ArrayList<String>();
        command.add(binary.toString());
        command.addAll(arguments);
        final var builder = new ProcessBuilder(command).directory(home.toFile())
                .redirectOutput(home.resolve(label + ".stdout.log").toFile())
                .redirectError(home.resolve(label + ".stderr.log").toFile());
        builder.environment().put("YCA_HOME", home.toString());
        builder.environment().put("YCA_STORAGEHOME", home.resolve("state").toString());
        builder.environment().put("YCA_CONFIGURATION_JSON", home.resolve("configuration.json").toString());
        return builder;
    }

    private static void stop(final Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Native process did not terminate");
                }
            }
        }
    }
}
