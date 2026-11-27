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
package io.yupiik.htmltomarkdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HtmlLightParserTest {

    private final HtmlLightParser parser = new HtmlLightParser();

    @Test
    void plainTextStripsMarkup() {
        final var out =
                parser.toText("<html><body><h1>Title</h1><p>Hello <b>world</b> &amp; friends.</p></body></html>");
        assertEquals("Title\nHello world & friends.", out);
    }

    @Test
    void realisticArticleToText() {
        final var doc = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <title>Ignored title</title>
                  <style>body { font-family: sans-serif; }</style>
                  <script>console.log('head script');</script>
                </head>
                <body>
                  <h1>Building Agents</h1>
                  <p>An agent needs <strong>tools</strong> and <em>memory</em>&nbsp;to act.</p>
                  <ul>
                    <li>plan</li>
                    <li>execute</li>
                  </ul>
                  <blockquote>A quote to remember.</blockquote>
                  <pre>public void run() {}</pre>
                  <footer>&copy; 2026</footer>
                </body>
                </html>
                """;
        final var out = parser.toText(doc);
        assertTrue(out.contains("Building Agents"));
        assertTrue(out.contains("An agent needs tools and memory to act."));
        assertTrue(out.contains("plan"));
        assertTrue(out.contains("execute"));
        assertTrue(out.contains("A quote to remember."));
        assertTrue(out.contains("public void run() {}"));
        assertTrue(out.contains("(c) 2026"));
        assertFalse(out.contains("Ignored title"));
        assertFalse(out.contains("console.log"));
        assertFalse(out.contains("font-family"));
        assertFalse(out.contains("--"));
    }

    @Test
    void markdownRendersLinksAndImages() {
        final var out = parser.toMarkdown(
                "A <a href=\"https://example.com\">link</a> and <img src=\"x.png\" alt=\"pic\"> here.");
        assertTrue(out.contains("[img:pic]"));
        assertTrue(out.contains("link"));
        assertTrue(out.contains("(https://example.com)"));
    }

    @Test
    void skipsScriptAndStyleContent() {
        final var out = parser.toText("<script>var x = 1 < 2;</script><style>.a{b:c}</style><p>kept</p>");
        assertEquals("kept", out);
    }

    @Test
    void skipsNestedScriptWithComparisonsAndHtmlInside() {
        final var doc = "<script>if (a < b && c > d) { el.innerHTML = '<span>nope</span>'; }</script>after";
        final var out = parser.toText(doc);
        assertEquals("after", out);
    }

    @Test
    void skipsNoscriptAndTextarea() {
        final var doc = "<noscript>needs js<div>xx</div></noscript><textarea>raw <b>text</b></textarea><p>visible</p>";
        assertEquals("visible", parser.toText(doc));
    }

    @Test
    void skipsHtmlComments() {
        final var out = parser.toText("<!-- hidden block <p>secret</p> --><p>real</p>");
        assertEquals("real", out);
    }

    @Test
    void extractsMarkdownFenceForPreAndListItem() {
        final var out = parser.toMarkdown("<ul><li>one</li><li>two</li></ul><pre>code</pre>");
        assertTrue(out.contains("- one"));
        assertTrue(out.contains("- two"));
        assertTrue(out.contains("```"));
        assertTrue(out.contains("code"));
    }

    @Test
    void decodesNumericEntities() {
        final var out = parser.toText("<p>&#65;&#x42;&amp;&nbsp;&#39;</p>");
        assertEquals("AB& '", out);
    }

    @Test
    void collapsesBlankLines() {
        final var out = parser.toText("<p>a</p><p></p><p></p><p>b</p>");
        assertFalse(out.contains("\n\n\n"));
        assertTrue(out.startsWith("a"));
        assertTrue(out.endsWith("b"));
    }

    @Test
    void handlesMalformedAndUnclosedTags() {
        final var out = parser.toText("<p>alpha<div>beta<br>gamma<img alt=\"pic here\"<span>delta");
        assertTrue(out.contains("alpha"));
        assertTrue(out.contains("beta"));
        assertTrue(out.contains("gamma"));
        assertTrue(out.contains("pic here"));
        assertTrue(out.contains("delta"));
    }

    @Test
    void nestedListsRenderAsSeparateItems() {
        final var out = parser.toMarkdown("<ul><li>root<ul><li>child</li></ul></li><li>sibling</li></ul>");
        assertTrue(out.contains("- root"));
        assertTrue(out.contains("- child"));
        assertTrue(out.contains("- sibling"));
    }

    @Test
    void nullInputIsEmpty() {
        assertEquals("", parser.toText(null));
        assertEquals("", parser.toMarkdown(null));
    }

    @Test
    void greaterThanInsideQuotedAttributeDoesNotBreakTag() {
        final var out = parser.toText("<a title=\"a > b\">link</a> and x < y rest");
        assertTrue(out.contains("link"));
        assertFalse(out.contains("b>"));
        assertFalse(out.contains(" a > b"));
    }

    @Test
    void attributeNamesAreWholeWordMatched() {
        final var out = parser.toMarkdown("<a data-href=\"data\" href=\"https://real.example\">go</a>");
        assertTrue(out.contains("(https://real.example)"));
        assertFalse(out.contains("(data)"));
    }

    @Test
    void unclosedHeadBailsAtBodyAndKeepsContent() {
        final var out = parser.toText("<head><title>t</title><meta name=desc content=x></head><body><p>keep me</p>");
        assertEquals("keep me", out);
        final var out2 = parser.toText("<head>unclosed head content that should be dropped<body><p>still kept</p>");
        assertFalse(out2.contains("unclosed head"));
        assertTrue(out2.contains("still kept"));
    }

    @Test
    void markdownPrefixesHeadingsWithPound() {
        final var out = parser.toMarkdown("<h1>A</h1><h2>B</h2><h6>C</h6>");
        assertTrue(out.contains("# A"));
        assertTrue(out.contains("## B"));
        assertTrue(out.contains("###### C"));
    }

    @Test
    void orderedListIsNumberedInMarkdown() {
        final var out = parser.toMarkdown("<ol><li>one</li><li>two</li></ol>");
        assertTrue(out.contains("1. one"));
        assertTrue(out.contains("2. two"));
        assertFalse(out.contains("- one"));
    }

    @Test
    void unorderedListStaysBuldeted() {
        final var out = parser.toMarkdown("<ul><li>x</li><li>y</li></ul>");
        assertTrue(out.contains("- x"));
        assertTrue(out.contains("- y"));
    }

    @Test
    void definitionAndFigureBlocksBreak() {
        final var out =
                parser.toText("<dl><dt>Term</dt><dd>Definition</dd></dl><figure><figcaption>Cap</figcaption></figure>");
        assertTrue(out.contains("Term"));
        assertTrue(out.contains("Definition"));
        assertTrue(out.contains("Cap"));
        assertFalse(out.contains("Term Definition"));
        assertFalse(out.contains("Definition Cap"));
    }

    @Test
    void quoteElementGetsQuotesInMarkdown() {
        final var out = parser.toMarkdown("<p>He said <q>hello</q> then left.</p>");
        assertTrue(out.contains("\"hello\""));
    }

    @Test
    void iframeAndNoframesContentIsSkipped() {
        final var out = parser.toText("<iframe src=\"x\">fallback</iframe><noframes>no frames</noframes><p>kept</p>");
        assertEquals("kept", out);
    }
}
