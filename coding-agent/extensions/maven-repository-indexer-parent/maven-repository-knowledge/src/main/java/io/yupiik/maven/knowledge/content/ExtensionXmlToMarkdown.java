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
 * Best-effort rendering of a Maven (re)extension descriptor ({@code extension.xml} or
 * {@code META-INF/maven/extension.xml}) as a compact markdown {@code ## Extension} section (its name and
 * description), folded into the knowledge index. Maven 4 descriptors are not fully structured yet, so only the
 * declaring name/description are surfaced; the rest is best-effort and tolerant of unknown tags.
 */
public final class ExtensionXmlToMarkdown {

    private final DocumentBuilderFactory documentBuilderFactory;

    public ExtensionXmlToMarkdown(final DocumentBuilderFactory documentBuilderFactory) {
        this.documentBuilderFactory = documentBuilderFactory;
    }

    /**
     * @param extensionXml the raw extension descriptor text.
     * @return the {@code ## Extension} markdown section, or empty on any parse failure or when absent.
     */
    public String apply(final String extensionXml) {
        if (extensionXml == null || extensionXml.isBlank()) {
            return "";
        }
        try {
            final var doc =
                    documentBuilderFactory.newDocumentBuilder().parse(new InputSource(new StringReader(extensionXml)));
            final var sb = new StringBuilder();
            sb.append("## Extension\n");
            final var names = XmlText.elementsByTagName(doc.getDocumentElement(), "name");
            final var name = names.isEmpty() ? null : names.get(0).getTextContent();
            if (name != null && !name.isBlank()) {
                sb.append(name.strip()).append('\n');
            }
            final var descriptions = XmlText.elementsByTagName(doc.getDocumentElement(), "description");
            final var description =
                    descriptions.isEmpty() ? null : descriptions.get(0).getTextContent();
            if (description != null && !description.isBlank()) {
                sb.append(description.strip()).append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (final Exception e) {
            return "";
        }
    }
}
