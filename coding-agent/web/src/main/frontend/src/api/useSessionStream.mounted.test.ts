// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TAIL_LIMIT, useSessionStream, type WebEvent } from './useSessionStream';

const rpc = vi.hoisted(() => ({ call: vi.fn(), dispatchServerRequest: vi.fn() }));
vi.mock('./rpc', () => rpc);

class Stream {
  static instances: Stream[] = [];
  listeners = new Map<string, (event: MessageEvent) => void>();
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  close = vi.fn();
  constructor(readonly url: string) { Stream.instances.push(this); }
  addEventListener(type: string, listener: (event: MessageEvent) => void) { this.listeners.set(type, listener); }
  emit(type: string, data: unknown) { this.listeners.get(type)?.(new MessageEvent(type, { data: JSON.stringify(data) })); }
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function page(from: number, to: number, sessionId = 's1'): WebEvent[] {
  return Array.from({ length: to - from }, (_, index) => ({
    type: 'user', sessionId, message: `message ${from + index}`, data: { ordinal: from + index },
  }));
}

let root: Root;
let host: HTMLDivElement;
let current: ReturnType<typeof useSessionStream>;
let frames: FrameRequestCallback[];
function Probe({ sessionId }: { sessionId?: string }) {
  current = useSessionStream(sessionId);
  return null;
}
function render(sessionId?: string) {
  act(() => root.render(createElement(Probe, { sessionId })));
}
function flushFrames() {
  act(() => {
    const pending = frames;
    frames = [];
    for (const frame of pending) { frame(0); }
  });
}
function snapshot(events: WebEvent[], total: number) {
  act(() => Stream.instances.at(-1)!.emit('snapshot', { events, total }));
  flushFrames();
}
async function load() {
  await act(async () => { await current.loadEarlier(); });
  flushFrames();
}

beforeEach(() => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.stubGlobal('EventSource', Stream);
  frames = [];
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => frames.push(callback));
  Stream.instances = [];
  rpc.call.mockReset();
  rpc.dispatchServerRequest.mockReset();
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

describe('mounted session history pagination', () => {
  it('preserves server order across multiple pages and deduplicates snapshot/live overlap', async () => {
    render('s1');
    snapshot(page(6, 8), 8);
    rpc.call.mockResolvedValueOnce({ events: page(3, 6), loaded: 5 });
    await load();
    rpc.call.mockResolvedValueOnce({ events: page(0, 3), loaded: 8 });
    await load();
    expect(current.events).toEqual(page(0, 8));
    expect(rpc.call.mock.calls).toEqual([
      ['session.loadEarlier', { sessionId: 's1', excludeFromTail: 2 }],
      ['session.loadEarlier', { sessionId: 's1', excludeFromTail: 5 }],
    ]);
    expect(current.moreReplayExists).toBe(false);
    act(() => {
      Stream.instances[0].emit('snapshot', { events: page(0, 8), total: 8 });
      Stream.instances[0].emit('agent', page(0, 1)[0]);
    });
    flushFrames();
    expect(current.events).toEqual(page(0, 8));
  });

  it('keeps all explicitly loaded pages accessible beyond TAIL_LIMIT as live events rotate', async () => {
    render('s1');
    const count = TAIL_LIMIT + 20;
    snapshot(page(count, count + 2), count + 2);
    for (let end = count; end > 0; end -= 1000) {
      const start = Math.max(0, end - 1000);
      rpc.call.mockResolvedValueOnce({ events: page(start, end), loaded: count + 2 - start });
      await load();
    }
    expect(current.events).toEqual(page(0, count + 2));
    act(() => {
      for (const event of page(count + 2, count + 2 + TAIL_LIMIT * 3)) {
        Stream.instances[0].emit('agent', event);
      }
    });
    expect(frames).toHaveLength(1);
    flushFrames();
    expect(current.events.slice(0, count)).toEqual(page(0, count));
    expect(current.events).toHaveLength(count + TAIL_LIMIT * 2);
    expect(current.events.at(-1)).toEqual(page(count + 1 + TAIL_LIMIT * 3, count + 2 + TAIL_LIMIT * 3)[0]);
  });

  it('exposes an explicitly requested page even when it overlaps events already rotated out of display', async () => {
    render('s1');
    snapshot(page(0, TAIL_LIMIT * 3), TAIL_LIMIT * 4);
    expect(current.events[0]).toEqual(page(TAIL_LIMIT, TAIL_LIMIT + 1)[0]);
    rpc.call.mockResolvedValueOnce({ events: page(0, 3), loaded: TAIL_LIMIT * 3 + 3 });
    await load();
    expect(current.events.slice(0, 3)).toEqual(page(0, 3));
    expect(current.events.filter((event) => event.message === 'message 0')).toHaveLength(1);
  });

  it('serializes concurrent calls and reads the updated cursor for each RPC', async () => {
    render('s1');
    snapshot(page(4, 6), 6);
    const first = deferred<{ events: WebEvent[]; loaded: number }>();
    rpc.call.mockReturnValueOnce(first.promise).mockResolvedValueOnce({ events: page(0, 2), loaded: 6 });
    let one!: Promise<void>;
    let two!: Promise<void>;
    await act(async () => {
      one = current.loadEarlier();
      two = current.loadEarlier();
      await Promise.resolve();
    });
    expect(rpc.call).toHaveBeenCalledTimes(1);
    await act(async () => {
      first.resolve({ events: page(2, 4), loaded: 4 });
      await Promise.all([one, two]);
    });
    flushFrames();
    expect(rpc.call.mock.calls[1][1]).toEqual({ sessionId: 's1', excludeFromTail: 4 });
    expect(current.events).toEqual(page(0, 6));
  });

  it('ignores stale session responses and queued calls without contaminating the new cursor', async () => {
    render('s1');
    snapshot(page(4, 6), 6);
    const pending = deferred<{ events: WebEvent[]; loaded: number }>();
    rpc.call.mockReturnValueOnce(pending.promise);
    let old!: Promise<void>;
    let queued!: Promise<void>;
    await act(async () => {
      old = current.loadEarlier();
      queued = current.loadEarlier();
      await Promise.resolve();
    });
    const staleSource = Stream.instances[0];
    render('s2');
    snapshot(page(2, 3, 's2'), 3);
    await act(async () => {
      pending.resolve({ events: page(0, 4), loaded: 6 });
      await Promise.all([old, queued]);
      staleSource.emit('snapshot', { events: page(0, 6), total: 100 });
      staleSource.emit('agent', page(0, 1)[0]);
      staleSource.emit('rpc', { id: 1, method: 'test', params: {} });
    });
    flushFrames();
    expect(rpc.call).toHaveBeenCalledTimes(1);
    expect(rpc.dispatchServerRequest).not.toHaveBeenCalled();
    expect(current.events).toEqual(page(2, 3, 's2'));
    expect(current.moreReplayExists).toBe(true);
    rpc.call.mockResolvedValueOnce({ events: page(0, 2, 's2'), loaded: 3 });
    await load();
    expect(rpc.call.mock.calls[1][1]).toEqual({ sessionId: 's2', excludeFromTail: 1 });
    expect(current.events).toEqual(page(0, 3, 's2'));
  });

  it('allows retry after failure without advancing the cursor or poisoning the queue', async () => {
    render('s1');
    snapshot(page(2, 4), 4);
    rpc.call.mockRejectedValueOnce(new Error('offline'));
    await load();
    expect(current.events).toEqual(page(2, 4));
    expect(current.moreReplayExists).toBe(true);
    rpc.call.mockResolvedValueOnce({ events: page(0, 2), loaded: 4 });
    await load();
    expect(rpc.call.mock.calls.map((entry) => entry[1].excludeFromTail)).toEqual([2, 2]);
    expect(current.events).toEqual(page(0, 4));
  });
  it('keeps history through a real reconnect and preserves latest-wins state frames', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
    render('s1');
    snapshot(page(2, 4), 4);
    rpc.call.mockResolvedValueOnce({ events: page(0, 2), loaded: 4 });
    await load();
    act(() => {
      Stream.instances[0].emit('agent', { type: 'text-delta', sessionId: 's1', message: 'in flight' });
      Stream.instances[0].onerror?.();
    });
    expect(current.bootstrapped).toBe(true);
    expect(current.reconnecting).toBe(true);
    act(() => { vi.advanceTimersByTime(500); });
    expect(Stream.instances).toHaveLength(2);
    act(() => {
      const stream = Stream.instances[1];
      stream.onopen?.();
      stream.emit('snapshot', { events: page(2, 4), total: 4 });
      stream.emit('agent', { type: 'usage-updated', sessionId: 's1', message: 'model', data: { promptTokens: 10 } });
      stream.emit('agent', { type: 'usage-updated', sessionId: 's1', message: 'model', data: { promptTokens: 20 } });
    });
    flushFrames();
    expect(current.events.slice(0, 4)).toEqual(page(0, 4));
    expect(current.events[4].message).toBe('in flight');
    expect(current.events.filter((event) => event.type === 'usage-updated')).toHaveLength(2);
    expect(current.status.promptTokens).toBe(20);
    expect(current.connected).toBe(true);
    expect(current.reconnecting).toBe(false);
    expect(current.moreReplayExists).toBe(false);
  });


  it('retains bootstrap, reconnect merging, frame batching and session-scoped RPC dispatch', async () => {
    render('s1');
    expect(current.bootstrapped).toBe(false);
    const stream = Stream.instances[0];
    act(() => stream.onopen?.());
    expect(current.connected).toBe(true);
    expect(current.bootstrapped).toBe(false);
    snapshot(page(2, 4), 4);
    rpc.call.mockResolvedValueOnce({ events: page(0, 2), loaded: 4 });
    await load();
    act(() => {
      stream.emit('agent', { type: 'text-delta', sessionId: 's1', message: 'live' });
      stream.emit('agent', { type: 'text-delta', sessionId: 's1', message: ' output' });
      stream.emit('snapshot', { events: page(2, 4), total: 4 });
      stream.emit('rpc', { id: 7, method: 'test', params: { value: 1 } });
    });
    expect(frames).toHaveLength(1);
    expect(current.events).toEqual(page(0, 4));
    flushFrames();
    expect(current.events.slice(0, 4)).toEqual(page(0, 4));
    expect(current.events.slice(4).map((event) => event.message)).toEqual(['live', ' output']);
    expect(current.status.liveOutChars).toBe(11);
    expect(current.bootstrapped).toBe(true);
    expect(rpc.dispatchServerRequest).toHaveBeenCalledWith(7, 'test', { value: 1 }, 's1');
    render(undefined);
    expect(current.events).toEqual([]);
    expect(current.moreReplayExists).toBe(false);
    expect(current.bootstrapped).toBe(false);
    await expect(current.loadEarlier()).resolves.toBeUndefined();
  });
});
