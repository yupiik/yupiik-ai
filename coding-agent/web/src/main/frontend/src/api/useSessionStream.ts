import { useEffect, useMemo, useRef, useState } from 'react';
import { call, dispatchServerRequest } from './rpc';

export type WebEvent = {
  type: string;
  sessionId: string;
  subagentId?: string | null;
  subagentName?: string | null;
  message?: string | null;
  data?: Record<string, unknown>;
};

export type SessionStatus = {
  model?: string;
  contextPercent?: number;
  estimatedContext?: number;
  // account quota (codex-usage providers): present only when the current provider exposes usage
  quota?: {
    enabled: boolean;
    supported: boolean;
    planType: string;
    usedPercent: number | null;
    windowSeconds: number | null;
    resetAtEpochSeconds: number | null;
    windowLabel: string;
    creditsBalance: string;
    creditsUnlimited: boolean;
    windows: Array<{ kind: string; label: string; usedPercent: number | null; windowSeconds: number | null; resetAtEpochSeconds: number | null }>;
  };
  promptTokens?: number;
  // Full model input, including cached input; promptTokens keeps the raw reported value.
  contextInputTokens?: number;
  completionTokens?: number;
  // provider-served prompt tokens read from the cache (subset of contextInputTokens when the provider
  // publishes cache metrics; absent for providers that do not).
  cacheReadTokens?: number;
  // turn metrics (last completed turn)
  turnLlmCalls?: number;
  turnThinkingMs?: number;
  turnToolsRun?: number;
  turnToolSumMs?: number;
  turnToolWallMs?: number;
  turnDurationMs?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  // realtime estimate of the current turn's streamed output: accumulated text-delta + thinking-delta
  // character length (a chars≈tokens proxy), reset at each turn boundary and superseded by the exact
  // turn-metrics outputTokens+reasoningTokens once the turn records them.
  liveOutChars?: number;
  // per-turn delivery made of the cumulative provider completion/reasoning tokens since the last turn
  // start (busy:true frame): ticks up live on every usage-updated the server emits mid-turn, and is
  // superseded by the exact turn-metrics outputTokens+reasoningTokens once the turn records them.
  // Preferred over the liveOutChars chars-proxy while the provider reports usage.
  liveOutTokens?: number;
  liveReasoningTokens?: number;
  turnThroughputTokPerSec?: number;
  turnServerThroughputTokPerSec?: number;
  turnCompletedRequests?: number;
  turnReportedRequests?: number;
  turnOutputEstimated?: boolean;
  // session totals
  sessionLlmCalls?: number;
  // latest status/notice line
  notice?: { level?: string; message?: string };
  busy?: boolean;
  // compaction lifecycle: true while a "Compaction in progress..." notice is live (before any
  // "Compacted:"/"Compaction gate:" result). Orthogonal to busy: compaction runs inside the turn,
  // so the spinner stays pinned while busy baselines still reset normally at turn start.
  compacting?: boolean;
  // the current /btw side question (latest-wins single slot): absent/null when no btw is open.
  btw?: { id?: number; running?: boolean; question: string; answer?: string; error?: string; retryable?: boolean };
};

/** Stable identity of an event, used to merge (not replace) live events with the snapshot. */
export function eventKey(ev: WebEvent): string {
  const ord = ev.data?.ordinal;
  const tool = ev.data?.toolCallId;
  // todo-updated frames carry no ordinal/callId and a blank message, so without their payload they
  // would all collapse into a single dedup key: include the delivered todos to keep each distinct
  // list change visible to the sidebar refresh trigger.
  const todos = ev.type === 'todo-updated' ? JSON.stringify(ev.data?.todos ?? null) : '';
  return [
    ev.type,
    ev.sessionId,
    ev.subagentId ?? '',
    ev.message ?? '',
    ord == null ? '' : String(ord),
    tool == null ? '' : String(tool),
    todos,
  ].join('\u0000');
}

/**
 * State-carrying event types whose content fields are constant across occurrences (usage carries the
 * model name, status/notice a transient label), so deduping them by content would collapse distinct
 * "latest wins" updates - the web sidebar usage bar froze on the first frame because of this. Each
 * occurrence must survive the O(1) key dedup: append a monotonic per-connection sequence. Structural
 * events (user, text/tool lifecycle) keep their natural identity and are NOT sequence-scoped.
 */
export const STATE_CARRYING = new Set(['usage-updated', 'turn-metrics', 'status', 'notice', 'btw', 'settings', 'web-command', 'feedback-started']);

/** The dedup key of {@code ev} in a connection where it is the {@code seq}-th state-carrying occurrence. */
export function dedupKey(ev: WebEvent, seq: number): string {
  return STATE_CARRYING.has(ev.type) ? eventKey(ev) + `\u0000${seq}` : eventKey(ev);
}

/**
 * How many live events are kept in the fast rotating tail. Older events rotate into the history
 * prefix (still in the same ordered log, just no longer part of the hot working set) - this is
 * the UI-side analog of the server's connection queue: the last 4096 live events are always
 * rendered without any pagination, older ones are one "Load earlier" click away (or auto-fetched
 * by scrolling to the top). 4096 keeps a long un-reloaded session's live history comfortably
 * browseable without unbounded memory growth in the browser.
 */
export const TAIL_LIMIT = 4096;

/**
 * Liveness watchdog threshold (ms): if the SSE connection stays open but delivers no real frame
 * (agent/rpc/snapshot - the server's raw `: ping` comments are not visible to EventSource listeners)
 * for this long after having delivered content, the stream is re-armed with a fresh connection.
 * The browser only notices a dead-but-open stream on reconnect, so without this a silently stalled
 * backend would leave the pane frozen on "working" until a manual refresh. The threshold is well above
 * the server's 15s keep-alive ping, so a healthy idle session never trips it.
 */
const LIVENESS_STALE_MS = 45_000;

/**
 * Opens the SSE stream for a session and reduces events into an ordered, de-duplicated log.
 *
 * Performance invariants (a busy turn streams hundreds of frames per second - bash fragments,
 * usage telemetry, status labels - and the UI must stay responsive):
 *  - dedup is O(1): a key {@link Set}, never a scan of the log;
 *  - the log is append-only and mutated in place: `history` + `tail` arrays are pushed to, and a
 *    single ordered display view (explicit earlier pages + bounded live window) is handed to React
 *    at most once per animation frame, never once per SSE frame.
 */
export function useSessionStream(sessionId: string | undefined) {
  const [events, setEvents] = useState<WebEvent[]>([]);
  const [connected, setConnected] = useState(false);
  // whether the persisted history (snapshot frame) has been delivered and seeded into the log.
  // Distinct from `connected`: onopen fires as soon as the socket opens, but the snapshot (loaded
  // from disk server-side) may take much longer for a large session. Until bootstrapped flips true
  // the UI has an empty pane and must show a loader + keep chat disabled instead of an active blank
  // page. Stays true across reconnects (a /backoff reconnect re-sends the snapshot and merges it);
  // only reset when the sessionId changes.
  const [bootstrapped, setBootstrapped] = useState(false);
  const sessionRef = useRef(sessionId);
  // a reconnecting flag so the UI can show "reconnecting…" distinctly from "connected".
  const [reconnecting, setReconnecting] = useState(false);
  // realtime streamed-output char counter for the current turn (see SessionStatus.liveOutChars).
  // Kept in a ref, incremented O(1) per SSE frame in append(), so the fold never has to rescan
  // the whole event array to derive it (which would be O(n) per rAF during a long answer).
  const liveOutCharsRef = useRef(0);
  // cumulative count of replayed transcript events the client currently holds (snapshot tail + any
  // session.loadEarlier slices). Serves as the exclusive-from-tail cursor for fetching even earlier
  // history, so a tail-first snapshot never loses access to a long session's older messages.
  const loadedEventsRef = useRef(0);
  // the full replayed event count the server reported in the latest snapshot; more history is available
  // (and the "Load earlier" affordance should show) while loadedEventsRef is below it.
  const totalReplayRef = useRef(0);
  // populated by the connection effect with a callable that fetches the next earlier slice of the
  // persisted history (the effect owns the in-memory event log it mutates). Kept in a ref so the
  // hook can expose a stable wrapper without re-registering on every render.
  const loadEarlierRef = useRef<() => Promise<void>>(async () => {});

  useEffect(() => {
    sessionRef.current = sessionId;
  }, [sessionId]);

  useEffect(() => {
    loadedEventsRef.current = 0;
    totalReplayRef.current = 0;
    liveOutCharsRef.current = 0;
    loadEarlierRef.current = async () => {};
    if (!sessionId) {
      setEvents([]);
      setConnected(false);
      setReconnecting(false);
      setBootstrapped(false);
      return;
    }
    // reset for the new session: a fresh mount must show the loader until THIS session's snapshot
    // arrives - the previous session's bootstrapped state must not leak onto it.
    setBootstrapped(false);
    const history: WebEvent[] = [];
    // Explicitly requested pages are not part of the rotating live display window.
    const earlier: WebEvent[] = [];
    const earlierKeys = new Set<string>();
    const tail: WebEvent[] = [];
    const keys = new Set<string>();
    let publishPending = false;
    // monotonic discriminator for state-carrying events (usage/status/notice) whose content fields are
    // constant across occurrences: each one is a genuine update ("latest wins"), so it must survive the
    // O(1) key dedup instead of collapsing into the first occurrence (which froze the sidebar usage bar).
    let seq = 0;

    const publish = () => {
      if (!publishPending) {
        // one ordered view per animation frame at most; the log itself is mutated in place so a
        // pending batch never blocks the append-only fast path of the SSE listeners
        publishPending = true;
        requestAnimationFrame(() => {
          publishPending = false;
          if (!closed && sessionRef.current === sessionId) {
            const liveWindow = [...history.slice(-TAIL_LIMIT), ...tail];
            setEvents(earlier.length === 0 ? liveWindow : [
              ...earlier, ...liveWindow.filter((ev) => STATE_CARRYING.has(ev.type) || !earlierKeys.has(eventKey(ev))),
            ]);
          }
        });
      }
    };

    const append = (ev: WebEvent) => {
      // state-carrying updates (usage/status/notice) carry no stable identity of their own, so each
      // occurrence gets a monotonic sequence suffix and survives the O(1) dedup - otherwise a later
      // usage frame with the same model would be dropped and the sidebar usage bar would freeze.
      const key = dedupKey(ev, STATE_CARRYING.has(ev.type) ? ++seq : seq);
      if (keys.has(key)) return; // O(1) dedupe
      keys.add(key);
      // keep the live out-char estimate in step with the stream (O(1) per frame): accumulate the
      // streamed visible+reasoning text, reset it at each turn boundary so it represents the current turn.
      liveOutCharsRef.current = foldLiveOutChars(liveOutCharsRef.current, ev);
      tail.push(ev);
      if (tail.length > TAIL_LIMIT) {
        // rotate the oldest live events into the history prefix so the hot tail stays bounded;
        // the ordered view still contains them, just outside the always-rendered window
        history.push(...tail.splice(0, tail.length - TAIL_LIMIT));
      }
      publish();
    };

    const mergeSnapshot = (snap: WebEvent[]) => {
      if (!snap || snap.length === 0) return;
      // snapshot replay is ordered history: append only events not already present
      for (const ev of snap) {
        // respect the same rotation as live events so the history prefix stays bounded:
        // events beyond the tail rotate into history, never dropped from the ordered view
        append(ev);
      }
    };

    // Fetches the next-earlier slice of the persisted history (tail-first snapshot pagination) and
    // PREPENDS it to the ordered log, so a long session stays browseable after the windowed snapshot.
    const fetchEarlier = async () => {
      if (closed || sessionRef.current !== sessionId) {
        return;
      }
      try {
        const before = loadedEventsRef.current;
        if (before <= 0 || before >= totalReplayRef.current) {
          return;
        }
        const r = await call<{ events: WebEvent[]; loaded: number }>('session.loadEarlier', {
          sessionId,
          excludeFromTail: before,
        });
        if (closed || sessionRef.current !== sessionId || !Array.isArray(r?.events) || r.events.length === 0) {
          return;
        }
        // older events go at the FRONT of the log, preserving conversation order; new keys are
        // registered so a reconnect's snapshot never re-appends a just-loaded slice.
        const page: WebEvent[] = [];
        for (const ev of r.events) {
          const identity = eventKey(ev);
          if (!STATE_CARRYING.has(ev.type) && earlierKeys.has(identity)) {
            continue;
          }
          const key = dedupKey(ev, STATE_CARRYING.has(ev.type) ? ++seq : seq);
          keys.add(key);
          if (!STATE_CARRYING.has(ev.type)) {
            earlierKeys.add(identity);
          }
          page.push(ev);
        }
        // Prepend the page once: unshifting each event would reverse its server order.
        earlier.unshift(...page);
        loadedEventsRef.current = typeof r.loaded === 'number' ? r.loaded : before + r.events.length;
        publish();
      } catch {
        // a failed earlier-slice fetch is non-fatal: the already-rendered tail remains, the user retries
      }
    };
    // Queue callers, not cursors: each RPC reads the cursor after the previous one settles.
    // fetchEarlier absorbs errors without advancing it, so a later call can retry the same page.
    let loadQueue = Promise.resolve();
    const loadEarlier = (): Promise<void> => {
      loadQueue = loadQueue.then(fetchEarlier);
      return loadQueue;
    };
    loadEarlierRef.current = loadEarlier;

    let source: EventSource | null = null;
    let closed = false;
    let retry = 0;
    let retryTimer = 0;
    let livenessTimer = 0;
    // the stream is "alive" when the browser has an open connection (mirrors the `connected` state so
    // the watchdog interval below can read it without a state dependency).
    let streamOpen = false;
    // set once any real frame has ever been delivered for this session: a genuinely empty session that
    // never produces content must not churn a reconnect forever, so the liveness watchdog only fires
    // for sessions that actually streamed something and then went silent.
    let deliveredContent = false;
    let lastEventAt = 0;

    const reconnect = () => {
      if (closed) return;
      setConnected(false);
      setReconnecting(true);
      source?.close();
      // backoff retry: 500ms, 1s, 2s, ... capped at 10s
      const delay = Math.min(500 * Math.pow(2, retry), 10_000);
      retry++;
      retryTimer = window.setTimeout(connect, delay);
    };

    const connect = () => {
      if (closed) return;
      const es = new EventSource(`/api/stream?session=${encodeURIComponent(sessionId)}`);
      source = es;
      streamOpen = false;

      es.onopen = () => {
        streamOpen = true;
        retry = 0;
        setConnected(true);
        setReconnecting(false);
      };

      es.onerror = () => {
        // the browser EventSource also auto-retries; we track the state so the UI reflects it.
        streamOpen = false;
        setConnected(false);
        setReconnecting(true);
        es.close();
        if (closed) return;
        // backoff retry: 500ms, 1s, 2s, ... capped at 10s
        const delay = Math.min(500 * Math.pow(2, retry), 10_000);
        retry++;
        retryTimer = window.setTimeout(connect, delay);
      };

      es.addEventListener('snapshot', (ev) => {
        if (closed || sessionRef.current !== sessionId) {
          return;
        }
        try {
          const snap = JSON.parse((ev as MessageEvent).data) as {
            events?: WebEvent[];
            total?: number;
            snapshotError?: string;
          };
          if (typeof snap.snapshotError === 'string' && snap.snapshotError) {
            // the server failed to build the full snapshot but still delivered an (empty) one so the
            // loader clears. Surface the failure in the status bar instead of hiding it - this turns a
            // previously-silent "Loading conversation…" hang into a visible error the user can act on.
            append({
              type: 'notice',
              sessionId,
              data: { level: 'ERROR' },
              message: `snapshot failed: ${snap.snapshotError}`,
            });
          }
          // merge history into the live log: NEVER replace, otherwise a reconnect during a
          // turn drops everything streamed in memory and the pane "refreshes" and loses content.
          mergeSnapshot(snap.events ?? []);
          // the snapshot is tail-first (bounded by the configured window): record the full replayed
          // event count so the client knows how much older history session.loadEarlier can still fetch.
          // loadedEventsRef is cumulative and NOT reset here on a reconnect (the log is append-only
          // across connections); it is initialised to the first snapshot's tail length so "more history
          // exists" is true only while older slices remain.
          if (typeof snap.total === 'number') totalReplayRef.current = snap.total;
          if (loadedEventsRef.current === 0 && snap.events?.length) {
            loadedEventsRef.current = snap.events.length;
          }
          lastEventAt = Date.now();
          if (snap.events && snap.events.length > 0) deliveredContent = true;
          // the persisted history has been delivered and seeded - the loader can clear and chat
          // unlocks. Flips true even for an empty session (the server still sends {events:[]}),
          // so an empty/fresh session never shows an infinite loader.
          setBootstrapped(bootstrapFromSnapshot(snap));
          setConnected(true);
          setReconnecting(false);
        } catch {
          // ignore malformed snapshot
        }
      });
      es.addEventListener('agent', (ev) => {
        if (closed || sessionRef.current !== sessionId) {
          return;
        }
        try {
          const event = JSON.parse((ev as MessageEvent).data) as WebEvent;
          append(event);
          lastEventAt = Date.now();
          deliveredContent = true;
        } catch {
          // ignore malformed event
        }
      });
      es.addEventListener('rpc', (ev) => {
        try {
          const request = JSON.parse((ev as MessageEvent).data) as {
            jsonrpc: string;
            id: number;
            method: string;
            params: Record<string, unknown>;
          };
          if (!closed) {
            dispatchServerRequest(request.id, request.method, request.params ?? {}, sessionId);
          }
          lastEventAt = Date.now();
          deliveredContent = true;
        } catch {
          // ignore malformed rpc
        }
      });
    };

    connect();
    // watchdog: re-arm a connection that stops delivering real frames while still open, so a silently
    // stalled backend cannot leave the transcript frozen behind a healthy-looking stream.
    livenessTimer = window.setInterval(() => {
      if (closed || !streamOpen || !deliveredContent) {
        return; // never opened, already handling an error, or nothing to keep alive yet
      }
      if (Date.now() - lastEventAt > LIVENESS_STALE_MS) {
        reconnect();
      }
    }, 10_000);
    return () => {
      closed = true;
      window.clearTimeout(retryTimer);
      window.clearInterval(livenessTimer);
      source?.close();
      // flush any pending batch so the teardown never leaves a stale render scheduled
      setEvents([]);
    };
  }, [sessionId]);

  // Derive the status bar from the single event log so it can never desync from the transcript:
  // usage-updated/turn-metrics/status/notice are "latest wins" messages, so fold over the tail.
  // liveOutChars is also folded from the log (see foldStatus) so it survives a reload; the realtime
  // ref overlay only bumps it higher while a turn streams live, never clobbers the log-derived value.
  const status = useMemo<SessionStatus>(() => {
    const s = foldStatus(events);
    if (liveOutCharsRef.current > 0) s.liveOutChars = Math.max(s.liveOutChars ?? 0, liveOutCharsRef.current);
    return s;
  }, [events]);

  // whether the server still has older persisted history the client has not fetched (tail-first snapshot).
  const moreReplayExists = totalReplayRef.current > loadedEventsRef.current;

  return {
    events,
    connected,
    bootstrapped,
    reconnecting,
    status,
    moreReplayExists,
    loadEarlier: (): Promise<void> => loadEarlierRef.current(),
  };
}

export function foldStatus(events: WebEvent[]): SessionStatus {
  const s: SessionStatus = {};
  // cumulative provider completion/reasoning tokens (monotonic across the session) and the baseline
  // captured at the last turn start: the per-turn live delta is `cumulative - baseline`. Baseline
  // resets when the agent goes busy (a beginTurn status:true frame) so a new turn's counter ticks
  // up from zero while the provider keeps reporting cumulative usage.
  let completionTotal: number | undefined;
  let reasoningTotal: number | undefined;
  let outBase = 0;
  let reasoningBase = 0;
  // last explicit busy flag seen (unset until the first frame carries one): used to detect a real
  // turn start (false -> true) and ignore the repeated busy:true activity frames mid-turn.
  let lastBusy: boolean | undefined;
  // chars of streamed output/reasoning text since the last turn boundary, accumulated over the WHOLE
  // event log (live + replayed). Mirrors foldLiveOutChars but survives a reload, whose snapshot
  // replays text-delta/thinking-delta that the live-only liveOutCharsRef never saw - without it a
  // reloaded session with a no-usage provider would have no token source and render empty metrics.
  let liveOutChars = 0;
  for (const ev of events) {
    if (ev.type === 'text-delta' || ev.type === 'thinking-delta') {
      liveOutChars += ev.message?.length ?? 0;
    } else if (ev.type === 'user') {
      // a new turn starts: reset the out estimate to that turn's streamed text. Unlike the live-only
      // ref (foldLiveOutChars resets on turn-done), this does NOT zero on turn-done/cancelled/error so
      // a reloaded snapshot of a completed turn keeps its displayable out estimate when no turn-metrics
      // or usage was replayed alongside it.
      liveOutChars = 0;
    }
    if (ev.type === 'usage-updated') {
      const d = ev.data ?? {};
      s.model = (ev.message as string) || s.model;
      s.contextPercent = num(d.contextPercent, s.contextPercent);
      s.estimatedContext = num(d.estimatedContextTokens, s.estimatedContext);
      s.promptTokens = num(d.promptTokens, s.promptTokens);
      // Fall back only to this frame's raw prompt, never to a previous frame's full input.
      s.contextInputTokens = num(d.contextInputTokens, num(d.promptTokens, undefined));
      s.completionTokens = num(d.completionTokens, s.completionTokens);
      s.cacheReadTokens = num(d.cacheReadTokens, s.cacheReadTokens);
      completionTotal = num(d.completionTokens, completionTotal) ?? completionTotal;
      reasoningTotal = num(d.reasoningTokens, reasoningTotal) ?? reasoningTotal;
      // Every usage frame replaces quota state, including unsupported/empty values, so a provider
      // switch or a window disappearing cannot leave stale gauges visible.
      if (!d.quota || typeof d.quota !== 'object') {
        s.quota = undefined;
      } else {
        const q = d.quota as Record<string, unknown>;
        const quota = q.enabled && q.supported ? {
          enabled: true,
          supported: true,
          planType: String(q.planType ?? ''),
          usedPercent: q.usedPercent == null ? null : num(q.usedPercent, 0) ?? 0,
          windowSeconds: q.windowSeconds == null ? null : num(q.windowSeconds, 0) ?? 0,
          resetAtEpochSeconds: q.resetAtEpochSeconds == null ? null : num(q.resetAtEpochSeconds, 0) ?? 0,
          windowLabel: String(q.windowLabel ?? ''),
          creditsBalance: String(q.creditsBalance ?? ''),
          creditsUnlimited: !!q.creditsUnlimited,
          windows: Array.isArray(q.windows) ? q.windows.flatMap((raw) => {
            if (!raw || typeof raw !== 'object') return [];
            const w = raw as Record<string, unknown>;
            return [{ kind: String(w.kind ?? ''), label: String(w.label ?? ''),
              usedPercent: typeof w.usedPercent === 'number' ? w.usedPercent : null,
              windowSeconds: typeof w.windowSeconds === 'number' ? w.windowSeconds : null,
              resetAtEpochSeconds: typeof w.resetAtEpochSeconds === 'number' ? w.resetAtEpochSeconds : null }];
          }) : [],
        } : undefined;
        s.quota = quota;
      }
      // session totals persist in the log tail; count LLM calls via turn-metrics below.
    } else if (ev.type === 'turn-metrics') {
      const d = ev.data ?? {};
      s.turnLlmCalls = num(d.llmCalls, s.turnLlmCalls);
      s.turnThinkingMs = num(d.thinkingDurationMs, s.turnThinkingMs);
      s.turnToolsRun = num(d.toolsRun, s.turnToolsRun);
      s.turnToolSumMs = num(d.toolDurationSumMs, s.turnToolSumMs);
      s.turnToolWallMs = num(d.toolDurationWallMs, s.turnToolWallMs);
      s.turnDurationMs = num(d.turnDurationMs, s.turnDurationMs);
      s.outputTokens = num(d.outputTokens, s.outputTokens);
      s.reasoningTokens = num(d.reasoningTokens, s.reasoningTokens);
      // Each metrics frame replaces the rates and their coverage, never retaining another turn's data.
      s.turnThroughputTokPerSec = nonnegative(Object.hasOwn(d, 'requestTokensPerSecond')
        ? d.requestTokensPerSecond : d.tokensPerSecond);
      s.turnServerThroughputTokPerSec = nonnegative(d.serverTokensPerSecond);
      s.turnCompletedRequests = nonnegative(d.completedRequests);
      s.turnReportedRequests = nonnegative(d.reportedRequests);
      s.turnOutputEstimated = typeof d.outputEstimated === 'boolean' ? d.outputEstimated : undefined;
      s.sessionLlmCalls = (s.sessionLlmCalls ?? 0) + (num(d.llmCalls, 0) ?? 0);
    } else if (ev.type === 'status') {
      // a status frame may carry an explicit busy flag (emitted by the web frontend's
      // beginTurn/endTurn/activity). A plain status (e.g. AgentLoop progress reports) carries no
      // busy field and must NOT toggle the busy state - only the message.
      const d = ev.data ?? {};
      if (typeof d.busy === 'boolean') {
        // reset the cumulative-token baseline only when the turn actually starts: a false -> true
        // transition (beginTurn), or the first busy frame of the stream (a mid-turn reconnect). The
        // repeated busy:true "activity" frames and the busy:true tool/thinking labels must NOT reset
        // it, otherwise the per-turn delta would be zeroed mid-generation.
        if (d.busy && lastBusy !== true) {
          outBase = completionTotal ?? 0;
          reasoningBase = reasoningTotal ?? 0;
          // a previous turn's authoritative turn-metrics no longer describes this turn: drop it so the
          // live per-turn delta shows until this turn's own metrics land.
          s.outputTokens = undefined;
          s.reasoningTokens = undefined;
          s.turnThroughputTokPerSec = undefined;
          s.turnServerThroughputTokPerSec = undefined;
          s.turnCompletedRequests = undefined;
          s.turnReportedRequests = undefined;
          s.turnOutputEstimated = undefined;
        }
        lastBusy = d.busy;
        s.busy = d.busy;
      }
      s.notice = { message: (ev.message as string) ?? '' };
    } else if (ev.type === 'notice') {
      const d = ev.data ?? {};
      s.notice = { level: (d.level as string) ?? 'INFO', message: (ev.message as string) ?? '' };
      // compaction lifecycle is a latest-wins flag: the start notice arms it, the result notices
      // (compacted/gate) clear it, and any non-compaction notice leaves it untouched. It is folded
      // separately from busy so a compaction running inside a turn keeps the spinner without
      // disturbing the busy-baseline token accounting.
      if (typeof d.compacting === 'boolean') {
        s.compacting = d.compacting;
      }
    } else if (ev.type === 'btw') {
      // latest-wins single /btw slot: the newest frame (running start, final answer/error, or a
      // clear) wholly replaces the previous btw state. data carries the durable content; the event
      // message is the question (legacy fallback for snapshots built before data.question existed).
      const d = ev.data ?? {};
      // a clear (StatusBar "x" -> session.btwClear) carries {id, cleared:true} with no running/question:
      // it removes the pill + modal. It must NOT reconstruct a fresh running btw from the message
      // (the old code did, because running defaulted to true and question fell back to the message,
      // which re-spawned the pill as if a new /btw had been asked).
      if (d.cleared === true) {
        s.btw = undefined;
      } else {
        const id = typeof d.id === 'number' ? d.id : undefined;
        const running = d.running !== false;
        const question = typeof d.question === 'string' && d.question ? d.question : (ev.message ?? '');
        const answer = typeof d.answer === 'string' ? d.answer : undefined;
        const error = typeof d.error === 'string' ? d.error : undefined;
        s.btw = { id, running, question, answer, error };
      }
    } else if (ev.type === 'turn-done' || ev.type === 'turn-cancelled') {
      s.busy = false;
      lastBusy = false;
      // a compaction can never outlive its turn: clear the pinned compacting flag so a turn that
      // ends without a result notice (cancel/interrupt) does not leave the spinner stuck.
      s.compacting = false;
    } else if (ev.type === 'error') {
      // a turn that errored is no longer running: without this the status bar could stay stuck on
      // "working · Ns" when the loop ends in an error rather than a clean turn-done/turn-cancelled.
      s.busy = false;
      lastBusy = false;
      s.compacting = false;
    }
  }
  // per-turn live out/reasoning: the cumulative provider usage since the last turn start. Only
  // meaningful while the provider reports usage (completionTotal set) and before the exact
  // turn-metrics land (the UI prefers outputTokens/reasoningTokens, so these are the live fallback).
  if (completionTotal != null) {
    const out = Math.max(0, completionTotal - outBase);
    if (out > 0) s.liveOutTokens = out;
  }
  if (reasoningTotal != null) {
    const reasoning = Math.max(0, reasoningTotal - reasoningBase);
    if (reasoning > 0) s.liveReasoningTokens = reasoning;
  }
  // the log-derived streamed-char estimate (last completed turn or in-flight turn): present even on a
  // reloaded session with no provider usage, so the token line never renders empty.
  if (liveOutChars > 0) s.liveOutChars = liveOutChars;
  return s;
}

function nonnegative(value: unknown): number | undefined {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim() === '')) {
    return undefined;
  }
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : undefined;
}

function num(v: unknown, fallback?: number): number | undefined {
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const n = Number(v);
    return Number.isFinite(n) ? n : fallback;
  }
  return fallback;
}

/**
 * Pure accounting for the realtime "out" estimate: accumulates streamed output/reasoning text
 * characters for the current turn, reset to 0 at each turn boundary. O(1) per event, so the hot
 * SSE append path never scans history. The result feeds SessionStatus.liveOutChars, which the UI
 * displays as a chars≈tokens proxy (÷4) until the exact turn-metrics tokens arrive.
 */
export function foldLiveOutChars(prev: number, ev: WebEvent): number {
  if (ev.type === 'text-delta' || ev.type === 'thinking-delta') {
    return prev + (ev.message?.length ?? 0);
  }
  if (ev.type === 'turn-done' || ev.type === 'turn-cancelled' || ev.type === 'error' || ev.type === 'user') {
    return 0;
  }
  return prev;
}

/**
 * Whether receiving a {@code snapshot} frame marks the session as bootstrapped (persisted history
 * delivered). Pure so it is unit-testable without a DOM/EventSource harness.
 *
 * The invariant that matters: bootstrapping is signaled by <em>receiving</em> the snapshot frame, not
 * by it carrying content. A genuinely empty session still gets a {@code snapshot {events:[]}} from the
 * server, so it must also clear the loader — otherwise a fresh/empty session would show an infinite
 * spinner and stay chat-locked forever. Hence this always returns {@code true} for any parsed
 * snapshot payload (unknown/malformed payloads returning false keep the loader up).
 */
export function bootstrapFromSnapshot(snap: unknown): boolean {
  return typeof snap === 'object' && snap !== null && 'events' in (snap as { events?: unknown });
}
