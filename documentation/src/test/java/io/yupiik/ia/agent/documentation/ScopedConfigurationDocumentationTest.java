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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Checks site wiring, not the external generator's rendering of configuration metadata. */
class ScopedConfigurationDocumentationTest {
    private static final Pattern INCLUDE = Pattern.compile(
            "(?m)^include::(content/coding-agent/_partials/generated/(?:configuration-|documentation\\.)[^\\[\\r\\n]+\\.adoc)\\[");
    private final Path base = Path.of("").toAbsolutePath().normalize();
    private final Path source = base.resolve("src/main/minisite");
    private final Path generated = source.resolve("content/coding-agent/_partials/generated");

    @Test
    void configurationIncludesHaveUniqueProducers() throws Exception {
        final var outputs = new HashSet<Path>();
        for (final var action : actions()) {
            final var output = Path.of(action.get("output")).toAbsolutePath().normalize();
            assertTrue(outputs.add(output), "Output collision: " + output);
        }
        assertFalse(outputs.isEmpty(), "No configuration references configured");
        final var includes = new HashSet<Path>();
        try (final var pages = Files.walk(source.resolve("content"))) {
            for (final var page : pages.filter(it -> it.toString().endsWith(".adoc"))
                    .filter(it -> !it.startsWith(generated))
                    .toList()) {
                INCLUDE.matcher(Files.readString(page))
                        .results()
                        .map(it -> source.resolve(it.group(1)).normalize())
                        .forEach(includes::add);
            }
        }
        assertEquals(
                outputs, includes, "Every configuration include needs a producer and every output needs a consumer");
    }

    private List<Map<String, String>> actions() throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var pom =
                factory.newDocumentBuilder().parse(base.resolve("pom.xml").toFile());
        final var nodes = (NodeList) XPathFactory.newInstance()
                .newXPath()
                .evaluate(
                        "/project/build/plugins/plugin[artifactId='yupiik-tools-maven-plugin']/configuration/preActions/"
                                + "preAction[type='io.yupiik.fusion.documentation.DocumentationGenerator']/configuration",
                        pom,
                        XPathConstants.NODESET);
        final var actions = new ArrayList<Map<String, String>>();
        for (var i = 0; i < nodes.getLength(); i++) {
            final var configuration = new HashMap<String, String>();
            final var children = nodes.item(i).getChildNodes();
            for (var j = 0; j < children.getLength(); j++) {
                final var child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    configuration.put(
                            child.getNodeName(),
                            child.getTextContent().strip().replace("${project.basedir}", base.toString()));
                }
            }
            if (configuration.containsKey("output")
                    && generated.equals(Path.of(configuration.get("output"))
                            .toAbsolutePath()
                            .normalize()
                            .getParent())) {
                actions.add(configuration);
            }
        }
        return actions;
    }
}
