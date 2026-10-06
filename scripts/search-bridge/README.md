# Cloudimage Search Bridge

A small, dependency-free Node.js HTTP server that exposes global image
search to the Cloud-Wallpaper Android app as two HTTPS routes.

The search behind `/api/search` is provider-pluggable:

- **`searxng`** *(recommended for permanent hosting)* — a self-hosted
  SearXNG metasearch instance, bundled into the same Docker image
  (`Dockerfile.searxng`) on the container loopback. No API keys, no
  quotas, no limits: it aggregates Bing, DuckDuckGo, Qwant, Openverse,
  Wikimedia, ... in parallel and degrades gracefully when one engine
  misbehaves. See [DEPLOY.md](DEPLOY.md).
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
  `{"success","query","count","page","hasMore","results":[…]}`. Page > 1
  appends a query modifier (`hd`, `high resolution`, `wallpaper`, `4k`…)
  to surface a fresh batch; the caller deduplicates by URL.
- `GET  /api/proxy-image?url=&w=&q=&fmt=` — proxies the image with strong
  caching headers. Bandwidth optimization only; the OSS originals are
  already right-sized for most thumbnail contexts.
- `GET  /health` — returns `{"ok":true,"service":…,"provider":"zaicli|googlecse"}`.

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
SearXNG + bridge in one container), `fly.toml` (Fly.io, recommended)
and a `render.yaml` blueprint (fallback) are all in place. The whole
deploy is two commands and needs **no secrets, no API keys**.

The bridge listens on `$PORT` (default 3000) on all interfaces. Tests:
`npm test` (stubs both the SearXNG and Google APIs — no credentials,
no running engine needed).

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
