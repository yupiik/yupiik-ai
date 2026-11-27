// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Block } from '../../api/eventLog';
import { ToolCard } from '../../components/ToolCard';
import { fileChangePatch } from '../../lib/fileChanges';
import { DiffViewer } from './DiffViewer';

let host: HTMLDivElement;
let root: Root;
let resize: (width: number) => void;
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 1024 } as DOMRect);
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: ResizeObserverCallback) {
      resize = width => callback([{ contentRect: { width } } as ResizeObserverEntry], this as unknown as ResizeObserver);
    }
    observe() {}
    disconnect() {}
  });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function render(props: React.ComponentProps<typeof DiffViewer>) {
  await act(async () => {
    root.render(<DiffViewer {...props} />);
    // Keep the real lazy chunk's resolution inside act (no renderer mock).
    if (props.mode !== 'simple') {
      await import('./SplitDiff');
    }
  });
}
async function split() {
  await vi.waitFor(async () => {
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(host.querySelector('[data-mode="side-by-side"] table')).not.toBeNull();
  });
  return host.querySelector('[data-mode="side-by-side"]')!;
}
const patch = 'diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ -1 +1 @@\n-old\n+new\n';

describe('DiffViewer modes and literal fallback', () => {
  it('honors the literal simple renderer', async () => {
    const mode = 'simple';
    await render({ text: patch, mode, 'aria-label': 'Captured diff', className: 'custom' });
    const pre = host.querySelector('pre')!;
    expect(pre.textContent).toBe(patch);
    expect(pre.getAttribute('aria-label')).toBe('Captured diff');
    expect(pre.classList.contains('custom')).toBe(true);
    expect(host.querySelector('table')).toBeNull();
    expect(pre.querySelector('[data-type="add"]')?.textContent).toBe('+++ b/file\n');
    expect([...pre.querySelectorAll('[data-type="del"]')].some((line) => line.textContent === '-old\n')).toBe(true);
  });

  it.each([undefined, 'auto', 'side-by-side'] as const)('renders actual split cells and line numbers in %s mode', async (mode) => {
    await render({ text: patch, mode, 'aria-label': 'Captured diff', className: 'custom' });
    const view = await split();
    expect(view.getAttribute('aria-label')).toBe('Captured diff');
    expect(view.classList.contains('custom')).toBe(true);
    expect(view.querySelector('.diff-code-delete')?.textContent).toBe('old');
    expect(view.querySelector('.diff-code-insert')?.textContent).toBe('new');
    expect(view.querySelector('.diff-gutter-delete')?.textContent).toBe('1');
    expect(view.querySelector('.diff-gutter-insert')?.textContent).toBe('1');
    expect(host.querySelector('pre')).toBeNull();
  });

  it.each([undefined, 'auto', 'side-by-side'] as const)('responds to container width and safely falls back when narrow (mode %s)', async (mode) => {
    await render({ text: patch, mode });
    await split();
    act(() => resize(500));
    expect(host.querySelector('table')).toBeNull();
    expect(host.querySelector('pre')?.textContent).toBe(patch);
    act(() => resize(1024));
    await split();
  });

  it.each([
    ['paged continuation', patch, false],
    ['missing header', '@@ -1 +1 @@\n-old\n+new\n', true],
    ['incomplete hunk', '--- a/file\n+++ b/file\n@@ -1,2 +1,2 @@\n-old\n+new\n', true],
    ['invalid hunk', 'diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ nonsense @@\n+new\n', true],
    ['unsafe line coordinates', 'diff --git a/file b/file\n--- a/file\n+++ b/file\n@@ -9007199254740993 +9007199254740993 @@\n-old\n+new\n', true],
    ['metadata only', 'diff --git a/file b/file\n--- a/file\n+++ b/file\n', true],
    ['character limit', patch + 'x'.repeat(100_001), true],
    ['line limit', patch + '\n'.repeat(2000), true],
  ] as const)('preserves %s verbatim instead of fabricating a split diff', async (_name, text, startsAtLine) => {
    await render({ text, startsAtLine, mode: 'side-by-side' });
    // Flush lazy loading too: an immediate Suspense fallback alone is not proof.
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 20)); });
    expect(host.querySelector('pre')?.textContent).toBe(text);
    expect(host.querySelector('table')).toBeNull();
  });

  it('does not invent a deletion indicator on the first continued line', async () => {
    await render({ text: '-continued\r\n+added\n\\ No newline at end of file', startsAtLine: false });
    const lines = host.querySelectorAll('[data-slot="diff-viewer-line"]');
    expect(lines[0].getAttribute('data-type')).toBe('normal');
    expect(lines[0].querySelector('[data-slot="diff-viewer-indicator"]')?.textContent).toBe('');
    expect(lines[1].getAttribute('data-type')).toBe('add');
    expect(lines[2].getAttribute('data-type')).toBe('normal');
    expect(host.querySelector('pre')?.textContent).toBe('-continued\r\n+added\n\\ No newline at end of file');
  });

  it.each(['-before\r\n+after\r\n', '-before\n+after', '+\n', ''])('preserves exact simple-mode whitespace and final newline state: %j', async (text) => {
    await render({ text });
    expect(host.querySelector('pre')?.textContent).toBe(text);
  });

  it('switches between modes without retaining stale split content', async () => {
    await render({ text: patch, mode: 'auto' });
    await split();
    await render({ text: patch, mode: 'simple' });
    expect(host.querySelector('pre')?.textContent).toBe(patch);
    expect(host.querySelector('table')).toBeNull();
    await render({ text: patch, mode: 'side-by-side' });
    await split();
    await render({ text: 'partial text', mode: 'auto' });
    expect(host.querySelector('pre')?.textContent).toBe('partial text');
    expect(host.querySelector('table')).toBeNull();
  });

  it('measures a previously hidden container instead of relying on the viewport width', async () => {
    vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({ width: 0 } as DOMRect);
    await render({ text: patch, mode: 'side-by-side' });
    expect(host.querySelector('table')).toBeNull();
    act(() => resize(1000));
    await split();
    act(() => resize(600));
    expect(host.querySelector('pre')?.textContent).toBe(patch);
  });

  it.each(['auto', 'side-by-side'] as const)('renders OpenCode patch bodies without inventing file coordinates in %s mode', async (mode) => {
    const text = '*** Begin Patch\n*** Update File: Example.java\n@@ class Example\n context\n-first\n-second\n+one\n+two\n@@\n--- literal removed content\n+++ literal added content\n*** End of File\n*** End Patch';
    await render({ text, mode });
    const view = await split();
    expect(view.textContent).toContain('Snippet preview; file line numbers unavailable.');
    expect(view.textContent).toContain('@@ class Example');
    expect(view.textContent).toContain('End of file anchor.');
    expect(view.querySelector('.diff-gutter')).toBeNull();
    expect([...view.querySelectorAll('.diff-code-delete')].map(node => node.textContent)).toEqual(['first', 'second', '-- literal removed content']);
    expect([...view.querySelectorAll('.diff-code-insert')].map(node => node.textContent)).toEqual(['one', 'two', '++ literal added content']);
    const paired = view.querySelector('.diff-code-delete')!.closest('tr')!;
    expect(paired.querySelector('.diff-code-insert')?.textContent).toBe('one');
    act(() => resize(500));
    expect(host.querySelector('pre')?.textContent).toBe(text);
    act(() => resize(1000));
    await split();
  });

  it.each([
    '@@ context\n-old\n+new\nunsupported syntax',
    '@@ context\n\\ No newline at end of string\n-old\n+new',
    '@@ context\n-old\n+new\n*** End of File\n+unexpected',
    '@@ context\n context only',
    '-old\n+new\n' + 'x'.repeat(100_001),
    '-old\n+new\n' + ' context\n'.repeat(2000),
  ])('keeps unsupported or unbounded snippet input literal: %.40s', async (text) => {
    await render({ text, mode: 'side-by-side', unnumbered: true });
    await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
    expect(host.querySelector('pre')?.textContent).toBe(text);
    expect(host.querySelector('table')).toBeNull();
  });

  it.each([
    { text: '+\tnew  \n', added: '\tnew  ', removed: undefined },
    { text: '-\told  \n', added: undefined, removed: '\told  ' },
    // react-diff-view renders a space placeholder to keep empty code cells visible.
    { text: '-\n+\n', added: ' ', removed: ' ' },
    { text: '@@\r\n-old\r\n+new\r\n', added: 'new', removed: 'old' },
  ])('renders bounded insertion/deletion/blank snippets without coordinates: $text', async ({ text, added, removed }) => {
    await render({ text, mode: 'side-by-side', unnumbered: true });
    const view = await split();
    expect(view.querySelector('.diff-code-insert')?.textContent).toBe(added);
    expect(view.querySelector('.diff-code-delete')?.textContent).toBe(removed);
    expect(view.querySelector('.diff-gutter')).toBeNull();
  });
});

describe('real SplitDiff content and syntax', () => {
  it.each([undefined, '', 'class Existing {}\n'])('renders high-coordinate sparse patches without unbounded highlighting (source %j)', async (oldSource) => {
    const text = 'diff --git a/Example.java b/Example.java\n--- a/Example.java\n+++ b/Example.java\n@@ -10000 +10000 @@\n-class Before {}\n+class After {}\n';
    await render({ text, mode: 'side-by-side', path: 'Example.java', oldSource });
    const view = await split();
    expect(view.querySelector('.diff-code-delete')?.textContent).toBe('class Before {}');
    expect(view.querySelector('.diff-code-insert')?.textContent).toBe('class After {}');
    expect(view.querySelector('.diff-gutter-delete')?.textContent).toBe('10000');
    expect(view.querySelector('.token')).toBeNull();
  });

  it.each([
    ['Example.java', 'class Example { int value = 1; }\n', 'class Example { int value = 2; }\n', '.token.keyword', 'class'],
    ['example.js', 'const value = 1;\n', 'const value = 2;\n', '.token.keyword', 'const'],
    ['example.json', '{"value":1}\n', '{"value":2}\n', '.token.property', '"value"'],
    ['example.xml', '<root>old</root>\n', '<root>new</root>\n', '.token.tag', 'root'],
  ])('highlights %s using captured source without changing code text', async (path, oldText, newText, selector, token) => {
    const text = fileChangePatch({ path, kind: 'update', oldText, newText })!;
    await render({ text, mode: 'side-by-side', path, oldSource: oldText });
    const view = await split();
    expect(view.querySelector('.diff-code-delete')?.textContent).toBe(oldText.trimEnd());
    expect(view.querySelector('.diff-code-insert')?.textContent).toBe(newText.trimEnd());
    expect([...view.querySelectorAll(selector)].some((node) => node.textContent?.includes(token))).toBe(true);
  });

  it.each(['simple', 'auto', 'side-by-side'] as const)('escapes HTML in %s mode', async (mode) => {
    const oldText = '<script>alert("old")</script>\n';
    const newText = '<img src=x onerror="alert(1)">\n';
    const text = fileChangePatch({ path: '<svg onload=alert(1)>', kind: 'update', oldText, newText })!;
    await render({ text, mode, path: 'example.xml', oldSource: oldText });
    if (mode !== 'simple') {
      await split();
    }
    expect(host.textContent).toContain(oldText.trimEnd());
    expect(host.textContent).toContain(newText.trimEnd());
    expect(host.querySelector('script, img, svg, [onerror], [onload]')).toBeNull();
  });

  it.each([
    { kind: 'create' as const, oldText: null, newText: 'created\n', added: 'created', removed: undefined },
    { kind: 'delete' as const, oldText: 'deleted\n', newText: '', added: undefined, removed: 'deleted' },
    { kind: 'update' as const, oldText: 'before', newText: 'after', added: 'after', removed: 'before' },
    { kind: 'update' as const, oldText: 'same', newText: 'same\n', added: 'same', removed: 'same' },
    { kind: 'update' as const, oldText: 'same\n', newText: 'same', added: 'same', removed: 'same' },
    { kind: 'update' as const, oldText: '\tbefore  \n', newText: '\tafter  \n', added: '\tafter  ', removed: '\tbefore  ' },
  ])('renders $kind captures including newline edge cases: $newText', async ({ kind, oldText, newText, added, removed }) => {
    const text = fileChangePatch({ path: 'file.txt', kind, oldText, newText })!;
    await render({ text, mode: 'auto', path: 'file.txt', oldSource: oldText ?? '' });
    const view = await split();
    expect(view.querySelector('.diff-code-insert')?.textContent).toBe(added);
    expect(view.querySelector('.diff-code-delete')?.textContent).toBe(removed);
    expect(view.textContent?.includes('Before: no final newline.')).toBe(oldText !== null && oldText !== '' && !oldText.endsWith('\n'));
    expect(view.textContent?.includes('After: no final newline.')).toBe(newText !== '' && !newText.endsWith('\n'));
  });

  it('accepts a complete non-git unified patch and unknown language', async () => {
    await render({ text: patch.slice(patch.indexOf('--- ')), mode: 'auto', path: 'file.unknown' });
    const view = await split();
    expect(view.querySelector('.diff-code-delete')?.textContent).toBe('old');
    expect(view.querySelector('.diff-code-insert')?.textContent).toBe('new');
  });

  it.each([
    { path: 'empty', kind: 'create' as const, oldText: null, newText: '' },
    { path: 'empty', kind: 'delete' as const, oldText: '', newText: '' },
    { path: 'unchanged', kind: 'update' as const, oldText: 'same\n', newText: 'same\n' },
  ])('keeps a hunkless $kind patch literal rather than fabricating changed rows', async (file) => {
    const text = fileChangePatch(file)!;
    expect(text).toBeDefined();
    await render({ text, mode: 'auto' });
    expect(host.querySelector('pre')?.textContent).toBe(text);
    expect(host.querySelector('table')).toBeNull();
  });
});

describe('ToolCard captured diff integration', () => {
  const block: Extract<Block, { kind: 'tool' }> = {
    kind: 'tool', name: 'write_file', state: 'done', content: '', output: 'written', success: true,
    args: JSON.stringify({ path: 'Requested.java', content: 'class Requested {}\n' }),
    files: [{ path: 'Actual.java', kind: 'update', oldText: 'class Before {}\n', newText: 'class After {}\n' }],
  };

  it('defaults to auto and renders actual captures separately from request intent', async () => {
    await act(async () => {
      root.render(<ToolCard block={block} collapsed={false} onToggle={() => {}} />);
      await import('./SplitDiff');
    });
    const view = await split();
    expect(view.querySelector('.diff-code-delete')?.textContent).toBe('class Before {}');
    expect(view.querySelector('.diff-code-insert')?.textContent).toBe('class After {}');
    expect(view.getAttribute('aria-label')).toBe('Applied diff for Actual.java');
    expect(view.textContent).not.toContain('Requested');
    expect(host.textContent).toContain('Requested full file content');
    expect(host.textContent).toContain('Captured during execution, not inferred from the request');
    expect(view.querySelector('.token.keyword')?.textContent).toBe('class');
  });

  it('keeps auto diffs mounted while request and result details follow the controlled disclosure', async () => {
    await act(async () => {
      root.render(<ToolCard block={block} collapsed onToggle={() => {}} />);
      await import('./SplitDiff');
    });
    await split();
    expect(host.querySelector('[aria-label="Applied file changes"]')).not.toBeNull();
    expect(host.querySelector('[aria-label="Tool request"], [aria-label="Tool result"], details')).toBeNull();
    expect(host.textContent).toContain('class Before');
    await act(async () => { root.render(<ToolCard block={block} collapsed={false} onToggle={() => {}} />); });
    await split();
    await act(async () => { root.render(<ToolCard block={block} collapsed onToggle={() => {}} />); });
    await split();
    expect(host.querySelector('[aria-label="Tool request"], [aria-label="Tool result"], details')).toBeNull();
  });

  it('honors explicit simple mode for applied captures', async () => {
    await act(async () => { root.render(<ToolCard block={block} collapsed={false} onToggle={() => {}} diffMode="simple" />); });
    const view = host.querySelector('[aria-label="Applied diff for Actual.java"]');
    expect(view?.tagName).toBe('PRE');
    expect(view?.textContent).toBe(fileChangePatch(block.files![0]));
    expect(host.querySelector('table')).toBeNull();
  });

  it.each(['auto', 'simple', 'side-by-side'] as const)('reveals only applied diffs with collapsed details in %s mode', async (diffMode) => {
    await act(async () => {
      root.render(<ToolCard block={block} collapsed onToggle={() => {}} diffMode={diffMode} />);
      await import('./SplitDiff');
    });
    if (diffMode !== 'simple') {
      await split();
    }
    expect(host.querySelector('[aria-label="Applied file changes"]')).not.toBeNull();
    expect(host.textContent).toContain('class Before');
    expect(host.textContent).toContain('class After');
    expect(host.querySelector('[aria-label="Tool request"], [aria-label="Tool result"], details')).toBeNull();
    expect(host.textContent).not.toContain('class Requested');
    expect(host.textContent).not.toContain('written');
    expect(host.querySelector('button[aria-expanded]')?.getAttribute('aria-expanded')).toBe('false');
  });

  it.each(['auto', 'simple', 'side-by-side'] as const)('keeps replacement intent separate from results in collapsed %s mode', async (diffMode) => {
    await act(async () => {
      root.render(<ToolCard block={{
        kind: 'tool', name: 'edit_file', state: 'done', content: '', output: 'PRIVATE_RESULT', success: false,
        args: JSON.stringify({ path: 'Example.java', oldString: '\told  \n', newString: '\tnew  ', replaceAll: true }),
      }} collapsed onToggle={() => {}} diffMode={diffMode} />);
      await import('./SplitDiff');
    });
    if (diffMode !== 'simple') {
      const view = await split();
      expect(view.querySelector('.diff-code-delete')?.textContent).toBe('\told  ');
      expect(view.querySelector('.diff-code-insert')?.textContent).toBe('\tnew  ');
      expect(view.querySelector('.diff-gutter')).toBeNull();
      expect(view.textContent).toContain('After: no final newline.');
    }
    const preview = host.querySelector('[aria-label="Requested replacement"]');
    expect(preview?.textContent).toContain('all occurrences');
    expect(preview?.textContent).toContain('not applied file changes or file line numbers');
    if (diffMode === 'simple') {
      expect(preview?.textContent).toContain('-\told  \n+\tnew  \n\\ No newline at end of string');
    }
    expect(host.textContent).not.toContain('PRIVATE_RESULT');
    expect(host.querySelector('[aria-label="Tool request"], [aria-label="Tool result"], details')).toBeNull();
  });

  it.each(['auto', 'side-by-side'] as const)('keeps OpenCode request intent split and separately labeled in %s mode', async (diffMode) => {
    const patch = '*** Begin Patch\n*** Add File: Example.java\n+class Added {}\n*** Update File: Other.java\n@@ class Existing\n-class Before {}\n+class After {}\n*** End Patch';
    await act(async () => {
      root.render(<ToolCard block={{ kind: 'tool', name: 'apply_patch', state: 'done', success: false, content: 'PRIVATE_RESULT', args: JSON.stringify({ patch }) }} collapsed onToggle={() => {}} diffMode={diffMode} />);
      await import('./SplitDiff');
    });
    await split();
    await vi.waitFor(() => expect(host.querySelectorAll('table')).toHaveLength(2));
    expect(host.querySelector('[aria-label="Requested patch for Example.java"] .diff-code-insert')?.textContent).toBe('class Added {}');
    expect(host.querySelector('[aria-label="Requested patch for Other.java"] .diff-code-delete')?.textContent).toBe('class Before {}');
    expect(host.querySelector('.diff-gutter')).toBeNull();
    expect(host.querySelector('.token.keyword')?.textContent).toBe('class');
    expect(host.textContent).toContain('Requested patch, not an applied diff.');
    expect(host.textContent).not.toContain('PRIVATE_RESULT');
    expect(host.querySelector('[aria-label="Applied file changes"]')).toBeNull();
  });

  it('renders metadata-only and hostile paths as text without inventing an empty-file diff', async () => {
    const path = '../../<img src=x onerror=alert(1)>\n+++ b/forged';
    await act(async () => {
      root.render(<ToolCard block={{ ...block, files: [{ path, kind: 'create' }] }} collapsed={false} onToggle={() => {}} />);
    });
    const changes = host.querySelector('[aria-label="Applied file changes"]')!;
    expect(changes.querySelector('h5')?.textContent).toBe(`create: ${path}`);
    expect(changes.textContent).toContain('before/after text was not captured');
    expect(changes.querySelector('[data-slot="diff-viewer"]')).toBeNull();
    expect(host.querySelector('img, [onerror]')).toBeNull();
  });

  it('shows bounded captured text rather than a split diff when a capture is too large', async () => {
    const oldText = 'before\n' + 'a'.repeat(50_000);
    const newText = 'after\n' + 'b'.repeat(50_000);
    await act(async () => {
      root.render(<ToolCard block={{ ...block, success: false, files: [{ path: 'large.txt', kind: 'update', oldText, newText }] }} collapsed={false} onToggle={() => {}} />);
    });
    const changes = host.querySelector('[aria-label="Applied file changes"]')!;
    expect(changes.textContent).toContain('Diff preview unavailable or too large');
    expect(changes.querySelector('[aria-label="Captured before"]')?.textContent).toContain('before\n');
    expect(changes.querySelector('[aria-label="Captured after"]')?.textContent).toContain('after\n');
    expect(changes.querySelector('table')).toBeNull();
    expect(host.textContent).toContain('Tool failed');
    expect(changes.textContent).toContain('Partial changes can remain even if the tool failed');
  });

  it('keeps non-diff captured text collapsed when the diff preview is unavailable', async () => {
    const files = [{ path: 'large.txt', kind: 'update' as const, oldText: 'PRIVATE_BEFORE' + 'a'.repeat(100_001), newText: 'PRIVATE_AFTER' }];
    await act(async () => {
      root.render(<ToolCard block={{ ...block, files }} collapsed onToggle={() => {}} diffMode="simple" />);
    });
    expect(host.querySelector('[aria-label="Applied file changes"]')?.textContent).toContain('Expand tool details');
    expect(host.textContent).not.toContain('PRIVATE_BEFORE');
    expect(host.textContent).not.toContain('PRIVATE_AFTER');
    expect(host.querySelector('[aria-label="Captured before"], [aria-label="Captured after"]')).toBeNull();
  });
});
