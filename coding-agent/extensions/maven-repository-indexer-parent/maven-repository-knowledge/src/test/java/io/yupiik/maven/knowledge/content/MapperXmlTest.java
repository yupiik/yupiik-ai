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
package io.yupiik.maven.knowledge.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class MapperXmlTest {

    private final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

    @Test
    void pluginXmlRendersMojoAndParameters() {
        final var xml = """
                <plugin>
                  <mojos>
                    <mojo>
                      <goal>generate</goal>
                      <description>Generates sources from the model.</description>
                      <parameters>
                        <parameter>
                          <name>outputDirectory</name>
                          <expression>${project.build.directory}/generated</expression>
                          <defaultValue>target/generated</defaultValue>
                          <description>Where to write the files.</description>
                        </parameter>
                        <parameter>
                          <name>verbose</name>
                          <expression>${plugin.verbose}</expression>
                          <defaultValue>false</defaultValue>
                          <description>Print extra output.</description>
                        </parameter>
                      </parameters>
                    </mojo>
                  </mojos>
                </plugin>
                """;
        final var out = new PluginXmlToMarkdown(dbf).apply(xml);
        assertTrue(out.contains("## Mojo generate"), "mojo goal rendered");
        assertTrue(out.contains("Generates sources from the model."), "mojo description rendered");
        assertTrue(out.contains("- `outputDirectory`"), "parameter name rendered");
        assertTrue(out.contains("(property ${project.build.directory}/generated)"), "property override rendered");
        assertTrue(out.contains("default=target/generated"), "default rendered");
        assertTrue(out.contains("- Where to write the files."), "parameter description rendered");
        assertFalse(out.contains("<mojo>"), "no raw XML leakage");
    }

    @Test
    void pluginXmlBlankAndBrokenReturnEmpty() {
        assertEquals("", new PluginXmlToMarkdown(dbf).apply("  "));
        assertEquals("", new PluginXmlToMarkdown(dbf).apply("<not-xml"));
    }

    @Test
    void extensionXmlRendersNameAndDescription() {
        final var xml = """
                <extension>
                  <name>custom-repository</name>
                  <description>Custom transport for fetching artifacts.</description>
                </extension>
                """;
        final var out = new ExtensionXmlToMarkdown(dbf).apply(xml);
        assertTrue(out.contains("## Extension"), "extension header rendered");
        assertTrue(out.contains("custom-repository"), "extension name rendered");
        assertTrue(out.contains("Custom transport for fetching artifacts."), "extension description rendered");
    }

    @Test
    void extensionXmlBlankReturnsEmpty() {
        assertEquals("", new ExtensionXmlToMarkdown(dbf).apply(""));
        assertEquals("", new ExtensionXmlToMarkdown(dbf).apply("<broken"));
    }
}
