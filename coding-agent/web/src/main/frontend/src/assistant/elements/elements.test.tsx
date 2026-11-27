// @vitest-environment happy-dom
import React, { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentCard, AgentStatus, McpServerPanel, MemoryChips, SubagentActivity, SubagentList, ThreadSearch, TodoList } from './index';

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});
function render(node: React.ReactNode) { act(() => root.render(node)); }
function click(button: Element | null) { act(() => (button as HTMLButtonElement).click()); }
function key(input: Element, value: string) {
  act(() => input.dispatchEvent(new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true })));
}

describe('source-adapted Elements', () => {
  it('folds todos without hiding active/failed rows and counts cancelled separately', () => {
    render(<TodoList className="max-w-none" revision={4} maxVisible={1} items={[
      { id: '1', text: 'Finished', status: 'done' },
      { id: '2', text: 'Later', status: 'pending', description: 'Details' },
      { id: '3', text: 'Working', status: 'active' },
      { id: '4', text: 'Broken', status: 'failed', reason: 'Denied' },
      { id: '5', text: 'Abandoned', status: 'cancelled' },
    ]} />);
    expect(host.textContent).toContain('1/4 · rev 4');
    expect(host.querySelector('[data-slot="todo-list"]')?.className).not.toContain('max-w-sm');
    expect(host.querySelectorAll('li')).toHaveLength(3);
    expect(host.textContent).toContain('Working');
    expect(host.textContent).toContain('Denied');
    expect(host.textContent).not.toContain('Later');
    const button = host.querySelector('button')!;
    const list = host.querySelector('ul')!;
    expect(button.getAttribute('aria-controls')).toBe(list.id);
    click(button);
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelectorAll('li')).toHaveLength(5);
    expect(host.textContent).toContain('Details');
    click(button);
    expect(host.querySelectorAll('li')).toHaveLength(3);
  });

  it('renders independent subagent states without inventing progress', () => {
    render(<SubagentActivity agents={[
      { id: 'a', name: 'First', state: 'working', label: 'Inspecting', model: 'model-a', elapsed: '2s' },
      { id: 'b', name: 'Second', state: 'done', label: 'Complete', description: 'Raw result' },
      { id: 'c', name: 'Third', state: 'failed', label: 'Denied' },
      { id: 'd', name: 'Fourth', state: 'waiting', label: 'Approval' },
    ]} />);
    expect(host.querySelectorAll('[data-slot="agent-status"]')).toHaveLength(4);
    expect(host.textContent).toContain('model-a');
    expect(host.textContent).toContain('Raw result');
    expect(host.querySelector('[role="progressbar"]')).toBeNull();
    expect(host.textContent).toContain('waiting');
  });

  it('retains the upstream progress-driven subagent list with bounded progress and summary', () => {
    render(<SubagentList agents={[{ name: 'One', model: 'm' }, { name: 'Two', model: 'n' }]} completedCount={1} progress={[150, -3]} showSummary summaryAgent={{ name: 'Summary', model: 's' }} />);
    const bars = host.querySelectorAll('[role="progressbar"]');
    expect(bars).toHaveLength(3);
    expect(bars[0].getAttribute('aria-valuenow')).toBe('100');
    expect(bars[1].getAttribute('aria-valuenow')).toBe('0');
    expect(bars[2].hasAttribute('aria-valuenow')).toBe(false);
  });

  it('connects an upstream agent card and disables the connected action', () => {
    const onConnect = vi.fn();
    const props = { name: 'Agent', description: 'Description', provider: 'Provider', version: '1', model: 'Model', endpoint: '/endpoint', skills: [{ name: 'Search', description: 'Find files' }], onConnect };
    render(<AgentCard {...props} connected={false} />);
    click(host.querySelector('button'));
    expect(onConnect).toHaveBeenCalledOnce();
    render(<AgentCard {...props} connected />);
    expect(host.querySelector('button')?.disabled).toBe(true);
    expect(host.textContent).toContain('Connected');
    expect(host.textContent).toContain('Find files');
  });

  it('preserves status elapsed semantics and custom trailing content', () => {
    render(<AgentStatus state="working" label="Running" elapsed="3s" trailing={<span>custom</span>} />);
    expect(host.textContent).toContain('3s');
    expect(host.textContent).toContain('custom');
    render(<AgentStatus state="done" label="Done" elapsed="3s" />);
    expect(host.textContent).not.toContain('3s');
  });

  it('supports controlled MCP expansion, authorization, resets and unknown metadata', () => {
    const onReset = vi.fn();
    const onAuthorize = vi.fn();
    function Panel() {
      const [expandedId, setExpandedId] = useState<string>();
      return <McpServerPanel servers={[
        { id: 'one', name: 'One', status: 'needs-auth', transport: 'stdio', tools: ['read'] },
        { id: 'two', name: 'Two', status: 'unknown', stateLabel: 'STOPPED' },
      ]} expandedId={expandedId} onToggle={id => setExpandedId(current => current === id ? undefined : id)} onReset={onReset} onAuthorize={onAuthorize} />;
    }
    render(<Panel />);
    const toggles = host.querySelectorAll('button[aria-expanded]');
    click(toggles[0]);
    expect(toggles[0].getAttribute('aria-expanded')).toBe('true');
    expect(host.textContent).toContain('stdio');
    const authorize = [...host.querySelectorAll('button')].find(b => b.textContent === 'Authorize')!;
    click(authorize);
    expect(onAuthorize).toHaveBeenCalledExactlyOnceWith('one');
    click(host.querySelector('[aria-label="Reset Two"]'));
    expect(onReset).toHaveBeenCalledExactlyOnceWith('two');
    expect(toggles[1].textContent).toContain('STOPPED');
    expect(toggles[1].textContent).not.toContain('tools');
    click(toggles[0]);
    expect(host.textContent).not.toContain('stdio');
  });

  it('forgets only explicit chips and labels fresh entries', () => {
    const onForget = vi.fn();
    render(<MemoryChips chips={[{ id: 'known', text: 'Explicit fact', change: 'added' }]} onForget={onForget} />);
    expect(host.textContent).toContain('remembered 1');
    click(host.querySelector('[aria-label=\'Forget "Explicit fact"\']'));
    expect(onForget).toHaveBeenCalledExactlyOnceWith('known');
  });

  it('retains thread-search keyboard navigation, grouping, filtering and selection', () => {
    const onSelect = vi.fn();
    function Search() {
      const [active, setActive] = useState('');
      const [query, setQuery] = useState('');
      return <ThreadSearch threads={[
        { id: 'a', title: 'Alpha', group: '/one', preview: 'First' },
        { id: 'b', title: 'Beta', group: '/two', preview: 'Second' },
      ]} query={query} activeId={active} onActiveChange={setActive} onQueryChange={setQuery} onSelect={onSelect} />;
    }
    render(<Search />);
    const input = host.querySelector('input')!;
    key(input, 'ArrowDown');
    expect(host.querySelector('[aria-selected="true"]')?.textContent).toContain('Alpha');
    expect(input.getAttribute('aria-activedescendant')).toBe(host.querySelector('[aria-selected="true"]')?.id);
    key(input, 'ArrowUp');
    key(input, 'Enter');
    expect(onSelect).toHaveBeenCalledExactlyOnceWith('b');
    click(host.querySelector('[role="option"]'));
    expect(onSelect).toHaveBeenLastCalledWith('a');
    act(() => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, 'missing');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(host.querySelectorAll('[role="option"]')).toHaveLength(0);
    expect(host.textContent).toContain('No thread matches');
  });
});
