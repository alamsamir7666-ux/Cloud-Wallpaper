# Cloudimage Search Bridge

A small Node.js HTTP server that exposes the z-ai-web-dev-sdk's
`image-search` CLI as two HTTPS routes the Cloud-Wallpaper Android app
expects.

The z-ai image-search service is reachable only from inside this sandbox
— it has no public address a phone could call. This bridge IS the public
face: it exposes the search over plain HTTPS so the app can reach it
from anywhere. The image URLs the SDK returns are OSS-hosted
(`z-cdn.chatglm.cn`) and are directly reachable from phones, so the
bridge only needs to handle search and image-proxy requests.

## Routes

- `POST /api/search` — body `{"query","count","page","gl"}` → JSON
  `{"success","query","count","page","hasMore","results":[…]}`. Page > 1
  appends a query modifier (`hd`, `high resolution`, `wallpaper`, `4k`…)
  to surface a fresh batch; the caller deduplicates by URL.
- `GET  /api/proxy-image?url=&w=&q=&fmt=` — proxies the image with strong
  caching headers. Bandwidth optimization only; the OSS originals are
  already right-sized for most thumbnail contexts.
- `GET  /health` — returns `{"ok":true,"service":"cloudimage-search-bridge"}`.

## Run

```bash
# 1. Install Node 20+ and the z-ai-web-dev-sdk CLI globally
npm install -g z-ai-web-dev-sdk

# 2. Start the bridge
cd scripts/search-bridge
node server.js

# 3. (Optional) Expose it publicly via Cloudflare Quick Tunnel
cloudflared tunnel --url http://localhost:3000
```

The bridge listens on port 3000 by default. Override with `PORT=8080 node server.js`.

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
- **The CLI is the only entry point** — per the image-search skill docs,
  the SDK is invoked via the `z-ai` binary, not via a JS import. This
  bridge spawns it as a child process and parses the JSON stdout
  (stripping the emoji-decorated progress lines the CLI prints).
- **Failure modes are bridged, not swallowed** — SDK failures return
  `502` with `success:false`, so the engine treats them as `SERVER`
  errors and invalidates the cached address.
