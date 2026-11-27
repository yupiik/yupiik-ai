import { useEffect, useState } from 'react';
import { call } from '../api/rpc';
import { MemoryChips } from '../assistant/elements';
import { PageShell } from './PageShell';

type MemoryResult = {
  global: string;
  project: string;
  globalPath: string;
  projectPath: string;
  globalExists: boolean;
  projectExists: boolean;
};

type ScopeState = {
  content: string;
  path: string;
  exists: boolean;
};

function placeholder(content: string, exists: boolean): string {
  if (content) { return content; }
  return exists ? '(no facts yet)' : '(file not found)';
}

function Scope({ title, state }: { title: string; state: ScopeState }) {
  return (
    <section aria-label={`${title} memory`} className="min-w-0">
      <h2 className="mb-2 text-sm font-medium">{title}</h2>
      <MemoryChips
        className="mb-2 max-w-none"
        label="Memory file"
        chips={[{ id: title, text: state.exists ? 'File exists' : 'File not found', change: 'existing' }]}
      />
      {state.path && (
        <div
          className="mb-1 truncate font-mono text-[11px]"
          style={{ color: 'var(--yca-muted)' }}
          title={state.path}
        >
          {state.path}
        </div>
      )}
      <pre
        aria-label={`${title} raw memory`}
        className="whitespace-pre-wrap rounded border p-3"
        style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel)' }}
      >
        {placeholder(state.content, state.exists)}
      </pre>
    </section>
  );
}

export function MemoryPage() {
  const [state, setState] = useState<{ global: ScopeState; project: ScopeState; error?: string } | null>(null);

  useEffect(() => {
    void call<MemoryResult>('memory.get', {})
      .then((m) =>
        setState({
          global: { content: m.global, path: m.globalPath, exists: m.globalExists },
          project: { content: m.project, path: m.projectPath, exists: m.projectExists },
        }),
      )
      .catch((e: unknown) => setState({ global: { content: '', path: '', exists: false }, project: { content: '', path: '', exists: false }, error: e instanceof Error ? e.message : String(e) }));
  }, []);

  return (
    <PageShell title="Memory">
      {state?.error ? (
        <div className="rounded border p-3 text-sm" style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-error)' }}>
          <strong>memory.get failed:</strong> {state.error}
        </div>
      ) : !state ? (
        <p className="text-sm" style={{ color: 'var(--yca-muted)' }}>
          Loading…
        </p>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          <Scope title="Global" state={state.global} />
          <Scope title="Project" state={state.project} />
        </div>
      )}
    </PageShell>
  );
}
