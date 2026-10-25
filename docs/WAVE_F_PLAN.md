# Wave F — Search, with known iOS failures parked

## Accepted starting point

Reviewed non-main Browse candidate4661983 in ../CineVerse-c5-catalog / e4-browse-ui-candidate integrates E2native49a991d and E3browser8b377ec with reviewed shared core/main contracts. Integration review found no issues (managed outputs/e4-browse-integration-review.md). Fresh combined results: sharedJVM329/0,Android-host323/0,Wasm294/0; composeAndroid-host37/0,Wasm84/0; server57/0;TV63/0; AndroidAPKs/iOSframework+Swiftapp/forcedWasmproduction18-file scan/noRoom gates pass. No native UI/full iOS/production-document/Safari/human/account acceptance implied. P7 remains unpromoted, original preview untouched.

Operator asked to complete product work while holding five known iOS failures. Keep those unchanged/unexecuted; never disable them, count them green or use them to block unrelated component implementation. New logic still needs focused checks and independent review. Search/Library/Profile/Settings/detail/person/season/player remain unfinished. Main stays shared-code-only plus planning docs; no UI promotion/push.

## Topology and next bounded seam

Search as a whole is multi-seam: read transport, recent-query persistence, presentation state, native UI and browser URL/UI. Do not commission one writer for the whole screen group. Implement/review dependent contracts before their consumers.

| Lane | Existing isolated cwd/branch | Exclusive contract | Authority/gate | Why bounded |
|---|---|---|---|---|
| F1 active | ../CineVerse-c1-native-auth / f1-search-read-contract from4661983; old E1 branch retained | Typed public search-facet read protocol: existing shared TMDB API/transport/cache extension, corresponding server GET allowlist, and affected tests/fakes only | Commit; focused shared JVM/Android-host/server checks, native/Wasm compilation; fresh independent read-only review before presenter/UI consumption. No device/browser lease | The client paths and BFF allowlist implement one public read contract; half a transport would be unusable. No recent store, presenter or UI ownership. |
| F2 pending F1 | Reuse cleared slot later | Durable bounded local recent queries using existing SettingsStore; explicit read/write failure behavior and storage tests | No native/browser account operations, no volatile fallback; review before UI | Independent local storage seam, not search network or account data. |
| F3 pending contracts | Reuse cleared slot later | Product query/facet debounce/pagination/cancellation/settings/recovery state reusing existing paging/catalog | New regression checks; legacy TV Search behavior preserved | Distinct screen-owner behavior. |
| F4/F5 pending reviewed contracts | Existing native/browser UI worktrees, preserving E2/E3 branches | Native Search and browser Search URL/UI respectively | Focused new checks, independent reviews; no old five-failure repairs | Platform-specific input, focus, history/lifetime/rendering. |

Six worktrees including main remain the cap. F1 reuses E1's clean slot; E4 stays stable for later components. Parent records every reuse and owns integration, merges, exact reviewer patches and publication.

## F1 behavior and compatibility

- Existing transport supports only search/multi and server permits only search/multi. Add real search/movie, search/tv and search/person reads, plus typed selection of existing multi, without using a local filter on one mixed page as a complete facet.
- Preserve existing TmdbApi.searchMulti and CatalogRepository.searchMulti behavior, cache identity/defaults, all legacy TV callers, persisted keys/enums and IDs. Add the smallest compatible typed API; no facade/framework or mass unrelated fake churn. An unsupported custom implementation must not fabricate facet results.
- Use existing PageDto/MediaItemDto and real endpoint mapper fallbacks. Category-specific responses can omit media_type; callers must receive enough typed provenance for correct mapping. Do not invent counts; document any existing metadata-default limitations for later UI.
- Use real existing native credentialed TMDB transport and browser fixed-/api public proxy, not new clients. BFF forwards only canonical allowed parameters, never browser credentials; preserve cache TTL/budgets/upstream error policy and GET-only isolation. Exact path/query validation, page1..500, supported language/adult/query rules. No account/auth/write/import behavior or changes.
- Query length must be consistent with actual BFF200-character budget, not TV keyboard28. Validate rather than truncate silently; make trust-boundary requirements explicit for subsequent UI. No new request/schema/count endpoints or fake analytics/trending searches.
- Focused tests for all four paths, media provenance, language/adult/page/query forwarding, canonical/cache separation, invalid method/path/query/page/header boundaries and unchanged existing multi behavior. Existing contracts/tests must still compile.

## Execution

Every worker/reviewer cliproxyapi/gpt-6.1-sol only; no provider/model/CLI/auth fallback, nested agents or new worktrees. Author GvHarish9894 <hariharangv9894@gmail.com>. No workers merge/push or edit main/other lanes. ANDROID_HOME=$HOME/Library/Android/sdk, DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer; Gradle --max-workers=2 and no configure-on-demand. No initial browser/device lease, launches/boots/resets/permission changes, physical/personal profiles, owner8765, real auth/account writes/import or credential output/overwrite. Build normal config loading allowed without manually exposing secrets.

Checkpoint before long gates; on provider/unexpected runtime failure preserve exact source/logs and stop, resume unfinished only on operator retry. Passing compile/fixture gates is not native/Safari/product/release acceptance. Keep the five known iOS failures parked and explicit. Future screen groups and real-platform acceptance are not silently waived.
