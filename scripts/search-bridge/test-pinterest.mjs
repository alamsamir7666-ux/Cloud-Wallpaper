// End-to-end + unit tests for the Pinterest deep-paging engine (v1.2.10).
//
//   node test-pinterest.mjs   (or: npm test, which runs the whole suite)
//
// The e2e half reproduces the production failure this engine fixes: a
// SearXNG stub that answers EVERY pageno with the same 18 results (the
// live behavior of its image engines — SearXNG's own pinterest engine
// needs a client-driven bookmark the JSON API never sends), next to a
// Pinterest stub whose BaseSearchResource answers real bookmark
// continuations. The bridge under test must merge both into a stream
// that goes hundreds of results deep with no repeats.
//
// The unit half pins the DTO mapper and the /736x/ tile rewrite.
import http from 'node:http';
import { spawn } from 'node:child_process';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { pinterestItemsToResults, pinimgThumbFor } from './server.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const SEARXNG_STUB_PORT = 3991;
const PINTEREST_STUB_PORT = 3992;
const BRIDGE_PORT = 3990;

let failures = 0;
function check(name, cond, extra = '') {
  if (cond) console.log(`  ok - ${name}`);
  else {
    failures++;
    console.error(`  FAIL - ${name} ${extra}`);
  }
}

// --- unit: the Pinterest DTO mapper -------------------------------------------

const PIN_URL = (i) => `https://i.pinimg.com/originals/23/5b/8e/pin${String(i).padStart(3, '0')}.jpg`;

function pinItem(overrides = {}) {
  return {
    type: 'pin',
    id: `pin-id-${Math.random()}`,
    title: 'A moody portrait',
    grid_title: 'Portrait pin',
    images: {
      orig: { url: PIN_URL(1), width: 1080, height: 1350 },
      '236x': { url: 'https://i.pinimg.com/236x/23/5b/8e/x.jpg', width: 236, height: 295 },
    },
    rich_summary: { site_name: 'pinterest.com' },
    ...overrides,
  };
}

{
  const [mapped] = pinterestItemsToResults([pinItem()]);
  check('mapper: full pin maps to DTO', mapped && mapped.original_url === PIN_URL(1));
  check('mapper: id mirrors the image url', mapped.id === PIN_URL(1));
  check('mapper: caption from title', mapped.caption === 'A moody portrait');
  check('mapper: numeric dims survive', mapped.original_width === 1080 && mapped.original_height === 1350);
  check('mapper: site_name wins as source', mapped.source === 'pinterest.com');

  const [grid] = pinterestItemsToResults([pinItem({ title: null })]);
  check('mapper: grid_title backs up a missing title', grid.caption === 'Portrait pin');

  const [annotated] = pinterestItemsToResults([
    pinItem({ title: null, grid_title: null, pin_join: { visual_annotation: ['dhaka street'] } }),
  ]);
  check('mapper: visual_annotation is the third title rung', annotated.caption === 'dhaka street');

  const [named] = pinterestItemsToResults([pinItem({ title: null, grid_title: null, name: 'Raw name' })]);
  check('mapper: name is the fourth title rung', named.caption === 'Raw name');

  const [hostSourced] = pinterestItemsToResults([pinItem({ rich_summary: null })]);
  check('mapper: source falls back to the image host', hostSourced.source === 'i.pinimg.com');

  const [zeroDims] = pinterestItemsToResults([
    pinItem({ images: { orig: { url: PIN_URL(2), width: 0, height: 0 } } }),
  ]);
  check('mapper: zero dims become null', zeroDims.original_width === null && zeroDims.original_height === null);

  check(
    'mapper: story items skipped (engine parity)',
    pinterestItemsToResults([pinItem({ type: 'story' })]).length === 0,
  );
  check(
    'mapper: promoted pins skipped',
    pinterestItemsToResults([pinItem({ is_ad: true })]).length === 0,
  );
  check(
    'mapper: items without images.orig skipped',
    pinterestItemsToResults([pinItem({ images: {} })]).length === 0,
  );
}

// --- unit: the pinimg /736x/ tile rewrite ---------------------------------------

{
  check(
    'pinimg: originals rewritten to the 736x rung',
    pinimgThumbFor('https://i.pinimg.com/originals/23/5b/8e/235b8e02d038164ebaf7b4d0aa428d7a.jpg', 640) ===
      'https://i.pinimg.com/736x/23/5b/8e/235b8e02d038164ebaf7b4d0aa428d7a.jpg',
  );
  check(
    'pinimg: an ask wider than the rung keeps the original',
    pinimgThumbFor('https://i.pinimg.com/originals/23/5b/8e/235b8e02d038164ebaf7b4d0aa428d7a.jpg', 800) === null,
  );
  check(
    'pinimg: already-variant urls are untouched',
    pinimgThumbFor('https://i.pinimg.com/736x/23/5b/8e/235b8e02d038164ebaf7b4d0aa428d7a.jpg', 640) === null,
  );
  check('pinimg: other hosts untouched', pinimgThumbFor('https://example.com/originals/a/b.jpg', 640) === null);
  check(
    'pinimg: uppercase hex shards match (case-insensitive)',
    pinimgThumbFor('https://i.pinimg.com/originals/AB/5B/8E/235b8e02d038164ebaf7b4d0aa428d7a.jpg', 640) ===
      'https://i.pinimg.com/736x/AB/5B/8E/235b8e02d038164ebaf7b4d0aa428d7a.jpg',
  );
}

// --- e2e stubs -------------------------------------------------------------------

// SearXNG stub: the production reality — same 18 results for every pageno.
const SEARXNG_RESULTS = Array.from({ length: 18 }, (_, i) => ({
  title: `SearXNG result ${i}`,
  img_src: `https://img.example.com/searxng-${i}.jpg`,
  url: `https://www.example.com/${i}`,
  source: 'www.example.com',
  img_format: 'jpeg',
  resolution: `${1920 - i}x${1080 - i}`,
}));
let searxngCalls = [];
const searxngStub = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://stub');
  searxngCalls.push(url);
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ query: url.searchParams.get('q'), results: SEARXNG_RESULTS, unresponsive_engines: [] }));
});

// Pinterest stub: 4 pages of 25 pins, real bookmark continuations, then -end-.
const PINTEREST_PAGES = 4;
const PINTEREST_PIN_COUNT = 25;
const pinPages = Array.from({ length: PINTEREST_PAGES }, (_, p) =>
  Array.from({ length: PINTEREST_PIN_COUNT }, (_, i) =>
    pinItem({
      id: `p${p}-${i}`,
      title: `Pinterest page ${p + 1} pin ${i}`,
      images: {
        orig: {
          url: `https://i.pinimg.com/originals/5b/8e/0${p}/stub${p}${String(i).padStart(3, '0')}.jpg`,
          width: 900 + p,
          height: 1200 + p,
        },
      },
    }),
  ),
);
const BOOKMARKS = ['bm-page-1', 'bm-page-2', 'bm-page-3', '-end-'];
let pinterestCalls = []; // { bookmark, headers, query, pageSize }
let pinterestMode = 'ok'; // 'ok' | 'blocked'
const pinterestStub = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://stub');
  if (pinterestMode === 'blocked') {
    res.writeHead(403, { 'content-type': 'text/html' });
    res.end('blocked');
    return;
  }
  const envelope = JSON.parse(url.searchParams.get('data') || '{}');
  const options = envelope.options || {};
  const bookmark = (options.bookmarks && options.bookmarks[0]) || '';
  pinterestCalls.push({
    bookmark,
    query: options.query,
    pageSize: options.page_size,
    headers: req.headers,
  });
  const pageIndex = BOOKMARKS.indexOf(bookmark); // '' -> -1 -> page 0
  const results = pinPages[pageIndex + 1] ?? [];
  const next = BOOKMARKS[pageIndex + 1] ?? '-end-';
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ resource_response: { data: { results }, bookmark: next } }));
});

// Bridge under test: both upstreams stubbed.
const bridge = spawn(process.execPath, ['server.js'], {
  cwd: HERE,
  env: {
    ...process.env,
    PORT: String(BRIDGE_PORT),
    SEARCH_PROVIDER: 'searxng',
    SEARXNG_BASE: `http://127.0.0.1:${SEARXNG_STUB_PORT}`,
    SEARXNG_MAX_PAGE: '10',
    PINTEREST_BASE: `http://127.0.0.1:${PINTEREST_STUB_PORT}`,
    PINTEREST_ENGINE: '1',
    PINTEREST_PAGE_SIZE: '25',
    PINTEREST_MAX_PAGES: '12',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
});
bridge.stdout.on('data', (d) => process.stdout.write(`  [bridge] ${d}`));
bridge.stderr.on('data', (d) => process.stderr.write(`  [bridge!] ${d}`));

async function getJson(path, init) {
  const r = await fetch(`http://127.0.0.1:${BRIDGE_PORT}${path}`, init);
  return { status: r.status, body: await r.json() };
}

async function search(query, page, count = 20) {
  return getJson('/api/search', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query, count, page, gl: 'us' }),
  });
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
  await new Promise((r) => searxngStub.listen(SEARXNG_STUB_PORT, '127.0.0.1', r));
  await new Promise((r) => pinterestStub.listen(PINTEREST_STUB_PORT, '127.0.0.1', r));
  console.log(`stubs on :${SEARXNG_STUB_PORT} (searxng) :${PINTEREST_STUB_PORT} (pinterest), bridge on :${BRIDGE_PORT}\n`);

  const health = await waitHealthy();
  check('health reports the pinterest engine on', health.pinterestEngine === true);

  // -- page 1: the broad SearXNG mix leads, Pinterest fills to the window.
  searxngCalls = [];
  pinterestCalls = [];
  const p1 = await search('Modhumita Sarker', 1);
  check('page 1 answers 200', p1.status === 200, `got ${p1.status}`);
  check('page 1 serves a full 20-result window', p1.body.results.length === 20, `got ${p1.body.results.length}`);
  check('page 1 hasMore stays true', p1.body.hasMore === true);
  check(
    'page 1 leads with the SearXNG batch (broad first screen preserved)',
    p1.body.results.slice(0, 18).every((r) => r.original_url.startsWith('https://img.example.com/searxng-')),
  );
  check(
    'page 1 fills the window with Pinterest pins',
    p1.body.results.slice(18).every((r) => r.original_url.startsWith('https://i.pinimg.com/originals/')),
  );
  check('searxng stub saw pageno=1', searxngCalls.some((u) => u.searchParams.get('pageno') === '1'));
  check('pinterest stub saw the empty first bookmark', pinterestCalls.some((c) => c.bookmark === ''));
  const firstPinCall = pinterestCalls[0];
  check('pinterest request carries the query', firstPinCall && firstPinCall.query === 'Modhumita Sarker');
  check('pinterest request asks for the 25-pin page size', firstPinCall && firstPinCall.pageSize === 25);
  check(
    'pinterest request rides the engine headers',
    firstPinCall &&
      firstPinCall.headers['x-pinterest-appstate'] === 'active' &&
      firstPinCall.headers['x-pinterest-source-url'] === '/ideas/' &&
      firstPinCall.headers['x-requested-with'] === 'XMLHttpRequest',
  );
  check('pinterest request brands a browser UA', firstPinCall && /Mozilla/.test(firstPinCall.headers['user-agent'] || ''));

  // -- pages 2..6: the deep stream — every window is FRESH results.
  const seen = new Set(p1.body.results.map((r) => r.original_url));
  const windows = [p1.body.results];
  let hasMoreTrue = p1.body.hasMore;
  for (let page = 2; page <= 6; page++) {
    const res = await search('Modhumita Sarker', page);
    const fresh = res.body.results.filter((r) => !seen.has(r.original_url));
    check(
      `page ${page} serves ${res.body.results.length} results, ALL fresh (no cross-page repeats)`,
      fresh.length === res.body.results.length && res.body.results.length > 0,
      `${fresh.length}/${res.body.results.length} fresh`,
    );
    for (const r of res.body.results) seen.add(r.original_url);
    windows.push(res.body.results);
    if (page === 6) hasMoreTrue = res.body.hasMore;
  }
  check(
    `the walk totals 118 unique results (18 searxng + 100 pins) — the "hundreds", not 18`,
    seen.size === 118,
    `got ${seen.size}`,
  );
  check('hasMore flips false once both streams are exhausted', hasMoreTrue === false);

  // The bookmark chain the stub served: page 2+ requests must ride the
  // tokens the earlier pages returned (the bug's missing ingredient).
  const chainBookmarks = pinterestCalls.map((c) => c.bookmark);
  check(
    'deep pages ride real bookmark tokens',
    chainBookmarks.includes('bm-page-1') && chainBookmarks.includes('bm-page-2') &&
      chainBookmarks.includes('bm-page-3'),
    `saw: ${chainBookmarks.join(',')}`,
  );

  // -- a page-1 request over a live state is a fresh search: chain resets.
  pinterestCalls = [];
  const again = await search('Modhumita Sarker', 1);
  check('re-search restarts at the same first window', JSON.stringify(again.body.results.map((r) => r.original_url)) ===
    JSON.stringify(windows[0].map((r) => r.original_url)));
  check('re-search restarts the bookmark chain from empty', pinterestCalls.some((c) => c.bookmark === ''));

  // -- Pinterest hard failure degrades to the SearXNG-only behavior.
  pinterestMode = 'blocked';
  const degraded = await search('another query', 1);
  check('pinterest block still answers 200', degraded.status === 200, `got ${degraded.status}`);
  check(
    'pinterest block still serves the searxng batch',
    degraded.body.results.length === 18 &&
      degraded.body.results.every((r) => r.original_url.startsWith('https://img.example.com/searxng-')),
    `got ${degraded.body.results.length}`,
  );
  pinterestMode = 'ok';

  console.log(
    failures === 0 ? '\nAll Pinterest deep-paging tests passed.' : `\n${failures} test(s) FAILED.`,
  );
} finally {
  bridge.kill('SIGTERM');
  searxngStub.close();
  pinterestStub.close();
  if (failures > 0) process.exitCode = 1;
}
