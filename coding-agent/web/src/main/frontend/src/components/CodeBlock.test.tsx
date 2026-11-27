import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { CodeBlock } from './CodeBlock';

function render(code: string, language?: string, className?: string): string {
  return renderToStaticMarkup(<CodeBlock code={code} language={language} className={className} />);
}

function text(html: string): string {
  return html.replace(/<[^>]*>/g, '')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#x27;/g, "'").replace(/&amp;/g, '&');
}

describe('CodeBlock', () => {
  it.each([
    ['java', 'public class Demo {}', 'keyword'],
    ['markdown', '# Heading\n**bold**', 'title'],
    ['asciidoc', '= Heading\n\n*bold*', 'title'],
  ])('highlights %s with CSS token classes and one pre/code', (language, code, token) => {
    const html = render(code, language, 'extra');
    expect(html).toMatch(/<pre class="[^"]*yca-code-block extra"/);
    expect(html.match(/<pre\b/g)).toHaveLength(1);
    expect(html.match(/<code\b/g)).toHaveLength(1);
    expect(html).toContain(`class="language-${language}"`);
    expect(html).toMatch(new RegExp(`class="token [^"]*${token}`));
    expect(html).not.toContain('color:');
    expect(html).not.toContain('background-color:');
    expect(text(html)).toBe(code);
  });

  it.each([
    ['ts', 'const value: number = 1;'],
    ['typescript', 'const value: number = 1;'],
    ['js', 'const value = 1;'],
    ['javascript', 'const value = 1;'],
    ['py', 'def run():\n  return True'],
    ['python', 'def run():\n  return True'],
    ['sh', 'echo "$HOME"'], ['shell', 'echo "$HOME"'], ['bash', 'echo "$HOME"'],
    ['sql', 'SELECT * FROM users;'],
    ['markup', '<tag/>'], ['xml', '<tag/>'], ['html', '<div/>'], ['svg', '<svg/>'],
    ['json', '{"value":true}'], ['yaml', 'value: true'],
    ['md', '# Heading'], ['adoc', '= Heading'],
  ])('supports the existing %s alias', (language, code) => {
    const html = render(code, language);
    expect(html).toContain('class="token ');
    expect(text(html)).toBe(code);
  });

  it.each([undefined, 'c++', 'java-extra', 'typescript.foo', 'unknown', 'constructor', 'toString']) (
    'renders unsupported %s as plain escaped pre/code without partial grammar matching', language => {
      const code = '  public class X {}\n<script>&\t\n\n';
      const html = render(code, language, 'extra');
      expect(html).toContain('<pre class="yca-code-block extra"><code');
      expect(html).not.toContain('<span');
      expect(html).not.toContain('<script>');
      expect(text(html)).toBe(code);
    },
  );

  it.each(['java', 'md', 'adoc', 'unknown', undefined])('preserves all whitespace for %s', language => {
    for (const code of ['', '\n', '\n\n', '\t  \n  public class X {}  \n\n', 'no final newline', 'a\r\nb\r\n']) {
      expect(text(render(code, language))).toBe(code);
    }
  });
});
