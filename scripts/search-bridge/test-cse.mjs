// End-to-end test of the googlecse provider WITHOUT real Google
// credentials: a stub server stands in for the Custom Search API, and the
// bridge under test is pointed at it via CSE_ENDPOINT.
//
//   node test-cse.mjs   (or: npm test)
//
// Covers: provider auto-reporting, DTO mapping (original_url / source /
// caption / dims), SVG filtering, URL dedupe, short-page hasMore, and
// quota exhaustion surfacing as HTTP 429 (the app's RATE_LIMITED signal).
import http from 'node:http';
import { spawn } from 'node:child_process';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const STUB_PORT = 3998;
const BRIDGE_PORT = 3997;

let failures = 0;
function check(name, cond, extra = '') {
  if (cond) console.log(`  ok - ${name}`);
  else {
    failures++;
    console.error(`  FAIL - ${name} ${extra}`);
  }
}

// --- stub Google CSE upstream ----------------------------------------------
let mode = 'ok'; // 'ok' | 'quota'
const CANNED_ITEMS = [
  {
    link: 'https://img.example.com/a.jpg',
    title: 'Mountain A',
    displayLink: 'www.example.com',
    fileFormat: 'image/jpeg',
    image: { width: 3840, height: 2160, thumbnailLink: 'https://t.example.com/a' },
  },
  {
    link: 'https://img.example.com/b.svg',
    title: 'SVG (must be filtered)',
    displayLink: 'www.example.com',
    fileFormat: 'image/svg+xml',
    image: { width: 100, height: 100 },
  },
  {
    link: 'https://img.example.com/c.jpg',
    title: 'Mountain C',
    displayLink: 'cdn.example.net',
    // String dims — the app's parser tolerates both forms.
    image: { width: '1920px', height: '1080px' },
  },
  {
    link: 'https://img.example.com/a.jpg', // duplicate URL — must dedupe
    title: 'Mountain A again',
    displayLink: 'www.example.com',
    image: { width: 10, height: 10 },
  },
];

const stub = http.createServer((req, res) => {
  if (mode === 'quota') {
    res.writeHead(403, { 'content-type': 'application/json' });
    res.end(
      JSON.stringify({
        error: {
          code: 403,
          message: 'Daily Limit Exceeded',
          errors: [{ reason: 'dailyLimitExceeded', message: 'Daily Limit Exceeded' }],
        },
      }),
    );
    return;
  }
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ items: CANNED_ITEMS }));
});

// --- bridge under test -------------------------------------------------------
const bridge = spawn(process.execPath, ['server.js'], {
  cwd: HERE,
  env: {
    ...process.env,
    PORT: String(BRIDGE_PORT),
    SEARCH_PROVIDER: 'googlecse',
    GOOGLE_CSE_KEY: 'test-key',
    GOOGLE_CSE_CX: 'test-cx',
    CSE_ENDPOINT: `http://127.0.0.1:${STUB_PORT}`,
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
  console.log(`stub CSE upstream on :${STUB_PORT}, bridge under test on :${BRIDGE_PORT}\n`);

  const health = await waitHealthy();
  check('health reports googlecse provider', health.provider === 'googlecse');
  check('health reports googleCseConfigured', health.googleCseConfigured === true);

  const search = await getJson('/api/search', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query: 'mountain wallpaper', count: 20, page: 1 }),
  });
  check('search answers 200', search.status === 200, `got ${search.status}`);
  check('search success:true', search.body.success === true);
  const results = search.body.results || [];
  check('svg results filtered out', !results.some((r) => r.original_url.endsWith('.svg')));
  check(
    'duplicate URLs deduped',
    results.filter((r) => r.original_url === 'https://img.example.com/a.jpg').length === 1,
  );
  const first = results.find((r) => r.original_url === 'https://img.example.com/a.jpg');
  check('original_url mapped from item.link', Boolean(first));
  check('source mapped from displayLink', first?.source === 'www.example.com');
  check('caption mapped from title', first?.caption === 'Mountain A');
  check(
    'numeric dims pass through',
    first?.original_width === 3840 && first?.original_height === 2160,
  );
  const c = results.find((r) => r.original_url === 'https://img.example.com/c.jpg');
  check('string dims pass through', c?.original_width === '1920px');
  check('count field matches results length', search.body.count === results.length);
  check('hasMore false on a short page', search.body.hasMore === false);

  mode = 'quota';
  const quota = await getJson('/api/search', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query: 'mountain wallpaper', count: 5, page: 1 }),
  });
  check('quota exhaustion answers HTTP 429', quota.status === 429, `got ${quota.status}`);
  check('quota body reports success:false', quota.body.success === false);

  console.log(
    failures === 0 ? '\nAll CSE bridge tests passed.' : `\n${failures} test(s) FAILED.`,
  );
} finally {
  bridge.kill('SIGTERM');
  stub.close();
  if (failures > 0) process.exitCode = 1;
}
