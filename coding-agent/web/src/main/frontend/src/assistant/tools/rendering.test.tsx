// @vitest-environment happy-dom
import React, { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToolCard, ProgressiveText } from '../../components/ToolCard';
import { MarkdownContent } from '../../components/MarkdownContent';
import { CodeBlock } from '../../components/CodeBlock';
import type { Block } from '../../api/eventLog';

type ToolBlock = Extract<Block, { kind: 'tool' }>;
let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
});
function render(node: React.ReactNode) {
  act(() => root.render(node));
}
function button(label: string): HTMLButtonElement {
  const found = host.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);
  if (!found) {
    throw new Error(`Missing button ${label}`);
  }
  return found;
}
function click(element: HTMLElement) {
  act(() => element.click());
}
function raw(label: string): HTMLDetailsElement {
  const found = [...host.querySelectorAll('details')].find((element) => element.querySelector('summary')?.textContent === label);
  if (!found) {
    throw new Error(`Missing disclosure ${label}`);
  }
  return found;
}
function open(details: HTMLDetailsElement) {
  act(() => {
    details.open = true;
    details.dispatchEvent(new Event('toggle'));
  });
}

describe('standalone assistant-ui rendering integration', () => {
  it('shows captured partial changes after failure, metadata-only files, and bounded file pagination', () => {
    const files = Array.from({ length: 7 }, (_, index) => ({ path: `file${index}.java`, kind: 'update' as const,
      ...(index === 1 ? {} : { oldText: 'before\n', newText: 'after\n' }) }));
    const block: ToolBlock = { kind: 'tool', name: 'apply_patch', state: 'done', success: false, output: 'Partial failure', content: '', files };
    render(<ToolCard block={block} collapsed onToggle={() => {}} diffMode="auto" />);
    expect(host.querySelector('[aria-label="Applied file changes"]')).not.toBeNull();
    render(<ToolCard block={block} collapsed={false} onToggle={() => {}} diffMode="simple" />);
    const changes = host.querySelector('[aria-label="Applied file changes"]')!;
    expect(changes.querySelectorAll('.yca-tool-patch-file')).toHaveLength(5);
    expect(changes.textContent).toContain('Partial changes can remain even if the tool failed');
    expect(changes.textContent).toContain('before/after text was not captured');
    expect(changes.querySelector('pre')?.textContent).toContain('-before\n+after');
    click([...changes.querySelectorAll('button')].find(element => element.textContent === 'More changed files')!);
    expect(changes.querySelectorAll('.yca-tool-patch-file')).toHaveLength(2);
    expect(changes.textContent).toContain('file6.java');
    click([...changes.querySelectorAll('button')].find(element => element.textContent === 'Previous changed files')!);
    expect(changes.textContent).toContain('file0.java');
  });

  it('keeps oversized captures available through exact before/after pages instead of computing an unbounded diff', () => {
    const before = 'b'.repeat(100_001) + '\r\nBEFORE_END';
    const after = '<script>unsafe()</script>\r\nAFTER_END';
    const block: ToolBlock = { kind: 'tool', name: 'edit_file', state: 'done', success: true, content: '',
      files: [{ path: 'large.txt', kind: 'update', oldText: before, newText: after }] };
    render(<ToolCard block={block} collapsed={false} onToggle={() => {}} />);
    expect(host.querySelector('.diff-split')).toBeNull();
    const beforeRegion = host.querySelector('[aria-label="Captured before"]')!;
    let recovered = beforeRegion.textContent ?? '';
    const more = button('Show more Captured before');
    while (!more.disabled) {
      click(more);
      recovered += beforeRegion.textContent;
    }
    expect(recovered).toBe(before);
    expect(host.querySelector('[aria-label="Captured after"]')?.textContent).toBe(after);
    expect(host.querySelector('script')).toBeNull();
  });

  it.each([false, true])('renders successful final skills as Markdown with separate optional front matter (%s)', (withFrontMatter) => {
    const metadata = 'name: example\r\ndescription: "**literal**"\r\n';
    const body = '\r\n# Skill instructions\r\n\r\nUse **care** and `tools`.\r\n\r\n- First step\r\n\r\n```java\r\nclass Skill {}\r\n```\r\n';
    const output = (withFrontMatter ? `---\r\n${metadata}---\r\n` : '') + body;
    const block: ToolBlock = { kind: 'tool', name: 'skill', state: 'done', success: true, content: '# Stale stream', output, args: '{"name":"example"}' };
    render(<ToolCard block={block} collapsed={false} onToggle={() => {}} />);
    const result = host.querySelector('[aria-label="Tool result"]')!;
    expect(result.querySelector('h1')?.textContent).toBe('Skill instructions');
    expect(result.querySelector('strong')?.textContent).toBe('care');
    expect(result.querySelector('li')?.textContent).toBe('First step');
    expect(result.querySelector('.aui-md')?.getAttribute('data-variant')).toBe('document');
    expect(result.querySelector('code.language-java')?.textContent).toContain('class Skill {}');
    expect(result.textContent).not.toContain('Stale stream');
    const frontMatter = result.querySelector('[aria-label="Skill front matter"]');
    if (withFrontMatter) {
      expect(frontMatter?.querySelector('code')?.textContent).toBe(metadata);
      expect(frontMatter?.querySelector('strong')).toBeNull();
      expect(result.querySelector('.aui-md')?.textContent).not.toContain('description:');
    } else {
      expect(frontMatter).toBeNull();
    }
    const details = raw('Raw output');
    expect(details.querySelector('code')).toBeNull();
    open(details);
    expect(details.querySelector('code')?.textContent).toBe(output);
  });

  it.each([
    { state: 'done', success: false },
    { state: 'done', success: undefined },
    { state: 'running', success: true },
    { state: 'interrupted', success: true },
    { state: 'done', success: true, pending: true },
    { state: 'done', success: true, name: 'mcp.skill' },
  ] satisfies Partial<ToolBlock>[])('does not render unsuccessful, unfinished or external skill results as Markdown: %j', (props) => {
    const output = '---\nname: example\n---\n# Not a document\n\n**Failure or preview**';
    const block: ToolBlock = { kind: 'tool', name: 'skill', content: output, ...props };
    render(<ToolCard block={block} collapsed={false} onToggle={() => {}} />);
    const result = host.querySelector('[aria-label="Tool result"]')!;
    expect(result.querySelector('.aui-md')).toBeNull();
    expect(result.querySelector('[aria-label="Skill front matter"]')).toBeNull();
    expect(result.querySelector('code')?.textContent).toBe(output);
    if (props.success === false) {
      expect(result.textContent).toContain('Tool failed.');
    }
    const details = raw('Raw output');
    open(details);
    expect(details.querySelector('code')?.textContent).toBe(output);
  });

  it('updates a streamed skill to its final document and respects authoritative empty output', () => {
    const block: ToolBlock = { kind: 'tool', name: 'skill', state: 'running', content: '# Stream preview' };
    render(<ToolCard block={block} collapsed={false} onToggle={() => {}} />);
    expect(host.querySelector('.aui-md')).toBeNull();
    render(<ToolCard block={{ ...block, state: 'done', success: true, output: '# Final body' }} collapsed={false} onToggle={() => {}} />);
    expect(host.querySelector('h1')?.textContent).toBe('Final body');
    render(<ToolCard block={{ ...block, state: 'done', success: true, output: '' }} collapsed={false} onToggle={() => {}} />);
    expect(host.querySelector('.aui-md')).toBeNull();
    expect(host.textContent).toContain('Empty output.');
    expect(host.textContent).not.toContain('Stream preview');
  });

  it('keeps ToolCard controlled, lazy and standalone and cleans up upstream scroll anchoring', () => {
    vi.useFakeTimers();
    const onToggle = vi.fn();
    const block: ToolBlock = { kind: 'tool', name: 'external', state: 'done', success: true, content: '', args: '{"secret":"value"}' };
    host.style.overflowY = 'auto';
    host.style.scrollbarWidth = 'thin';
    host.scrollTop = 73;
    render(<ToolCard block={block} collapsed onToggle={onToggle} />);
    const trigger = host.querySelector<HTMLButtonElement>('.yca-tool-header')!;
    expect(host.querySelector('[aria-label="Tool request"]')).toBeNull();
    click(trigger);
    expect(onToggle).toHaveBeenCalledTimes(1);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(host.style.scrollbarWidth).toBe('none');
    host.scrollTop = 10;
    host.dispatchEvent(new Event('scroll'));
    expect(host.scrollTop).toBe(73);
    render(<ToolCard block={block} collapsed={false} onToggle={onToggle} />);
    expect(host.querySelector('[aria-label="Tool request"]')).not.toBeNull();
    expect(raw('Raw arguments').querySelector('code')).toBeNull();
    render(null);
    expect(host.style.scrollbarWidth).toBe('thin');
  });

  it('exposes exact raw arguments and output including the final character through bounded pages', () => {
    const args = '  {"content":"' + 'x'.repeat(8100) + '"} \r\n\t';
    const output = ' \t\r\n' + 'o'.repeat(8100) + '<script>TAIL</script>  \n';
    const block: ToolBlock = { kind: 'tool', name: 'external', state: 'done', content: output, args };
    render(<ToolCard block={block} collapsed={false} onToggle={() => {}} />);
    for (const [label, expected] of [['Raw arguments', args], ['Raw output', output]]) {
      const details = raw(label);
      expect(details.querySelector('code')).toBeNull();
      open(details);
      let recovered = '';
      const more = button(`Show more ${label}`);
      for (let page = 0; page < 3; page++) {
        const value = details.querySelector('code')?.textContent ?? '';
        expect(value.length).toBeLessThanOrEqual(4000);
        recovered += value;
        if (!more.disabled) {
          click(more);
        }
      }
      expect(recovered).toBe(expected);
      expect(more.disabled).toBe(true);
      expect(details.querySelector('script')).toBeNull();
      click(button(`Show previous ${label}`));
      expect(details.querySelector('code')?.textContent).toBe(expected.slice(4000, 8000));
    }
  });

  it('does not style a page boundary inside a diff line as a new addition', () => {
    const text = 'x'.repeat(4000) + '+continuation\r\n+actual addition\n\\ No newline at end of file';
    render(<ProgressiveText text={text} label="Patch" diff />);
    click(button('Show more Patch'));
    const code = host.querySelector('code')!;
    expect(code.textContent).toBe(text.slice(4000));
    expect(code.firstElementChild?.getAttribute('data-type')).toBe('normal');
    expect(code.querySelectorAll('.yca-diff-add')).toHaveLength(1);
    expect(code.querySelector('.yca-diff-add')?.textContent).toBe('+actual addition\n');
  });

  it('updates standalone Markdown provider text and syntax without retaining stale code', () => {
    render(<MarkdownContent content={'```java\nclass Old {}\n```'} variant="assistant" />);
    expect(host.querySelector('.token.keyword')?.textContent).toBe('class');
    render(<MarkdownContent content={'```unknown\n\n\tnew  \n\n```'} variant="document" />);
    expect(host.querySelector('code')?.textContent).toBe('\n\tnew  \n\n');
    expect(host.textContent).not.toContain('Old');
    expect(host.querySelectorAll('pre')).toHaveLength(1);
    expect(host.querySelector('.aui-md')?.getAttribute('data-variant')).toBe('document');
    expect(host.querySelector('.token')).toBeNull();
  });

  it('updates the factory-supplied code/pre tags without losing aliases or whitespace', () => {
    render(<CodeBlock code={'class X {}\r\n'} language="java" className="first" />);
    render(<CodeBlock code={'# Heading\n\n'} language="md" className="second" />);
    expect(host.querySelector('pre')?.className).toBe('yca-code-block second');
    expect(host.querySelector('code')?.className).toBe('language-md');
    expect(host.querySelector('code')?.textContent).toBe('# Heading\n\n');
    expect(host.querySelector('.token')).not.toBeNull();
  });

  it('keeps patch file paging reachable through the reused request slot', () => {
    const patch = '*** Begin Patch\n' + Array.from({ length: 11 }, (_, index) => `*** Add File: file${index}\n+content${index}\n`).join('') + '*** End Patch';
    function Fixture() {
      const [collapsed, setCollapsed] = useState(true);
      return <ToolCard block={{ kind: 'tool', name: 'apply_patch', state: 'done', success: false, content: 'Rejected', args: JSON.stringify({ patch }) }} collapsed={collapsed} onToggle={() => setCollapsed((value) => !value)} />;
    }
    render(<Fixture />);
    click(host.querySelector('.yca-tool-header')!);
    for (let page = 0; page < 2; page++) {
      const next = [...host.querySelectorAll('button')].find((element) => element.textContent === 'Show more files')!;
      click(next);
    }
    expect(host.textContent).toContain('file10');
    expect(host.querySelectorAll('.yca-tool-patch-file')).toHaveLength(1);
    expect(host.textContent).toContain('not an applied diff');
    expect(host.textContent).toContain('Tool failed.');
  });
});
