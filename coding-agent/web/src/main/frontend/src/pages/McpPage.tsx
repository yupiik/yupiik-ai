import { useCallback, useEffect, useRef, useState } from 'react';
import { call } from '../api/rpc';
import { McpServerPanel, mcpServerStatus } from '../assistant/elements';
import { PageShell } from './PageShell';

type McpStatus = { name: string; state: string; transport?: string; url?: string }[];

export function McpPage() {
  const [servers, setServers] = useState<McpStatus | null>(null);
  const [error, setError] = useState('');
  const [resetting, setResetting] = useState(false);
  const [expandedId, setExpandedId] = useState<string>();
  const mounted = useRef(false);

  const load = useCallback(async () => {
    setServers(null);
    setError('');
    try {
      const result = await call<McpStatus>('mcp.status', {});
      if (mounted.current) { setServers(result); }
    } catch (e) {
      if (mounted.current) { setError(e instanceof Error ? e.message : String(e)); }
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void load();
    return () => { mounted.current = false; };
  }, [load]);

  const reset = async (name?: string) => {
    setResetting(true);
    setError('');
    try {
      await call('mcp.reset', name ? { name } : {});
      if (mounted.current) { await load(); }
    } catch (e) {
      if (mounted.current) { setError(e instanceof Error ? e.message : String(e)); }
    } finally {
      if (mounted.current) { setResetting(false); }
    }
  };

  return (
    <PageShell title="MCP servers" fullWidth>
      {error && <p role="alert" className="mb-3 text-sm text-[var(--yca-error)]">MCP request failed: {error}</p>}
      {!servers && !error && <p role="status" className="text-sm text-[var(--yca-muted)]">Loading...</p>}
      {servers && <McpServerPanel
        className="max-w-none"
        servers={servers.map(server => ({ id: server.name, name: server.name, transport: server.transport, url: server.url, status: mcpServerStatus(server.state), stateLabel: server.state }))}
        expandedId={expandedId}
        onToggle={id => setExpandedId(current => current === id ? undefined : id)}
        onReset={name => { void reset(name); }}
        resetting={resetting}
      />}
      {servers?.length === 0 && <p className="mt-3 text-[var(--yca-muted)]">No MCP servers.</p>}
      <button type="button" disabled={resetting} onClick={() => { void reset(); }} className="mt-4 rounded border border-[var(--yca-border)] px-3 py-1 disabled:opacity-50">
        {resetting ? 'Resetting...' : 'Reset all'}
      </button>
    </PageShell>
  );
}
