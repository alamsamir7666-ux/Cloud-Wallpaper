// Cloudimage global-search bridge.
//
// A small HTTP server that exposes two routes the Cloud-Wallpaper Android
// app expects, backed by the z-ai-web-dev-sdk's `image-search` CLI:
//
//   POST /api/search            — JSON body {query,count,page,gl} → JSON
//   GET  /api/proxy-image       — ?url=&w=&q=&fmt= → image/jpeg
//
// The SDK itself runs as a child process (the z-ai CLI is the only
// supported entry point per skills/image-search/SKILL.md). Image
// reachability is handled upstream: every original_url is OSS-hosted
// (sfile.chatglm.cn), so the proxy is a bandwidth optimization, not a
// reachability fix.

import { spawn } from 'node:child_process';
import http from 'node:http';
import https from 'node:https';
import { URL } from 'node:url';
import { lookup } from 'node:dns/promises';

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;
const MAX_RESULTS_PER_PAGE = 20;
const UPSTREAM_TIMEOUT_MS = 120_000;

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
const PROXY_CACHE_MAX = 200;

function proxyCacheSet(key, value) {
  if (proxyCache.size >= PROXY_CACHE_MAX) {
    // Evict oldest entry — Map preserves insertion order.
    const oldest = proxyCache.keys().next().value;
    proxyCache.delete(oldest);
  }
  proxyCache.set(key, value);
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
function callImageSearch({ query, count, gl }) {
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

  // Page > 1 appends a modifier to surface a fresh batch.
  const effectiveQuery = page > 1
    ? `${query} ${PAGE_MODIFIERS[(page - 2) % PAGE_MODIFIERS.length]}`
    : query;

  let upstream;
  try {
    upstream = await callImageSearch({ query: effectiveQuery, count, gl });
  } catch (err) {
    // SDK failures are bridged as 5xx with success:false — the engine
    // treats 5xx as SERVER error and invalidates the cached address.
    console.error(`[search] upstream failed for "${effectiveQuery}":`, err.message);
    sendJson(res, 502, {
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

  // Forward the SDK's response shape almost verbatim — just normalize
  // hasMore (the SDK doesn't have it; we infer from page < modifier count).
  const hasNextPage = page < PAGE_MODIFIERS.length + 1;
  sendJson(res, 200, {
    success: upstream.success !== false,
    query,
    count: upstream.results?.length ?? 0,
    page,
    hasMore: hasNextPage && (upstream.results?.length ?? 0) > 0,
    results: upstream.results ?? [],
    error: upstream.error,
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

function fetchImage(targetUrl) {
  return new Promise((resolve, reject) => {
    const lib = targetUrl.startsWith('https:') ? https : http;
    const req = lib.get(targetUrl, { timeout: 30_000 }, (resp) => {
      if (resp.statusCode !== 200) {
        reject(new Error(`upstream status ${resp.statusCode}`));
        return;
      }
      const chunks = [];
      resp.on('data', (chunk) => chunks.push(chunk));
      resp.on('end', () => {
        resolve({
          body: Buffer.concat(chunks),
          contentType: resp.headers['content-type'],
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
      sendJson(res, 200, { ok: true, service: 'cloudimage-search-bridge' });
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
  console.log(`  POST /api/search        — image search via z-ai SDK`);
  console.log(`  GET  /api/proxy-image   — image proxy with caching`);
  console.log(`  GET  /health            — health check`);
});

process.on('SIGTERM', () => {
  console.log('[bridge] SIGTERM received, shutting down');
  server.close(() => process.exit(0));
});
