# Cloudimage — V1 Delivery Plan

Working name **Cloudimage** · repo `Cloud-Wallpaper` · package `com.cloudimage.app`

This is the single source of truth for V1 scope and progress.
Every part ends green: app builds, tests pass, CI clean.

## Locked decisions

| Decision | Choice |
|---|---|
| Language | Kotlin (native Android) |
| Platforms | Android only for V1 |
| Content model | Hybrid — official providers preloaded + open third-party repos |
| Core mechanic | Runtime extension plugins (CloudStream model) |
| Backend | None — GitHub-hosted JSON extension repos |
| Distribution | GitHub Releases + in-app updater (v1) |
| UI | Material 3, dynamic color from current wallpaper |
| Content filter | Settings toggle, default SFW, enforced per extension |
| Licenses | App: Apache-2.0 · `:provider:api`: MIT |
| Min / target SDK | 26 / 35 |

## The 8 parts

- [x] **Part 1 — Foundation & CI** · Gradle multi-module + convention plugins,
      Compose M3 shell (dynamic color, bottom nav, splash), Hilt, provider API
      draft, ktlint, GitHub Actions, docs.
- [x] **Part 2 — Core Data & Network** · models, OkHttp + serialization client,
      Room (favorites/history), DataStore (SFW toggle, API keys, enabled
      providers), repository interfaces with fakes.
- [x] **Part 3 — Wallhaven Provider (built-in)** · Wallhaven client on the shared
      HTTP pipeline, staggered masonry grid (Coil), search + filters,
      prefetch pagination.
- [ ] **Part 4 — Preview & Apply** · fullscreen zoomable preview, info sheet,
      set wallpaper home/lock/both, WorkManager downloads, share. **← current**
- [ ] **Part 5 — Extension Engine** · final provider API + version gating,
      DexClassLoader loading, install/uninstall, sha256 verify, shared client
      injection.
- [ ] **Part 6 — Repo Manager + Official Plugins** · add-repo-by-URL,
      index.json parser, Wallhaven extracted to a real plugin, Unsplash /
      Pexels / Pixabay plugins (user keys), Python index builder + GitHub
      Action for the official repo.
- [ ] **Part 7 — User Data & Settings** · favorites, history, full settings,
      first-run onboarding.
- [ ] **Part 8 — Polish & Release** · in-app updater (GitHub Releases),
      empty/error states, offline handling, R8 + signing, performance pass,
      tag `v1.0.0` + signed APK.

**Out of scope for V1** (v1.1+): auto-rotate, Muzei source, TV UI, cloud sync.

## Status log

- **2026-09-23 — Part 1 done.** 10-module Gradle structure, Compose M3 shell,
  CI green on GitHub Actions, 4 Dependabot CI-tooling bumps merged.
- **2026-09-23 — Part 2 done.** Core data & network: domain models, typed-error
  HTTP client (OkHttp + kotlinx.serialization), Room favorites/history DAOs,
  DataStore user preferences, `:core:data` repositories + `:core:testing` fakes.
  Unit tests on every layer (MockWebServer, Robolectric, Turbine).
- **2026-09-23 — Part 3 done (local).** Wallhaven provider: DTOs + API client with
  flag-packed params, repository with content clamp (NSFW never sent, SFW-only
  enforced), staggered masonry grid with real aspect ratios, search, filter
  sheet, pagination with footer retry. Dependabot PR #2 (navigation-compose
  2.10.1) verified incompatible (needs compileSdk 37 > AGP 8.7.3's 35) — to be
  closed, not merged. Commits pending push (token needed).
