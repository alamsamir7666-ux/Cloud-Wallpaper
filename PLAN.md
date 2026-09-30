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
- [x] **Part 3 — Extensions platform (CloudStream plugins).** Update
      detection: catalog `versionName` vs installed → Update state on
      the catalog row plus an update action (install-over); per-extension
      enable/disable surfaced in the manager; their proportional stats
      bar (installed / disabled / available counts); catalog search by
      name; per-repo catalog refresh ("check for updates"). Update
      semantics ride the engine's existing sha256 + version gates.
      (Delivered 2026-09-25, commit f9f27f6: the catalog row compares
      versionCode first, versionName as the equal-code tiebreak — an
      "Update to vX" chip plus a download action whose install-over is
      the engine's ordinary replace-in-one-step; the manager row gains
      a Switch backed by a disabled-id set in DataStore, and a disabled
      source drops out of sources/search/sections/suggestTags (and the
      browse switcher) while staying installed — a disable also clears
      a browse pin naming it, and a pinned query on a disabled source
      fails with "enable it in the Extensions tab" instead of a
      connectivity lie; the proportional stats bar renders
      enabled/disabled/available as weighted segments with a legend;
      catalog search filters rows by id-contains and folds repos with
      no match; each repo header gets a check-for-updates refresh with
      an in-flight guard. 301 tests (+24), ktlint clean, dex audit PASS.)
- [x] **Part 4 — Personal rows + detail recommendations + housekeeping.**
      Detail screen grows "More like this": same-provider search over the
      wallpaper's top tags (SEARCH + TAGS capability-gated) as a
      horizontal carousel on the detail screen. Home grows a
      "Recently applied" row from history when present (their home
      bookmarks/continue-watching rows analog). The two parked
      deprecations land: `TabRow` → `PrimaryTabRow` (Library),
      `hiltViewModel` → androidx.hilt.lifecycle.viewmodel.compose
      across features. (Shipped as `34a2965`: Wallpaper carries tags
      end-to-end — wallhaven wire tags, the sources bridge, the demo
      fixture; SourceInfo gained a defaulted `capabilities` set mirrored
      from the provider contract by a new SourceCapability enum, so the
      detail row gates on SEARCH + TAGS without touching plugin classes;
      the query is the top 3 tags joined, broadening to the single
      strongest tag when the combination matches nothing, self excluded,
      capped at 15, failures degrade silently to a hidden row; home's
      "Recently applied" row leads the section list — APPLIED entries
      only, deduped newest-first, capped at 10, hidden when empty;
      Library TabRow → PrimaryTabRow; hiltViewModel migrated to
      androidx.hilt:hilt-lifecycle-viewmodel-compose:1.4.0 across app +
      5 features. 352 tests green (+12; the Part 3 "301" undercounted —
      it missed the plain-JVM `test`-task modules: providers ×4,
      provider/api), ktlint clean, dex audit PASS.)
- [x] **Release — v1.0.9.** versionCode 10 / versionName "1.0.9", full
      ritual: ktlint, unit tests, assembleRelease, dex audit, push, CI
      green, tag v1.0.9 + signed APK Release, PLAN/handoff/worklog.
      (Shipped as `638766b`: local gate ktlint clean, 512 unit tests
      green — 312 debug + 156 release + 44 plain-JVM, CI-comparable 356
      — assembleRelease 3.03MB, dex audit PASS: 5,600 host classes,
      wallhaven zip 36 classes / 32 external refs / 0 unresolved; CI
      build green on `638766b`; tag `v1.0.9` → release run green →
      GitHub Release v1.0.9 published with signed
      `Cloudimage-v1.0.9.apk`, 2.9MB.)

Not ported (deliberate): video-oriented surfaces (player, subtitles,
download queue, Chromecast), accounts/sync, TV layout (stays v1.1
backlog), extension language / TvType filters (no such dimensions in
Cloudimage), third-party web-search suggestions.

## Status log

- **2026-09-30 — v1.0.30 SHIPPED (the viewer's vertical gestures,
  re-parented).** The user's report on v1.0.29, flat and specific:
  after the image fully loaded, swipe down and swipe up did nothing
  (sideways paging worked; during loading they worked too). The
  underlay design was wrong about one thing: it never consumes until
  its own 10dp arbiter lock engages, and on a loaded image telephoto's
  gesture nodes — children dispatch first on the Main pass — see every
  event before the underlay does; whichever interleaving wins on real
  devices, the underlay starves. The diagnosis came from reading
  telephoto 0.19.0 end to end: its own media-viewer sample (pager +
  zoomable + dismiss, the exact stack this app wants) and its
  FlickToDismiss component — which v1.0.22 used before v1.0.29
  replaced it — do not underlay at all. **They wrap: the gesture layer
  is the PARENT of the image, and it consumes the vertical touch-slop
  event itself.** That consumption is a handshake telephoto is built to
  honor: its forked transformable (forked from compose.foundation
  explicitly "to solve incompatibility with FlickToDismiss") watches
  the Final pass for exactly that consumption while waiting for its own
  slop and stands down; the pager above finds the moves consumed and
  stops paging for the gesture. The slop race, not bookkeeping, decides
  ownership: a pinch crosses the zoomable's multi-pointer slop first
  (child-first dispatch) and the layer never locks; a double-tap-hold
  drag is claimed by the zoomable's own second-down slop detector; a
  zoomed-in pan consumes every move so the layer's slop helper returns
  null (and while zoomed at all, the layer declines from the first
  down); a horizontal swipe never crosses vertical slop so the pager
  pages. So v1.0.30 restructures the page: the invisible gesture Box
  is now a wrapper that PARENTS the moving content (scrim stays on the
  outer page; the moving Box with its whole-pixel offset and
  draw-phase alpha sits inside the wrapper), and its detector is
  flick's own choreography verbatim — `awaitFirstDown` unconsumed,
  `awaitVerticalTouchSlopOrCancellation` consuming the slop event,
  compose's `drag(pointerId)` consuming the tail. The motion semantics
  are unchanged from the v1.0.29 spec (the part that was right): 1:1
  down-translate with simultaneous fade and never a scale/skew/resize,
  commit past 35% of the screen or a 1250dp/s downward flick, the
  220ms exit tween, critically damped springs home, the capped 24dp
  half-speed details nudge committing past 64dp or an 800dp/s upward
  flick (upward can never dismiss — direction is latched at lock), the
  3px stillness band, the lock-time baseline latch for mid-settle
  grabs. The arbiter shrinks accordingly: `YIELDED` and the private
  lock slop are gone (the slop race owns that decision), replaced by
  `onLocked(downward)`; the double-tap-window and multi-pointer
  stand-downs are gone (the handshake owns those too). Sample parity
  one step further: a page that stops being the settled one snaps its
  zoom back to rest (`resetZoom(SnapSpec())`), so returning to a
  previously-zoomed neighbor never lands on a frozen, pager-locking
  page. Gates: ktlint clean; unit tests updated to the halved arbiter
  contract (lock latching, follow, thresholds, stillness — plus a new
  upward-flick-never-dismisses case); `assembleDebug` green in CI.
  versionCode 31 / versionName 1.0.30.

- **2026-09-30 — v1.0.29 SHIPPED (the viewer's gestures, rebuilt on a
  pager).** The user's brief, precise this time: swipe down dismisses
  with a 1:1 translate-and-fade and NO scaling at any point (flick's
  scaled dismiss was the distortion on screen); swipe left/right pages
  to the next/previous image; swipe up stays reserved for details; and
  the three directions must separate cleanly. The v1.0.23-27 lesson was
  architectural, so the rebuild is architectural: **the old design's
  fatal flaw was a custom four-direction arbiter layered as a parent
  over telephoto's zoomable — a parent must consume on the Initial pass
  to beat its own child, then watch the Main pass to learn whether the
  child claimed anyway, and five releases of that choreography still
  left interleaved writers.** The new design never fights the zoomable
  at all. **Horizontal is not custom anymore**: the viewer became a
  native `HorizontalPager` over the list the grid opened from (the
  `ViewerSession` hand-off is revived — browse feeds, every library
  tab, and the lookalike row park list+index; process death falls back
  to the lookalike row led by the current image), with
  `beyondViewportPageCount = 1` prefetching neighbors (the whole
  ViewerPreloadGate apparatus, retired) and `userScrollEnabled`
  standing down while the current page is zoomed. **Vertical is a
  passive underlay**: inside each page the invisible gesture Box is the
  FIRST child — below the moving image Box in dispatch order — so
  telephoto sees every event first; a drag the zoomable claims (zoomed
  pan, pinch, quick zoom) arrives at the underlay already consumed and
  it stands down on the spot, while its own locked moves are consumed
  for the pager above. One gesture, one writer, always, with no
  Initial-pass dance to get wrong. The zoomable's non-consuming
  engagements are covered deterministically: any zoom fraction at all
  disqualifies the gesture (checked before every lock and every
  write), and a down inside the double-tap window of a recent up is
  skipped outright — that finger belongs to telephoto's double-tap /
  quick-zoom detectors, exactly the interleaving the old arbiter
  fought. The held-finger shiver gets the same disciplines v1.0.26-27
  found, now actually effective because there is never a second
  writer: whole-pixel layout offsets (a sub-pixel layer translation
  re-samples the sub-sampled tiles every frame), a 3px stillness band
  on the follow, single-flight restore springs, and a dismiss exit
  that is immune to new input. The follow also latches the offset at
  lock time as its baseline, so a drag that grabs the image while a
  settle animation is in flight continues from exactly where the image
  is — no snap. Feel: the dismiss commits past 35% of the screen or a
  1250dp/s flick, the image fading to half at the threshold and to
  zero at 70% travel while the exit tween (220ms) carries it off
  screen; the chrome fades in lockstep; below threshold everything
  springs home critically damped. The details swipe nudges the image
  up at half speed capped at 24dp and opens the sheet past 64dp or an
  800dp/s flick (the bottom pill remains as its echo). Each page keeps
  its own zoom state and motion, registered up to the screen for the
  `userScrollEnabled` gate and the chrome fade; a settled page rebinds
  history, recommendations, details and the favorite/downloaded
  observers to the image that landed (`flatMapLatest`, so nothing
  carries over). Gates: ktlint clean; **540/0 tests** in the fresh
  sandbox (12 network-gated skips as always; +31 over the restore's
  single-variant count: 19 arbiter, 7 session, 5 ViewModel paging
  cases); `assembleDebug` green end-to-end. v1.0.23's mining paid off:
  ViewerSession and the ViewModel rebind came back nearly verbatim,
  the arbiter came back halved (vertical only), and the preload gate
  stayed dead.

- **2026-09-29 — v1.0.28 SHIPPED (the v1.0.22 restore).** The user
  called it: five releases of gesture work (v1.0.23-v1.0.27 — arbiter,
  in-place paging, preload gate, one-writer dispatch, stillness band)
  never fully killed the held-swipe shiver, so the whole tree is
  restored to the v1.0.22 tag. 21 files, -2,299/+172 lines: the
  gesture arbiter, ViewerPreloadGate, ViewerSession and their test
  suites are gone; the entries describing v1.0.23-v1.0.27 live on in
  the tagged history (tags v1.0.23..v1.0.27 keep both code and PLAN
  text if any of it is ever worth mining again). Only the version
  moves forward — versionCode 29 / versionName 1.0.28 — so the revert
  installs as a plain in-place update over v1.0.27 (code 23 was taken
  by the original v1.0.22; Android demands monotonic codes). On screen
  this means v1.0.22 exactly: paging swaps in place (no
  gallery-adjacent follow), telephoto flick-to-dismiss is back
  (v1.0.23 had retired it for the arbiter), and the viewer behaves as
  the user last accepted it in v1.0.22. Gates on the restored tree:
  ktlint clean; 669/0 tests (12 network-gated skips — the
  arbiter/preload/session suites left with their code); release build
  green on the second attempt (the known R8 daemon-kill, usual pkill
  recipe); dex audit PASS (5,974 host classes, wallhaven 36 classes /
  32 external refs / 0 unresolved). The sandbox had been wiped again —
  env restored via scripts/env-recovery-v112.sh (Temurin 17.0.20.1,
  SDK 37 / build-tools 36.0.0) before the gates. Ritual: push 0d42e12
  → CI build + publish-repo green → tag v1.0.28 → release workflow
  green → Cloudimage-v1.0.28.apk 3,197,115 bytes downloaded +
  apksigner VERIFIED (CN=Cloudimage, same CA) + badging confirms
  29/1.0.28; release notes rewritten to say plainly what a stability
  revert is. CI green on 0d42e12.
- **2026-09-28 — v1.0.22 SHIPPED (Downloads: the library tab and the
  honest download button).** The user's two-part brief, shipped whole.
  **(a) The Library grows a third destination — Downloaded** (order:
  Favorites, History, Downloaded), riding the same swipe-synced pill
  pager. It is a masonry grid of every wallpaper downloaded from the
  app, mirroring the favorites grid's layout exactly (same columns,
  spacing, card) — tracked in its OWN Room table (downloads, keyed by
  the favorite's (providerId, wallpaperId) pair with the same snapshot
  fields) so a download and a favorite stay independent facts: the
  heart overlay on downloaded cards reflects and toggles favorites
  (outlined when not favorited, filled when favorited — "still shows
  the heart icon as filled/active" when both), downloading never
  favorites, and clearing history never touches downloads. Empty
  state: "No downloads yet." **The migration is real** — database
  version 2 retires the pre-1.0 destructive fallback and seeds the new
  table from the history feed (newest DOWNLOADED row per wallpaper
  via SQLite's MAX-picks-the-row guarantee), so wallpapers downloaded
  under v1.0.21 and earlier appear without re-downloading; a
  full-fidelity Robolectric test builds a v1 file and opens it through
  Room so the migrated schema is validated for real (it caught the
  NOT NULL on the autoincrement id on its first run — the test works).
  **(b) The preview's download button gets the full treatment:** tap
  it and the icon is REPLACED by a determinate progress ring tracing
  the circular button's edge clockwise as the bytes land, driven by a
  new streaming path in CloudimageHttpClient (64 KiB ticks, the
  Content-Length when the server states one; error pages and
  Cloudflare challenges stay buffered so a solve-and-replay never
  emits misleading counts). The size text — "1.2 MB / 4.5 MB",
  KB/MB/GB formatted like the info sheet — rides as a small label
  DIRECTLY BELOW the button (the user's call: the button is small and
  circular), growing leftward so the button never moves. Unknown
  total degrades honestly: indeterminate ring, running count alone.
  On success the ring becomes a static checkmark, NOT tappable — once
  downloaded, always downloaded (isDownloaded streams from the table,
  so revisiting opens straight into the checkmark); a failure or
  cancel returns the button to its idle download icon for a fresh-tap
  retry. The download and share buttons are now true 40dp circular
  buttons on the FilledTonal palette, separated by twice the row's
  rhythm (24dp) so they read as distinct actions — the spacing the
  user asked for. Gates: ktlint clean; **669/0 tests (+15**:
  3 DAO + 2 migration + 3 streaming client + 5 detail VM incl. a
  gated mid-flight progress assertion + 3 library VM**)**;
  assembleRelease 3,184,827 bytes unsigned after the usual daemon-OOM
  pkill+retry; dex audit PASS 5,974 host classes, wallhaven ABI
  intact; CI green on dcd0d9d; tag `v1.0.22`;
  `Cloudimage-v1.0.22.apk` 3,197,115 bytes signed & cert-verified.

- **2026-09-27 — v1.0.21 SHIPPED (two keyless scrapers + honest
  detail info).** Two halves shipped under one version. **(a) Two new
  official keyless providers** in the app tree as buildable
  `:providers:*` modules, packaged by the publish-repo workflow into
  the official gh-pages repository: **WallpaperCave 1.2.0** (13-tab
  browse bar, `wallpapercave.com`) and **HDQWalls 1.0.1**
  (14 shelves, hdqwalls.com, "dimension-honest" — its grid publishes
  only a uniform 602x339 card crop, so it never reports the crop as
  the wallpaper's resolution). Both declare keyless scrapers with
  full offline test suites plus live-check tests that skip without
  network. **(b) The detail info sheet tells the truth about
  resolution and size** — new `WallpaperSources.details()` facade
  routes to the owning provider; `DetailViewModel` fetches the
  definitive record once per preview, ONLY when the listing lacks
  dimensions (API-keyed sources keep their quota; failures degrade
  silently); the sheet falls back to the record's resolution and
  gains a File-size row with KB/MB/GB formatting, and the record's
  page URL lights up the open-on-provider-site row. **Release
  interruption, diagnosed and completed by the follow-up session:**
  the feature session pushed both commits and CI went green but
  stopped before the ritual's tag step — no `v1.0.21` tag meant the
  tag-triggered release workflow never fired, so no APK existed.
  This session re-ran every gate from scratch on a recovered
  environment (ktlint clean; **654/0 tests, +73**: the two scraper
  suites, 3 facade-routing + 3 ViewModel detail tests;
  assembleRelease 3,182,775 bytes unsigned after the usual daemon-OOM
  pkill+retry; dex audit PASS 5,960 host classes, wallhaven ABI
  intact), tagged `v1.0.21`, release green, `Cloudimage-v1.0.21.apk`
  3,195,063 bytes signed & cert-verified. The official repo index
  re-published with all six providers (the two scrapers carry empty
  categories — a cosmetic follow-up candidate, they just don't sort
  under any category chip).

- **2026-09-27 — v1.0.20 SHIPPED (Cloudstream-style extension system).**
  The user asked for the Extensions tab to match Cloudstream's
  extension system in structure and functionality, with their repo as
  the reference (two screenshots + the recloudstream/cloudstream
  source). The rework, in full: **the extensions tab is now a repository
  browser** — one full-bleed row per added repo (monogram avatar, name,
  url, trailing delete; tap opens the catalog, long-press copies
  "name : url"), an empty state when no repos exist, and the add-repo
  FAB kept from v1.0.13. **The counts bar moved from the list body to a
  fixed bottom bar** and split into Cloudstream's three populations —
  Downloaded / Disabled / Not downloaded — over a segmented
  proportional track (neutral track when everything is zero; the
  v1.0.10 zero-weight crash guard stays). Tapping it opens the
  installed list. **Repo detail is a pushed screen** (repo id rides the
  nav graph Base64 URL-safe — it's a URL full of path-reserved
  characters): search over one repo's catalog, category filter chips
  when the index declares categories (rendered only then, per the
  user's "only if my extensions have categories"), and one row per
  extension — avatar, name, `v + size` meta, short description, and the
  per-item affordance the user asked for: download until installed,
  delete after (confirmed first), update riding the download as an
  install-over with an "Update to vX" chip. **The installed list** (was
  the manager's top section) keeps every per-source control — enable
  switch, API key flow, load diagnostics, uninstall — behind its own
  search. The ViewModels split browser / repo detail / installed, and
  the shared visual atoms (monogram avatar, chips, size formatting)
  live in `ExtensionsSharedUi`. The existing bottom nav was KEPT — the
  app's four labeled destinations already fit its model, and swapping
  chrome only on one screen would fragment wayfinding (the user's
  brief allowed exactly this). Schema: `RepoPackageEntry` + `name` +
  `categories`, `ExtensionManifest` + `categories`, both defaulted so
  old apps and old indexes interoperate both ways; the index builder
  carries them; the four bundled providers declare wallpaper-relevant
  categories (wallhaven anime/abstract/nature, unsplash
  photography/nature/minimal, pexels photography/nature, pixabay
  nature/photography/abstract). Provider versions intentionally NOT
  bumped — categories only feed the catalog UI, so the re-published
  gh-pages index alone carries them with no false update prompts.
  Gates: ktlint clean (one auto-fix round), 581/0 tests (feature tests
  28→38 across three VM suites + manifest categories parse test),
  assembleRelease survived the usual daemon-OOM kill (pkill + retry),
  dex audit PASS 5,957 host classes, wallhaven plugin ABI intact; CI
  green; tag `v1.0.20`; `Cloudimage-v1.0.20.apk`, 3.19MB.

- **2026-09-27 — v1.0.19 SHIPPED (suggestion panel that stays).** The
  user's field report: whenever an extension supplies search
  suggestions, the suggestion UI "appears and disappears immediately".
  The assumption checked out against the code, and the root cause was a
  timing flaw baked into v1.0.9: the panel is only visible mid-edit
  (`searchText != query.text`), and search-as-you-type commits at
  450ms — while suggestions fire at a 200ms debounce plus a real network
  round trip, so the tags land right around or after the commit. On a
  fast connection the chips flash for a frame before the commit hides
  the panel; on a normal one they never render at all. The v1.0.9
  comment "suggestions land before the search commits" was true only of
  the debounce order, never of the network. The fix re-frames the
  session: the panel stays while the field is focused and has anything
  to offer — text the grid is not showing yet, chips that landed, a
  request still in flight (new `suggestLoading` flag, generation-guarded
  so a superseded request can never clear its successor's flag), or a
  blank field with history. Only a submit (IME action, chip tap,
  history tap) or losing the focus ends the session; the debounced
  commit just refreshes the grid behind the panel. An explicit submit
  now also cancels the in-flight suggestion request (via
  `cancelSearchDebounces`), so a late landing can never pop the panel
  back open after the user committed; and re-typing a committed text
  refreshes its chips instead of discarding them (the old guard
  cleared suggestions whenever the typed text equaled the committed
  query). The panel itself gained the dropdown contract it was missing:
  an `AnimatedVisibility` fade/slide, a tap-away scrim below the search
  bar that drops focus (and with it the keyboard) — the search bar
  itself stays tappable so refining is never blocked — and a light
  "Finding tags…" progress row (16dp spinner) that holds the suggestions
  slot while the request is in flight so the panel never flashes
  hollow. Three new ViewModel tests pin the behavior (chips survive the
  commit, re-typed text refreshes, a submit cancels in-flight), the
  third behind a `SlowSuggestSources` delegating fake that adds a
  600ms RTT so the race is real, not simulated (570 total). Gates:
  ktlint clean, 570/0 tests, assembleRelease green after the usual one
  daemon-OOM retry, dex audit PASS with 5,943 host classes and the
  wallhaven plugin ABI intact; CI green; tag `v1.0.19`;
  `Cloudimage-v1.0.19.apk`, 3.16MB.

- **2026-09-27 — v1.0.18 SHIPPED (swipe-synced pill tabs + compact
  switches).** The user's field report, in four parts: the active tab
  pill looked stretched around its label; the pill should move with
  the swipe, not snap at the end; the extension-card toggles were too
  large for the card; the settings toggles likewise. Diagnosis first —
  the stretch was `ScrollableTabRow`'s baked-in 90dp minimum tab
  width: a short label rides in a tab two-thirds padding, and the pill
  (the tab's own background since the v1.0.12 indicator-slot fix)
  inherits every wasted pixel. And a per-tab background can never
  slide — it can only fade in and out at selection time, which is why
  the pill "snapped" on settle. The fix is a new designsystem
  `PillTabRow`: a custom scrollable `Layout` with one shared pill
  drawn behind the labels, whose geometry is interpolated every layout
  pass from the pager's live fraction (`currentPage +
  currentPageOffsetFraction`, read in the measure phase so a running
  swipe re-measures one tiny node per frame — labels never
  recompose). At 50% swipe the pill is halfway between the two tabs
  and has already adopted half the target's width; a cancelled swipe
  rides the pager's own snap-back home. The bar scrolls itself to
  keep the sliding pill in view — but only for pager-driven motion,
  so a finger on the bar itself still browses freely. The pill now
  hugs its label (14dp horizontal padding, 32dp pill height) while
  labels keep full 48dp touch slots with a capsule ripple and Tab
  semantics. The Library's Favorites/History pair moved under the
  same bar as a `HorizontalPager` — both top-level screens now swipe,
  with the same tap/settle contract (bar and pages can never
  disagree). The toggles: `CompactSwitch` (designsystem) wraps the
  material3 switch at 80% — the same colors, thumb animation, and
  behavior at ~42x26dp — and now guards the extension cards, every
  settings switch row, and the onboarding SFW choice. Twelve new
  unit tests pin the interpolation and keep-in-view math as pure
  functions (567 total). Gates: one `ktlintFormat` round
  (chain-wrapping after multiline calls is attach-the-dot style),
  567/0 tests, assembleRelease green after the usual one daemon-OOM
  retry, dex audit PASS with 5,940 host classes and the wallhaven
  plugin ABI intact; CI green; tag `v1.0.18`;
  `Cloudimage-v1.0.18.apk`, 3.17MB.

- **2026-09-27 — v1.0.17 SHIPPED (gallery-grade viewer).** The user's
  field report, in three parts: zooming a fully loaded image looked
  blurry while the same downloaded file zoomed crisp in the phone
  Gallery; the info icon was clutter; no feedback while an image
  downloads. Diagnosis first, as always — the blur was not a URL
  problem (every provider's `fullUrl` is the original file: wallhaven
  `path`, pexels `src.original`, unsplash `urls.full`), it was the
  decode: Coil's default sizing decodes an image to the screen's
  dimensions, and the viewer then scaled that screen-sized bitmap up to
  5x through a graphics layer. The Gallery, meanwhile, decodes
  zoomed-in regions as tiles from the full-resolution original. The
  fix is the same architecture: the viewer now renders through
  telephoto (`me.saket.telephoto:zoomable-image-coil` 0.19.0 + `flick`)
  — the original streams into Coil's disk cache and sub-sampled tiles
  decode on demand as you zoom, so pinch-zoom stays crisp at any
  magnification with no OutOfMemory risk, and the old hand-rolled
  gesture code (which also let the image be panned clean off-screen,
  no clamping, no fling, no focal-point pinch) is retired wholesale.
  Part two, the info icon is gone: a "Swipe up for details" handle
  above the action bar opens the sheet on a half-speed-following
  upward drag (spring-back, haptic tick, fling-aware) or a plain tap —
  the gesture is a bonus, never the only way in. Part three, loading
  feedback: a corner chip reports byte-accurate progress ("47%", or
  "1.2 MB" when the server declares no length) fed by the new
  `ImageProgressRegistry` (core/network) — an OkHttp interceptor on
  the app-wide Coil loader (installed via `ImageLoaderFactory` on the
  application, which also drops the 30 s call timeout so multi-MB
  originals survive slow networks; thumbnails pass through untouched,
  zero cost for unobserved URLs). Polish on top, since the user asked
  for it: a blurred thumbnail backdrop fills the screen while the
  original downloads, a quick downward flick anywhere on the image
  dismisses the screen (disabled while zoomed, so panning always
  wins), the "More like this" carousel steps aside while zoomed so
  nothing competes with pixel inspection, and the info sheet now
  shows the wallpaper's tags as chips. Shipped: ktlint clean, 555
  tests green (+4 registry cases over MockWebServer: monotonic byte
  progress, unknown-length degradation, unobserved pass-through,
  reset freshness; one CI round-trip on a test-import ordering
  nit caught after the fact), assembleRelease green, dex audit PASS
  with 5,960 host classes and the wallhaven plugin ABI intact; CI
  green; tag `v1.0.17`; `Cloudimage-v1.0.17.apk`, 3.17MB.

- **2026-09-26 — v1.0.16 SHIPPED (Cloudflare solves attach to a real
  window).** The v1.0.15 field report: a wallpaperflare install on a real
  phone still surfaced "source failed: wallpaperflare answered HTTP 403".
  Diagnosis from the artifacts, not guesswork — the published
  `Cloudimage-v1.0.15.apk` was pulled and its dex audited: every bypass
  constant present, so the shipped app did carry the machinery; the
  failing half was the solve itself. Two defects found. Defect one, the
  decisive one: `WebViewCloudflareSolver` created its WebView off-window,
  and Cloudflare's challenge scripts refuse to settle there —
  `document.visibilityState` reads "hidden" and the challenge's
  animation-frame work has no surface, so every solve parked until the
  20 s timeout and the raw 403 rode through (the same headless wall the
  recon sandbox hit with a full Chrome). CloudStream's battle-tested
  `CloudflareKiller` attaches its WebView to a dialog window; that is
  what this needed. The solver now runs the challenge inside a
  borderless full-screen `Dialog` over the resumed activity — the page
  gets a real viewport, a visible document, and, if Cloudflare escalates
  to an interactive challenge, the user's own finger (nothing headless
  can fake that; the dialog is honest UI for exactly as long as the
  solve takes). The resumed activity reaches the solver through the new
  `ForegroundActivityTracker` (`ActivityLifecycleCallbacks`, registered
  in the application; identity-checked pause clearing, volatile read
  from any thread) injected alongside the app context in `NetworkModule`
  — when no window exists (Muzei refresh in the background) the detached
  WebView remains the best-effort path, and a dialog that cannot be
  shown (activity dying mid-handoff) falls back to it instead of null.
  A stalled challenge also gets exactly one reload after 6 s — parked
  orchestrations usually run properly on a second load — with the
  heuristic running before the cookie poll's `continue` so an empty jar
  cannot starve it. Defect two, found while probing the live zone: the
  REAL "Attention Required!" block page carries a `challenge-platform`
  script (the ray-ID copy button), so the v1.0.15 marker-only rule
  misclassified hard blocks as challenges and burned a 20 s solve plus
  the 60 s cooldown on IPs no solve can ever save — the detector's own
  KDoc had promised otherwise. `CloudflareChallenge` now consults
  `BLOCK_MARKERS` ("Attention Required", "Sorry, you have been blocked",
  "You are unable to access", "error code: 1020") between the header and
  the interstitial markers; the `cf-mitigated: challenge` header stays
  authoritative over everything. Shipped: ktlint clean, 406 tests green
  (+4 classifier cases: the live block-page shape, the WAF 1020 deny,
  block-copy veto over markers, header outranking block copy),
  assembleDebug green with the new constants dex-verified; CI green; tag
  `v1.0.16`.

- **2026-09-26 — v1.0.15 SHIPPED (app-side Cloudflare bypass + section
  query presets).** The wallpaperflare endgame, attacking both halves of
  the v1.0.14 diagnosis at once. Half one: a provider can never beat a
  Cloudflare challenge alone — it is pure JVM code with no UI toolkit,
  so it cannot run the challenge's JavaScript, and the 0.4.0
  browser-UA fix only helped where the zone wasn't challenging. The app
  can: `:core:network` now ships the bypass the plugin cannot carry.
  `CloudimageHttpClient.getRaw` detects challenge responses
  (`CloudflareChallenge`: the `cf-mitigated: challenge` header plus
  interstitial markers, status-gated so a 200 page that merely mentions
  the copy never wakes the machinery — and the "Attention Required"
  hard-block page deliberately does NOT count, because a WebView cannot
  lift an IP block), asks `CloudflareBypasser` for a clearance and
  replays the request once under it. `CloudflareBypasser` is the state
  machine: per-host serialization (the home fires a dozen section rows
  at one origin — one WebView run clears the whole host), warm starts
  from the WebView cookie jar across launches (clearance cookies survive
  process death; the default WebView User-Agent is reconstructed to
  match, since Cloudflare binds `cf_clearance` to the earning agent),
  stale-state replacement (a clearance that stopped passing is
  re-solved by identity, not re-served), and a 60 s failure cooldown so
  a stubborn zone can't spawn a WebView per grid tile. The engine
  itself is `WebViewCloudflareSolver`: headless WebView, JS + DOM
  storage on, network images off, poll for `cf_clearance`, flush the
  jar, report the cookie header + the earning agent as an inseparable
  pair — the client applies that pair LAST so it overrides both the
  host agent and any provider browser UA for the replay. Unsolved
  challenges pass through as ordinary non-2xx per the facade contract,
  so providers keep their honest failure surface. Half two: home
  sections could only speak category/sorting/order/seed — a tag-first
  source like wallpaperflare browses by term, so
  `ExtensionWallpaperSources.toSourceSection` now honors the documented
  `query` host-vocabulary key as a section's free-text preset (routed
  through the standard blank-vs-text dispatch into the provider's own
  search; `Filters` KDoc updated). Companion release: wallpaperflare
  extension 0.5.0 adds the 12-row home (Popular + Nature, Anime, Space,
  Cars, Gaming, Abstract, Minimalist, Architecture, Animals, Fantasy,
  People presets) on top of this host. Shipped: ktlint clean, 402 tests
  green (+23: 10 detector, 7 state machine, 5 client replay, 1 section
  preset), assembleDebug dex-verified (Cloudflare classes present, Hilt
  graph green); CI green; tag `v1.0.15`.

- **2026-09-26 — v1.0.14 SHIPPED (source-failure reason surfaced).** The
  wallpaperflare follow-up: after adding the third-party repository and
  switching browse to it, the feed showed "A wallpaper source failed to
  load — check the Extensions tab for details." — but the Extensions tab
  only lists LOAD failures; a FETCH failure (the provider's HTTP call,
  e.g. wallpaperflare.com's Cloudflare zone answering 403) was flattened
  into `BrowseError.SOURCE` with its reason thrown away at the taxonomy
  mapping, and the tab showed nothing wrong. A dead-end generic banner
  with no way to tell "stale extension version" from "site blocking the
  client". Diagnosis established the chain end-to-end: the user's
  published 0.4.0 package is sha256-verified and ABI-clean, the app loads
  it READY (that's why the switcher offers it), and the failure is the
  runtime fetch — probes confirm the site challenges both app and
  browser UAs from datacenter IPs, so only a residential verdict counts
  (the 0.4.0 browser-UA fix targets exactly that). Fix:
  `NetworkError.Source.reason` now rides along as
  `BrowseUiState.errorDetail` / `BrowseSectionState.errorDetail`
  (populated at all five failure sites, cleared at every success/reset
  site so it can never go stale), rendered as a caption line under the
  banner in `SectionGridFooter`, `BrowseGridFooter` and
  `FullScreenError` — "wallpaperflare answered HTTP 403 for …" is now
  readable right where the error is. Shipped as `eac06c9`: ktlint clean,
  524 tests green (+2 reason-preservation/clearing), assembleRelease
  3.06MB, dex audit PASS; CI green; tag `v1.0.14` → release green →
  signed `Cloudimage-v1.0.14.apk` (2.93MB). Companion user guidance:
  if the installed row reads v0.3.0, tap the catalog row's Update
  affordance (host has offered update-installs since v1.0.9).

- **2026-09-26 — v1.0.13 SHIPPED (repo add fixes).** Adding a repository
  by its GitHub page URL — the address users copy from the browser
  (`github.com/{owner}/{repo}[.git]`) — 404ed, because `normalizeUrl`
  blindly appended `index.json` to the landing page. The ecosystem
  publishes extension repositories to the repo's `gh-pages` branch, so
  that URL form now resolves to
  `raw.githubusercontent.com/{owner}/{repo}/gh-pages/index.json` (deeper
  github paths keep the default handling). The add-repo dialog also
  closed itself on confirm before the fetch resolved, so a failure left
  no visible trace; it now lives until the add resolves — `RepoAdded`
  closes it, a failure keeps it open with the error under the field,
  and dismissing clears the stale error. Found via the user's own
  wallpaperflare repository (a third-party extension repo,
  `alamsamir7666-ux/WallpaperExtension`): its gh-pages index and
  package are fully ABI-clean against the host dex (verified with the
  new `scripts/audit_remote_plugin.py`: 39 classes, 28 external refs,
  0 unresolved) — only the pasted URL form stood between it and install.
  Shipped as `66c9f0f`: ktlint clean, 522 tests green (+3 GitHub-URL
  normalization), assembleRelease 3.06MB, dex audit PASS; CI green; tag
  `v1.0.13` → release green → signed `Cloudimage-v1.0.13.apk` (2.93MB).

- **2026-09-26 — v1.0.12 SHIPPED (UI hotfix).** Two user-reported
  regressions. (1) The v1.0.11 home tab bar drew its full-height
  secondaryContainer pill in `ScrollableTabRow`'s indicator slot, which
  material3 renders ON TOP of the tab content — the active tab's label
  vanished behind its own pill. The pill is now the `Tab`'s own background
  (inset padding, `clip(RoundedCornerShape(50%))`,
  `animateColorAsState(secondaryContainer <-> Transparent)`), the
  indicator slot stays empty; the label always draws on top, and the
  highlight fades with selection instead of sliding between positions.
  (2) The Extensions screen's status chips could stack their label one
  syllable per line when the chips row ran out of width (the trailing
  `Disabled` chip was measured with the squeezed remainder):
  `LabelChip` is single-line by construction (`maxLines = 1`,
  `softWrap = false`) and the row is a `FlowRow`, so overflow chips wrap
  as whole chips to the next line. Shipped as `a811aad`: ktlint clean,
  519 tests green, assembleRelease 3.06MB, dex audit PASS (5,668 host
  classes); CI green; tag `v1.0.12` → release green → signed
  `Cloudimage-v1.0.12.apk` (2.93MB).

- **2026-09-26 — v1.0.11 SHIPPED (tabbed home).** The home's stacked
  carousels (Recently applied + every provider section) became a
  horizontal tab bar at the top of the content area: the personal
  Recently applied tab leads whenever it exists, then every declared
  section (Trending, Latest, Anime, People on wallhaven). Tapping a tab
  and swiping the HorizontalPager are one selection — both land in
  `BrowseUiState.homeTabKey` via `onHomeTabSelected`, so the pill bar
  (ScrollableTabRow, rounded secondaryContainer indicator that slides
  between positions) and the pager can never disagree. Default active
  tab on load: Recently applied whenever the user has applied anything,
  else the first section; a vanished pick (source switch) degrades to
  the bar's head, never a dead index. Each section page is a
  two-column staggered grid fed by the section's own prefetch
  pagination (skeleton placeholders, load-more footer, inline retry,
  end-of-feed empty state); the Recently applied page is the same grid
  without pagination. See-all is superseded by the tabs (each page IS
  the full feed; the scoped-grid VM flow stays for tests). The FAB
  scroll tracker watches the active page's grid via a hoisted
  MutableState. Shipped as `e292de4`: ktlint clean, 519 tests green
  (+4), assembleRelease 3.06MB, dex audit PASS (5,670 host classes);
  CI green; tag `v1.0.11` → release green → signed
  `Cloudimage-v1.0.11.apk` (2.93MB).

- **2026-09-26 — v1.0.10 SHIPPED (hotfix).** The Extensions screen crashed
  on entry for most users: the Part 3 stats bar fed every population count
  straight into `Modifier.weight(count)`, and Compose's `weight()` throws
  `IllegalArgumentException` on 0 — one enabled extension with nothing
  disabled and nothing available (every fresh install) took the screen
  down. Fix on `8ca7905`: segments now come from
  `ExtensionsUiState.statsSegments()`, which drops empty populations by
  construction; three regression tests lock the invariant. Local gate
  ktlint clean, 515 tests green (+3), dex audit PASS; CI green; tag
  `v1.0.10` → release green → GitHub Release with signed
  `Cloudimage-v1.0.10.apk` (2.9MB). Lesson: derived UI weights are
  invariants, not formatting — a zero segment is a crash, so the segment
  list is built where it can be tested.

- **2026-09-26 — v1.0.9 SHIPPED.** Release gate complete on `638766b`
  (versionCode 10 / versionName "1.0.9"): local gate — ktlint clean,
  512 unit tests green (312 debug + 156 release + 44 plain-JVM;
  CI-comparable 356), assembleRelease 3.03MB, dex audit PASS
  (5,600 host classes; wallhaven zip 36 classes, 32 external refs,
  0 unresolved); CI build green; tag `v1.0.9` → release run green →
  GitHub Release v1.0.9 with signed `Cloudimage-v1.0.9.apk` (2.9MB).
  The four-part CloudStream-grade UX roadmap is fully closed out.
  Environment note: sandbox was wiped again before the gate — Azul's
  zulu17.52.17 CDN URL is now dead; recovery used Temurin 17.0.20.1
  via the Adoptium API (`api.adoptium.net/v3/binary/latest/17/ga/...`).

- **2026-09-26 — v1.0.9 Part 4 done.** More like this + Recently applied +
  housekeeping on main (`34a2965`), CI green. Details in the Part 4 entry
  above; test-counting correction: the suite's honest CI-comparable size is
  352 (debug-variant Android modules + plain-`test` JVM modules), not the
  301 recorded after Part 3.

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
- **2026-09-25 — v1.0.9 Part 3 done (extensions platform).** The
  extension manager grew up. Update detection: the catalog row compares
  its versionCode against the installed manifest (versionName as the
  equal-code tiebreak) and offers an "Update to vX" chip whose action
  is a plain install-over — the engine's replace-in-one-step and
  sha256/manifest gates stay the only authority on whether bytes
  actually move, the UI only labels the button. Per-extension
  enable/disable: every installed row carries a Switch persisted as a
  disabled-id set in DataStore; a disabled source drops out of
  `sources`, `readyProviders`, merged search, sections, suggestions
  and the browse switcher while staying installed — disabling also
  clears a browse pin that names it (the pin must never dead-end
  browse behind the manager's back), and a pinned query on a disabled
  source fails with "enable it in the Extensions tab" rather than
  masquerading as connectivity. The "every source is disabled" case
  gets its own honest failure reason. The proportional stats bar
  renders enabled/disabled/available as weighted segments with a
  color legend, driven off the same UiState the rows read. Catalog
  search filters rows by id-contains (case-insensitive — "unsplash"
  finds "cloudimage.unsplash") and folds repos with no match; each
  repo header gains a check-for-updates refresh with an in-flight
  guard. installPackage now says Updated for install-overs. Counting
  correction riding along: the suite's honest size is the debug
  variant's 301 tests (+24 here: 4 datastore, 7 sources bridge, 13
  ViewModel) — the earlier "476" had stale release-variant results
  mixed in; CI's `test` task is debug-only. ktlint clean; dex audit
  PASS (5,581 host classes, wallhaven 35 classes/32 external refs/0
  unresolved); one known lintVital OOM recovered by the usual
  kill-daemon-and-rerun recipe. Environment was wiped again mid-gap
  and recovered per the handoff recipe — JDK is now Temurin
  17.0.12 at /home/z/jdks/jdk-17.0.12 (the Azul CDN URL 404s now).
  CI green on f9f27f6.
