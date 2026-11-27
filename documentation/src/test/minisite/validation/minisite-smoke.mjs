// Opt-in rendered-site checks. See authoring.adoc for invocation.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, readdir } from 'node:fs/promises';
import { extname, resolve, relative, sep } from 'node:path';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = resolve(process.argv[2] || 'documentation/target/site');
const base = '/yupiik-ai/';
const agentBase = `${base}coding-agent/`;
const files = [];
const walk = async directory => {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) {
      await walk(path);
    } else if (path.endsWith('.html')) {
      files.push(path);
    }
  }
};
await walk(root);
assert.ok(files.length > 0, 'Build the minisite before running this check.');
const html = new Map(await Promise.all(files.map(async path => [path, await readFile(path, 'utf8')])));
const diagramMarkup = content => content.replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1>/gi, '');
const diagramCount = content => (diagramMarkup(content).match(/<pre class="mermaid">/g) || []).length;
const diagramPages = [...html].filter(([, content]) => diagramCount(content) > 0);
assert.ok(diagramPages.length > 0, 'No rendered Mermaid blocks found in the minisite.');
const pageUrl = path => `${origin}${base}${relative(root, path).split(sep).join('/')}`;

const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://local');
  const path = resolve(root, decodeURIComponent(url.pathname.slice(base.length)));
  if (!url.pathname.startsWith(base) || !path.startsWith(root + sep)) {
    response.writeHead(404).end();
    return;
  }
  try {
    response.setHeader('Content-Type', ({ '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json' })[extname(path)] || 'application/octet-stream');
    response.end(await readFile(path));
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise(done => { server.listen(0, '127.0.0.1', done); });
const origin = `http://127.0.0.1:${server.address().port}`;
let browser;
try {
  browser = await chromium.launch({
    ...(process.env.CHROME_BIN ? { executablePath: process.env.CHROME_BIN } : {}),
    args: ['--no-sandbox']
  });
  const pageErrors = [];
  const newPage = async () => {
    const page = await browser.newPage();
    page.on('pageerror', error => { pageErrors.push(error.message); });
    return page;
  };
  const page = await newPage();
  const rendered = async expected => {
    await page.waitForFunction(count => [...document.querySelectorAll('pre.mermaid')].filter(node => node.querySelector('svg')).length === count, expected, { timeout: 45000 });
  };
  for (const [path, content] of diagramPages) {
    const count = diagramCount(content);
    await page.goto(pageUrl(path));
    await rendered(count);
    await page.locator('#theme-toggle').click();
    await page.evaluate(() => new Promise(done => { requestAnimationFrame(() => { requestAnimationFrame(done); }); }));
    await rendered(count);
    await page.evaluate(() => {
      document.documentElement.setAttribute('data-theme', 'light');
      setTimeout(() => { document.documentElement.setAttribute('data-theme', 'dark'); }, 0);
      setTimeout(() => { document.documentElement.setAttribute('data-theme', 'light'); }, 10);
    });
    await page.waitForTimeout(300);
    await rendered(count);
    assert.equal(await page.locator('[role="status"]').filter({ hasText: 'Diagram unavailable.' }).count(), 0);
    console.log(`Diagrams passed: ${relative(root, path)} (${count}, both themes and rapid toggles)`);
  }

  await page.goto(`${origin}${agentBase}configuration.html`);
  const input = page.getByRole('searchbox', { name: 'Filter configuration options by key' });
  await input.focus();
  await page.keyboard.type('NO_SUCH_CONFIGURATION_KEY_12345');
  await page.waitForFunction(() => document.getElementById('config-filter-status').textContent === '0 matching options.');
  await input.fill('provider');
  await page.waitForFunction(() => parseInt(document.getElementById('config-filter-status').textContent, 10) > 0);
  await input.fill('');
  assert.equal(await page.locator('details').count(), 1, 'Schema disclosure');
  assert.equal(await page.locator('details').getAttribute('open'), null);
  await page.locator('details > summary').focus();
  await page.keyboard.press('Enter');
  assert.notEqual(await page.locator('details').getAttribute('open'), null);

  await page.setViewportSize({ width: 390, height: 844 });
  for (const path of ['github-bot/index.html', 'github-bot/security.html', 'cli.html', 'features.html', 'libraries.html']) {
    await page.goto(`${origin}${agentBase}${path}`);
    const metrics = await page.evaluate(() => ({
      width: innerWidth,
      scroll: document.documentElement.scrollWidth
    }));
    assert.ok(metrics.scroll <= metrics.width + 1, `Mobile page overflow: ${path} (${metrics.scroll})`);
  }

  const [fallbackPath, fallbackContent] = diagramPages[0];
  const fallbackUrl = pageUrl(fallbackPath);
  const fallbackCount = diagramCount(fallbackContent);
  const offline = await newPage();
  await offline.route('**/npm/mermaid@**', route => { void route.abort(); });
  await offline.goto(fallbackUrl);
  await offline.waitForFunction(count => [...document.querySelectorAll('[role="status"]')].filter(node => node.textContent.includes('Diagram unavailable.')).length === count, fallbackCount);
  assert.equal(await offline.locator('pre.mermaid svg').count(), 0);
  const sources = await offline.evaluate(content => {
    const document = new DOMParser().parseFromString(content, 'text/html');
    return [...document.querySelectorAll('pre.mermaid')].map(node => {
      node.querySelectorAll('button.copy-code-button').forEach(button => { button.remove(); });
      return node.textContent;
    });
  }, fallbackContent);
  assert.deepEqual(await offline.locator('pre.mermaid').allTextContents(), sources, 'Unavailable loader preserves diagram sources');
  await offline.close();

  // Inject a malformed sibling so isolation does not depend on a page's diagram count or content.
  const malformed = await newPage();
  await malformed.route(fallbackUrl, async route => {
    const response = await route.fetch();
    const body = (await response.text()).replace('<pre class="mermaid">', '<pre class="mermaid">not-a-diagram</pre><pre class="mermaid">');
    await route.fulfill({ response, body });
  });
  await malformed.goto(fallbackUrl);
  await malformed.waitForFunction(count => document.querySelectorAll('pre.mermaid svg').length === count, fallbackCount, { timeout: 45000 });
  await malformed.getByRole('status').filter({ hasText: 'Diagram unavailable.' }).waitFor();
  assert.equal(await malformed.locator('pre.mermaid').first().textContent(), 'not-a-diagram');
  assert.equal(await malformed.getByRole('status').filter({ hasText: 'Diagram unavailable.' }).count(), 1);
  await malformed.close();
  assert.deepEqual(pageErrors, []);
  console.log(`Passed: ${diagramPages.length} diagram pages, fallback, malformed-diagram isolation, themes, mobile, keyboard and filter checks.`);
} finally {
  if (browser) {
    await browser.close();
  }
  server.close();
}
