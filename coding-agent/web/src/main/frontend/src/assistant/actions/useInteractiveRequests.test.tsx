// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { dispatchServerRequest } from '../../api/rpc';
import { SessionRequests } from './SessionRequests';
import { useInteractiveRequests } from './useInteractiveRequests';

let host: HTMLDivElement;
let root: Root;
let current: ReturnType<typeof useInteractiveRequests>;
let fetchMock: ReturnType<typeof vi.fn>;
function Harness({ session }: { session?: string }) {
  current = useInteractiveRequests(session);
  return <SessionRequests request={current.pending} onAnswer={current.answer} pendingCount={current.pendingCount} />;
}
function render(session?: string) {
  act(() => root.render(<Harness session={session} />));
}
function emit(id: number, method = 'session.approval', params: Record<string, unknown> = { title: `Request ${id}` }) {
  act(() => dispatchServerRequest(id, method, params));
}
function response(resolved = true) {
  return { json: async () => ({ result: { resolved } }) };
}
async function click(label: string) {
  const button = Array.from(host.querySelectorAll('button')).find((item) => item.textContent?.trim() === label)!;
  expect(button).toBeDefined();
  await act(async () => { button.click(); });
}
function payload(index = 0) {
  return JSON.parse(fetchMock.mock.calls[index][1].body);
}

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  fetchMock = vi.fn().mockResolvedValue(response());
  vi.stubGlobal('fetch', fetchMock);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});
afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.unstubAllGlobals();
});

describe('useInteractiveRequests with the real RPC dispatcher', () => {
  it('removes only a cancelled side request and suppresses its stale replay without answering it', () => {
    render('a');
    emit(1, 'session.ask', { questions: [{ id: 'main', prompt: 'Main', options: [], multiple: false }] });
    emit(2);
    emit(3);
    emit(2, 'session.requestCancelled', {});
    expect(current.pending?.id).toBe(1);
    expect(current.pendingCount).toBe(2);
    emit(2);
    expect(current.pendingCount).toBe(2);
    emit(1, 'session.requestCancelled', {});
    expect(current.pending?.id).toBe(3);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('ignores late frames from the source stream of another session', () => {
    render('b');
    act(() => dispatchServerRequest(3, 'session.approval', { title: 'From a' }, 'a'));
    expect(current.pending).toBeNull();
    act(() => dispatchServerRequest(3, 'session.approval', { title: 'From b' }, 'b'));
    expect(current.pending?.sessionId).toBe('b');
    expect(current.pending?.id).toBe(3);
  });
  it('subscribes only with a session and unregisters on unmount', () => {
    render();
    emit(1);
    expect(current.pending).toBeNull();
    render('a');
    emit(1);
    expect(current.pending?.id).toBe(1);
    act(() => root.unmount());
    root = createRoot(host);
    emit(2);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('retains FIFO requests, deduplicates frames and never overwrites a pending question', async () => {
    render('a');
    emit(1);
    emit(2);
    emit(1);
    expect(current.pending?.id).toBe(1);
    expect(current.pendingCount).toBe(2);
    expect(host.textContent).toContain('2 requests waiting');
    await click('Allow once');
    expect(payload()).toMatchObject({ method: 'session.resolve', params: { id: 'a', requestId: '1', result: 'allow_once' } });
    expect(current.pending?.id).toBe(2);
    expect(current.pendingCount).toBe(1);
    emit(1);
    expect(current.pendingCount).toBe(1);
    await click('Deny');
    expect(payload(1).params).toEqual({ id: 'a', requestId: '2', result: 'deny' });
    expect(current.pending).toBeNull();
  });

  it('preserves new arrivals while an answer is in flight and suppresses duplicate submissions', async () => {
    let resolve!: (value: ReturnType<typeof response>) => void;
    fetchMock.mockReturnValueOnce(new Promise((success) => { resolve = success; }));
    render('a');
    emit(1);
    let first!: Promise<void>;
    let second!: Promise<void>;
    act(() => {
      first = current.answer(1, 'allow_once');
      second = current.answer(1, 'deny');
    });
    expect(first).toBe(second);
    expect(current.answering).toBe(true);
    emit(2);
    await act(async () => {
      resolve(response());
      await first;
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(current.pending?.id).toBe(2);
  });

  it('keeps a failed request and queue, exposes RPC errors in the mounted component, and retries', async () => {
    fetchMock.mockResolvedValueOnce({ json: async () => ({ error: { code: -1, message: 'Temporarily unavailable' } }) });
    render('a');
    emit(1);
    emit(2);
    await click('Allow session');
    expect(current.pending?.id).toBe(1);
    expect(current.pendingCount).toBe(2);
    expect(current.answering).toBe(false);
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Temporarily unavailable');
    await click('Allow session');
    expect(payload(1).params.result).toBe('allow_session');
    expect(current.pending?.id).toBe(2);
  });

  it('preserves a request on transport failure or malformed acknowledgement', async () => {
    fetchMock.mockRejectedValueOnce(new Error('Offline'));
    fetchMock.mockResolvedValueOnce({ json: async () => ({ result: {} }) });
    render('a');
    emit(1);
    await click('Deny');
    expect(current.pending?.id).toBe(1);
    expect(host.textContent).toContain('Offline');
    await click('Deny');
    expect(current.pending?.id).toBe(1);
    expect(host.textContent).toContain('did not acknowledge');
    await click('Deny');
    expect(current.pending).toBeNull();
  });

  it('retires expired/already resolved requests instead of trapping the queue forever', async () => {
    fetchMock.mockResolvedValueOnce(response(false));
    render('a');
    emit(1);
    emit(2);
    let failure: unknown;
    await act(async () => {
      try {
        await current.answer(1, 'deny');
      } catch (error) {
        failure = error;
      }
    });
    expect(String(failure)).toContain('no longer pending');
    expect(current.pending?.id).toBe(2);
  });

  it('isolates session queues and captures session association through a delayed response', async () => {
    let resolve!: (value: ReturnType<typeof response>) => void;
    fetchMock.mockReturnValueOnce(new Promise((success) => { resolve = success; }));
    render('a');
    emit(1);
    await click('Allow once');
    render('b');
    expect(current.pending).toBeNull();
    emit(2);
    await act(async () => { resolve(response()); });
    expect(payload().params.id).toBe('a');
    expect(current.pending?.id).toBe(2);
    render('a');
    expect(current.pending).toBeNull();
    render('b');
    expect(current.pending?.id).toBe(2);
    await click('Deny');
    expect(payload(1).params).toEqual({ id: 'b', requestId: '2', result: 'deny' });
  });

  it('keeps unanswered requests when navigating away and back without cross-session resolutions', async () => {
    render('a');
    emit(1);
    const oldAnswer = current.answer;
    render('b');
    emit(2);
    await act(async () => { await expect(current.answer(1, null)).rejects.toThrow('selected session'); });
    expect(fetchMock).not.toHaveBeenCalled();
    await act(async () => { await oldAnswer(1, 'deny'); });
    expect(payload().params.id).toBe('a');
    expect(current.pending?.id).toBe(2);
    render('a');
    expect(current.pending).toBeNull();
  });

  it('preserves pending requests while temporarily without a selected session', () => {
    render('a');
    emit(1);
    render();
    emit(2);
    expect(current.pending).toBeNull();
    render('a');
    expect(current.pending?.id).toBe(1);
    expect(current.pendingCount).toBe(1);
  });

  it('posts exact single, multiple, cancel and confirm values through session.resolve', async () => {
    render('a');
    emit(1, 'session.ask', { questions: [{ id: 'one', prompt: 'Pick', options: ['X'], multiple: false }] });
    await click('X');
    await click('Submit');
    expect(payload().params).toEqual({ id: 'a', requestId: '1', result: { one: 'X' } });
    emit(2, 'session.ask', { questions: [
      { id: 'one', prompt: 'First', options: ['X'], multiple: false },
      { id: 'two', prompt: 'Second', options: ['Y'], multiple: true },
    ] });
    await click('X');
    await click('Y');
    await click('Submit');
    expect(payload(1).params.result).toEqual({ one: 'X', two: ['Y'] });
    emit(3, 'session.question', { prompt: 'Confirm?' });
    await click('Yes');
    expect(payload(2).params.result).toBe(true);
    emit(4, 'session.question', { prompt: 'Pick', options: ['X'] });
    await click('Cancel');
    expect(payload(3).params).toEqual({ id: 'a', requestId: '4', result: null });
  });

  it('preserves fallback handling for unknown methods without stealing the question queue', () => {
    render('a');
    emit(1, 'future.method');
    expect(current.pending).toBeNull();
    expect(payload()).toEqual({ jsonrpc: '2.0', id: 1, result: null });
  });
});
