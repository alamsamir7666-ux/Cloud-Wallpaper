// End-to-end test of the searxng provider WITHOUT a real SearXNG
// instance: a stub server stands in for the engine's JSON API and the
// bridge under test is pointed at it via SEARXNG_BASE.
//
//   node test-searxng.mjs   (or: npm test, which also runs test-cse.mjs)
//
// Covers: provider auto-reporting, DTO mapping (original_url / source /
// caption / dims from "2560 x 1440"-style resolutions), SVG filtering,
// URL dedupe, pageno forwarding, gl->language mapping, hasMore page cap,
// and the JSON-format-disabled (HTTP 403) config error surfacing as 502.
import http from 'node:http';
import { spawn } from 'node:child_process';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const STUB_PORT = 3988;
const BRIDGE_PORT = 3987;

let failures = 0;
function check(name, cond, extra = '') {
  if (cond) console.log(`  ok - ${name}`);
  else {
    failures++;
    console.error(`  FAIL - ${name} ${extra}`);
  }
}

// --- stub SearXNG upstream ---------------------------------------------------
let mode = 'ok'; // 'ok' | 'disabled'
let lastQuery = null;
const CANNED = {
  query: 'mountain wallpaper',
  results: [
    {
      title: 'Mountain A',
      img_src: 'https://img.example.com/a.jpg',
      url: 'https://www.example.com/a',
      source: 'www.example.com',
      img_format: 'jpeg',
      resolution: '2560 x 1440',
    },
    {
      title: 'SVG (must be filtered)',
      img_src: 'https://img.example.com/b.svg',
      url: 'https://www.example.com/b',
      img_format: 'image/svg+xml',
      resolution: '100x100',
    },
    {
      title: 'Mountain C — no source, mime format',
      img_src: 'https://cdn.example.net/c.png',
      url: 'https://cdn.example.net/c',
      img_format: 'image/png',
      resolution: '1920x1080',
    },
    {
      title: 'not http (must be filtered)',
      img_src: 'data:image/png;base64,AAAA',
      url: 'https://www.example.com/d',
      img_format: 'png',
      resolution: '10x10',
    },
    {
      title: 'Duplicate of A',
      img_src: 'https://img.example.com/a.jpg',
      url: 'https://www.example.com/a2',
      img_format: 'jpeg',
      resolution: '1x1',
    },
    {
      title: 'No resolution at all',
      img_src: 'https://img.example.com/e.jpg',
      url: 'https://www.example.com/e',
      img_format: 'jpeg',
    },
  ],
};

const stub = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://stub');
  lastQuery = url;
  if (mode === 'disabled') {
    res.writeHead(403, { 'content-type': 'text/html' });
    res.end('The requested format is disabled');
    return;
  }
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ ...CANNED, unresponsive_engines: [] }));
});

// --- bridge under test ---------------------------------------------------------
const bridge = spawn(process.execPath, ['server.js'], {
  cwd: HERE,
  env: {
    ...process.env,
    PORT: String(BRIDGE_PORT),
    SEARCH_PROVIDER: 'searxng',
    SEARXNG_BASE: `http://127.0.0.1:${STUB_PORT}`,
    SEARXNG_MAX_PAGE: '10',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});
bridge.stdout.on('data', (d) => process.stdout.write(`  [bridge] ${d}`));
bridge.stderr.on('data', (d) => process.stderr.write(`  [bridge!] ${d}`));

async function getJson(path, init) {
  const r = await fetch(`http://127.0.0.1:${BRIDGE_PORT}${path}`, init);
  return { status: r.status, body: await r.json() };
}

async function waitHealthy() {
  for (let i = 0; i < 50; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${BRIDGE_PORT}/health`);
      if (r.ok) return await r.json();
    } catch {
      /* retry */
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('bridge never became healthy');
}

try {
  await new Promise((r) => stub.listen(STUB_PORT, '127.0.0.1', r));
  console.log(`stub SearXNG on :${STUB_PORT}, bridge under test on :${BRIDGE_PORT}\n`);

  const health = await waitHealthy();
  check('health reports searxng provider', health.provider === 'searxng');
  check('health reports searxngConfigured', health.searxngConfigured === true);

  const search = await getJson('/api/search', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query: 'mountain wallpaper', count: 20, page: 1, gl: 'us' }),
  });
  check('search answers 200', search.status === 200, `got ${search.status}`);
  check('search success:true', search.body.success === true);
  check('pageno forwarded to instance', lastQuery?.searchParams.get('pageno') === '1');
  check('gl=us mapped to language en-US', lastQuery?.searchParams.get('language') === 'en-US');
  check('format=json requested', lastQuery?.searchParams.get('format') === 'json');
  check('categories=images requested', lastQuery?.searchParams.get('categories') === 'images');

  const results = search.body.results || [];
  check('svg results filtered out', !results.some((r) => r.original_url.endsWith('.svg')));
  check('non-http img_src filtered out', results.every((r) => r.original_url.startsWith('http')));
  check(
    'duplicate URLs deduped',
    results.filter((r) => r.original_url === 'https://img.example.com/a.jpg').length === 1,
  );
  const a = results.find((r) => r.original_url === 'https://img.example.com/a.jpg');
  check('original_url mapped from img_src', Boolean(a));
  check('caption mapped from title', a?.caption === 'Mountain A');
  check('explicit source kept', a?.source === 'www.example.com');
  check(
    'resolution "2560 x 1440" parsed to numbers',
    a?.original_width === 2560 && a?.original_height === 1440,
  );
  const c = results.find((r) => r.original_url === 'https://cdn.example.net/c.png');
  check('missing source falls back to image host', c?.source === 'cdn.example.net');
  check(
    'resolution "1920x1080" parsed to numbers',
    c?.original_width === 1920 && c?.original_height === 1080,
  );
  const e = results.find((r) => r.original_url === 'https://img.example.com/e.jpg');
  check('missing resolution stays null', e?.original_width === null && e?.original_height === null);
  check('count field matches results length', search.body.count === results.length);
  check('hasMore true while under the page cap', search.body.hasMore === true);

  mode = 'disabled';
  const disabled = await getJson('/api/search', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query: 'mountain wallpaper', count: 5, page: 1 }),
  });
  check('json-format-disabled answers 502', disabled.status === 502, `got ${disabled.status}`);
  check('disabled error mentions the settings fix', /settings/i.test(disabled.body.error || ''));
  mode = 'ok';

  console.log(
    failures === 0 ? '\nAll SearXNG bridge tests passed.' : `\n${failures} test(s) FAILED.`,
  );
} finally {
  bridge.kill('SIGTERM');
  stub.close();
  if (failures > 0) process.exitCode = 1;
}
