// @vitest-environment happy-dom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionStatus } from '../api/useSessionStream';
import { TurnMetrics } from './ChatPage';

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
function render(status: SessionStatus) {
  act(() => root.render(<TurnMetrics status={{ turnLlmCalls: 3, ...status }} />));
  return host.textContent ?? '';
}

describe('TurnMetrics throughput', () => {
  it('labels estimated request throughput and partial server coverage independently', () => {
    expect(render({ turnThroughputTokPerSec: 12, turnServerThroughputTokPerSec: 30,
      turnOutputEstimated: true, turnCompletedRequests: 3, turnReportedRequests: 1 }))
      .toContain('request ~12.0 tok/s · server 30.0 tok/s (partial)');
  });

  it('does not mark exact request throughput or complete server coverage', () => {
    const text = render({ turnThroughputTokPerSec: 12, turnServerThroughputTokPerSec: 30,
      turnOutputEstimated: false, turnCompletedRequests: 3, turnReportedRequests: 3 });
    expect(text).toContain('request 12.0 tok/s · server 30.0 tok/s');
    expect(text).not.toContain('~');
    expect(text).not.toContain('(partial)');
  });

  it('can show either rate on its own and removes old rates on rerender', () => {
    expect(render({ turnThroughputTokPerSec: 12 })).toContain('request 12.0 tok/s');
    const serverOnly = render({ turnServerThroughputTokPerSec: 30 });
    expect(serverOnly).toContain('server 30.0 tok/s');
    expect(serverOnly).not.toContain('request');
    expect(serverOnly).not.toContain('(partial)');
    expect(render({})).not.toContain('tok/s');
  });

  it('hides zero and unavailable rates', () => {
    expect(render({ turnThroughputTokPerSec: 0, turnServerThroughputTokPerSec: 0 })).not.toContain('tok/s');
  });
});
