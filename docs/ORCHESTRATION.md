# KMP migration — orchestration rules (source of truth for lanes)

Normative documents, in order of authority:
1. `docs/KMP_ARCHITECTURE_PLAN.md` (v2, reviewed) — modules, source sets, phases, decisions K1–K11.
2. `docs/IMPLEMENTATION_SPEC.md` — protocol behaviour shared by every platform (auth v4, TMDB error table,
   token kinds, generations/identity, no-overlay player, import ledger). TV sections are the reference
   implementation: **when unsure, read how `tvApp` does it.**
3. `docs/kits/{mobile,web}/*` + `design/` — product requirements for mobile/web (inputs, not authority).

## Lanes and worktrees (cap 6)

| Wave | Lane | Scope (plan phases) | Depends on |
|---|---|---|---|
| A | P0 | repo layout + toolchain tuple proof | — |
| B | L1 core | P1 portable core → P2 Ktor transport → P3 native credentials/settings (sequential in one lane) | P0 |
| B | L2 server | P6 Ktor proxy + security suites | P0 |
| B | L3 design-system | P7 composeApp theme/tokens/primitives gallery (mobile + web shells' shared parts) | P0 |
| B | L4 presentation | P4 shared state holders + TV adapter VMs + auth-callback spike | L1 |
| B | L5 DI | P5 Koin at composition roots | L1, L4 |
| C | M1–M3 mobile | P8 screens split by group (shell+Home · Detail/Season/Person · Explore/Search/Library/Profile) | L1, L3, L4 |
| C | W1–W3 web | P9 screens split by group (routing/shell+Home · Browse/Detail/Season/Person · Search/Library/Settings/player) | L1, L3, L4, L2 |
| D | release | P10 pipelines | all |

Merge order inside a wave: the lane others depend on first; integrator resolves conflicts under the plan's
ownership rules (TV UI untouched; `shared` contracts canonical).

## Loop per lane (every lane, no exceptions)

```
brief → implement in own worktree → gates green → FINAL REPORT
  → orchestrator sends to reviewer (intercom 01a15a3b) → findings
  → orchestrator resumes/relaunches the lane worker with the findings → fixes → report
  → repeat until "REVIEW COMPLETE: no further blocking gaps"
  → integrator merges to master (fast-forward or merge commit) → master gate
```
Workers have no intercom; the orchestrator brokers all reviewer traffic and all supervisor decisions.
Workers raise blockers/decisions via `contact_supervisor`; they never wait silently.

## Gates

- **TV gate (every lane that touches `shared`, `tvApp`, build files)**: `:tvApp:assembleDebug :tvApp:lint
  :tvApp:testDebugUnitTest :tvApp:assembleRelease` + instrumented `com.techgv.cineverse.ui` on the TV emulator
  (38 OK / 1 skipped baseline) + install-over-old-data fixture.
- **shared**: `:shared:jvmTest :shared:testDebugUnitTest :shared:compileKotlinWasmJs` (+ iOS test task on the Mac).
- **composeApp**: android assemble, `linkDebugFrameworkIosSimulatorArm64`, `wasmJsBrowserDistribution`,
  `checkWasmHasNoSecrets`.
- **server**: unit + the security suites (two-account isolation, cache private/public, CSRF, expired session).
- Nothing merges with a red gate. No `@Ignore` to make a gate pass.

## Devices (shared; exclusive slots brokered by the orchestrator)

| Device | Serial / name | Used by |
|---|---|---|
| Android TV emulator | `CineVerse_TV_1080p_API36` → `emulator-5554` | TV gate, L1/L4/L5 |
| Android phone emulator | `CineVerse_Phone_API36` → `emulator-5556` | mobile lanes, L3 |
| Android tablet emulator | `CineVerse_Tablet_API35` → `emulator-5558` | mobile/web-width checks |
| iOS simulator | iPhone 17 Pro, iOS 26.x (Xcode 27.1) | L3, mobile lanes (no sharing issue) |
| Browsers | Chrome / Firefox / Safari on this Mac | web lanes |

Rules: never launch an emulator that `adb devices` already lists; always `adb -s <serial>`; a physical phone is
attached — never target it; request a slot via `contact_supervisor` ("REQUEST EMULATOR SLOT: <lane>, <serial>,
<N> min, plan: …"), wait for "SLOT GRANTED", release with "SLOT RELEASED: <lane>"; reinstall your own build
before testing (TV and phone apps have different applicationIds, so they don't collide across devices, but
lanes on the same device do). Never `read` screenshots into context — verify via uiautomator/logcat/dumpsys.

## Decisions taken by the orchestrator (owner delegated)

- **D8 / K7 — TV `minSdk` → 28** (within the plan's 26–31 band; Play TV requires ≤ 31). Done in L1 after P0,
  with the TV gate + a minSdk-28 API-usage lint pass; `compileSdk`/`targetSdk` unchanged. Rolled back if any
  TV behaviour changes.
- **Server deployment**: plain Docker-able Ktor app, same-origin static hosting of the wasm bundle under `/`
  and the API under `/api` (cookies "just work"); no cloud-specific code.
- **Model**: all subagents (workers, reviewers, scouts and validators), including new or resumed work in existing
  worktrees, use `cliproxyapi/gpt-6.1-sol`. Pass the model explicitly at every launch; no automatic Claude fallback.
  Intercom reviewer `01a15a3b` must also confirm GPT before any further review; otherwise use a GPT reviewer subagent.
- **Package / ids**: per plan K2.

## Reporting

Each lane's FINAL REPORT (text only): branch + head; what was built vs its brief; gate results with task
names and counts; devices used; shared/contract changes; spec gaps/decisions; what the next lanes must know.
The orchestrator keeps `SHELL_CHANGES.md` (TV) and a new `docs/MIGRATION_LOG.md` (per-phase merge log,
reviewer verdict, residual gates) up to date at every merge.
