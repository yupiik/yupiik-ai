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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.bot.runtime.configuration.ExecutorConfiguration;
import io.yupiik.ia.agent.bot.runtime.model.AgentConfigurationDocument;
import io.yupiik.ia.agent.github.configuration.PromptConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import io.yupiik.ia.agent.github.configuration.WorkerOptions.SourceMode;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.model.TaskKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a fresh trusted configuration; repository-local settings and host integrations never enter it. */
@ApplicationScoped
public class AttemptPreparation {
    private final JsonMapper json;

    private final BotTemplates templates;
    private final boolean dependencyDownloadsEnabled;
    private final List<String> allowedDownloadHosts;
    /** Fusion lazy proxy constructor; the contextual instance uses the injected constructor below. */
    protected AttemptPreparation() {
        json = null;
        templates = null;
        dependencyDownloadsEnabled = false;
        allowedDownloadHosts = List.of();
    }

    public AttemptPreparation(
            final JsonMapper json, final PromptConfiguration prompts, final ExecutorConfiguration executor) {
        this.json = json;
        try {
            this.templates = new BotTemplates(prompts.sources());
        } catch (final IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid github.prompts configuration: " + invalid.getMessage());
        }
        final var docker = executor.docker();
        final var egress = docker == null
                ? null
                : docker.services() != null ? docker.services().dependencyEgress() : docker.egress();
        dependencyDownloadsEnabled = egress != null
                && egress.enabled()
                && egress.allowedHosts() != null
                && !egress.allowedHosts().isEmpty();
        allowedDownloadHosts = dependencyDownloadsEnabled ? List.copyOf(egress.allowedHosts()) : List.of();
    }

    public Path prepare(final Path configuration) throws IOException {
        return prepare(configuration, null);
    }

    public Path prepare(final Path configuration, final Path attemptRoot) throws IOException {
        return new io.yupiik.ia.agent.bot.runtime.AttemptPreparation(json)
                .prepare(configuration, attemptRoot, "yca-github-");
    }

    @JsonModel
    public record UserRequest(
            @Property(documentation = "Untrusted invocation text encoded as prompt data.")
            String request) {}

    public static void delete(final Path directory) throws IOException {
        io.yupiik.ia.agent.bot.runtime.AttemptPreparation.delete(directory);
    }

    public String prompt(final TaskDocument task, final String forkOwner) {
        return prompt(task, forkOwner, true);
    }

    public String prompt(final TaskDocument task, final String forkOwner, final boolean agentCommenting) {
        return prompt(task, forkOwner, agentCommenting, SourceMode.export);
    }

    public String prompt(
            final TaskDocument task,
            final String forkOwner,
            final boolean agentCommenting,
            final SourceMode sourceMode) {
        if (sourceMode == null) {
            throw new IllegalArgumentException("Source mode is required");
        }
        final boolean broker = sourceMode == SourceMode.broker;
        final boolean git = sourceMode == SourceMode.git;
        final boolean fix = task.kind() == TaskKind.fix;
        final var reviewTarget = task.reviewTarget();
        final boolean assessment =
                task.kind() == TaskKind.review && ("issue".equals(reviewTarget) || "discussion".equals(reviewTarget));
        if ((broker || fix) && (agentCommenting || (fix && git))) {
            throw new IllegalArgumentException(
                    "Brokered context and PR fixes require credential-free strict execution");
        }
        if (git && (agentCommenting || task.kind() != TaskKind.solve)) {
            throw new IllegalArgumentException("Git source mode requires a strict solve task");
        }
        final var model = new LinkedHashMap<String, Object>();
        model.put("agentCommenting", agentCommenting);
        model.put("broker", broker);
        model.put("gitSourceMode", git);
        model.put("exportSourceMode", sourceMode == SourceMode.export);
        model.put("review", task.kind() == TaskKind.review);
        model.put("reviewTarget", reviewTarget == null ? "" : reviewTarget);
        model.put("assessment", assessment);
        model.put("issueReview", assessment && "issue".equals(reviewTarget));
        model.put("discussionReview", assessment && "discussion".equals(reviewTarget));
        model.put("pullRequestReview", task.kind() == TaskKind.review && "pull_request".equals(reviewTarget));
        model.put("solve", task.kind() == TaskKind.solve);
        model.put("fix", fix);
        model.put("taskId", task.id());
        model.put("taskIdJson", json.toString(task.id()));
        model.put("forkOwner", forkOwner == null ? "" : forkOwner);
        model.put("dependencyDownloadsEnabled", dependencyDownloadsEnabled);
        model.put("allowedDownloadHosts", allowedDownloadHosts);
        model.put("allowedDownloadHostsJson", json.toString(allowedDownloadHosts));
        final var identity = new LinkedHashMap<String, Object>();
        identity.put("id", task.id());
        identity.put("attempts", task.attempts());
        identity.put("repository", task.repository());
        identity.put("number", task.number());
        identity.put("kind", task.kind().name());
        identity.put("reviewTarget", reviewTarget);
        model.put("taskJson", json.toString(identity));
        model.put(
                "referenceJson",
                json.toString("https://github.com/" + task.repository()
                        + ("discussion".equals(reviewTarget) || "discussion".equals(task.sourceType())
                                ? "/discussions/"
                                : "pull_request".equals(reviewTarget) || task.kind() == TaskKind.fix
                                        ? "/pull/"
                                        : "/issues/")
                        + task.number()));
        model.put("requestJson", json.toString(new UserRequest(task.request() == null ? "" : task.request())));
        // Each component sees only the explicit runtime model; rendered components are data to main.
        final var components = new LinkedHashMap<String, Object>(model);
        for (final var name : List.of("commonPolicy", "headings", "toolchain", "resultContract", "taskEnvelope")) {
            components.put(name, templates.render(name, model));
        }
        components.put("source", templates.render(broker ? "brokerSource" : git ? "gitSource" : "exportSource", model));
        components.put(
                "publication", templates.render(agentCommenting ? "agentPublication" : "strictPublication", model));
        components.put("taskKind", templates.render(task.kind().name(), model));
        return templates.render("main", components);
    }

    /** Stages only nonsecret connection metadata and a launcher for the packaged Java helper. */
    public void stageBroker(
            final Path attempt,
            final String endpoint,
            final String repository,
            final Map<String, Object> metadata,
            final WorkerOptions.Source source,
            final Path nonoRuntime)
            throws IOException {
        final var file = attempt.resolve("configuration.json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) {
            throw new IOException("Expected bounded trusted configuration before broker launch");
        }
        final var document = json.fromString(AgentConfigurationDocument.class, Files.readString(file));
        final var configuration = document.configuration();
        configuration.put(
                "githubContext",
                Map.of(
                        "enabled",
                        true,
                        "endpoint",
                        endpoint,
                        "tokenEnv",
                        "YCA_CONTEXT_TOKEN",
                        "gitTokenEnv",
                        "YCA_CONTEXT_GIT_TOKEN",
                        "primaryRepository",
                        repository));
        Files.writeString(
                file,
                json.toString(new AgentConfigurationDocument(configuration, document.completionSettings())),
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
        final String java;
        final String classpath;
        if (nonoRuntime == null) {
            java = "java";
            classpath = "@/app/jib-classpath-file";
        } else {
            final var executable = nonoRuntime.resolve("jdk/bin/java");
            final var jar = nonoRuntime.resolve("lib/github-bot.jar");
            if (!Files.isExecutable(executable) || !Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "Broker Nono runtime requires jdk/bin/java and lib/github-bot.jar including the packaged context helper");
            }
            java = executable.toString();
            classpath = jar.toString();
        }
        final var script = "#!/bin/sh\nset -eu\nexec " + quote(java) + " -cp " + quote(classpath)
                + " io.yupiik.ia.agent.github.context.agent.AgentBrokerSource"
                + " --endpoint " + quote(endpoint) + " --repository " + quote(repository)
                + " --head " + quote(String.valueOf(metadata.get("headSha")))
                + " --max-depth " + source.maxDepth() + " --max-bytes " + source.maxBytes()
                + " --timeout " + source.commandTimeoutSeconds()
                + (nonoRuntime == null
                        ? ""
                        : " --git " + quote(nonoRuntime.resolve("bin/git").toString()))
                + " \"$@\"\n";
        Files.writeString(
                attempt.resolve("workspace/github-source-broker.sh"),
                script,
                StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS);
        Files.writeString(
                attempt.resolve("workspace/github-context.json"),
                json.toString(metadata),
                StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
