// Wave 2: four parallel implementation lanes on top of the merged Phase 1 foundation.
// Each child runs in its own managed git worktree (worktree: true at the call site).
const MODEL = "cliproxyapi/gpt-6.1-sol";

const COMMON = `
You are implementing one lane of the CineVerse Android TV app (Compose + androidx.tv.material3, Kotlin, TMDB).
You work in a dedicated git worktree (your cwd) branched from master. Commit on your branch; do NOT merge, do not touch other worktrees.
Env: export ANDROID_HOME=$HOME/Library/Android/sdk. Gate: ./gradlew assembleDebug lint testDebugUnitTest must stay green; run assembleDebugAndroidTest too if you add instrumented tests.
Copy /Users/harish/AndroidStudioProjects/CineVerse/secrets.properties into your worktree root (git-ignored; never commit it). An Android TV emulator AVD "CineVerse_TV_1080p_API36" exists; use it to verify your screens with real D-pad input (adb shell input keyevent) and take screenshots into /tmp/<lane>/.

READ FIRST, completely: docs/IMPLEMENTATION_SPEC.md (normative; your lane's sections are listed below, but §1, §3, §6, §12 apply to everyone), then the foundation you build on:
- Phase 1 worker reports (public APIs, routes, focus helper, contracts):
  /Users/harish/.pi/agent/sessions/--Users-harish-AndroidStudioProjects-CineVerse--/subagent-artifacts/5427f406-8dfa-48b1-a8e7-f441223f7aa2_worker_output.md
  /Users/harish/.pi/agent/sessions/--Users-harish-AndroidStudioProjects-CineVerse--/subagent-artifacts/2a76f114-0217-49c8-a7ed-e6a6a66cba16_worker_output.md
  /Users/harish/.pi/agent/sessions/--Users-harish-AndroidStudioProjects-CineVerse--/subagent-artifacts/621666eb-37d9-4d8b-9a20-fe8fff4289b1_worker_output.md
- Code under app/src/main/java/com/techgv/cineverse/: data/tmdb/TmdbRepository.kt, data/auth/AuthRepository.kt, data/settings/SettingsRepository.kt, ui/nav/*, ui/components/* (FocusMemory, SectionState, PinToStart), ui/player/PlayerScreen.kt, ui/feedback/Feedback.kt.

SHARED-CODE RULES (to keep four parallel lanes mergeable):
- Do not modify files owned by another lane (ownership below). If you need a change in shared foundation code (data/*, ui/nav/*, ui/components/*), make it minimal, additive, backward compatible, and list it under "Shared changes" in your final report.
- Shared UI cards live in ui/components/ and are OWNED BY LANE 1 (browse). Other lanes: if you need PosterCard/PersonCard/LandscapeCard before lane 1 lands, create a minimal private version inside your own package named <Lane>XxxCard and note it; the merge step will replace it.
- Domain models: lane 1 creates domain/Title.kt, domain/Person.kt (+ mappers from DTOs in data/tmdb/Mappers.kt). Other lanes: create only the additional domain types you own (listed below); if you need Title/Person before they exist, define them minimally in domain/ with exactly these fields: Title(ref: MediaRef, title, overview, posterPath?, backdropPath?, releaseDate?, voteAverage: Double, voteCount: Int, genreIds: List<Int>, mediaType) and Person(id, name, profilePath?, knownForDepartment?, knownFor: List<Title>) — identical definitions merge cleanly.
- Use the existing route names in ui/nav/Routes.kt; add routes only if your lane list says so.
- Follow §1.1 generations, §1.3 focus memory (FocusRestorer/rememberFocus/restoreRowFocus), §1.4 LazyRow + PinToStart, §1.5 Coil image sizes, §3.3 error handling via SectionState, D-pad only (no touch), no Hilt, no new frameworks.
- Unit tests for mappers/view-model logic; one instrumented D-pad test per screen where feasible.

FINAL REPORT (last message): worktree path + branch + final commit; gate results; screens verified on emulator (screenshot paths); per-spec-section conformance notes; "Shared changes" list; "Spec gaps / decisions"; contracts other lanes must know. Reviews are run by the orchestrator (you have no intercom); expect a follow-up resume with reviewer findings.
`;

const LANE1 = COMMON + `
=== LANE 1: BROWSE SURFACES — Home, Browse (Movies/TV/People), Search ===
Spec sections: §5 (rail/clock/ambient backdrop), §6 (all shared components), §7.2 Home, §7.3 Browse, §7.7 Search, D5 (person hero simplified).
You OWN: ui/components/{PosterCard,LandscapeCard,PersonCard,Chip,ActionButton,HeroStage,ScoreRing,AmbientBackdrop,OnScreenKeyboard,Toast}.kt, ui/home/*, ui/browse/*, ui/search/*, domain/Title.kt, domain/Person.kt, domain/Hero.kt, data/tmdb/Mappers.kt (DTO→Title/Person), genre-name resolution helper.
Deliver: Home with hero (Title|Person) following focus, 7 rows per §7.2 incl. Trending chips, Play-trailer-from-Home via videos() fetch, HomeBackHandle wired (first BACK → focus origin, second → Exit); Browse with tabs on focus + 7-col LazyVerticalGrid + pagination + rememberSavedLazyGridState; Search per §7.7 exactly (trending suggestions on empty, ≥1 char, 300 ms debounce, stale drop, mixed grid, hardware keys, 28-char cap). Rail expands on focus; clock; ambient backdrop honouring glow/motion settings (read Settings flow; Feedback lane will add haptics). Toast component: bottom area, queue of 1, accessibility live region.
`;

const LANE2 = COMMON + `
=== LANE 2: DETAIL SURFACES — Detail (movie/TV), Person, Season, Player integration ===
Spec sections: §7.4 Detail, §7.5 Person, §7.6 Season (+ inline episode panel), §7.10 Player (already implemented in ui/player — integrate, do not rewrite), D5/D6/D7.
You OWN: ui/detail/*, ui/person/*, ui/season/*, domain/{Detail,Season,Episode,Review,Video,Credit}.kt, data/tmdb/DetailMappers.kt, ui/components/{ReviewCard,FactCard,CreditCard,SeasonCard}.kt.
Deliver: Detail hero + chips (runtime/certification rules), buttons (Play trailer hidden when no YouTube video; Watchlist/Favourite/Rate buttons call a LibraryActions interface you define in domain/LibraryActions.kt with toggleWatchlist/toggleFavourite/openRate + a Flow of per-title state; provide a no-op/guest-Room-less stub so the screen works before Lane 4 merges), Episodes button, rows: Seasons (Specials last), Top billed cast (aggregate order/roles), Videos (ordering + en-US refetch), Recommendations, Reviews (HTML strip), Facts. account_states fetched separately when signed in (catalog detail must render even if states fail). D7 fallback via movieDetail/tvDetail/person(language = "en-US") filling only blank fields, labelled. Person screen per §7.5 (dedupe by (mediaType,id), credits sorting). Season screen per §7.6 incl. reload-own-detail after process death, missing-season fallback, episode panel BACK contract, LEFT exceptions. Player: navigate with Route.Player from Detail/Home origin; BACK pops to origin with focus restored.
`;

const LANE3 = COMMON + `
=== LANE 3: ACCOUNT & PREFERENCES — Login UI, Settings, Feedback, About ===
Spec sections: §4 (UI side of auth), §7.9 Settings, §9 Feedback, §7.12 toast usage, §1.2 Login BACK rule (cold-start → Exit, pushed → pop), D2, D3, D8 note.
You OWN: ui/login/*, ui/settings/*, ui/feedback/Feedback.kt (replace stub), ui/about/*.
Deliver: Login screen per prototype (left: steps + buttons "I've approved it"/"Continue as guest" + spinner; right: QR card) using AuthRepository.createRequestCode/awaitApproval with every outcome mapped to copy (pending spinner, CodeExpired → "Get a new code", AppCredentialError banner, Rejected → terminal copy + new code, TimedOut → "Check again", Superseded ignored); polling cancelled on BACK/guest/background; toast "Signed in as {name}"; rail avatar initials. Settings screen: rows + live description pane exactly per §7.9 table (labels, ←→ cycling, defaults), language/region/adult changes trigger cache invalidation + visible reload (repository already clears cache; make sure screens re-collect), Sign in/Sign out rows (sign-out local-first already in repo), "Use your TMDB account defaults" focusable row, About with TMDB logo + attribution sentence + version. Feedback: real implementation of LocalFeedback (Vibrator guard, strength ms, waveform at row edge, CONFIRM on OK, navigation sounds with native sound effects disabled when custom sounds on, reduce-motion flag exposed for components). Hook Feedback into the rail and the shared focus modifier via a small additive helper (list under Shared changes).
Also run the live auth round-trip once using the real token: start the emulator, open your Login screen, and if a human approval is impossible for you, leave the screen in the pending state and document the exact steps; never fake it.
`;

const LANE4 = COMMON + `
=== LANE 4: LIBRARY — guest Room store, account lists, writes, Rate dialog, Import dialog ===
Spec sections: §7.8 My Library, §7.11 Rate modal, §8 (8.1 model, 8.2 guest Room, 8.3 import rules incl. ledger states), D4, D6, §3.4 write invalidation.
You OWN: data/library/* (LibraryRepository, Room entities/DAO/database, ImportLedger, AccountLibrarySource, GuestLibrarySource), ui/library/*, ui/components/RateDialog.kt, ui/components/ImportLibraryDialog.kt, domain/LibraryState.kt. Implement domain/LibraryActions.kt (Lane 2 defines the interface with toggleWatchlist/toggleFavourite/openRate + Flow of per-title state; if it does not exist yet in your branch, create it with exactly that shape — identical definitions merge cleanly) backed by LibraryRepository.
Deliver: LibraryRepository per §8.1 (identity = guest or account_object_id; writes serialized per (MediaRef,kind); optimistic update + rollback that never overrides a later success; disabled-while-pending state; signed-in offline writes fail visibly). Guest Room store per §8.2. Account source: six v4 lists via TmdbRepository.accountList with sort created_at.desc, two independently paged groups (Movies / TV) per tab, per-group loading/error, Rated badge from account_rating.value. Writes via setWatchlist/setFavorite/rate/unrate with session; after any mutation refresh accountStates for that title and invalidate lists. Library screen per §7.8 (tabs switch on focus, OK enters grid/CTA, empty states + CTAs). Rate dialog per §7.11 (0.5–10 step 0.5, default existing or 7.0, OK on slider submits, BACK cancels, word labels, post-submit refresh + "removed from watchlist" only when observed). Import dialog + importer per §8.3 (all pages first; union / TMDB-wins; per-title rating→favourite→watchlist with confirmed-step gating; ledger states attempting/done; resume reconciliation via account_states; ambiguous-removal items not auto-resumed → "Couldn't confirm {n} items" + Retry with warning). Tests: write serialization/rollback race, import crash-window fixture, ambiguous-removal fixture, mapper for account_rating.
`;

const results = await runs.all([
  { key: "lane1-browse",  agent: "worker", model: MODEL, task: LANE1 },
  { key: "lane2-detail",  agent: "worker", model: MODEL, task: LANE2 },
  { key: "lane3-account", agent: "worker", model: MODEL, task: LANE3 },
  { key: "lane4-library", agent: "worker", model: MODEL, task: LANE4 },
]);

return results.map((r, i) => ({
  lane: ["lane1-browse", "lane2-detail", "lane3-account", "lane4-library"][i],
  ok: r.ok,
  runId: r.runId,
  outputReference: r.outputReference,
  error: r.error,
}));
