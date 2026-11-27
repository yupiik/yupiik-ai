import { describe, expect, it } from 'vitest';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { ContextBar, quotaWindowLabel, formatQuotaReset } from './UsageBar';

describe('ContextBar semantic thresholds', () => {
  it.each([
    [0, false, 'success'], [69, false, 'success'], [70, false, 'warning'],
    [89, false, 'warning'], [90, false, 'error'], [100, false, 'error'],
    [100, true, 'success'], [26, true, 'success'], [25, true, 'warning'],
    [11, true, 'warning'], [10, true, 'error'], [0, true, 'error'],
  ] as const)('renders %s percent (remaining=%s) with the %s tone', (percent, remaining, tone) => {
    const html = renderToStaticMarkup(createElement(ContextBar, { percent, remaining }));
    expect(html).toContain(`background:var(--yca-${tone})`);
    expect(html).toContain(`width:${percent}%`);
  });

  it.each([[-5, 0], [105, 100]])('clamps %s to %s', (percent, expected) => {
    expect(renderToStaticMarkup(createElement(ContextBar, { percent }))).toContain(`width:${expected}%`);
  });
});

describe('quotaWindowLabel - derived from window duration', () => {
  it('maps weekly window to a readable label', () => {
    expect(quotaWindowLabel(604800, 'anything')).toBe('Weekly');
  });

  it('maps a 5-hour window to hours', () => {
    expect(quotaWindowLabel(18000, 'primary')).toBe('5h');
  });

  it('maps multi-day windows to days', () => {
    expect(quotaWindowLabel(2 * 86400, 'window')).toBe('2d');
  });

  it('maps sub-hour windows to minutes', () => {
    expect(quotaWindowLabel(60 * 30, 'primary')).toBe('30m');
  });

  it('falls back to minutes/seconds for irregular durations', () => {
    expect(quotaWindowLabel(90, 'primary')).toBe('90s');
  });

  it('falls back to the label when no window duration is available', () => {
    expect(quotaWindowLabel(null, 'custom label')).toBe('custom label');
    expect(quotaWindowLabel(null, '')).toBe('Quota');
  });
});

describe('formatQuotaReset - readable time to reset', () => {
  const now = 1_700_000_000;

  it('returns empty for missing or non-positive timestamp', () => {
    expect(formatQuotaReset(0, now)).toBe('now');
    expect(formatQuotaReset(-1, now)).toBe('now');
  });

  it('formats a reset in days and hours', () => {
    expect(formatQuotaReset(now + 2 * 86400 + 3 * 3600, now)).toBe('in 2d 3h');
  });

  it('formats a reset in hours and minutes', () => {
    expect(formatQuotaReset(now + 2 * 3600 + 15 * 60, now)).toBe('in 2h 15m');
  });

  it('formats a reset in minutes', () => {
    expect(formatQuotaReset(now + 600, now)).toBe('in 10m');
  });

  it('clamps to a minimum of 1 minute and handles now', () => {
    expect(formatQuotaReset(now, now)).toBe('now');
    expect(formatQuotaReset(now + 1, now)).toBe('in 1m');
  });
});
