# Wave D — bounded product entrypoints

## Starting point and authority

- Main is `5f624d8` (reviewed CI-only health-token fix); corrected hosted CI must be checked separately.
- Reviewed **non-main** UI dependency base: `p7-ui-stage @ 711d339` (source merge `db16271`, docs `8571e38`). Staging review: `c7048de8-bc1c-4c20-b773-ca8b85861fc3/ui-stage-review.md` in managed artifacts.
- P7 is NOT accepted/promoted. Original `l3-design @ b5e69a6`, its worktree and `http://localhost:8765/?build=b5e69a6` stay untouched pending actual owner Safari render/Tab/Escape/handle/focus-return/console result.
- User authorized all remaining implementation and GPT delegation. Every worker/reviewer uses `cliproxyapi/gpt-6.1-sol`; no model/provider/auth/CLI fallback. No nested agents, merges, pushes or new worktrees by workers.
- Six worktrees including main remain the cap. Reuse completed lanes, retain original branch history. Commits: `GvHarish9894 <hariharangv9894@gmail.com>`.

## Topology preflight

Product work is **multi-seam**: native entry/navigation/Home, browser URL/session/navigation/Home, and the private defaults-read protocol are independently testable and have distinct owners. Do not commission one worker for all screens/platforms. Each lane below is a bounded vertical component; only the parent integrates committed, independently reviewed handoffs into a non-main candidate. No main UI promotion before actual P7 Safari acceptance.

| Lane | Cwd / branch, base711d339 | Exclusive decision and claimed files | Authority / next gate | Handoff / why independent |
|---|---|---|---|---|
| M1 | `../CineVerse-c2-web-http` / `m1-mobile-shell` | Native mobile root + five-tab saved navigation + real Home. Own new `composeApp/src/{commonMain,nonWebMain,androidMain,iosMain}/kotlin/com/techgv/cineverse/ui/mobile/**`, mobile DI/bootstrap files, Android Activity and iOS MainViewController entrypoint glue. Own native source-set/dependency portions of `composeApp/build.gradle.kts` if needed. May add optional long-press support to existing PosterCard, retaining default behavior. Also owns the approved additive shared HomeStateHolder.loadMore(row) forwarding and focused test, committed separately first for W1 handoff. Native tests under matching mobile packages. | Commit only. Native tests/builds, settings/cache/lifetime/restoration tests and emulator/simulator proof; independent review follows. | Native shell has its own entrypoints/host lifecycle. It does not own WebRuntime, browser routes, shared auth/defaults protocol, other common primitives, CI or TV. |
| W1 | `../CineVerse-c3-browser-storage` / `w1-web-shell` | Browser root + URL/history routing + real Home. Own new Wasm `ui/web/**`, Wasm `ui/main.kt`, matching Wasm web tests. Own ONLY scanner positive-control literal/comment in compose build if replacing the gallery removes its control; all actual secret detection and failure behavior retained. | Commit only. Parser/history/refresh/back/error/lifetime tests, browser suites/production/no-secrets/no-Room; independent review follows. | Browser URL/session/root differs from native navigation. No native entrypoints/build hierarchy or WebRuntime implementation changes; request seams if missing. |
| S1 | `../CineVerse-c5-catalog` / `s1-account-defaults` | Safe browser account-defaults read: server retained verified metadata/private bound GET + strict portable DTO/helper + concrete WebAuthRepository/WebRuntime read API + relevant shared/server tests. Preserve existing native AccountDto helper/TV behavior. Prefer no AuthRepository interface expansion unless source requires it; ask before broader API/fake churn. | Commit only. Missing/partial fields, identity/generation/auth-epoch races, no secret fields, private no-store/budget/error semantics, common/adapter/server tests; independent review follows. | One end-to-end private read contract, no UI files or automatic preference application. Unblocks later Profile, independently of Home/navigation. |

### Non-overlap rules

- M1 owns native build hierarchy; W1 only owns the scanner control/comment hunk, not its validation logic. Same build file has explicitly disjoint contracts; parent reconciles after both commit. No pin changes or new dependencies without approval.
- S1 may extend the existing WebRuntime with a narrow defaults read, but W1 must use the current runtime API and report any needed extension instead of editing it.
- Existing common UI foundations are frozen except M1's additive optional PosterCard long-press parameter. W1 does not edit it. Neither worker edits the other shell or gallery.
- M1 alone owns shared HomeStateHolder.loadMore(row) and its regression test; send its dedicated commit to parent for W1 consumption. Do not add HomeRow entries or change TV defaults/order/keys.
- Shared business logic remains in existing holders/repositories. Platform-specific shells must not impose TV search limits/interaction rules on mobile/web. No Koin lookups inside business logic.
- Other tabs/detail destinations may be explicit internal placeholders in this first shell slice; they are NOT finished features. Home and shell behaviors claimed by each lane must actually work. Subsequent Browse/Search/Library/Profile/Detail/Person/Season/Trailer groups follow reviewed handoffs.

### Approved first-slice limitations (not waived requirements)

- M1 may show the first ten PopularMovies entries with a truthful popularity/within-list label, never claim weekly/top-rated/global ranking. TopRatedTV remains unfinished follow-up; no silent shared HomeRow expansion that changes TV.
- W1 may show Upcoming movies with click-time 'Find a trailer', not a claimed Latest trailers feed. Preserve Unavailable/error and inactive-event cancellation. Player destinations remain explicitly unfinished. Navigation-only hero CTA says 'Open library', not 'Add to watchlist'; no false write/success or assumed membership. Real trailer feed/player/watchlist actions remain later requirements.
- Both shells must avoid automatic retry loops when pagination fails; retain content and require explicit retry.

### D0 — discovered catalog-storage recovery seam

W1 found that catalog preference reads can throw typed StorageException outside existing TMDB-only catches in PagedSection/Home. A shell CoroutineExceptionHandler/recreation workaround is rejected. Reuse the finished UI-staging worktree `../CineVerse-c1-native-auth` as `d0-catalog-storage-errors`, based on M1's dedicated paging handoff `676c068` (forwarder plus test only); p7-ui-stage711d339 remains preserved. D0 exclusively owns PagedSection/Home storage handling, compatible defaulted diagnostics/retry/resubscription and focused shared tests. M1 has finished/transferred shared Home ownership after676c068; M1/W1 do not edit it further. No new worktree; total remains six. D0 has no browser/native lease until explicitly granted.

Required: catch only known storage failures, preserve current-context real content and page/scalar state, surface initial errors honestly, recover dead settings/genre collectors through explicit retry, reset trailer loading without automatic trailer replay. Ignore stale/disposed/canceled outcomes; programmer/cancellation errors propagate. Keep native defaults/TV rows/keys/query-switch behavior and API compatibility. Independent review and committed handoff precede shell integration.

## Required contracts

- Native lifecycle/weak hosts/background cancellation and encrypted approval recovery stay unchanged. Native-only source may use a native UI source group if necessary; no native contracts in Wasm/common portable code.
- Bind theme/settings to real settings (unknown remains unknown, last good values retained); haptic token differences follow TOKEN_CONFLICTS, never change persisted enum names or TV strengths. Native content-preference cache observation is composition-root/app-lifetime, once per graph, not per screen/rotation.
- Use real HomeStateHolder and graph-owned catalog/app GenerationCounter/settings flow; screen work owns its cancellation scope. Preserve tab state/scroll/navigation, inactive trailer cancellation, per-section loading/empty/error/retry, and true images through credential-free networking.
- Browser `Checking/Ready(session?)/Failed` is authoritative. Unverified/null is not guest. Keep existing single BrowserApplication, fixed `/api`, guarded importer and typed storage error/retry. No native login request-code model, volatile persistence fallback, ambiguous-write replay, or fabricated AccountDto defaults.
- S1 GET must bind captured expected account+generation and reject stale work, use opaque signed64 decimal strings, retain no-store/error/budget/session-cookie rules. Unknown/incomplete defaults are explicitly unavailable/partial, never invented false/language/region values. No raw access/session/request tokens or numeric JS generations. No automatic application/import.

## Validation and resource ownership

- `ANDROID_HOME=$HOME/Library/Android/sdk`, `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`; Gradle max-workers2, no configure-on-demand.
- M1 has native slots: phone `emulator-5556` ONLY after verifying `CineVerse_Phone_API36` (25min from first use), iOS simulator `1F4DFB14-7325-4855-BE5E-4DD01D97D168` (25min from first use, `/tmp/c1-ios-tests.init.gradle`). No TV/physical devices. Ask if absent/not matching, no wipes/reset/uninstall. Explicitly release slots on completion.
- W1 has isolated ChromeHeadless/Karma slot (30min from first use). No personal profiles or owner preview. S1 requests a browser/native slot rather than colliding; begin with JVM/Android-host/server/compile gates.
- No real sign-in/account mutation/import without owner participation; use fake transports/stores for behavioral tests. Normal public catalog read rendering is allowed. No credential dumps/secrets.properties output/overwrites or permission changes.
- Do not rerun unaffected historical TV UI/retention for these first shells. Build/unit safety as needed; final integrated TV/platform gates remain parent-owned. CI result is separate from local tests.
- Checkpoint implementation before long gates; keep logs/XML/screenshots (public guest content only) under isolated temp/managed artifacts, reports file-only. Report exact head, owned diff/callers, counts/commands, slot release, known limits. On provider/runtime failure preserve state and stop; retry saved work only on user instruction.

## Current follow-up decisions

- S1 dbfb69e: independent source review complete; parent browser240/240 passed. Parent integration/combined gates remain.
- D0 e35d221: independent source review complete, OK with P2 note. Fix accepted: ordinary loadMore must consume the old storageFailedPage marker after no-op guards, with regression for loadMore→retryStorage and later TMDB failure. Same D0 owner, no UI changes.
- M1 4a6d373: complete recovery report preserved, phone57 and targeted iOS1 pass; original full iOS run remains interrupted/failed. Independent mobile review stopped at provider usage limit and is resumed only on explicit user request.
- W1 e88867d: complete handoff preserved, UI57/shared230/scanner18 passed. Reproduced nested document refresh fails before Kotlin because index.html uses a relative script URL. W1 is authorized to fix its document/resource bootstrap (including index.html and only necessary resource-public-path configuration), with actual full-document root/nested-route refresh regression. This is a focused repair to the existing navigation diff, not new screens. Preserve root-origin /api and original owner8765. W1 must separately consume reviewed D0 and bind typed Home callbacks/retry before acceptance; no handler/recreation workaround. Keep these repairs in separate commits; do not wait idle for a dependency or merge unreviewed code.

## Acceptance boundary

Each implementation handoff requires independent GPT review, fixes/re-review and parent combined gates. Code/build/screenshots are not human acceptance. Actual Safari, Android/iOS approval return, real account/import, browser-policy/quota/eviction/full reload/multitab, physical/API28 TV/YouTube policy, historical signed-in/nonempty-guest upgrades and release/deployment remain open. These three lanes do not complete P8/P9 or the product.
