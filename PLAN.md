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
- [x] **Part 4 — Preview & Apply** · fullscreen zoomable preview, info sheet,
      set wallpaper home/lock/both, downloads, share. (Delivered as scoped
      ViewModel operations behind the `WallpaperApplier`/`WallpaperSaver`
      ports; WorkManager re-evaluated in the Part 8 polish pass.)
- [x] **Part 5 — Extension Engine** · final provider API + version gating,
      DexClassLoader loading, install/uninstall, sha256 verify, shared client
      injection. (Packages are zip-based jars; the engine lives in
      `:extensions:core` with `:fixture:demo-provider` compiling a real plugin
      for the test suite. The Wallhaven built-in stays in-process until the
      Part 6 repo manager can distribute it as a real plugin.)
- [x] **Part 6 — Repo Manager + Official Plugins** · add-repo-by-URL,
      index.json parser, Wallhaven extracted to a real plugin, Unsplash /
      Pexels / Pixabay plugins (user keys), Python index builder + GitHub
      Action for the official repo. (The wallhaven package is also bundled
      in the app's assets and reconciled at every start, so fresh installs
      have content; key-based plugins are installed from the repo. Providers
      are dexed with d8 via the `cloudimage.provider` convention plugin.)
- [x] **Part 7 — User Data & Settings** · favorites, history, full settings,
      first-run onboarding. (Library tab streams Room favorites/history with
      in-place unfavorite and clear-all; Settings covers SFW-only, dynamic
      colors, grid columns, data counts, and about/version; the welcome flow
      gates on a persisted onboardingCompleted flag. `:core:designsystem`
      now hosts the shared wallpaper card.)
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
- **2026-09-23 — Part 3 pushed, PR #2 closed, CI green.** Wallhaven provider on
  main (b1d97a6); all CI steps green on run #16. Dependabot PR #2 closed with
  the compileSdk-37 rationale.
- **2026-09-23 — Part 4 done.** Preview & apply: zoomable fullscreen preview
  (pinch/double-tap), info sheet, set wallpaper (home/lock/both via
  WallpaperManager through a testable seam), save-to-gallery (MediaStore on
  API 29+, app-external dir on 26–28), share via FileProvider cache staging,
  favorites toggle, VIEWED/APPLIED/DOWNLOADED history records. The wallpaper
  travels through navigation as a Base64url JSON argument. Download note:
  delivered as scoped ViewModel operations behind the WallpaperSaver port
  instead of WorkManager — a 2–5s save does not justify the machinery in V1;
  revisit in the Part 8 polish pass if background guarantees become needed.
- **2026-09-24 — Part 7 done.** User data & settings: `:feature:library`
  (favorites masonry grid with in-place heart removal + history feed with
  action icons, relative timestamps, clear-all confirm), `:feature:settings`
  (SFW-only, dynamic colors, grid columns, data counts, about/version via a
  PackageManager-backed `VersionName` seam), and a first-run onboarding flow
  (brand header, feature rows, SFW switch, persisted `onboardingCompleted`).
  The app root now owns the theme — dynamic colors follow preferences — and
  the bottom bar grew to Browse / Library / Extensions / Settings.
  `WallpaperCard` moved to a new `:core:designsystem` module shared by
  browse and library. 172 debug unit tests, 0 failures.
