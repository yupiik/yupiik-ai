#!/usr/bin/env node
// Node >=22 with native WebSocket; no npm install. Run against the rebuilt Java web app:
//   node web/src/test/browser/assistant-ui.mjs [http://127.0.0.1:16060] [--real|--fixture|--all]
// Default: --real (one short provider turn). --fixture never sends API traffic to the backend.
// --self-test checks Chrome/CDP and fixture transport only, not the application.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { installFixture } from './assistant-ui-fixture.mjs';

const args = process.argv.slice(2);
const base = new URL(args.find(a => !a.startsWith('--')) ?? 'http://127.0.0.1:16060');
const mode = args.includes('--self-test') ? 'self-test' : args.includes('--all') ? 'all' : args.includes('--fixture') ? 'fixture' : 'real';
for (const arg of args.filter(a => a.startsWith('--'))) {
  assert(['--real', '--fixture', '--all', '--self-test'].includes(arg), `Unknown option: ${arg}`);
}
assert.equal(typeof WebSocket, 'function', 'A Node version with native WebSocket is required');
const output = resolve(dirname(fileURLToPath(import.meta.url)), '../../../target/browser-validation', `${new Date().toISOString().replaceAll(':', '-')}-${mode}`);
const report = { base: base.href, mode, started: new Date().toISOString(), steps: [], exceptions: [], consoleErrors: [], networkFailures: [], httpErrors: [], streamCounts: {}, gaps: [] };
if (process.env.YCA_TEST_ARTIFACT) {
  report.artifact = { path: process.env.YCA_TEST_ARTIFACT, sha256: createHash('sha256').update(await readFile(process.env.YCA_TEST_ARTIFACT)).digest('hex') };
}
let chrome, profile, cdp, sessionId, cleanupPromise;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const boundedFetch = (url, init = {}) => fetch(url, { ...init, signal: AbortSignal.timeout(10000) });
async function rpc(method, params = {}) {
  const response = await boundedFetch(new URL('/api/rpc', base), { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
  assert(response.ok, `${method}: HTTP ${response.status}`);
  const envelope = await response.json();
  assert(!envelope.error, `${method}: ${JSON.stringify(envelope.error)}`);
  return envelope.result;
}
async function waitFor(label, action, timeout = 15000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await action();
    if (value) { return value; }
    await sleep(100);
  }
  throw new Error(`Timed out (${timeout}ms): ${label}`);
}
class CDP {
  next = 0;
  pending = new Map();
  listeners = new Map();
  constructor(ws) {
    this.ws = ws;
    ws.addEventListener('message', ({ data }) => {
      const message = JSON.parse(data);
      if (message.id) {
        const pending = this.pending.get(message.id);
        if (pending) {
          clearTimeout(pending.timer);
          this.pending.delete(message.id);
          if (message.error) { pending.reject(new Error(JSON.stringify(message.error))); }
          else { pending.resolve(message.result); }
        }
      } else {
        for (const listener of this.listeners.get(message.method) ?? []) { listener(message.params); }
      }
    });
    ws.addEventListener('close', () => this.rejectPending('CDP connection closed'));
  }
  rejectPending(reason) {
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(new Error(reason)); }
    this.pending.clear();
  }
  on(name, callback) { this.listeners.set(name, [...(this.listeners.get(name) ?? []), callback]); }
  send(method, params = {}) {
    return new Promise((resolvePromise, reject) => {
      const id = ++this.next;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 15000);
      this.pending.set(id, { resolve: resolvePromise, reject, timer });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  close() { this.rejectPending('Runner finished'); this.ws.close(); }
}
const evaluate = async expression => {
  const result = await cdp.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  assert(!result.exceptionDetails, `Browser evaluation failed: ${JSON.stringify(result.exceptionDetails)}`);
  return result.result.value;
};
const ready = () => waitFor('restored conversation and writable composer', () => evaluate(`!!document.querySelector('textarea[placeholder^="Ask the agent"]:not([readonly])') && !document.body.innerText.includes('restoring session history')`));
const buttonExpression = (text, scope = 'document') => `[...${scope}.querySelectorAll('button')].find(b => b.textContent.trim() === ${JSON.stringify(text)} && !b.disabled)`;
async function click(text, scope) {
  await waitFor(`enabled button ${text}`, () => evaluate(`!!(${buttonExpression(text, scope)})`));
  await evaluate(`${buttonExpression(text, scope)}.click()`);
}
async function input(text, selector = 'textarea[placeholder^="Ask the agent"]') {
  await evaluate(`(() => { const el = document.querySelector(${JSON.stringify(selector)}); if (!el) { throw new Error('Input not found'); } el.focus(); el.select(); })()`);
  await cdp.send('Input.insertText', { text });
  await waitFor('native input reflected in DOM', () => evaluate(`document.querySelector(${JSON.stringify(selector)}).value === ${JSON.stringify(text)}`));
}
async function send(text) { await input(text); await click('Send'); }
async function screenshot(name) {
  const { data } = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  await writeFile(join(output, `${name}.png`), Buffer.from(data, 'base64'));
}
async function step(name, action) {
  const item = { name, started: new Date().toISOString() };
  report.steps.push(item);
  try { await action(); item.passed = true; await screenshot(name); }
  catch (error) { item.passed = false; item.error = error.stack; throw error; }
}
async function navigate(path) {
  const result = await cdp.send('Page.navigate', { url: new URL(path, base).href });
  assert(!result.errorText, `Navigation failed: ${result.errorText}`);
  await waitFor('app document', () => evaluate(`document.readyState === 'complete' && !!document.querySelector('#root')?.children.length`));
}
async function startChrome() {
  profile = await mkdtemp(join(tmpdir(), 'yca-browser-'));
  let stderr = '';
  chrome = spawn('/usr/bin/google-chrome', [
    '--headless=new', '--no-sandbox', '--disable-dev-shm-usage', '--disable-background-networking',
    '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0',
    `--user-data-dir=${profile}`, '--window-size=1440,1000', 'about:blank',
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  chrome.stderr.on('data', data => { stderr = (stderr + data).slice(-16000); });
  chrome.on('error', error => { report.chromeError = error.message; });
  const port = await waitFor('Chrome debugging port', async () => {
    if (chrome.exitCode !== null || report.chromeError) { throw new Error(`Chrome failed: ${report.chromeError ?? stderr}`); }
    try { return Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); }
    catch { return false; }
  });
  const targets = await (await boundedFetch(`http://127.0.0.1:${port}/json/list`)).json();
  const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolvePromise, reject) => { ws.addEventListener('open', resolvePromise, { once: true }); ws.addEventListener('error', reject, { once: true }); });
  cdp = new CDP(ws);
  cdp.on('Runtime.exceptionThrown', details => report.exceptions.push(details));
  cdp.on('Runtime.consoleAPICalled', details => { if (details.type === 'error') { report.consoleErrors.push(details); } });
  const requests = new Map();
  cdp.on('Network.requestWillBeSent', ({ requestId, request }) => { requests.set(requestId, request.url); });
  cdp.on('Network.loadingFinished', ({ requestId }) => { requests.delete(requestId); });
  cdp.on('Network.loadingFailed', details => { report.networkFailures.push({ ...details, url: requests.get(details.requestId) }); requests.delete(details.requestId); });
  cdp.on('Network.responseReceived', ({ response }) => { if (response.status >= 400) { report.httpErrors.push({ url: response.url, status: response.status }); } });
  cdp.on('Network.eventSourceMessageReceived', ({ eventName, data }) => {
    if (eventName !== 'agent') { return; }
    try { const { type } = JSON.parse(data); report.streamCounts[type] = (report.streamCounts[type] ?? 0) + 1; }
    catch { report.gaps.push('Malformed agent SSE frame'); }
  });
  await Promise.all(['Page.enable', 'Runtime.enable', 'Network.enable'].map(method => cdp.send(method)));
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
}
async function commonChecks() {
  await step('commands-help', async () => {
    await send('/help');
    await waitFor('help content', () => evaluate(`document.querySelector('[role="dialog"][aria-label="Slash commands"]')?.textContent.includes('/model')`));
    await evaluate(`document.querySelector('[role="dialog"][aria-label="Slash commands"] button[title="Close"]').click()`);
  });
  await step('model-picker-cancel', async () => {
    await send('/model');
    await waitFor('model dialog', () => evaluate(`!!document.querySelector('[role="dialog"]')`));
    await screenshot('model-picker-open');
    await click('Cancel', `document.querySelector('[role="dialog"]')`);
    await waitFor('picker dismissed', () => evaluate(`!document.querySelector('[role="dialog"]')`));
  });
  await step('theme-mobile', async () => {
    await send('/theme light');
    await waitFor('light theme', () => evaluate(`document.documentElement.dataset.theme === 'light'`));
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await waitFor('mobile composer', () => evaluate(`document.querySelector('textarea[placeholder^="Ask the agent"]').getBoundingClientRect().width > 50`));
    const composerBounds = await evaluate(`(() => { const rect = document.querySelector('textarea[placeholder^="Ask the agent"]').getBoundingClientRect(); return { left: rect.left, right: rect.right, viewport: innerWidth }; })()`);
    assert(composerBounds.left >= 0 && composerBounds.right <= composerBounds.viewport + 1, 'Mobile composer must remain inside the viewport');
    await screenshot('mobile-light');
    await send('/theme dark');
    await waitFor('dark theme', () => evaluate(`document.documentElement.dataset.theme === 'dark'`));
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  });
}
async function realChecks() {
  // Track native sources without replacing their transport or messages. This lets the
  // reconnect check close one live connection and exercise the app's error/retry path.
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: `(() => {
    window.__nativeTestSources = [];
    window.EventSource = new Proxy(window.EventSource, { construct(Target, args) {
      const source = Reflect.construct(Target, args);
      window.__nativeTestSources.push(source);
      return source;
    } });
  })();` });
  await step('real-sessions', async () => {
    await navigate('/');
    await waitFor('sessions search loaded', () => evaluate(`!!document.querySelector('input[aria-label="Search sessions"]') && !document.body.innerText.includes('Sessions request failed') && !document.body.innerText.includes('Loading sessions')`));
    assert(Array.isArray(await rpc('sessions.list', { query: '', project: '' })));
    ({ id: sessionId } = await rpc('sessions.create'));
    assert(sessionId, 'sessions.create must return an id');
    report.createdSession = sessionId;
    await navigate(`/session/${encodeURIComponent(sessionId)}`);
    await ready();
    report.metadata = await rpc('session.metadata', { id: sessionId });
  });
  await step('real-stream-tool-answer', async () => {
    await send('Use the read tool once to list the current directory (path "."). Do not write or modify anything. Then name one entry in one short sentence.');
    await waitFor('real streamed text', () => (report.streamCounts['text-delta'] ?? 0) > 0, 120000);
    await screenshot('real-streaming');
    await waitFor('real tool completion and completed turn', () => (report.streamCounts['tool-finished'] ?? 0) > 0 && (report.streamCounts['turn-done'] ?? 0) > 0, 120000);
    await waitFor('rendered real answer and tool', () => evaluate(`!!document.querySelector('[data-yca-kind="text"]') && !!document.querySelector('[data-slot="tool-call"]')`));
    if (!report.streamCounts['thinking-delta']) { report.gaps.push('Real provider emitted no reasoning delta; deterministic fixture covers reasoning UI.'); }
    report.realToolStatuses = await evaluate(`[...document.querySelectorAll('.yca-tool-status')].map(el => el.textContent)`);
    assert(report.realToolStatuses.includes('completed'), 'Real read tool must complete successfully');
  });
  await commonChecks();
  await step('real-navigation-reload', async () => {
    await navigate('/');
    await navigate(`/session/${encodeURIComponent(sessionId)}`);
    await ready();
    await cdp.send('Page.reload', { ignoreCache: true });
    await ready();
    await waitFor('persisted answer restored', () => evaluate(`!!document.querySelector('[data-yca-kind="text"]') && !!document.querySelector('[data-slot="tool-call"]')`));
  });
  await step('real-pending-picker-reload-navigation', async () => {
    await send('/model');
    await waitFor('pending picker', () => evaluate(`!!document.querySelector('[role="dialog"]')`));
    await cdp.send('Page.reload', { ignoreCache: true });
    await ready();
    await waitFor('pending request replay after reload', () => evaluate(`!!document.querySelector('[role="dialog"] [data-slot="option-list"]')`));
    await navigate('/');
    await navigate(`/session/${encodeURIComponent(sessionId)}`);
    await ready();
    await waitFor('pending request replay after navigation', () => evaluate(`!!document.querySelector('[role="dialog"] [data-slot="option-list"]')`));
    await screenshot('real-pending-picker-replayed');
    await click('Cancel', `document.querySelector('[role="dialog"]')`);
    await waitFor('replayed picker resolved', () => evaluate(`!document.querySelector('[role="dialog"]')`));
  });
  await step('real-sse-reconnect', async () => {
    await evaluate(`(() => { window.__toolBeforeReconnect = document.querySelector('[data-slot="tool-call"]'); })()`);
    await evaluate(`(() => { const source = window.__nativeTestSources.at(-1); source.close(); source.dispatchEvent(new Event('error')); })()`);
    await waitFor('disconnection indicator', () => evaluate(`document.body.innerText.includes('reconnecting') || document.body.innerText.includes('Disconnected')`));
    await waitFor('SSE reconnected', () => evaluate(`!!document.querySelector('[title="connected"]') && !document.body.innerText.includes('restoring session history')`));
    report.reconnectMethod = 'Closed the live native EventSource and dispatched an error; the application retry opened a new real backend connection. No SSE payloads were mocked.';
    assert(await evaluate(`window.__toolBeforeReconnect === document.querySelector('[data-slot="tool-call"]')`), 'Reconnect must preserve the existing tool node');
  });
  await step('real-supporting-pages', async () => {
    await navigate('/mcp');
    await waitFor('MCP status page', () => evaluate(`!!document.querySelector('[data-slot="mcp-server-panel"]') || document.body.innerText.includes('No MCP servers.')`));
    await screenshot('real-mcp-status');
    await navigate('/memory');
    await waitFor('both full raw memory views', () => evaluate(`!!document.querySelector('pre[aria-label="Global raw memory"]') && !!document.querySelector('pre[aria-label="Project raw memory"]')`));
    await navigate(`/session/${encodeURIComponent(sessionId)}`);
    await ready();
  });
  report.gaps.push('Short real turn may not overflow the viewport; Follow/history edge checks run in fixture mode.');
}
async function fixtureChecks() {
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: `(${installFixture.toString()})();` });
  await step('fixture-history-follow', async () => {
    await navigate('/session/browser-fixture');
    await ready();
    await waitFor('fixture transport ready', () => evaluate(`!!window.__browserFixture`));
    await evaluate(`window.__browserFixture.edges()`);
    await waitFor('rendered fixture text and reasoning disclosure', () => evaluate(`document.body.innerText.includes('Fixture streamed marker.') && !!document.querySelector('[data-slot="reasoning-trigger"]')`));
    assert(await evaluate(`document.querySelector('[data-slot="reasoning-trigger"]').getAttribute('aria-expanded') === 'false'`), 'Thoughts default to collapsed, including while streaming');
    const thoughts = async (label) => {
      await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.startsWith('Thoughts:')).click()`);
      await click(label, `document.querySelector('[role="menu"][aria-label="Thoughts visibility"]')`);
    };
    await thoughts('Expanded');
    await waitFor('reasoning expands without losing content', () => evaluate(`document.body.innerText.includes('Fixture reasoning marker.')`));
    await thoughts('Hidden');
    assert(await evaluate(`!document.querySelector('[data-slot="reasoning-root"]')`), 'Hidden thoughts remove the reasoning card');
    await thoughts('Collapsed');
    assert(await evaluate(`document.querySelector('[data-slot="reasoning-trigger"]').getAttribute('aria-expanded') === 'false'`), 'Collapsed thoughts restore the disclosure without opening it');
    await thoughts('Expanded');
    const follow = `[...document.querySelectorAll('button')].find(b => b.title.startsWith('Following output'))`;
    await evaluate(`${follow}.click()`);
    await waitFor('Follow paused', () => evaluate(`!!document.querySelector('button[title^="Paused"]')`));
    await evaluate(`document.querySelector('button[title^="Paused"]').click()`);
    await waitFor('Follow resumed', () => evaluate(`!!document.querySelector('button[title^="Following output"]')`));
    // A real wheel input, not a synthetic scroll event, must also pause Follow.
    await sleep(300);
    const point = await evaluate(`(() => {
      let node = [...document.querySelectorAll('[data-yca-kind]')].at(-1);
      while (node && !(node.scrollHeight > node.clientHeight + 100 && /auto|scroll/.test(getComputedStyle(node).overflowY))) { node = node.parentElement; }
      if (!node) { throw new Error('Scrollable transcript not found'); }
      const rect = node.getBoundingClientRect();
      return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
    })()`);
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseWheel', ...point, deltaX: 0, deltaY: -500 });
    await waitFor('manual scroll pauses Follow', () => evaluate(`!!document.querySelector('button[title^="Paused"]')`));
    await screenshot('fixture-manual-pause');
    await evaluate(`document.querySelector('button[title^="Paused"]').click()`);
    await sleep(300);
    await evaluate(`(() => {
      const last = [...document.querySelectorAll('[data-yca-message-id]')].at(-1);
      let viewport = last;
      while (viewport && !(viewport.scrollHeight > viewport.clientHeight + 100 && /auto|scroll/.test(getComputedStyle(viewport).overflowY))) { viewport = viewport.parentElement; }
      const top = viewport.getBoundingClientRect().top;
      const node = [...viewport.querySelectorAll('[data-yca-message-id]')].find(el => el.getBoundingClientRect().bottom > top);
      window.__historyAnchorProbe = { node, top: node.getBoundingClientRect().top };
    })()`);
    await waitFor('history load control', () => evaluate(`!![...document.querySelectorAll('button')].find(b => b.textContent.includes('Load earlier'))`));
    for (let i = 0; i < 4; i++) {
      if (await evaluate(`window.__browserFixture.calls.some(c => c.method === 'session.loadEarlier')`)) { break; }
      await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('Load earlier'))?.click()`);
      await sleep(150);
    }
    await waitFor('server history fetched', () => evaluate(`window.__browserFixture.calls.some(c => c.method === 'session.loadEarlier')`));
    await sleep(350);
    const anchorDelta = await evaluate(`Math.abs(window.__historyAnchorProbe.node.getBoundingClientRect().top - window.__historyAnchorProbe.top)`);
    assert(anchorDelta < 2, `History prepend moved the reading anchor by ${anchorDelta}px`);
    report.historyAnchorDeltaPx = anchorDelta;
    // Enlarge the local render window until the first of >1024 history blocks is reachable.
    for (let i = 0; i < 40; i++) {
      if (await evaluate(`document.body.innerText.includes('History prompt 0')`)) { break; }
      await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('Load earlier'))?.click()`);
      await sleep(100);
    }
    assert(await evaluate(`document.body.innerText.includes('History prompt 0')`), 'Earliest history must remain accessible');
  });
  await step('fixture-tool-edges', async () => {
    for (const state of ['pending', 'failed', 'interrupted', 'completed']) {
      assert(await evaluate(`!!document.querySelector('.yca-tool-${state}')`), `Missing ${state} tool card`);
    }
    assert(await evaluate(`document.body.innerText.includes('Fixture Scout')`), 'Subagent card rendered');
    assert(await evaluate(`!!document.querySelector('[data-slot="todo-list"]') && document.body.innerText.includes('Fixture active todo')`), 'Live todo list rendered');
    await evaluate(`document.querySelector('button[title="Collapse todos"]').click()`);
    assert(await evaluate(`(() => {
      const summary = document.querySelector('[data-slot="todo-collapsed-summary"]');
      return summary?.parentElement.tagName === 'BUTTON' && summary.textContent.includes('Fixture active todo') && !document.querySelector('[data-slot="todo-list"]');
    })()`), 'Collapsed todo summary stays inline on the disclosure row');
    await screenshot('fixture-todos-collapsed');
    await evaluate(`document.querySelector('button[title="Expand todos"]').click()`);
    await evaluate(`document.querySelector('button[title="Expand servers"]').click()`);
    assert(await evaluate(`!!document.querySelector('[data-slot="mcp-server-panel"]') && document.body.innerText.includes('Fixture MCP')`), 'MCP sidebar Element rendered');
    await evaluate(`document.querySelector('[data-slot="mcp-server-panel"] button[aria-expanded]').click()`);
    assert(await evaluate(`document.body.innerText.includes('https://mcp.example.test/api')`), 'Sidebar server exposes connection details');
    assert(await evaluate(`!!document.querySelector('[data-yca-kind="text"] table') && !!document.querySelector('[data-yca-kind="text"] pre code')`), 'Markdown table and fenced code rendered');
    await evaluate(`(() => { const b = document.querySelector('.yca-tool-completed [data-slot="tool-call-trigger"]'); if (b.getAttribute('aria-expanded') !== 'true') { b.click(); } })()`);
    for (let i = 0; i < 50; i++) {
      if (await evaluate(`document.body.innerText.includes('HUGE_OUTPUT_END')`)) { break; }
      await evaluate(`document.querySelector('button[aria-label="Show more Fetched content"]')?.click()`);
      await sleep(100);
    }
    assert(await evaluate(`document.body.innerText.includes('HUGE_OUTPUT_END')`), 'Last byte of huge tool output reachable');
  });
  await step('fixture-questions-approval', async () => {
    await evaluate(`window.__browserFixture.request(701, 'session.question', { questions: [{id:'color', prompt:'Choose a color', options:['Amber','Teal'], multiple:false}] })`);
    await waitFor('question dialog', () => evaluate(`!!document.querySelector('[role="dialog"]')`));
    await screenshot('fixture-question-open');
    await evaluate(`[...document.querySelectorAll('[role="dialog"] button')].find(b => b.textContent.trim() === 'Teal').click()`);
    await click('Submit', `document.querySelector('[role="dialog"]')`);
    await waitFor('question resolved', () => evaluate(`!document.querySelector('[role="dialog"]')`));
    await evaluate(`window.__browserFixture.request(702, 'session.approval', {title:'Read sample', tool:'read', args:{path:'/fixture/sample'}})`);
    await waitFor('approval dialog', () => evaluate(`document.querySelector('[role="dialog"]')?.textContent.includes('Tool approval')`));
    await screenshot('fixture-approval-open');
    await click('Allow once', `document.querySelector('[role="dialog"]')`);
    await waitFor('approval resolved', () => evaluate(`!document.querySelector('[role="dialog"]')`));
    await evaluate(`window.__browserFixture.request(703, 'session.ask', { questions: [{id:'scope', prompt:'Feedback scope', options:['Local','All'], multiple:false}] })`);
    await waitFor('feedback question', () => evaluate(`!!document.querySelector('[role="dialog"]')`));
    await click('Local', `document.querySelector('[role="dialog"]')`);
    await click('Submit', `document.querySelector('[role="dialog"]')`);
    await waitFor('feedback resolved', () => evaluate(`!document.querySelector('[role="dialog"]')`));
    assert.deepEqual(await evaluate(`window.__browserFixture.resolutions`), [
      { id: 'browser-fixture', requestId: '701', result: 'Teal' },
      { id: 'browser-fixture', requestId: '702', result: 'allow_once' },
      { id: 'browser-fixture', requestId: '703', result: { scope: 'Local' } },
    ]);
  });
  await commonChecks();
  report.fixtureCalls = await evaluate(`window.__browserFixture.calls`);
  report.fixtureResolutions = await evaluate(`window.__browserFixture.resolutions`);
  assert.deepEqual(report.fixtureResolutions.at(-1), { id: 'browser-fixture', requestId: '900', result: null }, 'Model picker cancellation must resolve null');
  assert.deepEqual(await evaluate(`window.__browserFixture.unknown`), [], 'All fixture API calls must be explicitly implemented');
  await step('fixture-mcp-layout-details', async () => {
    await navigate('/mcp');
    await waitFor('MCP list loaded', () => evaluate(`!!document.querySelector('[data-slot="mcp-server-panel"] button[aria-expanded]')`));
    const widthRatio = await evaluate(`(() => { const panel = document.querySelector('[data-slot="mcp-server-panel"]'); return panel.getBoundingClientRect().width / panel.parentElement.getBoundingClientRect().width; })()`);
    assert(widthRatio > 0.95, `MCP list should fill its content area (ratio ${widthRatio})`);
    await evaluate(`document.querySelector('[data-slot="mcp-server-panel"] button[aria-expanded]').click()`);
    assert(await evaluate(`document.body.innerText.includes('https://mcp.example.test/api')`), 'Expanded server displays its endpoint');
    assert(await evaluate(`(() => { const rows = [...document.querySelectorAll('[data-slot="mcp-status-icon"]')]; return getComputedStyle(rows[0]).color !== getComputedStyle(rows[1]).color; })()`), 'Enabled and disabled icons have distinct state colors');
    await screenshot('fixture-mcp-full-width');
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    assert(await evaluate(`document.documentElement.scrollWidth <= window.innerWidth`), 'Expanded MCP details fit the mobile viewport');
    await screenshot('fixture-mcp-mobile');
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
    await navigate('/session/browser-fixture');
    await ready();
  });
  assert.deepEqual(await evaluate(`window.__browserFixture.unknown`), [], 'All fixture API calls must be explicitly implemented');
  await step('fixture-reload', async () => {
    await cdp.send('Page.reload', { ignoreCache: true });
    await ready();
    assert(await evaluate(`document.documentElement.dataset.theme === 'dark'`), 'Theme survives reload');
    await waitFor('history snapshot after reload', () => evaluate(`document.body.innerText.includes('History answer 1099')`));
  });
}
async function selfTest() {
  await step('harness-cdp-input-fixture', async () => {
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: `(${installFixture.toString()})();` });
    await cdp.send('Fetch.enable', { patterns: [{ urlPattern: 'http://browser-harness.invalid/*', resourceType: 'Document' }] });
    cdp.on('Fetch.requestPaused', ({ requestId }) => {
      void cdp.send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'text/html' }], body: Buffer.from('<link rel="icon" href="data:,"><textarea></textarea>').toString('base64') });
    });
    await cdp.send('Page.navigate', { url: 'http://browser-harness.invalid/' });
    await waitFor('self-test document', () => evaluate(`!!document.querySelector('textarea') && !!window.__browserFixture`));
    await input('fabricated input', 'textarea');
    assert.deepEqual(await evaluate(`fetch('/api/rpc', {body: JSON.stringify({id:1,method:'sessions.create'})}).then(r=>r.json()).then(r=>r.result)`), { id: 'browser-fixture' });
    assert.deepEqual(await evaluate(`new Promise(resolve => {
      const source = new EventSource('/api/stream?session=browser-fixture');
      source.addEventListener('snapshot', event => { source.close(); const value = JSON.parse(event.data); resolve({ total: value.total, tail: value.events.length }); });
    })`), { total: 2200, tail: 128 });
  });
  report.gaps.push('Self-test only: no Java app, production assets, or application behavior validated.');
}
async function cleanup() {
  if (cleanupPromise) { return cleanupPromise; }
  cleanupPromise = (async () => {
    if (sessionId) {
      for (const method of ['session.cancelAll', 'sessions.delete']) {
        try { await rpc(method, { id: sessionId }); }
        catch (error) { report.cleanupErrors ??= []; report.cleanupErrors.push(`${method}: ${error.message}`); }
      }
    }
    cdp?.close();
    if (chrome && chrome.exitCode === null) {
      chrome.kill('SIGTERM');
      await Promise.race([new Promise(r => chrome.once('exit', r)), sleep(2000)]);
      if (chrome.exitCode === null) { chrome.kill('SIGKILL'); await sleep(200); }
    }
    if (profile) {
      try { await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 }); }
      catch (error) { report.cleanupErrors ??= []; report.cleanupErrors.push(`Remove Chrome profile: ${error.message}`); }
    }
    if (report.cleanupErrors?.length) { report.passed = false; }
    report.finished = new Date().toISOString();
    await mkdir(output, { recursive: true });
    await writeFile(join(output, 'report.json'), JSON.stringify(report, null, 2));
  })();
  return cleanupPromise;
}
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, () => { report.failure = `Interrupted by ${signal}`; void cleanup().finally(() => process.exit(130)); });
}
// Hard wall includes hung provider, debugging connection and all teardown work.
const watchdog = setTimeout(() => { report.failure = 'Global 360s deadline exceeded'; void cleanup().finally(() => process.exit(1)); }, 360000);
try {
  await mkdir(output, { recursive: true });
  await startChrome();
  if (mode === 'self-test') { await selfTest(); }
  if (mode === 'real' || mode === 'all') { await realChecks(); }
  if (mode === 'fixture' || mode === 'all') { await fixtureChecks(); }
  assert.equal(report.exceptions.length, 0, 'Uncaught browser exceptions (see report)');
  assert.equal(report.consoleErrors.length, 0, 'Browser console errors (see report)');
  assert.equal(report.httpErrors.length, 0, 'HTTP errors (see report)');
  assert.equal(report.networkFailures.filter(f => !f.canceled && f.errorText !== 'net::ERR_ABORTED').length, 0, 'Unexpected network failures (see report)');
  report.passed = true;
} catch (error) {
  report.passed = false;
  report.failure = error.stack;
  if (cdp) { try { await screenshot('failure'); report.body = await evaluate('document.body.innerText.slice(-16000)'); } catch { /* Browser may have exited. */ } }
  console.error(error.stack);
  process.exitCode = 1;
} finally {
  try { await cleanup(); }
  catch (error) { console.error('Cleanup failed:', error); process.exitCode = 1; }
  clearTimeout(watchdog);
  if (report.cleanupErrors?.length) { process.exitCode = 1; }
  console.log(`${report.passed && !process.exitCode ? 'PASS' : 'FAIL'} ${mode}: ${output}/report.json`);
}
