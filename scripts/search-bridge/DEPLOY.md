# Deploying the search bridge permanently (Render, no credit card)

The bridge that powers the app's global search has lived inside the
authoring sandbox until now, behind an address that dies whenever the
sandbox sleeps, recycles, or rolls back its filesystem. This guide moves
it to a host **you own**, so the app's global search stops being mortal.

Render's free plan needs **no credit card**: 512 MB RAM / 0.1 CPU, 750
instance-hours per month (enough for one always-resumable service), and
Docker builds run on Render's remote builder. The trade-off is the
15-minute idle sleep — see "The honest limits" below.

## The design: unlimited, keyless search

The bridge's search is provided by a **self-hosted SearXNG** metasearch
engine, bundled into the same Docker container as the bridge itself:

- **No API keys, no quotas, no daily limits** — SearXNG aggregates many
  image engines in parallel (Bing, DuckDuckGo, Qwant, Openverse,
  Wikimedia, Google-CSE scrape, …). When one engine blocks or breaks,
  the rest still answer — the instance observed 118 results on a query
  where three engines were simultaneously failing.
- **One container, one URL** — SearXNG listens only on the container
  loopback (:8080); the bridge is the public surface and speaks the exact
  same JSON the app already parses. No app update is ever needed.
- **Nothing to configure** — the deploy needs no secrets, no API keys,
  no Google anything. Memory is pre-calibrated: SearXNG measures
  ~175–220 MB RSS, the bridge runs a 64 MB V8 heap cap plus an 80 MB
  image-cache budget — together well inside the free 512 MB.

The alternatives deliberately NOT recommended:
[Google Programmable Search](#fallback-option-google-cse) (free tier caps
at 100 queries/day ≈ 50 app pages) and the z-ai CLI (its credentials are
chat-scoped and die with the sandbox — undeployable).

## Deploy on Render — step by step (≈5 minutes)

The repo already carries the blueprint (`render.yaml` at the repo root,
which points at `scripts/search-bridge/Dockerfile.searxng`).

1. **Open the blueprint picker** — sign in at
   [dashboard.render.com](https://dashboard.render.com), click
   **New → Blueprint**.
2. **Connect the repo** — if you haven't already, install Render's
   GitHub App and grant it access to `alamsamir7666-ux/Cloud-Wallpaper`.
   Pick that repo in the list, click **Next**.
3. **Apply the blueprint** — Render reads `render.yaml` and shows one
   service: `cloudimage-search` (Docker, free plan, Singapore region,
   no secret fields to fill). Click **Apply**.
4. **Watch the build** — the service page's **Events** tab shows the
   Docker build (pulls the SearXNG image, copies the Node binary —
   usually 3–8 minutes on the free builder). **Logs** shows runtime
   output once it boots.
5. **Note your URL** — shown at the top of the service page, e.g.
   `https://cloudimage-search.onrender.com` (Render appends a random
   suffix if the name is taken — always copy the URL from the page).
6. **Verify** (from any terminal, or a browser for /health):
   ```bash
   curl https://YOUR-URL.onrender.com/health
   # {"ok":true,"service":"cloudimage-search-bridge","provider":"searxng",...}

   curl -X POST https://YOUR-URL.onrender.com/api/search \
     -H 'content-type: application/json' \
     -d '{"query":"mountain wallpaper","count":5}'
   # {"success":true,...,"results":[{...original_url,original_width,...}]}
   ```
   The first request after a sleep takes ~30–60 s (the box waking);
   subsequent ones answer in ~1–3 s.

## Cut the app over (the last step, after the bridge answers)

Edit `search-backend.json` at the repository root:

```json
{ "baseUrl": "https://YOUR-URL.onrender.com" }
```

Commit and push. Every installed app picks it up on its **next search**:
the v1.2.1 config fetch re-reads this file after any bridge failure and
at least every 10 minutes. No app update, no reinstall.

## The honest limits (free plan)

- **15-minute sleep**: after 15 idle minutes the service stops; the next
  request pays ~30–60 s to wake it. The app's 160 s call timeout absorbs
  this (the search succeeds — it just feels slow the first time). A
  later upgrade to Starter ($7/mo, card required) removes the sleep if
  it ever bothers you.
- **Metasearch quality wobble**: an engine occasionally throttles or
  breaks; the others still answer. Fewer results sometimes, never a
  wall, never a quota error. No action needed — `Logs` shows which
  engines were unresponsive per query.
- **Engine restarts inside the container**: if SearXNG's process ever
  crashes, its watchdog restarts it in ~2 s; the bridge answers 502 for
  the gap, which the app treats as a transient failure and retries.
- **750 instance-hours/month** ≈ 31 days — one always-resumable service
  fits. (Render only counts the hours the service is *running*.)

## Troubleshooting

- **Build fails on "Dockerfile not found"** — make sure the repo's
  `render.yaml` is at the repo root and points at
  `./scripts/search-bridge/Dockerfile.searxng` (it does on `main`).
- **`/health` says `"provider":"none"`** — the env `SEARCH_PROVIDER`
  didn't reach the container; check the service's Environment settings.
- **Search answers `success:false` mentioning SearXNG JSON/403** —
  engine died/restarting; retry, and check Logs.
- **Container OOM-killed (Exits 137 in Events)** — lower
  `PROXY_CACHE_BUDGET_MB` (Environment → edit, e.g. `48`) and redeploy.
- **Manual redeploy** — Dashboard → service → **Manual Deploy → Deploy
  latest commit** (Render also auto-deploys on every push to `main`).

## Alternative: Fly.io (faster wake, card required)

Fly wakes a sleeping machine in ~2–5 s versus Render's ~30–60 s, and
offers the Mumbai region (~20–40 ms from Bangladesh) — but it requires
a credit card even for free-tier usage. If that's ever acceptable:

```bash
cd scripts/search-bridge
fly auth login && fly apps create cloudimage-search && fly deploy
```

Same keyless image, same verification, same `search-backend.json`
cutover — the URL just ends in `.fly.dev` instead. The shipped
`fly.toml` runs one 512 MB machine with auto-stop (cents/month at
personal usage).

## Fallback option: Google CSE

If you ever prefer a fixed-per-query provider over metasearch: create a
[Programmable Search engine](https://programmablesearchengine.google.com/controlpanel/create)
(entire web ON, **Image search ON**, copy the engine ID) and an API key
with the Custom Search API enabled, then deploy the plain `Dockerfile`
instead (`fly.toml`: `dockerfile = "Dockerfile"`) with:

```bash
fly secrets set GOOGLE_CSE_KEY=YOUR_KEY GOOGLE_CSE_CX=YOUR_CX
fly deploy
```

Free tier: 100 queries/day (one app page = 20 results = 2 queries).
Quota exhaustion answers HTTP 429 — the app shows "rate limited" and
recovers when the quota day rolls over. Never commit keys to this repo.
