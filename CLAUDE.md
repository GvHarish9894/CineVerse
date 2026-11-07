# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

CineVerse is a TMDB client that exists as **two parallel app worlds** in one Gradle build. Knowing which world a change belongs to is the first thing to get right:

1. **`tvApp/`** — the original, feature-complete Android TV app (`com.techgv.cineverse`), D-pad-first, built on `androidx.tv.material3`. Self-contained: all of its `data/`, `domain/`, `ui/` lives under `tvApp/src/main/java/`. It depends on `:shared` (since migration phase P1) but **not** on `:composeApp`. This is the mature surface; see `README.md`.
2. **The KMP stack** — a Kotlin Multiplatform migration (web-first) targeting browser (wasm), Android phone, and iOS, sharing one Compose UI and one data layer:
   - `shared/` — KMP library: data (TMDB/Ktor, auth, library, settings, storage), domain, presentation. Targets android, iosArm64, iosSimulatorArm64, jvm, wasmJs.
   - `composeApp/` — KMP Compose UI library. Produces the wasm `main()`, the iOS static framework `ComposeApp`, and the Android phone UI.
   - `androidApp/` — phone app (`com.techgv.cineverse.mobile`), consumes `:composeApp`.
   - `iosApp/` — Xcode project hosting the `ComposeApp` framework.
   - `server/` — Ktor JVM server (`GET /healthz`), also the TMDB upstream proxy surface.

`docs/IMPLEMENTATION_SPEC.md` is the normative product spec (TMDB endpoint map, auth flow, per-screen rules). `docs/KMP_ARCHITECTURE_PLAN.md` + the `docs/WAVE_*_PLAN.md` / `docs/*_PLAN.md` files track the migration phases (P0…P10). `SHELL_CHANGES.md` is the integration record (shell contract, merge log, open release gates).

## Architecture notes that span multiple files

- **Source-set hierarchy (`nonWebMain` vs `wasmJs`).** Both `shared` and `composeApp` define a custom `nonWebMain` / `nonWebTest` source set (android + ios + jvm `dependsOn` it) that sits between `commonMain` and the platform sets. Room, the DataStore settings store, the bundled SQLite driver, and the native TMDB read token live in `nonWebMain` **only**. The `wasmJs` target has none of these: settings use a localStorage-backed `BrowserSettingsStore`, and the web build ships **no token** (TMDB access is proxied). When adding a dependency or `actual`, decide deliberately whether it belongs in `commonMain`, `nonWebMain`, or one platform set.
- **Platform shell injection.** `composeApp`'s entry point is `CineVerseApp(shell: ShellStyle, nativeReadToken: String?)` in `composeApp/.../ui/CineVerseApp.kt`. Each platform passes its own chrome — `MobileShell` (Android/iOS) or `WebShell` (wasm) from `ui/shell/Shells.kt` — and its own token (null on web). There is no common default token field; see `docs/TOOLCHAIN.md` §Secrets.
- **Design is token- and canvas-driven.** The UI is laid out in 1280×720 prototype design units scaled to the real window (`DesignCanvas`), so it renders identically on 720p/1080p/4K and across form factors. Design tokens are **generated at build time** by `:composeApp:generateDesignTokens` from `design/mobile/tokens.json` + `design/web/tokens.json` into `composeApp/build/generated/designTokens/` (not checked in) — edit the JSON, not generated Kotlin. See `docs/kits/TOKEN_CONFLICTS.md`.
- **Secrets never reach web.** `:composeApp:checkWasmHasNoSecrets` builds the wasm distribution and scans every `.wasm/.js/.mjs/.map` for the token and a sentinel; it is a required gate. Native token flows into `BuildConfig.TMDB_READ_ACCESS_TOKEN` (`tvApp`, `androidApp`) or the iOS Info.plist; the server reads env `TMDB_READ_ACCESS_TOKEN`.

## Version pins are interlocked — do not bump casually

Every version in `gradle/libs.versions.toml` is pinned against a resolved, green smoke build documented in **`docs/TOOLCHAIN.md`**. Several pins are load-bearing ceilings, not laziness: Kotlin is **2.3.21**, which caps Coil at 3.4.0 and DataStore at 1.2.1 (newer klibs have an ABI the 2.3 compiler cannot consume on iOS/wasm). AGP is **9.4.1** (`com.android.kotlin.multiplatform.library` for KMP modules). When changing any toolchain or library version, update `docs/TOOLCHAIN.md` in the same change and re-run a gate-style smoke. minSdk differs per module (`tvApp` 28; `androidApp`/`composeApp`/`shared` 26); compile/targetSdk 37.

## Common commands

Set up first: `export ANDROID_HOME=~/Library/Android/sdk DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`. TMDB native token goes in git-ignored `secrets.properties` (`TMDB_READ_ACCESS_TOKEN=<v4 token>`); the build compiles without it (API calls then fail visibly).

### TV app (`tvApp`)
```bash
./gradlew :tvApp:assembleDebug
./gradlew :tvApp:installDebug                         # to a running Android TV emulator/device
./gradlew :tvApp:lint :tvApp:testDebugUnitTest        # ~200 JVM unit tests
./gradlew :tvApp:assembleDebugAndroidTest             # D-pad instrumentation tests (run via adb am instrument)
```
Primary dev target: an Android TV AVD (API 36, 1080p), named `CineVerse_TV_1080p_API36` during development.

### KMP stack (shared / composeApp / server / iOS)
```bash
# shared: multiplatform unit tests (jvm, android host, iOS sim, wasm headless Chrome)
./gradlew :shared:jvmTest :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :shared:wasmJsBrowserTest

# composeApp: build each target + the no-secrets gate
./gradlew :composeApp:wasmJsBrowserDistribution :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:checkWasmHasNoSecrets
./gradlew :composeApp:testDebugUnitTest :composeApp:iosSimulatorArm64Test :composeApp:wasmJsBrowserTest

./gradlew :androidApp:assembleDebug
PORT=8080 ./gradlew :server:run & curl http://127.0.0.1:8080/healthz             # "ok"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.4.1' build
```
Note: `:shared:testDebugUnitTest` is an alias for the AGP-9 KMP library's `testAndroidHostTest` (every module keeps the same gate task name). The full gate command list is in `docs/TOOLCHAIN.md` §Gate commands.

### Running a single test
```bash
./gradlew :shared:jvmTest --tests "com.techgv.cineverse.*SettingsStoreContractTest"
./gradlew :tvApp:testDebugUnitTest --tests "com.techgv.cineverse.*SomeTest.someMethod"
```

### HTTP debugging
Debug builds log HTTP headers only (auth bodies, tokens, `session_id` always redacted). Enable a bounded 2 KB body preview with `adb shell setprop log.tag.TmdbHttp VERBOSE`. Release builds have no HTTP logging.

### Running KMP UI tests (what actually works)
- **Host (JVM) tests:** `./gradlew :composeApp:testAndroidHostTest` (`nonWebTest` + `androidHostTest`; ViewModels, pure UI logic).
- **Mobile Compose UI tests** live in `composeApp/src/mobileUiTest` and run on Android device and iOS simulator. The quickest loop is the iOS simulator:
  `./gradlew :composeApp:iosSimulatorArm64Test --device 1F4DFB14-7325-4855-BE5E-4DD01D97D168 --tests '*MobileDetailScreenTest'` (iPhone 17 Pro; `xcrun simctl boot` it first, shut it down after). Results: `composeApp/build/test-results/iosSimulatorArm64Test/*.xml`. These run in an in-memory `SkikoComposeUiTest` scene (no UIWindow), so treat them as component-level evidence.
- **Android device tests:** do **not** use `connectedAndroidDeviceTest`. It can uninstall the app (wiping its data) and can report BUILD SUCCESSFUL after running 0 tests. Assemble the test APK and run `adb -s <serial> shell am instrument -w ...` instead.
- **Web:** `./gradlew :composeApp:wasmJsBrowserTest --tests '*WebSearch*'` (needs `CHROME_BIN`), plus `:composeApp:webAccessibilityRealPageTest` (real-page CDP accessibility harness, 7 cases; not in CI yet).
- Useful Gradle flags: `--max-workers=2 --no-configure-on-demand --console=plain`. Never weaken assertions or raise timeouts to make a test pass.

## Where the KMP UI lives
- Phone/tablet (Android + iOS): `composeApp/src/nonWebMain/kotlin/com/techgv/cineverse/ui/mobile/` (`Mobile*Screen.kt` + `Mobile*ViewModel.kt`, navigation in `MobileNavigation.kt`).
- Web: `composeApp/src/wasmJsMain/kotlin/com/techgv/cineverse/ui/web/` (`Web*.kt`).
- Shared components (PosterCard, Skeleton/shimmer, bars): `composeApp/src/commonMain/kotlin/com/techgv/cineverse/ui/components/`.
- Shared state holders: `shared/src/commonMain/kotlin/com/techgv/cineverse/presentation/`.
- A UX fix usually has to be applied on mobile, web **and** TV (TV has its own components under `tvApp/.../ui/`); check all three.
- Design references: `docs/kits/mobile/` and `docs/kits/web/` (SCREENS, INTERACTIONS, BUILD_PLAN).

## Working rules for agents
- **Do not commit or push unless the owner explicitly approves.** Make changes in the working tree and report the changed files. When approved, commit as `GvHarish9894 <hariharangv9894@gmail.com>` (author and committer) and stage explicit paths only.
- One workspace only: this checkout on `master`. Don't create extra worktrees or branches unless asked.
- Never stage the owner's `.idea/` files.
- When the owner says they will test, only compile; don't run device checks.
- Credentials for manual TMDB acceptance are in the git-ignored `.env` (`TMDB_USERNAME` is a username, not an email). Never print, log or type them in plain text.
- **Devices:** the TV AVD `CineVerse_TV_1080p_API36` holds the owner's real signed-in session. Never sign it out, clear its data or uninstall the app there; run guest-state tests as a secondary Android user. Boot owned emulators with `-no-snapshot-save`. Don't touch physical devices or the owner's Android Studio emulators unless asked.
- iOS command-line builds need `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` (local Xcode 27.2).

## Network notes
- Debug builds of `androidApp` trust user-installed CAs (`res/xml/network_security_config.xml`, `debug-overrides` only) so HTTP Toolkit/Charles can inspect traffic; release trusts system CAs only.
- On some Indian ISPs, connections to `api.themoviedb.org` are intermittently reset (`ECONNRESET`), while `api.tmdb.org` (official alias) works. This shows up when traffic goes through a Mac-side proxy such as HTTP Toolkit; it is a network issue, not an app bug.

## CI

Three GitHub Actions workflows: `.github/workflows/tv-linux.yml` (TV build/lint/unit/release), `shared-server-linux.yml` (shared tests, server, wasm secret scan — CI writes a sentinel into `secrets.properties`), and `mobile-macos.yml` (iOS + phone builds; pins `XCODE_VERSION` / `DEVELOPER_DIR`). CI launcher JDK is Temurin 21; the Gradle daemon JVM is Java 25 (foojay-provisioned).
