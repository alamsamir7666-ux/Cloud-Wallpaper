# Cloudimage Search Bridge

A small, dependency-free Node.js HTTP server that exposes global image
search to the Cloud-Wallpaper Android app as two HTTPS routes.

The search behind `/api/search` is provider-pluggable:

- **`searxng`** *(recommended for permanent hosting)* — a self-hosted
  SearXNG metasearch instance, bundled into the same Docker image
  (`Dockerfile.searxng`) on the container loopback. No API keys, no
  quotas, no limits: it aggregates Bing, DuckDuckGo, Qwant, Openverse,
  Wikimedia, ... in parallel and degrades gracefully when one engine
  misbehaves. On top of the metasearch pages, the bridge drives
  **Pinterest's own search API directly** (bookmark continuation, kept
  in a small per-query state) so image queries go ~300 results deep
  instead of stalling on SearXNG's duplicate `pageno` batches — see
  “The Pinterest deep-paging engine” below. See [DEPLOY.md](DEPLOY.md).
- **`zaicli`** — the z-ai image-search CLI. Zero-config inside the
  sandbox (it authenticates with chat-scoped session credentials), but
  those credentials die with the sandbox — not deployable elsewhere.
- **`googlecse`** — the Google Programmable Search JSON API with image
  search enabled, driven by your own API key (free tier: 100
  queries/day). Kept as an option; see [DEPLOY.md](DEPLOY.md).

Selection is automatic (`z-ai` binary on PATH → `zaicli`; otherwise
`SEARXNG_BASE` → `searxng`; otherwise CSE keys → `googlecse`) and can be
forced with `SEARCH_PROVIDER=zaicli|searxng|googlecse`.

## Routes

- `POST /api/search` — body `{"query","count","page","gl"}` → JSON
  `{"success","query","count","page","hasMore","results":[…]}`. Pages are
  windows into one merged per-query stream (SearXNG's broad pages + the
  deep Pinterest stream); windows are disjoint by URL, and `hasMore` is
  only true while a live source remains.
- `GET  /api/proxy-image?url=&w=&q=&fmt=` — proxies the image with strong
  caching headers. Bandwidth optimization only: Wikimedia originals are
  rewritten to their own standard-width thumb renders, Pinterest
  originals to the `/736x/` render (both with fallback to the untouched
  original); everything else passes through as-is.
- `GET  /health` — returns `{"ok":true,"service":…,"provider":…,
  "pinterestEngine":…}`.

## Run (sandbox / z-ai provider)

```bash
# 1. Install Node 18+ and the z-ai-web-dev-sdk CLI globally
npm install -g z-ai-web-dev-sdk

# 2. Start the bridge
cd scripts/search-bridge
node server.js

# 3. (Optional) Expose it publicly via Cloudflare Quick Tunnel
cloudflared tunnel --url http://localhost:3000
```

## Run (permanent host / SearXNG provider — unlimited, keyless)

See [DEPLOY.md](DEPLOY.md) — the combined image (`Dockerfile.searxng`:
SearXNG + bridge in one container), a `render.yaml` blueprint (Render,
no credit card, recommended) and `fly.toml` (Fly.io, faster wake) are
all in place. The whole deploy needs **no secrets, no API keys**; the
optional Supabase corpus mirror below adds two env vars.

The bridge listens on `$PORT` (default 3000) on all interfaces. Tests:
`npm test` (stubs the SearXNG, Pinterest and Google APIs — no
credentials, no running engine needed).

## The Supabase corpus mirror

Every successful `/api/search` upserts its served rows into a shared
Supabase table (`public.search_corpus`) — the corpus behind the app's
instant, keyless corpus search lane (v1.2.23+). The write path uses the
**service_role key** held as a host env var (never shipped in the app);
reads from phones are anonymous and RLS-locked to SELECT. Mirroring is
fire-and-forget — a missing, paused, or failing Supabase never slows or
breaks a search — and re-served rows refresh `scraped_at`, which doubles
as each URL's liveness heartbeat for TTL governance.

Enable by setting two env vars on the host (see DEPLOY.md):

- `SUPABASE_URL` — project root, e.g. `https://<ref>.supabase.co`
- `SUPABASE_SERVICE_KEY` — the service_role key (server-side only)

Knobs: `SUPABASE_TIMEOUT_MS` (default 10 s),
`SUPABASE_KEEPALIVE_HOURS` (default 144 = 6 days; Supabase's free tier
pauses projects after ~7 idle days, and every `/health` hit also pings
the corpus — so any uptime monitor keeps it alive for free).

Local smoke test (needs all three env vars, writes to the real table
and cleans up after itself): `node test-supabase-mirror.mjs`.

**Marketplace blocklist**: rows whose image host or source label matches
`amazon | etsy | ebay | walmart | auctionninja | alibaba | aliexpress |
temu | shein` are never mirrored (product shots are rarely good
wallpapers). The bridge still *serves* those results — only the corpus
write is skipped. The cleanup SQL that purged the seed corpus
(`download/corpus-cleanup-marketplaces.sql`) uses the identical pattern
set; keep the two in sync when editing either.

## The Pinterest deep-paging engine

The app's search protocol is stateless (`{query,count,page}`), but
Pinterest's search API paginates with an opaque `bookmark` continuation
token, and SearXNG's own pinterest engine never receives one through the
JSON API — which is why “load more” used to serve the same 18 images
forever. The bridge now keeps a small per-query state (bookmark chain +
URL ledger + pending buffer; LRU-capped, TTL'd, reset by any page-1
request) and serves `count`-sized windows of one merged stream: the
SearXNG first page keeps the opening screen broad, and the Pinterest
stream supplies the depth — up to `PINTEREST_MAX_PAGES` (12) pages of
`PINTEREST_PAGE_SIZE` (25) pins, ~300 images per query.

A mid-scroll service restart (free-tier sleep) is absorbed by a bounded
rebuild: the fill loop replays the chain from page 1 within a per-request
round budget. If Pinterest blocks the host outright, the engine marks
itself done and the search degrades to the SearXNG-only behavior.

Env knobs (all default-on): `PINTEREST_ENGINE` (`0` disables),
`PINTEREST_BASE`, `PINTEREST_PAGE_SIZE`, `PINTEREST_MAX_PAGES`,
`PINTEREST_TIMEOUT_MS`, `PINTEREST_USER_AGENT`.

## Repointing the app

Once the bridge is reachable at a public URL, edit
`search-backend.json` at the repo root:

```json
{ "baseUrl": "https://your-bridge-url.example.com" }
```

Installed apps pick up the change on their **next search** (the v1.2.1
config fetch has a 10-minute TTL and is invalidated on any bridge
failure, so a republished JSON heals apps without an app update).

## Why this design

- **No external database** — search results are not persisted; the SDK
  is the source of truth.
- **No image resizing** — the proxy is pass-through with caching. Adding
  `sharp` or `jimp` would let us honor the `w=` param properly, but the
  OSS originals are already reasonable size for thumbnails.
- **Providers are isolated behind one contract** — the sandbox provider
  spawns the `z-ai` CLI as a child process and parses its JSON stdout
  (stripping the emoji-decorated progress lines it prints); the SearXNG
  provider queries a self-hosted metasearch instance; the hosted Google
  provider calls the Programmable Search HTTP API directly. All three
  normalize to the same response DTO, so the phone cannot tell them
  apart.
- **Failure modes are bridged, not swallowed** — provider failures return
  `502` with `success:false` (the engine treats 5xx as `SERVER` errors
  and invalidates the cached address); Google quota exhaustion returns
  `429`, which the app maps to `RATE_LIMITED` without invalidating the
  address — a quota day is not a dead bridge.
