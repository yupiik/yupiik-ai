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

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Small DOM text helpers shared by the XML-to-markdown mappers. */
final class XmlText {

    private XmlText() {
        // utility
    }

    /** First direct child element with the given tag, or {@code null}. */
    static String firstChildText(final Node parent, final String tag) {
        final var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final var child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                return nodeText(child);
            }
        }
        return null;
    }

    /** Returns all descendant elements with the given tag, in document order. */
    static List<Element> elementsByTagName(final Node parent, final String tag) {
        final var result = new ArrayList<Element>();
        final var all = ((Element) parent).getElementsByTagName(tag);
        for (int i = 0; i < all.getLength(); i++) {
            final var item = all.item(i);
            if (item.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) item);
            }
        }
        return result;
    }

    private static String nodeText(final Node el) {
        return el.getTextContent() == null ? null : el.getTextContent().strip();
    }
}
