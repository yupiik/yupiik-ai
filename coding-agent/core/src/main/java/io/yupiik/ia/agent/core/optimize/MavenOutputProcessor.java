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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class MavenOutputProcessor implements OutputProcessor {
    private static final Pattern FRAMEWORK_STACK_FRAME =
            Pattern.compile("\\s+at (org\\.junit|java\\.base|org\\.apache\\.maven)[^\n]*");
    private static final Pattern TEST_SUMMARY = Pattern.compile("Tests run: \\d+, Failures: \\d+, Errors: \\d+.*");
    private static final Pattern INFORMATIONAL_OPTION = Pattern.compile("(?:^|\\s)(?:-v|--version|-h|--help)(?=\\s|$)");

    private final LogNoiseOutputProcessor logNoise;
    private final CommandOutputPostProcessor commandOutput;

    public MavenOutputProcessor(
            final LogNoiseOutputProcessor logNoise, final CommandOutputPostProcessor commandOutput) {
        this.logNoise = logNoise;
        this.commandOutput = commandOutput;
    }

    public MavenOutputProcessor(final LogNoiseOutputProcessor logNoise) {
        this(logNoise, new CommandOutputPostProcessor());
    }

    private static final Pattern RUNNING = Pattern.compile("\\[INFO] Running ([\\w.,\\s]+)$");
    private static final Pattern TESTS_RUN =
            Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)");
    private static final Pattern NOISE =
            Pattern.compile("^\\[INFO] ?(Scanning for projects|Detecting the operating system|Using the .* encoding|"
                    + "Downloading from|Downloaded from|Progress \\(|--- [\\w-]+:[\\w.-]+:[\\w.-]+|"
                    + "<<<|>>>|Caching disabled|Remote snapshots).*|^\\[INFO] ?$");
    /**
     * Actionable Spotless formatting failure diagnostic, e.g.
     * {@code [INFO] Spotless.Java is keeping 2 files clean - 1 needs changes to be clean, ...}.
     * Matched only when at least one file needs changes (leading count in {@code 1-9...}) so the
     * successful "0 need changes to be clean" chatter is left to be dropped as routine INFO noise.
     */
    private static final Pattern SPOTLESS =
            Pattern.compile("^\\[INFO] Spotless\\.\\S+.*\\b[1-9]\\d*\\s+needs? changes? to be clean\\b.*");
    /** Execution-wrapper status metadata appended to the command output, not emitted by Maven. */
    private static final Pattern EXIT_CODE = Pattern.compile("^Exit code: [0-9]+$");

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHead(command, "mvn", "mvnd");
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Set<String> names() {
        return Set.of("mvn", "mvnd", "mvnw");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        if (INFORMATIONAL_OPTION.matcher(command).find()) {
            return lines.map(commandOutput::stripAnsi);
        }
        final var errors = new ArrayList<String>();
        final var warns = new ArrayList<String>();
        final var spotlessDirty = new ArrayList<String>();
        final var failingTests = new ArrayList<String>();
        final var passingTests = new ArrayList<String>();
        final var reactor = new ArrayList<String>();
        String buildLine = null;
        String exitCode = null;
        String currentTest = null;
        var inReactor = false;

        for (final var raw : lines.toArray(String[]::new)) {
            final var line = commandOutput.stripAnsi(raw);
            if (logNoise.drop(line) || NOISE.matcher(line).matches()) {
                continue;
            }
            final var sm = SPOTLESS.matcher(line);
            if (sm.matches()) {
                spotlessDirty.add(line.strip());
                continue;
            }
            final var ec = EXIT_CODE.matcher(line);
            if (ec.matches()) {
                exitCode = line.strip();
                continue;
            }
            final var m = RUNNING.matcher(line);
            if (m.matches()) {
                currentTest = m.group(1).strip();
                continue;
            }
            final var tm = TESTS_RUN.matcher(line);
            if (tm.find()) {
                final var failures = Integer.parseInt(tm.group(2)) + Integer.parseInt(tm.group(3));
                if (failures > 0) {
                    failingTests.add(
                            currentTest != null ? "FAIL " + currentTest + "  (" + line.strip() + ")" : line.strip());
                } else if (currentTest != null) {
                    passingTests.add(currentTest);
                }
                currentTest = null;
                continue;
            }
            if (line.startsWith("[ERROR]")) {
                errors.add(collapseStack(line));
                continue;
            }
            if (line.startsWith("[WARNING]")) {
                if (warns.size() < 20) {
                    warns.add(line);
                } else if (warns.size() == 20) {
                    warns.add("[WARNING] ... more warnings omitted");
                }
                continue;
            }
            if (line.contains("Reactor Summary")) {
                inReactor = true;
                reactor.add(line.strip());
                continue;
            }
            if (inReactor) {
                if (line.startsWith("[INFO] BUILD") || line.startsWith("[INFO] ---")) {
                    inReactor = false;
                } else if (!line.isBlank() && line.startsWith("[INFO]")) {
                    reactor.add(line.strip());
                    continue;
                }
            }
            if (line.startsWith("[INFO] BUILD")) {
                buildLine = line.strip();
            }
        }

        final var out = new ArrayList<String>();
        if (buildLine != null) {
            out.add(buildLine);
        }
        if (!errors.isEmpty()) {
            out.add("Errors (" + errors.size() + "):");
            final var cap = Math.min(errors.size(), 20);
            for (var i = 0; i < cap; i++) {
                out.add(errors.get(i));
            }
            if (errors.size() > cap) {
                out.add("... +" + (errors.size() - cap) + " more errors");
            }
        }
        if (!spotlessDirty.isEmpty()) {
            out.add("Formatting:");
            out.addAll(spotlessDirty.stream().map(d -> "  " + d).toList());
        }
        if (!failingTests.isEmpty()) {
            out.add("Failing tests:");
            out.addAll(failingTests.stream().map(t -> "  " + t).toList());
        }
        if (reactor.size() > 2) {
            out.addAll(reactor);
        }
        if (!warns.isEmpty()) {
            out.add("Warnings (" + warns.size() + "):");
            out.addAll(warns);
        }
        if (!passingTests.isEmpty()) {
            out.add("Passing tests (" + passingTests.size() + "):");
            // wrap at ~110 chars so the generic line-based cap stays effective on huge runs
            final var current = new StringBuilder("  ");
            for (final var test : passingTests) {
                if (current.length() > 2 && current.length() + test.length() + 2 > 110) {
                    out.add(current.toString());
                    current.setLength(2);
                }
                if (current.length() > 2) {
                    current.append(", ");
                }
                current.append(test);
            }
            if (current.length() > 2) {
                out.add(current.toString());
            }
        }
        if (exitCode != null) {
            out.add(exitCode);
        }
        // enrich a quiet run with the fresh surefire/failsafe report summaries
        return enrichFromSurefire(command, out).stream();
    }

    /**
     * Collapses common framework stack frames of a maven error line so a failing build keeps
     * the meaningful application frames instead of tens of org.junit/java.base lines.
     * Applied per-line: a "[ERROR] Failed to execute goal ..." line keeps its head, and any
     * following "[ERROR] Caused by:" lines are kept but their known framework frames dropped.
     */
    private static String collapseStack(final String line) {
        if (!line.startsWith("[ERROR]")
                || !line.contains(".junit.")
                        && !line.contains("org.junit")
                        && !line.contains("java.base")
                        && !line.contains("org.apache.maven")) {
            return line;
        }
        return FRAMEWORK_STACK_FRAME.matcher(line).replaceAll("");
    }

    /**
     * Enriches a quiet ({@code -q}) test run from the fresh surefire/failsafe report files:
     * parses {@code target/surefire-reports/*.txt} and {@code target/failsafe-reports/*.txt}
     * summaries (each has a "{@code Tests run: X, Failures: Y, Errors: Z, Skipped: W}" line and a
     * "{@code <<< FAILURE!}" marker) into per-class pass/fail lists. No report files means the
     * input is returned unchanged (offline/CI without reports keeps the current behavior).
     */
    static List<String> enrichFromSurefire(final String command, final List<String> lines) {
        return enrichFromSurefire(command, lines, Path.of(""));
    }

    static List<String> enrichFromSurefire(final String command, final List<String> lines, final Path baseDir) {
        if (!command.contains("-q")) {
            return lines;
        }
        final var quiet = lines.stream().anyMatch(l -> TEST_SUMMARY.matcher(l).matches());
        if (quiet) {
            return lines; // already has the per-class lines
        }
        final var dirs = List.of(
                baseDir.resolve("target/surefire-reports").toAbsolutePath(),
                baseDir.resolve("target/failsafe-reports").toAbsolutePath());
        final var out = new ArrayList<String>();
        var added = false;
        for (final var dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var files = Files.list(dir)) {
                final var reports = files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                        .sorted()
                        .toList();
                for (final var report : reports) {
                    final var content = Files.readString(report, StandardCharsets.UTF_8);
                    final var tests = TESTS_RUN.matcher(content);
                    if (!tests.find()) {
                        continue;
                    }
                    final var name = report.getFileName().toString().replace(".txt", "");
                    final var failures = Integer.parseInt(tests.group(2)) + Integer.parseInt(tests.group(3));
                    if (failures > 0) {
                        out.add("FAIL " + name + "  (" + tests.group(0) + ")");
                    } else {
                        out.add(name);
                    }
                    added = true;
                }
            } catch (final IOException e) {
                // ignore unreadable reports and continue with what we have
            }
        }
        if (!added) {
            return lines;
        }
        final var result = new ArrayList<String>(lines);
        result.add("");
        result.add("test reports:");
        result.addAll(out);
        return result;
    }
}
