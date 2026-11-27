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
package io.yupiik.ia.agent.core.optimize;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Column-aware compaction for docker commands: {@code docker ps} keeps only name/image/status/ports,
 * {@code docker images} name/tag/id/size, {@code docker logs} the last N unique lines, and
 * {@code docker compose up} the service status lines. JSON output (docker inspect, or
 * {@code -o json}) is left to the {@link JsonOutputProcessor}.
 */
@ApplicationScoped
public class DockerOutputProcessor implements OutputProcessor {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern COLUMN_SEPARATOR = Pattern.compile("\\s{2,}");
    private static final int MAX_PS_LINES = 20;
    private static final int MAX_LOG_LINES = 50;
    private final CommandOutputPostProcessor commandOutput;

    public DockerOutputProcessor(final CommandOutputPostProcessor commandOutput) {
        this.commandOutput = commandOutput;
    }

    public DockerOutputProcessor() {
        this(new CommandOutputPostProcessor());
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHead(command, "docker");
    }

    @Override
    public int order() {
        return 18;
    }

    @Override
    public Set<String> names() {
        return Set.of("docker", "docker-compose", "docker compose");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var all = lines.map(commandOutput::stripAnsi).toList();
        if (commandOutput.containsFlag(command, "-o", "--output", "inspect", "ps --format")) {
            return all.stream(); // JSON or custom format: handled elsewhere / passthrough
        }
        final var sub = subCommand(command);
        return switch (sub) {
            case "ps" -> columns(all, 0, 1, 4, 6); // CONTAINER ID IMAGE COMMAND CREATED STATUS PORTS NAMES
            case "images" -> columns(all, 0, 1, 2, 6); // REPOSITORY TAG IMAGE ID CREATED SIZE
            case "logs" -> dedupeTail(all, MAX_LOG_LINES);
            case "compose" ->
                all.stream()
                        .filter(l -> l.contains("Started")
                                || l.contains("Running")
                                || l.contains("Recreated")
                                || l.contains("Error")
                                || l.contains("Creating")
                                || l.contains("Removing")
                                || l.contains("done"));
            default -> all.stream();
        };
    }

    private Stream<String> columns(final List<String> all, final int... keep) {
        final var out = new ArrayList<String>(all.size());
        var header = true;
        for (final var raw : all) {
            final var line = commandOutput.stripAnsi(raw).stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            final var cols = COLUMN_SEPARATOR.split(line);
            if (header) {
                header = false;
                out.add(pickHeader(cols, keep));
                continue;
            }
            out.add(pick(cols, keep));
            if (out.size() >= MAX_PS_LINES + 1) {
                out.add("... +" + (all.size() - out.size() + 1) + " more");
                break;
            }
        }
        return out.stream();
    }

    private String pickHeader(final String[] cols, final int[] keep) {
        final var out = new ArrayList<String>();
        for (final var k : keep) {
            if (k < cols.length) {
                out.add(commandOutput.stripAnsi(cols[k]));
            }
        }
        return String.join("  ", out);
    }

    private static String pick(final String[] cols, final int[] keep) {
        final var out = new ArrayList<String>();
        for (final var k : keep) {
            if (k < cols.length) {
                out.add(cols[k]);
            }
        }
        return String.join("  ", out);
    }

    private static Stream<String> dedupeTail(final List<String> all, final int max) {
        if (all.size() <= max) {
            return FilesystemSearchOutputProcessor.dedupe(all).stream();
        }
        return FilesystemSearchOutputProcessor.dedupe(all.subList(all.size() - max, all.size())).stream();
    }

    private static String subCommand(final String command) {
        final var tokens = WHITESPACE.split(command.strip());
        for (final var t : tokens) {
            if (!t.equals("docker") && !t.startsWith("-")) {
                return t;
            }
        }
        return "";
    }
}
