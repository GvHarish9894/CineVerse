# CineVerse → Kotlin Multiplatform: architecture & project-structure plan (v2)

Goal: turn the finished Android TV app into one Kotlin Multiplatform repo that ships **TV (Android)**,
**Mobile (Android + iOS)** and **Web (Kotlin/Wasm + Ktor proxy)** from one shared data/domain core,
without removing or regressing the TV app.

Inputs: the two design kits (`/Users/harish/Desktop/cineverse-mobile-kmp-kit`,
`/Users/harish/Desktop/cineverse-web-compose-kit`), the existing TV code (`app/`), joreilly's KMP
repos (PeopleInSpace, Confetti, KMP-App-Template, BikeShare), and architecture review round 1 (12 findings,
all adopted below).

Where this document and the kits disagree, this document wins; where it and
`docs/IMPLEMENTATION_SPEC.md` (TV, reviewed) disagree on protocol-level behaviour (auth, error table,
no-overlay player, generations/identity), the TV spec wins and is the normative base for all platforms.

---

## 0. Conventions taken from the reference repos

| Pattern | Seen in | Adopted |
|---|---|---|
| Shared KMP **library** (Ktor API, repositories, state) consumed by thin platform **apps** | PeopleInSpace `common`, Confetti `shared` | ✅ |
| Platform-specific dependency groups via `applyDefaultHierarchyTemplate { group("mobile") … }` | Confetti | ✅ a `native` group (android + ios) holds Room/Keystore/Keychain; `wasmJs` never sees them |
| AGP 9 KMP: `com.android.kotlin.multiplatform.library` for shared libraries; separate Android application modules | Confetti, PeopleInSpace | ✅ (KMP-App-Template's single-module `androidTarget`+application is the older arrangement and does not apply on AGP 9.4) |
| Koin, Ktor, kotlinx.serialization, DataStore/multiplatform-settings, Room KMP, Coil 3 | all | ✅ (Koin **after** extraction, at composition roots only) |
| BuildKonfig for native-only secrets from `local.properties` | Confetti | ✅ native entry points only — never a common/default field |
| CI per host/target; Maestro flows | Confetti | ✅ |

---

## 1. Module layout (five modules)

```
CineVerse/
├─ settings.gradle.kts            include(":shared", ":tvApp", ":composeApp", ":androidApp", ":server")  (+ iosApp Xcode project)
├─ gradle/libs.versions.toml      one catalog; versions PINNED to a verified tuple (§6)
│
├─ shared/                        KMP LIBRARY  (plugins: kotlin-multiplatform, com.android.kotlin.multiplatform.library)
│   targets: android, iosArm64, iosSimulatorArm64, wasmJs, jvm
│   src/commonMain                 ONLY portable code:
│   │  core/        UiState/Result, GenerationCounter (atomicfu), Clock (kotlinx-datetime), formatters
│   │  domain/      MediaRef, Title, Person, Detail, Season, Episode, Review, Video, Credit,
│   │               LibraryState, LibraryKind, LibraryActions(interface, NO Compose runtime)
│   │  data/tmdb/   TmdbApi (Ktor), Dtos, Mappers, DetailMappers, TmdbCache, TmdbError, TmdbRepository,
│   │               Transport { Direct(readToken), Proxy(baseUrl) }
│   │  data/auth/   AuthSession (public identity state: signedIn?, accountObjectId, name, sessionGeneration),
│   │               AuthRepository (v4 protocol — TV spec §4 is normative), ApprovalFlow (expect-free interface)
│   │  data/library/ LibraryRepository, LibraryImporter, AccountLibrarySource  (against storage CONTRACTS)
│   │               contracts: GuestLibraryStore, ImportLedgerStore, SettingsStore, CredentialStore
│   │  data/settings/ SettingsRepository (over SettingsStore)
│   │  presentation/ platform-free screen state (see §4 — selective, not "all VMs")
│   │  design/      tokens generated from design/tokens.json (constants only)
│   src/nonWebMain (android + ios + jvm)  Room database/entities/DAOs for guest library + import ledger,
│   │               DataStore-backed SettingsStore, CredentialStore + TokenCipher (Keystore / Keychain / jvm test cipher),
│   │               native read-token injection point. The intermediate source set is declared explicitly and
│   │               the AGP-9 Android target is attached to it by hand (withAndroidTarget() does NOT match the
│   │               new plugin's target — both reference repos special-case this). JVM uses Room's jvm driver.
│   src/androidMain  OkHttp engine, Room driver, Keystore cipher, DataStore path
│   src/iosMain      Darwin engine, Room/SQLite driver, Keychain cipher, DataStore path
│   src/wasmJsMain   Ktor JS engine → Proxy transport only; browser SettingsStore/GuestLibraryStore/
│   │               ImportLedgerStore (localStorage/IndexedDB), NO credential store, NO token
│   src/jvmMain      Java engine (server tests, tooling)
│   src/commonTest   kotlin.test ports of every test that doesn't touch an engine/Android (inventoried in P1)
│   src/androidUnitTest, iosTest, wasmJsTest   engine/storage/crypto tests where they actually execute
│
├─ tvApp/                         ← CURRENT app/ renamed. Android APPLICATION. UI untouched. Same applicationId,
│                                    signing, credential file/key alias, backup rules, DataStore keys, library.db schema.
├─ composeApp/                    KMP UI LIBRARY (com.android.kotlin.multiplatform.library): Compose Multiplatform
│   targets: android, iosArm64, iosSimulatorArm64, wasmJs
│   src/commonMain                 theme from tokens, shared primitives (PosterCard, PersonCard, ScoreRing, Chip,
│   │                              sheets/dialogs), TWO shells: MobileShell (5 tab stacks, hero pager, sheets,
│   │                              predictive back) and WebShell (header/menus/footer, URL model, keyboard/hover)
│   src/androidMain  Custom Tabs approval, SplashScreen API, Vibrator haptics
│   src/iosMain      ASWebAuthenticationSession approval, UIKit haptics, Keychain via shared
│   src/wasmJsMain   main(), browser-history navigation binding, YouTube iframe HTML interop (controls OUTSIDE video)
├─ androidApp/                    thin Android APPLICATION for the PHONE: applicationId com.techgv.cineverse.mobile
│                                    (distinct from TV so both install side by side), consumes composeApp
├─ iosApp/                        Xcode project hosting the composeApp framework (Swift entry only)
├─ server/                        Ktor JVM: TMDB proxy + auth/session (§5). Independent: keeps its own minimal
│                                    DTOs; never depends on the client library.
├─ design/                        tokens.json (both kits), prototypes, sample-tmdb.json
├─ docs/                          IMPLEMENTATION_SPEC.md (TV, normative protocols), this plan, kit docs
└─ .github/workflows/             tv-linux.yml · shared-and-server-linux.yml · mobile-macos.yml (Xcode-pinned) · web.yml
```

Dependency graph (no cycles, no app depends on another app):

```
                shared
          ┌───────┼────────────┐
        tvApp  composeApp    (server: independent; jvm tests may use shared's jvm target for mapper fixtures only)
                  ├── androidApp
                  └── iosApp (framework)   wasm executable lives in composeApp/wasmJsMain
```

Why TV keeps its own UI: tv-material3, D-pad focus memory, overlay rail and 1280×720 design units have no
phone/web equivalent. Mobile and web share **primitives and content**, not one responsive tree:
narrow web still has the web header/menus/footer and URL model, not the mobile tab bar (§4.3).

---

## 2. Storage & persistence (review #2)

- Room stays in the **`nonWeb`** intermediate source set (android + ios + jvm, declared explicitly — see §1).
  `commonMain` holds only the contracts; the Room dependency is declared on `nonWebMain` only, so neither the
  wasm compiler nor the wasm dependency graph ever sees `androidx.room`. P0 must prove this resolution, not
  just create folders.
- Contracts in `commonMain`:
  `GuestLibraryStore` (rows + title_json + added_at), `ImportLedgerStore` (per-account steps with
  `attempting`/`done` persisted **before** each POST — the importer keeps today's semantics),
  `ImportOfferStore` (per-account offers/resume), `SettingsStore`, `CredentialStore` (native only).
- Browser actuals: IndexedDB (via a tiny expect-backed KV with localStorage fallback) for guest rows, ledger
  and offers; guest/ledger updates run inside IndexedDB transactions (the cross-tab notification below is
  not a lock). Explicit behaviour and tests for: reload mid-import (ledger survives), quota exceeded /
  storage blocked (visible error, no silent loss), multi-tab coherence (§3).
- No SQLDelight rewrite for v1; browser SQLite workers are out of scope.
- Migration: `library.db` schema, DataStore keys, credential file name/key alias and backup exclusions are
  unchanged for TV; an install-over-old-version fixture is part of the P0 gate.

## 3. Auth & identity across platforms (review #3, #9)

- The reviewed **v4 protocol in TV spec §4 is normative** (request token → approve → access token →
  session convert → `/3/account`). The kits' v3 / 60-minute description is superseded.
- `commonMain` exposes **`AuthSession`** (signedIn, accountObjectId, displayName, sessionGeneration) —
  the only identity type screens and `LibraryRepository` bind writes to. Native `Credentials`
  (user token + session_id) exist **only** in `nativeMain`; the browser never fabricates them nor calls
  exchange/convert — the proxy does and exposes just the safe `AuthSession`.
- `ApprovalFlow` interface in common: `start(attempt)`, `onCallback(uri)`, `awaitApproval(attempt)`.
  Actuals: TV = QR + polling (existing), Android = Custom Tabs + deep link, iOS = ASWebAuthenticationSession
  + retained session object, web = new tab + proxy callback. Rules carried over from the TV spec: pending
  attempt persisted; callback is a **hint to verify**, never proof; bound to the current attempt; cancel /
  deny / timeout / consumed-token outcomes; cold-start intent handling on Android; original browser tab
  restored on web.
- **Callback verification is a P4 spike**: prove TMDB accepts `cineverse://auth` as `redirect_to` on
  Android and iOS; else use an HTTPS app/universal link, else "return and press *I've approved it*".
- Identity-bound writes are enforced **client and server side**: every mutation/import request carries the
  expected `sessionGeneration`/account; the proxy rejects stale ones (tab A showing account A while tab B
  switched the cookie to B).
- **Multi-tab on web**: cookies and IndexedDB do not fire `storage` events, so after any successful session or
  library change the writing tab (which updates its own state directly) broadcasts a non-secret
  `{sessionGeneration, libraryRevision}` via `BroadcastChannel` (plus a localStorage marker fallback). Other
  tabs refetch `/api/session`, cancel in-flight account work and reload. Tabs also revalidate on
  `visibilitychange`/focus to cover missed messages, expiry and revocation. Server-side generation rejection
  remains the safety net.
- **Common auth contract**: the common-facing `AuthRepository` API (`session: Flow<AuthSession>`,
  `startApproval`, `awaitApproval`, `signOut`) never returns or requires the native `Credentials` type. Token
  exchange, session conversion, revocation and credential lookup live behind the `nonWeb` implementation (and
  the proxy on web); only protocol classification/state rules are common. The existing token-bearing class is
  **not** moved wholesale into `commonMain`. P1 places interfaces/source sets; P3 performs the actual
  credential/settings migration — once, not twice.

## 4. What is shared vs. per-platform in code (review #6, #10, #11)

### 4.1 Data/domain — shared, with a real port list (not "pure move")
Items the extraction must actually change (inventoried):
`AtomicLong` (auth + GenerationCounter) → `kotlinx.atomicfu`; `ConcurrentHashMap` locks → `Mutex` map;
access-order `LinkedHashMap` + `@Synchronized` cache → explicit LRU with a `Mutex` **keeping synchronous
semantics for `imageBaseUrl`/`onAccountChanged` callers** (decide: cached value snapshot stays sync, loads
async); `System.currentTimeMillis`/`TimeZone`/`Locale`/date formatting → `kotlinx-datetime` + per-platform
`Locale` expect; `android.util.Log` → Kermit; Retrofit `HttpException` → Ktor response exceptions mapped
to the same `TmdbError` table; JVM Base64/crypto → native source set; `IOException` → Ktor/okio equivalents.
Atomic generation/invalidation/commit and per-title write locks keep their current guarantees (tests are the
contract).

### 4.2 Presentation — selective extraction
| Shared (`shared/presentation`) | Stays per platform |
|---|---|
| Home/Browse/Search/Detail/Person/Season/Library/Login/Settings **state + business rules** (loading, generations, D7 fallback, pagination, library actions, import controller) expressed with plain ids/events | TV: `FocusMemory`, `Route`/`BrowseKind` construction, `KeyboardKey`, `SectionState`/`PagedSection` adapters, `Toaster`, `RateRequest`, `ImportDialogState` → thin **TV adapter VMs** in `tvApp` wrapping the shared state holders |
| `LibraryActions` interface (pure Kotlin) | `LocalLibraryActions` (Compose runtime) in each UI module |
| A `StateSaver` interface for scalars | TV: SavedStateHandle; web: URL + sessionStorage; iOS: state restoration — defined per shell |
Shared repositories are the main value; VMs are shared only where the behaviour really is identical. TV's
focus-driven single hero and 28-char keyboard cap are **not** imposed on mobile/web.

### 4.3 UI — two shells inside `composeApp`
- **MobileShell**: 5 tabs with separate back stacks, hero pager, bottom sheets, predictive back, iOS gestures,
  Dynamic Type ≤ 1.3, haptics (`expect Haptics` with API-level fallbacks), OS + app reduce-motion.
- **WebShell**: header/menus/footer, URL model (`/`, `/movies?list=`, `/tv?list=`, `/people`, `/movie/{id}`,
  `/tv/{id}`, `/tv/{id}/season/{n}`, `/person/{id}`, `/search?q=`, `/library?tab=`, `/settings`, 404),
  history policy (replace while typing a query, push on navigation), query/filter/tab restoration from URL,
  keyboard focus ring / Esc / `/`, hover, iframe focus + disposal.
- Shared: theme from tokens, cards, score ring, chips, dialogs/sheets content, screen bodies where identical.
- Accepted canvas limits (recorded, owner may add SSR/OG later): no SEO for title pages, find-in-page,
  text selection, screen-reader differences.
- **Player policy carries over**: web iframe and any mobile embed keep custom chrome **outside** the video;
  episode taps are metadata only (parent trailer / available video / fallback), never implied streaming.

### 4.4 Kit features not in TV today — coverage table (to build in P8/P9, with just-in-time shared additions)
| Feature | Shared work | Notes |
|---|---|---|
| Discover genre/sort filters, keywords | `TmdbApi.discover*`, `keywords` append | URL-reflected on web |
| Latest trailers (web) | bounded fetch: first N upcoming → `/videos`, cached | cap N=8 |
| Top 10 / top-rated TV rows | list params only | |
| Search facets/counts, recent searches | counts = **loaded** vs total (never label a page as global total); `RecentSearchStore` contract | |
| Library/Profile totals | `total_results` per list | |
| Pull-to-refresh, autoplay preference, OS+app reduce motion, haptic fallbacks, Dynamic Type, shared fonts | UI modules | |
| Token/size conflicts between kits and TV | kits' tokens apply to mobile/web; **TV UI is not retokenized** | |

## 5. `server` — a security component, not "a small proxy" (review #4, #5)

- Fixed upstream `https://api.themoviedb.org`; **allow-list** of paths/methods/query params (the §3.5 map);
  request-body size limits; client-supplied `Authorization`/`session_id`/upstream URLs rejected.
- Bounded upstream timeouts; no automatic retry of ambiguous writes; sanitized errors; redacted logs.
- Cache: public catalog **GETs only**, keyed by every parameter (language/region/adult/page…); auth, account,
  write responses never cached and marked `Cache-Control: private, no-store`.
- Session: opaque server-side session id in an **HttpOnly, Secure, SameSite=Lax** cookie, explicit path/expiry,
  rotation on sign-in, revocation on sign-out; TMDB credentials live only server-side.
- Mutations: CSRF defence (Origin/Fetch-Metadata check + double-submit or header token); CORS is not CSRF
  protection. Prefer same-origin deployment (static wasm + `/api` behind one host).
- Identity binding: mutation/import requests carry expected account/session generation; mismatch → 409.
- Tests: two accounts isolated; cache isolation private vs public; expired/failed sessions; rejected
  cross-origin writes; callback attempt binding.

Secrets: native read token is injected from **Android/iOS entry points** (BuildKonfig fields generated in the
`androidApp`/`tvApp`/iOS build only — no common default field); server token from env; wasm receives
**only non-secret config**. P0 gate includes a **wasm + source-map artifact scan** with a sentinel token
string that must not appear. The credential-free image client stays separate from the authenticated API client.

## 6. Version tuple (to be pinned in P0 by an actual build, not assumed)

Candidate tuple, each verified by the P0 smoke build (Android consumer + iOS simulator framework + wasm
executable exercising lifecycle/savedstate/navigation, HTTP, image loading, settings):
Gradle 9.x · AGP 9.4.1 (already) · Kotlin **2.3.x** (Compose Multiplatform 1.11 release notes require 2.3.10
for native/web features; current 2.2.10 is not a verified tuple) · Compose Multiplatform 1.11.x ·
`org.jetbrains.androidx` lifecycle 2.9.x / navigation 2.9.x (multiplatform coordinates) · Ktor 3.x · Coil 3.x
with `coil-network-ktor3` + per-target engine · Room 2.8.x KMP (native only) · DataStore: **1.3.0 (web storage
support, Oct 21)** if it proves out on wasm, otherwise native DataStore behind `SettingsStore` and a browser
actual · atomicfu · kotlinx-datetime · Koin 4.x · KSP matching Kotlin · Xcode pinned (Apple-silicon simulator
only, iOS 16+, no iosX64). The TV module must **not** be downgraded incidentally; if a TV dependency conflicts
with the tuple, resolve it in P0 with the TV gate.

## 7. Phases and gates (review #7, #12)

Each phase is an independently reviewable, revertible commit set. TV gate = `assembleDebug lint
testDebugUnitTest` + 38 instrumented D-pad tests + release/R8 build + install-over-previous-version fixture.

| # | Phase | Scope | Gate |
|---|---|---|---|
| **P0** | Layout + tuple proof | `git mv app tvApp` (identity/data unchanged); add empty `shared`, `composeApp`, `androidApp`, `server`, `iosApp`; pin tuple; **smoke build** on all targets; CI split; artifact token scan | TV gate; `:shared` compiles for android/ios-sim/wasm/jvm; wasm scan clean |
| **P1** | Portable core | move `domain`, `core`, DTOs/mappers, `TmdbError` table, cache (LRU/Mutex port), storage **contracts**; Room + Keystore moved to `nativeMain`; inventory tests → `commonTest`; TV consumes `shared` | TV gate; `:shared:allTests` on jvm + android; iOS test task |
| **P2** | Transport port | Retrofit → Ktor (`Transport.Direct`), same token-kind + host rule + error table; MockEngine ports of `TmdbClientTest`/`TmdbErrorTest`/redaction tests | TV gate + live catalog smoke on emulator |
| **P3** | Native credentials & settings | `CredentialStore`/`TokenCipher` in `nativeMain`, DataStore `SettingsStore`; TV data compatibility fixture | TV gate; sign-in state survives upgrade |
| **P4** | Selective presentation | shared state holders per §4.2 + TV adapter VMs; `ApprovalFlow` interface; **callback spike** (redirect_to acceptance on Android/iOS) | TV gate; VM tests on jvm/ios |
| **P5** | DI | Koin at composition roots; TV keeps `AppContainer` facade over Koin; no service-location in logic | TV gate |
| **P6** | `server` | §5 contract + security tests; browser never sees token | server tests; two-account/CSRF/cache suites |
| **P7** | `composeApp` M0–M1 | fonts, theme from tokens, primitives gallery on Android + iOS sim + Chrome/Firefox/Safari | kit acceptance |
| **P8** | Mobile M3–M9 | MobileShell + screens per mobile kit | kit acceptance + Maestro; review loop |
| **P9** | Web M3–M10 | WebShell, routing/history, screens, iframe player (no overlay), responsive, Lighthouse | browser smoke incl. refresh/Back/Forward in 3 browsers |
| **P10** | Release | Play TV / Play phone (distinct ids) / TestFlight / static web + server deploy | — |

**First approval boundary = P0 alone.** P0 acceptance (agreed with the reviewer): explicit five-module graph +
iosApp host; TV applicationId/signing/paths/data unchanged, distinct phone id; the **exact** resolved
versions/coordinates/toolchains committed (not ranges); AGP-9 library DSL in `shared`/`composeApp`; TV
dependencies not downgraded; a minimal but real smoke on android + iOS simulator (pinned macOS/Xcode) + wasm
using lifecycle/savedstate/nav/HTTP/image/settings — Linux-skipped native tasks do not count as tested; TV
build/lint/unit/instrumentation + release/R8 + install-over-old-data fixture; native-token sentinel absent from
the wasm bundle and source maps; no common/default token generation; **no** production data/domain move, Ktor
replacement, Koin rewiring, TV token adoption or product UI in P0 (a smoke-only fake and tiny harness are fine).

## 8. Decisions (owner)

| # | Decision | Recommendation |
|---|---|---|
| K1 | Modules | `shared`, `tvApp`, `composeApp` (lib), `androidApp`, `iosApp`, `server` |
| K2 | Packages / ids | source package `com.techgv.cineverse` everywhere; TV applicationId unchanged; phone `com.techgv.cineverse.mobile`; iOS bundle `com.techgv.cineverse.mobile` |
| K3 | Persistence | Room KMP on native; IndexedDB/localStorage contracts on web; no SQLDelight |
| K4 | DI | Koin, adopted in P5 (after extraction), composition roots only |
| K5 | Web UI | Compose for Web (wasm) + mandatory `server`; canvas limits accepted (SSR/OG optional later) |
| K6 | Mobile trailers | open YouTube app/URL by default; optional in-app embed must keep controls outside the video |
| K7 | minSdk | `androidApp`/`composeApp`/`shared` android: 26; `tvApp`: owner's D8 value — must be **≥ shared's 26 and ≤ 31** (Play TV). P0 keeps the current 36 baseline; the D8 decision is not made silently inside a rename/upgrade |
| K8 | iOS | 16+, Apple-silicon simulator only |
| K9 | build-logic | not yet |
| K10 | Versions | pin the §6 tuple by the P0 smoke build; Kotlin 2.3.x upgrade included in P0 |
| K11 | Auth callback | `cineverse://auth` if TMDB accepts it (P4 spike), else HTTPS app link, else manual return. **P4 spike (2026-10-23, real read token):** `POST /4/auth/request_token {"redirect_to":"cineverse://auth?attempt=spike1"}` → 200 `success:true`; the request token is a JWT whose `redirect_to` claim carries the URI verbatim (`scopes:[pending_request_token]`, 15-min `exp`). TMDB does **not** validate `redirect_to` at creation (`"not a url"` and `"javascript:alert(1)"` are equally accepted), so acceptance proves storage only. The approve page (`/auth/access?request_token=…`; curl gets a bot challenge, fetched with headless Chrome) shows, unauthenticated, "An Account is Required … You need to be logged in to continue!" with a login form whose `redirect_to` is the approve page itself; the custom-scheme redirect can only happen after a logged-in user presses Approve. **Verified:** custom scheme accepted and bound to the token. **Not verified (needs a human TMDB login on a phone, P8 first task):** that Approve navigates to `cineverse://auth?attempt=…` on Android Custom Tabs and iOS ASWebAuthenticationSession. **Decision:** keep `cineverse://auth` as the primary (callback is only a hint; `awaitApproval` verifies by exchange either way, so a missing redirect degrades to the manual "I've approved it" return); fallback order unchanged (HTTPS app/universal link, then manual return). |

## 9. Risks (residual)

- Kotlin 2.3 upgrade may surface TV compiler/plugin issues — handled inside P0 with the TV gate.
- DataStore 1.3.0 web support is new — fallback path defined (§2/§6).
- Identity binding on web depends on the proxy and multi-tab handling — tested in P6/P9, not assumed.
- Canvas web accessibility — recorded as an accepted limit; revisit if the owner needs SEO/a11y parity.
