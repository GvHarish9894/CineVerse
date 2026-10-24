# Wave C — product implementation lane board

Owner resumed implementation after the pause. All agents and reviews use
`cliproxyapi/gpt-6.1-sol`; Git identity is `GvHarish9894 <hariharangv9894@gmail.com>`.
The six-worktree cap includes the main checkout. Leave unrelated IDE/untracked files alone.

## Preflight

This work is **multi-seam**: native approval, proxy transport/auth, and browser durability are independently
implementable/testable contracts. They have isolated component writers. After committed handoffs and review,
the orchestrator appoints one integration-only component owner (C4) for browser DI/lifetime observers. The
orchestrator merges reviewed handoffs; product screen lanes follow this integration rather than each inventing
their own transport/storage/auth wiring.

L3/P7 (`l3-design` @ `b5e69a6`) is source/test-approved but **not accepted/merged** until the owner reports the
actual Safari check. Its preview is served independently on port 8765; no worker may replace that build.
Native/web foundation work below does not require that pending UI merge.

## Foundation lanes (base `bddec7f`)

| Lane | Worktree / branch | Decision / exclusive ownership | Authority | Next gate / handoff | Why independent |
|---|---|---|---|---|---|
| C1 native approval | `../CineVerse-c1-native-auth` / `c1-native-auth` | Native redirect-aware token creation; encrypted persistent pending attempts; real Android Custom Tabs/iOS ASWebAuthenticationSession flows; callback host wiring and phone backup exclusions. Own native auth/host files; no browser/shared public auth-contract changes without coordination. | Implement/test/commit only; no merge/push/account writes. | Native/common tests, host links/builds, TV regression; committed API + manual approval instructions. | Uses existing direct transport/secure storage, not browser HTTP/storage. |
| C2 browser HTTP | `../CineVerse-c2-web-http` / `c2-web-http` | New proxy catalog/account/session/approval adapters and safe error/identity mapping; browser HTTP/cookie adapter. Own new `data/web` code/tests and new transport-specific bindings only. | Implement/test/commit; ask before server/public contract changes; no merge/push. | MockEngine/real-browser transport tests, signed-Long string extremes, no-secret scan; committed API handoff. | Accepts existing storage/account interfaces; does not implement browser persistence or final DI. |
| C3 browser durability | `../CineVerse-c3-browser-storage` / `c3-browser-storage` | Browser settings/guest/ledger/offers over durable transactions; migration/error behavior; non-secret revision notifications and lifecycle revalidation signals. Own browser storage code/tests, including existing BrowserSettingsStore. | Implement/test/commit only; no merge/push; no HTTP/auth policy edits. | Browser durability/reload/quota/blocked/concurrent-tab fixtures; committed constructors/event API handoff. | No network/session protocols, catalog methods or UI ownership. |
| C4 browser integration | Reuse `../CineVerse-c2-web-http` / branch `c4-web-integration` after C2 review/merge | Compose reviewed C2+C3 into wasm DI/lifetime wiring; guarded import entry, no echo-loop notifications, startup/teardown and visible failures. Own browser DI/runtime/main/tests; no catalog/native/UI behavior. | Implement/test/commit; no merge/push. | Real graph/isolation/lifecycle/invalidation/import-lock integration tests + independent review. | C2/C3 writers finished; this owner wires their contracts, not their implementations. |
| C5 catalog extensions | `../CineVerse-c5-catalog` / `c5-catalog` | Discover genre/sort and keywords across common API/repository, direct+proxy adapters and affected fakes/tests. | Implement/test/commit; no merge/push. | Exact endpoint/query/cache/normalization tests on all targets + TV compatibility/review. | One tightly coupled API surface across the same files; splitting discover/keywords would overlap those owners. No browser DI/native auth edits. |
| Final integration | main checkout | Merge reviewed C4/C5/C1 handoffs, resolve only contract/build seams; product screen lanes follow. | Merged gates; push as GvHarish9894. | Shared/TV/server/mobile/web combined gates + required independent reviews. | No simultaneous root writer; no component reimplementation. |

All component writers leave `shared/.../di/WebModules.kt` and the common `SharedModules.kt` to integration.
Any necessary shared contract/build-file overlap is a supervisor decision before editing. Each report names
exact commits, changed contracts, focused tests, actual runtime evidence, and unverified human/device gates.

## Frozen boundary decisions

- The web API is the implemented server contract, not the outdated kit auth endpoints. Catalog is
  `/api/tmdb/<v3 subpath>` **without `3/`**. Native `KtorTmdbApi` cannot be repointed at the proxy.
- Session generations are signed 64-bit **decimal strings**, equality-only. Cookies/tokens/native credential
  types never become browser account credentials. Signed-out session JSON maps to `null`.
- Every POST/DELETE uses the current readable `cv_csrf` cookie as `X-CV-CSRF`; account writes also use the
  expected account/generation captured at click time. Stale409 actions are dropped, never replayed as another
  identity.401 triggers authoritative revalidation, not unconditional logout; only explicit logout deletes
  the server session cookie. Unknown write outcomes are reconciled rather than blindly retried.
- Web approval uses the **server-owned** attempt id and a separate descriptor/controller; do not use the native
  LoginStateHolder's locally generated attempt id or fabricate AccountDto/defaults. Native-only request-code
  methods may fail clearly on the web adapter; browser UI uses the explicit web approval seam.
- Safe TMDB account-default metadata needs an explicit server contract if retained for web; no invented values.
  Ask before extending server session/account responses.
- A BroadcastChannel/storage marker is a notification, not a transaction/lock. Storage calls return only after
  durable commit. Blocked/quota failures must reach UI; no silent volatile fallback. Any localStorage fallback
  must retain cross-tab serialization (e.g. Web Locks) or reject unsupported mutations visibly.
- Session truth is authoritative. Starting attempt B does not retract an already committed A. Native shell
  coordination cancels/joins replaced work; callbacks are hints and must match the current persisted attempt.
- TV identity/schema/file/key aliases and all TV UI behavior remain unchanged.

## Current status

- C3 browser-storage component merged as `76d1e02` after round-2 REVIEW COMPLETE; merged JVM206/Android200/Wasm160
  and production secret scan pass. Its worktree is retained; browser DI/product acceptance is still pending.
- C2 HTTP `2556dff` merged as `ed3982e` after round-2 REVIEW COMPLETE. Combined JVM231/Android225/iOS228/Wasm186,
  server52/TVunit63 and build/scan gates pass. Its finished worktree is reused by C4; old C2 branch/history remain.
- C4/C5 start from this reviewed combined foundation (plus the lane-board documentation). Six total worktrees
  maximum; no temporary branch/history is deleted to make room. Native C1 recovery remains on its own branch.

- C1 native candidate `5bd81ca` has one required iOS scene-background cancellation fix unfinished after a GPT proxy
  stream failure. Its partial patch is retained at `/tmp/cineverse-review-diffs/c1-r2-interrupted-5bd81ca.patch`;
  no alternate model/execution mode was selected.
- P7/L3 remains unmerged pending the owner's actual Safari result. No human/platform gate is waived.

## Next slices after foundations

Add reviewed discover/keywords/recent-search/catalog capabilities just in time, then run the existing
M1–M3/W1–W3 screen ownership groups from ORCHESTRATION.md in bounded waves (never seven simultaneous worktrees).
Separate mobile and web shells consume shared primitives/holders. No UI lane invents credentials/storage.

Human acceptance still required: P7 Safari on the actual served build; real Android+iOS approval return and
account writes/import; real-TV/API28/platform/release/policy gates as recorded in MIGRATION_LOG.md. A proposed
check or a successful build is not a passed human/platform acceptance gate.
