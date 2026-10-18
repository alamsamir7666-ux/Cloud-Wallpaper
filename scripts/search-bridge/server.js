// Cloudimage global-search bridge.
//
// A small HTTP server that exposes two routes the Cloud-Wallpaper Android
// app expects:
//
//   POST /api/search            — JSON body {query,count,page,gl} → JSON
//   GET  /api/proxy-image       — ?url=&w=&q=&fmt= → image/jpeg
//
// The search behind /api/search is provider-pluggable (see selectProvider):
//
//   zaicli     — the z-ai image-search CLI. Zero-config inside this
//                sandbox, but its credentials are chat-scoped and die
//                with the sandbox: not deployable anywhere else.
//   searxng    — a self-hosted SearXNG metasearch instance (bundled in
//                the Docker image, internal :8080). No API keys, no
//                quotas, no limits: it aggregates Bing, DuckDuckGo,
//                Qwant, Openverse, Wikimedia, ... in parallel and
//                degrades gracefully when one engine misbehaves.
//                THE provider for a permanent free deployment — see
//                DEPLOY.md. On top of the metasearch pages, the bridge
//                drives Pinterest's own search API directly (bookmark
//                continuation, server-side state) so image queries go
//                hundreds of results deep instead of stalling on
//                SearXNG's duplicate pageno batches.
//   googlecse  — the Google Programmable Search JSON API with image
//                search enabled, driven by your own API key. Free tier
//                is capped at 100 queries/day — kept as an option, not
//                the recommendation.

import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import http from 'node:http';
import https from 'node:https';
import { URL, pathToFileURL } from 'node:url';
import { lookup } from 'node:dns/promises';

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;
const MAX_RESULTS_PER_PAGE = 20;
const UPSTREAM_TIMEOUT_MS = 120_000;

// --- search provider selection ---------------------------------------------

const GOOGLE_CSE_ENDPOINT =
  process.env.CSE_ENDPOINT || 'https://www.googleapis.com/customsearch/v1';
const CSE_NUM_PER_CALL = 10; // API hard limit: num <= 10
const CSE_MAX_START = 91; // API hard limit: start + num - 1 <= 100
const CSE_TIMEOUT_MS = 20_000;
const CSE_IMG_SIZE = (process.env.CSE_IMG_SIZE || '').trim(); // '', 'large', 'xlarge'…
const CSE_SAFE = (process.env.CSE_SAFE || '').trim(); // '', 'active', 'off'

// SearXNG instance settings — the combined Docker image runs the engine
// on localhost:8080 next to this bridge.
const SEARXNG_BASE = (process.env.SEARXNG_BASE || '').trim().replace(/\/+$/, '');
const SEARXNG_TIMEOUT_MS = parseInt(process.env.SEARXNG_TIMEOUT_MS || '25000', 10);
const SEARXNG_SAFESSEARCH = parseInt(process.env.SEARXNG_SAFESSEARCH || '0', 10);
// Most engines paginate; results thin out eventually. The app dedupes by
// URL, so a page of repeats collapses to nothing and the grid stops.
const SEARXNG_MAX_PAGE = parseInt(process.env.SEARXNG_MAX_PAGE || '10', 10);
// gl (ISO country) -> SearXNG language. Anything unmapped falls back to
// the instance default; pass-through for already-qualified codes.
const GL_TO_LANGUAGE = { us: 'en-US', gb: 'en-GB', au: 'en-AU', ca: 'en-CA', in: 'en-IN', bd: 'bn' };

// --- Pinterest deep-paging engine -------------------------------------------
//
// Why this exists (live-verified on production, v1.2.9): SearXNG's own
// pinterest engine answers our /search?pageno=N calls with the SAME first
// batch every time, because its pagination is client-driven — the engine
// returns a `bookmark` continuation token that must ride the NEXT request
// (engine_data), and the bare JSON API never sends it. Result: the app's
// "load more" walked into a wall of duplicates — 18 unique pinimg images
// total for a query like "Modhumita Sarker" where the web shows hundreds.
//
// So the bridge drives Pinterest's BaseSearchResource API itself, keeping
// the per-query bookmark chain server-side, and merges that deep stream
// with SearXNG's broad first page. The request shape below mirrors
// SearXNG's engine module exactly (searx/engines/pinterest.py) — the same
// headers that engine rides from Render's datacenter IP today.
const PINTEREST_BASE =
  (process.env.PINTEREST_BASE || 'https://www.pinterest.com').trim().replace(/\/+$/, '');
const PINTEREST_ENGINE = !['0', 'false', 'off'].includes(
  (process.env.PINTEREST_ENGINE || '1').trim().toLowerCase(),
);
const PINTEREST_TIMEOUT_MS = parseInt(process.env.PINTEREST_TIMEOUT_MS || '12000', 10);
// Pinterest's resource API honors page_size; 25 is the engine-proven
// default (what the SearXNG engine and the site's own grid ride).
const PINTEREST_PAGE_SIZE = parseInt(process.env.PINTEREST_PAGE_SIZE || '25', 10);
// 12 pages x ~25 pins ≈ 300 images per query — the "hundreds" the web
// shows, before the stream is declared exhausted.
const PINTEREST_MAX_PAGES = parseInt(process.env.PINTEREST_MAX_PAGES || '12', 10);
// SearXNG rides its default browser UA for engine traffic; this matches
// that family (a plain desktop Firefox), env-overridable for tuning.
const PINTEREST_USER_AGENT =
  process.env.PINTEREST_USER_AGENT ||
  'Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0';

// Upstream image hosts are picky about clients, each in its own way
// (verified live against Wikimedia, the largest wallpaper source in the
// results): UA-less requests get 403 by policy, generic browser UAs get
// rate-limited (429) from datacenter IPs, and a descriptive UA with
// contact info sails through (200). Env-overridable for future tuning.
const PROXY_USER_AGENT = process.env.PROXY_USER_AGENT ||
  'CloudImageBridge/1.0 (https://github.com/alamsamir7666-ux/Cloud-Wallpaper; image thumbnail proxy)';

// --- Supabase corpus mirror -------------------------------------------------
//
// Every successful /api/search upserts its result rows into a shared
// Supabase table (public.search_corpus) so the app gains an instant,
// keyless corpus lane on top of the live bridge. Writes go through the
// service_role key — a server-side secret held as a Render env var,
// NEVER shipped inside the app; reads from phones are anonymous and
// RLS-restricted to SELECT. Mirroring is strictly fire-and-forget: an
// absent, failing, or paused Supabase never slows or breaks a search.
//
// Upserts use Prefer: resolution=merge-duplicates on the content_key
// unique column, and re-serve refreshes scraped_at — which doubles as
// each row's liveness heartbeat for the app-side TTL governance.
const SUPABASE_URL_ENV = (process.env.SUPABASE_URL || '').trim().replace(/\/+$/, '');
const SUPABASE_SERVICE_KEY = (process.env.SUPABASE_SERVICE_KEY || '').trim();
const SUPABASE_MIRROR = Boolean(SUPABASE_URL_ENV && SUPABASE_SERVICE_KEY);
const SUPABASE_REST = SUPABASE_URL_ENV ? `${SUPABASE_URL_ENV}/rest/v1` : '';
const SUPABASE_TIMEOUT_MS = parseInt(process.env.SUPABASE_TIMEOUT_MS || '10000', 10);
const MIRROR_MAX_ROWS = 60; // per request; a page is <= MAX_RESULTS_PER_PAGE anyway
const mirrorInFlight = new Set(); // query_norm keys with a POST in flight

class CseQuotaError extends Error {}
class CseConfigError extends Error {}
class SearxngConfigError extends Error {}

/** True when the z-ai CLI is on PATH — i.e. we are inside the sandbox. */
function hasZaCli() {
  try {
    return spawnSync('which', ['z-ai'], { encoding: 'utf8' }).status === 0;
  } catch {
    return false;
  }
}

function selectProvider() {
  const forced = (process.env.SEARCH_PROVIDER || '').trim().toLowerCase();
  if (forced === 'zaicli' || forced === 'googlecse' || forced === 'searxng') {
    return forced;
  }
  if (hasZaCli()) return 'zaicli';
  if (SEARXNG_BASE) return 'searxng';
  if (process.env.GOOGLE_CSE_KEY && process.env.GOOGLE_CSE_CX) return 'googlecse';
  return 'none';
}

const PROVIDER = selectProvider();

// Pagination vocabulary — every page beyond 1 appends one of these
// modifiers to the query to surface a fresh batch (mirrors the engine's
// own contract; the caller dedupes by URL).
const PAGE_MODIFIERS = [
  'hd',
  'high resolution',
  'wallpaper',
  '4k',
  '8k',
  'ultra hd',
  'desktop',
  'mobile',
  'abstract',
  'minimal',
];

// --- in-memory LRU for proxy responses (key = full proxy URL) ---
const proxyCache = new Map();
const PROXY_CACHE_MAX = parseInt(process.env.PROXY_CACHE_MAX || '200', 10);
// Total-bytes budget — the cache holds full-resolution originals, so a
// bare entry cap is not enough on small containers: 200 entries at a few
// MB each would OOM a 512 MB free-tier box shared with SearXNG.
const PROXY_CACHE_BUDGET_BYTES =
  parseInt(process.env.PROXY_CACHE_BUDGET_MB || '128', 10) * 1024 * 1024;
// Single entries above this size are answered but never cached — one
// 20 MB wallpaper original must not evict twenty thumbnails.
const PROXY_CACHEABLE_MAX_BYTES =
  parseInt(process.env.PROXY_CACHEABLE_MAX_MB || '4', 10) * 1024 * 1024;
let proxyCacheBytes = 0;

function proxyCacheSet(key, value) {
  if (value.body.length > PROXY_CACHEABLE_MAX_BYTES) return; // pass through
  proxyCache.set(key, value);
  proxyCacheBytes += value.body.length;
  // Evict oldest entries — Map preserves insertion order — until both
  // the entry cap and the byte budget are honored.
  while (
    (proxyCache.size > PROXY_CACHE_MAX || proxyCacheBytes > PROXY_CACHE_BUDGET_BYTES) &&
    proxyCache.size > 0
  ) {
    const oldest = proxyCache.keys().next().value;
    proxyCacheBytes -= proxyCache.get(oldest).body.length;
    proxyCache.delete(oldest);
  }
}

// --- helpers ---

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'access-control-allow-origin': '*',
  });
  res.end(payload);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', (chunk) => {
      total += chunk.length;
      if (total > 64 * 1024) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

// Invoke the z-ai CLI's image-search subcommand. Resolves with the
// parsed JSON stdout. The CLI prints progress emojis (🚀 🔎 ✅) to
// stdout before the JSON object, so we slice from the first `{` to
// the matching last `}` before parsing.
function callZaCliImageSearch({ query, count, gl }) {
  return new Promise((resolve, reject) => {
    const args = [
      'image-search',
      '--query', query,
      '--count', String(count),
      '--gl', gl || 'us',
      '--no-rank', // faster; the app doesn't use captions anyway
    ];
    const child = spawn('z-ai', args, {
      timeout: UPSTREAM_TIMEOUT_MS,
      windowsHide: true,
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
    child.stderr.on('data', (chunk) => { stderr += chunk.toString(); });
    child.on('error', reject);
    child.on('close', (code) => {
      if (code !== 0) {
        reject(new Error(`z-ai exited ${code}: ${stderr.trim() || '(no stderr)'}`));
        return;
      }
      // The CLI decorates stdout with progress lines; find the JSON.
      const firstBrace = stdout.indexOf('{');
      const lastBrace = stdout.lastIndexOf('}');
      if (firstBrace === -1 || lastBrace === -1 || lastBrace < firstBrace) {
        reject(new Error(`z-ai returned no JSON object: ${stdout.slice(0, 200)}`));
        return;
      }
      const jsonText = stdout.slice(firstBrace, lastBrace + 1);
      try {
        resolve(JSON.parse(jsonText));
      } catch (err) {
        reject(new Error(`z-ai returned unparseable JSON: ${jsonText.slice(0, 200)}`));
      }
    });
  });
}

// --- providers: unified search entry points -------------------------------

// Sandbox provider: modifier pagination — the z-ai service answers each
// query with one capped batch (no offset, no cursor), so every page
// beyond 1 re-searches with a modifier appended to surface a fresh batch;
// the caller dedupes by URL.
async function searchViaZaCli({ query, count, page, gl }) {
  const effectiveQuery = page > 1
    ? `${query} ${PAGE_MODIFIERS[(page - 2) % PAGE_MODIFIERS.length]}`
    : query;
  const upstream = await callZaCliImageSearch({ query: effectiveQuery, count, gl });
  const results = upstream.results ?? [];
  return {
    results,
    hasMore: page < PAGE_MODIFIERS.length + 1 && results.length > 0,
  };
}

// External-host provider: real offset pagination — page N reads results
// (N-1)*count+1 … N*count in num<=10 chunks fetched in parallel, and the
// Google API's 100-results-per-query ceiling bounds hasMore.
async function searchViaGoogleCse({ query, count, page, gl }) {
  if (!process.env.GOOGLE_CSE_KEY || !process.env.GOOGLE_CSE_CX) {
    throw new CseConfigError(
      'googlecse provider selected but GOOGLE_CSE_KEY / GOOGLE_CSE_CX are not set — ' +
        'create a Programmable Search engine + API key (see DEPLOY.md) and set them as secrets',
    );
  }
  const startBase = (page - 1) * count; // 0-based offset of this page's first result
  const calls = [];
  for (let off = 0; off < count; off += CSE_NUM_PER_CALL) {
    const start = startBase + off + 1;
    if (start > CSE_MAX_START) break;
    calls.push(cseCall({ query, num: Math.min(CSE_NUM_PER_CALL, count - off), start, gl }));
  }
  if (calls.length === 0) return { results: [], hasMore: false };

  const batches = await Promise.all(calls);
  const merged = cseItemsToResults(batches.flat());
  const nextStart = startBase + count + 1;
  return {
    results: merged.slice(0, count),
    hasMore: merged.length >= count && nextStart <= CSE_MAX_START,
  };
}

async function cseCall({ query, num, start, gl }) {
  const params = new URLSearchParams({
    key: process.env.GOOGLE_CSE_KEY,
    cx: process.env.GOOGLE_CSE_CX,
    q: query,
    searchType: 'image',
    num: String(num),
    start: String(start),
  });
  if (gl) params.set('gl', gl);
  if (CSE_IMG_SIZE) params.set('imgSize', CSE_IMG_SIZE);
  if (CSE_SAFE) params.set('safe', CSE_SAFE);

  const resp = await fetch(`${GOOGLE_CSE_ENDPOINT}?${params}`, {
    signal: AbortSignal.timeout(CSE_TIMEOUT_MS),
  });
  const bodyText = await resp.text();
  let body = null;
  try {
    body = JSON.parse(bodyText);
  } catch {
    // fall through — handled by the status check below
  }

  if (!resp.ok) {
    const reason =
      body?.error?.errors?.[0]?.reason ?? body?.error?.status ?? `HTTP ${resp.status}`;
    const message = body?.error?.message || bodyText.slice(0, 200);
    if (resp.status === 403 || /LimitExceeded|quota/i.test(String(reason))) {
      // Mapped to HTTP 429 — the app surfaces RATE_LIMITED without
      // invalidating the backend address (a quota day is not a dead bridge).
      throw new CseQuotaError(`Google CSE quota: ${reason} — ${message}`);
    }
    throw new CseConfigError(`Google CSE rejected the request: ${reason} — ${message}`);
  }
  return Array.isArray(body?.items) ? body.items : [];
}

// Map Google CSE items onto the response DTO the app already parses —
// the two providers are indistinguishable to the phone.
function cseItemsToResults(items) {
  const seen = new Set();
  const results = [];
  for (const item of items) {
    const link = String(item.link || '');
    if (!/^https?:\/\//i.test(link)) continue;
    // Android's image pipeline cannot decode SVG results.
    if (String(item.fileFormat || '').toLowerCase().includes('svg')) continue;
    if (seen.has(link)) continue;
    seen.add(link);
    results.push({
      id: link,
      original_url: link,
      caption: item.title ? String(item.title) : null,
      source: item.displayLink ? String(item.displayLink) : null,
      original_width: item.image?.width ?? null,
      original_height: item.image?.height ?? null,
    });
  }
  return results;
}

// Permanent-host provider: a self-hosted SearXNG metasearch. No keys,
// no quotas — the instance aggregates many image engines in parallel
// (bing, duckduckgo, qwant, openverse, wikimedia, google-cse-scrape, ...)
// and answers even when some of them fail.
//
// The page-based protocol the app speaks is stateless, but Pinterest's
// deep stream is bookmark-continuous, so the provider keeps a small
// per-query state (see QUERY_STATES) and serves count-sized windows of
// one merged virtual stream: SearXNG's broad pages interleaved with the
// Pinterest pages that actually go deep. See runMergedSearch below.
async function fetchSearxngPage({ query, page, gl }) {
  if (!SEARXNG_BASE) {
    throw new SearxngConfigError(
      'searxng provider selected but SEARXNG_BASE is not set — point it at ' +
        'the SearXNG instance (the combined Docker image bundles one on :8080; see DEPLOY.md)',
    );
  }
  const params = new URLSearchParams({
    q: query,
    categories: 'images',
    format: 'json',
    pageno: String(page),
    safesearch: String(SEARXNG_SAFESSEARCH),
  });
  const language = glToLanguage(gl);
  if (language) params.set('language', language);

  const resp = await fetch(`${SEARXNG_BASE}/search?${params}`, {
    headers: { accept: 'application/json' },
    signal: AbortSignal.timeout(SEARXNG_TIMEOUT_MS),
  });
  const bodyText = await resp.text();
  let body;
  try {
    body = JSON.parse(bodyText);
  } catch {
    // fall through — handled by the status check below
  }

  if (!resp.ok || body === null || typeof body !== 'object') {
    // 403 is SearXNG's "format json is disabled on this instance" answer —
    // a deployment problem worth naming explicitly in the error.
    if (resp.status === 403) {
      throw new SearxngConfigError(
        'SearXNG refused the JSON API (HTTP 403) — enable `json` in the ' +
          'instance settings: search.formats must include json (see settings.yml in this directory)',
      );
    }
    throw new SearxngConfigError(
      `SearXNG instance at ${SEARXNG_BASE} answered HTTP ${resp.status}`,
    );
  }

  const items = Array.isArray(body.results) ? body.results : [];
  return searxngItemsToResults(items);
}

// One page of Pinterest's search stream. `bookmark` is the continuation
// token the previous page returned ('' for the first page); the response
// carries the token for the NEXT page in resource_response.bookmark —
// '-end-' (or an empty/null token) means the stream is exhausted.
// Request shape mirrors SearXNG's engine module (searx/engines/
// pinterest.py): same URL, same data envelope, same X-Pinterest headers.
async function fetchPinterestPage(query, bookmark) {
  const envelope = {
    options: {
      query,
      bookmarks: [bookmark || ''],
      page_size: PINTEREST_PAGE_SIZE,
    },
    context: {},
  };
  const url =
    `${PINTEREST_BASE}/resource/BaseSearchResource/get/?data=` +
    encodeURIComponent(JSON.stringify(envelope));
  const resp = await fetch(url, {
    headers: {
      accept: 'application/json, text/javascript, */*, q=0.01',
      'accept-language': 'en-US,en;q=0.9',
      'user-agent': PINTEREST_USER_AGENT,
      'x-requested-with': 'XMLHttpRequest',
      'x-pinterest-appstate': 'active',
      'x-pinterest-source-url': '/ideas/',
      'x-pinterest-pws-handler': 'www/ideas.js',
    },
    signal: AbortSignal.timeout(PINTEREST_TIMEOUT_MS),
  });
  if (!resp.ok) {
    throw new Error(`Pinterest answered HTTP ${resp.status}`);
  }
  const body = await resp.json();
  const items = body?.resource_response?.data?.results;
  const nextBookmark = body?.resource_response?.bookmark ?? null;
  return {
    results: Array.isArray(items) ? pinterestItemsToResults(items) : [],
    bookmark: typeof nextBookmark === 'string' ? nextBookmark : null,
  };
}

// Map Pinterest pin items onto the response DTO the app parses. Shapes
// verified against SearXNG's engine parser: images.orig is the original
// (i.pinimg.com/originals/...) with numeric width/height, the title
// falls back through the same chain the engine uses, and anything that
// is not a real pin (stories, boards, promoted slots, pinless items)
// is dropped.
function pinterestItemsToResults(items) {
  const results = [];
  for (const item of items) {
    if (!item || typeof item !== 'object') continue;
    if (item.type === 'story') continue; // the engine's own skip
    if (item.is_ad || item.promoted_is_removable) continue;
    const orig = item.images && item.images.orig;
    const link = orig && orig.url ? String(orig.url) : '';
    if (!/^https?:\/\//i.test(link)) continue;
    const title =
      item.title || item.grid_title ||
      (item.pin_join && item.pin_join.visual_annotation &&
        item.pin_join.visual_annotation[0]) ||
      item.name || item.auto_alt_text || '';
    results.push({
      id: link,
      original_url: link,
      caption: title ? String(title) : null,
      source:
        (item.rich_summary && item.rich_summary.site_name) || hostnameOf(link) || null,
      original_width: Number(orig.width) > 0 ? Number(orig.width) : null,
      original_height: Number(orig.height) > 0 ? Number(orig.height) : null,
    });
  }
  return results;
}

// --- per-query stream state ---------------------------------------------------
//
// The app's protocol is {query, count, page} with no cursor, but the deep
// Pinterest stream needs one. State per (query, gl): the bookmark for the
// next Pinterest page, the next SearXNG pageno, a pending buffer of
// fetched-but-unemitted results, and the set of URLs already served (the
// cross-page dedupe — SearXNG's pageno results repeat heavily, so its
// later pages contribute only what the first page missed).
//
// A page-1 request onto an existing state is treated as a fresh search
// (the app's SearchViewModel always restarts at page 1), so the chain
// resets and the first window is served again.
const QUERY_STATES = new Map();
const QUERY_STATE_MAX = 64; // bounded: ~300 URLs per entry ≈ 1-2 MB worst case
const QUERY_STATE_TTL_MS = 45 * 60 * 1000;
// Fill rounds per request — a mid-scroll wake-from-sleep rebuilds the
// chain from page 1; 8 rounds reaches deep pages without stalling the
// request on a pathological rebuild.
const MERGE_MAX_ROUNDS_PER_REQUEST = 8;
// SearXNG pages that contribute nothing new (the duplicate-page reality
// behind most of its image engines) before we stop asking it.
const SEARXNG_BARREN_LIMIT = 2;

function queryStateKey(query, gl) {
  return `${query.trim().toLowerCase()}|${String(gl || 'us').toLowerCase()}`;
}

function getQueryState(query, gl) {
  const key = queryStateKey(query, gl);
  let state = QUERY_STATES.get(key);
  if (state && Date.now() - state.updatedAt > QUERY_STATE_TTL_MS) {
    // Stale chain — its bookmarks may outlive Pinterest's patience;
    // start over rather than serve from a dead cursor.
    QUERY_STATES.delete(key);
    state = undefined;
  }
  if (state) {
    // LRU refresh: re-insert so Map order tracks recency.
    QUERY_STATES.delete(key);
    QUERY_STATES.set(key, state);
    state.updatedAt = Date.now();
  } else {
    state = {
      key,
      updatedAt: Date.now(),
      emitted: new Set(),
      emittedCount: 0,
      pending: [],
      searxngPage: 1,
      searxngBarren: 0,
      searxngDone: false,
      pinterestBookmark: '',
      pinterestPages: 0,
      pinterestDone: !PINTEREST_ENGINE,
    };
    QUERY_STATES.set(key, state);
  }
  // Entry cap — oldest-inserted first (Map preserves insertion order).
  while (QUERY_STATES.size > QUERY_STATE_MAX) {
    const oldest = QUERY_STATES.keys().next().value;
    QUERY_STATES.delete(oldest);
  }
  return state;
}

/** True when a fill round could still fetch something from either stream. */
function mergeSourcesAlive(state) {
  const searxngAlive =
    !state.searxngDone && state.searxngPage <= SEARXNG_MAX_PAGE;
  const pinterestAlive =
    !state.pinterestDone && state.pinterestPages < PINTEREST_MAX_PAGES;
  return searxngAlive || pinterestAlive;
}

/**
 * Serves window `page` of the merged stream for `query`.
 *
 * The virtual stream is built in lockstep rounds — SearXNG page k then
 * Pinterest page k — so the first screen stays the broad metasearch mix
 * the app shows today, and every later window is dominated by the deep
 * Pinterest pages. `emitted` keeps the windows disjoint across requests;
 * `pending` carries the unemitted tail between requests.
 */
async function runMergedSearch({ query, count, page, gl }) {
  const state = getQueryState(query, gl);
  // A page-1 request over a live state is a fresh search — reset.
  if (page === 1 && (state.emittedCount > 0 || state.pending.length > 0)) {
    QUERY_STATES.delete(state.key);
    return runMergedSearch({ query, count, page, gl });
  }

  const target = page * count; // absolute end of this window in the stream
  let rounds = 0;
  // A config-level SearXNG failure (JSON API disabled, instance down) is
  // a deployment problem, not engine degradation: remembered here and
  // rethrown at the end — but only when the merged stream could not serve
  // anything anyway, so a Pinterest-served search still succeeds.
  let searxngConfigError = null;
  while (
    state.emittedCount + state.pending.length < target &&
    mergeSourcesAlive(state) &&
    rounds < MERGE_MAX_ROUNDS_PER_REQUEST
  ) {
    rounds++;
    const jobs = [];
    const before = state.pending.length;

    if (!state.searxngDone && state.searxngPage <= SEARXNG_MAX_PAGE) {
      const pageNo = state.searxngPage;
      jobs.push(
        fetchSearxngPage({ query, page: pageNo, gl })
          .then((results) => ({ kind: 'searxng', pageNo, results }))
          .catch((err) => {
            console.warn(
              `[search] searxng page ${pageNo} failed for "${query}": ${err.message}`,
            );
            if (err instanceof SearxngConfigError) searxngConfigError ??= err;
            return { kind: 'searxng', pageNo, results: null };
          }),
      );
    }
    if (!state.pinterestDone && state.pinterestPages < PINTEREST_MAX_PAGES) {
      jobs.push(
        fetchPinterestPage(query, state.pinterestBookmark)
          .then((page_) => ({ kind: 'pinterest', results: page_.results, bookmark: page_.bookmark }))
          .catch((err) => {
            console.warn(
              `[search] pinterest page ${state.pinterestPages + 1} failed for ` +
                `"${query}": ${err.message}`,
            );
            return { kind: 'pinterest', results: null, bookmark: null };
          }),
      );
    }
    if (jobs.length === 0) break;
    const batches = await Promise.all(jobs);

    let searxngAdded = 0;
    for (const batch of batches) {
      if (batch.kind === 'searxng') {
        state.searxngPage = batch.pageNo + 1;
        if (batch.results === null) {
          // Hard failure — SearXNG is the broad backbone, but a search
          // still functions without it when Pinterest answers (the config
          // error is rethrown below only if nothing was served).
          state.searxngBarren++;
          if (state.searxngBarren >= SEARXNG_BARREN_LIMIT) state.searxngDone = true;
          continue;
        }
        for (const r of batch.results) {
          if (state.emitted.has(r.original_url)) continue;
          state.emitted.add(r.original_url);
          state.pending.push(r);
          searxngAdded++;
        }
        if (searxngAdded === 0) {
          state.searxngBarren++;
          if (state.searxngBarren >= SEARXNG_BARREN_LIMIT) state.searxngDone = true;
        } else {
          state.searxngBarren = 0;
        }
      } else {
        if (batch.results === null) {
          // Pinterest hard-failed (blocked, timeout) — degrade to the
          // SearXNG-only behavior rather than failing the search.
          state.pinterestDone = true;
          continue;
        }
        state.pinterestPages++;
        let pinterestAdded = 0;
        for (const r of batch.results) {
          if (state.emitted.has(r.original_url)) continue;
          state.emitted.add(r.original_url);
          state.pending.push(r);
          pinterestAdded++;
        }
        // '-end-' is Pinterest's end-of-stream marker; a missing token, an
        // empty page, or a page of pure repeats (broken cursor) all mean
        // the stream is finished.
        if (
          !batch.bookmark ||
          batch.bookmark === '-end-' ||
          batch.results.length === 0 ||
          pinterestAdded === 0
        ) {
          state.pinterestDone = true;
        } else {
          state.pinterestBookmark = batch.bookmark;
        }
      }
    }
    // No forward progress at all — both streams empty against a live
    // target. Stop instead of spinning the budget away.
    if (state.pending.length === before && !mergeSourcesAlive(state)) break;
  }

  const need = Math.max(target - state.emittedCount, 0);
  const window = state.pending.splice(0, need);
  state.emittedCount += window.length;
  if (window.length === 0 && searxngConfigError) {
    // Nothing was served AND the backbone is misconfigured — surface the
    // deployment problem as a hard 502 instead of a silent empty page.
    throw searxngConfigError;
  }
  const hasMore =
    state.pending.length > 0 ||
    (mergeSourcesAlive(state) && rounds < MERGE_MAX_ROUNDS_PER_REQUEST);
  return { results: window, hasMore };
}

async function searchViaSearXng(params) {
  return runMergedSearch(params);
}

function glToLanguage(gl) {
  if (!gl) return null;
  if (gl.includes('-')) return gl;
  return GL_TO_LANGUAGE[String(gl).toLowerCase()] ?? null;
}

// Map SearXNG image items onto the response DTO the app already parses —
// field names verified against a live instance (search?categories=images
// &format=json): img_src is the original image, resolution arrives as
// "2560x1440" or "2560 x 1440", img_format is an extension OR a mime type.
function searxngItemsToResults(items) {
  const seen = new Set();
  const results = [];
  for (const item of items) {
    const raw = item.img_src;
    const link = Array.isArray(raw) ? String(raw[0] ?? '') : String(raw ?? '');
    if (!/^https?:\/\//i.test(link)) continue;
    // Android's image pipeline cannot decode SVG results.
    const fmt = String(item.img_format || '').toLowerCase();
    if (fmt.includes('svg')) continue;
    if (seen.has(link)) continue;
    seen.add(link);
    const dims = parseResolution(item.resolution);
    results.push({
      id: link,
      original_url: link,
      caption: item.title ? String(item.title) : null,
      // SearXNG's `source` is engine-dependent and often blank — the image
      // host is the honest, always-present provenance.
      source: item.source || hostnameOf(link) || null,
      original_width: dims?.width ?? null,
      original_height: dims?.height ?? null,
    });
  }
  return results;
}

function parseResolution(resolution) {
  const m = /(\d+)\s*[x\u00d7]\s*(\d+)/i.exec(String(resolution ?? ''));
  if (!m) return null;
  const width = parseInt(m[1], 10);
  const height = parseInt(m[2], 10);
  if (!(width > 0 && height > 0)) return null;
  return { width, height };
}

function hostnameOf(url) {
  try {
    return new URL(url).hostname;
  } catch {
    return null;
  }
}

// --- Supabase corpus mirror: helpers ---------------------------------------

// MUST stay byte-identical with the app's CorpusSearchEngine and the
// harvest seeder: trim, lowercase, collapse internal whitespace.
function normalizeQuery(q) {
  return String(q || '').trim().toLowerCase().replace(/\s+/g, ' ');
}

// Tolerant dimension parse — providers emit numbers (SearXNG, Pinterest,
// CSE), strings like "1920px" (z-ai CLI), or junk like "n/a". Mirrors the
// app-side asDimension(): exact positive int or null.
function parseDimension(v) {
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) return Math.round(v);
  const m = /^(\d+)/.exec(String(v ?? '').trim());
  return m ? parseInt(m[1], 10) : null;
}

// Marketplace product shots (amazon/etsy/ebay/walmart/...) are almost never
// good wallpapers - the corpus is curated to exclude them, and the one-shot
// cleanup SQL (download/corpus-cleanup-marketplaces.sql) deletes rows with
// this EXACT pattern set. Keep the two in sync. Blocking only skips the
// MIRROR write; the bridge still serves the result to the phone.
const MIRROR_BLOCKED_MARKET_RE = /(amazon|etsy|ebay|walmart|auctionninja|alibaba|aliexpress|temu|shein)/i;

function isBlockedMarketplace(url, source) {
  const host = hostnameOf(String(url || '')) || '';
  const src = String(source || '');
  return MIRROR_BLOCKED_MARKET_RE.test(host) || MIRROR_BLOCKED_MARKET_RE.test(src);
}

// Maps one provider-agnostic result DTO onto a search_corpus row.
function corpusRowFor(queryNorm, r) {
  const url = String(r.original_url || '');
  if (!/^https?:\/\//i.test(url)) return null;
  if (isBlockedMarketplace(url, r.source)) return null;
  return {
    query_norm: queryNorm,
    content_key: createHash('md5').update(`${queryNorm}|${url}`).digest('hex'),
    title: r.caption ? String(r.caption) : null,
    page_url: null,
    image_url: url,
    thumb_url: null,
    width: parseDimension(r.original_width),
    height: parseDimension(r.original_height),
    source_host: (r.source && String(r.source)) || hostnameOf(url) || null,
    snippet: null,
    scraped_at: new Date().toISOString(),
  };
}

// Fire-and-forget upsert of a served result page. One POST per query at
// a time (merge-duplicates makes repeats idempotent; the in-flight guard
// just keeps double-tapped searches from stacking writes).
function mirrorSearchResults(query, results) {
  if (!SUPABASE_MIRROR || !Array.isArray(results) || results.length === 0) return;
  const qn = normalizeQuery(query);
  if (!qn || mirrorInFlight.has(qn)) return;
  const rows = results.map((r) => corpusRowFor(qn, r)).filter(Boolean).slice(0, MIRROR_MAX_ROWS);
  if (rows.length === 0) return;
  mirrorInFlight.add(qn);
  const release = () => mirrorInFlight.delete(qn);
  // on_conflict=content_key: without it PostgREST upserts on the primary
  // key (uuid, absent from the payload) and the unique constraint would
  // reject re-served rows with 23505.
  fetch(`${SUPABASE_REST}/search_corpus?on_conflict=content_key`, {
    method: 'POST',
    headers: {
      apikey: SUPABASE_SERVICE_KEY,
      authorization: `Bearer ${SUPABASE_SERVICE_KEY}`,
      'content-type': 'application/json',
      prefer: 'resolution=merge-duplicates,return=minimal',
    },
    body: JSON.stringify(rows),
    signal: AbortSignal.timeout(SUPABASE_TIMEOUT_MS),
  })
    .then(async (resp) => {
      if (!resp.ok) {
        const detail = await resp.text().catch(() => '');
        console.warn(`[mirror] supabase answered HTTP ${resp.status}: ${detail.slice(0, 160)}`);
      }
    })
    .catch((err) => console.warn(`[mirror] supabase unreachable: ${err.message}`))
    .finally(release);
}

// Keep-alive ping — Supabase's free tier pauses projects after ~7 idle
// days. Called from /health (any uptime monitor keeps the corpus alive
// for free) and from a long-interval timer as a belt-and-suspenders.
async function pingSupabase() {
  if (!SUPABASE_MIRROR) return;
  try {
    await fetch(`${SUPABASE_REST}/search_corpus?select=id&limit=1`, {
      headers: { apikey: SUPABASE_SERVICE_KEY, authorization: `Bearer ${SUPABASE_SERVICE_KEY}` },
      signal: AbortSignal.timeout(SUPABASE_TIMEOUT_MS),
    });
  } catch (err) {
    console.warn(`[mirror] keep-alive ping failed: ${err.message}`);
  }
}

async function runSearch(params) {
  if (PROVIDER === 'zaicli') return searchViaZaCli(params);
  if (PROVIDER === 'googlecse') return searchViaGoogleCse(params);
  if (PROVIDER === 'searxng') return searchViaSearXng(params);
  throw new Error(
    'no search provider available: run inside the sandbox (z-ai CLI default), set ' +
      'SEARXNG_BASE for the bundled metasearch, or SEARCH_PROVIDER=googlecse with ' +
      'GOOGLE_CSE_KEY + GOOGLE_CSE_CX (see DEPLOY.md)',
  );
}

// --- route handlers ---

async function handleSearch(req, res) {
  let body;
  try {
    body = JSON.parse(await readBody(req));
  } catch {
    sendJson(res, 400, { success: false, error: 'invalid JSON body' });
    return;
  }
  const query = (body.query ?? '').toString().trim();
  const count = Math.min(Math.max(parseInt(body.count, 10) || 20, 1), MAX_RESULTS_PER_PAGE);
  const page = Math.max(parseInt(body.page, 10) || 1, 1);
  const gl = (body.gl ?? 'us').toString();

  if (!query) {
    sendJson(res, 400, { success: false, error: 'query is required' });
    return;
  }

  let outcome;
  try {
    outcome = await runSearch({ query, count, page, gl });
  } catch (err) {
    // Provider failures are bridged with success:false. Quota exhaustion
    // answers 429 (the app maps it to RATE_LIMITED without invalidating
    // the address); anything else is 5xx, which the engine treats as a
    // SERVER error and invalidates the cached address against.
    console.error(`[search] provider (${PROVIDER}) failed for "${query}":`, err.message);
    sendJson(res, err instanceof CseQuotaError ? 429 : 502, {
      success: false,
      query,
      count: 0,
      page,
      hasMore: false,
      results: [],
      error: err.message,
    });
    return;
  }

  if (SUPABASE_MIRROR) {
    mirrorSearchResults(query, outcome.results);
  }

  sendJson(res, 200, {
    success: true,
    query,
    count: outcome.results.length,
    page,
    hasMore: outcome.hasMore,
    results: outcome.results,
    error: null,
  });
}

async function handleProxyImage(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const target = url.searchParams.get('url');
  const width = parseInt(url.searchParams.get('w') || '640', 10);
  const fmt = (url.searchParams.get('fmt') || 'jpeg').toLowerCase();

  if (!target) {
    res.writeHead(400, { 'content-type': 'text/plain' });
    res.end('missing url param');
    return;
  }
  if (!/^https?:\/\//.test(target)) {
    res.writeHead(400, { 'content-type': 'text/plain' });
    res.end('url must be http(s)');
    return;
  }

  // Bandwidth optimization is the proxy's only job — we don't resize
  // locally (would need sharp/jimp). Wikimedia gets the special path:
  // their own thumbnail renders ARE the resize, so a wikimedia original
  // at card width is rewritten to the standard-size thumb and fetched
  // from their CDN at a few hundred KB instead of ~11 MB. Pinterest
  // originals get the same treatment via their /736x/ render. Every other
  // host passes through as before with strong caching headers.
  const cacheKey = `${target}|${width}|${fmt}`;
  const cached = proxyCache.get(cacheKey);
  if (cached) {
    res.writeHead(200, cached.headers);
    res.end(cached.body);
    return;
  }

  const thumbTarget = wikimediaThumbFor(target, width) || pinimgThumbFor(target, width);
  let upstream;
  try {
    upstream = await fetchImage(thumbTarget || target);
  } catch (err) {
    if (!thumbTarget) throw err;
    // The thumb render can miss (an upscale of a smaller original, an odd
    // file type, a pin without that render); the untouched original is
    // still worth serving.
    console.warn(`[proxy] thumbnail miss (${err.message}); serving the original`);
    upstream = await fetchImage(target);
  }
  try {
    const headers = {
      'content-type': upstream.contentType || 'image/jpeg',
      'cache-control': 'public, max-age=86400, immutable',
      'access-control-allow-origin': '*',
      'x-proxy-width': String(width),
    };
    const body = upstream.body;
    proxyCacheSet(cacheKey, { headers, body });
    res.writeHead(200, headers);
    res.end(body);
  } catch (err) {
    console.error(`[proxy] failed for ${target}:`, err.message);
    res.writeHead(502, { 'content-type': 'text/plain' });
    res.end(`proxy error: ${err.message}`);
  }
}

/**
 * Wikimedia's hotlink policy: upload.wikimedia.org only serves /thumb/
 * requests at its production standard widths (see "Common thumbnail
 * sizes": 250, 330, 500, 960, 1280, 1920, 3840) and 403s any request
 * without a descriptive User-Agent (fetchImage sends PROXY_USER_AGENT).
 * Maps an original to the smallest standard width at or above the wanted
 * width — their thumbnail renders are the resize, so the proxy stops
 * paying ~11 MB pass-through originals for card-width requests. Returns
 * null for anything that should keep the pass-through path (non-wikimedia
 * host, already-thumb URL, oversized ask).
 */
function wikimediaThumbFor(target, wantedWidth) {
  const m = WIKIMEDIA_ORIGINAL_PATH.exec(target);
  if (!m) return null;
  const width = WIKIMEDIA_STANDARD_WIDTHS.find((w) => w >= wantedWidth);
  if (!width) return null;
  return `https://upload.wikimedia.org/${m[1]}/thumb/${m[2]}/${m[3]}/${m[4]}/${width}px-${m[4]}`;
}

const WIKIMEDIA_STANDARD_WIDTHS = [250, 330, 500, 960, 1280, 1920, 3840];
const WIKIMEDIA_ORIGINAL_PATH =
  /^https:\/\/upload\.wikimedia\.org\/(wikipedia\/[a-z-]+)\/([0-9a-f])\/([0-9a-f]{2})\/([^/?#]+)$/i;

/**
 * Pinterest's CDN renders every pin at standard widths — /736x/ is the
 * rung the site's own grid rides — and i.pinimg.com serves them to any
 * client (verified live: the same shard path under /736x/ answers 200 at
 * a fraction of the original's bytes). The deep Pinterest stream the
 * search engine now surfaces means hundreds of pinimg tiles, so card-
 * width asks stop paying full-original bytes: an /originals/ URL is
 * rewritten to /736x/, with the fetch-fallback above covering pins whose
 * 736x render is missing. Returns null for anything that should keep the
 * pass-through path (non-pinimg host, already-a-variant URL, an ask
 * wider than the rung can serve).
 */
function pinimgThumbFor(target, wantedWidth) {
  if (wantedWidth > PINIMG_RENDER_WIDTH) return null;
  const m = PINIMG_ORIGINAL_PATH.exec(target);
  if (!m) return null;
  return `https://i.pinimg.com/${PINIMG_RENDER_WIDTH}x/${m[1]}/${m[2]}/${m[3]}/${m[4]}.${m[5]}`;
}

const PINIMG_RENDER_WIDTH = 736;
// i.pinimg.com paths are /<variant>/<shard1>/<shard2>/<shard3>/<filename> —
// three two-hex shards (verified live: .../originals/23/5b/8e/235b8e02....jpg).
const PINIMG_ORIGINAL_PATH =
  /^https:\/\/i\.pinimg\.com\/originals\/([0-9a-f]{2})\/([0-9a-f]{2})\/([0-9a-f]{2})\/([0-9a-f]+)\.(jpe?g|png|webp|gif)$/i;

function fetchImage(targetUrl, redirectsLeft = 4) {
  return new Promise((resolve, reject) => {
    const lib = targetUrl.startsWith('https:') ? https : http;
    const req = lib.get(targetUrl, {
      timeout: 30_000,
      headers: {
        'user-agent': PROXY_USER_AGENT,
        accept: 'image/avif,image/webp,image/apng,image/*;q=0.8,*/*;q=0.5',
        'accept-language': 'en-US,en;q=0.9',
      },
    }, (resp) => {
      // CDNs occasionally bounce us (301/302 to a canonical host, or to a
      // signed URL). Follow a bounded number of hops with the same headers.
      if ([301, 302, 303, 307, 308].includes(resp.statusCode) && resp.headers.location) {
        resp.resume();
        if (redirectsLeft <= 0) {
          reject(new Error('too many redirects'));
          return;
        }
        try {
          const next = new URL(resp.headers.location, targetUrl).toString();
          fetchImage(next, redirectsLeft - 1).then(resolve, reject);
        } catch {
          reject(new Error('invalid redirect target'));
        }
        return;
      }
      if (resp.statusCode !== 200) {
        resp.resume();
        reject(new Error(`upstream status ${resp.statusCode}`));
        return;
      }
      // Guard against soft-block pages served with HTTP 200: only image
      // (or opaque binary) bodies are worth caching and re-serving.
      const contentType = String(resp.headers['content-type'] || '');
      if (contentType && !/^image\//i.test(contentType) && !/octet-stream/i.test(contentType)) {
        resp.resume();
        reject(new Error(`upstream content-type ${contentType}`));
        return;
      }
      const chunks = [];
      resp.on('data', (chunk) => chunks.push(chunk));
      resp.on('end', () => {
        resolve({
          body: Buffer.concat(chunks),
          contentType: contentType || 'image/jpeg',
        });
      });
    });
    req.on('error', reject);
    req.on('timeout', () => {
      req.destroy(new Error('timeout'));
    });
  });
}

// --- server ---

const server = http.createServer(async (req, res) => {
  // CORS preflight
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET, POST, OPTIONS',
      'access-control-allow-headers': 'content-type',
      'access-control-max-age': '86400',
    });
    res.end();
    return;
  }

  const url = new URL(req.url, `http://${req.headers.host}`);
  try {
    if (req.method === 'POST' && url.pathname === '/api/search') {
      await handleSearch(req, res);
    } else if (req.method === 'GET' && url.pathname === '/api/proxy-image') {
      await handleProxyImage(req, res);
    } else if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/health')) {
      if (SUPABASE_MIRROR) pingSupabase(); // free keep-alive rides any monitor
      sendJson(res, 200, {
        ok: true,
        service: 'cloudimage-search-bridge',
        provider: PROVIDER,
        searxngConfigured: Boolean(SEARXNG_BASE),
        pinterestEngine: PROVIDER === 'searxng' && PINTEREST_ENGINE,
        googleCseConfigured: Boolean(process.env.GOOGLE_CSE_KEY && process.env.GOOGLE_CSE_CX),
        supabaseMirror: SUPABASE_MIRROR,
      });
    } else {
      sendJson(res, 404, { error: 'not found', path: url.pathname });
    }
  } catch (err) {
    console.error('[unhandled]', err);
    if (!res.headersSent) {
      sendJson(res, 500, { error: 'internal', detail: err.message });
    }
  }
});

// server.js doubles as the container entrypoint AND a module the tests can
// import for its pure helpers — the listener (and the SIGTERM handler that
// goes with it) belongs to the entrypoint role only.
if (import.meta.url === pathToFileURL(process.argv[1] || '').href) {
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`[cloudimage-search-bridge] listening on :${PORT}`);
    console.log(`  search provider: ${PROVIDER}${PROVIDER === 'searxng' ? ` (${SEARXNG_BASE})` : ''}`);
    if (PROVIDER === 'searxng') {
      console.log(
        `  pinterest engine: ${PINTEREST_ENGINE ? 'on (deep bookmark paging)' : 'OFF'} ` +
          `— up to ${PINTEREST_MAX_PAGES} pages x ${PINTEREST_PAGE_SIZE} pins per query`,
      );
    }
    if (PROVIDER === 'none') {
      console.log('  (!) no provider configured — searches will 502 until one is available:');
      console.log('      SEARXNG_BASE (bundled metasearch, recommended) or SEARCH_PROVIDER=googlecse');
      console.log('      plus GOOGLE_CSE_KEY / GOOGLE_CSE_CX (see DEPLOY.md)');
    }
    console.log(`  POST /api/search        — image search (${PROVIDER})`);
    console.log(`  GET  /api/proxy-image   — image proxy with caching`);
    console.log(`  GET  /health            — health check`);
    console.log(
      `  corpus mirror: ${SUPABASE_MIRROR ? 'ON -> ' + SUPABASE_URL_ENV : 'OFF (set SUPABASE_URL + SUPABASE_SERVICE_KEY to enable)'}`,
    );
    if (SUPABASE_MIRROR) {
      // 6 days < Supabase's 7-day idle pause. unref: the timer must never
      // hold the process open on shutdown.
      const keepAliveMs = parseInt(process.env.SUPABASE_KEEPALIVE_HOURS || '144', 10) * 3600_000;
      setInterval(() => pingSupabase(), keepAliveMs).unref();
    }
  });

  process.on('SIGTERM', () => {
    console.log('[bridge] SIGTERM received, shutting down');
    server.close(() => process.exit(0));
  });
}

export { wikimediaThumbFor, pinimgThumbFor, pinterestItemsToResults, normalizeQuery, corpusRowFor, parseDimension, isBlockedMarketplace };
