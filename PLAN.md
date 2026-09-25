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
- [x] **Part 8 — Polish & Release** · in-app updater (GitHub Releases),
      empty/error states, offline handling, R8 + signing, performance pass,
      tag `v1.0.0` + signed APK. (Updater reads releases/latest, downloads
      through the shared HTTP pipeline and hands the APK to the system
      installer via a dedicated FileProvider; R8 minify + resource shrink
      with a hard keep on `:provider:api` so plugins keep binding by
      original names; signing material arrives via `CLOUDIMAGE_*` env
      vars backed by Actions secrets, and `release.yml` publishes the
      signed APK on `v*` tags. Release APK: 2.0 MB, down from 19.2 MB
      debug.)

**Out of scope for V1** (v1.1+): TV UI, cloud sync, toolchain debt batch
(AGP 9, compileSdk 37, Kotlin 2.4, hilt 2.60 — kept out of patch releases
on principle). *(Toolchain debt was pulled into v1.0.5; TV UI and cloud
sync stay parked.)*

## v1.0.9 — CloudStream-grade UX (planned 2026-09-25)

User's call: a "huge upgrade" modeled on the reference repo
(recloudstream/cloudstream — design reference only, no code copied;
their GPL stays theirs). Scope was set by reading their source, not by
recollection. Four parts, each ending green (builds, tests, CI clean),
one release at the end.

- [x] **Part 1 — Home sections (CloudStream `mainPage` model).** The flat
      feed becomes CloudStream's home: named section rows, each a
      horizontal carousel with its own pagination and a header row with
      a "See all" chevron (their `home_child_more_info` + horizontal
      RecyclerView per `homepage_parent`). Contract: `WallpaperProvider`
      grows an additive default method
      `suspend fun sections(): List<HomeSection>` where a section is
      id + title + `WallpaperQuery` — binary-compatible for plugins built
      against V1 (they inherit the default: a single "Popular" section
      over `popular()`; `ProviderApi.VERSION` stays 1). Built-ins declare
      real sections (Wallhaven: Trending / Latest / Anime / People via
      TOPLIST, DATE and categories; the others by POPULAR / LATEST
      capability). Single-source mode shows that source's sections;
      "All sources" shows each usable source's primary section as its
      own row. "See all" opens the staggered grid scoped to that
      section's query (the grid stays for search + drill-down). The
      v1.0.6–1.0.8 pipeline (pin, FAB switcher, key prompt, failure
      taxonomy) carries over untouched. `:fixture:demo-provider`
      overrides `sections()` to prove compatibility in both directions;
      R8 keep rules + dex audit re-run for the new API surface.
      (Delivered 2026-09-25, commits 25f1183 + 4a066a4: sections carry
      host-vocabulary `Filters` presets the repository translates to
      `WallpaperQuery` — purity deliberately never read, the SFW setting
      stays the owner; a degenerate empty home falls back to the v1.0.8
      flat merged feed; the merged view labels default rows with the
      source name and composes "Source · Section" for declared ones;
      See-all scopes the grid to the section's source with a chip as the
      way back; 452 tests (+18), dex audit PASS — 5,543 host classes,
      wallhaven 27/32/0 unresolved; the extension repo re-published with
      the sections-capable wallhaven package.)
- [x] **Part 2 — Search UX (CloudStream search).** Debounced
      search-as-you-type (IME submit stays); persisted search history
      (capped, deduped, most-recent first, clear-all with confirm) shown
      while the field is focused and empty; tag suggestions while typing
      from providers declaring the TAGS capability — no third-party
      suggest API (honesty + privacy). In "All sources" mode every card
      carries its provider label (their per-result `apiName` analog) and
      a per-source failure summary chip with retry (built on the v1.0.2
      failure taxonomy). Pinned-mode empty results get a "try All
      sources" CTA.
      (Delivered 2026-09-25, commit 1211e07: `suggestTags()` joins the
      contract as the second additive default — ProviderApi.VERSION
      stays 1, the demo fixture overrides it and the engine tests prove
      both classloader directions; wallhaven serves its own /tags
      endpoint when a key is stored and answers empty keyless —
      suggestions come from the source the user is searching, or not
      at all. Live debounced commits record no history — only explicit
      ones (IME submit, chip tap, history tap) do. `search()` now
      returns `SearchOutcome{page, sourceFailures}` so merged failures
      surface as a summary chip with retry while survivors still show.
      476 tests (+24), ktlint clean, dex audit 0 unresolved.)
- [ ] **Part 3 — Extensions platform (CloudStream plugins).** Update
      detection: catalog `versionName` vs installed → Update state on
      the catalog row plus an update action (install-over); per-extension
      enable/disable surfaced in the manager; their proportional stats
      bar (installed / disabled / available counts); catalog search by
      name; per-repo catalog refresh ("check for updates"). Update
      semantics ride the engine's existing sha256 + version gates.
- [ ] **Part 4 — Personal rows + detail recommendations + housekeeping.**
      Detail screen grows "More like this": same-provider search over the
      wallpaper's top tags (SEARCH + TAGS capability-gated) as a
      horizontal carousel on the detail screen. Home grows a
      "Recently applied" row from history when present (their home
      bookmarks/continue-watching rows analog). The two parked
      deprecations land: `TabRow` → `PrimaryTabRow` (Library),
      `hiltViewModel` → androidx.hilt.lifecycle.viewmodel.compose
      across features.
- [ ] **Release — v1.0.9.** versionCode 10 / versionName "1.0.9", full
      ritual: ktlint, unit tests, assembleRelease, dex audit, push, CI
      green, tag v1.0.9 + signed APK Release, PLAN/handoff/worklog.

Not ported (deliberate): video-oriented surfaces (player, subtitles,
download queue, Chromecast), accounts/sync, TV layout (stays v1.1
backlog), extension language / TvType filters (no such dimensions in
Cloudimage), third-party web-search suggestions.

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
- **2026-09-24 — Part 8 done.** Polish & release: in-app updater (Settings
  → Updates card; GitHub releases/latest check, version-compare tolerant of
  v-prefixes and prerelease suffixes, APK download through the shared
  OkHttp pipeline staged in cache and handed to the system installer via a
  dedicated FileProvider + REQUEST_INSTALL_PACKAGES); preview-image retry
  chip on failed loads; R8 minification + resource shrinking with a hard
  keep on the whole `:provider:api` package (plugins bind host classes by
  original name — verified surviving in the shipped dex) and the standard
  kotlinx.serialization rules; release signing from `CLOUDIMAGE_*`
  environment variables (Actions secrets, keystore never committed); new
  `release.yml` publishes the signed APK on `v*` tags with generated
  notes. Toolchain debt explicitly deferred to V1.1 (AGP 9, compileSdk 37,
  Kotlin 2.4, hilt 2.60) — V1 ships on the proven 8.7.3 stack. Release
  APK 2.0 MB (debug was 19.2 MB); 186 debug unit tests, 0 failures.
- **2026-09-24 — v1.0.2 shipped.** Honest failure taxonomy: plugin failures
  stop masquerading as connectivity loss. `NetworkError.Source` in
  :core:network; `WallpaperSources.loadFailures` feeds per-source
  diagnostics; browse has a distinct "a source failed" state; extensions
  rows explain why a source failed to load. 191 tests.
- **2026-09-24 — v1.0.3 shipped.** Auto-rotate wallpaper changer: the
  first v1.1 backlog item pulled forward per user call. Settings →
  Wallpaper rotation (switch, cadence 30 min–24 h, home/lock/both
  target, Wi-Fi-only, Rotate now with inline feedback). Round-robin
  cursor over the favorites (save-date order) persisted in DataStore,
  advanced before each apply so a broken download can't stall the
  carousel. WorkManager periodic work via @HiltWorker + Hilt-aware
  Configuration (default initializer removed); UPDATE policy so process
  restarts never reset the cycle; battery-not-low always, network
  constraint by the Wi-Fi-only setting. Successful rotations record
  APPLIED history like manual applies. 205 debug unit tests, 0 failures;
  dex audit clean (5,575 host classes). Release APK 2.68 MB.
- **2026-09-24 — v1.0.4 shipped.** Muzei source integration: Cloudimage is
  now selectable as an artwork source inside Muzei. New `:core:muzei`
  module — `CloudimageMuzeiArtProvider` (muzei-api 3.4.2, manifest-declared
  under Muzei's ACCESS_PROVIDER permission + intent action, with the
  AAR's documents provider alongside) enqueues a @HiltWorker
  `MuzeiArtworkWorker`; selection lives in the tested
  `FavoriteMuzeiArtworkSelector`: the batch is the saved wallpapers
  (save-date order, SFW/Sketchy per the SFW-only setting, NSFW never),
  served as a rotated list with a persisted cursor advanced BEFORE the
  handoff so a broken download can't stall the carousel, capped at 200
  artworks per binder transaction. Fresh installs with nothing saved
  serve the default feed instead, one page per load, wrapping to page 1
  when a source runs dry. Transport failures (offline/timeout/HTTP)
  retry with WorkManager backoff; source failures don't — Muzei keeps
  its current artwork. getCommandActions offers "Open Cloudimage"
  (RemoteActionCompat, the AAR's launch icon). R8 survival verified:
  provider kept public with its no-arg constructor, worker kept by name;
  plugin ABI audit 0 unresolved over 5,603 host classes. 231 debug unit
  tests, 0 failures (19 new). Release APK 2.82 MB.
- **2026-09-24 — v1.0.5 shipped.** Toolchain debt batch, the v1.1 roadmap
  item pulled forward with nothing feature-shaped mixed in: AGP 8.7.3 →
  9.4.1 on Gradle 8.9 → 9.7.1, Kotlin 2.0.21 → 2.4.20 through AGP 9's
  built-in Kotlin (the org.jetbrains.kotlin.android plugin is gone — AGP 9
  makes stacking it an error; the built-in Kotlin runs 2.4.20 via the
  buildscript classpath override in the root build file, alongside KSP
  2.3.12), the compilerOptions DSL migration (kotlinOptions is removed in
  Kotlin 2.4), compileSdk/targetSdk 35 → 37, and the gated library floor:
  Hilt 2.60.1 + androidx.hilt 1.4.0, Compose BOM 2026.09.00 (foundation
  1.12.1, material3 1.4.0), Room 2.8.5, kotlinx-serialization 1.11.0,
  ktlint-gradle 14.2.0 (with a repo-wide auto-format pass for its new
  rules), and the standalone d8 used to dex provider packages aligned to
  r8 9.4.24. AGP 9 migrations: the bundled-extensions Sync became
  SyncBundledExtensionsTask in build-logic (Gradle 9 cannot instantiate
  script-declared task classes) wired through the Variant API's
  addGeneratedSourceDirectory, which also carries the mergeAssets task
  dependency the old matching block used to; the engine's test-fixture
  resources moved to the new source-set DSL (AGP 9 rejects Provider
  instances on the legacy one); Gradle 9.6's error-level
  `configurations.creating`/`tasks.registering` delegates became direct
  create/register calls. CI unchanged (AGP 9 still only needs JDK 17).
  Known deprecations parked for the next batch: hiltViewModel's move to
  androidx.hilt.lifecycle.viewmodel.compose, TabRow → PrimaryTabRow.
  Plugin ABI intact under the new R8: audit clean — 5,518 host classes,
  wallhaven 27 classes / 32 external refs, 0 unresolved. 426 unit tests
  (231 debug + 156 release), 0 failures. Release APK 2.96 MB.
  Remaining v1.1 backlog: deprecation cleanup, TV UI, cloud sync.
- **2026-09-25 — v1.0.6 shipped.** Browse source switcher, from a real user
  report: after installing a new extension there was no way to view its feed
  on the home page (the merged feed silently skipped keyless sources, so
  nothing visibly changed). The home screen now has a source bar — "All
  sources" plus a chip per usable source, appearing as soon as a second
  source exists — that pins the feed to one provider: `WallpaperSources`
  .search gained a `sourceId` route (`ExtensionWallpaperSources` filters
  the ready providers, with an honest "not installed" failure for a dangling
  pin), the pin persists in DataStore (`UserPreferences.browseSourceId`,
  survives process death, and auto-clears with a feed restart when the
  pinned source is uninstalled), and search/pagination carry it. Chips that
  need an API key the user hasn't stored show a key hint, and selecting one
  shows an "API key required" prompt pointing at the Extensions tab
  instead of firing a request destined to fail — adding the key from the
  extensions screen un-prompts the feed live. The cold-start-race retry now
  waits for the first load to settle before rescuing (fixes a duplicate
  restart when sources are discovered while the initial request is in
  flight). Search hint de-Wallhavened. 433 unit tests (7 new: browse
  routing/persist/recreate/prompt-recovery/unpin-fallback, sources
  routing, datastore), 0 failures; dex audit clean — 5,524 host classes,
  wallhaven 27 classes / 32 external refs, 0 unresolved. Release APK
  2.97 MB.
- **2026-09-25 — v1.0.7 shipped.** CloudStream-style source selector,
  from user feedback on the v1.0.6 chips (they wanted the provider
  switcher UI CloudStream uses). The chip row is replaced by a
  full-width selector pill above the search bar — letter avatar + active
  source name + chevron — that opens a dropdown menu listing "All
  sources" and every installed extension (a round avatar tile in one of
  three container tones, stable per source name; a checkmark on the
  active entry; the key hint on keyless sources), plus a "Manage
  extensions" entry that jumps to the Extensions tab with the bottom
  bar's save/restore-state navigation contract — the discovery path the
  user said was missing from the app. The selector now shows with a
  single usable source too (it names where the feed comes from and
  always carries the Extensions shortcut) instead of waiting for a
  second source. The API-key prompt gained an "Open Extensions" recovery
  button next to "Show all sources". Persistence, pin routing, dangling
  -pin fallback and key-prompt recovery all carried over unchanged from
  v1.0.6. 434 unit tests (1 new: single-source selector visibility),
  0 failures; dex audit clean — 5,543 host classes, wallhaven 27
  classes / 32 external refs, 0 unresolved. Release APK 2.99 MB.
- **2026-09-25 — v1.0.8 shipped.** The real CloudStream switcher, ported
  from source: the user supplied the recloudstream/cloudstream repository
  as reference after the v1.0.7 pill+dropdown missed the mark. The home
  screen now mirrors CloudStream's pattern — a bold extended FAB pinned
  to the feed's bottom-end corner (filter-list icon, gray container) whose
  label is the active source name ("All sources" when merged), shrinking
  to its icon when the feed scrolls down and re-extending on scroll up,
  exactly like homeApiFab.shrink()/extend(). Tapping it opens the CloudStream
  provider picker: a bottom sheet that skips the half-expanded state
  (their BottomSheetDialog uses STATE_EXPANDED) with a single-choice list —
  "All sources" first, then every installed source sorted alphabetically
  (sortedBy name.lowercase()) — bold 16sp rows with a check mark and source
  color on the active entry (their CheckLabel style), a key hint on
  keyless sources, and tap-to-apply-and-dismiss. The sheet ends with a
  "Manage extensions" row deep-linking to the Extensions tab, standing in
  for CloudStream's per-provider action row. Deliberately not ported:
  CloudStream's "Random" fixed entry (no random-source mode here), TvType
  filter chips (no source-type dimension in Cloudimage), provider pinning
  (no pinned-providers preference yet) and the FAB long-press reload.
  Under the hood the browse grid's LazyStaggeredGridState was hoisted so
  the FAB can track scroll direction (index*1e6 + offset, monotonic across
  item swaps, -5px hysteresis), and a fresh feed always starts with the
  FAB extended. 434 unit tests (no ViewModel logic changed), 0 failures;
  dex audit clean — 5,526 host classes, wallhaven 27 classes / 32
  external refs, 0 unresolved. Release APK 2.99 MB.
- **2026-09-25 — v1.0.9 planned.** Four-part CloudStream-grade UX upgrade
  scoped from the reference source (home sections, search UX, extensions
  platform, personal rows + housekeeping — see the "v1.0.9" section
  above); roadmap committed to PLAN.md. Awaiting the user's GO for Part 1.
- **2026-09-25 — v1.0.9 Part 1 done (home sections).** The CloudStream
  `mainPage` model landed: `WallpaperProvider.sections()` as an additive
  default method (HomeSection = id + title + Filters preset;
  ProviderApi.VERSION stays 1 — engine tests prove both classloader
  directions: the fixture overrides with two sections, a
  non-overriding provider answers with the default Popular).
  Wallhaven declares Trending/Latest/Anime/People; key-based plugins
  inherit the default. `WallpaperSources.sections(sourceId)` resolves
  the pinned list or one primary row per source, translating filters
  to `WallpaperQuery` (purity never read — SFW setting owns ratings);
  failing sources degrade like search. BrowseViewModel: rows load
  their first page in parallel pinned to their source, carousels
  paginate with a prefetch buffer and retry failed first pages on
  page 1, See-all opens the grid scoped to the section's source (chip
  + back), search/filters keep their grid, empty degenerate homes
  fall back to the v1.0.8 flat feed, and the pin/dangling/key-prompt/
  cold-start pipeline carried over untouched. Two real bugs were
  caught by the new tests before shipping: search-submit not entering
  grid mode, and See-all querying all sources instead of the
  section's. 452 tests (18 new), 0 failures; ktlint clean (one CI
  catch on a hand-edited line — fixup 4a066a4); dex audit PASS
  (5,543 host classes, wallhaven 27 classes/32 external refs/0
  unresolved); the extension repo re-published with the
  sections-capable wallhaven package.
- **2026-09-25 — v1.0.9 Part 2 done (search UX).** CloudStream's search
  experience landed. Typing now searches by itself after a 450ms pause
  (IME submit stays the instant fast path and cancels the debounce);
  tag suggestions land at 200ms so chips are visible before the search
  commits; clearing the text by pausing mirrors the blank submit and
  returns to the home. Search history persists (10 entries, deduped,
  most-recent first, clear-all behind an AlertDialog confirmation) and
  records ONLY explicit commits — debounced live commits never pollute
  it. Suggestions are TAGS-capability gated and come from the
  provider's own API: `WallpaperProvider.suggestTags()` is the second
  additive default method (VERSION stays 1 — the demo fixture overrides
  it and the engine tests prove both classloader directions), wallhaven
  calls its own `/tags?apikey&q=` endpoint when the user stored a key
  and answers empty keyless (no doomed requests, no third-party suggest
  API, ever — honesty + privacy). `search()` now returns
  `SearchOutcome{page, sourceFailures}` (Muzei adapted mechanically):
  a failing source in a merged search degrades to a skip that is now
  NAMED — a summary chip under the search bar counts the failures with
  a retry, while surviving results still show. The merged grid stamps
  every card with its provider's name (CloudStream's per-result apiName
  analog); a pinned search with no results offers a "Try all sources"
  CTA. Cleanup riding along: wallhaven's `tags` field no longer
  masquerades as its colors palette. Test-technique lesson: under
  MainDispatcherRule the Main clock and the runTest clock are separate —
  DataStore writes need both clocks driven to settle. 476 tests (+24),
  0 failures; ktlint clean; dex audit PASS (5,568 host classes,
  wallhaven 35 classes/32 external refs/0 unresolved — the new ABI
  binds); one known lintVital OOM recovered by the usual
  kill-daemon-and-rerun recipe; the extension repo re-published with
  the TAGS-capable wallhaven package. CI green on 1211e07.
