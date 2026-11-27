import { describe, expect, it } from 'vitest';
import { eventsToBlocks, renderWindow, latestDrainBoundary, lossySupersedes } from './eventLog';
import type { WebEvent } from './useSessionStream';

function ev(partial: Partial<WebEvent> & { type: string; sessionId: string }): WebEvent {
  return { message: null, data: undefined, ...partial } as WebEvent;
}

describe('eventsToBlocks', () => {
  it('renders private diagnostics separately without contaminating tool results or completion', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's', message: 'mcp_example', data: { ordinal: 42 } }),
      ev({ type: 'tool-diagnostic', sessionId: 's', subagentId: 'child', subagentName: 'Worker', message: 'mcp_example', data: { ordinal: 42, detail: 'private failure\nsecond line' } }),
      ev({ type: 'tool-finished', sessionId: 's', message: 'mcp_example', data: { ordinal: 42, output: 'MCP failed', success: false } }),
      ev({ type: 'turn-done', sessionId: 's', message: 'final answer' }),
    ]);
    expect(blocks.find(b => b.kind === 'notice')).toMatchObject({
      content: '[Worker] mcp_example #42 diagnostic:\nprivate failure\nsecond line',
    });
    expect(blocks.find(b => b.kind === 'tool')).toMatchObject({ state: 'done', output: 'MCP failed', success: false });
    expect(blocks.flatMap(b => b.kind === 'text' ? [b.content] : []).join('')).not.toContain('private failure');
  });

  it('freezes captured feedback labels across deltas and final reconciliation without using them as identity', () => {
    const start = ev({ type: 'feedback-started', sessionId: 's1', message: 'text', data: { startedAt: 1000, speakerLabel: '[one] model' } });
    const text = ev({ type: 'text-delta', sessionId: 's1', message: 'answer' });
    const done = ev({ type: 'turn-done', sessionId: 's1', message: 'answer complete' });
    const blocks = eventsToBlocks([start, text, done]);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ startedAt: 1000, speakerLabel: '[one] model', content: 'answer complete', writing: false });
    expect(eventsToBlocks([{ ...start, data: { startedAt: 9000, speakerLabel: '[changed] model' } }, text, done])[0].id).toBe(blocks[0].id);
    expect(eventsToBlocks([text, done])[0]).not.toHaveProperty('startedAt');
  });

  it('starts a fresh block even for same-kind feedback and keeps replay boundaries and first times', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'text-delta', sessionId: 's1', message: 'first', data: { feedbackStart: true, startedAt: 1000, speakerLabel: '[first] model' } }),
      ev({ type: 'feedback-started', sessionId: 's1', message: 'text', data: { startedAt: 2000, speakerLabel: '[second] model' } }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'second' }),
      ev({ type: 'text-delta', sessionId: 's1', message: ' tail' }),
    ]);
    expect(blocks).toHaveLength(2);
    expect(blocks[0]).toMatchObject({ startedAt: 1000, content: 'first', writing: false });
    expect(blocks[1]).toMatchObject({ startedAt: 2000, content: 'second tail', writing: true });
  });

  it('retry reset discards failed timing with its partial content', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'feedback-started', sessionId: 's1', message: 'text', data: { startedAt: 1000 } }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'failed' }),
      ev({ type: 'text-reset', sessionId: 's1' }),
      ev({ type: 'feedback-started', sessionId: 's1', message: 'text', data: { startedAt: 2000 } }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'retry' }),
    ]);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ content: 'retry', startedAt: 2000 });
  });

  it('keeps default IDs deterministic across reconstruction and coalesced replay', () => {
    const events = [
      ev({ type: 'user', sessionId: 's1', message: 'dummy prompt' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'dummy ' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'answer' }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'inspect', data: { args: '{}', ordinal: 1 } }),
    ];
    const blocks = eventsToBlocks(events);
    expect(eventsToBlocks(JSON.parse(JSON.stringify(events)))).toEqual(blocks);
    const replayed = eventsToBlocks([
      events[0], ev({ type: 'text-delta', sessionId: 's1', message: 'dummy answer' }),
      { ...events[3], data: { args: '{}', ordinal: 73 } },
    ]);
    expect(replayed.map((block) => block.id)).toEqual(blocks.map((block) => block.id));
    const turn = JSON.stringify(['user\u0000s1\u0000\u0000dummy prompt\u0000\u0000\u0000', 0]);
    expect(blocks[0].id).toBe(JSON.stringify(['yca', 's1', turn, 'user']));
    const prepended = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'dummy prompt' }), ...events,
    ]);
    expect(prepended.slice(1).map((block) => block.id)).toEqual(blocks.map((block) => block.id));
  });

  it('allows a projector to supply IDs only for blocks that originate in the visible log', () => {
    const events = [
      ev({ type: 'user', sessionId: 's1', message: 'cleared' }),
      ev({ type: 'web-command', sessionId: 's1', data: { command: 'clear' } }),
      ev({ type: 'user', sessionId: 's1', message: 'dummy' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'one' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'two' }),
      ev({ type: 'turn-done', sessionId: 's1', message: 'onetwo' }),
    ];
    const origins: WebEvent[] = [];
    const blocks = eventsToBlocks(events, (event, kind) => {
      origins.push(event);
      return `${kind}:${origins.length}`;
    });
    expect(origins).toEqual([events[2], events[3]]);
    expect(blocks.map((block) => block.id)).toEqual(['user:1', 'text:2']);
    expect(blocks.map(({ id: _id, ...block }) => block)).toEqual(
      eventsToBlocks(events).map(({ id: _id, ...block }) => block),
    );
  });

  it('settles each stream kind when another kind takes over', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'thinking-delta', sessionId: 's1', message: 'thought' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'answer' }),
      ev({ type: 'thinking-delta', sessionId: 's1', message: 'next thought' }),
      ev({ type: 'turn-cancelled', sessionId: 's1' }),
    ]);
    expect(blocks.slice(0, 3)).toMatchObject([
      { kind: 'thought', writing: false }, { kind: 'text', writing: false }, { kind: 'thought', writing: false },
    ]);
  });
  it('accumulates text-delta events into a single assistant block', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'hi' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'Hello ' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'world' }),
    ]);
    expect(blocks).toHaveLength(2);
    expect(blocks[0]).toMatchObject({ kind: 'user', content: 'hi' });
    expect(blocks[1]).toMatchObject({ kind: 'text', content: 'Hello world' });
  });

  it('re-forms a compact seam split across deltas into one block content (render-chokepoint scrub relies on it)', () => {
    // the model echoed the injected "@N@" ref and the stream split it mid-tag; per-delta server
    // scrubbing could never catch the halves, so the reducer must accumulate them back into a
    // single "@535@" the Markdown component's stripEchoedRefs then removes at render time.
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'hi' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'answer @5' }),
      ev({ type: 'text-delta', sessionId: 's1', message: '35@ done' }),
    ]);
    expect(blocks[1]).toMatchObject({ kind: 'text', content: 'answer @535@ done' });
  });

  it('ignores todo-updated frames (they only trigger a sidebar reload)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'plan it' }),
      ev({ type: 'todo-updated', sessionId: 's1', data: { todos: [] } as never }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'done' }),
    ]);
    // todo-updated must not create a block nor disturb the transcript graph
    expect(blocks.map((b) => b.kind)).toEqual(['user', 'text']);
  });

  it('ignores btw events (they live in the status-bar pill + modal, never in the transcript)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'tell me something' }),
      ev({ type: 'btw', sessionId: 's1', message: 'what is xyz?', data: { id: 1, running: true, question: 'what is xyz?' } }),
      ev({ type: 'btw', sessionId: 's1', message: 'what is xyz?', data: { id: 1, running: false, question: 'what is xyz?', answer: 'something' } }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'the main answer' }),
    ]);
    // btw frames must not create a block nor disturb the transcript graph
    expect(blocks.map((b) => b.kind)).toEqual(['user', 'text']);
    expect(blocks[1]).toMatchObject({ kind: 'text', content: 'the main answer' });
  });

  it('renders a transcript event as its own notice block (Codex authorize URL)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'hi' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'Let me check.' }),
      ev({
        type: 'transcript',
        sessionId: 's1',
        message: '<https://auth.openai.com/authorize?code=abc>\n\nWaiting for authorization...',
      }),
    ]);
    // the login prompt is a standalone notice, never merged into the streamed assistant text
    expect(blocks.map((b) => b.kind)).toEqual(['user', 'text', 'notice']);
    expect(blocks[2]).toMatchObject({
      kind: 'notice',
      content: '<https://auth.openai.com/authorize?code=abc>\n\nWaiting for authorization...',
    });
  });

  it('skips the /executing-tools transcript inline (only-modal, never a notice block)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: '/executing-tools' }),
      ev({
        type: 'transcript',
        sessionId: 's1',
        message: '**Executing tools**\n\n| tool | command |\n| --- | --- |\n| bash | sleep 10 |',
      }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'the main answer' }),
    ]);
    // the executing-tools content lives only in the dedicated modal: no inline notice block is created
    expect(blocks.map((b) => b.kind)).toEqual(['user', 'text']);
  });

  it('keeps a preceding transcript notice intact when a later text-delta streams (no merge)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'transcript', sessionId: 's1', message: '<https://auth.openai.com/authorize>\n\nWaiting...' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'Signed in. Here is the answer.' }),
    ]);
    // the notice block is its own card; the assistant reply starts a fresh text block
    expect(blocks.map((b) => b.kind)).toEqual(['notice', 'text']);
    expect((blocks[0] as { content: string }).content).toContain('https://auth.openai.com/authorize');
    expect((blocks[1] as { content: string }).content).toBe('Signed in. Here is the answer.');
  });

  it('keeps a transcript notice when the turn finishes (turn-done never overwrites it)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'transcript', sessionId: 's1', message: '<https://auth.openai.com/authorize>\n\nWaiting...' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'Authorized. Running now.' }),
      ev({ type: 'turn-done', sessionId: 's1', message: 'Authorized. Running now.' }),
    ]);
    // turn-done consolidates only the trailing 'text' block; the login notice is preserved untouched
    expect(blocks.map((b) => b.kind)).toEqual(['notice', 'text']);
    expect((blocks[0] as { content: string }).content).toContain('https://auth.openai.com/authorize');
    expect((blocks[1] as { content: string }).content).toBe('Authorized. Running now.');
  });

  it('subagent-started pushes a running card finalized by subagent-completed (summary + model)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'subagent-started', sessionId: 's1', subagentId: 'sub-1', message: 'do the thing', data: { subagentName: 'child', subagentId: 'sub-1' } }),
      ev({ type: 'subagent-completed', sessionId: 's1', subagentId: 'sub-1', message: '## done\n- a\n- b', data: { success: true, cancelled: false, subagentId: 'sub-1', model: 'gpt-5.5' } }),
    ]);
    expect(blocks.map((b) => b.kind)).toEqual(['subagent']);
    const card = blocks[0] as Extract<typeof blocks[0], { kind: 'subagent' }>;
    expect(card).toMatchObject({
      kind: 'subagent',
      state: 'done',
      subagentId: 'sub-1',
      name: 'child',
      task: 'do the thing',
      summary: '## done\n- a\n- b',
      model: 'gpt-5.5',
    });
  });

  it('subagent-completed without a started (replay tail rotation) pushes a finalized card', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'subagent-completed', sessionId: 's1', subagentId: 'sub-9', message: 'already done', data: { success: true, cancelled: false, model: 'gpt-5.5' } }),
    ]);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ kind: 'subagent', state: 'done', subagentId: 'sub-9', summary: 'already done', model: 'gpt-5.5' });
  });

  it('subagent-completed failure and cancellation set their own states', () => {
    const failed = eventsToBlocks([
      ev({ type: 'subagent-started', sessionId: 's1', subagentId: 'a', message: 't', data: { subagentName: 'one' } }),
      ev({ type: 'subagent-completed', sessionId: 's1', subagentId: 'a', message: 'boom', data: { success: false, cancelled: false } }),
    ]);
    expect(failed[0]).toMatchObject({ kind: 'subagent', state: 'failed' });

    const cancelled = eventsToBlocks([
      ev({ type: 'subagent-started', sessionId: 's1', subagentId: 'b', message: 't', data: { subagentName: 'two' } }),
      ev({ type: 'subagent-completed', sessionId: 's1', subagentId: 'b', message: 'stopped', data: { success: false, cancelled: true } }),
    ]);
    expect(cancelled[0]).toMatchObject({ kind: 'subagent', state: 'cancelled' });
  });

  it.each([false, true])('merges replayed and live subagent completions into one card (replay first: %s)', replayFirst => {
    const replay = ev({
      type: 'subagent-completed', sessionId: 's1', subagentId: 'sub-1',
      message: '## Conclusion\n\n**Done**',
      data: { subagentId: 'sub-1', subagentName: 'active-instana-review', success: true, cancelled: false },
    });
    const live = ev({
      type: 'subagent-completed', sessionId: 's1', subagentId: 'sub-1',
      message: replay.message,
      data: { success: true, cancelled: false, model: 'review-model' },
    });
    const completions = replayFirst ? [replay, live] : [live, replay];
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'review it' }),
      ...completions,
      replay, // another reconnect must not create a second card or lose live model metadata
    ]);
    expect(blocks.map(b => b.kind)).toEqual(['user', 'subagent']);
    expect(blocks[1]).toMatchObject({
      kind: 'subagent', state: 'done', subagentId: 'sub-1', name: 'active-instana-review',
      summary: '## Conclusion\n\n**Done**', model: 'review-model',
    });
  });

  it('turn-done never settles a running subagent card (async children outlive the parent turn)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'subagent-started', sessionId: 's1', subagentId: 'sub-1', message: 'still working', data: { subagentName: 'child' } }),
      ev({ type: 'turn-done', sessionId: 's1', message: 'parent answer' }),
    ]);
    expect(blocks.map((b) => b.kind)).toEqual(['subagent', 'text']);
    expect(blocks[0]).toMatchObject({ kind: 'subagent', state: 'running' });
  });
});

describe('lossySupersedes', () => {
  it('is true for exact equality', () => {
    expect(lossySupersedes('same words', 'same words')).toBe(true);
  });

  it('is true when candidate adds words in between (candidate is a superset)', () => {
    // the real observed difference: the streamed tail dropped mid-text words
    expect(lossySupersedes('was emitted and validated', 'was and validated')).toBe(true);
    expect(lossySupersedes('No assistant-with-toolCalls found', 'Noalls found')).toBe(true);
    expect(lossySupersedes('No assistant-with-toolCalls found', 'No assistant found')).toBe(true);
  });

  it('is false when base has words absent from candidate (candidate is a subset)', () => {
    expect(lossySupersedes('shorter', 'a much longer base that candidate lacks')).toBe(false);
    expect(lossySupersedes('there were and validated', 'there were emitted and validated')).toBe(false);
  });

  it('handles empty inputs safely', () => {
    expect(lossySupersedes('', '')).toBe(false);
    expect(lossySupersedes('anything', '')).toBe(false);
    expect(lossySupersedes('', 'base')).toBe(false);
  });
});

describe('eventsToBlocks - replayed full-text supersedes the streamed tail (never duplicates)', () => {
  const streamedLossy = 'One because two Noalls found then three.';
  const complete = 'One because two No assistant-with-toolCalls found then three.';

  it('collapses a replayed full-text delta onto the same-turn streamed block and drops the replayed tool card', () => {
    const blocks = eventsToBlocks([
      // streamed live first (lossy, mid-text gap) + one tool card from that live round
      ev({ type: 'user', sessionId: 's1', message: 'bound it?' }),
      ev({ type: 'text-delta', sessionId: 's1', message: streamedLossy }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'write_todo', data: { ordinal: 5 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'write_todo', data: { ordinal: 5, output: 'ok', success: true } }),
      // reconnect snapshot re-delivers the same turn complete + a reconstructed tool card copy
      ev({ type: 'text-delta', sessionId: 's1', message: complete }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'write_todo', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'write_todo', data: { ordinal: 1, output: 'ok', success: true } }),
    ]);
    const texts = blocks.filter((b) => b.kind === 'text');
    const tools = blocks.filter((b) => b.kind === 'tool');
    // exactly one assistant answer, healed to the complete text
    expect(texts).toHaveLength(1);
    expect((texts[0] as { content: string }).content).toBe(complete);
    // and exactly one tool card (the replayed duplicate copy is dropped)
    expect(tools).toHaveLength(1);
  });

  it('does not collapse a genuinely new assistant answer in a multi-round turn', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'step it' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'Round one outcome.' }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 1, output: 'ok', success: true } }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'Round two final answer.' }),
    ]);
    const texts = blocks.filter((b): b is { kind: 'text'; content: string } => b.kind === 'text').map((b) => b.content);
    expect(texts).toEqual(['Round one outcome.', 'Round two final answer.']);
  });

  it('collapses an identical replayed text (equal superset) and drops the duplicate card', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'same answer' }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'write_todo', data: { ordinal: 2 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'write_todo', data: { ordinal: 2, output: 'ok', success: true } }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'same answer' }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'write_todo', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'write_todo', data: { ordinal: 1, output: 'ok', success: true } }),
    ]);
    const texts = blocks.filter((b) => b.kind === 'text');
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(texts).toHaveLength(1);
    expect((texts[0] as { content: string }).content).toBe('same answer');
    expect(tools).toHaveLength(1);
  });

  it('heals the exact observed lossy stream vs complete replay (s1a1402bcbbc)', () => {
    const limiter = 'Old bursts (before the last user prompt) were and validated when they were the current burst.';
    const full = 'Old bursts (before the last user prompt) were emitted and validated when they were the current burst.';
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'the O(n) matchedToolCallIds' }),
      ev({ type: 'text-delta', sessionId: 's1', message: limiter }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'write_todo', data: { ordinal: 9 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'write_todo', data: { ordinal: 9, output: 'ok', success: true } }),
      ev({ type: 'text-delta', sessionId: 's1', message: full }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'write_todo', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'write_todo', data: { ordinal: 1, output: 'ok', success: true } }),
    ]);
    const texts = blocks.filter((b) => b.kind === 'text');
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(texts).toHaveLength(1);
    // the lossy streamed block healed to the complete replayed text
    expect((texts[0] as { content: string }).content).toBe(full);
    expect(tools).toHaveLength(1);
  });

  it('never deletes a live RUNNING tool card when a mid-turn reconnect replays a prior round', () => {
    // Scenario: the tab is mid-turn with a bash call still executing (tool-started + tool-stream,
    // no tool-finished yet) when an SSE reconnect lands. The snapshot replays the PREVIOUS completed
    // round: its full text-delta lossy-supersedes the already-streamed answer, plus a reconstructed
    // duplicate tool card. The collapse must heal the text and drop ONLY the replayed duplicate card
    // (a done card), never the live running bash card that sits between them.
    const limiter = 'Old bursts (before the last user prompt) were and validated when they were the current burst.';
    const full = 'Old bursts (before the last user prompt) were emitted and validated when they were the current burst.';
    const blocks = eventsToBlocks([
      // live stream of the prior answer, lossy at the tail
      ev({ type: 'user', sessionId: 's1', message: 'step it' }),
      ev({ type: 'text-delta', sessionId: 's1', message: limiter }),
      // the CURRENT turn's tool is still running (no tool-finished yet) - must survive the collapse
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 5, args: '{"command":"mvn test"}' } }),
      ev({ type: 'tool-stream', sessionId: 's1', message: 'bash', data: { ordinal: 5, fragment: 'compiling...' } }),
      // reconnect snapshot re-delivers the prior round complete + a reconstructed duplicate card
      ev({ type: 'text-delta', sessionId: 's1', message: full }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'write_todo', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'write_todo', data: { ordinal: 1, output: 'ok', success: true } }),
    ]);
    const texts = blocks.filter((b) => b.kind === 'text');
    const tools = blocks.filter((b) => b.kind === 'tool');
    // the answer is healed to the complete text (single copy, no duplication)
    expect(texts).toHaveLength(1);
    expect((texts[0] as { content: string }).content).toBe(full);
    // the live running bash card is preserved (still running, with its streamed stdout) and the
    // replayed duplicate write_todo card is still present (the collapse dropped nothing live)
    expect(tools).toHaveLength(2);
    expect(tools[0]).toMatchObject({ name: 'bash', toolOrdinal: 5, state: 'running', content: 'compiling...\n' });
    expect(tools[1]).toMatchObject({ name: 'write_todo', toolOrdinal: 1, state: 'done' });
  });
});

describe('eventsToBlocks - repeated same-name tools bind to their own card via ordinal', () => {
  it('finished for bash#2 does not finish bash#1 and both settle done', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 2 } }),
      ev({ type: 'tool-stream', sessionId: 's1', message: 'bash', data: { ordinal: 2, fragment: 'out of #2' } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 2, output: 'done', success: true } }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools).toHaveLength(2);
    expect(tools[0]).toMatchObject({ toolOrdinal: 1, state: 'running' });
    expect(tools[1]).toMatchObject({ toolOrdinal: 2, state: 'done', success: true, content: 'out of #2\n' });
  });

  it('finished with an ordinal matches the exact started card, not the name-deque head', () => {
    // start bash#1 and bash#2, finish bash#1 FIRST (out of order relative to the deque)
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 2 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 1, output: 'first', success: true } }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ toolOrdinal: 1, state: 'done' });
    expect(tools[1]).toMatchObject({ toolOrdinal: 2, state: 'running' });
  });
});

describe('eventsToBlocks - turn boundaries settle still-running tool cards as interrupted', () => {
  it('turn-cancelled marks a tool left running as interrupted instead of working', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'read_file', data: { ordinal: 3 } }),
      ev({ type: 'turn-cancelled', sessionId: 's1' }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools).toHaveLength(1);
    expect(tools[0]).toMatchObject({ state: 'interrupted' });
    expect(tools[0].state).not.toBe('running');
  });

  it('turn-done settles a still-running tool card as interrupted even without message content', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'read_file', data: { ordinal: 1 } }),
      ev({ type: 'turn-done', sessionId: 's1', message: '' }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ state: 'interrupted' });
  });

  it('error settles still-running tool cards as interrupted', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'error', sessionId: 's1', message: 'boom' }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ state: 'interrupted' });
  });

  it('a tool-finished before the boundary still resolves to done (not interrupted)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'read_file', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'read_file', data: { ordinal: 1, output: 'ok', success: true } }),
      ev({ type: 'turn-cancelled', sessionId: 's1' }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ state: 'done', success: true });
  });

  it('a pending tool-finished is done-but-pending (renders neutral, not a failure)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'check_subagents' }),
      ev({
        type: 'tool-finished',
        sessionId: 's1',
        message: 'check_subagents',
        data: { ordinal: 1, output: '{"status":"pending"}', success: false, pending: true },
      }),
    ]);
    const tools = blocks.filter((b) => b.kind === 'tool');
    expect(tools[0]).toMatchObject({ state: 'done', success: false, pending: true });
  });
});

describe('eventsToBlocks - turn-done supersedes the streamed tail (never appends)', () => {
  const full = 'Answer with `code1`, `code2` and **bold** text.';

  it('a trailing-char difference no longer duplicates the whole answer', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: full }),
      ev({ type: 'turn-done', sessionId: 's1', message: full + '\n' }),
    ]);
    const b = blocks[blocks.length - 1];
    expect(b.kind).toBe('text');
    // the authoritative final content supersedes the streamed copy - not appended next to it
    expect((b as { content: string }).content).toBe(full + '\n');
  });

  it('a streamed prefix is extended in place by the final content (no duplication)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: full.slice(0, 20) }),
      ev({ type: 'turn-done', sessionId: 's1', message: full }),
    ]);
    const b = blocks[blocks.length - 1];
    expect((b as { content: string }).content).toBe(full);
  });

  it('a fuller streamed text (clipped provider reply) is kept, not truncated by turn-done', () => {
    const clip = full + ' MORE';
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: clip }),
      ev({ type: 'turn-done', sessionId: 's1', message: full }),
    ]);
    const b = blocks[blocks.length - 1];
    expect((b as { content: string }).content).toBe(clip);
  });

  it('a multi-round turn does not bleed the final content into an earlier round block', () => {
    const r1 = 'Before the tool. ';
    const r2 = 'After: `z`.';
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: r1 }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 1, output: 'ok', success: true } }),
      ev({ type: 'text-delta', sessionId: 's1', message: r2 }),
      ev({ type: 'turn-done', sessionId: 's1', message: r2 }),
    ]);
    const texts = blocks.filter((b): b is { kind: 'text'; content: string } => b.kind === 'text').map((b) => b.content);
    expect(texts).toEqual([r1, r2]);
  });

  it('arriving after a tool card (last block not text) pushes a fresh text block', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'bash', data: { ordinal: 1, output: 'ok', success: true } }),
      ev({ type: 'turn-done', sessionId: 's1', message: full }),
    ]);
    expect(blocks[blocks.length - 1]).toMatchObject({ kind: 'text', content: full });
  });

  it('streamed equal to final is a no-op (never duplicated)', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'user', sessionId: 's1', message: 'q' }),
      ev({ type: 'text-delta', sessionId: 's1', message: full }),
      ev({ type: 'turn-done', sessionId: 's1', message: full }),
    ]);
    const b = blocks[blocks.length - 1];
    expect((b as { content: string }).content).toBe(full);
  });
});

describe('eventsToBlocks - writing flag drives the debounced markdown render', () => {
  it('stamps writing=true while text-delta keeps appending, false once a non-delta settles it', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'text-delta', sessionId: 's1', message: 'built on ' }),
      ev({ type: 'text-delta', sessionId: 's1', message: '`8d41c8fd`' }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
    ]);
    expect(blocks[0]).toMatchObject({ kind: 'text', writing: false });
  });

  it('leaves a still-streaming text block flagged writing=true at the tail', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'text-delta', sessionId: 's1', message: 'built on `8d4' }),
      ev({ type: 'text-delta', sessionId: 's1', message: '1c8fd`' }),
    ]);
    expect(blocks[blocks.length - 1]).toMatchObject({ kind: 'text', writing: true });
  });
});

describe('eventsToBlocks - tool cards capture their args for language detection', () => {
  it('captures the raw arguments JSON from tool-started', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'read_file', data: { ordinal: 1, args: '{"path":"Foo.java"}' } }),
      ev({ type: 'tool-finished', sessionId: 's1', message: 'read_file', data: { ordinal: 1, output: 'class Foo {}', success: true } }),
    ]);
    const tool = blocks.find((b) => b.kind === 'tool') as { args?: string };
    expect(tool.args).toBe('{"path":"Foo.java"}');
  });

  it('omits args when tool-started carries none or a blank object', () => {
    const blocks = eventsToBlocks([
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash', data: { ordinal: 1 } }),
    ]);
    const tool = blocks.find((b) => b.kind === 'tool') as { args?: string };
    expect(tool.args).toBeUndefined();
  });
});

describe('renderWindow', () => {
  it('keeps everything when under the visible window', () => {
    const blocks = [1, 2, 3].map((n) => ({ kind: 'text' as const, content: String(n) }));
    expect(renderWindow(blocks, 1024)).toEqual({ start: 0, extended: false });
  });

  it('returns a start past the window when more blocks than visible', () => {
    const blocks = Array.from({ length: 20 }, (_, i) => ({ kind: 'text' as const, content: String(i) }));
    const r = renderWindow(blocks, 10);
    expect(r.extended).toBe(true);
    expect(r.start).toBe(10);
    expect(blocks.slice(r.start)).toHaveLength(10);
  });
});
describe('latestDrainBoundary', () => {
  it('returns null with no drain-relevant boundary present', () => {
    expect(latestDrainBoundary([
      ev({ type: 'user', sessionId: 's1', message: 'hi' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'ok' }),
    ])).toBeNull();
  });

  it('detects a prompt-integrated mid-stream even when it is NOT the last event (the queue-strip bug)', () => {
    // the loop keeps streaming status/text frames right after the prompt-integrated, so it is never
    // the tail - a tail-only check (the old behavior) missed it and left the queue block stale.
    const sig = latestDrainBoundary([
      ev({ type: 'user', sessionId: 's1', message: 'first' }),
      ev({ type: 'prompt-integrated', sessionId: 's1', message: 'queued one' }),
      ev({ type: 'text-delta', sessionId: 's1', message: 'answering…' }),
      ev({ type: 'usage-updated', sessionId: 's1' }),
    ]);
    expect(sig).toBe('prompt-integrated:s1:queued one');
  });

  it('prefers the most recent boundary scanning backward', () => {
    const sig = latestDrainBoundary([
      ev({ type: 'prompt-integrated', sessionId: 's1', message: 'older' }),
      ev({ type: 'text-delta', sessionId: 's1', message: '…' }),
      ev({ type: 'prompt-integrated', sessionId: 's1', message: 'newer' }),
      ev({ type: 'tool-started', sessionId: 's1', message: 'bash' }),
    ]);
    expect(sig).toBe('prompt-integrated:s1:newer');
  });

  it('uses prompt text to keep distinct queued prompts distinct', () => {
    const a = latestDrainBoundary([ev({ type: 'prompt-integrated', sessionId: 's1', message: 'q1' })]);
    const b = latestDrainBoundary([ev({ type: 'prompt-integrated', sessionId: 's1', message: 'q2' })]);
    expect(a).not.toBe(b);
  });

  it('turn boundaries are keyed by type+sessionId (no message)', () => {
    const sig = latestDrainBoundary([
      ev({ type: 'status', sessionId: 's1' }),
      ev({ type: 'turn-done', sessionId: 's1' }),
    ]);
    expect(sig).toBe('turn-done:s1');
  });
});

describe('captured tool file changes', () => {
  const files = [{ path: 'actual.java', kind: 'update', oldText: 'before\n', newText: 'after\n' }];
  const started = (ordinal: number, subagentId?: string) => ev({
    type: 'tool-started', sessionId: 's', subagentId, message: 'edit_file',
    data: { ordinal, args: '{"path":"requested-not-actual.java"}' },
  });
  const changed = (ordinal: number, subagentId?: string, message = 'edit_file') => ev({
    type: 'tool-files-changed', sessionId: 's', subagentId, message, data: { ordinal, files },
  });

  it('attaches captures before completion and retains them with the actual result', () => {
    const events = [started(1), changed(1)];
    const running = eventsToBlocks(events);
    expect(running).toHaveLength(1);
    expect(running[0]).toMatchObject({ kind: 'tool', state: 'running', files });
    events.push(ev({ type: 'tool-finished', sessionId: 's', message: 'edit_file', data: { ordinal: 1, output: 'actual result', success: true } }));
    expect(eventsToBlocks(events)[0]).toMatchObject({ state: 'done', files, output: 'actual result', success: true });
    expect(running[0]).toMatchObject({ state: 'running', files });
  });

  it('matches ordinal, name and agent scope, not the newest same-name card', () => {
    const blocks = eventsToBlocks([
      started(1), started(2), started(1, 'child'), started(1, 'other-child'),
      changed(1), changed(1, 'child'),
    ]);
    expect(blocks).toHaveLength(4);
    expect(blocks[0]).toMatchObject({ toolOrdinal: 1, files });
    expect(blocks[1]).not.toHaveProperty('files');
    expect(blocks[2]).toMatchObject({ subagentId: 'child', files });
    expect(blocks[3]).not.toHaveProperty('files');
  });

  it.each([
    [99, undefined, 'edit_file'],
    [1, 'unknown-child', 'edit_file'],
    [1, undefined, 'write_file'],
  ] as const)('ignores unmatched captures (%s, %s, %s)', (ordinal, scope, name) => {
    const blocks = eventsToBlocks([started(1), changed(ordinal, scope, name)]);
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).not.toHaveProperty('files');
  });

  it('ignores orphaned mutations and mutations after completion', () => {
    expect(eventsToBlocks([changed(1)])).toEqual([]);
    const blocks = eventsToBlocks([
      started(1),
      ev({ type: 'tool-finished', sessionId: 's', message: 'edit_file', data: { ordinal: 1, output: 'done' } }),
      changed(1),
    ]);
    expect(blocks[0]).toMatchObject({ state: 'done', output: 'done' });
    expect(blocks[0]).not.toHaveProperty('files');
  });

  it('retains metadata-only paths literally and drops malformed entries without inventing captures', () => {
    const path = '../<img src=x onerror=alert(1)>\n--- a/forged';
    const blocks = eventsToBlocks([
      started(1),
      ev({ type: 'tool-files-changed', sessionId: 's', message: 'edit_file', data: { ordinal: 1, files: [
        null, { path: 42, kind: 'update' }, { path, kind: 'update' },
        { path: 'incomplete', kind: 'create', newText: 'not an actual capture' },
      ] } }),
    ]);
    const card = blocks[0];
    if (card.kind !== 'tool') {
      throw new Error('Expected tool card');
    }
    expect(card.files).toEqual([{ path, kind: 'update' }, { path: 'incomplete', kind: 'create' }]);
  });
});
