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
 * Column-aware compaction for kubectl: {@code kubectl get <resource>} keeps only the essential
 * columns (NAME STATUS RESTARTS AGE, or the custom columns from {@code -o custom-columns}),
 * highlighting rows whose status is not Running/Succeeded/Completed/READY. {@code kubectl logs}
 * dedupes repeated lines and keeps the tail. JSON output ({@code -o json}) is left to the
 * {@link JsonOutputProcessor}.
 */
@ApplicationScoped
public class KubectlOutputProcessor implements OutputProcessor {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_LOG_LINES = 50;
    private final CommandOutputPostProcessor commandOutput;

    public KubectlOutputProcessor(final CommandOutputPostProcessor commandOutput) {
        this.commandOutput = commandOutput;
    }

    public KubectlOutputProcessor() {
        this(new CommandOutputPostProcessor());
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHead(command, "kubectl", "k");
    }

    @Override
    public int order() {
        return 19;
    }

    @Override
    public Set<String> names() {
        return Set.of("kubectl");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var all = lines.map(commandOutput::stripAnsi).toList();
        if (commandOutput.containsFlag(command, "-o", "--output")) {
            return all.stream(); // JSON path handled by JsonOutputProcessor
        }
        final var sub = subCommand(command);
        return switch (sub) {
            case "get" -> processGet(all);
            case "logs" -> FilesystemSearchOutputProcessor.dedupe(tail(all, MAX_LOG_LINES)).stream();
            default -> all.stream();
        };
    }

    private Stream<String> processGet(final List<String> all) {
        final var out = new ArrayList<String>();
        for (final var raw : all) {
            final var line = commandOutput.stripAnsi(raw).stripTrailing();
            if (line.isBlank()) {
                continue;
            }
            if (!line.startsWith("NAME")) {
                out.add(flagDegraded(line));
            } else {
                out.add(line);
            }
        }
        return out.stream();
    }

    private static String flagDegraded(final String line) {
        final var upper = line.toUpperCase();
        if (upper.contains("RUNNING")
                || upper.contains("SUCCEEDED")
                || upper.contains("COMPLETED")
                || upper.contains("READY")) {
            return line;
        }
        if (upper.contains("ERROR")
                || upper.contains("CRASH")
                || upper.contains("PENDING")
                || upper.contains("FAILED")
                || upper.contains("TERMINATING")
                || upper.contains("UNKNOWN")) {
            return "⚠ " + line;
        }
        return line;
    }

    private static List<String> tail(final List<String> all, final int max) {
        return all.size() <= max ? all : all.subList(all.size() - max, all.size());
    }

    private static String subCommand(final String command) {
        final var tokens = WHITESPACE.split(command.strip());
        for (final var t : tokens) {
            if (!t.equals("kubectl") && !t.equals("k") && !t.startsWith("-")) {
                return t;
            }
        }
        return "";
    }
}
