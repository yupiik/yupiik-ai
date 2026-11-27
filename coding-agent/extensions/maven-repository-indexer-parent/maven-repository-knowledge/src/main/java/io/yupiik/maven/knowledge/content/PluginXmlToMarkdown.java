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

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

/**
 * Extracts the configuration surface of a Maven plugin from its {@code META-INF/maven/plugin.xml} descriptor and
 * renders it as compact markdown folded into the knowledge index: one {@code ## Mojo <goal>} section per mojo with
 * its description and, per {@code <parameter>}, a {@code - `<name>`} line carrying the system-property override
 * (when the ${@code <expression>} is a {@code ${...}}), the default (from {@code <defaultValue>}) and the
 * description. This avoids dumping the raw plugin.xml as a sibling file.
 */
public final class PluginXmlToMarkdown {

    private final DocumentBuilderFactory documentBuilderFactory;

    public PluginXmlToMarkdown(final DocumentBuilderFactory documentBuilderFactory) {
        this.documentBuilderFactory = documentBuilderFactory;
    }

    /**
     * @param pluginXml the raw {@code plugin.xml} descriptor text.
     * @return the markdown section (may start directly with a {@code ## Mojo ...}); empty on any parse failure or
     *         when the descriptor is absent.
     */
    public String apply(final String pluginXml) {
        if (pluginXml == null || pluginXml.isBlank()) {
            return "";
        }
        try {
            final var doc =
                    documentBuilderFactory.newDocumentBuilder().parse(new InputSource(new StringReader(pluginXml)));
            final var sb = new StringBuilder();
            final var mojos = doc.getElementsByTagName("mojo");
            for (int m = 0; m < mojos.getLength(); m++) {
                final var mojo = mojos.item(m);
                final var goal = XmlText.firstChildText(mojo, "goal");
                final var description = XmlText.firstChildText(mojo, "description");
                sb.append("## Mojo ").append(goal == null ? "" : goal).append('\n');
                if (description != null && !description.isBlank()) {
                    sb.append(description.strip()).append('\n');
                }
                final var params = XmlText.elementsByTagName(mojo, "parameter");
                if (params.isEmpty()) {
                    sb.append("No configuration. ");
                } else {
                    for (final var param : params) {
                        final var name = XmlText.firstChildText(param, "name");
                        final var expression = XmlText.firstChildText(param, "expression");
                        final var defaultValue = XmlText.firstChildText(param, "defaultValue");
                        final var paramDesc = XmlText.firstChildText(param, "description");
                        sb.append("- `").append(name == null ? "" : name).append('`');
                        if (expression != null && expression.contains("${")) {
                            sb.append(" (property ").append(expression.strip()).append(')');
                        }
                        if (defaultValue != null && !defaultValue.isBlank()) {
                            sb.append(" default=").append(defaultValue.strip());
                        }
                        if (paramDesc != null && !paramDesc.isBlank()) {
                            sb.append(" - ").append(paramDesc.strip());
                        }
                        sb.append('\n');
                    }
                }
            }
            return sb.toString().stripTrailing();
        } catch (final Exception e) {
            return "";
        }
    }
}
