# Deploying the search bridge permanently (Fly.io recommended)

The bridge that powers the app's global search has lived inside the
authoring sandbox until now, behind an address that dies whenever the
sandbox is recycled. This guide moves it to a host **you own**, so the
app's global search stops being mortal.

## Why Fly.io over Render

| | Fly.io | Render (free plan) |
|---|---|---|
| Idle behavior | machine sleeps, wakes on the next request | service sleeps after 15 idle minutes |
| Wake-up latency | ~2–5 s | ~30–60 s — the first search after a pause feels broken |
| Region | Mumbai (`bom`), ~20–40 ms from Bangladesh | nearest available is Singapore |
| Cost | ~$0–2/mo with auto-stop; $0 always-on if your account has the legacy hobby allowance | $0 (with sleep) or $7/mo Starter to stay awake |
| Environment | this Dockerfile, pinned forever | managed Node runtime |

Both platforms keep a stable `*.fly.dev` / `*.onrender.com` URL for the
life of the account, and the app re-reads `search-backend.json` after
every bridge failure and at least every 10 minutes — so the backend
address is always one committed JSON line away. The decisive difference
is the sleep: global search is an interactive, on-demand call. A backend
that takes a minute to answer the first search after idle is a backend
users will report as broken. Fly wakes in seconds; that is the whole
decision.

## The one thing that must change first: the search provider

The bridge's original provider (the z-ai image-search CLI) authenticates
with sandbox-session credentials. Copying those credentials onto Fly or
Render would just relocate the mortality — the token dies with the chat.
The permanent provider is **Google Programmable Search** with image
search enabled, driven by **your own API key**: free for 100 queries per
day, which is ~50 app search pages per day (one page = 20 results =
2 queries).

### One-time: create your search engine + key (≈5 minutes)

1. **Search engine ID (`GOOGLE_CSE_CX`)** —
   <https://programmablesearchengine.google.com/controlpanel/create>
   - Name it anything (e.g. `cloudimage`).
   - Enable **Search the entire web** (leave the sites list empty).
   - After creation, open the engine's settings and switch **Image
     search ON**.
   - Copy the **Search engine ID**.
2. **API key (`GOOGLE_CSE_KEY`)** — <https://console.cloud.google.com/>
   - Enable the **Custom Search API** (APIs & Services → Library).
   - Credentials → Create credentials → **API key**.
   - Recommended: restrict the key to the Custom Search API.

Both values are secrets — set them as platform secrets below, and never
commit them to this repository.

## Deploy on Fly.io

Install [flyctl](https://fly.io/docs/flyctl/install/), then from **this
directory** (`scripts/search-bridge/`):

```bash
fly auth login

# The app name must be globally unique on fly.dev. If cloudimage-search
# is taken, pick another one and edit fly.toml to match.
fly apps create cloudimage-search

fly secrets set GOOGLE_CSE_KEY=YOUR_KEY GOOGLE_CSE_CX=YOUR_CX

fly deploy
```

`fly deploy` builds the Dockerfile on Fly's remote builder — no local
Docker needed. Verify:

```bash
curl https://cloudimage-search.fly.dev/health
# {"ok":true,"service":"cloudimage-search-bridge","provider":"googlecse",...}

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

## Deploy on Render instead (fallback)

Render blueprints read `render.yaml` at the repository root: dashboard →
**New → Blueprint** → select this repo → apply, then fill in
`GOOGLE_CSE_KEY` / `GOOGLE_CSE_CX` in the service's environment. Accept
the trade: on the free plan the service sleeps after 15 idle minutes and
the first search after that takes ~30–60 s to answer. The app's 160 s
call timeout survives it, but the wait feels broken — which is exactly
why Fly.io is the recommendation.

## Cut the app over (the last step, after the bridge answers)

Edit `search-backend.json` at the repository root:

```json
{ "baseUrl": "https://cloudimage-search.fly.dev" }
```

Commit and push. Every installed app picks it up on its **next search**:
the v1.2.1 config fetch re-reads this file after any bridge failure and
at least every 10 minutes. No app update, no reinstall.

## Troubleshooting

- **App shows "rate limited" / search answers HTTP 429** — the free 100
  queries/day are spent (each search page costs 2). Wait for the quota
  day to roll over, or add billing to the Google project ($5 per 1,000
  extra queries).
- **`fly deploy` fails on region `bom`** — edit `primary_region` in
  `fly.toml` to `sin` (Singapore).
- **Search answers `success:false` with a Google CSE message** — almost
  always a key/engine mismatch: check that the Custom Search API is
  enabled in the key's Google Cloud project, and that the engine has
  both "Search the entire web" and "Image search" enabled.
- **`fly logs`** streams the bridge's logs; **`fly status`** shows the
  machine state; **`fly machines start`** boots a stopped machine
  manually.
