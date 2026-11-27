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
package io.yupiik.ia.agent.documentation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Offline source checks. From the repository root:
 * java documentation/src/test/java/io/yupiik/ia/agent/documentation/ValidateBotSamples.java
 */
public class ValidateBotSamples {
    private static final Pattern REFERENCES = Pattern.compile("xref:([^\\s\\[]+)\\[");
    private static final Pattern BREADCRUMBS = Pattern.compile("\\[([^] ]+\\.html)]");
    private static final Pattern PROVIDER_TARGET =
            Pattern.compile("(?m)^SLACK_EXECUTOR_DOCKER_SERVICES_PROVIDERTARGET=(.+)$");
    private static final Pattern EXPLICIT_ANCHOR = Pattern.compile("\\[\\[([^],]+)(?:,[^]]*)?]]|\\[#([^]]+)]");
    private static final Pattern HEADING = Pattern.compile("={1,6} .+");
    private static final Pattern HEADING_PREFIX = Pattern.compile("^=+ +");
    private static final Pattern ANCHOR_PUNCTUATION = Pattern.compile("[^a-z0-9 _-]");
    private static final Pattern ANCHOR_SEPARATOR = Pattern.compile("[ _-]+");

    public static void main(final String[] args) throws Exception {
        new ValidateBotSamples().validate();
    }

    private void validate() throws Exception {
        final var content = Path.of("documentation/src/main/minisite/content/coding-agent");
        final var slack = Path.of("coding-agent/extensions/bot-parent/slack-bot/src/main");
        int links = 0;
        for (final var relative : List.of(
                "slack-bot/index.adoc",
                "slack-bot/configuration.adoc",
                "slack-bot/deployment.adoc",
                "slack-bot/installation.adoc",
                "slack-bot/docker.adoc",
                "slack-bot/context.adoc",
                "github-bot/index.adoc",
                "github-bot/docker.adoc",
                "bots/cohosting.adoc",
                "bots/docker-shared-slots.adoc")) {
            final var page = content.resolve(relative);
            final var source = Files.readString(page);
            final var matcher = REFERENCES.matcher(source);
            while (matcher.find()) {
                final var parts = matcher.group(1).split("#", 2);
                final var target = page.getParent().resolve(parts[0]).normalize();
                require(Files.isRegularFile(target), page + ": missing " + target);
                if (parts.length == 2) {
                    require(
                            anchors(Files.readString(target)).contains(parts[1]),
                            page + ": missing anchor " + matcher.group(1));
                }
                links++;
            }
            final var breadcrumb = BREADCRUMBS.matcher(source.lines()
                    .filter(line -> line.startsWith(":minisite-breadcrumb:"))
                    .findFirst()
                    .orElse(""));
            while (breadcrumb.find()) {
                final var target = page.getParent()
                        .resolve(breadcrumb.group(1).replace(".html", ".adoc"))
                        .normalize();
                // The minisite generates the root index from page metadata.
                require(
                        target.equals(content.resolve("index.adoc")) || Files.isRegularFile(target),
                        page + ": missing breadcrumb " + target);
                links++;
            }
        }

        final var deployment = Files.readString(content.resolve("slack-bot/deployment.adoc"));
        final var context = Files.readString(content.resolve("slack-bot/context.adoc"));
        final var searchManifest = Files.readString(slack.resolve("deployment/slack-app-search-manifest.yaml"));
        require(context.contains("slack-app-search-manifest.yaml"), "Missing opt-in search manifest documentation");
        for (final var scope : List.of("app_mentions:read", "chat:write", "users:read", "search:read.public")) {
            require(searchManifest.contains("      - " + scope + "\n"), "Missing opt-in bot scope: " + scope);
        }
        require(
                !searchManifest.contains("search:read.private") && !searchManifest.contains("search:read.im"),
                "Search sample must not enable private/DM access");
        final var minimalManifest = Files.readString(slack.resolve("resources/slack-app-manifest.json"));
        require(
                !minimalManifest.contains("search:read") && !minimalManifest.contains("users:read"),
                "Minimal manifest must remain minimal");
        for (final var relative : List.of(
                "deployment/controller.env.example",
                "deployment/agent-restricted-services.json.example",
                "docker/controller.sh.example",
                "docker/Dockerfile.agent-ca.example",
                "deployment/yca-slack-controller.service.example",
                "deployment/uninstall.sh.example",
                "resources/slack-app-manifest.json")) {
            require(Files.isRegularFile(slack.resolve(relative)), "Missing sample " + relative);
            require(
                    deployment.contains(slack.resolve(relative).getFileName().toString()),
                    "Undocumented sample " + relative);
        }
        require(deployment.contains("[mermaid]\n....\n"), "Unsupported diagram block");
        require(
                deployment.contains("include::content/coding-agent/_partials/mermaid.js.adoc[]"),
                "Missing diagram loader");
        require(Files.isRegularFile(content.resolve("_partials/mermaid.js.adoc")), "Missing local diagram loader");

        final var environment = Files.readString(slack.resolve("deployment/controller.env.example"));
        final var wrapper = Files.readString(slack.resolve("docker/controller.sh.example"));
        final var unit = Files.readString(slack.resolve("deployment/yca-slack-controller.service.example"));
        final var agent = Files.readString(slack.resolve("deployment/agent-restricted-services.json.example"));
        require(
                environment.contains("SLACK_EXECUTOR_DOCKER_SERVICES_DEPENDENCYEGRESS_ENABLED=false\n"),
                "Inference-only sample must explicitly disable dependency egress");
        require(environment.contains("BOT_ADMISSION_SLOTS=1\n"), "Missing common host budget");
        require(environment.contains("BOT_ADMISSION_TRUSTEDGROUP=yca-bots\n"), "Missing trusted controller group");
        require(
                environment.contains("SLACK_EXECUTOR_DOCKER_SERVICES_KEYSTOREPASSWORDENV=" + "YCA_SERVICES_"
                        + "KEYSTORE_PASSWORD\n"),
                "Gateway password environment name must match wrapper");
        for (final var credential :
                List.of("slack-app-token", "slack-bot-token", "provider-api-key", "services-keystore-password")) {
            require(
                    unit.contains("LoadCredential=" + credential + ":/etc/yca-slack/secrets/" + credential + "\n"),
                    "Missing unit credential mapping: " + credential);
            require(
                    wrapper.contains("$CREDENTIALS_DIRECTORY/" + credential),
                    "Missing wrapper credential: " + credential);
        }
        for (final var name : List.of("OPENAI_API_KEY", "YCA_SERVICES_KEYSTORE_PASSWORD")) {
            require(wrapper.contains("--env " + name), "Missing explicit controller credential forwarding: " + name);
        }
        final var target = PROVIDER_TARGET.matcher(environment);
        require(target.find() && agent.contains("\"baseUrl\": \"" + target.group(1) + "\""), "Provider targets differ");
        require(agent.contains("\"yca\": {"), "Sample must use the supported yca wrapper");
        require(agent.contains("\"api\": \"COMPLETIONS\""), "Unsupported provider API");
        require(!agent.contains("\"apiKey\""), "Sample must not supply a permanent provider key");
        for (final var feature : List.of("memory", "skills", "subagents", "mcp", "notifications")) {
            require(agent.contains("\"" + feature + "\": { \"enabled\": false }"), "Feature not disabled: " + feature);
        }
        require(unit.contains("ExecStop=/opt/yca-slack/bin/docker-controller --stop\n"), "Missing owned graceful stop");
        for (final var setting : List.of(
                "Restart=no",
                "TimeoutStopSec=infinity",
                "SendSIGKILL=no",
                "KillMode=process",
                "SuccessExitStatus=143")) {
            require(unit.contains(setting + "\n"), "Unsafe lifecycle setting: " + setting);
        }
        require(wrapper.contains("--stop-timeout=-1"), "Docker's own stop deadline must also be infinite");
        validateWrapperStop(wrapper);
        System.out.println(
                "Validated " + links
                        + " local documentation links/anchors, restricted Slack deployment wiring and 7 mocked graceful-stop cases (offline).");
    }

    private void validateWrapperStop(final String wrapper) throws Exception {
        final var root = Files.createTempDirectory("slack-wrapper-check-");
        try {
            final var docker = root.resolve("docker");
            final var id = "a".repeat(64);
            Files.writeString(docker, """
                    #!/bin/sh
                    set -eu
                    printf '%s\\n' "$*" >> "$TRACE"
                    case "$1 $2" in
                      'container inspect')
                        case "$SCENARIO" in
                          owned|stop-failed) printf '%s|io.yupiik.yca.slack-bot|controller\\n' "$FAKE_ID" ;;
                          foreign) printf '%s|foreign|controller\\n' "$FAKE_ID" ;;
                          malformed) printf 'bad-id|io.yupiik.yca.slack-bot|controller\\n' ;;
                          absent|unreadable|daemon) exit 1 ;;
                          *) exit 90 ;;
                        esac ;;
                      'container ls')
                        case "$SCENARIO" in
                          absent) printf 'unrelated-controller\\n' ;;
                          unreadable) printf 'yca-slack-controller\\n' ;;
                          daemon) exit 2 ;;
                          *) exit 91 ;;
                        esac ;;
                      'stop --time')
                        if [ "$SCENARIO" = stop-failed ]; then
                          exit 7
                        fi
                        test "$3" = -1 && test "$4" = "$FAKE_ID" ;;
                      *) exit 92 ;;
                    esac
                    """);
            require(docker.toFile().setExecutable(true, true), "Cannot make fake Docker executable");
            final var copy = root.resolve("controller.sh");
            Files.writeString(
                    copy,
                    wrapper.replace("/usr/bin/docker", "'" + docker.toString().replace("'", "'\\''") + "'"));
            for (final var scenario :
                    List.of("owned", "foreign", "malformed", "absent", "unreadable", "daemon", "stop-failed")) {
                final var trace = root.resolve(scenario + ".trace");
                final var command = new ProcessBuilder("sh", copy.toString(), "--stop")
                        .redirectOutput(root.resolve(scenario + ".out").toFile())
                        .redirectError(root.resolve(scenario + ".err").toFile());
                // Stopping must not read any credential or require a live-service environment.
                command.environment().clear();
                command.environment().put("PATH", "/usr/bin:/bin");
                command.environment().put("SCENARIO", scenario);
                command.environment().put("FAKE_ID", id);
                command.environment().put("TRACE", trace.toString());
                final var process = command.start();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly().waitFor();
                    throw new IllegalStateException("Local fake Docker hung: " + scenario);
                }
                final var success = "owned".equals(scenario) || "absent".equals(scenario);
                require((process.exitValue() == 0) == success, "Unexpected stop outcome: " + scenario);
                final var calls = Files.readString(trace);
                require(
                        calls.contains("stop --time -1 " + id)
                                == ("owned".equals(scenario) || "stop-failed".equals(scenario)),
                        "Foreign/unverifiable container touched or owned stop skipped: " + scenario);
                require(!calls.contains("run "), "Stop must never launch the controller");
            }
        } finally {
            try (final var paths = Files.walk(root)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private HashSet<String> anchors(final String source) {
        final var result = new HashSet<String>();
        final var explicit = EXPLICIT_ANCHOR.matcher(source);
        while (explicit.find()) {
            result.add(explicit.group(1) == null ? explicit.group(2) : explicit.group(1));
        }
        source.lines().filter(line -> HEADING.matcher(line).matches()).forEach(line -> {
            final var title = HEADING_PREFIX.matcher(line).replaceFirst("").toLowerCase(Locale.ROOT);
            final var plain = ANCHOR_PUNCTUATION.matcher(title).replaceAll("");
            result.add("_" + ANCHOR_SEPARATOR.matcher(plain).replaceAll("_"));
        });
        return result;
    }

    private void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
