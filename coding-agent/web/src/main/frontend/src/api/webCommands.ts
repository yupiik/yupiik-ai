export type WebCommandEffects = {
  openSession: (id: string) => void;
  closeSession: () => void;
  showTabs: () => void;
  setTheme: (theme: 'light' | 'dark') => void;
  showSubagents: (markdown: string) => void;
};

/** Interpret the web adapter's session-scoped UI actions independently of browser rendering. */
export function applyWebCommand(data: Record<string, unknown>, effects: WebCommandEffects): void {
  switch (data.command) {
    case 'open-session':
      if (typeof data.id === 'string' && data.id.length > 0) {
        effects.openSession(data.id);
      }
      break;
    case 'quit':
    case 'exit':
      effects.closeSession();
      break;
    case 'tabs':
      effects.showTabs();
      break;
    case 'theme':
      if (data.arg === 'light' || data.arg === 'dark') {
        effects.setTheme(data.arg);
      }
      break;
    case 'subagents':
      effects.showSubagents(String(data.markdown ?? 'No subagents.'));
      break;
  }
}
