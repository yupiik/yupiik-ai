import { applyPatch, parsePatch } from 'diff';
import { describe, expect, it } from 'vitest';
import { canRenderDiff, fileChangePatch, parseFileChanges, type FileChange } from './fileChanges';

describe('file change captures', () => {
  it.each([undefined, null, {}, 'files', 42])('ignores a non-array payload: %j', (value) => {
    expect(parseFileChanges(value)).toEqual([]);
  });

  it('rejects malformed entries while retaining valid metadata in order', () => {
    expect(parseFileChanges([
      null, false, 'file', {}, { path: 3, kind: 'update' }, { path: 'x', kind: 'rename' },
      { path: 'created', kind: 'create', oldText: null, newText: '' },
      { path: 'updated', kind: 'update', oldText: '', newText: 'new', ignored: 'extra' },
      { path: 'deleted', kind: 'delete', oldText: 'old', newText: '' },
    ])).toEqual([
      { path: 'created', kind: 'create', oldText: null, newText: '' },
      { path: 'updated', kind: 'update', oldText: '', newText: 'new' },
      { path: 'deleted', kind: 'delete', oldText: 'old', newText: '' },
    ]);
  });

  it.each([
    { path: 'x', kind: 'create' },
    { path: 'x', kind: 'create', oldText: '', newText: 'new' },
    { path: 'x', kind: 'update', oldText: null, newText: 'new' },
    { path: 'x', kind: 'update', oldText: 'old' },
    { path: 'x', kind: 'delete', oldText: 'old', newText: null },
    { path: 'x', kind: 'update', oldText: 42, newText: ['new'] },
  ])('keeps metadata without fabricating incomplete captures: %j', (value) => {
    const files = parseFileChanges([value]);
    expect(files).toEqual([{ path: value.path, kind: value.kind }]);
    expect(fileChangePatch(files[0])).toBeUndefined();
  });
});

describe('bounded captured file patches', () => {
  it.each<FileChange>([
    { path: 'file', kind: 'create', oldText: null, newText: 'created\n' },
    { path: 'file', kind: 'delete', oldText: 'deleted\n', newText: '' },
    { path: 'file', kind: 'update', oldText: 'old\n', newText: 'new\n' },
    { path: 'file', kind: 'update', oldText: '', newText: 'first' },
    { path: 'file', kind: 'update', oldText: 'last', newText: '' },
    { path: 'file', kind: 'update', oldText: 'old', newText: 'new' },
    { path: 'file', kind: 'update', oldText: 'same', newText: 'same\n' },
    { path: 'file', kind: 'update', oldText: 'same\n', newText: 'same' },
    { path: 'file', kind: 'update', oldText: '\told  \r\n\r\n', newText: '\tnew  \r\n\r\n' },
  ])('round trips captures without losing whitespace or newline state: %j', (file) => {
    const patch = fileChangePatch(file)!;
    expect(patch.startsWith('diff --git a/file b/file\n')).toBe(true);
    expect(applyPatch(file.oldText ?? '', patch)).toBe(file.newText);
    const [parsed] = parsePatch(patch);
    expect(parsed.oldFileName).toBe(file.kind === 'create' ? '/dev/null' : 'a/file');
    expect(parsed.newFileName).toBe(file.kind === 'delete' ? '/dev/null' : 'b/file');
  });

  it.each(['../../outside', 'a file\t"quoted".java', 'x\n--- a/injected\n+++ b/injected\n@@ -1 +1 @@', '<img src=x onerror=alert(1)>'])('never interpolates hostile paths into patch syntax: %j', (path) => {
    const [file] = parseFileChanges([{ path, kind: 'update', oldText: 'before\n', newText: 'after\n' }]);
    expect(file.path).toBe(path);
    const patch = fileChangePatch(file)!;
    expect(patch).not.toContain(path);
    expect(parsePatch(patch)).toHaveLength(1);
    expect(applyPatch('before\n', patch)).toBe('after\n');
  });

  it('bounds both characters and lines inclusively', () => {
    expect(canRenderDiff('x'.repeat(100_000))).toBe(true);
    expect(canRenderDiff('x'.repeat(100_001))).toBe(false);
    expect(canRenderDiff('\n'.repeat(1999))).toBe(true);
    expect(canRenderDiff('\n'.repeat(2000))).toBe(false);
  });

  it('bounds the combined captured content, not just either side', () => {
    expect(fileChangePatch({ path: 'x', kind: 'update', oldText: 'a'.repeat(50_001), newText: 'b'.repeat(50_000) })).toBeUndefined();
    expect(fileChangePatch({ path: 'x', kind: 'update', oldText: 'a\n'.repeat(1000), newText: 'b\n'.repeat(1000) })).toBeUndefined();
  });
});
