// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { applySuggestion, NO_SUGGESTION, useSlashCompletion, type SlashSuggestion } from './useSlashCompletion';
const rpc = vi.hoisted(() => vi.fn());
vi.mock('./rpc', () => ({ call: rpc }));
let root: Root;
let host: HTMLDivElement;
let current: SlashSuggestion;
function Harness({ text, cursor = text.length, session = 'a' }: { text: string; cursor?: number; session?: string }) {
  current = useSlashCompletion(text, cursor, session);
  return null;
}
function render(text: string, session = 'a') { act(() => root.render(<Harness text={text} session={session} />)); }
async function tick() { await act(async () => { await vi.advanceTimersByTimeAsync(130); }); }
beforeEach(() => {
  vi.useFakeTimers(); vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true); rpc.mockReset();
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
});
afterEach(() => { act(() => root.unmount()); host.remove(); vi.useRealTimers(); vi.unstubAllGlobals(); });
const suggestion: SlashSuggestion = { items: ['deepseek'], replaceStart: 7, replaceEnd: 10, label: 'Models' };
describe('backend slash completion adapter', () => {
  it('uses the backend argument range without changing suffix whitespace', () => {
    expect(applySuggestion('/model old  suffix', suggestion, 'deepseek')).toEqual({ text: '/model deepseek  suffix', cursor: 15 });
  });
  it('does not reopen completions when a late RPC resolves after leaving slash mode', async () => {
    let resolve!: (value: SlashSuggestion[]) => void;
    rpc.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    render('/model old'); await tick(); render('ordinary prompt');
    await act(async () => resolve([suggestion]));
    expect(current).toEqual(NO_SUGGESTION);
  });
  it('ignores responses from a previous session and out-of-order keystrokes', async () => {
    let resolve!: (value: SlashSuggestion[]) => void;
    rpc.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    rpc.mockResolvedValueOnce([{ ...suggestion, items: ['new-model'] }]);
    render('/model old'); await tick(); render('/model new', 'b'); await tick();
    await act(async () => resolve([suggestion]));
    expect(current.items).toEqual(['new-model']);
  });
  it('debounces RPC calls and passes the exact cursor', async () => {
    rpc.mockResolvedValue([suggestion]);
    render('/m'); render('/mo'); render('/model old');
    await tick();
    expect(rpc).toHaveBeenCalledExactlyOnceWith('slash.complete', { id: 'a', text: '/model old', cursor: 10 });
    expect(current).toEqual(suggestion);
  });
  it('sends the session id on every A/B/A request and ignores the first A response', async () => {
    let resolveFirst!: (value: SlashSuggestion[]) => void;
    rpc.mockReturnValueOnce(new Promise((done) => { resolveFirst = done; }));
    rpc.mockResolvedValueOnce([{ ...suggestion, items: ['b-model'] }]);
    rpc.mockResolvedValueOnce([{ ...suggestion, items: ['a-model'] }]);
    render('/model old', 'a'); await tick();
    render('/model old', 'b'); await tick();
    expect(current.items).toEqual(['b-model']);
    render('/model old', 'a'); await tick();
    await act(async () => resolveFirst([suggestion]));
    expect(current.items).toEqual(['a-model']);
    expect(rpc.mock.calls).toEqual(['a', 'b', 'a'].map((id) => [
      'slash.complete', { id, text: '/model old', cursor: 10 },
    ]));
  });
  it('does not request completions without a session', async () => {
    act(() => root.render(<Harness text="/model " session="" />));
    await tick();
    expect(rpc).not.toHaveBeenCalled();
    expect(current).toEqual(NO_SUGGESTION);
  });
  it('keeps concurrently mounted file and model completions scoped to their sessions', async () => {
    let resolveFiles!: (value: SlashSuggestion[]) => void;
    let resolveModels!: (value: SlashSuggestion[]) => void;
    rpc.mockImplementation((_method: string, params: { id: string }) => new Promise((done) => {
      if (params.id === 'a') { resolveFiles = done; } else { resolveModels = done; }
    }));
    const values: Record<string, SlashSuggestion> = {};
    function Session({ id, text }: { id: string; text: string }) {
      values[id] = useSlashCompletion(text, text.length, id);
      return null;
    }
    act(() => root.render(<><Session id="a" text="/context entry" /><Session id="b" text="/model " /></>));
    await tick();
    expect(rpc.mock.calls).toEqual([
      ['slash.complete', { id: 'a', text: '/context entry', cursor: 14 }],
      ['slash.complete', { id: 'b', text: '/model ', cursor: 7 }],
    ]);
    const files = { ...suggestion, items: ['entry-a'], label: 'Paths' };
    const models = { ...suggestion, items: ['b-model'] };
    await act(async () => resolveModels([models]));
    expect(values).toEqual({ a: NO_SUGGESTION, b: models });
    await act(async () => resolveFiles([files]));
    expect(values).toEqual({ a: files, b: models });
  });
});
