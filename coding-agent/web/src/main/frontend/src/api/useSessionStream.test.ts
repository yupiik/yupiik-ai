import { describe, expect, it } from 'vitest';
import { dedupKey, foldLiveOutChars, foldStatus, bootstrapFromSnapshot, type WebEvent } from './useSessionStream';

function ev(type: string, message?: string | null): WebEvent {
  return { type, sessionId: 's1', message: message ?? null } as WebEvent;
}

function btwEv(data: Record<string, unknown>, message?: string | null): WebEvent {
  return { type: 'btw', sessionId: 's1', message: message ?? null, data } as WebEvent;
}

function usageEv(data: Record<string, unknown>, message?: string | null): WebEvent {
  return { type: 'usage-updated', sessionId: 's1', message: message ?? null, data } as WebEvent;
}

function statusEv(message: string | null, busy?: boolean): WebEvent {
  return { type: 'status', sessionId: 's1', message, data: busy === undefined ? {} : { busy } } as WebEvent;
}

describe('turn throughput', () => {
  const metrics = (data: Record<string, unknown>): WebEvent => ({ type: 'turn-metrics', sessionId: 's1', data });
  const complete = metrics({ requestTokensPerSecond: 12, tokensPerSecond: 99, serverTokensPerSecond: 30,
    completedRequests: 3, reportedRequests: 1, outputEstimated: true });

  it('prefers explicit request rate and folds server coverage and estimation', () => {
    expect(foldStatus([complete])).toMatchObject({ turnThroughputTokPerSec: 12,
      turnServerThroughputTokPerSec: 30, turnCompletedRequests: 3, turnReportedRequests: 1, turnOutputEstimated: true });
    expect(foldStatus([metrics({ tokensPerSecond: '7.5' })]).turnThroughputTokPerSec).toBe(7.5);
  });

  it.each([null, undefined, -1, NaN, Infinity, -Infinity, '', 'invalid', true])(
    'rejects invalid explicit rates without falling back to legacy or stale values: %s', (value) => {
      const s = foldStatus([complete, metrics({ requestTokensPerSecond: value, tokensPerSecond: 99,
        serverTokensPerSecond: value, completedRequests: value, reportedRequests: value })]);
      expect(s.turnThroughputTokPerSec).toBeUndefined();
      expect(s.turnServerThroughputTokPerSec).toBeUndefined();
      expect(s.turnCompletedRequests).toBeUndefined();
      expect(s.turnReportedRequests).toBeUndefined();
      expect(s.turnOutputEstimated).toBeUndefined();
    });

  it('replaces absent fields and preserves explicit zero', () => {
    const empty = foldStatus([complete, metrics({})]);
    expect(empty.turnThroughputTokPerSec).toBeUndefined();
    expect(empty.turnServerThroughputTokPerSec).toBeUndefined();
    expect(empty.turnCompletedRequests).toBeUndefined();
    expect(empty.turnReportedRequests).toBeUndefined();
    expect(empty.turnOutputEstimated).toBeUndefined();
    expect(foldStatus([complete, metrics({ requestTokensPerSecond: 0, tokensPerSecond: 99,
      serverTokensPerSecond: 0, completedRequests: 0, reportedRequests: 0, outputEstimated: false })]))
      .toMatchObject({ turnThroughputTokPerSec: 0, turnServerThroughputTokPerSec: 0,
        turnCompletedRequests: 0, turnReportedRequests: 0, turnOutputEstimated: false });
  });

  it('clears throughput only on a new busy transition, not repeated activity', () => {
    const events = [statusEv('start', true), complete, statusEv('activity', true)];
    expect(foldStatus(events).turnThroughputTokPerSec).toBe(12);
    const next = foldStatus([...events, statusEv('idle', false), statusEv('start', true)]);
    expect(next.turnThroughputTokPerSec).toBeUndefined();
    expect(next.turnServerThroughputTokPerSec).toBeUndefined();
    expect(next.turnCompletedRequests).toBeUndefined();
    expect(next.turnReportedRequests).toBeUndefined();
    expect(next.turnOutputEstimated).toBeUndefined();
    expect(foldStatus([complete, statusEv('reconnect', true)]).turnThroughputTokPerSec).toBeUndefined();
  });

  it('keeps successive metrics frames in the stream', () => {
    expect(dedupKey(complete, 1)).not.toBe(dedupKey(complete, 2));
  });
});

describe('foldLiveOutChars - realtime out estimate', () => {
  it('accumulates streamed text and thinking characters', () => {
    let acc = 0;
    acc = foldLiveOutChars(acc, ev('text-delta', 'Hello '));
    acc = foldLiveOutChars(acc, ev('thinking-delta', 'reasoning '));
    acc = foldLiveOutChars(acc, ev('text-delta', 'world'));
    expect(acc).toBe('Hello '.length + 'reasoning '.length + 'world'.length);
  });

  it('ignores non-stream action events (tool, status, usage)', () => {
    const acc = foldLiveOutChars(42, ev('tool-finished'));
    expect(acc).toBe(42);
  });

  it('resets to 0 at a turn boundary', () => {
    let acc = 100;
    acc = foldLiveOutChars(acc, ev('turn-done'));
    expect(acc).toBe(0);
  });

  it('resets on error and user turns too', () => {
    expect(foldLiveOutChars(50, ev('error'))).toBe(0);
    expect(foldLiveOutChars(50, ev('user', 'new question'))).toBe(0);
    expect(foldLiveOutChars(50, ev('turn-cancelled'))).toBe(0);
  });

  it('null message contributes nothing', () => {
    expect(foldLiveOutChars(7, ev('text-delta', null))).toBe(7);
  });
});

describe('foldStatus - btw side-question folding (latest-wins single slot)', () => {
  it('folds a running btw start into status.btw', () => {
    const s = foldStatus([btwEv({ id: 1, running: true, question: 'what is xyz?' })]);
    expect(s.btw).toEqual({ id: 1, running: true, question: 'what is xyz?', answer: undefined, error: undefined });
  });

  it('replaces the running slot with the finalized answer on completion', () => {
    const events = [
      btwEv({ id: 1, running: true, question: 'q' }),
      btwEv({ id: 1, running: false, question: 'q', answer: 'the answer' }),
    ];
    const s = foldStatus(events);
    expect(s.btw).toEqual({ id: 1, running: false, question: 'q', answer: 'the answer', error: undefined });
  });

  it('folds an error completion', () => {
    const s = foldStatus([btwEv({ id: 2, running: true, question: 'q' }), btwEv({ id: 2, running: false, question: 'q', error: 'boom' })]);
    expect(s.btw?.error).toBe('boom');
    expect(s.btw?.running).toBe(false);
  });

  it('a newer /btw overrides the previous one (latest wins)', () => {
    const events = [
      btwEv({ id: 1, running: true, question: 'first' }),
      btwEv({ id: 2, running: true, question: 'second' }),
    ];
    const s = foldStatus(events);
    expect(s.btw?.id).toBe(2);
    expect(s.btw?.question).toBe('second');
    expect(s.btw?.running).toBe(true);
  });

  it('falls back to the event message as the question when data.question is absent (snapshot legacy)', () => {
    const s = foldStatus([btwEv({ id: 3, running: true }, 'legacy question')]);
    expect(s.btw?.question).toBe('legacy question');
  });

  it('leaves status.btw undefined when no btw event is present', () => {
    const s = foldStatus([ev('status', 'working'), ev('usage-updated', 'model')]);
    expect(s.btw).toBeUndefined();
  });

  it('a cleared btw event (StatusBar x -> session.btwClear) removes the slot instead of respawning it', () => {
    // the clear broadcast carries {id, cleared:true} with no running/question: it must clear the
    // pill + modal, NOT reconstruct a fresh running btw from the message (the respawn bug).
    const s = foldStatus([
      btwEv({ id: 1, running: true, question: 'what is xyz?' }),
      btwEv({ id: 1, cleared: true }, 'what is xyz?'),
    ]);
    expect(s.btw).toBeUndefined();
  });

  it('a clear after an answer also removes the slot', () => {
    const s = foldStatus([
      btwEv({ id: 1, running: true, question: 'q' }),
      btwEv({ id: 1, running: false, question: 'q', answer: 'the answer' }),
      btwEv({ id: 1, cleared: true }),
    ]);
    expect(s.btw).toBeUndefined();
  });
});

describe('foldStatus - compaction lifecycle flag', () => {
  const noticeEv = (message: string, data: Record<string, unknown>): WebEvent => ({
    type: 'notice',
    sessionId: 's1',
    message,
    data,
  } as WebEvent);

  it('arms compacting on the progress notice and clears it on the result notice', () => {
    const s = foldStatus([
      noticeEv('Compaction in progress... (≈ 51234 estimated tokens)', { level: 'INFO', compacting: true }),
      noticeEv('Compacted: context 68% -> 41%', { level: 'INFO', compacting: false }),
    ]);
    expect(s.compacting).toBe(false);
  });

  it('stays arming while compaction is in flight without a result', () => {
    const s = foldStatus([
      noticeEv('Compaction in progress... (≈ 51234 estimated tokens)', { level: 'INFO', compacting: true }),
    ]);
    expect(s.compacting).toBe(true);
  });

  it('a gate escalation clears the compacting flag', () => {
    const s = foldStatus([
      noticeEv('Compaction in progress... (≈ 51234 estimated tokens)', { level: 'INFO', compacting: true }),
      noticeEv('Compaction gate: the context cannot be compacted below the trigger.', {
        level: 'ERROR',
        compacting: false,
      }),
    ]);
    expect(s.compacting).toBe(false);
  });

  it('is orthogonal to the busy flag (no busy frame involved)', () => {
    const s = foldStatus([noticeEv('Compaction in progress... (≈ 51234 estimated tokens)', { level: 'INFO', compacting: true })]);
    expect(s.compacting).toBe(true);
    expect(s.busy).toBeUndefined();
  });

  it('a turn boundary clears the pinned flag even without a result notice', () => {
    const s = foldStatus([
      noticeEv('Compaction in progress... (≈ 51234 estimated tokens)', { level: 'INFO', compacting: true }),
      ev('turn-cancelled'),
    ]);
    expect(s.compacting).toBe(false);
  });

  it('an unrelated (non-compaction) notice leaves the flag untouched', () => {
    const s = foldStatus([ev('notice', 'memory extraction done')]);
    expect(s.compacting).toBeUndefined();
  });
});

describe('foldStatus - live per-turn out/reasoning tokens', () => {
  it('reports the per-turn delta from cumulative usage while no turn-metrics yet', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 120, reasoningTokens: 40 }),
      usageEv({ completionTokens: 300, reasoningTokens: 90 }),
    ]);
    expect(s.busy).toBe(true);
    expect(s.liveOutTokens).toBe(300);
    expect(s.liveReasoningTokens).toBe(90);
  });

  it('resets the baseline at each new turn (false -> true) so the delta is per-turn', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 300, reasoningTokens: 90 }),
      statusEv('ready', false),
      statusEv('working', true),
      usageEv({ completionTokens: 420, reasoningTokens: 110 }),
    ]);
    expect(s.liveOutTokens).toBe(120);
    expect(s.liveReasoningTokens).toBe(20);
  });

  it('a new turn drops the previous turn authoritative metrics so the live delta shows', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 300 }),
      { type: 'turn-metrics', sessionId: 's1', message: null, data: { outputTokens: 300, reasoningTokens: 90 } } as WebEvent,
      statusEv('ready', false),
      statusEv('working', true),
      usageEv({ completionTokens: 340 }),
    ]);
    expect(s.outputTokens).toBeUndefined();
    expect(s.reasoningTokens).toBeUndefined();
    expect(s.liveOutTokens).toBe(40);
  });

  it('keeps counting from the same baseline across repeated busy:true activity frames mid-turn', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 100 }),
      statusEv('thinking...', true),
      usageEv({ completionTokens: 250 }),
      statusEv('calling bash', true),
    ]);
    expect(s.liveOutTokens).toBe(250);
    expect(s.liveReasoningTokens).toBeUndefined();
  });

  it('a turn-done ends the turn and the next usage is a fresh delta', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 300, reasoningTokens: 90 }),
      { type: 'turn-done', sessionId: 's1', message: 'done' } as WebEvent,
      statusEv('working', true),
      usageEv({ completionTokens: 380, reasoningTokens: 95 }),
    ]);
    expect(s.liveOutTokens).toBe(80);
    expect(s.liveReasoningTokens).toBe(5);
  });

  it('authoritative turn-metrics outputTokens still win over the live delta', () => {
    const s = foldStatus([
      statusEv('working', true),
      usageEv({ completionTokens: 300, reasoningTokens: 90 }),
      { type: 'turn-metrics', sessionId: 's1', message: null, data: { outputTokens: 300, reasoningTokens: 90 } } as WebEvent,
    ]);
    expect(s.outputTokens).toBe(300);
    expect(s.reasoningTokens).toBe(90);
    expect(s.liveOutTokens).toBe(300);
  });

  it('no live out when the provider reports no usage', () => {
    const s = foldStatus([statusEv('working', true)]);
    expect(s.liveOutTokens).toBeUndefined();
    expect(s.liveReasoningTokens).toBeUndefined();
  });

  it('a reloaded snapshot (replayed text only, no usage) still yields a log-derived out estimate', () => {
    // WebReplay replays only user/text/tool events - no usage-updated/turn-metrics. Without the
    // log-derived liveOutChars fallback the metrics bar would render an EMPTY token line after a
    // reload (all seven token sources null); with it, the replayed deltas give a non-zero "out".
    // A trailing turn-done (completed turn on reload) keeps the accumulated value for display.
    const s = foldStatus([
      ev('user', 'reload'),
      ev('text-delta', 'Old bursts were emitted and validated.'),
      ev('text-delta', ' Now the second burst.'),
      { type: 'turn-done', sessionId: 's1', message: null } as WebEvent,
    ]);
    expect(s.liveOutTokens).toBeUndefined(); // no provider usage replayed
    expect(s.completionTokens).toBeUndefined(); // no usage-updated replayed
    expect(s.liveOutChars).toBe('Old bursts were emitted and validated. Now the second burst.'.length);
  });

  it('a server-seeded synthetic usage-updated (snapshot seed) yields cumulative in/out immediately', () => {
    // WebSessionManager.snapshotFor now prepends a synthetic usage-updated carrying the session's
    // cumulative totals for reloaded tabs, so the metrics bar shows exact "in N · out N" on load.
    const s = foldStatus([usageEv({ promptTokens: 4000, completionTokens: 1200, reasoningTokens: 300, cacheReadTokens: 1200, contextPercent: 42 })]);
    expect(s.promptTokens).toBe(4000);
    expect(s.completionTokens).toBe(1200);
    expect(s.cacheReadTokens).toBe(1200);
    // no busy baseline yet, so the live out delta is the whole cumulative total
    expect(s.liveOutTokens).toBe(1200);
    expect(s.liveReasoningTokens).toBe(300);
  });

  it('cacheReadTokens stays absent when the provider publishes none', () => {
    const s = foldStatus([usageEv({ promptTokens: 4000, completionTokens: 1200 })]);
    expect(s.cacheReadTokens).toBeUndefined();
  });

  it('preserves raw prompt tokens while folding explicit full input, including fully cached input', () => {
    const s = foldStatus([usageEv({ promptTokens: 0, contextInputTokens: 5000, cacheReadTokens: 5000 })]);
    expect(s.promptTokens).toBe(0);
    expect(s.contextInputTokens).toBe(5000);
    expect(s.cacheReadTokens).toBe(5000);
  });

  it.each([undefined, null, 'invalid'])('falls back to the current frame prompt for full input %s', (contextInputTokens) => {
    const s = foldStatus([
      usageEv({ promptTokens: 100, contextInputTokens: 5000 }),
      usageEv({ promptTokens: 200, contextInputTokens }),
    ]);
    expect(s.promptTokens).toBe(200);
    expect(s.contextInputTokens).toBe(200);
  });

  it('keeps explicit zero full input instead of falling back to the raw prompt', () => {
    const s = foldStatus([usageEv({ promptTokens: 100, contextInputTokens: 0 })]);
    expect(s.promptTokens).toBe(100);
    expect(s.contextInputTokens).toBe(0);
  });

  it('does not reuse stale input when a usage frame carries no input metrics', () => {
    const s = foldStatus([
      usageEv({ promptTokens: 100, contextInputTokens: 5000 }),
      usageEv({ completionTokens: 20 }),
    ]);
    expect(s.contextInputTokens).toBeUndefined();
  });

  it('folds the account quota carried by a usage event', () => {
    const s = foldStatus([
      usageEv({
        promptTokens: 4000,
        quota: {
          enabled: true,
          supported: true,
          planType: 'plus',
          usedPercent: 42.5,
          windowSeconds: 18000,
          resetAtEpochSeconds: 1700000000,
          windowLabel: 'primary',
          creditsBalance: '10',
          creditsUnlimited: false,
          windows: [
            { kind: 'primary', label: 'primary', usedPercent: 37, windowSeconds: 18000, resetAtEpochSeconds: 1700000000 },
            { kind: 'secondary', label: 'secondary', usedPercent: 6, windowSeconds: 604800, resetAtEpochSeconds: 1700600000 },
          ],
        },
      }),
    ]);
    expect(s.quota?.supported).toBe(true);
    expect(s.quota?.planType).toBe('plus');
    expect(s.quota?.usedPercent).toBe(42.5);
    expect(s.quota?.creditsBalance).toBe('10');
    expect(s.quota?.windows).toHaveLength(2);
    expect(s.quota?.windows[1].windowSeconds).toBe(604800);
  });

  it('clears quota after an unsupported provider usage frame', () => {
    const s = foldStatus([
      usageEv({ quota: { enabled: true, supported: true, planType: 'plus', usedPercent: 22,
        windows: [{ kind: 'secondary', label: 'weekly', usedPercent: 22, windowSeconds: 604800, resetAtEpochSeconds: 1 }] } }),
      usageEv({ quota: { enabled: false, supported: false, planType: '', usedPercent: null, windows: [] } }),
    ]);
    expect(s.quota).toBeUndefined();
  });

  it('keeps a known-zero window distinct from a missing one', () => {
    const s = foldStatus([
      usageEv({
        quota: {
          enabled: true,
          supported: true,
          planType: 'plus',
          usedPercent: 0,
          windows: [
            { kind: 'primary', label: 'primary', usedPercent: 100, windowSeconds: 18000, resetAtEpochSeconds: 1 },
            { kind: 'secondary', label: 'weekly', usedPercent: 0, windowSeconds: 604800, resetAtEpochSeconds: 1 },
            { kind: 'additional', label: 'code-review', usedPercent: null, windowSeconds: 18000, resetAtEpochSeconds: 1 },
          ],
        },
      }),
    ]);
    // a known zero must stay 0 (a real, consumed window), never coerced to null
    expect(s.quota?.windows).toHaveLength(3);
    expect(s.quota?.windows[1].usedPercent).toBe(0);
    // a missing percentage stays null (no fabricated value)
    expect(s.quota?.windows[2].usedPercent).toBeNull();
  });

  it('restores quota from a synthetic snapshot-replay usage event', () => {
    // Reconnect replays the persisted snapshot as a usage-updated event carrying the quota; the fold
    // must surface both windows so a reloaded sidebar shows quota immediately.
    const s = foldStatus([
      usageEv({
        quota: {
          enabled: true,
          supported: true,
          planType: 'plus',
          usedPercent: 60,
          windows: [
            { kind: 'primary', label: 'primary', usedPercent: 40, windowSeconds: 18000, resetAtEpochSeconds: 1 },
            { kind: 'secondary', label: 'weekly', usedPercent: 10, windowSeconds: 604800, resetAtEpochSeconds: 1 },
          ],
        },
      }),
    ]);
    expect(s.quota?.windows).toHaveLength(2);
    expect(s.quota?.windows[1].windowSeconds).toBe(604800);
    expect(s.quota?.windows[1].usedPercent).toBe(10);
  });

  it('leaves status.quota undefined when no usage quota is present', () => {
    const s = foldStatus([
      usageEv({
        promptTokens: 4000,
        contextPercent: 12,
      }),
    ]);
    expect(s.quota).toBeUndefined();
  });

  it('log-derived out chars start fresh at each new user turn', () => {
    const s = foldStatus([
      ev('user', 'first turn'),
      ev('text-delta', 'alpha '),
      { type: 'turn-done', sessionId: 's1', message: null } as WebEvent,
      ev('user', 'second turn'),
      ev('text-delta', 'beta gamma'),
    ]);
    expect(s.liveOutChars).toBe('beta gamma'.length);
  });
});

describe('bootstrapFromSnapshot - bootstrapped (history-loaded) transition', () => {
  it('a snapshot with history bootstraps', () => {
    expect(bootstrapFromSnapshot({ events: [ev('user', 'hi')] })).toBe(true);
  });

  it('an EMPTY snapshot ({events:[]}) still bootstraps - a fresh/empty session must clear the loader, never spin forever', () => {
    expect(bootstrapFromSnapshot({ events: [] })).toBe(true);
  });

  it('a snapshot carrying snapshotError still bootstraps - error is surfaced, never a silent infinite loader', () => {
    // SseExchangeHandler returns {events:[], snapshotError} when the server-side replay fails: the
    // loader must clear (so the tab is usable) and the error is surfaced as a status notice instead of
    // leaving the session stuck on "Loading conversation…".
    expect(bootstrapFromSnapshot({ events: [], snapshotError: 'boom' })).toBe(true);
  });

  it('a snapshot frame missing the events key does NOT bootstrap (keeps the loader up)', () => {
    expect(bootstrapFromSnapshot({})).toBe(false);
  });

  it('malformed / non-object payloads do NOT bootstrap', () => {
    expect(bootstrapFromSnapshot(null)).toBe(false);
    expect(bootstrapFromSnapshot(undefined)).toBe(false);
    expect(bootstrapFromSnapshot('events')).toBe(false);
    expect(bootstrapFromSnapshot(7)).toBe(false);
  });
});
