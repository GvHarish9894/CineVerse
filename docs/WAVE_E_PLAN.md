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
| E2 (independent review pending; E1 cleared) | ../CineVerse-c2-web-http, e2-native-explore fromcd8532a + reviewed core003c75d | Native Explore screen + existing root/Home Browse handoff + matching focused tests. No five-failure repair or shared core edits. | Bind committed reviewed E1, focused new checks; no repeated failing full iOS suite. Independent review before integration. | Native five-tab navigation/lifetime differs from browser history. |
| E3 (independent review pending; E1 cleared) | ../CineVerse-c3-browser-storage, e3-web-browse from16703b9 + reviewed core07ec730 | Browser Movies/TV/People pages and existing URL filter/history bindings + focused Wasm tests. No native or shared-core ownership. | Bind reviewed E1, targeted checks/production safety, independent review. | URL parsing, history, desktop/narrow filters and keyboard behavior are browser-specific. |

## E1 public behavior

- Use real existing catalog reads: curated Movies Popular/NowPlaying/Upcoming/TopRated, TV Popular/AiringToday/OnTheAir/TopRated, People, and exact Home-row handoffs including Trending. No placeholder result data or local filtering of one fetched page presented as complete results.
- Generic Discover supports existing canonical genre IDs and DiscoverSort for movies/TV. Its provenance is explicit: never label generic Discover as curated NowPlaying/Upcoming/OnTheAir. No new unsupported server/API parameters.
- Explicit initial selection and source identity; cancel superseded loads, drop stale generations/settings/genre results, deduplicate and preserve valid loaded pages. Initial empty/loading/error, retained-content pagination error, explicit retry, no infinite error retry loop.
- Reuse reviewed typed StorageException handling/retry patterns. Keep last real settings/content where valid; unknown remains unknown. Resubscribe dead readers explicitly even if recovered values compare equal. Programmer/cancellation errors propagate; no broad handler as recovery. No writes/auth operations/volatile fallback.
- Keep public contract small and deterministic. Existing BrowseStateHolder may be extended compatibly if smallest; a separate product holder is justified only for genuinely different request state. Do not create a general controller framework or duplicate paging/repositories.
- Source changes carry small tests for switching, canceled/stale results, failed next-page recovery, equal/changed settings recovery, genre errors, curated-vs-discover provenance and unchanged legacy TV behavior as affected.

## E1 cleared / shell handoff

E1 final6a6207a independently source-reviewed OK, no remaining findings. Focused JVM47/0 and Android-host47/0 plus shared native/Wasm compilation passed. Parent cherry-picked only reviewed E1 commits into clean existing E2/E3 worktrees; old m1-mobile-shell@cd8532a and w1-web-shell@16703b9 remain preserved. E2 starts003c75d; E3 starts07ec730. No new worktrees or main UI changes.

E2 exclusively owns native Explore rendering/owner and the existing native route/Home-row handoff seams, including an additive persisted-defaulted Trending window argument. E3 exclusively owns Wasm Browse/People rendering/owner and WebApp/WebRoute URL/history seams. Neither edits shared core or the other shell. Both reuse existing UI primitives; common primitives/build hierarchy stay frozen unless a concrete missing contract is reported first. Repair old placeholder-only assertions that these new screens make obsolete, preserving their original navigation intent; do not repair/ignore the known Native restoration/helper and timing failures or claim they passed.

For E3, Discover provenance must remain explicit even when route list/sort/genre scalars coexist; curated NowPlaying/Upcoming/OnTheAir labels must never describe generic Discover results. Align external genre/sort validation with E1, rejecting malformed URL/saved state explicitly rather than crashing or fabricating defaults. E2 preserves per-tab state and exact Home Trending day/week selection. Focused new-feature checks only; leases require exact emulator/browser verification and explicit expiry. Parent owns eventual non-main integration after independent source reviews.

### E2 partial checkpoint / E3 lease

E2 committed implementation a451bc0 and compile correction b26a3bf, then stopped on GPT auth_unavailable. New Android-host checks reached14 reported/1 failure; a small unfinished MobileExploreViewModelTest edit remains uncommitted. Parent preserved it under /tmp/cineverse-e2-recovery-YzqUVP5c/. Do not claim native completion or retry the failed owner without operator instruction. Five previously parked iOS failures are unrelated and untouched.

E3 checkpoint a381a6d compiled main/tests and passed production/secret checks. Parent verified existing Pi-managed CDP Chrome PID95408/port53870 is not Headless/Karma; it is unowned by this lane and must remain untouched. E3 granted only a fresh isolated ChromeHeadless/Karma profile/port, max30min from first use, to finish focused new tests. Release only its own resources.

### E3 timeout checkpoint

E3 stopped at clean924e411 after the30min worker timeout. Feature a381a6d plus fixture corrections f964cce/59ae266 and malformed-history bridge924e411 are committed; independent UI review and handoff missing. Initial39/1, corrected runtime10/1 and final affected settings1/0 results remain separate; focused route/history checks subsequently passed (count to verify in saved logs/XML). No active owned Headless/Karma process found after timeout. Parent clean snapshot /tmp/cineverse-e3-recovery-06qqAdsx/; evidence /tmp/cineverse-e3-web-browse/.

A source-to-distribution provenance check detected the new malformed-history marker in compileSync but not production dist despite an up-to-date production pipeline. Do not claim a fresh final production bundle/scan. Worker proposed forced optimize/sync/webpack/distribution plus scanner and marker check; this did not execute before timeout. On operator retry resume only that unfinished pipeline/handoff (and needed verification), never repeat all completed tests. Native E2 remains separately provider-blocked with its saved unfinished test edit. Neither UI component is accepted or integrated.

### Recovered E2/E3 committed handoffs

Explicit operator retry resumed only unfinished saved work in workflowce061e25-24c6-4142-a3d1-47403cad9c06. E2 now clean8313fbf: native Explore/Home exact Trending handoffs, bounded per-type owners; focused Android-host14/0 and Android-device/iOS production+test compilation passed. Native UI execution remains absent; five old failures parked. E3 remains clean924e411: approved forced production optimize/sync/webpack/distribution/scanner executed once,18 files/no leak, new INVALID_HISTORY_STATE present in shipped composeApp.js. Separate focused39/1,10/1,1/0,16/0 results retain their actual candidate provenance; no fabricated final all-suite green. No browser resources used during production-only recovery.

Both require fresh independent read-only source review before non-main integration. Parent exact patches /tmp/cineverse-e23-independent-review/{e2-003c75d-8313fbf.patch,e3-07ec730-924e411.patch}. Reports under managed outputs/ce061e25-24c6-4142-a3d1-47403cad9c06/{e2-native-finish.md,e3-browser-finish.md}. Remaining Search/Library/Profile/detail/person/season/player work and all actual Safari/account/hardware/release acceptance remain open. Old M1/W1/core branch history retained; total worktrees6; no main UI promotion/push.

## Common execution rules

Workers/reviewers use cliproxyapi/gpt-6.1-sol only, no fallback/auth changes or nested agents. Author GvHarish9894 <hariharangv9894@gmail.com>. Commit durable handoffs; no merges/push/main promotion. Parent supplies exact reviewer patches, integrates only reviewed components into non-main candidates and performs combined gates later.

Gradle --max-workers=2, no configure-on-demand. No real sign-in/account write/import, credential output/overwrites, personal browser/owner8765, resets/wipes/permissions or physical devices. Obtain a fresh exclusive lease before any allowed browser/simulator use; none granted to E1 initially. Checkpoint before long gates, preserve logs and exact head, stop/report provider or unexpected runtime failures. Do not repeat the five parked iOS failures for unrelated feature work. New logic still needs focused checks; holding known failures is not permission to skip new regression coverage.
