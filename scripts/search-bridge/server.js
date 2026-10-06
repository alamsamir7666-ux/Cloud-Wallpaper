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
//                DEPLOY.md.
//   googlecse  — the Google Programmable Search JSON API with image
//                search enabled, driven by your own API key. Free tier
//                is capped at 100 queries/day — kept as an option, not
//                the recommendation.

import { spawn, spawnSync } from 'node:child_process';
import http from 'node:http';
import https from 'node:https';
import { URL } from 'node:url';
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

// Upstream image hosts are picky about clients, each in its own way
// (verified live against Wikimedia, the largest wallpaper source in the
// results): UA-less requests get 403 by policy, generic browser UAs get
// rate-limited (429) from datacenter IPs, and a descriptive UA with
// contact info sails through (200). Env-overridable for future tuning.
const PROXY_USER_AGENT = process.env.PROXY_USER_AGENT ||
  'CloudImageBridge/1.0 (https://github.com/alamsamir7666-ux/Cloud-Wallpaper; image thumbnail proxy)';

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
// and answers even when some of them fail. Pagination rides the engines'
// own `pageno` support, so every page surfaces a fresh batch (the caller
// dedupes by URL).
async function searchViaSearXng({ query, count, page, gl }) {
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

  let body;
  const resp = await fetch(`${SEARXNG_BASE}/search?${params}`, {
    headers: { accept: 'application/json' },
    signal: AbortSignal.timeout(SEARXNG_TIMEOUT_MS),
  });
  const bodyText = await resp.text();
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
  const results = searxngItemsToResults(items);
  return {
    results: results.slice(0, count),
    hasMore: page < SEARXNG_MAX_PAGE && results.length > 0,
  };
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

  // Bandwidth optimization is the proxy's only job — we don't actually
  // resize (would need sharp/jimp), we just pass through with strong
  // caching headers. The OSS originals are already right-sized for most
  // thumbnail contexts; the width param is honored as a hint for future
  // optimization without breaking the contract.
  const cacheKey = `${target}|${width}|${fmt}`;
  const cached = proxyCache.get(cacheKey);
  if (cached) {
    res.writeHead(200, cached.headers);
    res.end(cached.body);
    return;
  }

  try {
    const upstream = await fetchImage(target);
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
      sendJson(res, 200, {
        ok: true,
        service: 'cloudimage-search-bridge',
        provider: PROVIDER,
        searxngConfigured: Boolean(SEARXNG_BASE),
        googleCseConfigured: Boolean(process.env.GOOGLE_CSE_KEY && process.env.GOOGLE_CSE_CX),
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

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[cloudimage-search-bridge] listening on :${PORT}`);
  console.log(`  search provider: ${PROVIDER}${PROVIDER === 'searxng' ? ` (${SEARXNG_BASE})` : ''}`);
  if (PROVIDER === 'none') {
    console.log('  (!) no provider configured — searches will 502 until one is available:');
    console.log('      SEARXNG_BASE (bundled metasearch, recommended) or SEARCH_PROVIDER=googlecse');
    console.log('      plus GOOGLE_CSE_KEY / GOOGLE_CSE_CX (see DEPLOY.md)');
  }
  console.log(`  POST /api/search        — image search (${PROVIDER})`);
  console.log(`  GET  /api/proxy-image   — image proxy with caching`);
  console.log(`  GET  /health            — health check`);
});

process.on('SIGTERM', () => {
  console.log('[bridge] SIGTERM received, shutting down');
  server.close(() => process.exit(0));
});
