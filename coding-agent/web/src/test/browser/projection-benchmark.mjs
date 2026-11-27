#!/usr/bin/env node
// Run from any directory: node web/src/test/browser/projection-benchmark.mjs
// In-memory transpilation only; requires the frontend's already-installed TypeScript dev dependency.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { cpus } from 'node:os';
import { dirname, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..');
const frontend = resolve(root, 'web/src/main/frontend');
const require = createRequire(resolve(frontend, 'package.json'));
const ts = require('typescript');
const sourcePath = 'web/src/main/frontend/src/api/eventLog.ts';
const baselineSource = execFileSync('git', ['show', `HEAD:${sourcePath}`], { cwd: root, encoding: 'utf8' });
const currentSource = await readFile(resolve(root, sourcePath), 'utf8');
const streamSource = await readFile(resolve(frontend, 'src/api/useSessionStream.ts'), 'utf8');
const projectorSource = await readFile(resolve(frontend, 'src/assistant/runtime/eventsToMessages.ts'), 'utf8');
const hash = source => createHash('sha256').update(source).digest('hex');

// Extract the actual pure helper, not a hand-maintained approximation, without loading React.
const streamAst = ts.createSourceFile('useSessionStream.ts', streamSource, ts.ScriptTarget.Latest, true);
const helper = streamAst.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === 'eventKey');
assert(helper?.body, 'Expected a standalone eventKey function');
const helperSource = helper.getText(streamAst);
function moduleUrl(source, fileName, imports = {}) {
  const result = ts.transpileModule(source, {
    fileName,
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
    reportDiagnostics: true,
  });
  assert.equal((result.diagnostics ?? []).filter(d => d.category === ts.DiagnosticCategory.Error).length, 0);
  // Rewrite runtime import specifiers using AST spans; type-only imports have already disappeared.
  const ast = ts.createSourceFile(fileName, result.outputText, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  let output = result.outputText;
  const replacements = [];
  for (const node of ast.statements) {
    if (ts.isImportDeclaration(node)) {
      const specifier = node.moduleSpecifier;
      assert(Object.hasOwn(imports, specifier.text), `Unexpected runtime import: ${specifier.text}`);
      replacements.push([specifier.getStart(ast), specifier.end, JSON.stringify(imports[specifier.text])]);
    }
  }
  for (const [start, end, replacement] of replacements.reverse()) {
    output = output.slice(0, start) + replacement + output.slice(end);
  }
  return `data:text/javascript;base64,${Buffer.from(output).toString('base64')}`;
}
const keyUrl = moduleUrl(helperSource, 'eventKey.ts');
const baselineUrl = moduleUrl(baselineSource, 'baseline-eventLog.ts');
const currentUrl = moduleUrl(currentSource, 'current-eventLog.ts', { './useSessionStream': keyUrl });
const projectorUrl = moduleUrl(projectorSource, 'eventsToMessages.ts', {
  '../../api/eventLog': currentUrl, '../../api/useSessionStream': keyUrl,
});
const { eventsToBlocks: baseline } = await import(baselineUrl);
const { eventsToBlocks: current } = await import(currentUrl);
const { createYcaMessageProjector } = await import(projectorUrl);
const event = (type, message = '', data = {}, extra = {}) => ({ type, sessionId: 'benchmark-session', message, data, ...extra });
function turn(index, identical = false) {
  const prompt = identical ? 'Repeat the dummy check.' : `Inspect dummy module ${index}.`;
  const answer = identical ? 'Dummy check complete.' : `Dummy module ${index} checked.\n\n\`\`\`js\nconst valid = true;\n\`\`\``;
  const ordinal = index + 1;
  const events = [
    event('user', prompt), event('thinking-delta', 'Checking the dummy input. '),
    event('thinking-delta', 'Considering a small example.'),
    event('tool-started', 'read', { ordinal, args: '{"path":"dummy.txt"}' }),
    event('tool-stream', 'read', { ordinal, fragment: 'dummy output' }),
    event('tool-finished', 'read', { ordinal, success: true, output: 'dummy output' }),
    event('text-delta', answer.slice(0, 12)), event('text-delta', answer.slice(12)),
    event('usage-updated', 'dummy-model', { outputTokens: 32 }), event('turn-done', answer),
  ];
  if (index % 10 === 0) {
    events.splice(6, 0,
      event('subagent-started', 'Check dummy fixture', { subagentName: 'Dummy helper' }, { subagentId: `dummy-${index}` }),
      event('subagent-completed', 'Dummy fixture checked.', { success: true, model: 'dummy-model' }, { subagentId: `dummy-${index}` }));
  }
  return events;
}
const stripId = blocks => blocks.map(({ id, ...block }) => block);
const blocksOf = (name, value) => name === 'projector' ? value.map(message => message.metadata.custom.ycaBlock) : value;
const factories = { baseline: () => baseline, current: () => current, projector: createYcaMessageProjector };
const samples = 3;
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
let sink = 0;
function measure(seed, snapshots) {
  const measurements = Object.fromEntries(Object.keys(factories).map(name => [name, []]));
  for (let trial = -1; trial < samples; trial++) {
    // Rotate order to reduce systematic warmup/GC ordering bias. No forced GC.
    const names = Object.keys(factories);
    for (const name of names.slice((trial + 1) % 3).concat(names.slice(0, (trial + 1) % 3))) {
      const project = factories[name]();
      project(seed); // Initial hydration is excluded for update scenarios.
      const elapsed = [];
      const input = trial < 0 ? snapshots.slice(0, 3) : snapshots;
      for (const events of input) {
        const start = performance.now();
        const value = project(events);
        elapsed.push(performance.now() - start);
        sink += value.length;
      }
      if (trial >= 0) {
        measurements[name].push(elapsed);
      }
    }
  }
  return Object.fromEntries(Object.entries(measurements).map(([name, trials]) => [name, {
    medianElapsedMs: median(trials.map(times => times.reduce((sum, time) => sum + time, 0))),
    medianPerUpdateMs: median(trials.flat()),
    trialElapsedMs: trials.map(times => times.reduce((sum, time) => sum + time, 0)),
  }]));
}
function validateAndCount(seed, snapshots) {
  const project = createYcaMessageProjector();
  let previous = project(seed);
  let unchangedArrayUpdates = 0;
  let reusedMessageRefs = 0;
  let eligibleUnchangedMessages = 0;
  let previousMessageCount = 0;
  let baselineReusedBlockRefs = 0;
  let currentReusedBlockRefs = 0;
  let oldBaseline = baseline(seed);
  let oldCurrent = current(seed);
  for (const events of snapshots) {
    const before = baseline(events);
    const after = current(events);
    const messages = project(events);
    const expected = stripId(before);
    assert.deepStrictEqual(stripId(after), expected, 'HEAD/current reducer block semantics differ');
    assert.deepStrictEqual(stripId(blocksOf('projector', messages)), expected, 'Projector block semantics differ');
    assert.equal(new Set(messages.map(message => message.id)).size, messages.length, 'Duplicate message IDs');
    const byId = new Map(previous.map(message => [message.id, message]));
    for (const message of messages) {
      const old = byId.get(message.id);
      if (old) {
        if (JSON.stringify(old.metadata.custom.ycaBlock) === JSON.stringify(message.metadata.custom.ycaBlock)) {
          eligibleUnchangedMessages++;
          assert.strictEqual(message, old, 'Unchanged message did not reuse its reference');
        }
        if (old === message) {
          reusedMessageRefs++;
        }
      }
    }
    const baselineRefs = new Set(oldBaseline);
    const currentRefs = new Set(oldCurrent);
    baselineReusedBlockRefs += before.filter(block => baselineRefs.has(block)).length;
    currentReusedBlockRefs += after.filter(block => currentRefs.has(block)).length;
    previousMessageCount += previous.length;
    if (messages === previous) {
      unchangedArrayUpdates++;
    }
    previous = messages;
    oldBaseline = before;
    oldCurrent = after;
  }
  return { equivalenceCheckedSnapshots: snapshots.length, previousMessageCount, eligibleUnchangedMessages,
    reusedMessageRefs, unchangedArrayUpdates, baselineReusedBlockRefs, currentReusedBlockRefs,
    finalMessages: previous.length };
}
const report = {
  generatedAt: new Date().toISOString(), node: process.version, typescript: ts.version,
  cpu: cpus()[0]?.model, head: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(),
  sourceSha256: { baseline: hash(baselineSource), current: hash(currentSource), projector: hash(projectorSource), eventKey: hash(helperSource) },
  methodology: {
    samples, warmup: 'Three untimed updates per method/scenario; hydration excluded from update timings.',
    elapsed: 'Median trial total and median individual synchronous call, milliseconds; rotated method order; no forced GC.',
    scope: 'Node pure computation only, not React rendering, browser rAF scheduling, network, or frame latency.',
    live: '200 successive rAF-sized batches of four text deltas; no sleeps; no log truncation.',
    prepend: '10 prepends of one completed identical-prompt/answer turn, preserving existing event object references.',
    validation: 'Every generated snapshot compared to HEAD blocks with only id stripped, outside timing. Reference counts from one separate pass.',
    caveat: 'Projector includes current reducer plus identity/reconciliation and message wrapping; reference reuse is not evidence of faster computation. Synthetic local timings include incidental GC and shared-machine noise.',
  }, results: [],
};
const output = resolve(root, 'web/target/browser-validation/projection-benchmark.json');
execFileSync('git', ['check-ignore', '-q', output], { cwd: root });
await mkdir(dirname(output), { recursive: true });
report.complete = false;
for (const turns of [100, 1000, 5000]) {
  const history = Array.from({ length: turns }, (_, index) => turn(index)).flat();
  const identicalHistory = Array.from({ length: turns }, (_, index) => turn(index, true)).flat();
  let live = [...history, event('user', 'Stream a dummy response.'), event('text-delta', 'Dummy live answer: ')];
  const liveSnapshots = [];
  for (let update = 0; update < 200; update++) {
    live = [...live, ...Array.from({ length: 4 }, () => event('text-delta', 'dummy token '))];
    liveSnapshots.push(live);
  }
  let prepended = identicalHistory;
  const prependSnapshots = [];
  for (let update = 0; update < 10; update++) {
    prepended = [...turn(turns + update, true), ...prepended];
    prependSnapshots.push(prepended);
  }
  const scenarios = [
    ['snapshot', [], [history]],
    ['live-append', liveSnapshots[0].slice(0, -4), liveSnapshots],
    ['identical-snapshot', identicalHistory, Array(10).fill(identicalHistory)],
    ['identical-prompt-prepend', identicalHistory, prependSnapshots],
  ];
  for (const [scenario, seed, snapshots] of scenarios) {
    const timing = measure(seed, snapshots);
    const references = validateAndCount(seed, snapshots);
    const last = snapshots.at(-1);
    const row = { turns, scenario, updates: snapshots.length, initialLogEvents: (seed.length ? seed : snapshots[0]).length,
      finalLogEvents: last.length, finalLogJsonBytes: Buffer.byteLength(JSON.stringify(last)), timing, references };
    report.results.push(row);
    await writeFile(output, JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({ turns, scenario, events: row.finalLogEvents,
      medianMs: Object.fromEntries(Object.entries(timing).map(([name, value]) => [name, Number(value.medianElapsedMs.toFixed(3))])),
      reusedMessageRefs: references.reusedMessageRefs, eligibleUnchangedMessages: references.eligibleUnchangedMessages,
      unchangedArrayUpdates: references.unchangedArrayUpdates }));
  }
}
report.sink = sink;
report.complete = true;
await writeFile(output, JSON.stringify(report, null, 2) + '\n');
console.log(`Report: ${output}`);
