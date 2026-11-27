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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Dependency-free regression tests: java .github/test-reports/SurefireReportTest.java. */
public class SurefireReportTest {
    public static void main(final String[] args) throws Exception {
        final var tests = new SurefireReportTest();
        tests.combinesReports();
        tests.missingReports();
        tests.truncatedReport();
        tests.limitsFailures();
        tests.rejectsExternalEntities();
        System.out.println("All 5 SurefireReport tests passed");
    }

    private void combinesReports() throws Exception {
        final var output = render(Map.of(
                "api/target/surefire-reports/TEST-api.xml", """
                        <testsuites tests="3"><testsuite tests="3">
                          <testcase classname="Api" name="passed"/>
                          <testcase classname="Api" name="failed &lt;script&gt;"><failure/></testcase>
                          <testcase name="skipped"><skipped/></testcase>
                        </testsuite></testsuites>
                        """,
                "integration/target/failsafe-reports/TEST-it.xml", """
                        <testsuite>
                          <testcase name="passed"/>
                          <testcase classname="Integration" name="broken"><error/></testcase>
                        </testsuite>
                        """,
                "integration/target/failsafe-reports/failsafe-summary.xml", "<failsafe-summary/>"));
        contains(output, "Existing summary\n## Java Test Report");
        contains(output, "2 passed, 1 failed, 1 errors, 1 skipped / 5 total");
        contains(output, "Test reports: 2 parsed / 2 found");
        contains(output, "Api#failed &lt;script&gt;");
        excludes(output, "<script>");
        contains(output, "Integration#broken");
        contains(output, "api/target/surefire-reports/TEST-api.xml");
        contains(output, "java-test-reports");
    }

    private void missingReports() throws Exception {
        final var output = render(Map.of());
        contains(output, "No Java test reports found");
        excludes(output, "0 passed");
    }

    private void truncatedReport() throws Exception {
        final var output = render(Map.of(
                "api/target/surefire-reports/TEST-bad.xml", "<testsuite>",
                "api/target/surefire-reports/TEST-good.xml", "<testsuite><testcase name='ok'/></testsuite>"));
        contains(output, "Incomplete results");
        contains(output, "1 parsed / 2 found");
        contains(output, "1 passed, 0 failed, 0 errors, 0 skipped / 1 total");
    }

    private void limitsFailures() throws Exception {
        final var output = render(Map.of("api/target/surefire-reports/TEST-fail.xml",
                IntStream.range(0, 60)
                        .mapToObj(i -> "<testcase name='case" + i + "'><failure/></testcase>")
                        .collect(Collectors.joining("", "<testsuite>", "</testsuite>"))));
        contains(output, "60 failed");
        contains(output, "#case49</code>");
        excludes(output, "#case50</code>");
    }

    private void rejectsExternalEntities() throws Exception {
        final var output = render(Map.of(
                "secret.txt", "do-not-publish-this-secret",
                "api/target/surefire-reports/TEST-xxe.xml", """
                        <!DOCTYPE testsuite [<!ENTITY secret SYSTEM "../../secret.txt">]>
                        <testsuite><testcase name="test"><failure>&secret;</failure></testcase></testsuite>
                        """));
        contains(output, "Incomplete results");
        excludes(output, "do-not-publish-this-secret");
    }

    private String render(final Map<String, String> reports) throws Exception {
        final var workspace = Files.createTempDirectory("surefire-report-test-");
        try {
            for (final var entry : reports.entrySet()) {
                final var path = workspace.resolve(entry.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, entry.getValue());
            }
            final var summary = workspace.resolve("summary.md");
            Files.writeString(summary, "Existing summary\n");
            final var log = workspace.resolve("process.log");
            final var builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    Path.of(".github/test-reports/SurefireReport.java").toAbsolutePath().toString())
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().put("GITHUB_STEP_SUMMARY", summary.toString());
            final var process = builder.start();
            try {
                process.getOutputStream().close();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    throw new AssertionError("Reporter timed out");
                }
                if (process.exitValue() != 0) {
                    throw new AssertionError("Reporter failed: " + Files.readString(log));
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
            return Files.readString(summary);
        } finally {
            try (final var paths = Files.walk(workspace)) {
                for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void contains(final String output, final String expected) {
        if (!output.contains(expected)) {
            throw new AssertionError("Missing: " + expected + "\n" + output);
        }
    }

    private void excludes(final String output, final String unexpected) {
        if (output.contains(unexpected)) {
            throw new AssertionError("Unexpected: " + unexpected + "\n" + output);
        }
    }
}
