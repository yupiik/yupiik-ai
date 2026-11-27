import { eventKey, type WebEvent } from './useSessionStream';
import { parseFileChanges, type FileChange } from '../lib/fileChanges';

/**
 * The transcript blocks derived from the raw SSE event log. Pure data: the same events always
 * produce the same blocks, which is what makes the reducer unit-testable (temporary vitest) and
 * cheap to re-run per render batch (it is O(events), and events arrive rAF-batched).
 */
export type Block = { id?: string; startedAt?: number; speakerLabel?: string } & (
  | {
      // 'notice' is a standalone server-emitted transcript fragment (e.g. the Codex authorize URL)
      // rendered as its own block; it is never merged with streaming text-delta or overwritten by
      // turn-done consolidation, unlike 'text'. 'error' is a failed/aborted turn message.
      kind: 'text' | 'thought' | 'notice' | 'error' | 'user';
      content: string;
      state?: string;
      output?: string;
      // true while a live stream is still appending to this block (text-delta/thinking-delta since the
      // last non-streaming event). Renderers debounce such blocks so a half-open markdown span (an
      // inline-code backtick whose closer is still streaming) is never parsed into a bogus pairing.
      writing?: boolean;
    }
  | {
      kind: 'tool';
      name: string;
      content: string;
      // 'running' while live, 'done' when a tool-finished resolved it, 'interrupted' when a turn
      // boundary (cancel/done/error) ended the turn without a tool-finished for this card.
      state: 'running' | 'done' | 'interrupted';
      // the raw tool-arguments JSON carried by tool-started (for bash the `command` / workdir,
      // for read_file the `path`, ...), used to guess the output language for highlighting.
      // Null when the event carried none (e.g. replayed history with empty args).
      args?: string;
      output?: string;
      success?: boolean;
      // a tool that finished but is not done (e.g. check_subagents collect timed out): renders neutral,
      // distinct from both a success and a hard failure
      pending?: boolean;
      toolOrdinal?: number;
      subagentId?: string;
      files?: FileChange[];
    }
  | {
      // A spawned sub-agent card: 'running' while the child works (async, outlives the parent
      // turn), finalized by its own subagent-completed (never by a turn boundary). The final
      // summary renders as markdown; the model is the effective one resolved by the runner.
      kind: 'subagent';
      // 'running' while live, then 'done' (success), 'failed' or 'cancelled' from the completion.
      state: 'running' | 'done' | 'failed' | 'cancelled';
      subagentId: string;
      name: string;
      // the spawn task, present while running
      task?: string;
      // the final report summary
      summary?: string;
      model?: string;
    });

const EMPTY_TOOL_STACK: number[] = [];

/**
 * Display identity only. Captured timestamps and server-rendered labels are presentation metadata,
 * never identity. The DTO has no persisted event ID. Anchor streams
 * to their user event, not their growing text (replay can replace many deltas with one full delta).
 * Count identical user origins from the right so loading older identical prompts does not rename
 * the visible suffix. Within a turn, count each originating block, never delta events or array slots.
 * These are deterministic snapshot IDs, not stable identities across edits to the event window.
 * A stateful UI projector can reconcile user origins while leaving this pure default unchanged.
 */
function blockIds(events: WebEvent[]): (event: WebEvent, kind: Block['kind']) => string {
  const users = new Map<string, number>();
  for (const event of events) {
    if (event.type === 'user') {
      const key = eventKey(event);
      users.set(key, (users.get(key) ?? 0) + 1);
    }
  }
  let turn = '';
  const occurrences = new Map<string, number>();
  return (event, kind) => {
    if (kind === 'user') {
      const key = eventKey(event);
      const occurrence = users.get(key)! - 1;
      users.set(key, occurrence);
      const origin = JSON.stringify([key, occurrence]);
      turn = origin;
      return JSON.stringify(['yca', event.sessionId, turn, 'user']);
    }
    // Tool ordinals are reconstructed session-wide by WebReplay, unlike live turn ordinals.
    // The original name/arguments survive both representations. Neither results nor stdout belong
    // in identity. Sub-agent IDs also survive a replay containing only the completed event.
    const origin = kind === 'tool'
      ? [event.data?.toolCallId ?? null, event.message ?? '', event.data?.args ?? '']
      : kind === 'subagent'
        ? [event.subagentId ?? '']
        : kind === 'text' || kind === 'thought'
          ? []
          : [eventKey(event)];
    const key = JSON.stringify(['yca', event.sessionId, kind === 'subagent' ? '' : turn, kind, origin]);
    const occurrence = occurrences.get(key) ?? 0;
    occurrences.set(key, occurrence + 1);
    return JSON.stringify([key, occurrence]);
  };
}

/**
 * Terminal tool-state marker used when a turn boundary (cancelled/done/error) leaves a tool card still
 * "running": no `tool-finished` is ever going to arrive for it (the turn already ended - e.g. a cancel
 * hit between the assistant tool_calls and the tool result), so the card is settled as interrupted
 * rather than left spinning "working" forever.
 */
export const INTERRUPTED = 'interrupted' as const;

/**
 * Whether {@code candidate} is a "lossy superset" of {@code base}: every whitespace-delimited word of
 * {@code base} appears in {@code candidate} in the same order, with {@code candidate} allowed to contain
 * extra words in between. This models a streamed copy that lost tokens mid-text (fragments dropped while
 * live) where the replayed/final copy is the complete authoritative text - in the observed duplication
 * the streamed tail read "Noalls found" while the final read "No assistant-with-toolCalls found".
 *
 * <p>A plain {@code startWith} comparison (what {@code turn-done} used) misses these mid-text gaps, which
 * is exactly why the replayed final was previously appended as a new block instead of superseding. Word
 * subsequence (not the identity of a {@code startWith} prefix) tolerates the dropped tokens.
 */
export function lossySupersedes(candidate: string, base: string): boolean {
  if (candidate === base) return !!base;
  if (!candidate || !base) return false;
  if (candidate.length < base.length) return false;
  // greedy character-subsequence: is base a (possibly non-contiguous) subsequence of candidate? This
  // tolerates the streaming-loss signature where the live tail dropped/fragmented tokens (e.g. base
  // "Noalls found" is a subsequence of "No assistant-with-toolCalls found"). Linear, so it stays cheap
  // even for long assistant answers.
  let b = 0;
  for (let c = 0; c < candidate.length && b < base.length; c++) {
    if (candidate[c] === base[b]) b++;
  }
  return b === base.length;
}

/**
 * Collapses a replayed full-text delta that duplicates an already-rendered same-turn text block.
 *
 * <p>When an SSE reconnect re-delivers a completed assistant turn, the snapshot carries the turn's full
 * final text as one {@code text-delta} (plus reconstructed tool cards) that lossy-supersedes the text
 * already streamed live earlier in the same turn - the streamed copy may have lost tokens mid-stream
 * (e.g. {@code "Noalls found"} vs {@code "No assistant-with-toolCalls found"}). Without this guard, the
 * full-text would be rendered as a second assistant block and the reconstructed tool cards as duplicate
 * cards (the observed web duplication). When the incoming text lossy-supersedes a same-turn earlier text
 * block (nothing but tool cards between, no user boundary), we heal that block with the complete text,
 * drop the replayed tool cards, and skip pushing a duplicate - so there is exactly one assistant answer
 * and one set of cards per turn.</p>
 *
 * <p>A genuinely new assistant answer (a different turn / a new reasoning round) is not a superset of the
 * earlier text, so it is never collapsed - it falls through to be pushed normally.</p>
 *
 * @return true when a duplicate was collapsed (the caller must not push a new text block), false to push normally.
 */
function collapseReplayDuplicate(blocks: Block[], runningTools: Map<string, number[]>, msg: string): boolean {
  // walk back over tool cards only (a replayed turn chunk's cards) to the last same-turn text block
  let i = blocks.length - 1;
  while (i >= 0) {
    const b = blocks[i];
    if (b.kind === 'user') return false; // different turn - not a duplicate
    if (b.kind === 'text') {
      if (lossySupersedes(msg, b.content)) {
        // heal the streamed block with the authoritative complete text
        b.content = msg;
        b.writing = true;
        // Drop ONLY the replayed duplicate tool cards that sit in the contiguous suffix after the
        // healed text. A card still `running` is a LIVE tool of the ongoing turn and must never be
        // spliced away: a mid-turn SSE reconnect replays a full text-delta of a PRIOR completed round
        // into a log that already holds the currently-executing tool's card after that text, and the
        // old whole-tail splice deleted that live running card (the "currently running tool sometimes
        // disappears" bug). Finished/replayed cards (`done`/`interrupted`) are interchangeable with a
        // replayed reconstruction (the dedup this collapse exists for), so they stay removable; the
        // payment is never crossing a running card or a non-tool block.
        let cut = blocks.length;
        for (let k = blocks.length - 1; k > i; k--) {
          const c = blocks[k];
          if (c.kind === 'tool' && c.state !== 'running') {
            cut = k; // tentatively part of the removable replayed-duplicate suffix
            continue;
          }
          cut = k + 1; // a running live card (or any non-tool block): keep it and everything before
          break;
        }
        const removed = blocks.splice(cut, blocks.length - cut);
        for (const card of removed) {
          if (card.kind === 'tool') {
            const stack = runningTools.get(card.name);
            if (stack) {
              const j = stack.indexOf(cut);
              if (j >= 0) stack.splice(j, 1);
              if (stack.length === 0) runningTools.delete(card.name);
            }
          }
        }
        return true;
      }
      return false; // a same-turn text that the incoming does NOT supersede - distinct content, push normally
    }
    i--;
  }
  return false; // no same-turn text found - nothing to duplicate
}

/**
 * Reduces the ordered event log into transcript blocks. Appends never mutate earlier blocks
 * (text-delta only touches the LAST block), so a memoized BlockView keyed by block index sees
 * stable content for everything except the live tail.
 * Running tools are tracked per name (a deque of block indices), mirroring the core
 * ConversationTranscript's runningTools: BOTH tool-stream and tool-finished bind to the most
 * recently started card with that tool name, so they can never "finish" the wrong card (which
 * previously left cards stuck in the 'running' state).
 * The optional idFor supplies display-only identities in stateful projectors;
 * without it, IDs remain a deterministic function of this snapshot alone.
 */
export function eventsToBlocks(events: WebEvent[], idFor?: (event: WebEvent, kind: Block['kind']) => string): Block[] {
  // /clear clears the visible conversation, not the persisted session or its usage counters.
  const cleared = events.findLastIndex((event) => event.type === 'web-command' && event.data?.command === 'clear');
  if (cleared >= 0) {
    events = events.slice(cleared + 1);
  }
  const blocks: Block[] = [];
  idFor ??= blockIds(events);
  const runningTools = new Map<string, number[]>();
  let feedback: { startedAt?: number; speakerLabel?: string } | undefined;
  const feedbackOf = (event: WebEvent) => ({
    ...(typeof event.data?.startedAt === 'number' ? { startedAt: event.data.startedAt } : {}),
    ...(typeof event.data?.speakerLabel === 'string' ? { speakerLabel: event.data.speakerLabel } : {}),
  });
  // Any event that is not a streaming delta marks the end of the current stream burst, so the trailing
  // text/thought block(s) are no longer "writing" and renderers may debounce-flush them.
  const settle = () => {
    for (let i = blocks.length - 1; i >= 0; i--) {
      const b = blocks[i];
      if (b.kind === 'text' || b.kind === 'thought') {
        b.writing = false;
        break;
      }
      if (b.kind === 'tool') break; // text/thought only trail after a tool card
    }
  };
  // A turn boundary means none of the still-running tool cards will ever get a tool-finished (a cancel
  // landed between the assistant tool_calls being persisted and the tool result). Settle them all as
  // 'interrupted' and clear the running stack so no card is left spinning "working" after the turn ends.
  const settleInterrupted = () => {
    for (const [, stack] of runningTools) {
      for (const idx of stack) {
        const b = blocks[idx];
        if (b?.kind === 'tool' && b.state === 'running') {
          b.state = INTERRUPTED;
        }
      }
    }
    runningTools.clear();
  };
  for (const ev of events) {
    const last = blocks[blocks.length - 1];
    if (ev.type === 'feedback-started') {
      settle();
      feedback = feedbackOf(ev);
      continue;
    }
    const fresh = feedback !== undefined || ev.data?.feedbackStart === true;
    const timing = feedback ?? feedbackOf(ev);
    if (ev.type === 'text-delta') {
      if (last?.kind === 'thought') {
        last.writing = false;
      }
      if (last?.kind === 'text' && !fresh) {
        last.content += ev.message ?? '';
        last.writing = true;
      } else {
        // A replay-duplicate guard: on an SSE reconnect the snapshot re-delivers a completed assistant
        // turn as one full-text delta that lossy-supersedes the already-streamed (and possibly token-lossy)
        // same-turn text block, with the replayed tool card(s) re-created between them. Collapse instead of
        // duplicating: heal the earlier streamed block with the complete text and drop the replayed cards.
        const msg = ev.message ?? '';
        const collapsed = !fresh && collapseReplayDuplicate(blocks, runningTools, msg);
        if (!collapsed) {
          blocks.push({ id: idFor(ev, 'text'), kind: 'text', content: msg, writing: true, ...timing });
        }
      }
    } else if (ev.type === 'thinking-delta') {
      if (last?.kind === 'text') {
        last.writing = false;
      }
      if (last?.kind === 'thought' && !fresh) {
        last.content += ev.message ?? '';
        last.writing = true;
      } else {
        blocks.push({ id: idFor(ev, 'thought'), kind: 'thought', content: ev.message ?? '', writing: true, ...timing });
      }
    } else if (ev.type === 'tool-diagnostic') {
      // A separate live display block, never a tool result or provider message.
      settle();
      const scope = ev.subagentId ? `[${ev.subagentName ?? ev.subagentId}] ` : '';
      const ordinal = typeof ev.data?.ordinal === 'number' && ev.data.ordinal > 0 ? ` #${ev.data.ordinal}` : '';
      blocks.push({
        id: idFor(ev, 'notice'), kind: 'notice',
        content: `${scope}${ev.message ?? 'Tool'}${ordinal} diagnostic:\n${String(ev.data?.detail ?? '')}`,
      });
    } else if (ev.type === 'tool-started') {
      settle();
      const name = ev.message ?? ev.type;
      const idx = blocks.length;
      const ord = ev.data?.ordinal;
      const args = typeof ev.data?.args === 'string' && ev.data.args.trim() ? ev.data.args : undefined;
      blocks.push({
        id: idFor(ev, 'tool'),
        kind: 'tool',
        name,
        content: '',
        state: 'running',
        args,
        toolOrdinal: typeof ord === 'number' && ord > 0 ? ord : undefined,
        ...(ev.subagentId ? { subagentId: ev.subagentId } : {}),
      });
      runningTools.set(name, [...(runningTools.get(name) ?? EMPTY_TOOL_STACK), idx]);
    } else if (ev.type === 'tool-stream') {
      settle();
      // the DTO maps message=name and data.fragment (the live stdout fragment). Bind by the per-call
      // ordinal when the runner stamped one (repeated same-name tools then never mis-route a late
      // stream), falling back to the most recent running card for legacy events without an ordinal.
      const toolBlock = ordinalBlock(blocks, runningTools, ev);
      if (toolBlock && toolBlock.kind === 'tool') {
        toolBlock.content += (ev.data?.fragment as string) ?? '';
        toolBlock.content += '\n';
      }
    } else if (ev.type === 'tool-files-changed') {
      settle();
      // Mutation events precede tool-finished. Never attach an unmatched ordinal
      // or another agent's file contents to a same-name card.
      const stack = runningTools.get(ev.message ?? '') ?? EMPTY_TOOL_STACK;
      const ordinal = ev.data?.ordinal;
      const target = stack.findLast((index) => {
        const card = blocks[index];
        return card.kind === 'tool' && (card.subagentId ?? '') === (ev.subagentId ?? '')
          && (!(typeof ordinal === 'number' && ordinal > 0) || card.toolOrdinal === ordinal);
      });
      const card = target === undefined ? undefined : blocks[target];
      const files = parseFileChanges(ev.data?.files);
      if (card?.kind === 'tool' && files.length > 0) {
        card.files = files;
      }
    } else if (ev.type === 'tool-finished') {
      settle();
      const stack = runningTools.get(ev.message ?? '');
      // prefer finishing the exact started call identified by the runner's ordinal; fall back to the
      // name deque (parallel batches, legacy events) when the ordinal never matched.
      let toolBlock: Block | undefined;
      if (stack && stack.length > 0) {
        const ord = ev.data?.ordinal;
        const idx = typeof ord === 'number' && ord > 0
          ? stack.findIndex((i) => blocks[i]?.kind === 'tool' && blocks[i].toolOrdinal === ord)
          : -1;
        if (idx >= 0) {
          toolBlock = blocks[stack.splice(idx, 1)[0]];
        } else {
          toolBlock = blocks[stack.pop()!];
        }
        if (stack.length === 0) runningTools.delete(ev.message ?? '');
      }
      if (toolBlock && toolBlock.kind === 'tool') {
        toolBlock.state = 'done';
        toolBlock.output = (ev.data?.output as string) ?? '';
        toolBlock.success = ev.data?.success !== false;
        toolBlock.pending = ev.data?.pending === true;
      }
    } else if (ev.type === 'subagent-started') {
      settle();
      blocks.push({
        id: idFor(ev, 'subagent'),
        kind: 'subagent',
        state: 'running',
        subagentId: ev.subagentId ?? '',
        name: (ev.data?.subagentName as string) || ev.subagentId || '',
        task: ev.message ?? '',
      });
    } else if (ev.type === 'subagent-completed') {
      settle();
      const id = ev.subagentId ?? '';
      const finalState = ev.data?.cancelled === true ? 'cancelled' : ev.data?.success === false ? 'failed' : 'done';
      // finalize the running card of the same sub-agent (a child can outlive several parent
      // turns); when the started event rotated out of the tail (replay/TAIL_LIMIT) or was lost,
      // push a finalized card so the report is never silently dropped
      let target = -1;
      for (let i = blocks.length - 1; i >= 0; i--) {
        const b = blocks[i];
        if (b && b.kind === 'subagent' && b.subagentId === id) {
          target = i;
          break;
        }
      }
      const summary = ev.message ?? '';
      const model = (ev.data?.model as string) || undefined;
      if (target >= 0) {
        const b = blocks[target] as Extract<Block, { kind: 'subagent' }>;
        b.state = finalState;
        b.summary = summary;
        if (ev.data?.subagentName) {
          b.name = ev.data.subagentName as string;
        }
        if (model) b.model = model;
      } else {
        blocks.push({
          id: idFor(ev, 'subagent'),
          kind: 'subagent',
          state: finalState,
          subagentId: id,
          name: (ev.data?.subagentName as string) || id,
          summary,
          model,
        });
      }
    } else if (ev.type === 'user') {
      settle();
      blocks.push({ id: idFor(ev, 'user'), kind: 'user', content: ev.message ?? '', ...timing });
    } else if (ev.type === 'transcript') {
      // A server-emitted standalone fragment (e.g. the Codex authorize URL pushed through
      // WebShellFrontend.transcript during login). It becomes its own 'notice' block so it is never
      // merged with a streaming assistant text-delta nor overwritten by turn-done consolidation.
      // EXCEPTION: the /executing-tools transcript (identified by its "**Executing tools**" header)
      // is only-modal - it never renders inline, only in the dedicated modal (mirroring /btw).
      if (ev.message?.startsWith('**Executing tools**')) {
        // only-modal: the content lives only in the /executing-tools modal; still settle any
        // in-progress stream so a following text-delta starts a fresh block (mirrors the notice path)
        settle();
      } else {
        settle();
        blocks.push({ id: idFor(ev, 'notice'), kind: 'notice', content: ev.message ?? '' });
      }
    } else if (ev.type === 'btw') {
      // /btw answers live in the status bar pill + modal (folded from the same log by foldStatus),
      // never in the transcript stream - the side conversation must not pollute the main thread.
      // Deliberately no-op: the event carries no transcript block.
    } else if (ev.type === 'error') {
      settle();
      settleInterrupted();
      blocks.push({ id: idFor(ev, 'error'), kind: 'error', content: ev.message ?? '', state: 'done' });
    } else if (ev.type === 'turn-done') {
      settle();
      settleInterrupted();
      // The final assistant message is authoritative and complete: it must SUPERSEDE whatever
      // streamed into the tail, never be appended (appending duplicated the whole answer whenever
      // the final content differed from the accumulated stream by a trailing char or spanned two
      // reasoning rounds). Mirror the core ConversationTranscript consolidation: when the streamed
      // text is a fuller superset of the final content (a clipped provider reply) the streamed text
      // wins and nothing is lost; otherwise the final content replaces the tail (regeneration or a
      // genuine extension of what streamed, so there is never a duplicated copy).
      if (ev.message) {
        if (last?.kind === 'text') {
          const cur = last.content;
          if (ev.message.startsWith(cur)) {
            last.content = ev.message; // final content extends/supersedes the streamed prefix
          } else if (!cur.startsWith(ev.message)) {
            last.content = ev.message; // regenerated / diverged: supersede, no duplication
          }
          // else cur.startsWith(ev.message): streamed is fuller (clipped reply) -> keep it
        } else {
          blocks.push({ id: idFor(ev, 'text'), kind: 'text', content: ev.message ?? '', ...timing });
        }
      }
    } else if (ev.type === 'turn-cancelled') {
      settle();
      settleInterrupted();
    } else if (ev.type === 'text-reset') {
      // a stream retry discards ONLY the partial assistant rendering, never the whole history:
      // drop the trailing in-progress text/thought so prior turns stay visible.
      while (blocks.length > 0) {
        const b = blocks[blocks.length - 1];
        if (b.kind === 'text' || b.kind === 'thought') blocks.pop();
        else break;
      }
    }
    if (ev.type === 'text-delta' || ev.type === 'thinking-delta' || ev.type === 'text-reset'
      || ev.type === 'turn-done' || ev.type === 'turn-cancelled' || ev.type === 'user') {
      feedback = undefined;
    }
  }
  return blocks;
}

/**
 * Resolves the tool card a stream/finished event targets: the running card whose {@code toolOrdinal}
 * matches the event's {@code data.ordinal} when the runner stamped one, else the most recently started
 * card of that tool name (the name-deque topology). This makes repeated same-name tools land on their
 * own card even when a sibling is still streaming.
 */
function ordinalBlock(blocks: Block[], runningTools: Map<string, number[]>, ev: WebEvent): Block | undefined {
  const stack = runningTools.get(ev.message ?? '');
  if (!stack || stack.length === 0) return undefined;
  const ord = ev.data?.ordinal;
  if (typeof ord === 'number' && ord > 0) {
    for (let i = stack.length - 1; i >= 0; i--) {
      const card = blocks[stack[i]];
      if (card?.kind === 'tool' && card.toolOrdinal === ord) return card;
    }
  }
  return blocks[stack[stack.length - 1]];
}

/**
 * Computes the signature of the most recent "queue/sidebar drain" boundary in an event log:
 * the latest {@code prompt-integrated}, {@code turn-done} or {@code turn-cancelled} scanning
 * backward from the tail. A {@code prompt-integrated} is identified by its prompt text (each queued
 * prompt is unique); a turn boundary carries no message, so its type+sessionId is enough.
 *
 * <p>Returns {@code null} when no boundary is present. This exists so the web UI can react to a
 * queued prompt being absorbed (or a turn ending) by refreshing the queue strip + sidebar even when
 * the boundary is NOT the last event - mid-turn, the agent loop immediately streams status/text/usage
 * frames right after a {@code prompt-integrated}, so a tail-only check would miss it and leave the
 * green "N queued prompt(s)" block stale until turn-done.</p>
 */
export function latestDrainBoundary(events: WebEvent[]): string | null {
  for (let i = events.length - 1; i >= 0; i--) {
    const ev = events[i];
    if (ev.type !== 'turn-done' && ev.type !== 'turn-cancelled' && ev.type !== 'prompt-integrated') continue;
    return ev.type === 'prompt-integrated'
      ? `${ev.type}:${ev.sessionId}:${ev.message ?? ''}`
      : `${ev.type}:${ev.sessionId}`;
  }
  return null;
}

/** Chunk size of one "Load earlier" pagination step. */
export const LOAD_EARLIER_CHUNK = 256;

/**
 * The rendered window: the last {@code visible} blocks plus at most one partial tool card before
 * them (a tool-started just outside the window would otherwise render as stuck "working" - the
 * card is atomic and must not be split). Returns [firstBlockToRender, hadToExtend].
 */
export function renderWindow(blocks: Block[], visible: number): { start: number; extended: boolean } {
  if (blocks.length <= visible) {
    return { start: 0, extended: false };
  }
  let start = blocks.length - visible;
  // a tool block that started right before the window belongs to it (its finished/stream events
  // are inside the window): extend the window so the card is complete
  if (start > 0 && blocks[start - 1].kind === 'tool' && blocks[start - 1].state === 'running') {
    start -= 1;
  }
  return { start, extended: start > 0 };
}
