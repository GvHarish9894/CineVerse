# CineVerse for Android TV

A D-pad-first Android TV client for [TMDB](https://www.themoviedb.org): browse trending, popular and
top-rated movies, TV shows and people; read details, cast, reviews and episodes; play trailers; keep a
watchlist, favourites and ratings — as a guest on the TV or synced to your TMDB account.

Built with Jetpack Compose + `androidx.tv.material3`, Kotlin, Retrofit/OkHttp, Room, DataStore, Coil
and the YouTube IFrame player.

> This product uses the TMDB API but is not endorsed or certified by TMDB.

## Features

| Area | What you get |
|---|---|
| Home | Hero that follows focus (title or person), Trending today/this week, popular / now playing / upcoming / top rated rows, play trailer from the hero |
| Browse | Movies, TV Shows, People — tabbed 7-column grids with infinite paging |
| Search | On-screen keyboard (and hardware keys), live multi-search, trending suggestions when empty |
| Detail | Hero with certification, runtime and genre chips, score ring, action buttons, seasons, top-billed cast, trailers, recommendations, reviews (with a reading panel), facts; English fallback for untranslated text |
| Person | Biography (D-pad readable), known for, acting/crew credits |
| Season | Season list + episodes with an inline episode panel; survives process death |
| Player | In-app YouTube trailer playback with a control strip **below** the video (no overlays), remote media keys, “Open in YouTube” fallback |
| My Library | Watchlist / Favourites / Rated; guest data stored on the TV, account data via TMDB; rate dialog; guest → account import with a resumable ledger |
| Login | TMDB v4 QR sign-in, “Continue as guest”, sign-in expiry handling |
| Settings | Haptics, navigation sounds, ambient glow, reduce motion, content language, region, adult content, account defaults, About |

Design: all sizes are 1280×720 prototype design units scaled to the real window (`DesignCanvas`), so the
app looks the same on 720p, 1080p and 4K panels.

## Project layout

```
tvApp/src/main/java/com/techgv/cineverse/
├─ data/      tmdb (API, DTOs, repository, cache), auth, library (Room + account), settings
├─ domain/    Title, Person, Detail, Season, Episode, Review, LibraryState, MediaRef …
└─ ui/        nav (AppNav, NavRail, routes), components (cards, dialogs, focus memory, toasts),
              home, browse, search, detail, person, season, player, library, login, settings, about, feedback
shared/       KMP library (android, iosArm64, iosSimulatorArm64, wasmJs, jvm) — P0 smoke only so far
composeApp/   KMP Compose UI library (android, iOS framework `ComposeApp`, wasm `main()`)
androidApp/   phone app (com.techgv.cineverse.mobile), consumes composeApp
iosApp/       Xcode project hosting the ComposeApp framework
server/       Ktor JVM server (`GET /healthz`)
docs/IMPLEMENTATION_SPEC.md   normative spec (TMDB endpoint map, auth flow, per-screen rules, decisions)
docs/KMP_ARCHITECTURE_PLAN.md Kotlin Multiplatform migration plan; docs/TOOLCHAIN.md pinned versions
SHELL_CHANGES.md              integration record: shell contract, merge log, follow-ups, open gates
```

## Getting started

1. **TMDB credentials** — create `secrets.properties` in the repo root (git-ignored):
   ```
   TMDB_READ_ACCESS_TOKEN=<your v4 "API Read Access Token" from https://www.themoviedb.org/settings/api>
   ```
   The build still compiles without it (empty token → API calls fail with a visible error).
2. **Android SDK** — set `ANDROID_HOME` (e.g. `~/Library/Android/sdk`). Requires AGP 9 / Kotlin 2.3 (see
   `docs/TOOLCHAIN.md`).
3. **Build & run**
   ```bash
   ./gradlew :tvApp:assembleDebug
   ./gradlew :tvApp:installDebug   # to a running Android TV emulator or device
   ```
   An Android TV emulator image (API 36, 1080p) is the primary dev target; a Google TV AVD named
   `CineVerse_TV_1080p_API36` was used throughout development.

## Testing

```bash
./gradlew :tvApp:lint :tvApp:testDebugUnitTest   # ~200 JVM tests
./gradlew :tvApp:assembleDebugAndroidTest
adb -s <serial> shell am instrument -w -e package com.techgv.cineverse.ui \
    com.techgv.cineverse.test/androidx.test.runner.AndroidJUnitRunner   # ~38 D-pad tests
```

CI: `.github/workflows/tv-linux.yml` (TV build, lint, unit tests, release build),
`shared-server-linux.yml` (shared tests, server, wasm secret scan) and `mobile-macos.yml` (iOS + phone builds).

Debug builds log HTTP headers only; a bounded 2 KB body preview can be enabled with
`adb shell setprop log.tag.TmdbHttp VERBOSE` (auth bodies, tokens and `session_id` are always redacted).
Release builds have no HTTP logging.

## Status

Feature-complete against `docs/IMPLEMENTATION_SPEC.md`; all code passed multi-round design and code review
(see `SHELL_CHANGES.md`). Remaining **release gates** before a Play Store TV submission:

- `minSdk` is 36 (emulator-only); the Android TV quality checklist requires ≤ 31 — decision pending.
- Live TMDB sign-in smoke (QR approval, account writes, import) on a device.
- Physical Android TV pass and YouTube embedded-player policy check.

## Credits

Data and images © [TMDB](https://www.themoviedb.org). Trailers via YouTube. Not endorsed or certified by TMDB.
