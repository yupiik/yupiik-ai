import { describe, expect, it, vi } from 'vitest';
import { applyWebCommand } from './webCommands';

function controls() {
  return { openSession: vi.fn(), closeSession: vi.fn(), showTabs: vi.fn(), setTheme: vi.fn(), showSubagents: vi.fn() };
}

describe('web command actions', () => {
  it('opens new/resumed sessions through browser navigation and closes quit/exit through the tab controller', () => {
    const effects = controls();
    applyWebCommand({ command: 'open-session', id: 'new-session' }, effects);
    applyWebCommand({ command: 'open-session', id: 'saved-session' }, effects);
    expect(effects.openSession.mock.calls).toEqual([['new-session'], ['saved-session']]);
    applyWebCommand({ command: 'quit' }, effects);
    applyWebCommand({ command: 'exit' }, effects);
    expect(effects.closeSession).toHaveBeenCalledTimes(2);
    applyWebCommand({ command: 'tabs' }, effects);
    expect(effects.showTabs).toHaveBeenCalledOnce();
  });

  it('applies only browser-supported themes', () => {
    const effects = controls();
    applyWebCommand({ command: 'theme', arg: 'light' }, effects);
    applyWebCommand({ command: 'theme', arg: 'dark' }, effects);
    applyWebCommand({ command: 'theme', arg: 'invalid' }, effects);
    expect(effects.setTheme.mock.calls).toEqual([['light'], ['dark']]);
  });

  it('opens the subagent modal for empty, list, detail, and error content', () => {
    const effects = controls();
    for (const markdown of [undefined, '## Subagents\nreviewer', '### Conversation\nanswer', 'Unknown subagent: missing']) {
      applyWebCommand({ command: 'subagents', markdown }, effects);
    }
    expect(effects.showSubagents.mock.calls).toEqual([
      ['No subagents.'], ['## Subagents\nreviewer'], ['### Conversation\nanswer'], ['Unknown subagent: missing'],
    ]);
  });

  it('does not navigate for invalid payloads or interpret unknown commands', () => {
    const effects = controls();
    for (const data of [{ command: 'open-session' }, { command: 'open-session', id: '' }, { command: 'unknown' }]) {
      applyWebCommand(data, effects);
    }
    for (const effect of Object.values(effects)) {
      expect(effect).not.toHaveBeenCalled();
    }
  });
});
