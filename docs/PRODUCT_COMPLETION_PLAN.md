# Web + Android + iOS completion — 2026-10-27

## Current operator decision

Stop the local deployed preview and implement the remaining web and both mobile apps in parallel. This supersedes web-first/mobile-first holds. PID22225 was stopped; port49272 has no listener. Do NOT restart the persistent preview unless requested. Temporary, owned loopback test servers must be stopped after checks.

Use `cliproxyapi/gpt-6.1-sol` explicitly for every subagent. No provider/model/CLI/auth fallback. Preserve partial work and report terminal provider/infrastructure failures; retries need operator direction. Recovered Kotlin incremental diagnostics inside a successful invocation are recorded, not silently counted as terminal build failures.

## Privacy and safety boundaries

- Repository/worktrees, managed artifacts, required exact SDK/Gradle/Xcode paths and owned test temp only. No Desktop, Pictures, Photos, iCloud/cloud-storage or broad home searches; use repo kit docs. Never grant/change OS privacy permissions.
- Use isolated agent-owned Chrome profiles and emulator/simulator test hosts only. Current request covers necessary local product validation; no personal profiles/physical devices, resets, user-data migration/destruction, real account writes or permission bypasses. Confirm device ownership before use. Native UI tests are now a required completion gate, not perpetually waived by compilation.
- Real TMDB-account acceptance still requires operator interaction. External deployment needs a supplied target/credentials; do not invent either or claim it happened. No push until correct GitHub identity is verified; never switch global auth.
- Six worktrees including main maximum, retain milestone branches; one writer per worktree. Exactly ONE CineVerse Gradle/Karma/browser/device-test lease at a time. Do not touch unrelated Zuper builds/daemons.
- Commit author AND committer: GvHarish9894 <hariharangv9894@gmail.com>. Main remains docs-only; preserve owner IDE/app/staging. Commit docs with explicit `--only` paths.

## Existing checkpoints (read actual artifacts before continuing)

| Slot | Branch/head | Current state |
|---|---|---|
| C3 `../CineVerse-c3-browser-storage` | `web-integration@d1bc075` | Last web build: implemented routes, polish, popup/session/redirect and Detail-back fixes; server stopped. Known keyboard/AX/font and final live checks open. |
| C5 `../CineVerse-c5-catalog` | `mobile-integration@6a5435f` | Search/storage/Detail/rate/Library source integrated. Root41 host passes and five native compile targets. Root follow-up closes local-read P1 but one introduced same-owner fresh-prompt close continuation remains. Library tab not yet bound; Profile/Person/Season/trailer destinations incomplete. |
| C1 `../CineVerse-c1-native-auth` | `mobile-person-component@7c505c6` | Person20 host/5compile prior a97140c; latest scroll-only correction + tests uncompiled; needs corrected compile + targeted review. |
| C2 `../CineVerse-c2-web-http` | `mobile-season-component@c9ae87d` | Season29 host/5compile pass;17 UI compiled-only; targeted scroll follow-up review pending. |
| retained design | `l3-design@b5e69a6` | Owner/Safari milestone, untouched. |

Prior evidence is not final-head acceptance. Five historical iOS failures stay enabled and explicit: two unsupported StateRestorationTester helper cases and three unconfirmed navigation/storage-recovery UI failures. Reproduce/diagnose on owned test hosts; never disable or count compilation as passes.

## Partitioned lane board / first wave

This is MULTI-SEAM. Independent components have exclusive writers; parent merges only cleared handoffs, then one integration-only owner wires app roots.

| Lane | Isolated cwd | Exclusive contract/files | Authority now | Next gate / handoff | Independence |
|---|---|---|---|---|---|
| W1 web keyboard/AX | C3, new `web-final-accessibility` from d1bc075 | Web root/header/dialog-trigger focus, trailer DOM focus, real modal semantics, corresponding web tests; minimal common overlay hook only if native defaults preserved | Source/tests/commit; NO initial build lease | Focused browser + real production-document keyboard/AX checks after source handoff; native compatibility compile if shared UI changes | Separate web platform/rendering seam, no mobile root files |
| M1 corrected Person | C1/7c505c6 | Same five native Person component/test files only | Sole native host/compile lease for corrected five-target compilation; no unrelated successful-host repeats | Stable exact head + source parity; targeted Person review; later rendered UI checks | Public component, no root edits |
| M2 remaining root prompt P1 | C5/6a5435f | MobileDetailEntry and root-specific regression/fixture only | Source/tests/commit ONLY while C1 builds | New real same-owner recovery opens prompt during context refresh; follow-up review/gates | Root continuation seam, no shared producer or component redesign |
| M3 Season closure | C2/c9ae87d | Exact accepted scroll-fix follow-up | Read/search-only review, no builds/edits | Named P1 closure or exact correction; slot reused after handoff | Stable component independent of root/Person |

Initial wave launched as workflow404cfb57: W1 web source, M1 corrected Person compilation (sole lease), M2 root fresh-prompt source correction, M3 Season targeted review. No background test polling. Parent runtime inventory only: no Android devices connected; existing `CineVerse_Phone_API36`, TV and tablet AVDs available; all iOS simulators shutdown, historical owned iPhone17Pro `1F4DFB14-7325-4855-BE5E-4DD01D97D168` available. Nothing booted/deployed/reset. `adb devices` started the standard adb daemon automatically; no emulator or personal device was used.

### Current scoped amendments and rolling slot reuse

W1 inspected installed CMP1.11.1: only last scene owner is exposed (focusable Close tooltip masks dialog), and dialog/paneTitle semantics are not mapped. Parent approved Buttons.kt additive showTooltip=true with false ONLY for web CvDialog Close (replaces earlier tooltipFocusable proposal: even nonfocusable tooltips attach a scene and mask AX); Overlays default-noop platform hook and DialogProperties wasm annotation of existing actual modal semantics node (Android/iOS actual noop), cleanup scoped to exact node/owner. No duplicate invisible controls/semantics tree. Installed listener may also lose background semantics after layer disposal; actual AX restoration is REQUIRED. Prefer public API; if unavailable, bounded web-only same-scene modal behind existing CvDialog/native-default contract is approved. No private framework reflection/fork, global app/graph/history remount, or false success with broken post-close AX. Two iframe boundary focus guards permitted (NOT focusable aria-hidden descendants), no reading/intercepting cross-origin YouTube controls. Optional CvDialog onRestoreFocus callback propagated through five web dialog wrappers, null preserves native behavior; ordinary callback recomposition must not trigger disposal focus return. Source-only, no browser/build yet.

M3 Season follow-up c63d67d9 atc9ae87d is OK with notes: both scroll P1s CLOSED in source; host29/0 and17UI compiled-only/5nativecompile retained. Parent preserved mobile-season-component and reused C2 as web-font-resilience fromd1bc075 for independent W2 FONT/BOOTSTRAP source seam. W2 owns CineVerseTheme font-selection override with native defaults unchanged, wasm main/font provider+matching tests only; MUST NOT edit W1 WebApp/header/overlay/buttons/trailer focus files. W2 source-only, no initial lease. Source graph/application lifetime never recreated on font failure/retry. Parent merges cleared Season into mobile candidate at next clean C5 writer boundary.

M1 corrected Person compilation completed at unchanged clean7c505c6: five native targets EXECUTED,11UI compiled-only, owner20/0 prior source parity (no unnecessary host repeat). Root remaining fresh-prompt fix cleandfa7b0b adds only comment+operation++ AFTER fresh verified prompt acceptance and one actual same-owner recovery regression; fix SHA624618d752d794b458504319ec6f15715c09714b5dcfdd9bfd083a43d586536e,18rootUI current new case uncompiled. Parent merged cleared Season into clean C5 ->b745076943f981c7fa10540c6196d098f5a34424, no conflicts and source parity checked. Required short targeted reviews: Person scroll correction and root remaining prompt continuation; W1/W2 writers continue source-only. No build lease held after Person handoff; subsequent merged-head/native runtime checks serialized. Persistent preview remains off.

### Usage-limit recovery and next rolling wave

W1 and W2 failed with ChatGPT usage limit; operator explicitly said try now. Parent inspected/preserved partial work before retry: C3 HEAD6939fcc tests-first + nine dirty/untracked accessibility paths (12 cumulative changed paths), C2 HEADd1bc075 + five dirty/untracked font paths. Exact working-source snapshots/patches/manifests under checkpoints/web-quota-retry/20261028T020455Z/{accessibility,font}; tar SHA88fa23e021d16e4fbebe0c4ba2bd82b473532391d3a0c60184e4b6a6b2a8e208 /9791abc3d60fb5fa755f82f52572e1c05c163a0d7317e109c7f31d0c49acea60. No builds or successful gate claims for these partials. Retry same source seams/model only; no regeneration/fallback. W1 approvals showTooltip/web-only same-scene modal/sentinels/captured restore callback all retained; no font overlap.

Both native targeted reviews completed OK with notes: Person scroll7c505c6 and root fresh-promptdfa7b0b are source-review CLOSED; owned runtime still required. Parent merged Person into clean C5 ->eb258c16f35af208fadfd864769b633e225b93b1, conflict-free/parity checked, with cleared Season already present. Preserved all branches. C1 reused as native-profile-settings fromeb258c1 for NEW Profile/preferences owner/screen/picker/tests only (no root/graph edits), real captured sign-in/out callbacks; account dialogs/Library root binding remain separately owned. C5 first Android UI validation may use ONLY confirmed-owned CineVerse_Phone_API36 at free5556, no wipe/reset/physical device, sole native build/device lease; inventory was empty. First run infrastructure smoke then scoped component/root cases, preserve failures, no assertion weakening. No existing source changes in validation-only slice. iOS owned hosted checks follow serially, not waived. Persistent preview remains STOPPED.

W2 source/API supervisor amendment: pinned coroutines Promise.await can wrap native rejections as plain Kotlin Exception, so matching 'Non-Kotlin exception' message would hide unrelated programmer errors. Parent rejected message-string/catch-all classification. Approved minimal WASM-only typed fetch/body bridge for the seven real assets, URL resolved by installed public Res.getUri/resource mapping; normalize native transport/HTTP/body errors BEFORE await, preserve Kotlin mapping/programmer errors and cancellation/owned abort cleanup. Confined to font seam, no global interception/framework/new cache, native fonts unchanged. Deterministic actual Promise rejection/body/404/cancel/success tests required. Same source-only/no browser/build lease. Decision323b22a9 delivered; stale waiting notification does not mean a new unanswered request.

## Subsequent delivery stages (not optional omissions)

### Web

1. Keyboard/AX: actual iframe forward/back Tab path, stable focus return to actual trigger, header dead/split focus, narrow logo name, modal role/name/controls exposed. Reproduce on the real component path, not injected-iframe approximations.
2. Font/asset failure: recoverable/static-font fallback with no uncaught fetch leaving session/settings stuck. Separate bootstrap/font seam after W1 handoff; preserve house typography when assets load.
3. Independent review of polish + later fixes, desktop/phone screenshots from repo kit expectations, real production-document navigation/session/approval/refresh checks. Fake transports can test lifecycle and failures but not claim real-account approval.
4. Safari/mobile browser, corruption/quota/private/eviction/multitab failures, CSP/BFF/header/CSRF/session/error redaction and production secret scans. Explicit evidence and residual blockers, no fabricated deployed acceptance.

### Android + iOS

1. Clear current root/Person/Season findings, merge reviewed components, bind Person/Season per actual navigation-entry lifecycle (same typed graph, no parallel business stacks).
2. Native Profile/Settings + sign-in/account dialog flow as bounded components using existing settings/auth/import holders, actual platform approval hosts and captured sessions. Wire Library tab and real sign-in/explore CTAs; no no-op buttons or guessed all-account counts. Sign-out/import/default-offer outcomes honest, no automatic write replay.
3. Validated YouTube trailer open-out/platform URL adapters (and genuine sharing if shipped); no fictitious movie/episode streams/player controls.
4. Owned Android emulator + hosted iOS simulator UI validation for new screens, keyboard/insets/accessibility/sheets/back/tab/foreground/restoration; diagnose five old iOS failures with exact traces. Resolve actual Skiko/runtime compatibility if reproduced, not arbitrary upgrades to silence warnings.
5. Combined final-head host/native/UI/package/TV regression gates, independent integrated review, screenshots and explicit operator account/hardware acceptance boundaries.

## Finish criteria

No known reachable P0/P1 remains unaddressed; each delivered component and root is independently reviewed; final-head validation artifacts distinguish executed, compiled-only and blocked cases. All live test servers/profiles are cleaned; persistent preview stays off. Product acceptance, external deployment and push are reported separately from local implementation; no claim of all-done while real-account/deployment requirements remain unsupplied.
