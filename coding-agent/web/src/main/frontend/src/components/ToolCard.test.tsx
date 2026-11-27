import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ToolCard, ProgressiveText } from './ToolCard';
import { BUILTIN_FIELDS } from '../lib/toolDetails';
import { eventsToBlocks, type Block } from '../api/eventLog';
import type { WebEvent } from '../api/useSessionStream';

type ToolBlock = Extract<Block, { kind: 'tool' }>;
const base: ToolBlock = { kind: 'tool', name: 'edit_file', content: '', state: 'done', success: true };
function render(block: Partial<ToolBlock> = {}, collapsed = false): string {
  return renderToStaticMarkup(<ToolCard block={{ ...base, ...block }} collapsed={collapsed} onToggle={() => {}} />);
}
function plain(html: string): string {
  return html.replace(/<[^>]*>/g, '').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&#x27;/g, "'").replace(/&amp;/g, '&');
}

describe('ToolCard request/result presentation', () => {
  it.each(Object.entries(BUILTIN_FIELDS))('renders %s schema fields and separate result', (name, fields) => {
    const args = Object.fromEntries(fields.map((key) => [key, key === 'patch' ? '*** Begin Patch\n*** Add File: file\n+new\n*** End Patch' : `value-${key}`]));
    const html = render({ name, args: JSON.stringify(args), output: 'actual result from backend' });
    expect(html).toContain('aria-label="Tool request"');
    expect(html).toContain('aria-label="Tool result"');
    expect(plain(html)).toContain('actual result from backend');
    for (const field of fields) {
      expect(html).toContain(`<dt>${field}</dt>`);
    }
    expect(html).toContain('<summary>Raw arguments</summary>');
    expect(html).toContain('<summary>Raw output</summary>');
  });

  it.each(['  before  \n\t\n', '\n', '\r\n\t  ', 'without final newline'])('preserves exact replacement whitespace %j', (value) => {
    const html = render({ args: JSON.stringify({ path: 'a', oldString: value, newString: value, replaceAll: false }), output: 'Replaced 1' });
    const code = [...html.matchAll(/<code[^>]*>([\s\S]*?)<\/code>/g)].map((match) => plain(match[1]));
    expect(code.filter((text) => text === value)).toHaveLength(2);
    expect(plain(html)).toContain('one unique match');
    expect(plain(html)).toContain('no file context or applied line numbers are inferred');
  });

  it('shows deletion and replaceAll without inventing file context', () => {
    const html = render({ args: JSON.stringify({ path: 'a', oldString: 'old', newString: '', replaceAll: true }), success: false, output: 'No match' });
    expect(plain(html)).toContain('all occurrences');
    expect(plain(html)).toContain('Replacement: delete matched text');
    expect(plain(html)).toContain('Empty string (0 characters)');
    expect(plain(html)).toContain('Tool failed.');
    expect(plain(html)).toContain('No match');
    expect(html).not.toContain('@@');
  });

  it('does not fabricate a diff for write_file', () => {
    const html = render({ name: 'write_file', args: JSON.stringify({ path: 'a', content: '\tnew  \n' }), state: 'running' });
    expect(plain(html)).toContain('Requested full file content');
    expect(plain(html)).toContain('\tnew  \n');
    expect(html).not.toContain('yca-diff-add');
    expect(html).toContain('Result preview');
  });

  it.each([
    ['Foo.java', 'java', '\tpublic class Foo {}  \r\n'],
    ['README.adoc', 'asciidoc', '= Title\n\n  text  \n'],
    ['README.md', 'markdown', '# Title\n\n  **text**  \n'],
  ])('highlights builtin write/edit snippets for %s without changing their text', (path, language, value) => {
    for (const name of ['write_file', 'edit_file']) {
      const args = name === 'write_file' ? { path, content: value } : { path, oldString: value, newString: value };
      const html = render({ name, args: JSON.stringify(args) });
      const code = [...html.matchAll(/<code[^>]*class="language-([^"]+)"[^>]*>([\s\S]*?)<\/code>/g)]
        .filter((match) => match[1] === language).map((match) => plain(match[2]));
      expect(code).toEqual(name === 'write_file' ? [value] : [value, value]);
      expect(html).toContain('token');
      expect(html).not.toContain('yca-diff-add');
    }
  });

  it('highlights only builtin bash request commands as bash', () => {
    expect(render({ name: 'bash', args: '{"command":"printf hello"}' })).toContain('language-bash');
    expect(render({ name: 'external', args: '{"command":"printf hello","path":"Foo.java","content":"class Foo {}"}' })).not.toContain('language-bash');
  });

  it.each([
    { path: 'a', oldString: 'a', newString: '', replaceAll: 'false' },
    { path: 'a', oldString: 'a', newString: '', replaceAll: null },
    { path: 'a', oldString: [], newString: '', replaceAll: true },
    { path: 'a', oldString: 'a', newString: false },
  ])('does not infer replacement intent for malformed snippet types: %j', (args) => {
    const html = plain(render({ args: JSON.stringify(args) }));
    expect(html).toContain('no replacement intent inferred');
    for (const claim of ['unique match', 'all occurrences', 'delete requested text', 'Exact text to find', 'Replacement: delete']) {
      expect(html).not.toContain(claim);
    }
  });

  it('renders external JSON/diff/plain outputs from content alone', () => {
    const args = '{"path":"Foo.java","command":"python3"}';
    const json = render({ name: 'external', args, output: ' {"answer":[42]} ' });
    expect(json).toContain('language-json');
    expect(plain(json)).toContain('{\n  "answer": [\n    42\n  ]\n}');
    const diff = render({ name: 'external', args, output: '--- a/f\n+++ b/f\n@@ -1 +1 @@\n-old\n+new' });
    expect(diff).toContain('yca-tool-diff');
    expect(diff).toContain('yca-diff-remove');
    expect(diff).toContain('yca-diff-add');
    const text = render({ name: 'external', args, output: '  ordinary output\t\n' });
    expect(plain(text)).toContain('  ordinary output\t\n');
    expect(text).not.toContain('language-java');
    expect(text).not.toContain('language-python');
    expect(text).not.toContain('yca-tool-diff');
  });

  it.each(['html', 'markdown', 'text'])('renders fetched %s as safe code, not HTML or markdown UI', (format) => {
    const html = render({ name: 'webfetch', args: JSON.stringify({ format }), output: '<script>evil()</script>\n# Heading\n[link](javascript:evil)' });
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('<a ');
    expect(plain(html)).toContain('<script>evil()</script>');
    expect(html).toContain('<code');
    if (format !== 'text') {
      expect(html).toContain(`language-${format === 'html' ? 'markup' : 'markdown'}`);
    }
  });

  it.each([
    '--- a/file\n+++ b/file\n@@ -1,2 +1,2 @@\n context\n-old\n+new',
    '*** Begin Patch\n*** Update File: file\n@@ context\n context\n-old\n+new\n*** End Patch',
  ])('renders requested per-file patch counts, context and diff styles', (patch) => {
    const html = render({ name: 'apply_patch', args: JSON.stringify({ patch }), success: false, output: 'Rejected' });
    expect(plain(html)).toContain('update: file +1 -1');
    expect(plain(html)).toContain(' context\n-old\n+new');
    expect(html).toContain('yca-diff-add');
    expect(html).toContain('yca-diff-remove');
    expect(plain(html)).toContain('not an applied diff');
    expect(plain(html)).toContain('Rejected');
  });

  it('keeps external arguments conservative and escapes all untrusted content', () => {
    const html = render({ name: 'mcp.edit_file', args: JSON.stringify({ oldString: '<script>alert(1)</script>', newString: 'javascript:evil', patch: '<img src=x onerror=evil>' }), output: '<script>result</script>' });
    expect(plain(html)).toContain('oldString');
    expect(html).not.toContain('<script>');
    expect(html).not.toContain('<img');
    expect(html).not.toContain('<a ');
    expect(html).not.toContain('Exact text to find');
    expect(html).not.toContain('yca-diff-add');
  });

  it.each([undefined, '', 'null', '{broken', '[]', JSON.stringify({ content: 'x'.repeat(1_000_001) })])('handles unavailable/malformed/huge request', (args) => {
    const html = render({ args });
    expect(html).toContain(args === undefined ? 'Arguments unavailable' : 'Use raw arguments');
    expect(html.length).toBeLessThan(3000);
  });

  it('uses authoritative empty output rather than stale streamed text', () => {
    const html = render({ output: '', content: 'stale stream' });
    expect(html).toContain('Empty output.');
    expect(html).not.toContain('stale stream');
  });

  it('preserves whitespace-only stream previews and labels interruption/pending states', () => {
    for (const props of [{ state: 'running' as const }, { state: 'interrupted' as const }, { pending: true }]) {
      const html = render({ ...props, content: '  \t\n', success: false, args: '{"path":"file","oldString":"a","newString":"b"}' });
      expect(html).toContain('Result preview');
      expect(plain(html)).toContain('  \t\n');
      expect(html).not.toContain('yca-tool-failed');
      expect(html).toContain('Exact text to find');
    }
  });

  it('retains controlled collapse and accessible native controls without rendering heavy details', () => {
    const html = render({ args: '{"oldString":"SECRET"}' }, true);
    expect(html).toContain('aria-expanded="false"');
    expect(html).toMatch(/aria-controls="([^"]+)"[\s\S]*id="\1" hidden=""/);
    expect(html).not.toContain('SECRET');
    expect(html).not.toContain('Tool request');
    expect(render()).toContain('aria-expanded="true"');
  });

  it('bounds rendered text and exposes keyboard-accessible progressive controls', () => {
    const html = renderToStaticMarkup(<ProgressiveText text={'x'.repeat(20000) + 'TAIL'} label="Output" />);
    expect(html.length).toBeLessThan(5000);
    expect(html).toContain('Show more');
    expect(html).toContain('Characters 1-4000 of 20004');
    expect(html).toContain('aria-controls=');
    expect(html).not.toContain('TAIL');
  });

  it('bounds the file preview window and keeps large request/output tails reachable', () => {
    const patch = '*** Begin Patch\n' + Array.from({ length: 20 }, (_, index) => `*** Add File: file${index}\n+content${index}\n`).join('') + '*** End Patch';
    const html = render({ name: 'apply_patch', args: JSON.stringify({ patch }), output: 'x'.repeat(5000) + 'TAIL' });
    expect(html.match(/class="yca-tool-patch-file"/g)).toHaveLength(5);
    expect(html).toContain('Files 1-5 of 20');
    expect(html).toContain('Show more files');
    expect(html).toContain('Show more');
    expect(html).not.toContain('TAIL');
  });
});

describe('live/replay tool rendering fixtures', () => {
  it.each([false, true])('renders failed bash lifecycle consistently (live=%s)', (live) => {
    const events: WebEvent[] = [
      { type: 'tool-started', sessionId: 's', message: 'bash', data: { args: '{"command":"false","workdir":"/tmp"}', ordinal: 1 } },
      ...(live ? [{ type: 'tool-stream', sessionId: 's', message: 'bash', data: { fragment: 'old live output', ordinal: 1 } }] : []),
      { type: 'tool-finished', sessionId: 's', message: 'bash', data: { output: 'final\nExit code: 1', success: true, ordinal: 1 } },
    ];
    const original = structuredClone(events);
    const blocks = eventsToBlocks(events);
    expect(blocks).toHaveLength(1);
    const block = blocks[0];
    if (block.kind !== 'tool') {
      throw new Error('Expected tool block');
    }
    const html = render(block);
    expect(html).toContain('yca-tool-failed');
    expect(plain(html)).toContain('command: false');
    expect(plain(html)).toContain('Exit code: 1');
    expect(html).not.toContain('old live output');
    expect(block.success).toBe(true);
    expect(events).toEqual(original);
  });

  it('retains request previews after a replayed failed patch', () => {
    const blocks = eventsToBlocks([
      { type: 'tool-started', sessionId: 's', message: 'apply_patch', data: { args: JSON.stringify({ patch: '*** Begin Patch\n*** Delete File: file\n*** End Patch' }), ordinal: 1 } },
      { type: 'tool-finished', sessionId: 's', message: 'apply_patch', data: { output: 'File not found', success: false, ordinal: 1 } },
    ]);
    const block = blocks[0];
    if (block.kind !== 'tool') {
      throw new Error('Expected tool block');
    }
    expect(plain(render(block))).toContain('delete: file +0 -0');
    expect(plain(render(block))).toContain('File not found');
  });
});
