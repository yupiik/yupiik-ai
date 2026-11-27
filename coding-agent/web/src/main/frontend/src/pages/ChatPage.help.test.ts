import { describe, expect, it } from 'vitest';
import { isHelpCommand, latestTranscriptMessage } from './ChatPage';

describe('isHelpCommand', () => {
  it('recognises /help and /help with trailing args', () => {
    expect(isHelpCommand('/help')).toBe(true);
    expect(isHelpCommand('/help ')).toBe(true);
    expect(isHelpCommand('/help anything')).toBe(true);
    expect(isHelpCommand('  /help')).toBe(true);
    expect(isHelpCommand('/HELP')).toBe(true);
  });

  it('rejects other slash commands and plain prompts', () => {
    expect(isHelpCommand('/memory')).toBe(false);
    expect(isHelpCommand('/tools')).toBe(false);
    expect(isHelpCommand('/help.md')).toBe(false);
    expect(isHelpCommand('/help/topic')).toBe(false);
    expect(isHelpCommand('/help-extra')).toBe(false);
    expect(isHelpCommand('//help')).toBe(false);
    expect(isHelpCommand('help me')).toBe(false);
    expect(isHelpCommand('help')).toBe(false);
    expect(isHelpCommand('')).toBe(false);
  });
});

describe('latestTranscriptMessage', () => {
  it('returns the newest transcript event content', () => {
    const events = [
      { type: 'status', message: 'working' },
      { type: 'transcript', message: '## commands' },
      { type: 'transcript', message: '## commands (newer)' },
    ];
    expect(latestTranscriptMessage(events)).toBe('## commands (newer)');
  });

  it('returns null when no transcript event is present', () => {
    expect(latestTranscriptMessage([{ type: 'status', message: 'ready' }])).toBeNull();
    expect(latestTranscriptMessage([])).toBeNull();
  });

  it('tolerates a transcript event with a null message', () => {
    expect(latestTranscriptMessage([{ type: 'transcript', message: null }])).toBe('');
  });
});
