// Serialized into Page.addScriptToEvaluateOnNewDocument. No application modules are replaced.
export function installFixture() {
  const sessionId = 'browser-fixture';
  const streams = new Set();
  const calls = [];
  const resolutions = [];
  const unknown = [];
  let todos = [];
  const event = (type, message = '', data = {}, extra = {}) => ({ type, message, data, sessionId, ...extra });
  const history = Array.from({ length: 1100 }, (_, i) => [
    event('user', `History prompt ${i}`), event('turn-done', `History answer ${i}`),
  ]).flat();
  const emit = (kind, value) => {
    for (const source of streams) {
      source.dispatchEvent(new MessageEvent(kind, { data: JSON.stringify(value) }));
    }
  };
  const agent = (type, message, data, extra) => emit('agent', event(type, message, data, extra));
  const request = (id, method, params) => emit('rpc', { jsonrpc: '2.0', id, method, params });
  class FixtureEventSource extends EventTarget {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 2;
    readyState = 0;
    constructor(url, options) {
      super();
      const parsed = new URL(url, location.href);
      if (parsed.origin !== location.origin || parsed.pathname !== '/api/stream') {
        return new NativeEventSource(url, options);
      }
      this.url = parsed.href;
      streams.add(this);
      setTimeout(() => {
        if (!streams.has(this)) { return; }
        this.readyState = 1;
        this.onopen?.(new Event('open'));
        this.dispatchEvent(new Event('open'));
        this.dispatchEvent(new MessageEvent('snapshot', { data: JSON.stringify({ events: history.slice(-128), total: history.length }) }));
        this.dispatchEvent(new MessageEvent('agent', { data: JSON.stringify(event('settings', '', { showThinking: true, showToolOutput: 'shown' })) }));
      }, 50);
    }
    close() { this.readyState = 2; streams.delete(this); }
  }
  const NativeEventSource = window.EventSource;
  window.EventSource = FixtureEventSource;
  const nativeFetch = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
    if (url.origin !== location.origin || url.pathname !== '/api/rpc') {
      return nativeFetch(input, init);
    }
    const body = JSON.parse(init?.body ?? await input.text());
    calls.push(body);
    const { method, params = {} } = body;
    let result;
    switch (method) {
      case 'web.config': result = { windowBlocks: 64 }; break;
      case 'sessions.list': result = [{ id: sessionId, title: 'Browser fixture', modifiedAt: 1, sizeBytes: 1024, project: '/fixture' }]; break;
      case 'sessions.restore': case 'sessions.get': case 'slash.complete': result = []; break;
      case 'session.todos': result = todos; break;
      case 'sessions.currentProject': result = { project: '/fixture' }; break;
      case 'sessions.create': result = { id: sessionId }; break;
      case 'session.gitBranch': result = { branch: 'fixture' }; break;
      case 'session.queuedCount': result = { count: 0 }; break;
      case 'session.queuedList': result = { prompts: [] }; break;
      case 'mode.get': result = { mode: 'plan' }; break;
      case 'session.metadata': result = { mode: 'plan', permissionMode: 'ask', model: 'fixture-model', provider: 'fixture', modelCandidates: ['fixture-model'], providerCandidates: ['fixture'], mcpServers: [{ name: 'Fixture MCP', state: 'ENABLED', transport: 'http', url: 'https://mcp.example.test/api' }], cwd: '/fixture' }; break;
      case 'mcp.status': result = [{ name: 'Fixture MCP', state: 'ENABLED', transport: 'http', url: 'https://mcp.example.test/api' }, { name: 'Disabled MCP', state: 'DISABLED', transport: 'stdio' }]; break;
      case 'session.loadEarlier': {
        const end = Math.max(0, history.length - params.excludeFromTail);
        result = { events: history.slice(0, end), loaded: history.length };
        break;
      }
      case 'session.resolve': resolutions.push(params); result = { resolved: true }; break;
      case 'session.cancel': case 'session.cancelAll': result = { cancelled: true }; break;
      case 'sessions.delete': result = { deleted: true }; break;
      case 'session.slash':
        if (params.text === '/help') { setTimeout(() => agent('transcript', 'Fixture help: /model /theme /help'), 10); }
        if (params.text === '/model') { setTimeout(() => request(900, 'session.ask', { prompt: 'Select model', options: ['fixture-model', 'other-model'] }), 10); }
        if (params.text.startsWith('/theme ')) { setTimeout(() => agent('web-command', '', { command: 'theme', arg: params.text.split(' ')[1] }), 10); }
        result = { dispatched: true };
        break;
      default:
        unknown.push(method ?? 'response-envelope');
        return Response.json({ jsonrpc: '2.0', id: body.id, error: { code: -32601, message: `Unimplemented fixture RPC: ${method}` } });
    }
    return Response.json({ jsonrpc: '2.0', id: body.id, result });
  };
  window.__browserFixture = {
    calls, resolutions, unknown, agent, request,
    edges() {
      agent('user', 'Inspect deterministic tool states.');
      agent('status', '', { busy: true });
      agent('thinking-delta', 'Fixture reasoning marker.');
      agent('text-delta', 'Fixture streamed marker.\n\n| Key | Value |\n| --- | --- |\n| Fixture | Rendered |\n\n```javascript\nconst fixture = true;\n```');
      for (const [ordinal, name, output, success, pending] of [
        [1, 'collect', 'Waiting for a child', false, true],
        [2, 'read', 'Fixture read error', false, false],
        [3, 'webfetch', 'fixture line\n'.repeat(9000) + 'HUGE_OUTPUT_END', true, false],
      ]) {
        agent('tool-started', name, { ordinal, args: JSON.stringify({ path: '/fixture/example.txt', url: 'https://example.invalid' }) });
        agent('tool-finished', name, { ordinal, output, success, pending });
      }
      agent('tool-started', 'grep', { ordinal: 4, args: '{"pattern":"fixture","path":"/fixture"}' });
      agent('turn-cancelled', 'Fixture interrupted');
      agent('subagent-started', 'Inspect a sample', { subagentName: 'Fixture Scout' }, { subagentId: 'fixture-child' });
      agent('subagent-completed', 'Fixture child report', { subagentName: 'Fixture Scout', success: true, model: 'fixture-model' }, { subagentId: 'fixture-child' });
      todos = [{ content: 'Fixture active todo', status: 'in_progress' }, { content: 'Fixture completed todo', status: 'completed' }, { content: 'Fixture pending todo', status: 'pending' }, { content: 'Fixture cancelled todo', status: 'cancelled' }];
      agent('todo-updated', '', { todos });
      agent('status', '', { busy: false });
    },
  };
}
