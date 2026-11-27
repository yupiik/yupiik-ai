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

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Node;

class PublicConfigurationDocumentationTest {
    private final Path base = Path.of("").toAbsolutePath().normalize();

    @Test
    void everyPublicRootIsSelected() throws Exception {
        final var expected = new TreeSet<String>();
        final var resources = Thread.currentThread()
                .getContextClassLoader()
                .getResources("META-INF/fusion/configuration/documentation.json");
        try (final var mapper = new JsonMapperImpl(List.of(), ignored -> Optional.empty())) {
            while (resources.hasMoreElements()) {
                try (final var input = resources.nextElement().openStream()) {
                    final var metadata = mapper.fromBytes(Map.class, input.readAllBytes());
                    if (metadata.get("roots") instanceof List<?> roots) {
                        roots.stream()
                                .map(Object::toString)
                                .filter(this::isPublicRoot)
                                .forEach(expected::add);
                    }
                }
            }
        }
        assertFalse(expected.isEmpty(), "Compile the configuration modules first");
        assertEquals(
                expected,
                new TreeSet<>(Arrays.stream(configuration().get("roots").split(","))
                        .map(String::strip)
                        .filter(it -> !it.isEmpty())
                        .toList()),
                "Every public root needs an explicit generator input/selection; CLI roots have their own reference");
        assertTrue(
                Files.readString(base.resolve("src/main/minisite/content/coding-agent/configuration.adoc"))
                        .contains("include::content/coding-agent/_partials/generated/public-configuration.adoc[]"),
                "The catalogue must be consumed by the configuration page");
    }

    private boolean isPublicRoot(final String name) {
        // Commands, nested command options and external framework configuration keep separate owners.
        return !name.contains("$")
                && !name.contains(".cli.")
                && !name.contains(".command.")
                && (name.startsWith("io.yupiik.ia.")
                        || name.startsWith("io.yupiik.ai.")
                        || name.startsWith("io.yupiik.maven.")
                        || name.startsWith("io.yupiik.fusion.a2a.")
                        || name.startsWith("io.yupiik.fusion.mcp."));
    }

    private Map<String, String> configuration() throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final var pom =
                factory.newDocumentBuilder().parse(base.resolve("pom.xml").toFile());
        final var action = (Node) XPathFactory.newInstance()
                .newXPath()
                .evaluate(
                        "/project/build/plugins/plugin[artifactId='yupiik-tools-maven-plugin']/configuration/preActions/"
                                + "preAction[type='io.yupiik.fusion.documentation.ConfigurationDocumentationGenerator']"
                                + "/configuration[module='public-configuration']",
                        pom,
                        XPathConstants.NODE);
        assertTrue(action != null, "Use the existing Fusion generator for the public catalogue");
        final var configuration = new HashMap<String, String>();
        for (var i = 0; i < action.getChildNodes().getLength(); i++) {
            final var child = action.getChildNodes().item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                configuration.put(
                        child.getNodeName(),
                        child.getTextContent().strip().replace("${project.basedir}", base.toString()));
            }
        }
        return configuration;
    }
}
