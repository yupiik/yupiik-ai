import { useEffect, useRef, useState } from 'react';
import { call } from '../api/rpc';
import { PageShell } from './PageShell';

type ToolListing = {
  anyTool: boolean;
  servers: { server: string; tools: { name: string; description?: string | null }[] }[];
  matches: number;
};

export function ToolsPage() {
  const [listing, setListing] = useState<ToolListing | null>(null);
  const [query, setQuery] = useState('');

  const load = (q: string) => {
    call<ToolListing>('tools.list', { query: q }).then(setListing).catch(() => setListing(null));
  };

  // immediate load on mount, then debounced reloads as the filter is typed so a fast typist doesn't
  // fire one /api/rpc round-trip per keystroke (skip the mount run of the query effect below).
  const isFirstRun = useRef(true);
  useEffect(() => {
    load(query);
    isFirstRun.current = false;
  }, []);

  useEffect(() => {
    if (isFirstRun.current) return;
    if (query === '') {
      load(query);
      return;
    }
    const t = window.setTimeout(() => load(query), 200);
    return () => window.clearTimeout(t);
  }, [query]);

  return (
    <PageShell title="Tools">
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Filter (server_tool)…"
        className="mb-4 w-full rounded border px-3 py-1 outline-none"
        style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel)' }}
      />
      {listing && (
        <div className="grid gap-4 md:grid-cols-2">
          {listing.servers.map((server) => (
            <div key={server.server}>
              <h2 className="mb-1 text-xs font-semibold uppercase" style={{ color: 'var(--yca-muted)' }}>
                {server.server}
              </h2>
              <ul className="space-y-1">
                {server.tools.map((t) => (
                  <li key={`${server.server}_${t.name}`} className="rounded border px-3 py-1" style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel)' }}>
                    <code>{t.name}</code>
                    {t.description && (
                      <div className="text-xs" style={{ color: 'var(--yca-muted)' }}>{t.description}</div>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
      {listing && listing.servers.length === 0 && (
        <p style={{ color: 'var(--yca-muted)' }}>{listing.anyTool ? 'No matching tools.' : 'No tools available.'}</p>
      )}
    </PageShell>
  );
}