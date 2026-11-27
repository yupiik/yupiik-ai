import { useEffect, useState } from 'react';
import { call } from '../api/rpc';
import { PageShell } from './PageShell';

type Hit = { fqn: string; kind: string; file?: string; startLine?: number; endLine?: number; signature?: string };

export function CodeGraphPage() {
  const [status, setStatus] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [symbol, setSymbol] = useState('');
  const [hits, setHits] = useState<Hit[] | null>(null);

  const load = () => {
    setLoading(true);
    call<{ status: string }>('codeGraph.status', {})
      .then((r) => setStatus(r.status))
      .catch((e) => setStatus(`error: ${(e as Error).message}`))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const resync = async () => {
    await call('codeGraph.resync', {});
    load();
  };

  const resolveSymbol = async () => {
    if (!symbol.trim()) return;
    setHits(null);
    try {
      const r = await call<{ hits: Hit[] }>('codeGraph.resolve', { symbol: symbol.trim() });
      setHits(r.hits ?? []);
    } catch (e) {
      setHits([]);
    }
  };

  return (
    <PageShell title="Code graph">
      <pre className="mb-4 whitespace-pre-wrap rounded border p-3" style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel)' }}>
        {loading ? '…' : status}
      </pre>
      <button onClick={resync} className="mb-6 rounded border px-3 py-1" style={{ borderColor: 'var(--yca-border)' }}>
        Resync
      </button>
      <div className="mb-2 flex gap-2">
        <input
          value={symbol}
          onChange={(e) => setSymbol(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && resolveSymbol()}
          placeholder="Search a symbol (fqn or simple name)"
          className="flex-1 rounded border px-3 py-1 outline-none"
          style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel)' }}
        />
        <button onClick={resolveSymbol} className="rounded border px-3 py-1" style={{ borderColor: 'var(--yca-border)' }}>
          Search
        </button>
      </div>
      {hits && (
        <ul className="space-y-1">
          {hits.map((h, i) => (
            <li key={i} className="rounded border px-3 py-1" style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel)' }}>
              <code className="text-primary">{h.fqn}</code>
              <span className="ml-2 text-xs" style={{ color: 'var(--yca-muted)' }}>{h.kind}</span>
              <div className="text-xs" style={{ color: 'var(--yca-muted)' }}>
                {h.file ? `${h.file}:${h.startLine ?? ''}` : ''}
              </div>
            </li>
          ))}
          {hits.length === 0 && <p style={{ color: 'var(--yca-muted)' }}>No symbols found.</p>}
        </ul>
      )}
    </PageShell>
  );
}