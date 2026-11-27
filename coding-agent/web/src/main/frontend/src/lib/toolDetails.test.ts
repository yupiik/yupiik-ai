import { describe, expect, it } from 'vitest';
import { BUILTIN_FIELDS, displayValue, isBuiltin, MAX_STRUCTURED_ARGS, parsePatch, parseToolArgs, presentToolOutput, summarizeToolArgs, textPage } from './toolDetails';

// Deliberately independent inventory: registry omissions must not silently remove test cases.
const requests = {
  bash: { command: 'printf hello', workdir: '/tmp', timeout_seconds: 3 },
  read: { path: 'x.ts', offset: 17, limit: 20 },
  glob: { pattern: '**/*.ts', path: '.', hidden: true, limit: 7 },
  grep: { pattern: 'x()', path: ['a', 'b'], literal: true, ignoreCase: false, include: '*.ts', limit: 2, maxResults: 3 },
  edit_file: { path: 'a', oldString: '\t old  \n', newString: '', replaceAll: true },
  write_file: { path: 'a', content: '  new\n' },
  apply_patch: { patch: '*** Begin Patch\n*** Add File: a\n+hello\n*** End Patch' },
  webfetch: { url: 'https://example.org', format: 'html', timeout_seconds: 5 },
  websearch: { query: 'test', num_results: 3 },
  write_todo: { todos: [{ content: 'test it', status: 'pending' }] },
  ask_user: { questions: [{ id: 'q', prompt: 'Continue?', options: ['yes'], multiple: false }] },
  skill: { name: 'testing' },
  spawn_subagent: { task: 'test', name: 'tester', agent: 'build', model: 'm', max_turns: 2, tools: ['read'] },
  check_subagents: { action: 'collect', subagent_id: 'child', max_wait_ms: '1000' },
  list_subagent_options: { kind: 'models', query: 'm', provider: 'p', offset: 1, limit: 3 },
  compress: { action: 'compress', topic: 'test', content: [{ startId: '1', endId: '5', summary: 'done' }], blockId: 'b1', focus: 'test', keep_last: 2 },
  memory_write: { fact: 'test', scope: 'project', keep: false, p: 2, topic: 'tests', path: 'src' },
  maven_knowledge_content: { gav: 'g:a:1', file: 'api' },
  maven_repository_knowledge: { operation: 'index', gavs: ['g:a:1'], force: true },
  code_resolve: { op: 'resolve', symbol: 'a.B', relation: 'uses', artifact: 'g:a:1', depth: 2, max: 3 },
  code_trace_calls: { symbol: 'a.B', direction: 'both', edgeTypes: 'calls', depth: 1, max: 2 },
  code_find_code: { query: 'Demo', kind: 'TYPE', max: 4 },
  code_check_graph: {},
  read_symbol: { symbol: 'a.B', context: 5 },
};

describe('tool request models', () => {
  it('covers precisely the 24 builtins and their actual schema keys', () => {
    expect(Object.keys(BUILTIN_FIELDS).sort()).toEqual(Object.keys(requests).sort());
    expect(Object.keys(requests)).toHaveLength(24);
    for (const [name, args] of Object.entries(requests)) {
      expect(isBuiltin(name)).toBe(true);
      expect([...BUILTIN_FIELDS[name as keyof typeof requests]].sort()).toEqual(Object.keys(args).sort());
      expect(parseToolArgs(JSON.stringify(args))).toEqual(args);
    }
    for (const name of ['constructor', '__proto__', 'functions.edit_file', 'web_fetch', 'other']) {
      expect(isBuiltin(name)).toBe(false);
      expect(summarizeToolArgs(name, '{"command":"not a command"}')).toBe('');
    }
  });

  it.each([undefined, '', '{', 'null', '[]', '"text"', '42', '{"x":'])('handles unavailable/non-object/malformed args %s', (raw) => {
    expect(parseToolArgs(raw)).toBeNull();
    expect(summarizeToolArgs('bash', raw)).toBe('');
  });

  it('bounds structured work while retaining raw source for paging', () => {
    const raw = JSON.stringify({ command: 'x'.repeat(MAX_STRUCTURED_ARGS) });
    expect(parseToolArgs(raw)).toBeNull();
    expect(summarizeToolArgs('bash', raw)).toBe('');
    const deep = '{"x":'.repeat(10000) + '0' + '}'.repeat(10000);
    expect(displayValue(parseToolArgs(deep))).toContain('raw arguments');
  });

  it('summarizes real fields, retaining false, ranges, actions, and array counts', () => {
    expect(summarizeToolArgs('read', JSON.stringify(requests.read))).toBe('path: x.ts | offset: 17 | limit: 20');
    expect(summarizeToolArgs('edit_file', JSON.stringify(requests.edit_file))).toContain('all occurrences | delete requested text');
    expect(summarizeToolArgs('maven_repository_knowledge', JSON.stringify(requests.maven_repository_knowledge))).toContain('gavs: 1 items');
    expect(summarizeToolArgs('check_subagents', JSON.stringify(requests.check_subagents))).toContain('subagent_id: child');
    expect(summarizeToolArgs('ask_user', JSON.stringify(requests.ask_user))).toBe('1 questions requested');
    expect(summarizeToolArgs('grep', JSON.stringify(requests.grep))).toContain('ignoreCase: false');
    expect(summarizeToolArgs('bash', JSON.stringify({ command: 'x'.repeat(50000) }))).toHaveLength(169);
  });

  it('offers bounded windows through the entire source, including the tail', () => {
    const source = '  \t\n' + 'x'.repeat(23000) + 'TAIL';
    let restored = '';
    for (let page = 0; page < Math.ceil(source.length / 4000); page++) {
      const window = textPage(source, page);
      expect(window.text.length).toBeLessThanOrEqual(4000);
      restored += window.text;
    }
    expect(restored).toBe(source);
    expect(textPage(source, 999).text).toMatch(/TAIL$/);
    expect(textPage('', 0)).toEqual({ text: '', start: 0, end: 0, more: false });
  });
});

describe('output presentation', () => {
  it.each(['external', 'code_resolve', 'bash', 'read'])('pretty prints validated objects/arrays for %s but preserves raw exactly', (name) => {
    for (const raw of [' \r\n{ "x": [1, true], "text": "  \\t" }\t ', '\t[1,{"a":2}]\n']) {
      const result = presentToolOutput(name, raw, '{"command":"node","path":"Foo.java"}');
      expect(result).toEqual({ raw, text: JSON.stringify(JSON.parse(raw), null, 2), language: 'json' });
    }
  });

  it.each(['plain output  \r\n\t', '{broken}', 'null', 'true', '42', '"string"', 'Here is JSON: {"x":1}', '- a\n+ b', '--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n-a\n+b'])('keeps ambiguous external output exact and plain: %j', (raw) => {
    expect(presentToolOutput('external', raw, '{"path":"Foo.java","command":"python3"}')).toEqual({ raw, text: raw });
  });

  it.each([
    '--- a/f\n+++ b/f\n@@ -1 +1 @@\n-old  \n+new\t\n',
    'diff --git a/f b/f\nindex 123..456 100644\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\n+b',
    '*** Begin Patch\n*** Update File: f\n@@ context\n-old\n+new\n*** End Patch',
    '*** Begin Patch\r\n*** Add File: f\r\n+new\r\n*** End Patch\r\n',
  ])('recognizes actual diffs without modifying raw or presented text', (raw) => {
    expect(presentToolOutput('external', raw)).toEqual({ raw, text: raw, diff: true });
  });

  it('bounds JSON/diff inspection and tolerates extreme nesting and pretty expansion', () => {
    for (const raw of [JSON.stringify({ value: 'x'.repeat(MAX_STRUCTURED_ARGS) }), '['.repeat(10000) + '0' + ']'.repeat(10000), JSON.stringify(Array(200000).fill(0))]) {
      expect(presentToolOutput('external', raw)).toEqual({ raw, text: raw });
    }
  });

  it('only accepts builtin-specific output hints', () => {
    const raw = 'ordinary output';
    const args = '{"path":"Foo.java","command":"python3","format":"html"}';
    expect(presentToolOutput('read', raw, args).language).toBe('java');
    expect(presentToolOutput('bash', raw, args).language).toBe('python');
    for (const name of ['external', 'mcp.read', 'write_file', 'edit_file', 'code_resolve']) {
      expect(presentToolOutput(name, raw, args)).toEqual({ raw, text: raw });
    }
    expect(presentToolOutput('skill', raw).language).toBe('markdown');
    expect(presentToolOutput('webfetch', raw, args).language).toBe('markup');
    expect(presentToolOutput('webfetch', raw, '{"format":"markdown"}').language).toBe('markdown');
    expect(presentToolOutput('webfetch', raw, '{"format":"text","path":"Foo.java"}').language).toBeUndefined();
  });
});

describe('requested patch previews', () => {
  it('reads unified updates/additions/deletions/moves and ignores hunk stat suffixes', () => {
    const patch = '--- a/a\n+++ b/a\n@@ -1,2 +1,2 @@ (+1 -1)\n keep \n-\told  \n+\tnew  \n--- /dev/null\n+++ b/new\n@@ -0,0 +1 @@\n+new\n--- a/deleted\n+++ /dev/null\n@@ -1 +0,0 @@\n-gone\n--- a/before\n+++ b/after\n@@ -1 +1 @@\n-a\n+b';
    const preview = parsePatch(patch);
    expect(preview.warning).toBeUndefined();
    expect(preview.files.map(({ path, operation, added, removed, destination }) => ({ path, operation, added, removed, destination }))).toEqual([
      { path: 'a', operation: 'update', added: 1, removed: 1 },
      { path: 'new', operation: 'add', added: 1, removed: 0 },
      { path: 'deleted', operation: 'delete', added: 0, removed: 1 },
      { path: 'before', operation: 'move', destination: 'after', added: 1, removed: 1 },
    ]);
    expect(preview.files[0].diff).toContain(' keep \n-\told  \n+\tnew  ');
    expect(preview.files.every(file => !file.unnumbered)).toBe(true);
  });

  it('does not mistake unified hunk content for another file header', () => {
    const preview = parsePatch('--- a/a\n+++ b/a\n@@ -1 +1 @@\n--- not a header\n+++ not a header');
    expect(preview.files).toHaveLength(1);
    expect(preview.files[0]).toMatchObject({ added: 1, removed: 1 });
  });

  it('supports git unified metadata between files and warns about incomplete hunks', () => {
    const preview = parsePatch('diff --git a/a b/a\nindex 123..456 100644\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-a\n+b\ndiff --git a/b b/b\nindex 123..456 100644\n--- a/b\n+++ b/b\n@@ -1 +1 @@\n-c\n+d');
    expect(preview.files).toHaveLength(2);
    expect(preview.warning).toBeUndefined();
    expect(preview.files[0].diff).not.toContain('diff --git');
    expect(parsePatch('--- a/a\n+++ b/a\n@@ -1,2 +1,2 @@\n-a\n+b').warning).toBeDefined();
  });

  it('reads OpenCode add/delete/update/move, context, EOF and multiple hunks', () => {
    const patch = '*** Begin Patch\n*** Add File: new\n+\tfirst  \n+\n*** Delete File: gone\n*** Update File: old\n*** Move to: moved\n@@ class A (+2 -2)\n context\n-before\n+after\n@@\n-\t\n+ \n*** End of File\n*** End Patch';
    const preview = parsePatch(patch);
    expect(preview.warning).toBeUndefined();
    expect(preview.files).toHaveLength(3);
    expect(preview.files.every(file => file.unnumbered)).toBe(true);
    expect(preview.files[0]).toMatchObject({ operation: 'add', added: 2, removed: 0 });
    expect(preview.files[1]).toMatchObject({ operation: 'delete', added: 0, removed: 0 });
    expect(preview.files[2]).toMatchObject({ operation: 'move', destination: 'moved', added: 2, removed: 2 });
    expect(preview.files[2].diff).toContain('@@ class A (+2 -2)\n context\n-before\n+after');
    expect(preview.files[2].diff).toContain('*** End of File');
  });

  it('handles CRLF and no-space OpenCode headers', () => {
    const preview = parsePatch('*** Begin Patch\r\n*** Update File:a\r\n@@\r\n-a\r\n+b\r\n*** End Patch\r\n');
    expect(preview.files[0]).toMatchObject({ path: 'a', operation: 'update', added: 1, removed: 1 });
    expect(preview.files[0].diff).toContain('-a\r\n+b\r');
  });

  it.each(['', 'nonsense', '--- a/a\n+++ b/a\n@@ malformed\n+x'])('warns for unsupported or incomplete syntax', (patch) => {
    expect(parsePatch(patch).warning).toBeDefined();
  });

  it('does not attempt an unbounded structured patch preview', () => {
    expect(parsePatch('x'.repeat(MAX_STRUCTURED_ARGS + 1))).toMatchObject({ files: [], warning: expect.any(String) });
  });
});
