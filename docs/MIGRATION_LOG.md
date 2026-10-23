# KMP migration log (per phase: merge, reviewer verdict, residual gates)

| Phase | Branch @ head | Merged as | Reviewer verdict | Residual gates |
|---|---|---|---|---|
| P0 layout + tuple | p0-layout @ 92b73af (+00cf9e9 CI Xcode 26.6 pin) | dd2c8ab | COMPLETE after Xcode-pin fix | hosted CI not yet indexed by GitHub (see below) |
| P1–P3 core (L1) | l1-core @ b0c8aac | (this merge) | COMPLETE, no source findings; 44a3713+42955c7 are an atomic pair for bisect | API-28 runtime / physical TV; live account; iOS Keychain persistence in an app host (P8); wasm network-error mapping + durable/quota/multi-tab storage (P9) |

Hosted CI: after the P0 merge GitHub Actions has not indexed the new workflow files (no runs for dd2c8ab/f47b445; `gh workflow run tv-linux.yml` → 404). Files verified present/valid; token has `workflow` scope; Actions enabled. Re-check at each merge; escalate to owner if it persists.
| P6 server (L2) | l2-server @ aa4184c (5 review rounds) | merged after c08a5d0 | COMPLETE; wire: sessionGeneration is a decimal STRING, opaque, equality-only | single-instance in-memory sessions; live TMDB approval + account writes; P9 multi-tab/auth integration |
| P5 Koin (L5) | l5-koin @ 72c7d10 | merged after P6 | COMPLETE, no findings; modules are functions (Koin 4.2.2 shared-singleton pitfall) | P8/P9 must wire the app-lifetime content-settings→cache-invalidation collector (today only in TV AppContainer); iOS Keychain app-host; browser durable/multi-tab |
| P4 presentation (L4) | l4-presentation @ c753b4e (rebased on f7d1278) | fast-forward after P5 | COMPLETE after r2 (Browse enum FQN kept; ApprovalFlow supersession during wait) | P8 first gate: human Android/iOS Approve → cineverse://auth return; persistent pending store + platform ApprovalFlow actuals; starting a new attempt never retracts a committed session |
