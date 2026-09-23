# Cloudimage

[![build](https://github.com/alamsamir7666-ux/Cloud-Wallpaper/actions/workflows/build.yml/badge.svg)](https://github.com/alamsamir7666-ux/Cloud-Wallpaper/actions/workflows/build.yml)

**A CloudStream-inspired wallpaper app for Android.** The app is an engine; the
content is plugins. Cloudimage ships as a clean, fast Material 3 client and
gets its wallpaper sources from user-added extension repositories — with
official providers (Wallhaven, Unsplash, Pexels, Pixabay) published in the
official repo from day one.

> **Status:** `v0.1.0` — Part 1 of 8 (foundation). See [PLAN.md](PLAN.md).

## What it will look like

- **Browse** — staggered masonry grids, search with filters (category, purity,
  sorting, aspect ratio), infinite scroll (Part 3)
- **Preview & apply** — fullscreen zoomable preview, set as home / lock /
  both via `WallpaperManager`, download manager (Part 4)
- **Extensions** — CloudStream-style runtime plugins loaded from APKs,
  distributed through GitHub-hosted JSON repos (Parts 5–6)
- **Your data** — favorites, history, per-provider API keys, SFW toggle
  default-on (Part 7)

## Architecture

Single-activity Jetpack Compose app, unidirectional data flow, feature modules.

| Module | Purpose |
|---|---|
| `:app` | Shell, navigation, theme, DI wiring |
| `:core:model` | App-internal domain models |
| `:core:data` | Repositories — favorites, history (fakes in `:core:testing`) |
| `:core:database` | Room — favorites, history, downloads |
| `:core:datastore` | Preferences DataStore — settings |
| `:core:network` | Shared OkHttp client handed to every provider |
| `:core:testing` | Test doubles (fake repositories, dispatcher rule) |
| `:feature:browse` | Home grid, search |
| `:feature:detail` | Preview & apply |
| `:feature:extensions` | Plugin/repo manager |
| `:provider:api` | **The extension contract** — plugins implement this |

Build logic lives in `build-logic/` (convention plugins over a version
catalog), so every module's build file stays a few lines long.

### Tech stack

Kotlin 2.0 · Jetpack Compose (BOM) · Material 3 with dynamic color ·
Navigation Compose · Hilt · Room · DataStore · WorkManager · Coil · OkHttp ·
kotlinx.serialization · ktlint · GitHub Actions CI

## Build from source

Requirements: JDK 17+, Android SDK 35 (or let Android Studio handle it).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

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
