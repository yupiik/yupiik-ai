/** Minimal JSON-RPC 2.0 client for the YCA web backend. */

let nextId = 1;

export type ServerRequest = {
  id: number;
  method: string;
  params: Record<string, unknown>;
  /** Source SSE stream, not whichever session happens to be selected on delivery. */
  sessionId?: string;
};

type ServerRequestHandler = (request: ServerRequest, respond: (value: unknown) => void) => void;

const handlers: Array<{ resolve: (value: unknown) => void; onRequest: ServerRequestHandler }> = [];

export async function call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  const response = await fetch('/api/rpc', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: nextId++, method, params }),
  });
  const envelope = (await response.json()) as { result?: T; error?: { code: number; message: string } };
  if (envelope.error) {
    throw new Error(envelope.error.message || `RPC error ${envelope.error.code}`);
  }
  return envelope.result as T;
}

/**
 * Dispatches a server-initiated JSON-RPC request (approval/question). The SSE
 * layer calls this when it reads an {@code event: rpc} frame; the caller of
 * {@code onServerRequest} answers via the provided closure.
 *
 * Only the most recently registered handler receives the request (the active page's), so the
 * response is posted exactly once - if every handler ran, several mounted pages would each POST
 * the same resolution back to the backend.
 */
export function dispatchServerRequest(id: number, method: string, params: Record<string, unknown>, sessionId?: string): void {
  const request = { id, method, params, ...(sessionId ? { sessionId } : {}) };
  const entry = handlers[handlers.length - 1];
  if (!entry) return;
  entry.onRequest(request, (value) => {
    // resolve the backend future by POSTing the response back
    void fetch('/api/rpc', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id, result: value ?? null }),
    });
  });
}

export function onServerRequest(handler: ServerRequestHandler): () => void {
  const entry = { resolve: () => {}, onRequest: handler };
  handlers.push(entry);
  return () => {
    const i = handlers.indexOf(entry);
    if (i >= 0) handlers.splice(i, 1);
  };
}
