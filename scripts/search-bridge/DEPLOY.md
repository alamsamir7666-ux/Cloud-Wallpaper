# Deploying the search bridge permanently (Fly.io recommended)

The bridge that powers the app's global search has lived inside the
authoring sandbox until now, behind an address that dies whenever the
sandbox sleeps, recycles, or rolls back its filesystem. This guide moves
it to a host **you own**, so the app's global search stops being mortal.

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
- **Nothing to configure** — the deploy needs no secrets, no accounts
  beyond Fly itself, no Google anything.

The alternatives deliberately NOT recommended:
[Google Programmable Search](#fallback-option-google-cse) (free tier caps
at 100 queries/day ≈ 50 app pages) and the z-ai CLI (its credentials are
chat-scoped and die with the sandbox — undeployable).

## Why Fly.io over Render

| | Fly.io | Render (free plan) |
|---|---|---|
| Idle behavior | machine sleeps, wakes on the next request | service sleeps after 15 idle minutes |
| Wake-up latency | ~2–5 s | ~30–60 s — the first search after a pause feels broken |
| Region | Mumbai (`bom`), ~20–40 ms from Bangladesh | nearest available is Singapore |
| Cost | ~$0–2/mo with auto-stop; $0 always-on if your account has the legacy hobby allowance | $0 (with sleep) or $7/mo Starter to stay awake |

Global search is an interactive, on-demand call. A backend that takes a
minute to answer the first search after idle is a backend users will
report as broken. Fly wakes in seconds; that is the whole decision.

## Deploy on Fly.io (two commands)

Install [flyctl](https://fly.io/docs/flyctl/install/), then from **this
directory** (`scripts/search-bridge/`):

```bash
fly auth login

# The app name must be globally unique on fly.dev. If cloudimage-search
# is taken, pick another one and edit fly.toml to match.
fly apps create cloudimage-search

fly deploy
```

`fly deploy` builds the combined Dockerfile on Fly's remote builder — no
local Docker needed. Verify:

```bash
curl https://cloudimage-search.fly.dev/health
# {"ok":true,"service":"cloudimage-search-bridge","provider":"searxng",...}

curl -X POST https://cloudimage-search.fly.dev/api/search \
  -H 'content-type: application/json' \
  -d '{"query":"mountain wallpaper","count":5}'
```

### Cost control

The shipped `fly.toml` runs a single 256 MB shared-CPU machine with
`auto_stop_machines = true`: it sleeps when idle, wakes in a few seconds
on the next request, and a stopped machine costs nothing. Check the
billing page for your account:

- **Legacy hobby allowance present** (3 tiny always-on VMs) — flip to
  always-on in `fly.toml` for zero cold start at zero cost:
  `auto_stop_machines = false`, `min_machines_running = 1`.
- **Pay-as-you-go only** — keep auto-stop (default) and expect cents per
  month at personal usage.

## Cut the app over (the last step, after the bridge answers)

Edit `search-backend.json` at the repository root:

```json
{ "baseUrl": "https://cloudimage-search.fly.dev" }
```

Commit and push. Every installed app picks it up on its **next search**:
the v1.2.1 config fetch re-reads this file after any bridge failure and
at least every 10 minutes. No app update, no reinstall.

## Troubleshooting

- **Search returns fewer results than usual** — an upstream engine is
  throttling or broken; the others still answer (this is the graceful
  degradation by design). `fly logs` shows which engines were
  unresponsive per query. No action needed.
- **Search answers `success:false` mentioning SearXNG** — the engine
  process inside the container died and is restarting (watchdog, ~2 s).
  Retry the search.
- **Container OOM-killed / restarts repeatedly** — bump the machine:
  edit `[[vm]]` in `fly.toml` to `memory = "512mb"` and `fly deploy`.
- **`fly deploy` fails on region `bom`** — edit `primary_region` in
  `fly.toml` to `sin` (Singapore).
- **`fly logs`** streams the bridge + engine logs; **`fly status`** shows
  the machine state; **`fly machines start`** boots a stopped machine
  manually.
- **Want the sandbox (z-ai) provider for local dev?** — it auto-selects
  wherever the `z-ai` CLI is on PATH; see README.md.

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
