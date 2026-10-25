# Wave E — continue product screens, park known iOS test failures

## Operator decision and boundaries

The operator asked to continue pending product work while holding the five known iOS test failures. This is prioritization, not waiver: do not disable/filter those tests, relabel them green, change SDK pins to conceal failures, or promote UI to main/release. Keep main050f0a1 shared-only and the original l3-design/Safari preview untouched. Main has unrelated untracked IDE/app files; do not touch, stage or delete them.

Preserved candidates:
- Native M1 cd8532a: hosted real-window coverage move independently reviewed with no findings; hosted1/1 passed. Full unfiltered Compose iOS65 reported,60 passed,5 failed, no SIGTRAP. Five deferred failures: MobileNavigationTest selectedTabAndEachStackSurviveSaveRestore and homeScrollAndTrendRestoreAcrossTabsAndSaveRestore (unimplemented Native restoration helper), seeAllUsesExploreHandoffAndReturnsWithoutLosingHome, and both MobileHomeStorageRecoveryTest cases. Diagnostic evidence /tmp/cineverse-m1-hosted-window-IlzDuYQQ/REPORT.md. No claim of actual iOS app rendering.
- Browser W1 16703b9: independent review complete. Compose Wasm62/0 and production/secret scan passed at63a51a4;11-step fixture passed there. Later test-only same-row uniqueness guard reviewed and replayed against9 saved proofs, not a fresh full browser run at16703b9.
- Original P7 Safari/human acceptance, all real-account/import/approval-return, historical-upgrade, hardware and deployment gates remain open.

## Implementation topology

The next screen group is Explore/Browse/People, followed by Search and detail groups. It is multi-seam: portable catalog request/recovery state, native navigation/rendering, and browser URL/rendering are separate contracts. Implement/review the portable component first; then bind native and browser components independently. No single writer owns the whole migration. Reuse existing six worktrees; never create a seventh.

| Lane | Repo/cwd and isolated branch | Exclusive decision/claimed files | Authority and next gate | Handoff/why independent |
|---|---|---|---|---|
| E1 | ../CineVerse-c1-native-auth, e1-product-browse-core from050f0a1; retain d0-catalog-storage-errors branch | Shared product Browse request/state/recovery contract and focused shared tests. Reuse CatalogRepository, PageSource/PagedSection and existing enums/factories. Legacy TV Browse behavior/API/IDs stay compatible. No native/web UI, server, dependency or storage-schema ownership. | Single bounded presenter seam. Commit only; focused JVM/Android-host tests and native/Wasm compilation, independent read-only review before shell consumption. No browser/device lease initially. | Real curated movie/TV lists, People, exact Home-row handoff and explicitly generic Discover filters need a portable cancellation/pagination/settings/storage owner, not duplicated shell networking. |
| E2 (wait E1 review) | ../CineVerse-c2-web-http, native continuation fromcd8532a | Native Explore screen + existing root/Home Browse handoff + matching focused tests. No five-failure repair or shared core edits. | Bind committed reviewed E1, focused new checks; no repeated failing full iOS suite. Independent review before integration. | Native five-tab navigation/lifetime differs from browser history. |
| E3 (wait E1 review) | ../CineVerse-c3-browser-storage, browser continuation from16703b9 | Browser Movies/TV/People pages and existing URL filter/history bindings + focused Wasm tests. No native or shared-core ownership. | Bind reviewed E1, targeted checks/production safety, independent review. | URL parsing, history, desktop/narrow filters and keyboard behavior are browser-specific. |

## E1 public behavior

- Use real existing catalog reads: curated Movies Popular/NowPlaying/Upcoming/TopRated, TV Popular/AiringToday/OnTheAir/TopRated, People, and exact Home-row handoffs including Trending. No placeholder result data or local filtering of one fetched page presented as complete results.
- Generic Discover supports existing canonical genre IDs and DiscoverSort for movies/TV. Its provenance is explicit: never label generic Discover as curated NowPlaying/Upcoming/OnTheAir. No new unsupported server/API parameters.
- Explicit initial selection and source identity; cancel superseded loads, drop stale generations/settings/genre results, deduplicate and preserve valid loaded pages. Initial empty/loading/error, retained-content pagination error, explicit retry, no infinite error retry loop.
- Reuse reviewed typed StorageException handling/retry patterns. Keep last real settings/content where valid; unknown remains unknown. Resubscribe dead readers explicitly even if recovered values compare equal. Programmer/cancellation errors propagate; no broad handler as recovery. No writes/auth operations/volatile fallback.
- Keep public contract small and deterministic. Existing BrowseStateHolder may be extended compatibly if smallest; a separate product holder is justified only for genuinely different request state. Do not create a general controller framework or duplicate paging/repositories.
- Source changes carry small tests for switching, canceled/stale results, failed next-page recovery, equal/changed settings recovery, genre errors, curated-vs-discover provenance and unchanged legacy TV behavior as affected.

## Common execution rules

Workers/reviewers use cliproxyapi/gpt-6.1-sol only, no fallback/auth changes or nested agents. Author GvHarish9894 <hariharangv9894@gmail.com>. Commit durable handoffs; no merges/push/main promotion. Parent supplies exact reviewer patches, integrates only reviewed components into non-main candidates and performs combined gates later.

Gradle --max-workers=2, no configure-on-demand. No real sign-in/account write/import, credential output/overwrites, personal browser/owner8765, resets/wipes/permissions or physical devices. Obtain a fresh exclusive lease before any allowed browser/simulator use; none granted to E1 initially. Checkpoint before long gates, preserve logs and exact head, stop/report provider or unexpected runtime failures. Do not repeat the five parked iOS failures for unrelated feature work. New logic still needs focused checks; holding known failures is not permission to skip new regression coverage.
