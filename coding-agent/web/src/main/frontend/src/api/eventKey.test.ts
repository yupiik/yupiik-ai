import { describe, expect, it } from 'vitest';
import { eventKey, dedupKey, type WebEvent } from './useSessionStream';

function ev(type: string, sessionId: string, message: string | null, data?: Record<string, unknown>): WebEvent {
  return { type, sessionId, subagentId: null, subagentName: null, message, data } as WebEvent;
}

describe('eventKey dedup identity (tool/usage occurrences must stay distinct)', () => {
  it('tool-finished for the same tool name but distinct ordinals do NOT collide', () => {
    const a = eventKey(ev('tool-finished', 's', 'bash', { output: 'out1', success: true, ordinal: 1 }));
    const b = eventKey(ev('tool-finished', 's', 'bash', { output: 'out2', success: true, ordinal: 2 }));
    expect(a).not.toBe(b);
  });

  it('tool-stream for the same tool name but distinct ordinals do NOT collide', () => {
    const a = eventKey(ev('tool-stream', 's', 'bash', { fragment: 'frag-a', ordinal: 1 }));
    const b = eventKey(ev('tool-stream', 's', 'bash', { fragment: 'frag-b', ordinal: 1 }));
    // same ordinal (same call) dedups; different ordinal (different call) is a distinct event
    const c = eventKey(ev('tool-stream', 's', 'bash', { fragment: 'frag-c', ordinal: 2 }));
    expect(a).toBe(b);
    expect(a).not.toBe(c);
  });

  it('tool-started with distinct ordinals do NOT collide (already supported)', () => {
    const a = eventKey(ev('tool-started', 's', 'bash', { ordinal: 1 }));
    const b = eventKey(ev('tool-started', 's', 'bash', { ordinal: 2 }));
    expect(a).not.toBe(b);
  });
});

describe('dedupKey (usage-updated / state-carrying events must survive the dedup)', () => {
  it('usage-updated frames with the same model stay distinct via the sequence', () => {
    const u1 = ev('usage-updated', 's', 'deepseek-v4-flash', { promptTokens: 100, contextPercent: 1 });
    const u2 = ev('usage-updated', 's', 'deepseek-v4-flash', { promptTokens: 999, contextPercent: 50 });
    // same model -> same base eventKey, but the sequence discriminator keeps each occurrence distinct
    expect(eventKey(u1)).toBe(eventKey(u2));
    expect(dedupKey(u1, 1)).not.toBe(dedupKey(u2, 2));
  });

  it('dedupKey leaves structural events (e.g. user) untouched regardless of seq', () => {
    const u = ev('user', 's', 'hello');
    expect(dedupKey(u, 1)).toBe(dedupKey(u, 99));
  });
});