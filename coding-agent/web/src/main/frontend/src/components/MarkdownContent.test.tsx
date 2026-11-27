import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MarkdownContent } from './MarkdownContent';
import { eventsToBlocks } from '../api/eventLog';

function render(content: string): string {
  return renderToStaticMarkup(<MarkdownContent content={content} />);
}

describe('MarkdownContent', () => {
  it('renders a replayed subagent summary as assistant Markdown rather than envelope text', () => {
    const [block] = eventsToBlocks([{
      type: 'subagent-completed', sessionId: 'parent', subagentId: 'child',
      message: '## Conclusion\n\n**Reviewed**\n\n```text\nresult\n```',
      data: { subagentName: 'active-instana-review', success: true, cancelled: false },
    }]);
    expect(block.kind).toBe('subagent');
    if (block.kind !== 'subagent') {
      throw new Error('Expected a subagent card');
    }
    const html = renderToStaticMarkup(<MarkdownContent content={block.summary ?? ''} variant="assistant" />);
    expect(html).toContain('data-variant="assistant"');
    expect(html).toContain('<h2>Conclusion</h2>');
    expect(html).toContain('<strong>Reviewed</strong>');
    expect(html).toContain('<pre class="yca-code-block">');
    expect(html).not.toContain('data-variant="user"');
    expect(html).not.toContain('&lt;subagent');
  });

  it('defaults to a document root and supports every presentation variant', () => {
    expect(render('Hello')).toBe('<div data-variant="document" class="aui-md"><p>Hello</p></div>');
    for (const variant of ['user', 'assistant', 'document', 'thought'] as const) {
      expect(renderToStaticMarkup(<MarkdownContent content="Hello" variant={variant} />))
        .toContain(`<div data-variant="${variant}" class="aui-md">`);
    }
  });

  it.each(['NOTE', 'TIP', 'IMPORTANT', 'WARNING', 'CAUTION'])('renders a standalone %s callout', title => {
    const html = render(`> [!${title}]\n> Body with **emphasis** and \`code\`.\n>\n> - one\n> - two`);
    expect(html).toContain(`<blockquote data-callout="${title.toLowerCase()}">`);
    expect(html).toContain(`<p class="yca-callout-title">${title}</p>`);
    expect(html).toContain('<p>Body with <strong>emphasis</strong> and <code>code</code>.</p>');
    expect(html).toContain('<li>one</li>');
    expect(html).not.toContain(`[!${title}]`);
  });

  it('preserves body whitespace and formatting when stripping just marker and newline', () => {
    expect(render('> [!NOTE]\n> body\n> continued'))
      .toContain('<p>body\ncontinued</p>');
    expect(render('> [!TIP]\n> **body**')).toContain('<p><strong>body</strong></p>');
    expect(render('> [!TIP]  \n> **body**')).toContain('<p><strong>body</strong></p>');
    expect(render('> [!TIP]\\\n> **body**')).toContain('<p><strong>body</strong></p>');
  });

  it('supports marker-only quotes without empty body paragraphs', () => {
    expect(render('> [!NOTE]')).toContain(
      '<blockquote data-callout="note">\n<p class="yca-callout-title">NOTE</p>\n</blockquote>',
    );
    expect(render('> [!NOTE]\n>\n> next')).toContain('</p>\n<p>next</p>');
  });

  it('preserves nested quotes, lists, links and nested callouts', () => {
    const html = render('> [!NOTE]\n>\n> > [!WARNING]\n> > Danger\n>\n> - [link](https://example.com)\n>\n> > ordinary quote');
    expect(html).toContain('<blockquote data-callout="note">');
    expect(html).toContain('<blockquote data-callout="warning">');
    expect(html).toContain('<p>Danger</p>');
    expect(html).toContain('<li>\n<p><a href="https://example.com" target="_blank" rel="noopener noreferrer">link</a></p>\n</li>');
    expect(html).toContain('<blockquote>\n<p>ordinary quote</p>\n</blockquote>');
    expect(render('> > [!TIP]\n> > nested')).toContain('<blockquote>\n<blockquote data-callout="tip">');
  });

  it.each([
    '> [!UNKNOWN]\n> body', '> [!note]\n> body', '> [!Note]\n> body',
    '> [!NOTE] same line', '> [!NOTE]**same line**', '> [!NOTE]`same line`',
    '> intro [!NOTE]\n> body', '> **[!NOTE]**\n> body', '> `[!NOTE]`\n> body',
    '> first paragraph\n>\n> [!NOTE]\n> body',
    '> [!NOT', '> [!NOTE', '> [!',
    '[!NOTE]\nbody',
  ])('leaves non-callout or partial input literal: %s', content => {
    const html = render(content);
    expect(html).not.toContain('data-callout');
    expect(html).not.toContain('yca-callout-title');
    expect(html).toContain('[!');
  });

  it('does not transform callout markers in fenced or indented code', () => {
    for (const content of ['```\n> [!NOTE]\n```', '    > [!NOTE]', '> ```\n> [!NOTE]\n> ```']) {
      const html = render(content);
      expect(html).not.toContain('data-callout');
      expect(html).toContain('[!NOTE]');
      expect(html).toContain('<pre class="yca-code-block">');
    }
  });

  it('retains default raw HTML and URL safety and escapes code', () => {
    const html = render('<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n[x](javascript:alert%281%29)\n\n[good](https://example.com)\n\n`<b>&`\n\n```unknown\n<script>&\n```');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('<img');
    expect(html).not.toContain('href="javascript:');
    expect(html).toContain('&lt;script&gt;');
    expect(html).toContain('<code>&lt;b&gt;&amp;</code>');
    expect(html).toContain('<a href="https://example.com" target="_blank" rel="noopener noreferrer">good</a>');
  });

  it('renders GFM tables, tasks, strikethrough and autolinks', () => {
    const html = render('| A | B |\n| - | - |\n| 1 | 2 |\n\n- [x] done\n- [ ] next\n\n~~old~~ https://example.com');
    expect(html).toContain('<table>');
    expect(html).toContain('<th>A</th>');
    expect(html).toContain('type="checkbox"');
    expect(html).toContain('checked=""');
    expect(html).toContain('<del>old</del>');
    expect(html).toContain('href="https://example.com"');
  });

  it('keeps inline code ordinary and emits exactly one pre/code for each block', () => {
    const html = render('Inline `java`\n\n```java\npublic class X {}\n```\n\n```\nplain\n```\n\n    indented');
    expect(html).toContain('<p>Inline <code>java</code></p>');
    expect(html.match(/<pre\b/g)).toHaveLength(3);
    expect(html.match(/<code\b/g)).toHaveLength(4);
    expect(html).not.toMatch(/<pre[^>]*>\s*<pre/);
    expect(html).toContain('class="token keyword"');
    expect(html).toContain('<pre class="yca-code-block"><code>plain\n</code></pre>');
    expect(html).toContain('<pre class="yca-code-block"><code>indented\n</code></pre>');
  });

  it.each(['c++', 'java-extra', 'typescript.foo', 'unknown'])('extracts the whole %s identifier, never a prefix', language => {
    const html = render(`\`\`\`${language}\npublic class X {}\n\`\`\``);
    expect(html).toContain(`<code class="language-${language}">public class X {}\n</code>`);
    expect(html).not.toContain('<span');
  });

  it.each(['java', 'markdown', 'asciidoc', 'md', 'adoc'])('passes %s fences through the real highlighter', language => {
    const code = language === 'java' ? 'public class X {}' : language === 'asciidoc' || language === 'adoc' ? '= Heading' : '# Heading';
    const html = render(`\`\`\`${language}\n${code}\n\`\`\``);
    expect(html).toContain('class="token ');
    expect(html).toContain(`class="language-${language}"`);
  });

  it('preserves the HAST code text including leading, trailing and final whitespace', () => {
    const html = render('```unknown\n\n\t  text  \n\n```');
    expect(html).toContain('<code class="language-unknown">\n\t  text  \n\n</code>');
    expect(render('```unknown\ntext')).toContain('<code class="language-unknown">text\n</code>');
  });
});
