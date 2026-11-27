import { useEffect, useMemo, useState } from 'react';
import { call } from '../api/rpc';
import { ThreadSearch } from '../assistant/elements';

type SessionSummary = { id: string; title: string; modifiedAt: number; sizeBytes: number; project?: string };

export function SessionsPage({ openSession }: { openSession: (id: string, title: string) => void }) {
  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [query, setQuery] = useState('');
  const [project, setProject] = useState('');
  const [activeId, setActiveId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    const load = async () => {
      try {
        // An empty project disables default current-project scoping. The server
        // searches session content too, so never re-filter results in the UI.
        const result = await call<SessionSummary[]>('sessions.list', { query, project: '' });
        if (!cancelled) { setSessions(result); }
      } catch (e) {
        if (!cancelled) { setError(e instanceof Error ? e.message : String(e)); }
      } finally {
        if (!cancelled) { setLoading(false); }
      }
    };
    // Initial/cleared search is immediate; typed queries debounce for 200ms.
    const timer = query ? window.setTimeout(() => { void load(); }, 200) : undefined;
    if (!query) { void load(); }
    return () => {
      cancelled = true;
      if (timer !== undefined) { window.clearTimeout(timer); }
    };
  }, [query]);

  useEffect(() => {
    let cancelled = false;
    void call<{ project: string }>('sessions.currentProject', {}).then(r => {
      if (!cancelled) { setProject(r.project); }
    }).catch(() => { /* The project badge is informational. */ });
    return () => { cancelled = true; };
  }, []);

  const create = async () => {
    try {
      const { id } = await call<{ id: string }>('sessions.create', {});
      openSession(id, 'new session');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const threads = useMemo(() => {
    const byProject = new Map<string, SessionSummary[]>();
    for (const session of sessions) {
      const key = (session.project || '').trim() || '(unknown project)';
      const list = byProject.get(key);
      if (list) { list.push(session); } else { byProject.set(key, [session]); }
    }
    const groups = [...byProject.entries()].map(([group, list]) => ({
      group,
      sessions: [...list].sort((a, b) => b.modifiedAt - a.modifiedAt),
    })).sort((a, b) => (b.sessions[0]?.modifiedAt ?? 0) - (a.sessions[0]?.modifiedAt ?? 0) || a.group.localeCompare(b.group));
    return groups.flatMap(({ group, sessions: list }) => list.map(session => ({
      id: session.id,
      title: session.title || session.id,
      group,
      preview: `${session.id} · ${formatSize(session.sizeBytes)}`,
    })));
  }, [sessions]);

  return (
    <div className="mx-auto h-full max-w-3xl overflow-y-auto p-6">
      <div className="yca-page-header flex items-center gap-2">
        <h1 className="flex-1">Sessions</h1>
        {project && <span title={project} className="max-w-xs truncate rounded border border-[var(--yca-border)] px-2 py-0.5 text-xs text-[var(--yca-muted)]">{project}</span>}
        <button type="button" onClick={() => { void create(); }} className="rounded border border-[var(--yca-primary)] px-3 py-1 text-sm text-[var(--yca-primary)]">+ New session</button>
      </div>
      {error && <p role="alert" className="mb-3 text-sm text-[var(--yca-error)]">Sessions request failed: {error}</p>}
      <ThreadSearch
        className="max-w-none"
        threads={error ? [] : threads}
        query={query}
        onQueryChange={setQuery}
        activeId={activeId}
        onActiveChange={setActiveId}
        onSelect={id => {
          const session = sessions.find(s => s.id === id);
          if (session) { openSession(session.id, session.title); }
        }}
        serverFiltered
        loading={loading}
        searchLabel="Search sessions"
        emptyLabel={error ? 'Sessions unavailable.' : 'No sessions.'}
        showGroupCounts
      />
    </div>
  );
}

function formatSize(bytes: number): string {
  if (bytes < 1024) { return `${bytes} B`; }
  if (bytes < 1024 * 1024) { return `${(bytes / 1024).toFixed(1)} KB`; }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
