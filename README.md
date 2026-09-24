# Cloudimage

[![build](https://github.com/alamsamir7666-ux/Cloud-Wallpaper/actions/workflows/build.yml/badge.svg)](https://github.com/alamsamir7666-ux/Cloud-Wallpaper/actions/workflows/build.yml)

**A CloudStream-inspired wallpaper app for Android.** The app is an engine; the
content is plugins. Cloudimage ships as a clean, fast Material 3 client and
gets its wallpaper sources from user-added extension repositories — with
official providers (Wallhaven, Unsplash, Pexels, Pixabay) published in the
official repo from day one.

> **Status:** `v1.0.0` — feature complete (8/8 parts). See [PLAN.md](PLAN.md).

## Downloads

Grab the latest signed APK from
[Releases](https://github.com/alamsamir7666-ux/Cloud-Wallpaper/releases/latest) —
or update straight from the app's Settings → Updates card. The official
extension repository ships with the app, so Wallhaven works out of the box;
Unsplash, Pexels and Pixabay install from the same place with your own API
keys.

## What you get

- **Browse** — staggered masonry grids, search with filters (category, purity,
  sorting, aspect ratio), infinite scroll, SFW-only mode enforced at the
  provider layer
- **Preview & apply** — fullscreen zoomable preview, set as home / lock /
  both, download to gallery, share
- **Extensions** — CloudStream-style runtime plugins (dexed zips,
  sha256-verified, API-version gated) from GitHub-hosted JSON repos
- **Your data** — favorites, history, per-provider API keys, full settings,
  first-run onboarding
- **Updates** — in-app updater against GitHub Releases with a signed APK
  handoff to the system installer

## Architecture

Single-activity Jetpack Compose app, unidirectional data flow, feature modules.

| Module | Purpose |
|---|---|
| `:app` | Shell, navigation, theme, DI wiring |
| `:core:model` | App-internal domain models |
| `:core:data` | Repositories — favorites, history, updates (fakes in `:core:testing`) |
| `:core:database` | Room — favorites, history, downloads |
| `:core:datastore` | Preferences DataStore — settings, API keys |
| `:core:network` | Shared OkHttp client handed to every provider |
| `:core:designsystem` | Shared composables (wallpaper card) |
| `:core:testing` | Test doubles (fake repositories, dispatcher rule) |
| `:extensions:core` | Plugin engine — install, verify, load, repos |
| `:providers:*` | Official plugins — wallhaven (bundled), unsplash, pexels, pixabay |
| `:feature:browse` | Home grid, search |
| `:feature:detail` | Preview & apply |
| `:feature:extensions` | Plugin/repo manager |
| `:feature:library` | Favorites & history |
| `:feature:settings` | Settings & in-app updater |
| `:provider:api` | **The extension contract** — plugins implement this |

Build logic lives in `build-logic/` (convention plugins over a version
catalog), so every module's build file stays a few lines long.

### Tech stack

Kotlin 2.0 · Jetpack Compose (BOM) · Material 3 with dynamic color ·
Navigation Compose · Hilt · Room · DataStore · Coil · OkHttp ·
kotlinx.serialization · R8 · ktlint · GitHub Actions CI

## Build from source

Requirements: JDK 17+, Android SDK 35 (or let Android Studio handle it).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Release builds are minified (R8) and resource-shrunk, and pick up signing
from the `CLOUDIMAGE_*` environment variables — see the release workflow
for the full set. Without them, `assembleRelease` produces an unsigned APK.

Lint and tests:

```bash
./gradlew ktlintCheck test
```

## Extension licensing

The app is Apache-2.0. The `:provider:api` artifact is **MIT-licensed**
(see [provider/api/LICENSE](provider/api/LICENSE)) so wallpaper-source authors
can depend on it without adopting our license.

## Legal

Cloudimage hosts no images and bundles no third-party sources. All content
comes from extensions the user installs, exactly like a package manager.
Respect the terms of the services you configure.

## License

```
Copyright 2026 The Cloudimage Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

See [LICENSE](LICENSE) for the full text.
