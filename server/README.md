# CineVerse server

Ktor (CIO) server that lets the web app use TMDB without ever shipping a TMDB credential to the browser
(plan §5, spec §3–§4). Same-origin layout: the wasm bundle under `/`, the API under `/api`. Independent of
`shared`/`composeApp`; it keeps its own minimal DTOs.

## Run locally

```bash
export TMDB_READ_ACCESS_TOKEN=…            # from secrets.properties; never commit or log it
PUBLIC_ORIGIN=http://localhost:8080 COOKIE_SECURE=false ./gradlew :server:run
curl localhost:8080/healthz                 # ok
curl localhost:8080/api/tmdb/configuration
```

Serve a web build from the same origin with `STATIC_DIR=composeApp/build/dist/wasmJs/productionExecutable`.

Docker (build context = repository root; only the files in `/.dockerignore` are sent):

```bash
docker build -f server/Dockerfile -t cineverse-server .
docker run -p 8080:8080 -e TMDB_READ_ACCESS_TOKEN -e PUBLIC_ORIGIN=https://cineverse.example \
  -e STATIC_DIR=/app/static -v "$PWD/composeApp/build/dist/wasmJs/productionExecutable:/app/static:ro" cineverse-server
```

The image runs as a non-root user on a JRE 21; TLS is terminated in front of it (set `TRUST_PROXY=true` there).

## Configuration (environment)

| Variable | Default | Meaning |
|---|---|---|
| `TMDB_READ_ACCESS_TOKEN` | — (required; start-up fails without it) | TMDB v4 read access token |
| `PUBLIC_ORIGIN` | `http://localhost:$PORT` | Public origin of this server (no path). Used for `redirect_to`, the post-sign-in redirect and the CSRF Origin check |
| `PORT` | `8080` | Listen port |
| `COOKIE_SECURE` | `true` | `false` is accepted only when `PUBLIC_ORIGIN` is `localhost`/`127.0.0.1` |
| `SESSION_TTL` | `30d` | Absolute session lifetime (`12h`, `30d`, ISO-8601 `PT12H`) — cookie `Max-Age` and server-side expiry |
| `STATIC_DIR` | unset (API only) | Directory with the wasm bundle; unknown non-`/api` paths fall back to `index.html` |
| `CORS_ORIGIN` | unset (no CORS) | One extra browser origin (local web dev server) allowed with credentials |
| `TRUST_PROXY` | `false` | Use the last `X-Forwarded-For` hop as client IP (rate limits) behind a reverse proxy |

## Routes

| Method & path | Auth | Purpose |
|---|---|---|
| `GET /healthz` | — | liveness, `ok` |
| `GET /api/tmdb/{v3 path}` | — | allow-listed catalog read → `https://api.themoviedb.org/3/{path}` with the read token |
| `GET /api/session` | — | `AuthSession` JSON; issues the `cv_csrf` cookie if missing |
| `POST /api/auth/start` | CSRF | creates a v4 request token; `{approveUrl, attemptId}` + attempt cookie |
| `GET /api/auth/callback?attempt=` | attempt cookie | TMDB redirect target; exchange → convert → account; sets `cv_session`; 302 to `${PUBLIC_ORIGIN}/?signedIn=1` |
| `POST /api/auth/logout` | CSRF | local sign-out + cancels this browser's pending sign-ins, then best-effort TMDB revocation; returns signed-out `AuthSession` |
| `POST /api/auth/cancel[?attempt=]` | CSRF | "Not now": cancels every pending/in-flight sign-in of this browser; the current session stays; `204` |
| `GET /api/account/{movie\|tv}/{watchlist\|favorites\|rated}?page&sort_by&language` | session | v4 account list (user token) |
| `GET /api/account/states/{movie\|tv}/{id}` | session | v3 `account_states` |
| `POST /api/account/watchlist` `{"media_type","media_id","watchlist"}` | session + CSRF + binding | v3 write |
| `POST /api/account/favorite` `{"media_type","media_id","favorite"}` | session + CSRF + binding | v3 write |
| `POST /api/account/rating/{movie\|tv}/{id}` `{"value"}` (0.5–10, step 0.5) | session + CSRF + binding | v3 rate |
| `DELETE /api/account/rating/{movie\|tv}/{id}` | session + CSRF + binding | v3 unrate |

Catalog allow-list (path → extra query params; every other path, method or parameter is `404 not_allowed`):
`configuration`; `genre/{movie|tv}/list` (language); `trending/all/{day|week}` (language, page);
`movie/{popular|now_playing|upcoming|top_rated}` (language, region, page); `tv/{popular|top_rated}` (language, page);
`tv/{airing_today|on_the_air}` (language, timezone, page); `person/popular` (language, page);
`discover/movie` (language, region, page, with_genres, vote_count.gte, sort_by ∈ popularity.desc/vote_average.desc/
primary_release_date.desc/title.asc); `discover/tv` (same without region; sort_by ∈ popularity.desc/vote_average.desc/
first_air_date.desc/name.asc); `search/multi` (query, language, include_adult, page);
`movie/{id}` (language, append_to_response ⊆ credits,videos,recommendations,reviews,release_dates,keywords);
`tv/{id}` (language, append_to_response ⊆ aggregate_credits,credits,videos,recommendations,reviews,content_ratings,keywords);
`{movie|tv}/{id}/videos` (language); `{movie|tv}/{id}/keywords`; `tv/{id}/season/{n}` (language);
`person/{id}` (language, append_to_response=combined_credits). Each parameter at most once, values validated.

### Errors

Every error is `{"error": "<code>", "status_code": <TMDB status_code, when known>}` — TMDB's message text is
never relayed. TMDB codes map per spec §3.3:

| Situation | HTTP | `error` |
|---|---|---|
| not allow-listed | 404 | `not_allowed` |
| TMDB 34 / 404 | 404 | `not_found` |
| TMDB 7/10 (or bare 401) | 500 | `app_credential` (never signs out) |
| TMDB 429 | 429 + `Retry-After` | `rate_limited` |
| TMDB 5xx | 502 | `upstream_error` |
| network / timeout | 504 | `upstream_unavailable` (writes: outcome unknown — re-read state) |
| exchange 41 | 409 | `not_approved` (attempt kept; retry the callback) |
| exchange 33 | 410 | `request_token_expired` |
| exchange other 4xx | 403 | `denied` |
| attempt unknown/expired/used | 410 | `attempt_expired` |
| attempt cookie mismatch | 403 | `attempt_mismatch` |
| logout / cancel / another sign-in of this browser happened first (or the attempt expired mid-callback) | 409 | `attempt_superseded` (nothing kept, tokens revoked) |
| convert/account failed after exchange | 502 | `sign_in_failed` (nothing kept, tokens revoked) |
| no session cookie | 401 | `not_signed_in` |
| TMDB 3/14/17/37/35/36 on an account call | 401 | `session_expired` + `sessionGeneration`, `accountObjectId` of the ended session; cookie cleared only if this request ended a still-current session |
| session expired (TTL) / unknown cookie | 401 | `session_expired` (TTL: cookie cleared; unknown: cookie left alone) |
| CSRF Origin / Fetch-Metadata | 403 | `csrf_origin` |
| CSRF token | 403 | `csrf_token` |
| missing binding headers | 400 | `missing_session_binding` |
| binding mismatch | 409 | `stale_session` |
| bad body / too large / not JSON | 400 / 413 / 415 | `invalid_body` / `body_too_large` / `json_required` |
| local rate limit | 429 + `Retry-After` | (empty body) |

## For the web app (P9)

1. On start, on `visibilitychange`/focus and on any `BroadcastChannel` message: `GET /api/session` →
   `{"signedIn":true,"accountObjectId":"…","displayName":"Alice","sessionGeneration":7}` (signed out:
   `{"signedIn":false,"accountObjectId":null,"displayName":null,"sessionGeneration":0}`). This also sets `cv_csrf`.
2. Every POST/DELETE sends `X-CV-CSRF: <cv_csrf cookie value>` (read from `document.cookie`); the browser adds
   `Origin`. On `403 csrf_token`, call `/api/session` once and retry (nothing reached TMDB).
3. Sign-in: `POST /api/auth/start` → open `approveUrl` in a new tab. TMDB redirects that tab to the callback, which
   lands on `/?signedIn=1` signed in; that tab broadcasts `{sessionGeneration}` and the original tab refetches
   `/api/session`. `409 not_approved` means "not approved yet". "Not now"/close: `POST /api/auth/cancel?attempt=…`
   (or simply `POST /api/auth/logout`); both invalidate every outstanding attempt of this browser, including a
   callback already talking to TMDB, which then ends in `409 attempt_superseded` without signing in. Any
   completed sign-in also supersedes the browser's other attempts.
4. Account writes send `X-CV-Expected-Session-Generation` and `X-CV-Expected-Account` (= `accountObjectId`) from
   the `AuthSession` the screen is showing; `409 stale_session` → refetch the session and drop the action.
   `401 session_expired` → signed out ("Sign-in expired") — but if the body's `sessionGeneration` is older than
   the `AuthSession` currently shown, it is a late answer for a replaced session: refetch `/api/session` instead
   of signing out. Writes are never retried by the server.
5. Send `credentials: "include"` only when using `CORS_ORIGIN` in dev; same-origin needs nothing.

## Security model

- **Credentials stay server-side.** The read token comes from the environment only; user access token, v3
  `session_id`, account ids live in memory keyed by an opaque 256-bit id. The browser gets that id in
  `cv_session` (HttpOnly, Secure, SameSite=Lax, Path=/api, Max-Age=`SESSION_TTL`), rotated on every sign-in
  (the previous session is ended and revoked). Responses never contain tokens.
- **Fixed upstream + allow-list.** Only `https://api.themoviedb.org`, only the paths/params above; client
  `Authorization`, cookies, `session_id`, `api_key` and URLs are never forwarded (requests are rebuilt).
  Bounded timeouts (5 s connect, 10 s socket, 15 s total), no redirects followed. Only public catalog GETs get one
  retry on a transport failure; auth steps and writes are sent exactly once.
- **Cache.** In-memory LRU (1000 entries) for public catalog 200s only, keyed by path + every canonical query
  parameter; lists 10 min, details 1 h, configuration/genres process lifetime; `Cache-Control: public, max-age`
  to match. Session, auth and account responses are never cached and carry `private, no-store`.
- **Sign-in.** The attempt id travels in `redirect_to`; a separate random binding secret lives in the
  `cv_auth_attempt` cookie (HttpOnly, Path=/api/auth, 15 min). The callback must present both, so an approved
  request token can't be redeemed by another browser (login CSRF). The callback is only a hint: the token
  exchange decides. Attempts are single-use and expire after 15 min. The binding is kept per browser across
  attempts and carries an auth epoch: logout, cancel and every committed sign-in bump it, and the callback
  re-checks it (atomically with creating the session) after `/3/account` answers — a superseded attempt keeps
  nothing and revokes what it just obtained. Logout never waits on a callback's upstream calls.
- **CSRF.** Every state-changing route requires `Origin` ∈ {`PUBLIC_ORIGIN`, `CORS_ORIGIN`}, rejects
  `Sec-Fetch-Site` other than same-origin/same-site, requires `X-CV-CSRF` == `cv_csrf` cookie (double submit;
  cookie is SameSite=Strict), and writes require `application/json`. CORS is off unless `CORS_ORIGIN` is set.
- **Identity binding.** Writes carry the expected session generation + account; a tab still showing account A
  after another tab switched the cookie to B gets `409 stale_session`. The session is checked again after the
  body has arrived, immediately before the TMDB call: a logout, expiry or re-sign-in meanwhile is also
  `409 stale_session` and nothing is sent (never retargeted to the new session).
- **Rate limits** (per minute): auth routes 20 per client IP and 20 per session; writes 120 per IP and 60 per
  session. Catalog reads are protected by the cache, not throttled.
- **Headers.** `nosniff`, `X-Frame-Options: DENY`, CSP `frame-ancestors 'none'` (API: `default-src 'none'`),
  `Referrer-Policy` (`no-referrer` on the API, `strict-origin-when-cross-origin` for the app so YouTube embeds work),
  HSTS when `PUBLIC_ORIGIN` is https.
- **Logs.** One access line per request (method, path, status — no query, headers, cookies or bodies);
  failures log the exception class only (client exception messages contain upstream URLs with `session_id`).
  Ktor client and rate-limit internals stay at info even if the default level is lowered
  (`src/main/resources/simplelogger.properties`). A test captures trace-level logs to prove it.

Limits: sessions and attempts are in memory (lost on restart, one instance only); no SSR.
