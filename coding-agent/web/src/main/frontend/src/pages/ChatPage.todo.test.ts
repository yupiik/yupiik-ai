import { describe, expect, it } from 'vitest';
import { todoDiffSummary, todoGlyph } from './ChatPage';

const t = (content: string, status: string) => ({ content, status });

describe('todoDebugSummary collapsed fallback (diff of the last todo change)', () => {
  it('returns null on the first snapshot (no prior state to diff)', () => {
    expect(todoDiffSummary(null, [t('a', 'pending')])).toBeNull();
  });

  it('flags a newly added item with its status glyph', () => {
    expect(todoDiffSummary([t('a', 'pending')], [t('a', 'pending'), t('b', 'pending')])).toBe('☐ b');
    expect(todoDiffSummary([t('a', 'pending')], [t('a', 'pending'), t('b', 'completed')])).toBe('☑ b');
  });

  it('flags a status change (e.g. a task marked done) with the new glyph', () => {
    expect(todoDiffSummary([t('a', 'pending')], [t('a', 'completed')])).toBe('☑ a');
    expect(todoDiffSummary([t('a', 'pending')], [t('a', 'in_progress')])).toBe('◐ a');
    expect(todoDiffSummary([t('a', 'in_progress')], [t('a', 'completed')])).toBe('☑ a');
    expect(todoDiffSummary([t('a', 'in_progress')], [t('a', 'cancelled')])).toBe('⊘ a');
  });

  it('flags a dropped item', () => {
    expect(todoDiffSummary([t('a', 'pending'), t('b', 'pending')], [t('a', 'pending')])).toBe('☐ b');
  });

  it('ignores a dropped cancelled item (no noisy removal)', () => {
    expect(todoDiffSummary([t('a', 'cancelled')], [])).toBeNull();
  });

  it('returns null when nothing changed', () => {
    const before = [t('a', 'pending'), t('b', 'completed')];
    const after = [t('a', 'pending'), t('b', 'completed')];
    expect(todoDiffSummary(before, after)).toBeNull();
  });
});

describe('todoGlyph', () => {
  it('maps each status to the TUI-style glyph', () => {
    expect(todoGlyph('pending')).toBe('☐');
    expect(todoGlyph('in_progress')).toBe('◐');
    expect(todoGlyph('completed')).toBe('☑');
    expect(todoGlyph('cancelled')).toBe('⊘');
    expect(todoGlyph('')).toBe('☐');
  });
});