// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { call } from '../api/rpc';
import { McpPage } from './McpPage';
import { PageShell } from './PageShell';
import { MemoryPage } from './MemoryPage';
import { SessionsPage } from './SessionsPage';

vi.mock('../api/rpc', () => ({ call: vi.fn() }));
const rpc = vi.mocked(call);
let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  rpc.mockReset();
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
async function render(node: React.ReactNode) { await act(async () => root.render(node)); }
async function click(button: Element | null) { await act(async () => (button as HTMLButtonElement).click()); }
async function type(value: string) {
  const input = host.querySelector('input')!;
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

describe('McpPage', () => {
  it('keeps the shared page shell constrained unless full width is explicitly requested', async () => {
    await render(<PageShell title="Default page">Content</PageShell>);
    expect(host.firstElementChild?.classList.contains('max-w-5xl')).toBe(true);
    expect(host.firstElementChild?.classList.contains('max-w-none')).toBe(false);
  });

  it('uses the available page width and expands real connection details without invented URLs', async () => {
    const url = 'https://mcp.example.test:8443/custom%20path/mcp';
    rpc.mockResolvedValue([
      { name: 'remote', state: 'ENABLED', transport: 'http', url },
      { name: 'local', state: 'DISABLED', transport: 'stdio' },
      { name: 'yca', state: 'ENABLED', transport: 'in-process' },
      { name: 'unknown', state: 'NOT_STARTED' },
    ]);
    await render(<McpPage />);
    const panel = host.querySelector('[data-slot="mcp-server-panel"]')!;
    expect(panel.classList.contains('max-w-none')).toBe(true);
    expect(panel.parentElement?.classList.contains('w-full')).toBe(true);
    expect(panel.parentElement?.classList.contains('max-w-5xl')).toBe(false);
    expect(host.textContent).not.toContain(url);
    const row = (name: string) => [...host.querySelectorAll('button[aria-expanded]')].find(button => button.textContent?.includes(name))!;
    await click(row('remote'));
    expect(row('remote').getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelector('[aria-label="URL for remote"]')?.textContent).toBe(url);
    expect(host.textContent).toContain('Streamable HTTP');
    await click(row('remote'));
    expect(host.querySelector('[aria-label="URL for remote"]')).toBeNull();
    await click(row('local'));
    expect(host.textContent).toContain('Standard input/output (stdio; command, arguments and environment hidden)');
    expect(host.querySelector('[aria-label^="URL for"]')).toBeNull();
    await click(row('yca'));
    expect(host.textContent).toContain('Built-in (in-process)');
    expect(host.textContent).not.toContain('Standard input/output');
    await click(row('unknown'));
    expect(host.textContent).toContain('Connection details unavailable');
    expect(rpc).toHaveBeenCalledExactlyOnceWith('mcp.status', {});
  });

  it('maps backend states to colored status icons without miscounting enabled servers', async () => {
    const states = ['ENABLED', 'DISABLED', 'PENDING', 'NOT_STARTED', 'FAILED', 'RUNNING', 'FUTURE_STATE'];
    rpc.mockResolvedValue(states.map(state => ({ name: state.toLowerCase(), state })));
    await render(<McpPage />);
    expect(host.textContent).toContain('2 of 7 connected');
    const icons = [...host.querySelectorAll('[data-slot="mcp-status-icon"]')];
    expect(icons.map(icon => icon.getAttribute('class'))).toEqual([
      expect.stringContaining('text-[var(--yca-success)]'),
      expect.stringContaining('text-[var(--yca-muted)]'),
      expect.stringContaining('text-[var(--yca-warning)]'),
      expect.stringContaining('text-[var(--yca-muted)]'),
      expect.stringContaining('text-[var(--yca-error)]'),
      expect.stringContaining('text-[var(--yca-success)]'),
      expect.stringContaining('text-[var(--yca-muted)]'),
    ]);
    for (const state of states) { expect(host.textContent).toContain(state); }
    expect(icons[2].getAttribute('class')).toContain('animate-spin');
  });

  it('renders display-safe URLs as text and reports unavailable HTTP URLs', async () => {
    rpc.mockResolvedValue([
      { name: 'redacted', state: 'FAILED', transport: 'http', url: 'https://[redacted]@example.test/mcp?[redacted]#[redacted]' },
      { name: 'invalid', state: 'FAILED', transport: 'http' },
    ]);
    await render(<McpPage />);
    await click(host.querySelector('button[aria-expanded]'));
    expect(host.querySelector('[aria-label="URL for redacted"]')?.textContent).toBe('https://[redacted]@example.test/mcp?[redacted]#[redacted]');
    expect(host.querySelector('a')).toBeNull();
    await click([...host.querySelectorAll('button[aria-expanded]')][1]);
    expect(host.textContent).toContain('URL unavailable (invalid or unsafe configuration)');
  });

  it('preserves raw states and resets a named server or all servers, then reloads', async () => {
    rpc.mockImplementation(async method => {
      if (method === 'mcp.status') {
        return [{ name: 'running', state: 'RUNNING' }, { name: 'broken', state: 'FAILED' }, { name: 'stopped', state: 'STOPPED' }];
      }
      return {};
    });
    await render(<McpPage />);
    expect(host.querySelector('[data-slot="mcp-server-panel"]')).not.toBeNull();
    expect(host.textContent).toContain('1 of 3 connected');
    expect(host.textContent).toContain('STOPPED');
    expect(host.textContent).not.toContain('0 tools');
    await click(host.querySelector('[aria-label="Reset broken"]'));
    expect(rpc).toHaveBeenCalledWith('mcp.reset', { name: 'broken' });
    expect(rpc.mock.calls.filter(([method]) => method === 'mcp.status')).toHaveLength(2);
    await click([...host.querySelectorAll('button')].find(b => b.textContent === 'Reset all')!);
    expect(rpc).toHaveBeenCalledWith('mcp.reset', {});
    expect(rpc.mock.calls.filter(([method]) => method === 'mcp.status')).toHaveLength(3);
  });

  it('reports reset failures, disables duplicate actions while pending and allows retry', async () => {
    const reset = deferred<unknown>();
    rpc.mockImplementation(method => method === 'mcp.status' ? Promise.resolve([{ name: 'one', state: 'RUNNING' }]) : reset.promise);
    await render(<McpPage />);
    await click(host.querySelector('[aria-label="Reset one"]'));
    expect([...host.querySelectorAll('button')].filter(b => b.textContent?.includes('Reset')).every(b => b.disabled)).toBe(true);
    await act(async () => reset.reject(new Error('reset denied')));
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('reset denied');
    expect(host.querySelector<HTMLButtonElement>('[aria-label="Reset one"]')?.disabled).toBe(false);
  });

  it('reloads connection details after a reset and retains the expanded server', async () => {
    const reset = deferred<unknown>();
    rpc.mockResolvedValueOnce([{ name: 'remote', state: 'FAILED', transport: 'http', url: 'https://example.test/before' }]);
    await render(<McpPage />);
    await click(host.querySelector('button[aria-expanded]'));
    expect(host.textContent).toContain('https://example.test/before');
    rpc.mockReturnValueOnce(reset.promise);
    await click(host.querySelector('[aria-label="Reset remote"]'));
    expect(host.querySelector<HTMLButtonElement>('[aria-label="Reset remote"]')?.disabled).toBe(true);
    rpc.mockResolvedValueOnce([{ name: 'remote', state: 'ENABLED', transport: 'http', url: 'https://example.test/after' }]);
    await act(async () => reset.resolve({ reset: true }));
    expect(rpc.mock.calls).toEqual([['mcp.status', {}], ['mcp.reset', { name: 'remote' }], ['mcp.status', {}]]);
    expect(host.querySelector('button[aria-expanded]')?.getAttribute('aria-expanded')).toBe('true');
    expect(host.textContent).toContain('https://example.test/after');
    expect(host.textContent).not.toContain('https://example.test/before');
    expect(host.textContent).toContain('1 of 1 connected');
    expect(host.querySelector<HTMLButtonElement>('[aria-label="Reset remote"]')?.disabled).toBe(false);
  });

  it('distinguishes loading, empty servers and request failures', async () => {
    const result = deferred<unknown>();
    rpc.mockReturnValue(result.promise);
    await render(<McpPage />);
    expect(host.querySelector('[role="status"]')?.textContent).toContain('Loading');
    await act(async () => result.resolve([]));
    expect(host.textContent).toContain('No MCP servers.');
    act(() => root.unmount());
    root = createRoot(host);
    rpc.mockRejectedValue(new Error('offline'));
    await render(<McpPage />);
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('offline');
    expect(host.textContent).not.toContain('No MCP servers.');
  });
});

describe('MemoryPage', () => {
  it('shows full raw global/project memory and paths without fabricating chips or forget actions', async () => {
    const global = '# Unstructured\n\n- literal text\n```json\n{"key": true}\n```\n';
    const project = 'Project prose with <markup> & spaces\n  indented\n';
    rpc.mockResolvedValue({ global, project, globalPath: '/home/test/.yca/memory', projectPath: '/workspace/.yca/memory', globalExists: true, projectExists: true });
    await render(<MemoryPage />);
    expect(rpc).toHaveBeenCalledExactlyOnceWith('memory.get', {});
    expect(host.querySelector('[aria-label="Global raw memory"]')?.textContent).toBe(global);
    expect(host.querySelector('[aria-label="Project raw memory"]')?.textContent).toBe(project);
    expect(host.textContent).toContain('/home/test/.yca/memory');
    expect(host.textContent).toContain('/workspace/.yca/memory');
    const chips = [...host.querySelectorAll('[data-slot="memory-chips"]')];
    expect(chips).toHaveLength(2);
    expect(chips.every(chip => chip.textContent === 'Memory fileFile exists')).toBe(true);
    expect(host.querySelector('[aria-label^="Forget"]')).toBeNull();
    expect(host.textContent).not.toContain('remembered');
  });

  it('distinguishes empty from missing files', async () => {
    rpc.mockResolvedValue({ global: '', project: '', globalPath: '/global', projectPath: '/project', globalExists: true, projectExists: false });
    await render(<MemoryPage />);
    expect(host.querySelector('[aria-label="Global raw memory"]')?.textContent).toBe('(no facts yet)');
    expect(host.querySelector('[aria-label="Project raw memory"]')?.textContent).toBe('(file not found)');
  });

  it('shows loading and RPC errors instead of empty file contents', async () => {
    const result = deferred<unknown>();
    rpc.mockReturnValue(result.promise);
    await render(<MemoryPage />);
    expect(host.textContent).toContain('Loading');
    await act(async () => result.reject(new Error('memory unavailable')));
    expect(host.textContent).toContain('memory.get failed: memory unavailable');
    expect(host.querySelector('pre')).toBeNull();
  });
});

describe('SessionsPage', () => {
  const sessions = [
    { id: 'old', title: 'Older', modifiedAt: 1, sizeBytes: 10, project: '/alpha' },
    { id: 'other', title: 'Other', modifiedAt: 3, sizeBytes: 1024, project: '/beta' },
    { id: 'new', title: 'Newest', modifiedAt: 5, sizeBytes: 1048576, project: '/alpha' },
    { id: 'unknown', title: '', modifiedAt: 0, sizeBytes: 1 },
  ];
  function mockSessions() {
    rpc.mockImplementation(async method => {
      if (method === 'sessions.currentProject') { return { project: '/current' }; }
      if (method === 'sessions.create') { return { id: 'created' }; }
      return sessions;
    });
  }

  it('loads all projects once, groups/sorts by recency, shows sizes and opens/creates sessions', async () => {
    mockSessions();
    const openSession = vi.fn();
    await render(<SessionsPage openSession={openSession} />);
    expect(rpc.mock.calls.filter(([method]) => method === 'sessions.list')).toEqual([['sessions.list', { query: '', project: '' }]]);
    expect(host.textContent).toContain('/current');
    expect([...host.querySelectorAll('[role="group"]')].map(group => group.getAttribute('aria-label'))).toEqual(['/alpha', '/beta', '(unknown project)']);
    expect([...host.querySelectorAll('[role="option"]')].map(option => option.textContent)).toEqual(['Newestnew · 1.0 MB', 'Olderold · 10 B', 'Otherother · 1.0 KB', 'unknownunknown · 1 B']);
    expect(host.querySelector('[role="group"]')?.textContent).toContain('/alpha2');
    await click(host.querySelector('[role="option"]'));
    expect(openSession).toHaveBeenCalledExactlyOnceWith('new', 'Newest');
    await click(host.querySelector('button'));
    expect(rpc).toHaveBeenCalledWith('sessions.create', {});
    expect(openSession).toHaveBeenLastCalledWith('created', 'new session');
  });

  it('debounces backend search without locally discarding content-only matches, and clears immediately', async () => {
    vi.useFakeTimers();
    mockSessions();
    await render(<SessionsPage openSession={vi.fn()} />);
    await type('content-only');
    await type('content-only match');
    await act(async () => { await vi.advanceTimersByTimeAsync(199); });
    expect(rpc.mock.calls.filter(([method]) => method === 'sessions.list')).toHaveLength(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(1); });
    expect(rpc).toHaveBeenLastCalledWith('sessions.list', { query: 'content-only match', project: '' });
    expect(host.querySelectorAll('[role="option"]')).toHaveLength(4);
    await type('');
    expect(rpc).toHaveBeenLastCalledWith('sessions.list', { query: '', project: '' });
    expect(rpc.mock.calls.filter(([method]) => method === 'sessions.list')).toHaveLength(3);
  });

  it('ignores late search responses and cancels debounce on unmount', async () => {
    vi.useFakeTimers();
    const first = deferred<unknown>();
    const second = deferred<unknown>();
    rpc.mockImplementation((method, params) => {
      if (method === 'sessions.currentProject') { return Promise.resolve({ project: '' }); }
      return (params as { query: string }).query === '' ? first.promise : second.promise;
    });
    await render(<SessionsPage openSession={vi.fn()} />);
    await type('new');
    await act(async () => { await vi.advanceTimersByTimeAsync(200); });
    await act(async () => second.resolve([sessions[2]]));
    await act(async () => first.resolve([sessions[0]]));
    expect(host.querySelectorAll('[role="option"]')).toHaveLength(1);
    expect(host.querySelector('[role="option"]')?.textContent).toContain('Newest');
    await type('cancel');
    act(() => root.unmount());
    root = createRoot(host);
    await act(async () => { await vi.advanceTimersByTimeAsync(200); });
    expect(rpc.mock.calls.filter(([method]) => method === 'sessions.list')).toHaveLength(2);
  });

  it('shows list errors and empty results explicitly', async () => {
    rpc.mockImplementation(async method => {
      if (method === 'sessions.currentProject') { return { project: '' }; }
      throw new Error('unavailable');
    });
    await render(<SessionsPage openSession={vi.fn()} />);
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('unavailable');
    act(() => root.unmount());
    root = createRoot(host);
    mockSessions();
    rpc.mockImplementation(async method => method === 'sessions.currentProject' ? { project: '' } : []);
    await render(<SessionsPage openSession={vi.fn()} />);
    expect(host.textContent).toContain('No sessions.');
  });
});
