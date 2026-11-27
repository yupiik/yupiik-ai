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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class GradleOutputProcessor implements OutputProcessor {
    private static final Pattern TASK = Pattern.compile("> Task (:[^ ]+)");
    private final LogNoiseOutputProcessor logNoise;
    private final CommandOutputPostProcessor commandOutput;

    public GradleOutputProcessor(
            final LogNoiseOutputProcessor logNoise, final CommandOutputPostProcessor commandOutput) {
        this.logNoise = logNoise;
        this.commandOutput = commandOutput;
    }

    public GradleOutputProcessor(final LogNoiseOutputProcessor logNoise) {
        this(logNoise, new CommandOutputPostProcessor());
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHead(command, "gradle", "gradlew");
    }

    @Override
    public int order() {
        return 11;
    }

    @Override
    public Set<String> names() {
        return Set.of("gradle", "gradlew");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        if (commandOutput.containsFlag(command, "--info", "--debug", "--stacktrace", "--scan")) {
            return lines;
        }
        final var all = lines.toArray(String[]::new);
        final var failures = new ArrayList<String>();
        final var warnings = new ArrayList<String>();
        String lastTask = null;
        String buildResult = null;
        var inFailure = false;

        for (final var raw : all) {
            final var line = commandOutput.stripAnsi(raw);
            if (logNoise.drop(line) || line.startsWith("Download ")) {
                continue;
            }
            if (line.startsWith("BUILD SUCCESSFUL")) {
                buildResult = line.strip();
                inFailure = false;
                continue;
            }
            if (line.startsWith("BUILD FAILED")
                    || line.startsWith("FAILURE:")
                    || line.startsWith("* What went wrong:")) {
                buildResult = line.strip();
                inFailure = true;
                continue;
            }
            if (line.startsWith("> Task ")) {
                lastTask = line.strip();
                inFailure = line.contains("FAILED");
                if (inFailure) {
                    failures.add(lastTask);
                }
                continue;
            }
            if (inFailure) {
                if (line.isBlank()
                        && !failures.isEmpty()
                        && failures.get(failures.size() - 1).isBlank()) {
                    inFailure = false;
                    continue;
                }
                failures.add(line.strip());
                continue;
            }
            if (line.startsWith("Deprecated ") || isWarningLine(line)) {
                if (warnings.size() < 10) {
                    warnings.add(line.strip());
                }
            }
        }

        final var out = new ArrayList<String>();
        if (buildResult != null) {
            out.add(buildResult);
        }
        if (!failures.isEmpty()) {
            final var cap = Math.min(failures.size(), 30);
            for (var i = 0; i < cap; i++) {
                out.add(failures.get(i));
            }
            if (failures.size() > cap) {
                out.add("... +" + (failures.size() - cap) + " more failure lines");
            }
        }
        if (!warnings.isEmpty()) {
            out.add("Warnings (" + warnings.size() + "):");
            out.addAll(warnings.stream().map(w -> "  " + w).toList());
        }
        if (lastTask != null) {
            out.add("Last task: " + lastTask);
        }
        final var passing = new LinkedHashSet<String>();
        for (final var raw : all) {
            final var line = commandOutput.stripAnsi(raw);
            final var m = TASK.matcher(line);
            if (m.find()
                    && !line.contains("FAILED")
                    && !line.contains("FROM-CACHE")
                    && !line.contains("NO-SOURCE")
                    && !line.contains("UP-TO-DATE")) {
                passing.add(m.group(1));
            }
        }
        if (!passing.isEmpty()) {
            out.add("Passing tasks (" + passing.size() + "): " + String.join(", ", passing));
        }
        return out.stream();
    }

    private boolean isWarningLine(final String line) {
        return line.startsWith("w:")
                || line.contains(": warning")
                || line.contains("warning:")
                || line.contains("Note:");
    }
}
