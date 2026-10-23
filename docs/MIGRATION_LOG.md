# KMP migration log (per phase: merge, reviewer verdict, residual gates)

| Phase | Branch @ head | Merged as | Reviewer verdict | Residual gates |
|---|---|---|---|---|
| P0 layout + tuple | p0-layout @ 92b73af (+00cf9e9 CI Xcode 26.6 pin) | dd2c8ab | COMPLETE after Xcode-pin fix | hosted CI indexing issue resolved; verified runs below |
| P1–P3 core (L1) | l1-core @ b0c8aac | c08a5d0 | COMPLETE, no source findings; 44a3713+42955c7 are an atomic pair for bisect | API-28 runtime / physical TV; live account; iOS Keychain persistence in an app host (P8); wasm network-error mapping + durable/quota/multi-tab storage (P9) |
| P6 server (L2) | l2-server @ aa4184c (5 review rounds) | merged after c08a5d0 | COMPLETE; wire: sessionGeneration is a decimal STRING, opaque, equality-only | single-instance in-memory sessions; live TMDB approval + account writes; P9 multi-tab/auth integration |
| P5 Koin (L5) | l5-koin @ 72c7d10 | merged after P6 | COMPLETE, no findings; modules are functions (Koin 4.2.2 shared-singleton pitfall) | P8/P9 must wire the app-lifetime content-settings→cache-invalidation collector (today only in TV AppContainer); iOS Keychain app-host; browser durable/multi-tab |
| P4 presentation (L4) | l4-presentation @ c753b4e (rebased on f7d1278) | fast-forward after P5 | COMPLETE after r2 (Browse enum FQN kept; ApprovalFlow supersession during wait) | P8 first gate: human Android/iOS Approve → cineverse://auth return; persistent pending store + platform ApprovalFlow actuals; starting a new attempt never retracts a committed session |
| P7 design system (L3) | l3-design @ b5e69a6 | not merged | Source/tests approved after r4; acceptance INCOMPLETE | Owner's actual Safari render/Tab/Escape/focus-return/console check |

Hosted CI indexing was subsequently resolved. All three workflows passed on `66e219b` and `87cb193`:
[TV](https://github.com/GvHarish9894/CineVerse/actions/runs/39845723853),
[shared/server/web](https://github.com/GvHarish9894/CineVerse/actions/runs/39845723753),
[mobile](https://github.com/GvHarish9894/CineVerse/actions/runs/39845723446) (links are for `87cb193`, not proof of later heads).

Wave C resumed with GPT-only agents and GvHarish9894 repository credentials; component ownership and handoffs
are recorded in [WAVE_C_PLAN.md](WAVE_C_PLAN.md).
