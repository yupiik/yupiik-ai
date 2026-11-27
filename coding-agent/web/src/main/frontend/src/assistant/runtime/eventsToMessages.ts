import type { ThreadMessageLike } from '@assistant-ui/react';
import { eventsToBlocks, type Block } from '../../api/eventLog';
import { eventKey, type WebEvent } from '../../api/useSessionStream';

/** A display projection, never a provider message or an authoritative conversation record. */
export type YcaThreadMessage = ThreadMessageLike & {
  readonly id: string;
  readonly metadata: { readonly custom: { readonly ycaBlock: Block } };
};

function statusFor(block: Block): ThreadMessageLike['status'] {
  if (block.kind === 'user') {
    return undefined;
  }
  if (block.kind === 'tool') {
    if (block.state === 'running') {
      return { type: 'running' };
    }
    if (block.state === 'interrupted') {
      return { type: 'incomplete', reason: 'cancelled' };
    }
    // A timed-out collect is a finished invocation, not a human/tool approval request or a failure.
    if (block.pending) {
      return { type: 'complete', reason: 'unknown' };
    }
    if (block.success === false) {
      return { type: 'incomplete', reason: 'error' };
    }
  } else if (block.kind === 'subagent') {
    if (block.state === 'running') {
      return { type: 'running' };
    }
    if (block.state === 'failed') {
      return { type: 'incomplete', reason: 'error' };
    }
    if (block.state === 'cancelled') {
      return { type: 'incomplete', reason: 'cancelled' };
    }
  } else {
    if (block.kind === 'error') {
      return { type: 'incomplete', reason: 'error' };
    }
    if (block.writing) {
      return { type: 'running' };
    }
  }
  return { type: 'complete', reason: 'stop' };
}

function projectBlock(block: Block): YcaThreadMessage {
  // eventsToBlocks assigns IDs at every creation site, including completion-only fallback cards.
  if (block.id === undefined) {
    throw new Error('A YCA display block must have an origin ID');
  }
  let content: ThreadMessageLike['content'];
  if (block.kind === 'tool') {
    content = [{
      type: 'tool-call',
      toolCallId: block.id,
      toolName: block.name,
      argsText: block.args ?? '',
      ...(block.state === 'done' ? {
        result: block.output ?? '',
        isError: !block.pending && block.success === false,
        isPreliminary: block.pending === true,
      } : {}),
    }];
  } else if (block.kind === 'subagent') {
    // The exact card (including name, model, task and state) remains available to the YCA renderer.
    content = [{ type: 'text', text: block.summary ?? block.task ?? block.name }];
  } else {
    content = [{ type: block.kind === 'thought' ? 'reasoning' : 'text', text: block.content }];
  }
  return {
    id: block.id,
    role: block.kind === 'user' ? 'user' : 'assistant',
    content,
    ...(block.kind === 'user' ? {} : { status: statusFor(block) }),
    metadata: { custom: { ycaBlock: block } },
  };
}

function sameBlock(previous: Block, next: Block): boolean {
  // Never stringify markdown, stdout or captured source on streaming updates.
  let previousSize = 0;
  for (const key in previous) {
    if (key === 'files' && previous.kind === 'tool' && next.kind === 'tool') {
      const left = previous.files;
      const right = next.files;
      if (left !== right && (!left || !right || left.length !== right.length || left.some((file, index) => {
        const other = right[index];
        return file.path !== other.path || file.kind !== other.kind || file.oldText !== other.oldText || file.newText !== other.newText;
      }))) {
        return false;
      }
      previousSize++;
      continue;
    }
    if (previous[key as keyof Block] !== next[key as keyof Block]) {
      return false;
    }
    previousSize++;
  }
  let nextSize = 0;
  for (const _key in next) {
    nextSize++;
  }
  return previousSize === nextSize;
}

type TurnOrigin = { event: WebEvent; key: string; text: string; anchor?: string; origin?: string };

function turnOrigins(events: WebEvent[], keys: WeakMap<WebEvent, string>): TurnOrigin[] {
  const turns: TurnOrigin[] = [];
  let turn: TurnOrigin | undefined;
  let text = '';
  const finish = () => {
    if (turn) {
      turn.text = text;
    }
  };
  for (const event of events) {
    if (event.type === 'web-command' && event.data?.command === 'clear') {
      turns.length = 0;
      turn = undefined;
    } else if (event.type === 'user') {
      finish();
      let key = keys.get(event);
      if (key === undefined) {
        key = eventKey(event);
        keys.set(event, key);
      }
      turn = { event, key, text: '' };
      turns.push(turn);
      text = '';
    } else if (event.type === 'text-delta') {
      text += event.message ?? '';
    } else if (event.type === 'turn-done' && event.message) {
      text = event.message;
    } else if (event.type === 'text-reset') {
      text = '';
    }
  }
  finish();
  return turns;
}

function reconcileOrigins(previous: TurnOrigin[], next: TurnOrigin[], origins: WeakMap<WebEvent, string>): void {
  if (previous.length === 0 || next.length === 0) {
    for (const turn of next) {
      turn.origin = origins.get(turn.event);
    }
    return;
  }
  const positions = new Map(previous.map((turn, index) => [turn.origin, index]));
  const matches = new Map<number, number>();
  const used = new Set<number>();
  const match = (index: number, position: number) => {
    next[index].origin = previous[position].origin;
    matches.set(index, position);
    used.add(position);
  };
  // Live append/prepend is unambiguous even when all prompts and answers are identical.
  next.forEach((turn, index) => {
    const position = positions.get(origins.get(turn.event));
    if (position !== undefined && !used.has(position)) {
      match(index, position);
    }
  });
  if (matches.size === next.length || matches.size === previous.length) {
    // No unmatched old/new pair remains; live append/prepend needs no content anchors.
    return;
  }
  // Reconstructed event objects lose reference identity. Unique prompt/answer anchors locate
  // existing turns even when deltas have been coalesced by replay or history was prepended.
  const unique = (turns: TurnOrigin[]) => {
    const anchors = new Map<string, number>();
    turns.forEach((turn, index) => {
      turn.anchor ??= JSON.stringify([turn.key, turn.text]);
      anchors.set(turn.anchor, anchors.has(turn.anchor) ? -1 : index);
    });
    return anchors;
  };
  const oldAnchors = unique(previous);
  const newAnchors = unique(next);
  next.forEach((turn, index) => {
    const position = oldAnchors.get(turn.anchor!);
    if (!matches.has(index) && position !== undefined && position >= 0 &&
        newAnchors.get(turn.anchor!) === index && !used.has(position)) {
      match(index, position);
    }
  });
  // Align unmatched prefixes/suffixes between anchors. A reconstructed identical prefix is
  // an append, not a reason to renumber old occurrences. Only an entirely replaced identical
  // sequence has no evidence to distinguish an append from a prepend without backend IDs.
  const boundaries = [[-1, -1]];
  next.forEach((_, index) => {
    const position = matches.get(index);
    if (position !== undefined) {
      boundaries.push([index, position]);
    }
  });
  boundaries.push([next.length, previous.length]);
  for (let index = 1; index < boundaries.length; index++) {
    let [left, oldLeft] = boundaries[index - 1];
    let [right, oldRight] = boundaries[index];
    while (++left < right && ++oldLeft < oldRight && next[left].key === previous[oldLeft].key && !used.has(oldLeft)) {
      match(left, oldLeft);
    }
    while (--right >= left && --oldRight >= oldLeft && next[right].key === previous[oldRight].key && !used.has(oldRight)) {
      match(right, oldRight);
    }
  }
}

/**
 * Create once per displayed session (for example with useMemo), then pass the result of project(events)
 * as messages to useExternalStoreRuntime. One runtime message represents one existing display block.
 * Render metadata.custom.ycaBlock with the existing YCA block renderer; do not feed these messages
 * back into the backend, persistence, compaction or provider cache identity.
 *
 * The reducer remains the sole authority for display semantics. Each call rebuilds its blocks, then
 * reuses messages, parts and metadata for unchanged flat projections. Only the current snapshot is
 * cached, so removed/reset/cleared blocks are not retained. An unchanged projection also reuses the
 * array. Adapter work/storage is linear in the current blocks, in addition to eventsToBlocks itself.
 * No React lifecycle, subscription, network access or source-event mutation is involved.
 */
export function createYcaMessageProjector(): (events: WebEvent[]) => YcaThreadMessage[] {
  let previous = new Map<string, YcaThreadMessage>();
  let messages: YcaThreadMessage[] = [];
  let turns: TurnOrigin[] = [];
  const origins = new WeakMap<WebEvent, string>();
  const keys = new WeakMap<WebEvent, string>();
  // Event objects are immutable. Cache the expensive exact origin once, not its growing output.
  // Interning uses exact strings (no hashes/collisions), and retains only the current snapshot's
  // origins. Weak entries allow a still-referenced event to return after an empty/cleared window.
  type BlockOrigin = { key: string; id: number };
  const blockOrigins = new WeakMap<WebEvent, BlockOrigin>();
  let interned = new Map<string, BlockOrigin>();
  let nextTurnId = 0;
  let nextBlockOriginId = 0;
  const sessions = new Map<string, number>();
  const blockIds = new WeakMap<WebEvent, {
    session: number; scope: string; kind: Block['kind']; originId: number;
    key: string; occurrence: number; id: string;
  }>();
  return (events) => {
    const nextTurns = turnOrigins(events, keys);
    reconcileOrigins(turns, nextTurns, origins);
    for (const turn of nextTurns) {
      turn.origin ??= String(++nextTurnId);
      origins.set(turn.event, turn.origin);
    }
    const nextOrigins = new Map<string, BlockOrigin>();
    const occurrences = new Map<string, number>();
    const next = new Map<string, YcaThreadMessage>();
    let turn = '';
    const projected = eventsToBlocks(events, (event, kind) => {
      let session = sessions.get(event.sessionId);
      if (session === undefined) {
        session = sessions.size + 1;
        sessions.set(event.sessionId, session);
      }
      if (kind === 'user') {
        turn = origins.get(event)!;
        return `yca:${session}:${turn}:user`;
      }
      let originId = 0;
      if (kind !== 'text' && kind !== 'thought') {
        let origin = blockOrigins.get(event);
        if (origin === undefined) {
          const key = JSON.stringify(kind === 'tool'
            ? [kind, event.data?.toolCallId ?? null, event.message ?? '', event.data?.args ?? '']
            : kind === 'subagent'
              ? [kind, event.subagentId ?? '']
              : [kind, eventKey(event)]);
          origin = interned.get(key) ?? nextOrigins.get(key) ?? { key, id: ++nextBlockOriginId };
          blockOrigins.set(event, origin);
        } else {
          // A replay may have interned the same exact origin under another event reference.
          origin = interned.get(origin.key) ?? nextOrigins.get(origin.key) ?? origin;
        }
        nextOrigins.set(origin.key, origin);
        originId = origin.id;
      }
      const scope = kind === 'subagent' ? '' : turn;
      const cached = blockIds.get(event);
      const sameOrigin = cached?.session === session && cached.scope === scope &&
        cached.kind === kind && cached.originId === originId;
      const key = sameOrigin ? cached.key : `yca:${session}:${scope}:${kind}:${originId}`;
      const occurrence = occurrences.get(key) ?? 0;
      occurrences.set(key, occurrence + 1);
      if (sameOrigin && cached.occurrence === occurrence) {
        return cached.id;
      }
      const id = `${key}:${occurrence}`;
      blockIds.set(event, { session, scope, kind, originId, key, occurrence, id });
      return id;
    }).map((block) => {
      const cached = block.id === undefined ? undefined : previous.get(block.id);
      const message = cached && sameBlock(cached.metadata.custom.ycaBlock, block)
        ? cached
        : projectBlock(block);
      next.set(message.id, message);
      return message;
    });
    previous = next;
    interned = nextOrigins;
    turns = nextTurns;
    if (projected.length !== messages.length || projected.some((message, index) => message !== messages[index])) {
      messages = projected;
    }
    return messages;
  };
}
