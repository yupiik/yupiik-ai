import { useCallback, useEffect, useReducer, useRef } from 'react';
import { call, onServerRequest, type ServerRequest } from '../../api/rpc';

export type InteractiveRequest = ServerRequest & { sessionId?: string };
export type RequestState = InteractiveRequest | null;
export type AnswerRequest = (id: number, value: unknown) => void | Promise<void>;

/** FIFO queues survive session navigation for the lifetime of this mounted hook. */
export function useInteractiveRequests(sessionId: string | undefined) {
  const queues = useRef(new Map<string, InteractiveRequest[]>());
  const flights = useRef(new Map<string, Promise<void>>());
  const completed = useRef(new Set<string>());
  const mounted = useRef(false);
  const [, refresh] = useReducer((value: number) => value + 1, 0);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    if (!sessionId) {
      return;
    }
    // Retain the source stream's session, not a later selected tab. Older callers
    // without source metadata remain scoped to this subscription.
    return onServerRequest((request, respond) => {
      if (request.sessionId && request.sessionId !== sessionId) {
        return;
      }
      const key = `${sessionId}:${request.id}`;
      if (request.method === 'session.requestCancelled') {
        completed.current.add(key);
        queues.current.set(sessionId, (queues.current.get(sessionId) ?? []).filter((item) => item.id !== request.id));
        refresh();
        return;
      }
      if (!['session.approval', 'session.ask', 'session.question'].includes(request.method)) {
        respond(null);
        return;
      }
      const queue = queues.current.get(sessionId) ?? [];
      if (completed.current.has(key) || queue.some((item) => item.id === request.id)) {
        return;
      }
      queues.current.set(sessionId, [...queue, { ...request, params: request.params ?? {}, sessionId }]);
      refresh();
    });
  }, [sessionId]);

  const answer = useCallback((requestId: number, value: unknown): Promise<void> => {
    const queue = sessionId ? queues.current.get(sessionId) : undefined;
    if (!sessionId || !queue?.some((item) => item.id === requestId)) {
      return Promise.reject(new Error('This request does not belong to the selected session.'));
    }
    const key = `${sessionId}:${requestId}`;
    const existing = flights.current.get(key);
    if (existing) {
      return existing;
    }
    const operation = (async () => {
      // Defer the call so flights is populated even if call throws synchronously.
      await Promise.resolve();
      try {
        const response = await call<{ resolved: boolean }>('session.resolve', {
          id: sessionId, requestId: String(requestId), result: value,
        });
        if (response?.resolved === false) {
          // The server says the future no longer exists (Stop, timeout, or an
          // answer from another client). It cannot be retried; advance the queue.
          completed.current.add(key);
          queues.current.set(sessionId, (queues.current.get(sessionId) ?? []).filter((item) => item.id !== requestId));
          throw new Error('This request is no longer pending (it may have been cancelled or answered elsewhere).');
        }
        if (response?.resolved !== true) {
          throw new Error('The server did not acknowledge this answer. Please retry.');
        }
        completed.current.add(key);
        // Remove only this request: another may arrive while the POST is pending.
        queues.current.set(sessionId, (queues.current.get(sessionId) ?? []).filter((item) => item.id !== requestId));
      } finally {
        flights.current.delete(key);
        if (mounted.current) {
          refresh();
        }
      }
    })();
    flights.current.set(key, operation);
    refresh();
    return operation;
  }, [sessionId]);

  const queue = (sessionId ? queues.current.get(sessionId) : undefined) ?? [];
  const pending = queue[0] ?? null;
  return {
    pending,
    answer,
    pendingCount: queue.length,
    answering: pending !== null && flights.current.has(`${sessionId}:${pending.id}`),
  };
}
