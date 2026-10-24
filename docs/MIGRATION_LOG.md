# KMP migration log (per phase: merge, reviewer verdict, residual gates)

| Phase | Branch @ head | Merged as | Reviewer verdict | Residual gates |
|---|---|---|---|---|
| P0 layout + tuple | p0-layout @ 92b73af (+00cf9e9 CI Xcode 26.6 pin) | dd2c8ab | COMPLETE after Xcode-pin fix | hosted CI indexing issue resolved; verified runs below |
| P1–P3 core (L1) | l1-core @ b0c8aac | c08a5d0 | COMPLETE, no source findings; 44a3713+42955c7 are an atomic pair for bisect | API-28 runtime / physical TV; live account; iOS Keychain persistence in an app host (P8); wasm network-error mapping + durable/quota/multi-tab storage (P9) |
| P6 server (L2) | l2-server @ aa4184c (5 review rounds) | merged after c08a5d0 | COMPLETE; wire: sessionGeneration is a decimal STRING, opaque, equality-only | single-instance in-memory sessions; live TMDB approval + account writes; P9 multi-tab/auth integration |
| P5 Koin (L5) | l5-koin @ 72c7d10 | merged after P6 | COMPLETE, no findings; modules are functions (Koin 4.2.2 shared-singleton pitfall) | P8/P9 must wire the app-lifetime content-settings→cache-invalidation collector (today only in TV AppContainer); iOS Keychain app-host; browser durable/multi-tab |
| P4 presentation (L4) | l4-presentation @ c753b4e (rebased on f7d1278) | fast-forward after P5 | COMPLETE after r2 (Browse enum FQN kept; ApprovalFlow supersession during wait) | P8 first gate: human Android/iOS Approve → cineverse://auth return; persistent pending store + platform ApprovalFlow actuals; starting a new attempt never retracts a committed session |
| P7 design system (L3) | l3-design @ b5e69a6 | not merged | Source/tests approved after r4; acceptance INCOMPLETE | Owner's actual Safari render/Tab/Escape/focus-return/console check |
| P9 browser-storage foundation (C3) | c3-browser-storage @ 94be9d9 | 76d1e02 | COMPLETE after r2; commit-owned invalidation survives caller cancellation | Browser DI/session/import-lock integration; Firefox/Safari/private-mode/full-product/top-level-tab and durability-policy acceptance remain pending |
| P9 HTTP foundation (C2) | c2-web-http @ 2556dff | ed3982e | COMPLETE after r2; delivery-time expiry freshness + supported Settings logout failure fixed | Browser DI/lifecycle/import integration and deployed browser/auth/product gates; prior TV UI evidence is not new-head device acceptance |
| P8 native-approval foundation (C1) | c1-native-auth @ 311f4e9 | 113fea1 | COMPLETE after r2; background cancellation preserves pending recovery | Real Android/iOS approval/return and production-app persistence; physical/release and full historical upgrade gates |
| P8/P9 catalog extensions (C5) | c5-catalog @ 3b4be68 | ca2f39b | COMPLETE; discover/keywords direct+proxy/cache contracts | Product Browse/Detail behavior and live/platform acceptance |
| P9 browser integration (C4) | c4-web-integration @ 518b45f | 8c186d6 | COMPLETE after r2; forced-retry permission consumed, real content retained, explicit UI owners | Deployed product/UI/auth/browser-policy acceptance and full historical upgrades |

C3 merged gate at `76d1e02`: JVM **206**, Android-host **200**, Chromium/Wasm **160**, all zero
failures/errors/skips; production distribution and **18-file** secret scan passed. Log:
`/tmp/cineverse-supervisor-review-gates/c3-merged-master.log`. This is component acceptance, not full P9 acceptance.

Combined C2+C3 gate at `ed3982e`: JVM **231**, Android-host **225**, iOS **228**, Wasm **186**,
server **52**, TV unit **63**; all zero failures/errors/skips. TV debug/lint/release, phone debug, iOS
framework link and **18-file** production secret scan passed. Log:
`/tmp/cineverse-supervisor-review-gates/c2-c3-merged-gates.log`. No combined live-account/device-UI acceptance is implied.

Combined C1+C5 source at `113fea1`: JVM **256**, Android-host **250**, iOS **253**, compose Android
**2**, compose iOS **3**, TV unit **63**, server **52**, Wasm **195**, all zero failures/errors/skips.
TV debug/lint/release, phone debug/release, production iOS framework/Swift host and **18-file** secret scan
passed. Logs: `/tmp/cineverse-supervisor-review-gates/c1-c5-merged-{native,browser,swift}.log`.
These are automated combined gates, not real sign-in/product/hardware acceptance.

Combined C4 foundation gate at `8c186d6`: shared JVM **265**, Android-host **259**, iOS **262**,
Wasm **229**, compose Android **2**, compose iOS **3**, TV unit **63**, server **52**; all zero
failures/errors/skips. Native/web builds, production Swift host and **18-file** secret scan passed.
Integrated TV raw UI: **38 passed** plus the existing ignored smoke; settings hash restored. Debug-key-signed
R8 release installed over data and booted with a live foreground app process; no live-catalog/real-account
acceptance is implied. Logs: `/tmp/cineverse-supervisor-review-gates/c4-all-merged-*` and
`/tmp/cineverse-integrated-tv-8c186d6/`.

P7 design source may be integrated into an isolated **non-main UI staging branch** for continued development;
this does not approve/promote P7, alter the owner's b5e69a6 preview, or waive its manual Safari gate.

Reviewed Wave D shared-only integration at `a50c20d`: Home paging/storage recovery (D0 final
`adb67d4`, including independently re-reviewed marker correction) and identity-bound defaults read
(S1 `dbfb69e`) were cherry-picked without any P7/mobile/web UI source. Combined shared JVM **302**,
Android-host **296**, iOS **299**, Wasm **267**, TV unit **63**, server **57**, compose host **2** /
iOS **3** all pass with zero failures/errors/skips; native/web builds, lint/release and **18-file**
secret scan pass. Evidence: `/tmp/cineverse-supervisor-review-gates/wave-d-core-merged-*`.
These core iOS results do not clear the separate interrupted mobile-UI iOS suite. P7 owner Safari,
M1 source-fix re-review/recovery wiring and W1 strict reload diagnosis/recovery wiring remain open.

Hosted CI indexing was subsequently resolved. All three workflows passed on `66e219b` and `87cb193`:
[TV](https://github.com/GvHarish9894/CineVerse/actions/runs/39845723853),
[shared/server/web](https://github.com/GvHarish9894/CineVerse/actions/runs/39845723753),
[mobile](https://github.com/GvHarish9894/CineVerse/actions/runs/39845723446) (links are for `87cb193`, not proof of later heads).

Wave C resumed with GPT-only agents and GvHarish9894 repository credentials; component ownership and handoffs
are recorded in [WAVE_C_PLAN.md](WAVE_C_PLAN.md).
