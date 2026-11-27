// @vitest-environment happy-dom
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useExternalStoreRuntime, type ThreadMessageLike } from '@assistant-ui/react';
import { eventsToBlocks } from '../../api/eventLog';
import type { WebEvent } from '../../api/useSessionStream';
import { createYcaMessageProjector, type YcaThreadMessage } from './eventsToMessages';

function event(type: string, message = '', data?: Record<string, unknown>): WebEvent {
  return { type, sessionId: 'session', message, data };
}

function parts(message: ThreadMessageLike): Exclude<ThreadMessageLike['content'], string> {
  if (typeof message.content === 'string') {
    throw new Error('Expected structured content');
  }
  return message.content;
}

function ids(messages: YcaThreadMessage[]): string[] {
  return messages.map((message) => message.id);
}

function withoutIds(blocks: ReturnType<typeof eventsToBlocks>) {
  return blocks.map(({ id: _id, ...block }) => block);
}

function round(prompt: string, answer: string): WebEvent[] {
  return [event('user', prompt), event('text-delta', answer), event('turn-done', answer)];
}

describe('YCA display message projector', () => {
  it('keeps IDs compact for large prompts/arguments and reconciles exact replay anchors', () => {
    const project = createYcaMessageProjector();
    const prompt = 'dummy prompt '.repeat(4000);
    const args = JSON.stringify({ payload: 'dummy argument '.repeat(4000) });
    const events = [
      event('user', prompt), event('thinking-delta', 'consider'),
      event('tool-started', 'inspect', { args, ordinal: 1 }),
      event('tool-finished', 'inspect', { output: 'checked', ordinal: 1 }),
      event('text-delta', 'newer answer'), event('turn-done', 'newer answer'),
    ];
    const before = project(events);
    expect(before.every((message) => message.id.length < 80)).toBe(true);
    expect(new Set(ids(before)).size).toBe(before.length);
    for (let index = 0; index < 3; index++) {
      events.push(event('status', 'working'));
      expect(project(events)).toBe(before);
    }
    expect(project(JSON.parse(JSON.stringify(events)))).toBe(before);
    const prepended = project(JSON.parse(JSON.stringify([...round(prompt, 'older answer'), ...events])));
    before.forEach((message, index) => expect(prepended[index + 2]).toBe(message));
    expect(prepended.every((message) => message.id.length < 80)).toBe(true);
  });

  it('interns exact tool and notice origins without conflating similar strings', () => {
    const project = createYcaMessageProjector();
    const user = event('user', 'dummy');
    const tools = [
      event('tool-started', 'inspect:one', { args: 'two' }),
      event('tool-started', 'inspect', { args: 'one:two' }),
      event('tool-started', 'inspect', { args: 'one:two', toolCallId: 'call' }),
      event('transcript', 'notice\u0000one'), event('transcript', 'notice:one'),
    ];
    const before = project([user, ...tools]);
    expect(new Set(ids(before)).size).toBe(before.length);
    expect(project(JSON.parse(JSON.stringify([user, ...tools])))).toBe(before);
    const inserted = event('tool-started', 'different', { args: '{}' });
    const after = project([user, inserted, ...tools]);
    before.slice(1).forEach((message, index) => expect(after[index + 2]).toBe(message));
    expect(project(JSON.parse(JSON.stringify([user, inserted, ...tools])))).toBe(after);
  });

  it('invalidates cached IDs when an identical origin occurrence shifts', () => {
    const project = createYcaMessageProjector();
    const user = event('user', 'dummy');
    const first = event('tool-started', 'inspect', { args: '{}', ordinal: 1 });
    const second = event('tool-started', 'inspect', { args: '{}', ordinal: 2 });
    const before = project([user, first, second]);
    expect(before[1].id).not.toBe(before[2].id);
    const removed = project([user, second]);
    expect(removed[1].id).toBe(before[1].id);
    expect(removed[1]).not.toBe(before[1]); // The exact ordinal metadata changed.
    expect(project(JSON.parse(JSON.stringify([user, second])))).toBe(removed);
    const restored = project([user, first, second]);
    expect(ids(restored)).toEqual(ids(before));
    expect(project(JSON.parse(JSON.stringify([user, first, second])))).toBe(restored);
  });

  it('keeps repeated user-only appends distinct without replacing earlier prompt projections', () => {
    const project = createYcaMessageProjector();
    const events = [event('user', 'repeat')];
    const first = project(events)[0];
    events.push(event('user', 'repeat'));
    const second = project(events);
    expect(second[0]).toBe(first);
    events.push(event('user', 'repeat'));
    const third = project(events);
    expect(third[0]).toBe(first);
    expect(third[1]).toBe(second[1]);
    expect(new Set(ids(third)).size).toBe(3);
    expect(project(JSON.parse(JSON.stringify(events)))).toBe(third);
  });

  it.each([false, true])('preserves repeated prompt append IDs and references (reconstructed: %s)', (reconstructed) => {
    const project = createYcaMessageProjector();
    let events = round('repeat', 'same answer');
    let before = project(events);
    for (let index = 0; index < 3; index++) {
      events = [...events, ...round('repeat', 'same answer')];
      if (reconstructed) {
        events = JSON.parse(JSON.stringify(events));
      }
      const after = project(events);
      expect(ids(after.slice(0, before.length))).toEqual(ids(before));
      before.forEach((message, position) => expect(after[position]).toBe(message));
      expect(new Set(ids(after)).size).toBe(after.length);
      expect(project(JSON.parse(JSON.stringify(events)))).toBe(after);
      before = after;
    }
  });

  it('reconciles identical prefix prepends by shared event references, then replay and append', () => {
    const project = createYcaMessageProjector();
    const tail = [...round('repeat', 'answer'), ...round('repeat', 'answer')];
    const before = project(tail);
    const events = [...round('repeat', 'answer'), ...tail, ...round('repeat', 'answer')];
    const after = project(events);
    before.forEach((message, index) => expect(after[index + 2]).toBe(message));
    expect(new Set(ids(after)).size).toBe(after.length);
    expect(project(JSON.parse(JSON.stringify(events)))).toBe(after);
  });

  it('uses answer anchors to locate reconstructed prepends with repeated prompts', () => {
    const project = createYcaMessageProjector();
    const before = project(round('repeat', 'newer answer'));
    const events = [...round('repeat', 'older answer'), ...round('repeat', 'newer answer')];
    const after = project(JSON.parse(JSON.stringify(events)));
    expect(after.slice(-2)).toEqual(before);
    expect(after.at(-2)).toBe(before[0]);
    expect(after.at(-1)).toBe(before[1]);
    expect(new Set(ids(after)).size).toBe(after.length);
  });

  it('preserves runtime message identity with the existing identity convertMessage contract', () => {
    vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
    const host = document.createElement('div');
    const root = createRoot(host);
    const project = createYcaMessageProjector();
    const convertMessage = (message: ThreadMessageLike) => message;
    let runtime: ReturnType<typeof useExternalStoreRuntime> | undefined;
    function Runtime({ messages }: { messages: YcaThreadMessage[] }) {
      runtime = useExternalStoreRuntime({ messages, convertMessage, onNew: async () => {} });
      return null;
    }
    const render = (messages: YcaThreadMessage[]) => {
      act(() => root.render(createElement(Runtime, { messages })));
      return runtime!.thread.getState().messages;
    };
    try {
      const events = round('repeat', 'answer');
      const before = render(project(events));
      const appended = [...events, ...round('repeat', 'answer')];
      const after = render(project(appended));
      before.forEach((message, index) => expect(after[index]).toBe(message));
      expect(new Set(after.map((message) => message.id)).size).toBe(4);
      const allEvents = [...round('repeat', 'answer'), ...appended];
      const prepended = render(project(allEvents));
      after.forEach((message, index) => expect(prepended[index + 2]).toBe(message));
      expect(new Set(prepended.map((message) => message.id)).size).toBe(6);
      const replayed = render(project(JSON.parse(JSON.stringify(allEvents))));
      prepended.forEach((message, index) => expect(replayed[index]).toBe(message));
    } finally {
      act(() => root.unmount());
      vi.unstubAllGlobals();
    }
  });

  it('preserves every exact reducer block and projects roles, reasoning and notices', () => {
    const events = [
      event('user', 'hello'),
      event('thinking-delta', 'considering'),
      event('text-delta', 'answer'),
      event('transcript', 'login URL'),
      event('btw', 'not in transcript'),
      event('transcript', '**Executing tools**\nnot inline'),
      event('error', 'failure'),
    ];
    const messages = createYcaMessageProjector()(events);
    expect(withoutIds(messages.map((message) => message.metadata.custom.ycaBlock))).toEqual(withoutIds(eventsToBlocks(events)));
    expect(messages.map((message) => message.role)).toEqual(['user', 'assistant', 'assistant', 'assistant', 'assistant']);
    expect(parts(messages[1])).toEqual([{ type: 'reasoning', text: 'considering' }]);
    expect(parts(messages[3])).toEqual([{ type: 'text', text: 'login URL' }]);
    expect(messages[0]).not.toHaveProperty('status');
    expect(messages[4].status).toEqual({ type: 'incomplete', reason: 'error' });
  });

  it('keeps IDs and object references when earlier turns, including identical prompts, are prepended', () => {
    const project = createYcaMessageProjector();
    const tail = round('same prompt', 'latest answer');
    const before = project(tail);
    const after = project([...round('different', 'older'), ...round('same prompt', 'older answer'), ...tail]);
    expect(ids(after.slice(-2))).toEqual(ids(before));
    expect(after.at(-2)).toBe(before[0]);
    expect(after.at(-1)).toBe(before[1]);
    expect(new Set(ids(after)).size).toBe(after.length);
  });

  it('uses deterministic identities across JSON reconstruction and streaming vs full-text replay', () => {
    const project = createYcaMessageProjector();
    const streamed = [
      event('user', 'inspect'),
      event('thinking-delta', 'think '),
      event('thinking-delta', 'more'),
      event('text-delta', 'hello '),
      event('text-delta', 'world'),
      event('tool-started', 'read', { args: '{"path":"a"}', ordinal: 1 }),
      event('tool-finished', 'read', { output: 'contents', success: true, ordinal: 1 }),
    ];
    const first = project(streamed);
    expect(project(JSON.parse(JSON.stringify(streamed)))).toBe(first);
    const replayed = [
      event('user', 'inspect'),
      event('thinking-delta', 'think more'),
      event('text-delta', 'hello world'),
      event('tool-started', 'read', { args: '{"path":"a"}', ordinal: 57 }),
      event('tool-finished', 'read', { output: 'contents', success: true, ordinal: 57 }),
    ];
    const replay = createYcaMessageProjector()(replayed);
    expect(ids(replay)).toEqual(ids(first));
    expect(parts(replay[2])).toEqual(parts(first[2]));
    // Exact metadata still carries the reducer's reconstructed ordinal, not a rewritten source log.
    expect(replay[3].metadata.custom.ycaBlock).toMatchObject({ toolOrdinal: 57 });
    const reconciled = project(replayed);
    expect(ids(reconciled)).toEqual(ids(first));
    first.slice(0, 3).forEach((message, index) => expect(reconciled[index]).toBe(message));
  });

  it('does not use growing text, timestamps or array indices as stream identity', () => {
    const project = createYcaMessageProjector();
    const events = [event('user', 'hi'), event('text-delta', 'first')];
    const before = project(events);
    events.push(event('text-delta', ' second'));
    const growing = project(events);
    expect(growing[0]).toBe(before[0]);
    expect(growing[1]).not.toBe(before[1]);
    expect(growing[1].id).toBe(before[1].id);
    expect(parts(before[1])).toEqual([{ type: 'text', text: 'first' }]);
    expect(parts(growing[1])).toEqual([{ type: 'text', text: 'first second' }]);
    expect(growing[1].status).toEqual({ type: 'running' });
    events.push(event('turn-done', 'authoritative replacement'));
    const final = project(events);
    expect(final[1].id).toBe(before[1].id);
    expect(parts(final[1])).toEqual([{ type: 'text', text: 'authoritative replacement' }]);
    expect(final[1].status).toEqual({ type: 'complete', reason: 'stop' });
  });

  it('discriminates identical same-millisecond origin occurrences without wall-clock IDs', () => {
    const events = [
      event('user', 'same'),
      event('tool-started', 'bash', { args: '{}', ordinal: 1, timestamp: 100 }),
      event('tool-started', 'bash', { args: '{}', ordinal: 2, timestamp: 100 }),
      event('transcript', 'same notice'),
      event('transcript', 'same notice'),
      ...round('same', 'answer'),
    ];
    const messages = createYcaMessageProjector()(events);
    expect(new Set(ids(messages)).size).toBe(messages.length);
    expect(ids(createYcaMessageProjector()(JSON.parse(JSON.stringify(events))))).toEqual(ids(messages));
  });

  it('keeps the tool origin through stdout, successful and failed results', () => {
    const project = createYcaMessageProjector();
    const events = [event('user', 'run'), event('tool-started', 'bash', { args: '{"command":"ls"}', ordinal: 1 })];
    const running = project(events)[1];
    expect(running.status).toEqual({ type: 'running' });
    expect(parts(running)[0]).toMatchObject({ type: 'tool-call', toolCallId: running.id, toolName: 'bash', argsText: '{"command":"ls"}' });
    expect(parts(running)[0]).not.toHaveProperty('result');
    events.push(event('tool-stream', 'bash', { fragment: 'line', ordinal: 1 }));
    const streaming = project(events)[1];
    expect(streaming.id).toBe(running.id);
    expect(streaming.metadata.custom.ycaBlock).toMatchObject({ content: 'line\n' });
    expect(running.metadata.custom.ycaBlock).toMatchObject({ content: '' });
    events.push(event('tool-finished', 'bash', { output: 'done', success: true, ordinal: 1 }));
    const done = project(events)[1];
    expect(done.id).toBe(running.id);
    expect(parts(done)[0]).toMatchObject({ result: 'done', isError: false });
    expect(done.status).toEqual({ type: 'complete', reason: 'stop' });
    const failed = createYcaMessageProjector()([
      ...events.slice(0, -1), event('tool-finished', 'bash', { output: 'bad', success: false, ordinal: 1 }),
    ])[1];
    expect(parts(failed)[0]).toMatchObject({ result: 'bad', isError: true });
    expect(failed.status).toEqual({ type: 'incomplete', reason: 'error' });
  });

  it('preserves pending as neutral and interrupted as terminal, not still running', () => {
    const started = [event('tool-started', 'collect', { ordinal: 1 })];
    const pending = createYcaMessageProjector()([
      ...started, event('tool-finished', 'collect', { ordinal: 1, output: 'timeout', success: false, pending: true }),
    ])[0];
    expect(parts(pending)[0]).toMatchObject({ result: 'timeout', isPreliminary: true, isError: false });
    expect(pending.status).toEqual({ type: 'complete', reason: 'unknown' });
    expect(pending.metadata.custom.ycaBlock).toMatchObject({ pending: true });
    for (const boundary of ['turn-done', 'turn-cancelled', 'error']) {
      const interrupted = createYcaMessageProjector()([...started, event(boundary)])[0];
      expect(interrupted.status).toEqual({ type: 'incomplete', reason: 'cancelled' });
      expect(parts(interrupted)[0]).not.toHaveProperty('result');
      expect(interrupted.metadata.custom.ycaBlock).toMatchObject({ state: 'interrupted' });
    }
  });

  it('exposes exact subagent cards, including completion-only replay identity and all states', () => {
    const started: WebEvent = { ...event('subagent-started', 'task', { subagentName: 'worker' }), subagentId: 'child' };
    const project = createYcaMessageProjector();
    const running = project([started])[0];
    expect(running.status).toEqual({ type: 'running' });
    for (const [data, state, status] of [
      [{ success: true }, 'done', { type: 'complete', reason: 'stop' }],
      [{ success: false }, 'failed', { type: 'incomplete', reason: 'error' }],
      [{ cancelled: true }, 'cancelled', { type: 'incomplete', reason: 'cancelled' }],
    ] as const) {
      const completed: WebEvent = { ...event('subagent-completed', 'report', { ...data, model: 'model' }), subagentId: 'child' };
      const message = project([started, completed])[0];
      expect(message.id).toBe(running.id);
      expect(message.status).toEqual(status);
      expect(message.metadata.custom.ycaBlock).toMatchObject({ state, task: 'task', summary: 'report', model: 'model' });
      expect(parts(message)).toEqual([{ type: 'text', text: 'report' }]);
      expect(createYcaMessageProjector()([completed])[0].id).toBe(running.id);
    }
  });

  it('leaves replay healing, turn consolidation and retry reset to the existing reducer', () => {
    const project = createYcaMessageProjector();
    const events = [
      event('user', 'start'), event('text-delta', 'Noalls found'),
      event('tool-started', 'read'), event('tool-finished', 'read', { output: 'old' }),
      event('text-delta', 'No assistant-with-toolCalls found'),
    ];
    const healed = project(events);
    expect(withoutIds(healed.map((message) => message.metadata.custom.ycaBlock))).toEqual(withoutIds(eventsToBlocks(events)));
    expect(healed).toHaveLength(2);
    expect(parts(healed[1])).toEqual([{ type: 'text', text: 'No assistant-with-toolCalls found' }]);
    const resetEvents = [...events, event('text-reset'), event('thinking-delta', 'retry'), event('text-delta', 'fresh')];
    const reset = project(resetEvents);
    expect(withoutIds(reset.map((message) => message.metadata.custom.ycaBlock))).toEqual(withoutIds(eventsToBlocks(resetEvents)));
    expect(reset[0]).toBe(healed[0]);
    expect(reset.some((message) => message.id === healed[1].id)).toBe(false);
  });

  it('reuses the entire snapshot on status-only updates without inventing display messages', () => {
    const project = createYcaMessageProjector();
    const events = round('prompt', 'answer');
    const before = project(events);
    const after = project([
      ...events, event('status', 'working', { busy: true }), event('usage-updated', 'model'),
      event('notice', 'compacting'), event('todo-updated', '', { todos: [] }), event('btw', 'question'),
    ]);
    expect(after).toBe(before);
  });

  it('clears and evicts removed projections, including on an empty log and session replacement', () => {
    const project = createYcaMessageProjector();
    const events = round('prompt', 'answer');
    const before = project(events);
    expect(project([...events, event('web-command', '', { command: 'clear' })])).toEqual([]);
    expect(project([])).toEqual([]);
    const restored = project(events);
    expect(ids(restored)).toEqual(ids(before));
    expect(restored[0]).not.toBe(before[0]);
    const other = project(events.map((item) => ({ ...item, sessionId: 'other' })));
    expect(other[0].id).not.toBe(restored[0].id);
    expect(project(events)[0]).not.toBe(restored[0]);
  });

  it('never mutates input events/data or backend-visible serialization', () => {
    const events = [
      ...round('user', 'answer'),
      event('tool-started', 'read', { args: '{"path":"file"}', ordinal: 1 }),
      event('tool-finished', 'read', { output: 'result', ordinal: 1 }),
    ];
    const serialized = JSON.stringify(events);
    for (const item of events) {
      Object.freeze(item.data);
      Object.freeze(item);
    }
    Object.freeze(events);
    const project = createYcaMessageProjector();
    project(events);
    project(events);
    expect(JSON.stringify(events)).toBe(serialized);
    expect(events.every((item) => !Object.hasOwn(item, 'id'))).toBe(true);
  });

  it('allocates only changed message projections across a large transcript and evicts old snapshots', () => {
    const project = createYcaMessageProjector();
    const events = Array.from({ length: 2000 }, (_, index) => round(`prompt ${index}`, `answer ${index}`)).flat();
    events.push(event('user', 'live'), event('text-delta', 'tail'));
    const initial = project(events);
    expect(initial).toHaveLength(4002);
    let previous = initial;
    for (let index = 0; index < 10; index++) {
      events.push(event('text-delta', '.'));
      const next = project(events);
      expect(next.filter((message, position) => message !== previous[position])).toHaveLength(1);
      expect(next[0]).toBe(initial[0]);
      previous = next;
    }
    project([]);
    expect(project(events)[0]).not.toBe(initial[0]);
  });
});

describe('captured file projection stability', () => {
  it('updates only the matching tool and retains equivalent captures across replay and recomputation', () => {
    const project = createYcaMessageProjector();
    const files = [{ path: 'Actual.java', kind: 'update', oldText: 'class Before {}\n', newText: 'class After {}\n' }];
    const events = [
      event('user', 'change files'),
      event('tool-started', 'edit_file', { ordinal: 1, args: '{"path":"requested.java"}' }),
      event('tool-started', 'edit_file', { ordinal: 2, args: '{"path":"other.java"}' }),
    ];
    const before = project(events);
    events.push(event('tool-files-changed', 'edit_file', { ordinal: 1, files }));
    const captured = project(events);
    expect(captured[0]).toBe(before[0]);
    expect(captured[2]).toBe(before[2]);
    expect(captured[1]).not.toBe(before[1]);
    expect(captured[1].id).toBe(before[1].id);
    expect(captured[1].metadata.custom.ycaBlock).toMatchObject({ state: 'running', files });
    expect(before[1].metadata.custom.ycaBlock).not.toHaveProperty('files');
    expect(project(JSON.parse(JSON.stringify(events)))).toBe(captured);

    // A new, equivalent mutation forces reducer work without changing visible data.
    events.push(event('tool-files-changed', 'edit_file', { ordinal: 1, files: structuredClone(files) }));
    expect(project(events)).toBe(captured);
    events.push(event('text-delta', 'progress'));
    const recomputed = project(events);
    captured.forEach((message, index) => expect(recomputed[index]).toBe(message));
    expect(project(JSON.parse(JSON.stringify(events)))).toBe(recomputed);

    events.push(event('tool-finished', 'edit_file', { ordinal: 1, output: 'finished', success: true }));
    const done = project(events);
    expect(done[1].id).toBe(captured[1].id);
    expect(done[1]).not.toBe(captured[1]);
    expect(done[1].metadata.custom.ycaBlock).toMatchObject({ state: 'done', files, output: 'finished' });
    expect(done[0]).toBe(captured[0]);
    expect(done[2]).toBe(captured[2]);
    expect(project(JSON.parse(JSON.stringify(events)))).toBe(done);
  });

  it('preserves metadata-only and hostile paths without converting them into source or tool results', () => {
    const project = createYcaMessageProjector();
    const path = '../../<script>alert(1)</script>\n+++ b/forged';
    const events = [
      event('tool-started', 'write_file', { ordinal: 1 }),
      event('tool-files-changed', 'write_file', { ordinal: 1, files: [{ path, kind: 'create' }] }),
    ];
    const messages = project(events);
    const block = messages[0].metadata.custom.ycaBlock;
    expect(block).toMatchObject({ state: 'running', files: [{ path, kind: 'create' }] });
    if (block.kind !== 'tool') {
      throw new Error('Expected tool card');
    }
    expect(block.files).toEqual([{ path, kind: 'create' }]);
    expect(parts(messages[0])[0]).not.toHaveProperty('result');
    expect(project(JSON.parse(JSON.stringify(events)))).toBe(messages);
    events.push(event('tool-files-changed', 'write_file', { ordinal: 999, files: [{ path: 'wrong', kind: 'delete' }] }));
    expect(project(events)).toBe(messages);
  });
});
