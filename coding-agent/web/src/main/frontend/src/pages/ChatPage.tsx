import { ContextBar, formatQuotaReset, quotaWindowLabel } from '../components/UsageBar';
import { createContext, forwardRef, memo, useCallback, useContext, useEffect, useImperativeHandle, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router';
import { ToolCard } from '../components/ToolCard';
import type { DiffMode } from '../assistant/tools/DiffViewer';
import { VisibilityMenu, type Visibility } from '../components/VisibilityMenu';
import { MarkdownContent } from '../components/MarkdownContent';
import { SessionMetadata } from '../components/SessionMetadata';
import { call } from '../api/rpc';
import { SessionRequests, useInteractiveRequests, type RequestState } from '../assistant/actions';
import { useSessionStream, type WebEvent, type SessionStatus } from '../api/useSessionStream';
import {
  renderWindow,
  latestDrainBoundary,
  LOAD_EARLIER_CHUNK,
  type Block,
} from '../api/eventLog';
import { applyWebCommand } from '../api/webCommands';
import { useSlashCompletion, applySuggestion } from '../api/useSlashCompletion';
import { normalizeMarkdown, hasOpenInlineSpan, stripEchoedRefs } from '../lib/markdown';
import { ComposerPrimitive, ThreadPrimitive, MessagePrimitive, useAuiState } from '@assistant-ui/react';
import { YcaRuntime, ComposerDraft } from '../assistant/YcaRuntime';
import { SlashPopover } from '../assistant/SlashPopover';
import { Reasoning } from '../assistant/Reasoning';
import { TodoList, McpServerPanel, mcpServerStatus, SubagentActivity } from '../assistant/elements';

// resizable sidebar width bounds and default (px), persisted in localStorage like the theme
const SIDEBAR_DEFAULT_WIDTH = 256; // matches the historical w-64
const SIDEBAR_MIN_WIDTH = 176;
const SIDEBAR_MAX_WIDTH = 560;
// prompt input resize bounds (px); 0 means "fill the available width" (the default before resizing)
const PROMPT_MIN_WIDTH = 240;
const PROMPT_MAX_WIDTH = 1200;
// prompt input vertical resize bounds (px); 0 means the natural auto height (2 rows, the default before
// dragging). The max is bounded both absolutely and by a share of the window so the input can never
// grow taller than the viewport leaves for the transcript.
const PROMPT_MIN_HEIGHT = 56; // roughly the natural 2-row height with padding
const PROMPT_MAX_HEIGHT = 360;
const PROMPT_MAX_HEIGHT_VH = 0.5; // hard ceiling: half the viewport height
const promptMaxHeight = () => Math.min(PROMPT_MAX_HEIGHT, Math.floor(window.innerHeight * PROMPT_MAX_HEIGHT_VH));

type TodoItem = { content: string; status: string };
type McpServerInfo = { name: string; state: string; transport?: string; url?: string };
type SidebarInfo = {
  project?: string;
  gitBranch?: string;
  todos: TodoItem[];
  mode: string;
  permissionMode?: string;
  sandboxEnabled?: boolean;
  model?: string;
  flavor?: string;
  provider?: string;
  mcpServers?: McpServerInfo[];
  modelCandidates?: string[];
  providerCandidates?: string[];
};

// the raw backend payload of session.metadata (SidebarInfo minus the derived todos/project)
type SidebarMeta = {
  mode?: string;
  permissionMode?: string;
  sandboxEnabled?: boolean;
  model?: string;
  flavor?: string;
  provider?: string;
  gitBranch?: string;
  cwd?: string;
  mcpServers?: McpServerInfo[];
  modelCandidates?: string[];
  providerCandidates?: string[];
};

export function ChatPage({
  activeSession,
  openSession,
  tabs = [],
  closeSession,
  onModeChange,
  onTitleChange,
  windowBlocks = 1024,
}: {
  activeSession?: string;
  openSession: (id: string, title: string) => void;
  tabs?: { id: string; title: string }[];
  closeSession?: (id: string) => void;
  onModeChange?: (id: string, mode: 'plan' | 'build') => void;
  onTitleChange?: (id: string, title: string) => void;
  windowBlocks?: number;
}) {
  const { id } = useParams<{ id: string }>();
  const sessionId = id ?? activeSession;
  const { events, connected, bootstrapped, reconnecting, status, moreReplayExists, loadEarlier: loadServerEarlier } =
    useSessionStream(sessionId);
  // true while a session page has mounted but its persisted history (the SSE snapshot) has not been
  // delivered yet: the pane is still empty and chat must stay locked so the user never races the
  // load. False once bootstrapped, or when there is no session at all.
  const initializing = !!sessionId && !bootstrapped;
  const [prompt, setPrompt] = useState('');
  const [cursor, setCursor] = useState(0);
  // Transient flag set while the composer session.input RPC (including routing pickers) is in flight.
  const [sending, setSending] = useState(false);
  // the real "a turn is running" signal comes from the SSE stream; combine both
  const busy = status.busy || sending;
  const [sidebarOpen, setSidebarOpen] = useState(() => typeof window === 'undefined' || window.innerWidth >= 768);
  useEffect(() => {
    const onResize = () => {
      if (window.innerWidth < 768) {
        setSidebarOpen(false);
      }
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);
  // persist the resizable sidebar width (px) across reloads, like the theme setting
  const [sidebarWidth, setSidebarWidth] = useState(() => {
    const saved = typeof localStorage !== 'undefined' ? localStorage.getItem('yca-sidebar-width') : null;
    const n = saved ? Number(saved) : NaN;
    return Number.isFinite(n) && n >= SIDEBAR_MIN_WIDTH ? n : SIDEBAR_DEFAULT_WIDTH;
  });
  // persist the sidebar width whenever it changes (clamped to its bounds)
  useEffect(() => {
    if (typeof localStorage !== 'undefined') localStorage.setItem('yca-sidebar-width', String(sidebarWidth));
  }, [sidebarWidth]);
  // drag state for the sidebar resize handle (pointer capture keeps a stable clientX even if the
  // cursor leaves the handle while dragging)
  const sidebarDrag = useRef<{ startX: number; startWidth: number } | null>(null);
  // prompt input width (px): horizontally resizable via a drag handle on its left edge, persisted.
  const [promptWidth, setPromptWidth] = useState(() => {
    const saved = typeof localStorage !== 'undefined' ? localStorage.getItem('yca-prompt-width') : null;
    const n = saved ? Number(saved) : NaN;
    return Number.isFinite(n) && n >= PROMPT_MIN_WIDTH ? n : 0; // 0 = fill the available flex space
  });
  useEffect(() => {
    if (typeof localStorage !== 'undefined' && promptWidth > 0) localStorage.setItem('yca-prompt-width', String(promptWidth));
  }, [promptWidth]);
  const promptDrag = useRef<{ startX: number; startWidth: number } | null>(null);
  // prompt input height (px): vertically resizable via a drag handle on its top edge, persisted.
  const [promptHeight, setPromptHeight] = useState(() => {
    const saved = typeof localStorage !== 'undefined' ? localStorage.getItem('yca-prompt-height') : null;
    const n = saved ? Number(saved) : NaN;
    return Number.isFinite(n) && n >= PROMPT_MIN_HEIGHT ? Math.min(n, promptMaxHeight()) : 0; // 0 = natural auto height
  });
  useEffect(() => {
    if (typeof localStorage !== 'undefined' && promptHeight > 0) localStorage.setItem('yca-prompt-height', String(promptHeight));
  }, [promptHeight]);
  const promptVDrag = useRef<{ startY: number; startHeight: number } | null>(null);
  const [sidebar, setSidebar] = useState<SidebarInfo>({ todos: [], mode: 'plan' });
  // number of prompts currently queued behind the running turn (see the queue strip in the bottom bar)
  const [queuedCount, setQueuedCount] = useState(0);
  // the queued prompts themselves (oldest first), so each can be inspected and removed with confirmation
  const [queuedPrompts, setQueuedPrompts] = useState<string[]>([]);
  const refreshQueued = useCallback(() => {
    if (!sessionId) {
      setQueuedCount(0);
      setQueuedPrompts([]);
      return;
    }
    Promise.all([
      call<{ count: number }>('session.queuedCount', { id: sessionId }).catch(() => ({ count: 0 })),
      call<{ prompts: string[] }>('session.queuedList', { id: sessionId }).catch(() => ({ prompts: [] })),
    ]).then(([count, list]) => {
      setQueuedCount(typeof count?.count === 'number' ? count.count : 0);
      setQueuedPrompts(Array.isArray(list?.prompts) ? list.prompts : []);
    });
  }, [sessionId]);
  // remove one queued prompt (oldest first index) with a confirmation; refresh the strip after.
  useEffect(() => {
    if (!initializing) {
      refreshQueued();
    }
  }, [initializing, refreshQueued]);

  const removeQueued = useCallback(
    async (index: number) => {
      if (!sessionId) return;
      const prompt = queuedPrompts[index];
      if (prompt == null) return;
      if (!window.confirm(`Remove this queued prompt?\n\n${prompt}`)) return;
      await call('session.queuedRemove', { id: sessionId, index }).catch(() => undefined);
      refreshQueued();
    },
    [sessionId, queuedPrompts, refreshQueued],
  );
  // global tool visibility: 'shown' | 'collapsed' | 'hidden' (mirrors TUI /tool-output states)
  const [toolVisibility, setToolVisibility] = useState<Visibility>('collapsed');
  const [diffMode, setDiffMode] = useState<DiffMode>('auto');
  // Disclosure is display-only; /thinking still owns backend show/hide and never drops the log.
  const [thoughtVisibility, setThoughtVisibility] = useState<Visibility>('collapsed');
  const [metrics, setMetrics] = useState({ perTurn: true, session: true, full: false });
  const [subagentsContent, setSubagentsContent] = useState<string | null>(null);
  const [subagentReference, setSubagentReference] = useState('');
  const [tabsOpen, setTabsOpen] = useState(false);
  const handledCommands = useRef(new WeakSet<WebEvent>());
  useEffect(() => {
    for (const event of events) {
      if (handledCommands.current.has(event)) {
        continue;
      }
      handledCommands.current.add(event);
      const data = event.data ?? {};
      if (event.type === 'settings') {
        if (typeof data.showThinking === 'boolean') {
          setThoughtVisibility(current => data.showThinking ? (current === 'hidden' ? 'collapsed' : current) : 'hidden');
        }
        if (data.showToolOutput === 'shown' || data.showToolOutput === 'collapsed' || data.showToolOutput === 'hidden') {
          setToolVisibility(data.showToolOutput);
        }
        if (data.diffViewMode === 'auto' || data.diffViewMode === 'side-by-side' || data.diffViewMode === 'simple') {
          setDiffMode(data.diffViewMode);
        }
        if (typeof data.metricsPerTurn === 'boolean' && typeof data.metricsSession === 'boolean') {
          setMetrics({ perTurn: data.metricsPerTurn, session: data.metricsSession, full: data.metricsMode === 'full' });
        }
      } else if (event.type === 'web-command') {
        applyWebCommand(data, {
          openSession: (id) => openSession(id, id),
          closeSession: () => {
            if (sessionId) {
              closeSession?.(sessionId);
            }
          },
          showTabs: () => setTabsOpen(true),
          setTheme: (theme) => {
            document.documentElement.setAttribute('data-theme', theme);
            localStorage.setItem('yca-theme', theme);
          },
          showSubagents: setSubagentsContent,
        });
      }
    }
  }, [events, openSession, closeSession, sessionId]);
  // per-session todo list collapsed flag
  const [todosCollapsed, setTodosCollapsed] = useState(false);
  // per-session mcp servers section collapsed flag (collapsed by default)
  const [serversCollapsed, setServersCollapsed] = useState(true);
  // Issue #8: when set, the completion dropdown is suppressed for the current keystroke
  // (after accepting an entry or pressing Escape), until typing changes the prompt again.
  const [completionDismissed, setCompletionDismissed] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  // Explicit "Follow" mode (mirrors the `Follow` toggle in the top bar): when ON the view is
  // continuously trailed to the tail as events stream. Defaults to ON per mount. Unlike the old
  // distance heuristic (follow while within 48px of the bottom, which re-armed as live reflow grew
  // the container and made the view wobble up/down when reading near the tail), this is a strict
  // two-state toggle: any genuine user scroll away from the tail disables it for good, and only the
  // toggle button / "jump to latest" re-enables it. Kept as React state so the button and the
  // "↓ latest" affordance re-render when it flips.
  const [follow, setFollow] = useState(true);
  // ref mirror of `follow` so the visibilitychange listener (registered once) always reads the current
  // value without re-registering on every toggle.
  const followRef = useRef(follow);
  followRef.current = follow;
  // Programmatic scrolls (the tail jump on mount, the smooth re-follows during snapshot replay or a
  // live turn) must NEVER disable Follow - only a genuine user scroll-away from the tail may, per the
  // two explicit conditions (intentional toggle, or scrolling up to read). The browser fires the same
  // `scroll` events for a programmatic scrollIntoView as for a real one, so onScroll alone cannot tell
  // them apart: this flag is set true only when a real user scroll INPUT (wheel / touch / scroll-drag
  // pointer / navigation keys) has just fired on the transcript, and reset false by every programmatic
  // scrollToTail. onScroll then disables Follow only when a user-scroll intent is present.
  const userScrollIntentRef = useRef(false);
  // true once this mount has auto-scrolled to a populated tail. The FIRST such jump is done instantly
  // (scrollTop = scrollHeight, no animation) so remounting a long session cannot produce intermediate
  // scroll events; later live follows stay smooth because they are tiny handoffs near the bottom.
  const tailScrolledRef = useRef(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const eventLogRef = useRef<EventLogHandle>(null);
  // throttle/debounce the scroll-to-top auto-load so a single fling doesn't enqueue multiple chunks
  const loadEarlierTimer = useRef(0);
  useEffect(() => {
    return () => window.clearTimeout(loadEarlierTimer.current);
  }, []);
  const completion = useSlashCompletion(prompt, cursor, sessionId);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const completionRef = useRef(completion);
  completionRef.current = completion;
  const { pending, answer, pendingCount } = useInteractiveRequests(sessionId);

  // Auto-focus the prompt when this chat tab opens (create-session, tab switch, deep-link) so the
  // user can type immediately without clicking. ChatPage remounts per session via App's
  // key={activeSession}, so a single ready-gated effect covers every tab-open path. It fires once
  // the snapshot is in (!initializing) because the textarea is readOnly while booting; the
  // initializing flip makes it run exactly once per mount even on a near-instant load.
  useEffect(() => {
    if (!sessionId || initializing) return;
    textareaRef.current?.focus();
  }, [sessionId, initializing]);

  // the /btw modal open flag: it never auto-opens; clicking the btw pill opens it. It stays open
  // across a /btw override (the modal then shows the new question) and auto-closes when the btw is
  // cleared (pill "x" -> session.btwClear -> status.btw becomes null).
  const [btwOpen, setBtwOpen] = useState(false);
  const btw = status.btw;
  useEffect(() => {
    if (!btw) setBtwOpen(false);
  }, [btw]);
  // the StatusBar "x": fully clears the btw on the server (cancels an in-flight answer too), which
  // removes both the pill and the modal until the next /btw.
  const clearBtw = useCallback(async () => {
    if (!sessionId) return;
    await call('session.btwClear', { id: sessionId }).catch(() => {});
  }, [sessionId]);
  // a retryable /btw failure: re-ask the same question by re-dispatching /btw, which resets the
  // single-slot modal to a fresh running state (latest-wins).
  const retryBtw = useCallback(async () => {
    if (!sessionId || !btw?.question) return;
    await call('session.slash', { id: sessionId, text: `/btw ${btw.question}` }).catch(() => {});
  }, [sessionId, btw?.question]);

  // the /help modal: opened when the user dispatches /help, fed by the resulting "transcript" SSE
  // event (which /help renders through, and which the inline block renderer deliberately skips), and
  // dismissed via the Close button, Esc or the backdrop.
  const [helpOpen, setHelpOpen] = useState(false);
  const [helpContent, setHelpContent] = useState<string | null>(null);
  // capture the /help transcript as it streams in and show it in the big scrollable modal
  useEffect(() => {
    if (!helpOpen) return;
    const message = latestTranscriptMessage(events);
    if (message !== null) {
      setHelpContent(message);
    }
  }, [events, helpOpen]);
  useEffect(() => {
    if (helpOpen) return;
    setHelpContent(null);
  }, [helpOpen]);

  // the /executing-tools modal: opened when the user dispatches /executing-tools, fed by the resulting
  // "transcript" SSE event (which only renders through this modal - the inline block renderer skips it),
  // and dismissed via the Close button, Esc or the backdrop.
  const [executingToolsOpen, setExecutingToolsOpen] = useState(false);
  const [executingToolsContent, setExecutingToolsContent] = useState<string | null>(null);
  useEffect(() => {
    if (!executingToolsOpen) return;
    const message = latestTranscriptMessage(events);
    if (message !== null) {
      setExecutingToolsContent(message);
    }
  }, [events, executingToolsOpen]);
  useEffect(() => {
    if (executingToolsOpen) return;
    setExecutingToolsContent(null);
  }, [executingToolsOpen]);

  // Issue #1: ensure the session is served by the backend AND its tab is registered. App normally
  // registers the tab on /session/:id navigation; this is the safety net for any mount that skipped it
  // (e.g. openSession dedup is a no-op when App already added the tab). We only register when the
  // session actually EXISTS: a bogus/typo/deleted id must never pollute the TabBar, so a failure here
  // is intentionally silent (the chat pane stays empty; the user gets no phantom tab).
  useEffect(() => {
    if (!sessionId) return;
    // The health-check fires an async round-trip; drop it once this page unmounts or moves to
    // another session. Without this guard a pending sessions.get could resolve AFTER the user
    // closed the tab, and openSession would silently re-add the just-closed tab (as a raw/session-id
    // tab) and navigate back onto it - making a closed tab "stay open".
    let cancelled = false;
    void call('sessions.get', { id: sessionId })
      .then(() => {
        if (cancelled) return;
        openSession(sessionId, sessionId);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  // sidebar info: project, git branch, todos, mode
  // onModeChange is called from loadSidebar after the authoritative mode is re-read; it is
  // deliberately referenced through a ref (not listed in the useCallback deps) so loadSidebar's
  // identity never changes with App/onModeChange identity. Otherwise a new tabs array in App
  // -> new onModeChange -> new loadSidebar -> the [loadSidebar] effect refires -> infinite
  // refetch/re-render loop ("page keeps refreshing even when nothing happens").
  const modeChangeRef = useRef(onModeChange);
  modeChangeRef.current = onModeChange;
  const loadSidebar = useCallback(() => {
    if (!sessionId) {
      setSidebar({ todos: [], mode: 'plan' });
      return;
    }
    Promise.all([
      call<{ project: string }>('sessions.currentProject', {}).catch(() => ({ project: '' })),
      call<{ branch: string }>('session.gitBranch', { id: sessionId }).catch(() => ({ branch: '' })),
      call<TodoItem[]>('session.todos', { id: sessionId }).catch(() => []),
      call<{ mode: string }>('mode.get', { id: sessionId }).catch(() => ({ mode: 'plan' })),
      call<SidebarMeta>('session.metadata', { id: sessionId }).catch((): SidebarMeta => ({ mode: 'plan' })),
    ]).then(([proj, branch, todos, mode, meta]) => {
      // the backend reports AgentMode enum names as "BUILD"/"PLAN" (uppercase): normalize before
      // comparing, otherwise the badge/sidebar would always render "plan" and /mode would appear dead.
      const isBuild = (mode.mode === 'build' || meta.mode === 'build') ||
        (typeof mode.mode === 'string' && mode.mode.toLowerCase() === 'build') ||
        (typeof meta.mode === 'string' && meta.mode.toLowerCase() === 'build');
      const m: 'plan' | 'build' = isBuild ? 'build' : 'plan';
      // session.metadata reports permissionMode as lowercase already (web API normalizes), but keep
      // the defensive toLowerCase like the mode above so the dropdown value always matches the
      // permission options and the status-indicator comparisons.
      const permissionMode =
        typeof meta.permissionMode === 'string' ? meta.permissionMode.toLowerCase() : meta.permissionMode;
      setSidebar({
        // the session's own working dir (session.metadata.cwd) is authoritative; the process-level
        // sessions.currentProject is only the fallback for sessions that never recorded a project.
        project: meta.cwd || proj.project || undefined,
        gitBranch: branch.branch || meta.gitBranch || undefined,
        todos: todos ?? [],
        mode: m,
        permissionMode,
        sandboxEnabled: meta.sandboxEnabled,
        model: meta.model,
        flavor: meta.flavor,
        provider: meta.provider,
        mcpServers: meta.mcpServers,
        modelCandidates: meta.modelCandidates,
        providerCandidates: meta.providerCandidates,
      });
      if (modeChangeRef.current && sessionId) modeChangeRef.current(sessionId, m);
    });
  }, [sessionId]);

  useEffect(() => {
    loadSidebar();
  }, [loadSidebar]);

  // when a picker/ask modal is answered, the underlying slash command has applied new state
  // (e.g. /model, /provider, /permissions) after the initial loadSidebar in send() already ran -
  // re-sync again so the dropdowns reflect the authoritative result (two-way sync).
  const prevPendingRef = useRef<RequestState | null>(null);
  useEffect(() => {
    if (prevPendingRef.current != null && pending == null) {
      loadSidebar();
    }
    prevPendingRef.current = pending;
  }, [pending, loadSidebar]);

  // Follow mode: only the user controls when it turns off. Every genuine scroll that moves the view
  // up away from the tail disables Follow (no auto-re-arm near the bottom - that is what made the
  // old heuristic wobble); returning to the tail never re-enables it, only the toggle / jump-to-latest
  // re-enables it, so the view stays put wherever the user left it while reading.
  const onScroll = () => {
    // Only a scroll that follows a genuine user scroll input counts as "user scrolled away".
    // The tail jump and any smooth re-follow (snapshot replay, live turn) are programmatic:
    // scrollToTail resets the flag, so their scroll events never disable Follow.
    if (!userScrollIntentRef.current) return;
    const el = scrollRef.current;
    // disable Follow when the user is genuinely not at the tail anymore. `follow` is a state, so
    // React bails on the redundant set (same value) and we do not re-render on every scroll frame.
    if (follow && el) {
      const atTail = el.scrollHeight - el.scrollTop - el.clientHeight < 48;
      if (!atTail) setFollow(false);
    }
    // auto-load earlier history when the user scrolls back to the very top (while more history
    // exists). Debounced so a fast fling doesn't fire one chunk per frame; each invocation grows
    // the rendered window by one LOAD_EARLIER_CHUNK, so repeated rests at the top keep revealing
    // older blocks the same way the manual "↑ Load earlier" button does - Task: scroll history.
    if (el && el.scrollTop <= 4 && eventLogRef.current?.canLoadEarlier()) {
      window.clearTimeout(loadEarlierTimer.current);
      loadEarlierTimer.current = window.setTimeout(() => {
        eventLogRef.current?.loadEarlier();
      }, 250);
    }
  };

  // Programmatic scroll-to-tail. The FIRST time this mount reaches a populated tail the jump is
  // instant (no smooth animation) so the potentially long distance from the top cannot emit the
  // intermediate scroll events that used to disable Follow and strand the view. Later
  // live follows hand off tiny deltas near the bottom, so they keep the smooth animation. Every
  // programmatic jump clears the user-scroll-intent flag (it is NOT a user scrolling away).
  const scrollToTail = (smooth: boolean) => {
    const el = scrollRef.current;
    if (!el) return;
    userScrollIntentRef.current = false;
    tailScrolledRef.current = true;
    if (smooth) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    } else {
      el.scrollTop = el.scrollHeight;
    }
  };

  // Mark the next scroll event(s) as user-driven so onScroll can disable Follow when the user really
  // scrolls away from the tail (wheel, touch, a scrollbar drag press, or a navigation key). Programmatic
  // scrolls (scrollToTail) clear this flag, so they never disable Follow. Keyboard-driven scrolling is
  // wired as a native listener because the scroll container rarely has focus and React's onKeyDown would
  // miss keys pressed while focus is elsewhere; the navigation keys are the only ones that move the view.
  const markUserScroll = () => {
    userScrollIntentRef.current = true;
  };
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '].includes(e.key)) {
        userScrollIntentRef.current = true;
      }
    };
    el.addEventListener('keydown', onKeyDown);
    return () => el.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    if (!follow) return;
    if (events.length === 0) return;
    // Skip while the tab is hidden: the smooth scroll is rAF-suspended in a background tab and would
    // never complete (and, worse, would strand the view mid-transition). The visibilitychange listener
    // below jumps to the tail instantly when the tab returns to the foreground.
    if (document.visibilityState !== 'visible') return;
    // first populated jump on this mount is instant (scrollToTail(false)), so no intermediate
    // smooth-scroll events can leak; later live follows stay smooth (tiny handoffs near the tail).
    // scrollToTail itself records the "already jumped" marker and resets the user-scroll-intent flag.
    scrollToTail(tailScrolledRef.current);
  }, [events.length, follow]);

  // Markdown is debounced and disclosures/fonts can reflow after the SSE render.
  // Observe the transcript's actual size, not just event count, without re-arming Follow.
  useEffect(() => {
    const transcript = scrollRef.current?.firstElementChild;
    if (!transcript) {
      return;
    }
    const observer = new ResizeObserver(() => {
      if (followRef.current && document.visibilityState === 'visible') {
        scrollToTail(false);
      }
    });
    observer.observe(transcript);
    return () => observer.disconnect();
  }, [sessionId]);

  // Follow must be interruption-proof: the rAF-driven smooth scroll is suspended in a background tab,
  // so while hidden the live re-follows never complete and the view is stranded away from the tail.
  // When the tab becomes visible again while Follow is still ON, jump straight to the tail (instant, no
  // rAF) to close the gap. Instant rather than smooth because we may be far from the tail and an
  // animated jump could re-suspend before completing.
  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'visible' && followRef.current) {
        scrollToTail(false);
      }
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, []);

  // re-enable Follow (toggle button or the "jump to latest" affordance) and jump to the tail.
  const jumpToLatest = () => {
    setFollow(true);
    scrollToTail(true);
  };

  useEffect(() => {
    if (!onTitleChange || !sessionId) return;
    // Compaction prepends a digest as a fresh user message wrapped in
    // <compacted-context>/<pruned-context>; treat it as historical state, not the
    // conversation's opening question, so it never leaks into the tab title as
    // "<compacted-context>## ...". Mirror ContextView.java's marker filtering.
    const firstUser = events.find(
      (ev) => ev.type === 'user'
        && typeof ev.message === 'string'
        && ev.message.trim()
        && !/^\s*<(?:compacted|pruned)-context>/.test(ev.message)
    );
    if (!firstUser) return;
    const text = (firstUser.message as string)
      .trim()
      .replace(/\s+/g, ' ')
      .replace(/^<(?:compacted|pruned)-context>\s*.*?##\s*/, '');
    onTitleChange(sessionId, text.length > 60 ? `${text.slice(0, 57)}…` : text);
  }, [events, sessionId, onTitleChange]);

  // Issue #3: slash commands (/model, /mode, /permissions, /provider...) mutate backend
  // state through the prompt line. Refresh the sidebar whenever a turn completes or a
  // prompt is integrated so the right-pane metadata (and todos) reflect the change.
  //
  // This must NOT be gated on the very last event being a boundary (the old tail-only check):
  // when a queued prompt is absorbed, `prompt-integrated` is emitted but the loop keeps streaming
  // status/text-delta/usage frames right after it, so it is almost never the tail - the queue
  // strip then stayed stale (showing a consumed prompt) until turn-done. `latestDrainBoundary`
  // scans backward for the most recent drain-relevant boundary and we dedup by its signature.
  const lastEventRef = useRef<string>('');
  useEffect(() => {
    if (!sessionId) return;
    const sig = latestDrainBoundary(events);
    if (sig == null || lastEventRef.current === sig) return;
    lastEventRef.current = sig;
    loadSidebar();
    refreshQueued(); // the queue drains as prompts are integrated / the turn ends
  }, [events, sessionId, loadSidebar, refreshQueued]);

  // Issue #11: a write_todo mid-turn must refresh the sidebar todo widget immediately, not only at
  // turn-done. The backend broadcasts a `todo-updated` frame (via the TodoUpdatedEvent bridge) each
  // time the list changes; dedup on the delivered todos so only genuine changes trigger a refetch
  // (and a repeated identical write_todo does not spam the RPC endpoint).
  const lastTodoRef = useRef<string>('');
  // collapsed-summary fallback: the human-readable "what changed" line of the most recent todo-updated
  // diff (e.g. "☑ write tests"), used when there is no in-progress/pending "current" item to show.
  // null when no change is recorded yet (first snapshot load).
  const [todoSummary, setTodoSummary] = useState<string | null>(null);
  // previous todo list (the `from` of the last change), to compute the diff client-side since the
  // `todo-updated` SSE frame only carries the new list (`to`), not the prior one.
  const lastTodoItemsRef = useRef<Array<{ content: string; status: string }> | null>(null);
  useEffect(() => {
    if (!sessionId || events.length === 0) return;
    for (let i = events.length - 1; i >= 0; i--) {
      const ev = events[i];
      if (ev.type !== 'todo-updated') continue;
      const todos = (ev.data?.todos as Array<{ content: string; status: string }> | undefined) ?? [];
      const sig = JSON.stringify(todos);
      if (lastTodoRef.current === sig) return;
      lastTodoRef.current = sig;
      setTodoSummary(todoDiffSummary(lastTodoItemsRef.current, todos));
      lastTodoItemsRef.current = todos;
      loadSidebar();
      return;
    }
  }, [events, sessionId, loadSidebar]);

  const resetCursor = (t: string, c: number) => {
    setPrompt(t);
    setCursor(c);
    setCompletionDismissed(false); // typing re-opens the completion dropdown
  };

  const [sendError, setSendError] = useState<string | null>(null);
  const sendingRef = useRef(false);
  const sendText = async (draft: string) => {
    if (!sessionId || initializing || sendingRef.current || !draft.trim()) {
      return;
    }
    sendingRef.current = true;
    setSending(true);
    setSendError(null);
    const text = draft.trim();
    setPrompt('');
    setCursor(0);
    setCompletionDismissed(false);
    try {
      // Only exact known modal commands get special presentation. The server owns routing,
      // including slash-like paths and the correction/cancellation picker.
      if (isHelpCommand(text)) {
        setHelpOpen(true);
        setHelpContent(null);
      }
      if (isExecutingToolsCommand(text)) {
        setExecutingToolsOpen(true);
        setExecutingToolsContent(null);
      }
      const { disposition } = await call<{ disposition: 'accepted' | 'correct' | 'cancel' }>(
        'session.input', { id: sessionId, text });
      if (disposition === 'correct' || disposition === 'cancel') {
        setPrompt(draft);
        setCursor(draft.length);
      } else {
        loadSidebar();
        // An accepted prompt may have been queued behind a running turn.
        refreshQueued();
      }
    } catch (error) {
      setSendError(String(error));
      setPrompt((current) => current || draft);
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
  };
  const send = () => sendText(prompt);

  // Issue #5: interrupt the running turn (the equivalent of the TUI's Esc / pause).
  const cancel = async () => {
    if (!sessionId) return;
    await call('session.cancel', { id: sessionId });
  };

  // Issue #9: explicitly continue a resumed/paused session only when the user asks,
  // instead of auto-running straight into an unresolved approval on tab open.
  const resume = async () => {
    if (!sessionId || initializing) return;
    setSending(true);
    try {
      await call('session.resume', { id: sessionId });
    } finally {
      setSending(false);
    }
  };

  // Issue #9: one click flips the session mode (plan <-> build). Dispatched as the
  // /mode slash command so it runs through the exact same backend path as typing it,
  // then the sidebar/tab are re-synced from the authoritative result.
  const cycleMode = async () => {
    if (!sessionId || initializing) return;
    const next = sidebar.mode === 'build' ? 'plan' : 'build';
    await call('session.slash', { id: sessionId, text: `/mode ${next}` }).catch(() => undefined);
    loadSidebar();
    onModeChange?.(sessionId, next as 'plan' | 'build');
  };

  // Issue #4: change the model via the /model slash command so pickers/validations/state
  // all run like the TUI; the sidebar metadata is then re-synced from the backend.
  const setSessionModel = async (model: string) => {
    if (!sessionId || !model) return;
    await call('session.slash', { id: sessionId, text: `/model ${model}` }).catch(() => undefined);
    loadSidebar();
  };

  const setSessionProvider = async (provider: string) => {
    if (!sessionId || !provider) return;
    await call('session.slash', { id: sessionId, text: `/provider ${provider}` }).catch(() => undefined);
    loadSidebar(); // provider switch resets the model selection + candidates
  };

  const setProject = async (dir: string) => {
    if (!sessionId || !dir.trim()) return;
    await call('session.setProject', { id: sessionId, dir });
    // refresh the sidebar (new working dir + branch), which reflects the rebuilt runtime
    loadSidebar();
  };

  // Issue #6: switch the session's permission level via the /permissions slash command.
  const setSessionPermissions = async (mode: string) => {
    if (!sessionId || !mode) return;
    await call('session.slash', { id: sessionId, text: `/permissions ${mode}` }).catch(() => undefined);
    loadSidebar();
  };


  if (!sessionId) {
    return <div className="p-6 text-sm" style={{ color: 'var(--yca-muted)' }}>Select a session.</div>;
  }

  // Issue #8: the dropdown is suppressed after an accept/Escape until typing resumes. It is also
  // suppressed while the session is still booting (snapshot not delivered) since typing is locked.
  const showCompletion = completion.items.length > 0 && !completionDismissed && !initializing;

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.nativeEvent.isComposing || e.nativeEvent.keyCode === 229) {
      return;
    }
    const items = completionRef.current.items;
    // completion is interactive only while it's actually shown (not dismissed after accept/Escape)
    const show = !completionDismissed && items.length > 0;
    if (show) {
      if (e.key === 'Tab' || e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        return; // TriggerPopover owns keyboard navigation and completion selection.
      }
      if (e.key === 'Enter') {
        const highlighted = e.currentTarget.getAttribute('aria-activedescendant');
        const item = (highlighted ? document.getElementById(highlighted)?.textContent : null) ?? items[0];
        // A no-op completion (the candidate is textually identical to the span it
        // would replace, e.g. a fully typed command like /editplan) must not hijack
        // Enter: there is nothing to complete, so let Enter submit the line instead
        // of swallowing the keystroke to accept the identical text.
        const applied = applySuggestion(prompt, completionRef.current, item);
        if (applied.text !== prompt) {
          return; // TriggerPopover accepts without executing the command.
        }
      }
    }
    if (e.key === 'Escape') {
      e.preventDefault();
        // Issue #8: Escape fully dismisses the completion dropdown until typing resumes.
      setCompletionDismissed(true);
      return;
    }
    if (e.key !== 'Shift' && e.key !== 'Control' && e.key !== 'Alt' && e.key !== 'Meta') {
      // any other keystroke (including typing) re-opens the dropdown if still completing
      setCompletionDismissed(false);
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      void send();
    }
  };

  // sidebar resize handle: pointer (mouse/touch) drag re-computes the width from the horizontal delta.
  // The handle sits on the sidebar's LEFT edge, so dragging left (negative delta) narrows it.
  const onSidebarResizeStart = (e: React.PointerEvent) => {
    if (e.button !== 0 && e.pointerType !== 'touch') return;
    e.preventDefault();
    sidebarDrag.current = { startX: e.clientX, startWidth: sidebarWidth };
    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);
    const onMove = (ev: PointerEvent) => {
      const drag = sidebarDrag.current;
      if (!drag) return;
      const next = drag.startWidth - (ev.clientX - drag.startX);
      const clamped = Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, next));
      setSidebarWidth(clamped);
    };
    const onUp = () => {
      sidebarDrag.current = null;
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      (target as HTMLElement).style.overscrollBehavior = '';
    };
    // capture on the window so the drag stays alive across slightly-faster-than-React pointer bursts
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    (target as HTMLElement).style.overscrollBehavior = 'contain';
  };

  // prompt input resize handle (left edge): same drag pattern as the sidebar handle. A width of 0
  // means "auto/fill"; once dragged it locks to a concrete px width that the textarea adopts.
  const onPromptResizeStart = (e: React.PointerEvent) => {
    if (e.button !== 0 && e.pointerType !== 'touch') return;
    e.preventDefault();
    const startWidth = promptWidth > 0 ? promptWidth : textareaRef.current?.getBoundingClientRect().width ?? PROMPT_MIN_WIDTH;
    promptDrag.current = { startX: e.clientX, startWidth };
    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);
    const onMove = (ev: PointerEvent) => {
      const drag = promptDrag.current;
      if (!drag) return;
      const next = drag.startWidth + (ev.clientX - drag.startX);
      const clamped = Math.max(PROMPT_MIN_WIDTH, Math.min(PROMPT_MAX_WIDTH, next));
      setPromptWidth(clamped);
    };
    const onUp = () => {
      promptDrag.current = null;
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  };

  // prompt input vertical resize handle (top edge): same drag pattern as the horizontal handle. A
  // height of 0 means the natural auto height (2 rows); once dragged it locks to a concrete px height.
  // Dragging up (negative vertical delta) grows the input because the handle sits on the TOP edge.
  const onPromptVResizeStart = (e: React.PointerEvent) => {
    if (e.button !== 0 && e.pointerType !== 'touch') return;
    e.preventDefault();
    const startHeight = promptHeight > 0 ? promptHeight : textareaRef.current?.getBoundingClientRect().height ?? PROMPT_MIN_HEIGHT;
    promptVDrag.current = { startY: e.clientY, startHeight };
    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);
    const onMove = (ev: PointerEvent) => {
      const drag = promptVDrag.current;
      if (!drag) return;
      const next = drag.startHeight + (drag.startY - ev.clientY);
      const clamped = Math.max(PROMPT_MIN_HEIGHT, Math.min(promptMaxHeight(), next));
      setPromptHeight(clamped);
    };
    const onUp = () => {
      promptVDrag.current = null;
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      (target as HTMLElement).style.overscrollBehavior = '';
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    (target as HTMLElement).style.overscrollBehavior = 'contain';
  };

  return (
    <YcaRuntime events={events} busy={busy} initializing={initializing} sending={sending} onNew={sendText} onCancel={cancel}>
    <ThreadPrimitive.Root className="flex h-full flex-col">
      {/* Merged top bar: session info (connected/mode/model), TUI-format metrics and the
          global tool-visibility + collapse-all/open-all controls. */}
      <div className="flex flex-wrap items-center gap-2 border-b px-3 py-1 text-sm" style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-muted)' }}>
        <span
          title={reconnecting ? 'connection lost - reconnecting' : connected ? 'connected' : 'disconnected'}
          style={{ color: connected ? (reconnecting ? 'var(--yca-warning)' : 'var(--yca-success)') : 'var(--yca-error)' }}
        >
          {connected ? (reconnecting ? '⟳ reconnecting' : '● connected') : '○ disconnected'}
        </span>
        <span
          className="cursor-pointer rounded px-1.5 font-bold"
          style={{
            color: sidebar.mode === 'build' ? 'var(--yca-build)' : 'var(--yca-plan)',
            background: 'var(--yca-panel)',
          }}
          title={`Click to switch to ${sidebar.mode === 'build' ? 'plan' : 'build'} mode`}
          onClick={() => cycleMode()}
        >
          {sidebar.mode === 'build' ? '◆ build' : '▪ plan'}
        </span>
        {status.model && <span className="truncate">{status.model}</span>}
        {/* Follow mode: trails the output to the tail when ON. Any scroll up disables it; the
            toggle (and the floating ↓ latest button) re-enables it. */}
        <button
          onClick={() => setFollow((f) => !f)}
          className="rounded px-1.5 hover:bg-white/5"
          style={follow ? { color: 'var(--yca-accent)' } : undefined}
          title={follow ? 'Following output - click to pause (scrolling up also pauses)' : 'Paused - click to follow output to the tail'}
        >
          {follow ? '● Follow' : '○ Follow'}
        </button>
        <VisibilityMenu label="Tools" key={`tools-${sessionId}`} value={toolVisibility} onChange={value => {
          const command = { hidden: 'hide', collapsed: 'collapse', shown: 'show' }[value];
          void call('session.slash', { id: sessionId, text: `/tool-output ${command}` }).catch(() => undefined);
        }} />
        <label className="flex items-center gap-1">Diff
          <select aria-label="Diff mode" value={diffMode} onChange={(event) => {
            void call('session.slash', { id: sessionId, text: `/diff-view ${event.target.value}` }).catch(() => undefined);
          }}
            style={{ background: 'var(--yca-panel)', color: 'var(--yca-text)' }}>
            <option value="auto">Auto</option>
            <option value="side-by-side">Side-by-side</option>
            <option value="simple">Simple</option>
          </select>
        </label>
        <VisibilityMenu label="Thoughts" key={`thoughts-${sessionId}`} value={thoughtVisibility} onChange={value => {
          setThoughtVisibility(value);
          void call('session.slash', { id: sessionId, text: `/thinking ${value === 'hidden' ? 'hide' : 'show'}` }).catch(() => undefined);
        }} />
        <div className="mx-1 h-3 w-px" style={{ background: 'var(--yca-border)' }} />
        <TurnMetrics status={status} settings={metrics} />
        <button
          onClick={() => setSidebarOpen((o) => !o)}
          className="ml-auto rounded px-2 py-0.5 hover:bg-white/5"
          style={{ borderColor: 'var(--yca-border)' }}
        >
          {sidebarOpen ? 'Sidebar ▸' : 'Sidebar ◂'}
        </button>
      </div>
      {/* disconnected banner - obvious feedback when the stream is not healthy */}
      {!connected && (
        <div
          className="flex items-center gap-2 border-b px-3 py-1 text-xs"
          style={{ borderColor: 'var(--yca-error)', background: 'var(--yca-error-soft)', color: 'var(--yca-error)' }}
        >
          <span>⚠ {reconnecting ? 'Connection lost - reconnecting…' : 'Disconnected from the agent server'}</span>
          {!reconnecting && (
            <button onClick={() => window.location.reload()} className="ml-auto underline hover:opacity-80">
              Retry
            </button>
          )}
        </div>
      )}
      <div className="flex flex-1 overflow-hidden">
        <ThreadPrimitive.Viewport
          ref={scrollRef}
          autoScroll={false}
          scrollToBottomOnRunStart={false}
          scrollToBottomOnInitialize={false}
          scrollToBottomOnThreadSwitch={false}
          onScroll={onScroll}
          onWheel={markUserScroll}
          onTouchMove={markUserScroll}
          onPointerDown={markUserScroll}
          className="relative flex-1 overflow-y-auto p-4"
        >
          <EventLog
            ref={eventLogRef}
            toolVisibility={toolVisibility}
            diffMode={diffMode}
            thoughtVisibility={thoughtVisibility}
            windowBlocks={windowBlocks}
            hasServerHistory={moreReplayExists}
            onLoadServerEarlier={loadServerEarlier}
            onPauseFollow={() => { followRef.current = false; setFollow(false); }}
          />
          {initializing && (
            // Full-pane, blocking "restoring session" veil while the persisted snapshot is still being
            // fetched/replayed server-side. It paints over the ENTIRE transcript area (a translucent
            // backdrop dims any content beneath, pointer-events blocks interaction) and shows a prominent
            // centered card with an animated spinner - unmissable in the chat zone, not just a status bar
            // dot. It disappears the instant bootstrapped flips (even for an empty session).
            <div
              className="absolute inset-0 z-10 flex flex-col items-center justify-center gap-3"
              style={{ background: 'rgba(0,0,0,0.35)', backdropFilter: 'blur(2px)' }}
            >
              <div
                className="flex flex-col items-center gap-3 rounded-lg border px-8 py-6 shadow-xl"
                style={{
                  borderColor: 'var(--yca-border)',
                  background: 'var(--yca-panel)',
                }}
              >
                <div className="flex items-center gap-3 text-base" style={{ color: 'var(--yca-accent)' }}>
                  <span className="text-2xl" style={{ animation: 'yca-spin 0.8s linear infinite', display: 'inline-block' }}>⟳</span>
                  <span className="font-medium" style={{ color: 'var(--yca-text)' }}>Loading conversation…</span>
                </div>
                <div className="text-xs" style={{ color: 'var(--yca-muted)' }}>
                  restoring session history
                </div>
              </div>
            </div>
          )}
          <div ref={bottomRef} />
          {!follow && events.length > 0 && (
            <button
              onClick={jumpToLatest}
              className="absolute bottom-3 right-4 rounded border px-2 py-1 text-sm shadow-lg"
              style={{ borderColor: 'var(--yca-primary)', color: 'var(--yca-primary)', background: 'var(--yca-panel)' }}
              title="Re-enable Follow and jump to the latest output"
            >
              ↓ latest
            </button>
          )}
        </ThreadPrimitive.Viewport>
        {sidebarOpen && (
          <div className="flex shrink-0" style={{ width: sidebarWidth }}>
            {/* horizontal resize handle on the sidebar's left edge */}
            <div
              onPointerDown={onSidebarResizeStart}
              className="shrink-0 cursor-ew-resize select-none touch-none"
              style={{ width: 5, marginRight: -1, background: 'transparent' }}
              title="Drag to resize sidebar"
            />
            <div
              className="w-full overflow-y-auto border-l p-3 text-sm"
              style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel)' }}
            >
              <Sidebar
                sidebar={sidebar}
                status={status}
                sessionId={sessionId}
                todosCollapsed={todosCollapsed}
                todoSummary={todoSummary}
                onToggleTodos={() => setTodosCollapsed((c) => !c)}
                serversCollapsed={serversCollapsed}
                onToggleServers={() => setServersCollapsed((c) => !c)}
                onSetProject={setProject}
                onSetModel={setSessionModel}
                onSetProvider={setSessionProvider}
                onSetPermissions={setSessionPermissions}
              />
            </div>
          </div>
        )}
      </div>
      {/* bottom bar kept for live agent status + feedback (thinking/calling/working spinners) */}
      <StatusBar status={status} busy={busy} initializing={initializing} btw={btw} onOpenBtw={() => setBtwOpen(true)} onClearBtw={clearBtw} />
      <SessionRequests request={pending} onAnswer={answer} pendingCount={pendingCount} />
      {btwOpen && <BtwModal btw={btw} onClose={() => setBtwOpen(false)} onRetry={retryBtw} />}
      {helpOpen && <HelpModal content={helpContent} onClose={() => setHelpOpen(false)} />}
      {subagentsContent !== null && (
        <HelpModal title="Subagents" content={subagentsContent} onClose={() => setSubagentsContent(null)}>
          <form className="flex gap-2 p-3" onSubmit={(event) => {
            event.preventDefault();
            void call('session.slash', { id: sessionId, text: `/subagents ${subagentReference}` })
              .catch((error) => setSubagentsContent(`Unable to load subagent: ${String(error)}`));
          }}>
            <input aria-label="Subagent id or name" placeholder="Subagent id or name (blank lists all)"
              className="min-w-0 flex-1 rounded border p-2" style={{ background: 'var(--yca-panel)', color: 'var(--yca-text)' }}
              value={subagentReference} onChange={(event) => setSubagentReference(event.target.value)} />
            <button type="submit" className="rounded border px-3">View / refresh</button>
          </form>
        </HelpModal>
      )}
      {tabsOpen && (
        <HelpModal title="Session tabs" content="Select an open session or close a tab." onClose={() => setTabsOpen(false)}>
          <div className="overflow-y-auto p-3">
            {tabs.map((tab) => <div key={tab.id} className="flex gap-3 p-2">
              <button className="flex-1 text-left" onClick={() => { setTabsOpen(false); openSession(tab.id, tab.title); }}>{tab.title}</button>
              <button onClick={() => closeSession?.(tab.id)}>Close tab</button>
            </div>)}
          </div>
        </HelpModal>
      )}
      {executingToolsOpen && (
        <ExecutingToolsModal content={executingToolsContent} onClose={() => setExecutingToolsOpen(false)} />
      )}
      <ComposerPrimitive.TriggerPopoverRoot>
      <ComposerPrimitive.Root onSubmit={(event) => { event.preventDefault(); void send(); }} className="border-t p-3" style={{ borderColor: 'var(--yca-border)' }}>
        <ComposerDraft text={prompt} />
        {sendError && <div role="alert" className="mb-2 text-sm" style={{ color: 'var(--yca-error)' }}>{sendError}</div>}
        <div className="relative">
          <SlashPopover text={prompt} cursor={cursor} suggestion={completion} visible={showCompletion}
            inputRef={textareaRef} onAccept={(text, position) => {
              resetCursor(text, position);
              setCompletionDismissed(true);
            }} />
          {queuedCount > 0 && (
            <div
              className="mb-1 space-y-1 rounded border px-2 py-1 text-xs"
              style={{ borderColor: 'var(--yca-warning)', color: 'var(--yca-warning)', background: 'var(--yca-warning-soft)' }}
            >
              <div className="flex items-center gap-1">
                <span>⏱</span>
                <span className="truncate">
                  {queuedCount} queued prompt{queuedCount === 1 ? '' : 's'} - will be picked up at the next checkpoint
                </span>
              </div>
              {/* queue manager: each pending prompt is listed (oldest first) and can be removed with
                  confirmation before the running turn picks it up. */}
              {queuedPrompts.length > 0 && (
                <ul className="space-y-0.5">
                  {queuedPrompts.map((p, i) => (
                    <li key={`${i}-${p}`} className="flex items-start gap-1">
                      <span className="min-w-0 flex-1 truncate" title={p}>
                        {i + 1}. {p}
                      </span>
                      <button
                        type="button"
                        onClick={() => removeQueued(i)}
                        className="shrink-0 rounded px-1 hover:bg-white/10"
                        title="Remove this queued prompt"
                      >
                        ✕
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
          <div className="flex gap-2 items-start">
            <div className="flex min-w-0 flex-1 flex-col" style={promptWidth > 0 ? { width: promptWidth, maxWidth: 'calc(100% - 6rem)', flex: '0 0 auto' } : undefined}>
              {/* vertical resize handle on the prompt input's top edge: a full-width strip above the
                  textarea. Dragging up grows the input; 0 height (never dragged) keeps the natural
                  auto height of rows={2}. */}
              <div
                onPointerDown={onPromptVResizeStart}
                className="shrink-0 cursor-ns-resize select-none touch-none"
                style={{ height: 5, marginBottom: -1, background: 'transparent' }}
                title="Drag to resize the prompt input"
              />
              <div className="flex min-w-0 flex-1 items-stretch">
                {/* horizontal resize handle on the prompt input's left edge */}
                <div
                  onPointerDown={onPromptResizeStart}
                  className="shrink-0 cursor-ew-resize select-none touch-none"
                  style={{ width: 5, marginRight: -1, background: 'transparent' }}
                  title="Drag to resize the prompt input"
                />
                <ComposerPrimitive.Input
                  submitMode="none"
                  cancelOnEscape={false}
                  unstable_focusOnRunStart={false}
                  unstable_focusOnScrollToBottom={false}
                  ref={textareaRef}
                  value={prompt}
                  onChange={(e) => resetCursor(e.target.value, e.target.selectionStart ?? e.target.value.length)}
                  onSelect={(e) => setCursor(e.currentTarget.selectionStart ?? e.currentTarget.value.length)}
                  onKeyDown={onKeyDown}
                  rows={promptHeight > 0 ? undefined : 2}
                  readOnly={initializing}
                  placeholder={initializing ? 'Loading conversation…' : 'Ask the agent… (try /help, /model, /tools…) '}
                  className="min-w-0 flex-1 resize-none rounded border px-3 py-2 text-[15px] outline-none disabled:cursor-wait"
                  style={{
                    borderColor: 'var(--yca-border)',
                    background: 'var(--yca-panel)',
                    ...(promptHeight > 0 ? { height: promptHeight, overflowY: 'auto' } : {}),
                  }}
                />
              </div>
            </div>
            <div className="flex flex-col gap-2">
              <button
                type="button"
                onClick={send}
                // Send stays enabled while a turn runs so an additional prompt is queued (never a
                // concurrent turn); it is disabled while the session boots (snapshot not delivered)
                // and while the in-flight RPC is pending or the prompt is empty.
                disabled={sending || initializing || !prompt.trim()}
                className="rounded border px-4 py-1 text-sm disabled:opacity-40"
                style={{ borderColor: 'var(--yca-primary)', color: 'var(--yca-primary)' }}
              >
                Send
              </button>
              {busy ? (
                // Issue #5: a persistent interrupt button for the whole running turn (driven by the
                // SSE busy flag, not the transient send RPC), matching the TUI's Esc / pause.
                <button type="button" onClick={cancel} className="rounded border px-4 py-1 text-sm" style={{ borderColor: 'var(--yca-accent)', color: 'var(--yca-accent)' }}>
                  ■ Stop
                </button>
              ) : (
                // Issue #9: explicitly continue a resumed/paused session instead of auto-running
                // into an unresolved approval the moment the tab opens.
                events.length > 0 && (
                  <button type="button" onClick={resume} disabled={sending} className="rounded border px-4 py-1 text-sm disabled:opacity-40" style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-text)' }}>
                    ⟳ Resume
                  </button>
                )
              )}
            </div>
          </div>
        </div>
      </ComposerPrimitive.Root>
      </ComposerPrimitive.TriggerPopoverRoot>
    </ThreadPrimitive.Root>
    </YcaRuntime>
  );
}

/** Humanized duration like the TUI's DurationFormat.humanize (seconds/minutes). */
function humanize(ms: number | undefined): string {
  if (ms == null || !Number.isFinite(ms)) return '';
  if (ms < 1000) return `${ms}ms`;
  const sec = ms / 1000;
  if (sec < 60) return `${sec.toFixed(1)}s`;
  const min = Math.floor(sec / 60);
  const rem = Math.round(sec % 60);
  return rem === 0 ? `${min}m` : `${min}m${rem}s`;
}

/**
 * Format a token count like the TUI (TuiCommand.formatTokens): plain integer below 1k, "N.Nk"
 * below 1M, "N.NM" above — with 0 decimals once the value crosses 9.9 (e.g. 12k, 4.8M, 26M).
 * Mirrors the TUI exactly so the web and terminal agree on the same numbers.
 */
function formatTokens(n: number | undefined): string {
  if (n == null || !Number.isFinite(n)) return '';
  if (n < 1000) return String(n);
  if (n < 1_000_000) {
    const k = n / 1000;
    return `${k > 9 ? Math.round(k) : k.toFixed(1)}k`;
  }
  const m = n / 1_000_000;
  return `${m > 9 ? Math.round(m) : m.toFixed(1)}M`;
}

/**
 * Current "out" token count to display: the authoritative per-turn value once turn-metrics records
 * it, otherwise the per-turn live provider count (usage-updated, ticks up during the turn), and
 * only as a last resort the chars≈tokens proxy of the streamed text. This keeps the counter moving
 * in real time instead of staying frozen until the request completes.
 */
function currentOutTokens(status: SessionStatus): number {
  if (status.outputTokens != null) return status.outputTokens;
  if (status.liveOutTokens != null) return status.liveOutTokens;
  return Math.round((status.liveOutChars ?? 0) / 4);
}

/** Current "reasoning" token count to display: per-turn turn-metrics value, else the live per-turn count. */
function currentReasoningTokens(status: SessionStatus): number {
  if (status.reasoningTokens != null) return status.reasoningTokens;
  return status.liveReasoningTokens ?? 0;
}

/** TUI-format per-turn metrics (compact): N LLM (think) · tok/s · M tools (sum/wall) · duration. */
export function TurnMetrics({ status, settings = { perTurn: true, session: true, full: false } }: {
  status: SessionStatus;
  settings?: { perTurn: boolean; session: boolean; full: boolean };
}) {
  const inputTokens = status.contextInputTokens ?? status.promptTokens;
  const parts: string[] = [];
  if (settings.perTurn) {
    if (status.turnLlmCalls != null && status.turnLlmCalls > 0) {
      const think = status.turnThinkingMs != null ? ` (${humanize(status.turnThinkingMs)})` : '';
      const tok = status.turnThroughputTokPerSec != null && status.turnThroughputTokPerSec > 0
        ? ` · request ${status.turnOutputEstimated ? '~' : ''}${status.turnThroughputTokPerSec.toFixed(1)} tok/s`
        : '';
      const partial = status.turnReportedRequests != null && status.turnCompletedRequests != null
        && status.turnReportedRequests < status.turnCompletedRequests;
      const server = status.turnServerThroughputTokPerSec != null && status.turnServerThroughputTokPerSec > 0
        ? ` · server ${status.turnServerThroughputTokPerSec.toFixed(1)} tok/s${partial ? ' (partial)' : ''}`
        : '';
      const tools = status.turnToolsRun != null ? `${status.turnToolsRun} tools` : '';
      const toolDur = status.turnToolSumMs != null || status.turnToolWallMs != null
        ? ` (${humanize(status.turnToolSumMs)}/${humanize(status.turnToolWallMs)})`
        : '';
      const dur = status.turnDurationMs != null ? ` · ${humanize(status.turnDurationMs)}` : '';
      parts.push(`${status.turnLlmCalls} LLM${think}${tok}${server} · ${tools}${toolDur}${dur}`);
    }
    if (
      inputTokens != null ||
      status.completionTokens != null ||
      status.outputTokens != null ||
      status.reasoningTokens != null ||
      status.liveOutTokens != null ||
      status.liveReasoningTokens != null ||
      status.liveOutChars != null
    ) {
      // Prefer exact per-turn tokens, then live provider usage, then the streamed-character estimate.
      const out = currentOutTokens(status);
      const reas = currentReasoningTokens(status);
      const outTxt = reas > 0 ? `${formatTokens(out)} out + ${formatTokens(reas)} reasoning` : `${formatTokens(out)} out`;
      let inTxt = inputTokens != null ? `in ${formatTokens(inputTokens)}` : '';
      if (status.cacheReadTokens != null && status.cacheReadTokens > 0 && inputTokens != null && inputTokens > 0) {
        inTxt += ` (cached ${formatTokens(status.cacheReadTokens)} (${Math.round((status.cacheReadTokens * 100) / inputTokens)}%))`;
      }
      parts.push(`${inTxt && inTxt + ' · '}${outTxt}`.trim());
    }
    if (status.contextPercent != null) {
      parts.push(`ctx ${Math.min(100, Math.round(status.contextPercent))}%`);
    }
    if (settings.full && status.turnLlmCalls != null) {
      parts.push(`thinking ${humanize(status.turnThinkingMs)}; tools sum ${humanize(status.turnToolSumMs)}, wall ${humanize(status.turnToolWallMs)}`);
    }
  }
  if (settings.session && status.sessionLlmCalls != null && status.sessionLlmCalls > 0) {
    parts.push(`session: ${status.sessionLlmCalls} LLM calls`);
  }
  if (!settings.perTurn && !settings.session) {
    return null;
  }
  return (
    <span className="truncate">
      {parts.length === 0 ? (
        <span className="opacity-50">idle</span>
      ) : (
        parts.map((p, i) => (
          <span key={i} className={i > 0 ? 'opacity-70' : ''}>
            {i > 0 ? ' · ' : ''}
            {p}
          </span>
        ))
      )}
    </span>
  );
}

/** Bottom bar retained for live agent status (thinking/calling/working spinners) + feedback, and the
 * /btw pill (same line as the status message): clicking the pill opens the /btw modal; the inner "x"
 * clears the btw entirely (server state too) so both the pill and the modal disappear. */
function StatusBar({
  status,
  busy,
  initializing,
  btw,
  onOpenBtw,
  onClearBtw,
}: {
  status: SessionStatus;
  busy: boolean;
  initializing: boolean;
  btw?: SessionStatus['btw'];
  onOpenBtw: () => void;
  onClearBtw: () => void;
}) {
  const notice = status.notice?.message || '';
  const isError = status.notice?.level === 'ERROR' || status.notice?.level === 'WARN';
  // while a turn runs the transient status message is the live label (e.g. "thinking... (5s)",
  // "working"); only fall back to a generic label if the busiest frame carried none.
  const live = busy && notice && notice !== 'ready' ? notice : '';
  // compaction pins its own spinner label: the notice text stays as the message while compacting,
  // and the elapsed counter keeps ticking (the summarizer can run for many seconds).
  const compacting = !!status.compacting;
  // elapsed seconds since the turn went busy: ticks while a turn runs so a stalled backend (no
  // more frames) stays distinguishable from a fast one - the counter keeps climbing regardless.
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    if (!busy) {
      setElapsed(0);
      return;
    }
    const start = Date.now();
    const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, [busy]);
  return (
    <div
      className="flex items-center gap-2 overflow-hidden border-t px-4 py-1 text-sm"
      style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-muted)' }}
    >
      {busy || initializing || compacting ? (
        // animated spinner indistinguishable from a still dot: a CSS rotation on a spinner glyph.
        <span className="shrink-0" style={{ color: 'var(--yca-accent)', animation: 'yca-spin 0.8s linear infinite' }}>
          ⟳
        </span>
      ) : (
        <span className="shrink-0">⏻</span>
      )}
      <span
        className="min-w-0 truncate"
        style={isError ? { color: 'var(--yca-error)' } : (notice && !live && !initializing) || compacting ? { color: 'var(--yca-text)' } : undefined}
      >
        {initializing
          ? 'Loading conversation…'
          : compacting
            ? 'compacting context…'
            : live || notice || (busy ? 'working…' : 'ready')}
        {busy && !initializing && <span className="opacity-70"> · {elapsed}s</span>}
      </span>
      {status.notice?.level && status.notice.level !== 'INFO' && (
        <span
          className="shrink-0 rounded px-1 text-xs"
          style={{ background: isError ? 'var(--yca-error-soft)' : 'var(--yca-warning-soft)', color: isError ? 'var(--yca-error)' : 'var(--yca-warning)' }}
        >
          {status.notice.level}
        </span>
      )}
      {btw && (
        // the /btw pill: click (outside the x) opens the modal; the x clears the btw server-side.
        <span
          className="ml-auto flex shrink-0 items-center gap-1 rounded border px-2 py-0.5 text-xs"
          style={{ borderColor: 'var(--yca-accent)', color: 'var(--yca-accent)', background: 'var(--yca-accent-soft)' }}
          title={btw.running ? `btw: "${btw.question}" - answering…` : `btw: "${btw.question}" - click to view the answer`}
        >
          <button onClick={onOpenBtw} className="flex items-center gap-1.5 outline-none hover:brightness-125">
            {btw.running ? (
              <span style={{ animation: 'yca-spin 0.8s linear infinite', display: 'inline-block' }}>⟳</span>
            ) : (
              <span>✎</span>
            )}
            <span className="max-w-40 truncate">{btw.question || 'btw'}</span>
          </button>
          <button
            onClick={onClearBtw}
            className="ml-1 flex shrink-0 items-center justify-center rounded px-0.5 leading-none hover:bg-white/10"
            title="Clear the btw question (removes pill and modal)"
          >
            ✕
          </button>
        </span>
      )}
    </div>
  );
}

/**
 * Thin context-usage bar (after the Context block): green under most of the window, warming to amber
 * approaching the compaction/prune threshold and red near the limit, mirroring the TUI.
 */
function Sidebar({
  sidebar,
  status,
  sessionId,
  todosCollapsed,
  todoSummary,
  onToggleTodos,
  serversCollapsed,
  onToggleServers,
  onSetProject,
  onSetModel,
  onSetProvider,
  onSetPermissions,
}: {
  sidebar: SidebarInfo;
  status: SessionStatus;
  sessionId: string;
  todosCollapsed: boolean;
  todoSummary: string | null;
  onToggleTodos: () => void;
  serversCollapsed: boolean;
  onToggleServers: () => void;
  onSetProject: (dir: string) => void;
  onSetModel: (model: string) => void;
  onSetProvider: (provider: string) => void;
  onSetPermissions: (mode: string) => void;
}) {
  const [expandedServerId, setExpandedServerId] = useState<string>();
  const inputTokens = status.contextInputTokens ?? status.promptTokens;
  const currentTodo = sidebar.todos.find(todo => todo.status === 'in_progress') ?? sidebar.todos.find(todo => todo.status === 'pending');
  const doneTodos = sidebar.todos.filter(todo => todo.status === 'completed').length;
  const collapsedTodoText = currentTodo ? currentTodo.content : todoSummary ?? (doneTodos > 0 ? 'All done' : 'No active tasks');
  return (
    <div className="space-y-3">
      <SessionMetadata
        sessionId={sessionId}
        project={sidebar.project}
        gitBranch={sidebar.gitBranch}
        model={sidebar.model}
        flavor={sidebar.flavor}
        provider={sidebar.provider}
        permissionMode={sidebar.permissionMode}
        sandboxEnabled={sidebar.sandboxEnabled}
        modelCandidates={sidebar.modelCandidates}
        providerCandidates={sidebar.providerCandidates}
        onSetProject={onSetProject}
        onSetModel={onSetModel}
        onSetProvider={onSetProvider}
        onSetPermissions={onSetPermissions}
      />
      {/* tokens in/out + context size/% (previously only on the status bar) */}
      <div>
        <div className="mb-1 text-xs font-bold uppercase" style={{ color: 'var(--yca-muted)' }}>Context</div>
        <div className="space-y-0.5 text-sm" style={{ color: 'var(--yca-text)' }}>
          <div className="flex justify-between">
            <span style={{ color: 'var(--yca-muted)' }}>tokens in</span>
            <span className="font-mono">{formatTokens(inputTokens)}</span>
          </div>
          {status.cacheReadTokens != null && status.cacheReadTokens > 0 && inputTokens != null && inputTokens > 0 && (
            <div className="flex justify-between">
              <span style={{ color: 'var(--yca-muted)' }}>cached</span>
              <span className="font-mono">
                {formatTokens(status.cacheReadTokens)} (
                {Math.round((status.cacheReadTokens * 100) / inputTokens)}%)
              </span>
            </div>
          )}
          <div className="flex justify-between">
            <span style={{ color: 'var(--yca-muted)' }}>tokens out</span>
            <span className="font-mono">
              {formatTokens(currentOutTokens(status))}
              {currentReasoningTokens(status) > 0 ? ` + ${formatTokens(currentReasoningTokens(status))} r` : ''}
            </span>
          </div>
          <div className="flex justify-between">
            <span style={{ color: 'var(--yca-muted)' }}>context</span>
            <span className="font-mono">
              {status.estimatedContext != null ? formatTokens(status.estimatedContext) : '—'}
              {status.contextPercent != null ? ` (${Math.round(status.contextPercent)}%)` : ''}
            </span>
          </div>
          {status.contextPercent != null && (
            <ContextBar percent={Math.min(100, Math.max(0, status.contextPercent))} />
          )}
        </div>
      </div>
      {/* account usage / quota (only when the current provider exposes usage; hidden otherwise) */}
      {status.quota?.supported && (
        <div>
          <div className="mb-1 text-xs font-bold uppercase" style={{ color: 'var(--yca-muted)' }}>Usage</div>
          <div className="space-y-0.5 text-sm" style={{ color: 'var(--yca-text)' }}>
          {status.quota.windows.slice(0, 2).map((window) => {
            const label = quotaWindowLabel(window.windowSeconds, window.label);
            const resetLabel = window.resetAtEpochSeconds != null && window.resetAtEpochSeconds > 0
              ? `Resets ${formatQuotaReset(window.resetAtEpochSeconds)}` : '';
            if (window.usedPercent == null) {
              return (
                <div key={`${window.kind}-${window.windowSeconds}-${window.resetAtEpochSeconds}`} title={resetLabel || undefined}>
                  <div className="flex justify-between gap-2">
                    <span style={{ color: 'var(--yca-muted)' }}>{label}</span>
                    <span className="font-mono">unknown</span>
                  </div>
                  <div className="mt-1 h-1 w-full rounded-full" style={{ background: 'var(--yca-panel-2)' }} />
                </div>
              );
            }
            const usedPercent = Math.round(Math.min(100, Math.max(0, window.usedPercent)));
            return (
              <div key={`${window.kind}-${window.windowSeconds}-${window.resetAtEpochSeconds}`} title={resetLabel || undefined}>
                <div className="flex justify-between gap-2">
                  <span style={{ color: 'var(--yca-muted)' }}>{label}</span>
                  <span className="font-mono">{usedPercent}%</span>
                </div>
                <ContextBar percent={usedPercent} />
              </div>
            );
          })}
          </div>
        </div>
      )}
      {sidebar.mcpServers && sidebar.mcpServers.length > 0 && (
        <div>
          <button
            onClick={onToggleServers}
            className="mb-1 flex w-full items-center gap-1 text-xs font-bold uppercase"
            style={{ color: 'var(--yca-muted)' }}
            title={serversCollapsed ? 'Expand servers' : 'Collapse servers'}
          >
            <span>{serversCollapsed ? '▸' : '▾'}</span>
            <span>Servers</span>
            <span className="ml-auto text-xs font-normal normal-case" style={{ color: 'var(--yca-muted)' }}>
              {sidebar.mcpServers.length}
            </span>
          </button>
          {!serversCollapsed && (
            <McpServerPanel className="max-w-none" expandedId={expandedServerId} onToggle={id => setExpandedServerId(current => current === id ? undefined : id)} servers={sidebar.mcpServers.map((server) => ({
              id: server.name, name: server.name, stateLabel: server.state.toLowerCase(),
              transport: server.transport, url: server.url, status: mcpServerStatus(server.state),
            }))} />
          )}
        </div>
      )}
      <div>
        <button
          type="button"
          onClick={onToggleTodos}
          aria-expanded={!todosCollapsed}
          className="mb-1 flex w-full min-w-0 items-center gap-2 text-left text-xs font-bold uppercase"
          style={{ color: 'var(--yca-muted)' }}
          title={todosCollapsed ? 'Expand todos' : 'Collapse todos'}
        >
          <span aria-hidden="true" className="shrink-0">{todosCollapsed ? '▸' : '▾'}</span>
          <span className="shrink-0">Todos</span>
          {todosCollapsed && sidebar.todos.length > 0 && (
            <span data-slot="todo-collapsed-summary" className="min-w-0 flex-1 truncate text-xs font-normal normal-case" title={collapsedTodoText}>
              {currentTodo?.status === 'in_progress' && <span aria-hidden="true" className="mr-1 inline-block size-1.5 rounded-full bg-[var(--yca-accent)] align-middle" />}
              {collapsedTodoText}
            </span>
          )}
          {sidebar.todos.length > 0 && (
            <span className="ml-auto shrink-0 rounded px-1 text-xs tabular-nums" style={{ background: 'var(--yca-panel-2)' }}>
              {doneTodos}/{sidebar.todos.length}
            </span>
          )}
        </button>
        {!todosCollapsed &&
          (sidebar.todos.length === 0 ? (
            <div className="text-sm" style={{ color: 'var(--yca-muted)' }}>No todos.</div>
          ) : (
            <TodoList items={sidebar.todos.map((todo, index) => ({
              id: `${index}:${todo.content}`, text: todo.content,
              status: todo.status === 'in_progress' ? 'active' : todo.status === 'completed' ? 'done' : todo.status === 'cancelled' ? 'cancelled' : 'pending',
            }))} />
          ))}
      </div>
    </div>
  );
}

/** TUI tristate checkbox glyph: [ ] pending / [>] in_progress / [x] completed / [-] cancelled. */
export function todoGlyph(status: string): string {
  return status === 'in_progress' ? '◐' : status === 'completed' ? '☑' : status === 'cancelled' ? '⊘' : '☐';
}

/**
 * Human-readable "what changed" line between two todo snapshots, used as the collapsed-summary fallback
 * when there is no in-progress/pending "current" item to show (e.g. everything is done). Returns null
 * when no delta is derivable (first snapshot, or an identical/empty change).
 */
export function todoDiffSummary(
  prev: Array<{ content: string; status: string }> | null,
  next: Array<{ content: string; status: string }>,
): string | null {
  if (prev == null) return null; // first snapshot -> no prior state to diff against
  const nextHas = (content: string) => next.some((n) => n.content === content);
  for (const n of next) {
    const p = prev.find((x) => x.content === n.content);
    if (!p) return `${todoGlyph(n.status)} ${n.content}`; // newly added
    if (p.status !== n.status) return `${todoGlyph(n.status)} ${n.content}`; // status changed
  }
  for (const p of prev) {
    if (p.status !== 'cancelled' && !nextHas(p.content)) return `☐ ${p.content}`; // dropped
  }
  return null;
}

/** Whether a submitted prompt is the /help slash command (leading slash help, even with args). */
export function isHelpCommand(text: string): boolean {
  return /^\/help(?:\s|$)/i.test(text?.trim() ?? '');
}

/** Whether a submitted prompt is the /executing-tools slash command (leading slash, even with args). */
export function isExecutingToolsCommand(text: string): boolean {
  return /^\/executing-tools(?:\s|$)/i.test(text?.trim() ?? '');
}

/**
 * The newest "transcript" event's message, or null when none is present. /help renders the command
 * catalog through a "transcript" event (which the inline block renderer deliberately skips); the big
 * modal reads it from the raw SSE event log through this helper.
 */
export function latestTranscriptMessage(events: ReadonlyArray<{ type: string; message?: string | null }>): string | null {
  for (let i = events.length - 1; i >= 0; i--) {
    if (events[i].type === 'transcript') {
      return events[i].message ?? '';
    }
  }
  return null;
}

export type EventLogHandle = { loadEarlier: () => void; canLoadEarlier: () => boolean };
const GlobalToolCollapse = createContext(true);
const GlobalDiffMode = createContext<DiffMode>('auto');
const GlobalThoughtCollapse = createContext(true);
const DisclosureOverrides = createContext<Map<string, unknown> | null>(null);
const MESSAGE_COMPONENTS = { Message: RuntimeMessage };
const PART_COMPONENTS = { Text: RuntimeBlockPart, Reasoning: RuntimeBlockPart, tools: { Fallback: RuntimeBlockPart } };

export const EventLog = memo(
  forwardRef<
    EventLogHandle,
    {
      toolVisibility: 'shown' | 'collapsed' | 'hidden';
      diffMode?: DiffMode;
      thoughtVisibility: Visibility;
      windowBlocks: number;
      hasServerHistory: boolean;
      onLoadServerEarlier: () => void | Promise<void>;
      onPauseFollow: () => void;
    }
  >(
    function EventLog(
      { toolVisibility, diffMode = 'auto', thoughtVisibility, windowBlocks, hasServerHistory, onLoadServerEarlier, onPauseFollow },
      ref,
    ) {
      // The runtime can add a synthetic empty assistant placeholder while running. YCA's
      // status bar owns that state; it is not a persisted event and must not enter pagination.
      const allMessages = useAuiState((state) => state.thread.messages);
      const messages = useMemo(() => allMessages.filter((message) => message.metadata.custom.ycaBlock), [allMessages]);
      const blocks = useMemo(() => messages.map((message) => message.metadata.custom.ycaBlock as Block), [messages]);
      const rootRef = useRef<HTMLDivElement>(null);
      const disclosureOverrides = useRef(new Map<string, unknown>());
      useEffect(() => {
        const present = new Set(messages.map((message) => message.id));
        for (const key of disclosureOverrides.current.keys()) {
          if (!present.has(key)) {
            disclosureOverrides.current.delete(key);
          }
        }
      }, [messages]);
      const anchorRef = useRef<{ viewport: HTMLElement; node: HTMLElement | null; offset: number; height: number; top: number; pending: boolean } | null>(null);
      const anchorTimer = useRef(0);
      const restoreAnchor = useCallback(() => {
        const anchor = anchorRef.current;
        if (!anchor) {
          return;
        }
        const delta = anchor.node?.isConnected
          ? anchor.node.getBoundingClientRect().top - anchor.offset
          : anchor.top + anchor.viewport.scrollHeight - anchor.height - anchor.viewport.scrollTop;
        if (delta !== 0) {
          anchor.viewport.scrollTop += delta;
        }
        if (!anchor.pending) {
          window.clearTimeout(anchorTimer.current);
          anchorTimer.current = window.setTimeout(() => { anchorRef.current = null; }, 250);
        }
      }, []);
      useEffect(() => {
        const node = rootRef.current;
        const viewport = node?.parentElement;
        if (!node || !viewport) {
          return;
        }
        const cancelAnchor = () => { anchorRef.current = null; };
        const observer = new ResizeObserver(restoreAnchor);
        observer.observe(node);
        viewport.addEventListener('wheel', cancelAnchor);
        viewport.addEventListener('touchmove', cancelAnchor);
        viewport.addEventListener('keydown', cancelAnchor);
        return () => {
          observer.disconnect();
          window.clearTimeout(anchorTimer.current);
          viewport.removeEventListener('wheel', cancelAnchor);
          viewport.removeEventListener('touchmove', cancelAnchor);
          viewport.removeEventListener('keydown', cancelAnchor);
        };
      }, [restoreAnchor]);
      // pagination: the tail (windowBlocks) is always rendered; "load earlier" grows the
      // window backward in LOAD_EARLIER_CHUNK steps (block boundaries, so tool cards stay atomic).
      const [loadedBlocks, setLoadedBlocks] = useState(windowBlocks);
      useEffect(() => {
        setLoadedBlocks((n) => Math.max(n, windowBlocks));
      }, [messages.length, windowBlocks]);
      const requested = Math.max(loadedBlocks, windowBlocks);
      const { start, extended } = renderWindow(blocks, requested);
      // "load earlier" first pulls the next slice from the server (tail-first snapshot pagination) when
      // more persisted history exists, falling back to growing the local render window over what is already
      // loaded once the server has nothing left to give.
      const loadEarlier = useCallback(async () => {
        if (anchorRef.current?.pending) {
          return;
        }
        onPauseFollow();
        const viewport = rootRef.current?.parentElement;
        window.clearTimeout(anchorTimer.current);
        if (viewport) {
          const node = [...rootRef.current!.querySelectorAll<HTMLElement>('[data-yca-message-id]')]
            .find((element) => element.getBoundingClientRect().bottom >= viewport.getBoundingClientRect().top) ?? null;
          anchorRef.current = { viewport, node, offset: node?.getBoundingClientRect().top ?? 0,
            height: viewport.scrollHeight, top: viewport.scrollTop, pending: true };
        }
        // Reveal locally buffered blocks before fetching another server page.
        setLoadedBlocks((n) => n + LOAD_EARLIER_CHUNK);
        try {
          if (!extended && hasServerHistory) {
            await onLoadServerEarlier();
          }
        } finally {
          if (anchorRef.current) {
            anchorRef.current.pending = false;
          }
          restoreAnchor();
        }
      }, [hasServerHistory, extended, messages, onLoadServerEarlier, onPauseFollow, restoreAnchor]);
      useLayoutEffect(() => {
        restoreAnchor();
      }, [messages, loadedBlocks, restoreAnchor]);
      // expose the pagination controls so the scroll container can auto-load-early when the user
      // scrolls back to the top (else a long conversation stops at a hard wall mid-history).
      useImperativeHandle(
        ref,
        () => ({
          loadEarlier,
          canLoadEarlier: () => extended || hasServerHistory,
        }),
        [loadEarlier, extended, hasServerHistory],
      );
      // thinking blocks are filtered at render time (never dropped from the log) so toggling back
      // restores them instantly - mirrors the TUI /thinking toggle. The log-driven pagination/keys
      // stay unchanged so tool-card atomicity and the "load earlier" window are untouched.
      const visibleMessages = messages.slice(start).filter((message) => {
        const block = message.metadata.custom.ycaBlock as Block;
        return (thoughtVisibility !== 'hidden' || block.kind !== 'thought') && (toolVisibility !== 'hidden' || block.kind !== 'tool');
      });
      return (
        <DisclosureOverrides.Provider value={disclosureOverrides.current}>
        <GlobalToolCollapse.Provider value={toolVisibility === 'collapsed'}>
        <GlobalDiffMode.Provider value={diffMode}>
        <GlobalThoughtCollapse.Provider value={thoughtVisibility === 'collapsed'}>
        <div ref={rootRef} className="space-y-2">
          {(extended || hasServerHistory) && (
            <button onClick={loadEarlier} className="rounded border px-2 py-1 text-sm hover:bg-white/5" style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-muted)' }}>
              ↑ Load earlier ({start} blocks)
            </button>
          )}
          {visibleMessages.map((message) => (
            <ThreadPrimitive.Unstable_MessageById key={message.id} messageId={message.id}
              components={MESSAGE_COMPONENTS} />
          ))}
      {blocks.length === 0 && (
        <p className="text-sm" style={{ color: 'var(--yca-muted)' }}>No messages yet.</p>
      )}
    </div>
    </GlobalThoughtCollapse.Provider>
    </GlobalDiffMode.Provider>
    </GlobalToolCollapse.Provider>
    </DisclosureOverrides.Provider>
  );
}));

function RuntimeMessage() {
  const block = useAuiState((state) => state.message.metadata.custom.ycaBlock as Block | undefined);
  const id = useAuiState((state) => state.message.id);
  if (!block) {
    return null;
  }
  return <MessagePrimitive.Root data-yca-kind={block.kind} data-yca-message-id={id}><MessagePrimitive.Parts components={PART_COMPONENTS} /></MessagePrimitive.Root>;
}
function RuntimeBlockPart() {
  const block = useAuiState((state) => state.message.metadata.custom.ycaBlock as Block);
  const globalCollapsed = useContext(GlobalToolCollapse);
  return <BlockView block={block} globalCollapsed={globalCollapsed} />;
}

const BlockView = memo(function BlockView({ block, globalCollapsed }: { block: Block; globalCollapsed: boolean }) {
  const diffMode = useContext(GlobalDiffMode);
  const thoughtsCollapsed = useContext(GlobalThoughtCollapse);
  // per-card override on top of the global show/collapse-all header switch (tools are collapsed by
  // default, matching the TUI's default tool-output posture). null = follow the global toggle.
  const overrides = useContext(DisclosureOverrides);
  const [userOverride, setUserOverride] = useState<'expanded' | 'collapsed' | null>(() =>
    (block.id ? overrides?.get(block.id) as 'expanded' | 'collapsed' | undefined : undefined) ?? null);
  const running = block.kind === 'tool' && block.state === 'running';
  // In auto diff mode, a running tool with live output auto-expands regardless of the global collapse posture so the
  // streamed stdout is visible as it arrives (the user can still collapse it via the per-card
  // toggle, which pins an explicit override) - Issue #5.
  const runningAutoExpand =
    diffMode === 'auto' && running && ((block as { content?: string }).content?.length ?? 0) > 0;
  const collapsed = block.kind === 'tool'
    ? userOverride !== null
      ? userOverride === 'collapsed'
      : runningAutoExpand
      ? false
      : globalCollapsed
    : false;
  const changeOverride = (open: boolean) => {
    const value = open ? 'expanded' : 'collapsed';
    if (block.id) {
      overrides?.set(block.id, value);
    }
    setUserOverride(value);
  };
  const toggleCard = () => changeOverride(collapsed);
  if (block.kind === 'subagent') {
    return <div>
      <SubagentActivity agents={[{
        id: block.subagentId, name: block.name, model: block.model,
        state: block.state === 'running' ? 'working' : block.state === 'failed' ? 'failed' : block.state === 'cancelled' ? 'waiting' : 'done',
        label: block.state, description: block.state === 'running' ? block.task : undefined,
      }]} />
      {block.summary && <Markdown content={block.summary} writing={false} />}
    </div>;
  }
  if (block.kind === 'thought') {
    return (
      <div>
        {block.speakerLabel && <div className="mb-1 text-xs opacity-60">{block.speakerLabel}</div>}
        <Reasoning streaming={block.writing === true} manualOpen={userOverride === null ? !thoughtsCollapsed : userOverride === 'expanded'} onManualOpenChange={changeOverride}>
          <Markdown content={block.content} writing={block.writing} variant="thought" />
        </Reasoning>
      </div>
    );
  }
  if (block.kind === 'user') {
    return (
      <div
        className="yca-turn yca-turn-user px-3 py-1.5 text-[15px]"
      >
        <span className="mb-0.5 block text-xs font-bold uppercase tracking-wide" style={{ color: 'var(--yca-primary)' }}>
          {block.speakerLabel ?? 'You'}
        </span>
        <Markdown content={block.content} writing={block.writing} variant="user" />
      </div>
    );
  }
  if (block.kind === 'tool') {
    return <ToolCard block={block} collapsed={collapsed} onToggle={toggleCard} diffMode={diffMode} />;
  }
  if (block.kind === 'error') {
    return <div className="rounded border px-3 py-1 text-[15px]" style={{ borderColor: 'var(--yca-error)', color: 'var(--yca-error)' }}>{block.content}</div>;
  }
  if (block.kind === 'notice') {
    // Server-emitted standalone fragment (e.g. the Codex authorize URL): a distinct callout card so a
    // login prompt stands out from ordinary assistant replies, with its <url> autolink clickable in a
    // new tab via the shared `link` component below.
    return (
      <div
        className="rounded border-l-2 px-3 py-2 text-[15px]"
        style={{ borderColor: 'var(--yca-accent)', background: 'var(--yca-panel)', borderRadius: 'var(--yca-radius)' }}
      >
        <Markdown content={block.content} writing={block.writing} />
      </div>
    );
  }
  // Speaker identity stays on the rail, leaving prose on the neutral transcript surface.
  return (
    <div
      className="yca-turn yca-turn-assistant px-4 py-2.5 text-[15px]"
    >
      {block.speakerLabel && <div className="mb-1 text-xs opacity-60">{block.speakerLabel}</div>}
      <Markdown content={block.content} writing={block.writing} />
    </div>
  );
});

/**
 * How long (ms) a live block may lag the stream while an inline-code span stays open before we
 * force-render the latest fragment anyway. Real inline-code tokens run up to a few dozen chars
 * (`org.apache.hop:hop-transform-drools:2.19.0` is 42) and stream in within a few hundred ms, so
 * the cap is never hit in normal streaming; it only guards the pathological case where the model
 * stalls mid-span (or streams a single enormous code token slowly), so the UI is never frozen
 * behind the stream for more than ~2s.
 */
const INLINE_SPAN_HOLD_CAP_MS = 2000;

/**
 * Debounces the markdown source of a live-streaming block so intermediate fragments with a half-open
 * span (an inline-code backtick whose closer is still streaming) are never parsed into a bogus pairing
 * ("inversed" wording). Scope is the whole text block: a backtick pair spans the fragments arriving
 * within one short burst, so holding the re-parse ~60ms behind the last delta is enough, and the block
 * flushes immediately when {@code writing} flips false (i.e. the block settled - a tool call followed,
 * the turn ended, or the source was replayed with no live stream). Non-writing blocks (user input,
 * replayed history) render immediately.
 *
 * While {@code writing} and the latest fragment has an OPEN inline-code span (its closing backtick
 * has not streamed yet - see {@link hasOpenInlineSpan}), the previous balanced render is kept instead
 * of the fragment: rendering the unbalanced fragment would make CommonMark mis-pair the backticks and
 * swallow the surrounding prose into one oversized {@code <code>}. This is the fix for the iceboxes
 * whose provider streams in tiny fragments that split inside code spans ~40% of the time (freeinference
 * deepseek-v4-flash): the live view now never shows the inverted pairing, it simply lags the stream up
 * to {@link INLINE_SPAN_HOLD_CAP_MS} until the span closes, and the settled content is always rendered.
 */
function useDebouncedMarkdown(content: string, writing: boolean): string {
  const [stable, setStable] = useState(() => content);
  const timer = useRef<number>(0); // normal 60ms debounce after a balanced burst of fragments
  const capTimer = useRef<number>(0); // ~2s fallback: force-render even if an inline span stays open
  // keep the latest incoming content for the immediate flush (setStable below never reads a stale closure)
  const latest = useRef(content);
  latest.current = content;
  const heldSince = useRef<number | null>(null);

  useEffect(() => {
    if (!writing) {
      // settled (or never live): render the exact content now, cancel any pending debounce/cap
      window.clearTimeout(timer.current);
      window.clearTimeout(capTimer.current);
      setStable(content);
      heldSince.current = null;
      return;
    }
    // still streaming: re-arm the debounce and the cap against the newest fragment, cancelling prior ones.
    window.clearTimeout(timer.current);
    window.clearTimeout(capTimer.current);
    const cur = latest.current;
    heldSince.current ??= Date.now();
    const flush = () => {
      setStable(latest.current);
      heldSince.current = null;
    };
    if (!hasOpenInlineSpan(cur)) {
      // backticks balanced (no inline span mid-stream): render once the burst pauses (~60ms), as before.
      timer.current = window.setTimeout(flush, 60);
    } else {
      // an inline-code span is still open (its closer hasn't streamed yet): hold the last balanced
      // render so no inverted/mis-paired span is ever drawn; the cap timer force-renders the latest
      // if the model stalls mid-span, so the UI never lags the stream by more than the cap.
    }
    // A maximum interval, not a resettable debounce: sustained streams must paint too.
    const maxHold = hasOpenInlineSpan(cur) ? INLINE_SPAN_HOLD_CAP_MS : 200;
    capTimer.current = window.setTimeout(flush, Math.max(0, maxHold - (Date.now() - heldSince.current)));
    return () => {
      window.clearTimeout(timer.current);
      window.clearTimeout(capTimer.current);
    };
  }, [content, writing]);

  return stable;
}

/**
 * Renders assistant/user text with markdown (gfm) while keeping code/whitespace readable.
 * Two layers beyond ReactMarkdown alone:
 *  - a normalization pre-pass fixes LLM-emitted markdown that CommonMark otherwise renders literally
 *    (see {@link normalizeMarkdown} from {@code ../lib/markdown}) - Issue #6;
 *  - fenced/inline code blocks get syntax highlighting via Prism - Issue #7.
 */
function Markdown({ content, writing = false, variant = 'assistant' }: {
  content: string;
  writing?: boolean;
  variant?: 'user' | 'assistant' | 'document' | 'thought';
}) {
  // strip echoed compact-seam ref tags ("@N@", "@bN@", "@blocked@") from the ACCUMULATED block
  // content before rendering: a seam split across streamed deltas has already re-formed in the
  // block, so the strip catches it exactly like the core ConversationTranscript.markdown scrub.
  const src = normalizeMarkdown(stripEchoedRefs(useDebouncedMarkdown(content, writing) || ''));
  return <MarkdownContent content={src} variant={variant} />;
}

/**
 * The /btw side-question modal: shows the question and either a "pending" state (spinner while the
 * answerer is running) or the rendered markdown answer / error once done. Closable via the Close
 * button, an Esc keypress, or clicking the backdrop. It only renders the CURRENT btw state (passed
 * by the parent), so a /btw override swaps the content in place and a cleared btw unmounts it.
 */
function BtwModal({
  btw,
  onClose,
  onRetry,
}: {
  btw?: SessionStatus['btw'];
  onClose: () => void;
  onRetry: () => void;
}) {
  useEffect(() => {
    if (!btw) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, btw]);

  if (!btw) return null;
  const running = btw.running !== false;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
    >
      <div
        className="flex max-h-[80vh] w-full max-w-xl flex-col rounded-lg border p-4 shadow-xl"
        style={{ borderColor: 'var(--yca-border)', background: 'var(--yca-panel-2)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-2 flex items-start gap-2">
          <span className="shrink-0 rounded px-1.5 py-0.5 text-xs font-bold uppercase" style={{ background: 'var(--yca-accent-soft)', color: 'var(--yca-accent)' }}>
            btw
          </span>
          <div className="min-w-0 flex-1 truncate text-sm font-semibold" style={{ color: 'var(--yca-text)' }} title={btw.question}>
            {btw.question}
          </div>
          <button
            onClick={onClose}
            className="shrink-0 rounded px-1.5 leading-none hover:bg-white/10"
            style={{ color: 'var(--yca-muted)' }}
            title="Close"
          >
            ✕
          </button>
        </div>
        <div className="min-h-24 flex-1 overflow-y-auto">
          {running ? (
            <div className="flex items-center gap-2 py-6 text-sm" style={{ color: 'var(--yca-muted)' }}>
              <span style={{ color: 'var(--yca-accent)', animation: 'yca-spin 0.8s linear infinite', display: 'inline-block' }}>⟳</span>
              <span>answering…</span>
            </div>
          ) : btw.error ? (
            <div className="whitespace-pre-wrap text-sm" style={{ color: 'var(--yca-error)' }}>
              {btw.error}
            </div>
          ) : btw.retryable ? (
            <div className="whitespace-pre-wrap text-sm" style={{ color: 'var(--yca-muted)' }}>
              {btw.answer ?? '(no answer)'}
              {'\n\n'}This side question could not be answered; you can retry it.
            </div>
          ) : (
            <div className="prose prose-sm max-w-none text-sm leading-relaxed" style={{ color: 'var(--yca-text)' }}>
              <MarkdownContent content={btw.answer ?? '(no answer)'} variant="assistant" />
            </div>
          )}
        </div>
        <div className="mt-3 flex justify-end gap-2">
          {!running && btw.retryable && (
            <button
              onClick={onRetry}
              className="rounded border px-3 py-1 text-xs"
              style={{ borderColor: 'var(--yca-accent)', color: 'var(--yca-accent)' }}
              title="Retry the side question"
            >
              Retry
            </button>
          )}
          <button
            onClick={onClose}
            className="rounded border px-3 py-1 text-xs"
            style={{ borderColor: 'var(--yca-border)', color: 'var(--yca-text)' }}
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * The /help modal: a big, near-full-view scrollable panel with the command catalog rendered from
 * the "transcript" event /help dispatches (which the inline block renderer intentionally skips).
 * Sized with margins instead of a small centered card so the whole command list is readable, with
 * an internal scroll for long content. Closable via the Close button, Esc, or clicking the backdrop.
 */
function HelpModal({ content, onClose, title = 'Slash commands', children }: {
  content: string | null; onClose: () => void; title?: string; children?: React.ReactNode;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 sm:p-8"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="flex h-[88vh] w-[min(960px,94vw)] flex-col overflow-hidden rounded-lg"
        style={{ border: '1px solid var(--yca-border)', background: 'var(--yca-panel-2)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div
          className="flex shrink-0 items-center gap-2 border-b px-5 py-3"
          style={{ borderColor: 'var(--yca-border)' }}
        >
          <span
            className="shrink-0 rounded px-1.5 py-0.5 text-xs font-bold uppercase"
            style={{ background: 'var(--yca-accent-soft)', color: 'var(--yca-accent)' }}
          >
            {title === 'Slash commands' ? 'help' : 'info'}
          </span>
          <div className="min-w-0 flex-1 truncate text-sm font-semibold" style={{ color: 'var(--yca-text)' }}>
            {title}
          </div>
          <button
            onClick={onClose}
            className="shrink-0 rounded px-1.5 leading-none hover:bg-white/10"
            style={{ color: 'var(--yca-muted)' }}
            title="Close"
          >
            ✕
          </button>
        </div>
        {children}
        <div className="flex-1 overflow-y-auto p-5">
          {content == null ? (
            <div className="py-6 text-sm" style={{ color: 'var(--yca-muted)' }}>
              loading…
            </div>
          ) : (
            <div
              className="prose prose-sm max-w-none text-sm leading-relaxed"
              style={{ color: 'var(--yca-text)' }}
            >
              <MarkdownContent content={content} variant="document" />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * The /executing-tools modal: a big scrollable panel with the running-tools table rendered from the
 * "transcript" event /executing-tools dispatches (which the inline block renderer intentionally skips,
 * so this fragment only ever appears here - "only modal"). Closable via the Close button, Esc, or the
 * backdrop.
 */
function ExecutingToolsModal({ content, onClose }: { content: string | null; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 sm:p-8"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Executing tools"
        className="flex h-[88vh] w-[min(960px,94vw)] flex-col overflow-hidden rounded-lg"
        style={{ border: '1px solid var(--yca-border)', background: 'var(--yca-panel-2)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div
          className="flex shrink-0 items-center gap-2 border-b px-5 py-3"
          style={{ borderColor: 'var(--yca-border)' }}
        >
          <span
            className="shrink-0 rounded px-1.5 py-0.5 text-xs font-bold uppercase"
            style={{ background: 'var(--yca-accent-soft)', color: 'var(--yca-accent)' }}
          >
            executing-tools
          </span>
          <div className="min-w-0 flex-1 truncate text-sm font-semibold" style={{ color: 'var(--yca-text)' }}>
            Executing tools
          </div>
          <button
            onClick={onClose}
            className="shrink-0 rounded px-1.5 leading-none hover:bg-white/10"
            style={{ color: 'var(--yca-muted)' }}
            title="Close"
          >
            ✕
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-5">
          {content == null ? (
            <div className="py-6 text-sm" style={{ color: 'var(--yca-muted)' }}>
              loading…
            </div>
          ) : (
            <div
              className="prose prose-sm max-w-none text-sm leading-relaxed"
              style={{ color: 'var(--yca-text)' }}
            >
              <MarkdownContent content={content} variant="document" />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
