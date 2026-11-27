import { describe, expect, it } from 'vitest';
import { eventsToBlocks, type Block } from '../api/eventLog';
import type { WebEvent } from '../api/useSessionStream';
import { isToolVisuallyFailed } from './toolPresentation';

type ToolBlock = Extract<Block, { kind: 'tool' }>;
const completed: ToolBlock = { kind: 'tool', name: 'bash', state: 'done', content: '', success: true };

describe('isToolVisuallyFailed', () => {
  it.each(['1', '42', '127', '-1', '0002'])('styles nonzero exit code %s as failed', (code) => {
    expect(isToolVisuallyFailed({ ...completed, output: `output\nExit code: ${code}` })).toBe(true);
  });

  it.each(['', '\n', '\r\n'])('accepts a footer with terminal line ending %j', (ending) => {
    expect(isToolVisuallyFailed({ ...completed, output: `Exit code: 1${ending}` })).toBe(true);
  });

  it.each([
    undefined, '', 'ordinary output', 'Exit code: 0', 'Exit code: -0', 'Exit code: 000',
    'Exit code: ', 'Exit code: nope', 'Exit code: 1.5', 'Exit code: 1e2', 'Exit code: 0x1',
    'Exit code: 1 trailing', 'Exit code: 1 ', ' Exit code: 1', 'prefix Exit code: 1',
    'exit code: 1', 'Exit code:1', 'Exit code:  1', 'Exit code: 1\nmore output',
    'Exit code: 1\n\n', 'Exit code: 1\r', 'Exit code: 1\nExit code: 0', 'Exit code: 999999999999',
  ])('preserves successful styling for missing, zero or malformed footer %j', (output) => {
    expect(isToolVisuallyFailed({ ...completed, output })).toBe(false);
  });

  it('does not infer failure from nonbash output or a bash-like name', () => {
    for (const name of ['read_file', 'check_subagents', 'functions.bash', 'Bash']) {
      expect(isToolVisuallyFailed({ ...completed, name, output: 'Exit code: 1' })).toBe(false);
    }
  });

  it('preserves running, interrupted and pending presentation, even for explicit failures', () => {
    for (const success of [true, false]) {
      for (const state of ['running', 'interrupted'] as const) {
        expect(isToolVisuallyFailed({ ...completed, state, success, output: 'Exit code: 1' })).toBe(false);
      }
      expect(isToolVisuallyFailed({ ...completed, pending: true, success, output: 'Exit code: 1' })).toBe(false);
    }
  });

  it('keeps explicit tool failures red regardless of tool or footer', () => {
    expect(isToolVisuallyFailed({ ...completed, name: 'read_file', success: false })).toBe(true);
    expect(isToolVisuallyFailed({ ...completed, success: false, output: 'Exit code: 0' })).toBe(true);
  });

  it('uses the full final output, never the live stream or truncated preview', () => {
    expect(isToolVisuallyFailed({ ...completed, content: 'Exit code: 1' })).toBe(false);
    expect(isToolVisuallyFailed({ ...completed, content: 'Exit code: 1', output: 'Exit code: 0' })).toBe(false);
    expect(isToolVisuallyFailed({ ...completed, output: `${'x'.repeat(5000)}\nExit code: 1` })).toBe(true);
  });

  it.each([true, false])('styles live/replayed results without mutating success (live=%s)', (live) => {
    const events: WebEvent[] = [
      { type: 'tool-started', sessionId: 'session', message: 'bash', data: { args: '{"command":"false"}', ordinal: 1 } },
      ...(live ? [{ type: 'tool-stream', sessionId: 'session', message: 'bash', data: { fragment: 'live output', ordinal: 1 } }] : []),
      { type: 'tool-finished', sessionId: 'session', message: 'bash', data: { output: 'Exit code: 1', success: true, ordinal: 1 } },
    ];
    const originalEvents = structuredClone(events);
    const blocks = eventsToBlocks(events);
    const block = blocks[0];
    expect(block.kind).toBe('tool');
    if (block.kind !== 'tool') {
      throw new Error('Expected tool card');
    }
    const originalBlock = structuredClone(block);
    expect(isToolVisuallyFailed(block)).toBe(true);
    expect(block.success).toBe(true);
    expect(block).toEqual(originalBlock);
    expect(events).toEqual(originalEvents);
  });
});
