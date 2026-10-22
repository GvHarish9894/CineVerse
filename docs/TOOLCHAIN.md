# Toolchain and version tuple (pinned in P0)

Pinned by the P0 smoke build (plan §6/§7), not assumed. Every version below is what Gradle **resolved**
(`./gradlew :<module>:dependencies`), on 2026-10-22, macOS (Apple silicon). Declared pins live in
`gradle/libs.versions.toml`; change them only together with this file and a green P0-style smoke.

## Toolchains

| Tool | Version | Where pinned |
|---|---|---|
| Gradle | 9.6.0 | `gradle/wrapper/gradle-wrapper.properties` |
| Gradle daemon JVM | Java 25 (any vendor; foojay-provisioned) | `gradle/gradle-daemon-jvm.properties` |
| Launcher / CI JDK | Temurin 21 | `.github/workflows/*.yml` |
| Android Gradle Plugin | 9.4.1 (`com.android.application`, `com.android.kotlin.multiplatform.library`) | `agp` |
| Kotlin | 2.3.21 (multiplatform, jvm, compose compiler, serialization) | `kotlin` |
| KSP | 2.3.12 (KSP2; Room compiler in `shared`: `kspAndroid`/`kspJvm`/`kspIosArm64`/`kspIosSimulatorArm64`, since P1) | `ksp` |
| Compose Multiplatform | 1.11.1 (Gradle plugin `org.jetbrains.compose`) | `composeMultiplatform` |
| Android SDK | compileSdk / targetSdk 37; minSdk: `tvApp` 28 (D8, lowered in P3 with a minSdk-28 lint pass), `androidApp`/`composeApp`/`shared` 26 | module build files |
| Java bytecode | `tvApp`/`androidApp` Java 11; `server` toolchain 21 | module build files |
| Xcode | CI: **26.6** (17F113, default of the `macos-26` Apple-silicon runner image; hosted runners do not ship 27.x — checked against actions/runner-images `macos-26-arm64-Readme.md`). Local: 27.1 (27A9275). Both target iOS 16.0 | `mobile-macos.yml` (`XCODE_VERSION`, `DEVELOPER_DIR`) |
| iOS deployment target | 16.0, Apple-silicon simulator only (no `iosX64`; `x86_64` excluded for simulator builds) | `iosApp.xcodeproj` |
| iOS simulator used locally | iPhone 17 Pro, iOS 26.4.1 | — |
| Node.js / Yarn / Binaryen (Kotlin/Wasm) | 25.0.0 / 1.22.17 / version_125 (KGP 2.3.21 defaults, resolved from the settings repositories) | `settings.gradle.kts`, `kotlin-js-store/wasm/yarn.lock` |

## Library tuple

| Element | Declared coordinates | Resolved |
|---|---|---|
| Compose Multiplatform | `org.jetbrains.compose.{runtime:runtime,foundation:foundation,ui:ui}:1.11.1` | 1.11.1; on Android → `androidx.compose.*` 1.11.2; Skiko 0.144.6 |
| Lifecycle (KMP) | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`, `-viewmodel-savedstate`, `-viewmodel-compose:2.9.6` | 2.9.6 (`lifecycle-runtime-compose` 2.9.6 transitively); on Android → `androidx.lifecycle` 2.9.4 |
| Savedstate (KMP) | transitive | `org.jetbrains.androidx.savedstate:savedstate*:1.3.6` |
| Navigation Compose (KMP) | `org.jetbrains.androidx.navigation:navigation-compose:2.9.2` | 2.9.2; on Android → `androidx.navigation:navigation-compose` 2.9.7 |
| Ktor client | `io.ktor:ktor-client-{core,content-negotiation,mock}`, `ktor-serialization-kotlinx-json:3.6.0` | 3.6.0 |
| Ktor engines | `ktor-client-okhttp` (android), `-darwin` (ios), `-java` (jvm), `-js` (wasmJs) `:3.6.0` | 3.6.0 |
| Ktor server | `io.ktor:ktor-server-{core,cio,rate-limit,cors,forwarded-header,test-host}:3.6.0` (+ `ktor-client-java`/`-mock` for the TMDB upstream/tests), `org.slf4j:slf4j-simple:2.0.19` | 3.6.0 / slf4j 2.0.19 |
| Coil | `io.coil-kt.coil3:coil-compose`, `coil-network-ktor3:3.4.0` (same `coil` pin as `tvApp`) | 3.4.0 |
| Room (nonWeb only) | `androidx.room:room-runtime:2.8.5` (same pin as `tvApp`) | 2.8.5 |
| SQLite bundled driver (P1; `shared` jvm/iOS **tests** only) | `androidx.sqlite:sqlite-bundled:2.6.2` — the `androidx.sqlite` version Room 2.8.5 already resolves | 2.6.2 |
| DataStore (nonWeb only) | `androidx.datastore:datastore-preferences-core:1.2.1` (same `datastore` pin as `tvApp`) | 1.2.1 |
| atomicfu | `org.jetbrains.kotlinx:atomicfu:0.33.0` (library only, no Gradle plugin) | 0.33.0 |
| kotlinx-datetime | `org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat` | 0.8.0-0.6.x-compat (Ktor 3.6.0's `ktor-openapi-schema` requires the compat flavour; plain 0.8.0 is upgraded to it) |
| kotlinx-coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0` (catalog `kotlinxCoroutines`, also `tvApp`'s `coroutines-test`) | 1.11.0 |
| kotlinx-serialization | transitive via Ktor | 1.11.0 (`tvApp` keeps its own 1.9.0 declaration) |
| kotlinx-browser | `org.jetbrains.kotlinx:kotlinx-browser:0.5.0` (wasmJs) | 0.5.0 |
| Koin | `io.insert-koin:koin-core:4.2.2` — declared only; wiring is P5 | 4.2.2 |

`tvApp` dependencies are unchanged except `kotlinx-coroutines-test` 1.10.2 → 1.11.0 (test classpath only, shared
catalog key). Nothing in `tvApp` was downgraded; `tvApp` does not depend on `shared` yet (P1). When it does,
Gradle resolves the higher Android versions `tvApp` already uses (lifecycle 2.11.0, navigation 2.10.2).

**P1:** `tvApp` depends on `shared`. Through it, `tvApp`'s release runtime classpath now resolves the tuple's
`kotlinx-coroutines` 1.11.0 (was 1.10.2), `kotlinx-serialization` 1.11.0 (was 1.9.0), `okio` 3.18.2 (was 3.18.1) and
adds Ktor 3.6.0 / kotlinx-datetime / atomicfu / Koin (declared, unused until P5; R8 strips it). No catalog version
changed and nothing was downgraded.

**P2:** `tvApp`'s TMDB client is Ktor 3.6.0 on the OkHttp engine (`ktor-client-okhttp`, from the tuple) via `:shared`;
Retrofit 3.0.0 and `okhttp` `logging-interceptor` are removed from `tvApp` and the catalog. `okhttp` 5.5.0 stays
declared in `tvApp` (Coil's image client and the Ktor engine resolve it).

## Decisions and fallbacks (recorded)

- **DataStore 1.3.0 → 1.2.1 (supervisor-approved).** `:shared:compileKotlinIosSimulatorArm64` with 1.3.0 failed:
  ```
  w: KLIB resolver: Skipping '.../androidx.datastore/datastore-core-okio-iossimulatorarm64/1.3.0/.../datastore-core-okio-iosSimulatorArm64Main-1.3.0.klib' having incompatible ABI version '2.4.0'. The library was produced by '2.4.20' compiler.
  The current Kotlin compiler can consume libraries having ABI version <= '2.3.0'.
  Please upgrade your Kotlin compiler version to consume this library.
  ```
  The DataStore 1.3.0 klibs (1.3.0-rc01 and 1.3.0) are built with Kotlin 2.4.20 (1.3.0-alpha08…alpha11: 2.3.20;
  1.2.1: 2.2.20). In addition, DataStore 1.3.0's wasm storage is **sessionStorage-only** (b/441511612 — localStorage
  and OPFS are TODO), so it would not have served web settings anyway. Result: `datastore-preferences-core` 1.2.1
  on `shared/nonWebMain` (same pin as `tvApp`, no TV change) and a localStorage-backed `BrowserSettingsStore` on
  `wasmJs`. No alpha. **Revisit when the repo moves to Kotlin 2.4.**
- **Coil 3.4.0, not 3.5+.** Coil 3.5.0+ is built with Kotlin 2.4 (klib ABI 2.4), which Kotlin 2.3.21 cannot
  consume on iOS/wasm; 3.4.0 (Kotlin 2.3.10) is also `tvApp`'s existing pin. The Compose plugin warns that Coil
  3.4.0 was built against Skiko 0.9.22.2 while CMP 1.11.1 resolves Skiko 0.144.6; images load at runtime on iOS and
  wasm (see smoke evidence), so the warning is accepted until the Kotlin 2.4 move allows Coil 3.5+.
- **Lifecycle 2.9.6 / navigation 2.9.2** follow the plan's 2.9.x line; CMP 1.11.1 UI itself requires lifecycle
  ≥ 2.9.6 and navigation-compose 2.9.2 is the navigation version CMP 1.11.1 ships with.
- **Wasm toolchain repositories.** Settings keep `FAIL_ON_PROJECT_REPOS`, so Node.js, Yarn and Binaryen are
  declared as exclusive ivy repositories in `settings.gradle.kts` and KGP's own `downloadBaseUrl`s are cleared in
  the root `build.gradle.kts`.
- **`rootProject.name` "Cine Verse" → "CineVerse".** Kotlin/Wasm derives npm package names from it and Yarn
  rejects the space (`package.json: Name contains illegal characters`). No effect on application ids or artifacts.
- **`:shared:testDebugUnitTest`** is an alias for the AGP-9 KMP library's `testAndroidHostTest`, so every module
  keeps the same gate task name.
- **Xcode destination.** `iPhone 17 Pro` exists locally only with iOS 26.4.1, so the local command pins
  `OS=26.4.1`; CI uses `generic/platform=iOS Simulator` with `CODE_SIGNING_ALLOWED=NO`.
- The iOS link prints `ld: warning: object file (...ComposeApp(libicu.icudtl_dat.o)) was built for newer
  'iOS-simulator' version (18.5) than being linked (16.0)` — ICU data shipped inside the Compose/Skiko klibs. Not a
  P0 blocker; must be checked on an iOS 16 simulator/device before release (P10).

## Secrets

- Native read token: `secrets.properties` (git-ignored) only. `tvApp` (unchanged) and `androidApp` read it into
  `BuildConfig.TMDB_READ_ACCESS_TOKEN`; `iosApp`'s "Inject native read token" build phase writes it into the built
  app's Info.plist (`TMDBReadAccessToken`). Entry points pass it to `App(nativeReadToken)`; there is no
  common/default generated token field.
- Server: environment `TMDB_READ_ACCESS_TOKEN` only (warns at start-up when missing).
- Wasm: no token. `:composeApp:checkWasmHasNoSecrets` builds the wasm distribution and scans every
  `.wasm/.js/.mjs/.map` in the production outputs (UTF-8 and UTF-16LE) for the configured token and the sentinel
  `SENTINEL_TMDB_TOKEN_DO_NOT_SHIP`; it fails when the token is absent from `secrets.properties` (nothing to scan
  for) and self-checks that a known Kotlin string literal is found in the `.wasm`. CI's web job writes the sentinel
  into `secrets.properties` first.

## P0 smoke (what each target proves)

- `shared/commonMain` `SmokeKit.kt`: `SmokeViewModel` (lifecycle `ViewModel` + `SavedStateHandle`), the
  `SettingsStore` contract, `fetchImageBaseUrl` (Ktor GET `/3/configuration`, MockEngine in tests).
  `nonWebMain`: `DataStoreSettingsStore`; `wasmJsMain`: `BrowserSettingsStore`.
- P1 replaced the smoke `SettingsStore` by the typed `data/settings/SettingsStore` contract (string / boolean /
  string-set keys, atomic multi-key `edit`); `DataStoreSettingsStore` and `BrowserSettingsStore` moved to
  `data/settings` with it.
- Tests: `SmokeKitTest` (common) and `SettingsStoreContractTest` (write → read → remove) run on jvm, android host,
  iOS simulator (DataStore actual, plus a reopen-the-file persistence test) and wasm in headless Chrome
  (browser actual).
- `composeApp`: one Navigation Compose route, `viewModel { SmokeViewModel(createSavedStateHandle()) }`, a Coil
  `AsyncImage` from `image.tmdb.org` (credential-free); logs `CineVerseSmoke image=success|error`. Wasm `main()`
  lives in `composeApp/wasmJsMain`; iOS static framework `ComposeApp`.

## Gate commands

```bash
export ANDROID_HOME=~/Library/Android/sdk DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew :shared:compileKotlinWasmJs :shared:jvmTest :shared:testDebugUnitTest :shared:iosSimulatorArm64Test :shared:wasmJsBrowserTest
./gradlew -q :shared:dependencies --configuration wasmJsRuntimeClasspath | grep -c room   # must print 0
./gradlew :composeApp:wasmJsBrowserDistribution :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:checkWasmHasNoSecrets
./gradlew :androidApp:assembleDebug :server:test
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.4.1' build
PORT=8080 ./gradlew :server:run & curl http://127.0.0.1:8080/healthz          # "ok"
./gradlew :tvApp:assembleDebug :tvApp:lint :tvApp:testDebugUnitTest :tvApp:assembleRelease
```
