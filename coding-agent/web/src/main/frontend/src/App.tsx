import { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router';
import { call } from './api/rpc';
import { SessionsPage } from './pages/SessionsPage';
import { ChatPage } from './pages/ChatPage';
import { CodeGraphPage } from './pages/CodeGraphPage';
import { McpPage } from './pages/McpPage';
import { ToolsPage } from './pages/ToolsPage';
import { MemoryPage } from './pages/MemoryPage';

type SessionSummary = { id: string; title: string; modifiedAt: number; sizeBytes: number };

export type Tab = { id: string; title: string; mode: 'plan' | 'build' };

export function App() {
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [activeSession, setActiveSession] = useState<string | undefined>();
  const [windowBlocks, setWindowBlocks] = useState(1024);
  const navigate = useNavigate();
  const location = useLocation();

  // Fetch the web command's tunable frontend settings on boot (transcript pagination window size).
  useEffect(() => {
    let cancelled = false;
    call<{ windowBlocks?: number }>('web.config')
      .then((cfg) => {
        if (cancelled) return;
        if (typeof cfg?.windowBlocks === 'number' && cfg.windowBlocks > 0) {
          setWindowBlocks(cfg.windowBlocks);
        }
      })
      .catch(() => {
        // keep the built-in default if the config call fails
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const openSession = (id: string, title: string) => {
    setTabs((prev) => {
      if (prev.some((t) => t.id === id)) return prev;
      return [...prev, { id, title, mode: 'plan' }];
    });
    setActiveSession(id);
    navigate(`/session/${id}`);
  };

  const createSession = async () => {
    const { id } = await call<{ id: string }>('sessions.create', {});
    openSession(id, 'new session');
  };

  const closeTab = (id: string) => {
    // Align on the TUI's closeTab: interrupting the in-flight turn (stop stream, kill tools/sub-agents)
    // and dropping its queued prompts, so a closed tab never leaves a turn running server-side to
    // completion nor resurrects interrupted work via the queue. Fire-and-forget so the tab removal and
    // navigation below never block on the RPC round-trip; the server-side cancelAll is idempotent for an
    // idle session (the cancelled flag resets on the next submit).
    call('session.cancelAll', { id }).catch(() => undefined);
    setTabs((prev) => prev.filter((t) => t.id !== id));
    // Compute the post-close destination OUTSIDE the state updater: setTabs updaters must be pure
    // (React may invoke them more than once / at unexpected times), so navigate/setActiveSession
    // must never be side-effects inside it or the navigation can fire on a stale snapshot.
    if (activeSession !== id) return;
    const next = tabs.filter((t) => t.id !== id);
    if (next.length > 0) {
      const target = next[next.length - 1].id;
      setActiveSession(target);
      navigate(`/session/${target}`);
    } else {
      setActiveSession(undefined);
      navigate('/');
    }
  };

  // Clicking a tab must switch the visible session too (navigate + activate), not just the badge,
  // otherwise the tabs appear "stuck" and other tabs look lost.
  const selectTab = (id: string) => {
    setActiveSession(id);
    navigate(`/session/${id}`);
  };

  // setTabMode/setTabTitle are intentionally stable (useCallback) even though they write App state:
  // they are passed down as onModeChange/onTitleChange, and ChatPage's loadSidebar depends on
  // onModeChange's identity. If they were recreated on every App render, that dependency would
  // regenerate loadSidebar each time -> useEffect([loadSidebar]) refires -> onModeChange called ->
  // new tabs array -> App re-renders -> ... an infinite refetch/render loop that looks like the
  // page "keeps refreshing even when nothing happens" (and the mode badge flapping to plan).
  const setTabMode = useCallback((id: string, mode: 'plan' | 'build') => {
    setTabs((prev) => prev.map((t) => (t.id === id ? { ...t, mode } : t)));
  }, []);

  const setTabTitle = useCallback((id: string, title: string) => {
    const t = (title || '').trim();
    if (!t) return;
    setTabs((prev) => prev.map((tab) => (tab.id === id ? { ...tab, title: t } : tab)));
    if (activeSession === id) {
      document.title = `${t} — YCA`;
    }
  }, [activeSession]);

  // Issue #1: a session opened by direct navigation / a page refresh / a rundown/back button on
  // /session/:id never went through SessionsPage's openSession, so its tab was never registered in
  // the TabBar even though the chat pane renders. Reconcile the tab list from the current route:
  // any /session/:id that is not already open gets a tab (with its persisted title, resolved
  // server-side), and the active session follows the route so the tab is visibly selected. Unknown
  // ids (typo / deleted session) are NOT given a tab - ChatPage's own sessions.get guard also drops
  // them, so a bogus deep link never pollutes the bar.
  const resolveSessionTitle = useCallback(async (id: string): Promise<string | null> => {
    try {
      const sessions = await call<SessionSummary[]>('sessions.list', { query: '', project: '' });
      const hit = sessions.find((s) => s.id === id);
      return hit ? hit.title || id : null;
    } catch {
      return null;
    }
  }, []);

  const pathname = location.pathname;
  useEffect(() => {
    const m = /^\/session\/([^/]+)$/.exec(pathname);
    if (!m) return;
    const id = decodeURIComponent(m[1]);
    setActiveSession(id);
    // already open -> nothing to do (the tab exists; title kept fresh by setTabTitle)
    if (tabs.some((t) => t.id === id)) return;
    let cancelled = false;
    resolveSessionTitle(id).then((title) => {
      if (cancelled) return;
      // still not open (openSession may have won the race while we awaited the title) and the
      // session actually exists -> register the tab. Unknown id -> leave the bar untouched.
      setTabs((prev) => {
        if (prev.some((t) => t.id === id)) return prev;
        if (title === null) return prev;
        return [...prev, { id, title, mode: 'plan' }];
      });
    });
    return () => {
      cancelled = true;
    };
    // pathname only: tabs changes (mode/title) must not re-run the effect, only navigation does.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pathname]);

  // Run once on boot: restore the sessions the web command was configured with (--session ...). Each
  // existing id gets a tab. We only navigate to a restored session when the user landed on the home
  // page; if they are already viewing a session (deep link / refresh on /session/Y), their current
  // route is left untouched so we never yank them away - that session's tab is still ensured.
  const restoreRanRef = useRef(false);
  useEffect(() => {
    if (restoreRanRef.current) return;
    restoreRanRef.current = true;
    let cancelled = false;
    (async () => {
      const ids = await call<string[]>('sessions.restore').catch(() => [] as string[]);
      if (cancelled || !Array.isArray(ids) || ids.length === 0) return;
      // resolve the persisted title server-side for each id (the same source SessionsPage uses)
      const sessions = await call<SessionSummary[]>('sessions.list', { query: '', project: '' }).catch(
        () => [] as SessionSummary[],
      );
      if (cancelled) return;
      const titleOf = new Map<string, string>();
      for (const s of sessions) titleOf.set(s.id, s.title || s.id);
      const atHome = /^\/?$/.test(pathname);
      const landed = ids.filter((id) => pathname === `/session/${id}`);
      setTabs((prev) => {
        const next = [...prev];
        for (const id of ids) {
          if (!next.some((t) => t.id === id)) next.push({ id, title: titleOf.get(id) || id, mode: 'plan' });
        }
        return next;
      });
      if (atHome && !landed.length && ids.length > 0) {
        // boot on the home page with restore configured: land on the first restored session's tab
        setActiveSession(ids[0]);
        navigate(`/session/${ids[0]}`);
      } else if (landed.length) {
        // already viewing one of the restored sessions: make sure its tab is active
        setActiveSession(landed[0]);
      }
    })();
    return () => {
      cancelled = true;
    };
    // run exactly once per App mount; pathname is read on mount only for the boot decision,
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [navigate]);

  return (
    <div className="flex h-full flex-col">
      <TabBar
        tabs={tabs}
        active={activeSession}
        onSelect={selectTab}
        onClose={closeTab}
        onCreate={createSession}
      />
      <div className="flex-1 overflow-hidden">
        <Routes>
          <Route path="/" element={<SessionsPage openSession={openSession} />} />
          <Route
            path="/session/:id"
            element={
              // key the page by session id so switching tabs remounts it with a clean slate:
              // the events / prompt / busy / pending-approval state never leaks across sessions.
              <ChatPage
                key={activeSession}
                activeSession={activeSession}
                openSession={openSession}
                tabs={tabs}
                closeSession={closeTab}
                onModeChange={setTabMode}
                onTitleChange={setTabTitle}
                windowBlocks={windowBlocks}
              />
            }
          />
          <Route path="/code-graph" element={<CodeGraphPage />} />
          <Route path="/memory" element={<MemoryPage />} />
          <Route path="/mcp" element={<McpPage />} />
          <Route path="/tools" element={<ToolsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </div>
    </div>
  );
}

const NAV_ITEMS = [
  { to: '/', label: 'Sessions' },
  { to: '/code-graph', label: 'Code graph' },
  { to: '/memory', label: 'Memory' },
  { to: '/mcp', label: 'MCP servers' },
  { to: '/tools', label: 'Tools' },
];

function TabBar({
  tabs,
  active,
  onSelect,
  onClose,
  onCreate,
}: {
  tabs: Tab[];
  active?: string;
  onSelect: (id: string) => void;
  onClose: (id: string) => void;
  onCreate: () => void;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const [tooltip, setTooltip] = useState<{ id: string; title: string; x: number; y: number } | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  // Dropdown root: clicking outside (or an <Esc>) closes the menu. The ref is on the wrappering
  // button + popover container so a click on the menu items themselves also counts as inside.
  const menuRef = useRef<HTMLDivElement>(null);
  const closeMenu = useCallback(() => setMenuOpen(false), []);
  useEffect(() => {
    if (!menuOpen) return;
    const onPointerDown = (e: PointerEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) closeMenu();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeMenu();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [menuOpen, closeMenu]);

  const go = (to: string) => {
    closeMenu();
    navigate(to);
  };

  return (
    <div className="relative flex items-center gap-1 border-b px-2 py-1" style={{ borderColor: 'var(--yca-border)' }}>
      <div ref={menuRef} className="relative">
        <div
          className="flex cursor-pointer items-center gap-1 rounded px-2 py-1 text-sm hover:bg-white/5"
          onMouseMove={(e) => setTooltip({ id: '__sessions', title: 'Sessions', x: e.clientX, y: e.clientY + 16 })}
          onMouseLeave={() => setTooltip(null)}
          onClick={() => setMenuOpen((o) => !o)}
          title="Sessions"
        >
          <span className="max-w-40 truncate">
            <span className="text-xs font-bold" style={{ color: 'var(--yca-muted)' }}>▤</span>{' '}
            Sessions <span className="text-[11px]" style={{ color: 'var(--yca-muted)' }}>▾</span>
          </span>
        </div>
        {menuOpen && (
          <div
            className="absolute left-0 top-full z-50 mt-1 min-w-44 overflow-hidden rounded border shadow-lg"
            style={{ background: 'var(--yca-panel)', borderColor: 'var(--yca-border)' }}
          >
            {NAV_ITEMS.map((item) => {
              const isActive = location.pathname === item.to;
              return (
                <div
                  key={item.to}
                  onClick={() => go(item.to)}
                  className={`flex cursor-pointer items-center px-3 py-1.5 text-sm ${
                    isActive ? 'text-primary' : ''
                  } hover:bg-white/5`}
                >
                  {item.label}
                </div>
              );
            })}
          </div>
        )}
      </div>
      <div className="mx-1 h-4 w-px" style={{ background: 'var(--yca-border)' }} />
      {tabs.map((tab) => (
        <div
          key={tab.id}
          className={`flex cursor-pointer items-center gap-1 rounded px-2 py-1 text-sm ${
            active === tab.id ? 'bg-white/10' : ''
          }`}
          onMouseMove={(e) => setTooltip({ id: tab.id, title: tab.title, x: e.clientX, y: e.clientY + 16 })}
          onMouseLeave={() => setTooltip(null)}
          onClick={() => {
            onSelect(tab.id);
            setTooltip(null);
          }}
          title={tab.title}
        >
          <span
            className="text-xs font-bold"
            style={{
              color: tab.mode === 'build' ? 'var(--yca-build)' : 'var(--yca-plan)',
            }}
          >
            {tab.mode === 'build' ? '◆' : '▪'}
          </span>
          <span className="max-w-40 truncate">{tab.title}</span>
          {active === tab.id && (
            <button
              className="ml-1 text-muted hover:text-[var(--yca-error)]"
              onClick={(e) => {
                e.stopPropagation();
                onClose(tab.id);
              }}
            >
              ×
            </button>
          )}
        </div>
      ))}
      <button
        onClick={onCreate}
        className="ml-1 rounded border px-2 py-0.5 text-sm hover:bg-white/5"
        style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-primary)' }}
        title="Open a new session"
      >
        + New
      </button>
      {tooltip && (
        <div
          className="pointer-events-none absolute z-50 rounded border px-2 py-1 text-xs shadow-lg"
          style={{
            left: tooltip.x,
            top: tooltip.y,
            background: 'var(--yca-panel)',
            borderColor: 'var(--yca-border)',
            color: 'var(--yca-text)',
          }}
        >
          {tooltip.title}
        </div>
      )}
    </div>
  );
}
