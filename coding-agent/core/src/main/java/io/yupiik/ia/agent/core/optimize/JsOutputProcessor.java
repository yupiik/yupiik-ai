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
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compacts JavaScript/TypeScript tooling output: {@code tsc} and {@code biome} errors and
 * {@code eslint}/{@code next build} violations are grouped per file (then per rule), keeping
 * only the failing paths and their messages. Mirrors the rtk tsc/eslint/next compaction.
 *
 * <p>tsc emits {@code path(line,col): error TS1234: message} on stderr; eslint emits
 * {@code path:line:col  error  message  rule} on stdout (or JSON with {@code -f json}); next
 * emits {@code Error: ...} and {@code ./path:line:col} stacks. All three are grouped per file.</p>
 */
@ApplicationScoped
public class JsOutputProcessor implements OutputProcessor {
    private static final Pattern TSC_LINE =
            Pattern.compile("^(.*)\\((\\d+),(\\d+)\\):\\s+(error|warning)\\s+(TS\\d+):\\s+(.*)$");
    private static final Pattern ESLINT_LINE =
            Pattern.compile("^(.*):(\\d+):(\\d+)\\s+(error|warning)\\s+(.*)\\s+([\\w@/.-]+)$");
    private static final Pattern NEXT_ERROR = Pattern.compile("^(\\.?/?[^: ]+):(\\d+):(\\d+):?\\s+(.*)$");

    private final CodingAgentConfiguration configuration;
    private final CommandOutputPostProcessor commandOutput;

    public JsOutputProcessor(
            final CodingAgentConfiguration configuration, final CommandOutputPostProcessor commandOutput) {
        this.configuration = configuration;
        this.commandOutput = commandOutput;
    }

    public JsOutputProcessor(final CodingAgentConfiguration configuration) {
        this(configuration, new CommandOutputPostProcessor());
    }

    protected JsOutputProcessor() {
        this.configuration = null;
        this.commandOutput = new CommandOutputPostProcessor();
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHeadToken(command, "tsc", "eslint", "biome", "next");
    }

    @Override
    public int order() {
        return 17;
    }

    @Override
    public Set<String> names() {
        return Set.of("tsc", "eslint", "biome", "next");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var out = new ArrayList<String>();
        final var perFile = new LinkedHashMap<String, List<String>>();
        String lastFile = null;
        for (final var raw : lines.toList()) {
            final var line = commandOutput.stripAnsi(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            final var tsc = TSC_LINE.matcher(line);
            if (tsc.matches()) {
                final var file = tsc.group(1);
                lastFile = file;
                perFile.computeIfAbsent(file, k -> new ArrayList<>())
                        .add("L" + tsc.group(2) + ":" + tsc.group(3) + " " + tsc.group(4) + " " + tsc.group(5) + ": "
                                + tsc.group(6));
                continue;
            }
            final var eslint = ESLINT_LINE.matcher(line);
            if (eslint.matches()) {
                final var file = eslint.group(1);
                lastFile = file;
                perFile.computeIfAbsent(file, k -> new ArrayList<>())
                        .add("L" + eslint.group(2) + ":" + eslint.group(3) + " " + eslint.group(4) + " "
                                + eslint.group(5) + " [" + eslint.group(6) + "]");
                continue;
            }
            final var next = NEXT_ERROR.matcher(line);
            if (next.matches() && line.contains("Error")) {
                final var file = next.group(1);
                lastFile = file;
                perFile.computeIfAbsent(file, k -> new ArrayList<>())
                        .add("L" + next.group(2) + ":" + next.group(3) + " " + next.group(4));
                continue;
            }
            if (startsWith(line, "error TS", "Error:", "✖", "×", "Found ")) {
                out.add(line); // summary/headline lines stay at the top
            }
        }
        if (lastFile != null) {
            // eslint run finished with counts, keep the trailing summary line
            out.removeIf(l -> l.startsWith("Found ") && perFile.isEmpty());
        }
        var total = 0;
        for (final var e : perFile.entrySet()) {
            if (total >= maxEntries()) {
                out.add("... +" + e.getValue().size() + " more in " + e.getKey());
                break;
            }
            out.add(e.getKey() + " (" + e.getValue().size() + "):");
            var count = 0;
            for (final var entry : e.getValue()) {
                if (total + count >= maxEntries()) {
                    out.add("  ... +" + (e.getValue().size() - count) + " more");
                    break;
                }
                out.add("  " + entry);
                count++;
            }
            total += count;
        }
        return out.stream();
    }

    private boolean startsWith(final String line, final String... prefixes) {
        for (final var p : prefixes) {
            if (line.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private int maxEntries() {
        return configuration == null || configuration.optimize() == null
                ? 20
                : Math.max(1, configuration.optimize().maxEntries());
    }
}
