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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Renders Surefire and Failsafe XML reports in a GitHub Actions job summary. */
public class SurefireReport {
    public static void main(final String[] args) throws Exception {
        Files.writeString(
                Path.of(System.getenv("GITHUB_STEP_SUMMARY")),
                new SurefireReport().render(Path.of("").toAbsolutePath()),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private String render(final Path workspace) throws Exception {
        final var output = new StringBuilder("## Java Test Report\n\nSurefire and Failsafe results.\n\n");
        final List<Path> reports;
        try (final var paths = Files.walk(workspace)) {
            reports = paths.filter(Files::isRegularFile).filter(this::isReport).sorted().toList();
        }
        if (reports.isEmpty()) {
            return output.append("No Java test reports found. The build may have stopped before tests ran.\n")
                    .toString();
        }

        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        final var parser = factory.newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler() {
            @Override
            public void fatalError(final SAXParseException error) throws SAXException {
                throw error;
            }
        });
        final var totals = new long[4];
        final var failures = new ArrayList<String>();
        final var unreadable = new ArrayList<String>();
        final var rows = new StringBuilder();
        var parsed = 0;
        for (final var report : reports) {
            final var label = workspace.relativize(report).toString().replace('\\', '/');
            final Document document;
            try (final var input = Files.newInputStream(report)) {
                document = parser.parse(input);
            } catch (final SAXException | IOException error) {
                unreadable.add(label + ": " + error.getMessage());
                continue;
            }
            parsed++;
            final var counts = new long[4]; // passed, failed, errors, skipped
            final var cases = document.getElementsByTagName("testcase");
            for (var i = 0; i < cases.getLength(); i++) {
                final var test = (Element) cases.item(i);
                final var status = status(test);
                counts[status]++;
                if (status == 1 || status == 2) {
                    failures.add(test.getAttribute("classname") + "#" + test.getAttribute("name"));
                }
            }
            rows.append("| <code>").append(escape(label)).append("</code>");
            for (var i = 0; i < counts.length; i++) {
                totals[i] += counts[i];
                rows.append(" | ").append(counts[i]);
            }
            rows.append(" |\n");
        }
        output.append("### Summary\n\nTest reports: ").append(parsed).append(" parsed / ")
                .append(reports.size()).append(" found\n\nTest results: **")
                .append(totals[0]).append(" passed, ").append(totals[1]).append(" failed, ")
                .append(totals[2]).append(" errors, ").append(totals[3]).append(" skipped / ")
                .append(Arrays.stream(totals).sum()).append(" total**\n\n");
        if (!unreadable.isEmpty()) {
            output.append("**Incomplete results: some report files could not be read.**\n\n");
            unreadable.stream().limit(20).forEach(value -> appendItem(output, value));
            output.append('\n');
        }
        if (!failures.isEmpty()) {
            output.append("### Failed tests (first 50)\n\n");
            failures.stream().limit(50).forEach(value -> appendItem(output, value));
            output.append('\n');
        }
        return output.append("<details><summary>Results by report</summary>\n\n")
                .append("| Report | Passed | Failed | Errors | Skipped |\n")
                .append("| --- | ---: | ---: | ---: | ---: |\n")
                .append(rows).append("\n</details>\n\n")
                .append("Download the **java-test-reports** artifact for full reports and diagnostics.\n")
                .toString();
    }

    private boolean isReport(final Path path) {
        final var name = path.getFileName().toString();
        final var parent = path.getParent();
        return name.startsWith("TEST-") && name.endsWith(".xml")
                && parent != null && parent.getParent() != null
                && (parent.endsWith("surefire-reports") || parent.endsWith("failsafe-reports"))
                && parent.getParent().endsWith("target");
    }

    private int status(final Element test) {
        // Inspect direct children; nested suite totals must not be counted again.
        var status = 0;
        final var children = test.getChildNodes();
        for (var i = 0; i < children.getLength(); i++) {
            final var candidate = switch (children.item(i).getNodeName()) {
                case "failure" -> 1;
                case "error" -> 2;
                case "skipped" -> 3;
                default -> 0;
            };
            if (candidate > 0 && (status == 0 || candidate < status)) {
                status = candidate;
            }
        }
        return status;
    }

    private void appendItem(final StringBuilder output, final String value) {
        output.append("- <code>").append(escape(value)).append("</code>\n");
    }

    private String escape(final String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;").replace("|", "&#124;")
                .replace("\r", " ").replace("\n", " ");
    }
}
