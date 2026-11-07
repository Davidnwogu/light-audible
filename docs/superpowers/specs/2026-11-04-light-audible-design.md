# Light Audible — Design Spec

**Date:** 2026-11-04
**Status:** Draft for review
**Working name:** Light Audible (repo: `light-audible`)

## 1. Purpose

A Light Phone III tool that lets its owner listen to the audiobooks they own on Audible, the way the built-in podcast tool works: browse, search, play, close the tool and keep listening, resume later.

### Agreed understanding

**Stated by the owner**
- For personal use only. Not targeting Light's public Tool Library.
- A cloud-hosted server does the Audible work, built on the `audible` Python library that powers audible-cli.
- Sign-in happens from a computer browser, playing the role the Light dashboard plays for Google Calendar.
- Books stream by default; each book can optionally be downloaded to the phone.
- Listening position syncs with Audible. If that integration breaks, fall back to Light-only positions.
- Search across books the owner already owns.
- Hosting: Railway trial first, with server, Postgres, and bucket all on Railway. After the trial, use Railway's Free plan. If its $1/month credit is not enough, move the database to Neon's free plan.

**Assumptions (accepted)**
- One Audible account, one phone, no other users.
- Phone features: library list, search, book detail with chapters, player (play/pause, ±30s, chapter list, resume), per-book download. No store, wishlist, cover art, playback speed, or sleep timer in v1.
- A book is converted the first time it is played, then kept.
- Development and testing target the LightOS emulator, because current LightOS builds do not yet run community tools.

### Success criteria
The owner signs in once, opens the tool, and picks a book. The first play starts within about 2 minutes, and later plays start immediately. Playback continues after the tool is closed. The owner can resume later at the same position, including after listening in the official Audible app.

### Out of scope (v1)
Multiple accounts or users, Audible store/wishlist, cover art, playback speed, sleep timer, Tool Manager-based setup, submitting to Light's Tool Library.

## 2. Architecture

Two parts in one repository:

```
light-audible/
├── server/   Python 3.12, FastAPI, Docker image (deployed on Railway)
└── tool/     Light SDK tool (Kotlin, Compose): build.gradle.kts, lighttool.toml, src/main/**
```

Light's builder accepts a `--tool-path`, so the tool does not have to be a fork of `light-sdk`. For local builds, a dedicated git worktree of the owner's `light-sdk` checkout, detached at the release tag **`v0.2.3`**, compiles our `tool/` folder in place of its own `:tool` module through a Gradle `projectDir` override in that worktree's `settings.gradle.kts`. The owner's main `light-sdk` checkout is not touched. The SDK is pinned to the tag, not to `main`: since 2026-11-04, every SDK merge to `main` cuts a tagged release, so a tag is the unit to pin and upgrade deliberately.

### 2.1 Infrastructure

| Piece | Where | Notes |
|---|---|---|
| Server container | Railway service | Sleeps when idle, wakes on request. Needs `ffmpeg` in the image. |
| Database | Railway Postgres, later Neon if needed | Plain Postgres only, nothing Railway-specific. Connected via `DATABASE_URL`, schema managed by Alembic migrations. |
| Audio files | Railway Bucket (S3-compatible) | Accessed through the standard S3 API with a configurable endpoint, so R2, Tigris, or MinIO work unchanged. The phone streams from presigned URLs, so audio never passes through the server. |

**Migration plan:** moving the DB to Neon means running `pg_dump`/`pg_restore` and changing `DATABASE_URL`. It must happen before Railway deletes trial volumes, which is 30 days after trial credit expires.

**Server configuration (environment variables):**
`DATABASE_URL`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`, `S3_REGION`, `AUTH_ENCRYPTION_KEY` (Fernet key), `SETUP_PASSWORD`, `AUDIBLE_LOCALE` (e.g. `us`), `PUBLIC_BASE_URL`, `BUCKET_SOFT_LIMIT_BYTES` (default 8 GB).

### 2.2 Server components

Each component is a module with one job and a narrow interface.

| Module | Responsibility | Depends on |
|---|---|---|
| `audible_client` | The only code that imports `audible`. Covers external-login URL generation, finishing login and registering the device, refreshing tokens, fetching library and chapters, requesting the AAXC license (URL + key/iv), and getting/setting last position heard. Exposed as an `AudibleClient` protocol; tests use a fake. | `audible` library |
| `secrets` | Encrypts and decrypts the Audible auth blob with Fernet using `AUTH_ENCRYPTION_KEY`. | `cryptography` |
| `setup_web` | HTML pages behind an HTTP Basic auth prompt (password = `SETUP_PASSWORD`) for sign-in, pairing code, position-sync toggle, and status. | `audible_client`, `pairing`, `account` |
| `pairing` | Issues 6-digit codes, exchanges a code for a device token, verifies tokens, and revokes old tokens. | DB |
| `library` | Syncs owned books from Audible into the DB and serves the list with an ETag. Chapters are fetched per book the first time that book is opened, then stored. | `audible_client`, DB |
| `converter` | Background worker: license → download AAXC to temp dir → `ffmpeg` decrypt/remux → upload to bucket. | `audible_client`, `storage`, DB |
| `storage` | S3 upload, presigned GET URLs, delete, and evicting least-recently-played books. | `boto3` |
| `positions` | Reads and writes positions, resolves conflicts, and switches to Light-only mode when Audible fails. | `audible_client`, DB |
| `api` | FastAPI routes for the phone, with bearer-token auth. | all of the above |

### 2.3 Data model (Postgres)

- `account` (single row): `encrypted_auth`, `locale`, `status` (`signed_out` \| `ok` \| `needs_sign_in`), `position_sync_enabled` (bool, default true), `consecutive_sync_failures`, `library_synced_at`, `pending_login` (sign-in started but not finished).
- `books`: `asin` PK, `title`, `authors`, `narrators`, `runtime_ms`, `purchase_date`, `playable` (bool), `unplayable_reason`, `acr` (Audible content reference, needed for position writes; stored on first conversion), `chapters_fetched` (bool).
- `chapters`: (`asin`, `idx`) PK, `title`, `start_ms`, `length_ms`.
- `conversions`: `asin` PK, `status` (`preparing` \| `ready` \| `failed`; no row means not ready), `attempts`, `error`, `object_key`, `size_bytes`, `started_at`, `finished_at`, `last_played_at`.
- `positions`: `asin` PK, `position_ms`, `updated_at`.
- `devices`: `id`, `token_hash` (SHA-256), `created_at`, `revoked_at`.
- `pairing_codes`: `code_hash`, `expires_at`, `failed_attempts`.

### 2.4 Phone API

All `/api/*` routes except `/api/pair` require `Authorization: Bearer <token>`. They return `401` for a missing or revoked token and `409 {"error":"needs_sign_in"}` when the Audible account needs re-authentication.

| Method & path | Purpose |
|---|---|
| `POST /api/pair` `{code}` | Returns `{token}`. A code is invalid after 5 failed tries or 10 minutes. |
| `GET /api/library` | Book list with chapters omitted. Supports `If-None-Match` (ETag). |
| `POST /api/library/refresh` | Re-sync from Audible, then return the list. |
| `POST /api/books/{asin}/prepare` | Starts conversion if needed (and re-queues a `failed` one). Returns a `BookState`. This is the **only** route that returns a stream URL. |
| `GET /api/books/{asin}` | Returns a `BookState` with `chapters`, without starting work. Never returns a `url`. |
| `GET /api/books/{asin}/position` | `{position_ms, updated_at}` after conflict resolution. |
| `PUT /api/books/{asin}/position` `{position_ms, client_updated_at}` | Save a position. Returns `204`. |
| `GET /api/health` | Liveness, no auth. |

`BookState` = `{status: "not_ready"|"preparing"|"ready"|"failed"|"unplayable", url?, url_expires_at?, chapters?, error?}`.
- `url` and `url_expires_at` appear only in a `prepare` response whose status is `ready`. `url` is a presigned bucket URL valid for 6 hours. Returning a URL updates `last_played_at`.
- `chapters` (`[{idx, title, start_ms, length_ms}]`) appear only in `GET /api/books/{asin}`. They are best-effort: an Audible failure returns `[]`.
- `error` appears for `failed` (conversion error) and `unplayable` (reason).

So the phone's play flow is: `prepare` → while `preparing`, poll `GET /api/books/{asin}` → once it says `ready`, call `prepare` again to get the URL.

**Verified against the live deployment (2026-11-06):**
- Converted books are stored with the `moov` index at the front (faststart), so the presigned URL streams with HTTP range requests and seeks quickly anywhere in the book.
- `GET /api/library` items are `{asin, title, authors: [..], narrators: [..], runtime_ms, playable}`, newest purchase first. The `ETag` is a quoted timestamp; a matching `If-None-Match` returns `304` with no body.
- Position timestamps are UTC ISO-8601 (`…+00:00`). `client_updated_at` must carry an offset (`Z` is fine) or the request is rejected. With no saved position, `GET …/position` returns `{position_ms: 0, updated_at: "1970-01-01T00:00:00+00:00"}`.
- `PUT …/position` keeps whichever write has the latest `client_updated_at`; an older write is silently ignored (still `204`).

**Error bodies (issue #5 open):** `401 {"error":"unauthorized"}`, `409 {"error":"needs_sign_in"}`, `400 {"error":"invalid_code"}` (pair), `404 {"error":"not_found"}` (unknown book), `502 {"error":"audible_error"}` (library refresh) are stable. Validation failures (`422 {"detail": …}`), unknown paths (`404 {"detail": …}`) and unhandled errors (plain-text `500`) are not uniform yet. The tool therefore classifies every failure by **HTTP status first** and reads the `error` field only to tell `invalid_code` and `needs_sign_in` apart; it never requires a JSON body on an error. It does not wait for #5.

**Clock skew (issue #3 open):** if the phone's clock runs ahead, the server stores a future `updated_at` and ignores later correct writes until real time catches up; a clock running behind makes the phone's writes lose to older ones. The tool works around both instead of waiting: it records the server's clock offset from the `Date` header of every successful response and stamps `client_updated_at` with `device now + offset`. Server-side clamping (#3) remains a separate defense.

Setup routes (`/setup/*`) use HTTP Basic auth: start login, finish login (pasted redirect URL), create pairing code, toggle position sync, and view status.

### 2.5 Light tool

**SDK version:** Light SDK `v0.2.3` (`sdkVersion=0.2.3`, AGP 8.12.3, Kotlin 2.3.20, media3 1.10.1, minSdk 34, JVM 17).

**`lighttool.toml`:** `id = "com.davidnwogu.lightaudible"`, `label = "Audiobooks"`, `permissions = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]`, `capabilities = ["detached-audio"]`, `orientation = "portrait"`. `serverPackage` is `com.thelightphone.sdk.emulator` while developing against the LightOS emulator (built with `-DlightSdk.allowAltServerPackage=true`) and `com.lightos` for a real phone.

**Libraries (all on Light's v0.2.3 allow-list):** OkHttp, kotlinx-serialization, kotlinx-coroutines, androidx.datastore, androidx.media3 (via the SDK). The tool may not import `android.content.Context`, `android.app.*`, or use reflection; it reaches Android only through `LightScreen`/`SealedLightActivity` APIs (`dataStore`, `filesDir`, `DefaultLightAudio`, `LightConnectivity`).

**Testability seam:** `LightAudioPlayer` is a final class with an internal constructor, so view models depend on a small `Playback` interface implemented by an adapter around `LightAudioPlayer`, and JVM tests use a fake. The same applies to the HTTP client (`ServerApi` interface) and storage (`ToolStore` interface).

**Screens (each `LightScreen` + `LightViewModel`):**
1. **Setup**: server URL + 6-digit code, then store the token. This screen is the `@InitialScreen` router: it goes to Library when a token exists.
2. **Library**: text list with a search field at the top (filters cached list by title/author, case-insensitive, on device). A **Refresh** action at the top (the Light UI kit has no pull-to-refresh). Unplayable books are greyed out.
3. **Book**: title, author, narrator, length, chapter list, **Play**, **Download** / **Remove download**.
4. **Player**: title, current chapter, position/duration, play/pause, −30s/+30s, chapter list. Shows "Preparing…", "Connecting…", or "Reconnecting…" states.

**Local storage:** DataStore holds server URL, token, the library JSON cache, and an outgoing position queue. Downloads go to the tool's private files dir, named `<asin>.m4b`.

**Playback:** a detached `LightAudioPlayer` with `LightPlayerSpec(usage = LightAudioUsage.Speech, playback = Detached, cache = LightMediaCache("stream", 512 MB))`, one book per queue. Each item has `id = asin`, so cached bytes survive URL rotation. A downloaded book uses `FileSource`; otherwise it uses `UrlSource(presignedUrl)`. On reconnect the tool calls `awaitReady()` and reuses a live queue instead of replacing it. The tool keeps the URL's `url_expires_at` and asks `prepare` for a new URL before reusing one that expires within 10 minutes.

**Who owns the player:** only one detached handle may exist per process, and position reports must keep flowing after the Player screen closes. So a process-wide `PlaybackSession` (not the Player view model) holds the handle, reports positions (on pause and every 60 s while playing), and keeps the handle while audio plays. When playback is paused and no Player screen is attached, it reports the position and releases the handle (without `stop()`), which lets the SDK's 15-minute idle rule stop the service. The Player view model only attaches to and detaches from the session.

**Accepted risk:** the device token is stored unencrypted in the tool's private DataStore. v1 does not use the Android Keystore, because it is unconfirmed whether Light's lint rules allow it. The token only grants access to this one server and can be revoked by re-pairing.

## 3. Data flow

1. **Sign-in:** owner opens `/setup` → enters `SETUP_PASSWORD` at the browser's password prompt → server builds an Amazon login URL via `audible` external login → owner signs in (captcha/2FA work normally) → pastes the final redirect URL → server registers the device, encrypts the auth blob, stores it in `account`, and triggers library sync.
2. **Pairing:** setup page shows a 6-digit code (10 min) → phone posts it to `/api/pair` → phone stores the token. Pairing again revokes previous tokens.
3. **Library:** phone fetches `/api/library` on launch with its ETag, keeps a cached copy, and refreshes on pull-to-refresh or when the cache is older than 24h. Search runs on the cached list.
4. **Play:** phone calls `prepare`.
   - **Ready:** play the returned URL.
   - **Not ready:** server inserts a `conversions` row as `preparing`. The in-process worker claims rows one at a time (`SELECT … FOR UPDATE SKIP LOCKED`) and runs the conversion:
     1. Request the AAXC license (URL, key, iv).
     2. Stream-download the file to a temp dir.
     3. Decrypt with `ffmpeg -audible_key <key> -audible_iv <iv> -i in.aaxc -c copy -map_chapters 0 out.m4b`.
     4. Upload the result as `books/<asin>.m4b`.
     5. Mark the row `ready` and delete the temp files.

     Meanwhile the phone shows "Preparing…" and polls `GET /api/books/{asin}` every 5 seconds. When the poll says `ready`, the phone calls `prepare` once more to get the URL.
5. **Playback:** start at the resolved position (step 6). If a URL expires (a `Source` playback error on a streamed item), call `prepare` for a fresh URL and `replaceMediaItem` with the same `id`, then seek to the position saved before the error.
6. **Position:**
   - **When the phone reports:** on pause, every 60 seconds while playing, and when the tool closes. Reports go through the outgoing queue so offline updates are sent later.
   - **On PUT:** the server saves its copy, then writes to Audible when sync is enabled.
   - **On GET:** the server reads Audible's position and returns whichever of Audible's and its own copy is newer.
7. **Download:** phone takes the presigned URL from `prepare` (status `ready`), downloads it with OkHttp to `<asin>.m4b.part`, then renames it to `<asin>.m4b` on success.

## 4. Error handling

| Situation | Behavior |
|---|---|
| Audible access token expires | `audible` refreshes automatically, and the server saves the updated auth blob. |
| Refresh fails or device deregistered | `account.status = needs_sign_in`. The API returns `409 needs_sign_in`, and the phone shows "Sign in again on your setup page" while keeping the cached library browsable. |
| Server asleep | Phone requests use a 30-second timeout and one retry, showing "Connecting…". |
| Conversion error (network) | Retried automatically up to 2 more times (3 attempts total). The final failure becomes `failed` with `error`. The phone shows "Couldn't prepare this book" with **Try again**, which re-queues it. |
| License refused / no AAXC available (podcasts, newer protected formats) | Book marked `playable = false` with a reason. It is greyed out in the library and not retried automatically. |
| Server restarts mid-job | On startup, `preparing` rows with `started_at` older than 30 minutes go back in the queue, and the temp dir is wiped. |
| Stream error: expired URL | Call `prepare` for a fresh URL and resume at the same position. The phone cannot see the HTTP status through the player, so the first `Source` error on a streamed item always tries one URL refresh before backing off. |
| Stream error: other source error | "Reconnecting…", retry with backoff (2s, 4s, 8s … max 60s). Cached bytes keep playing in the meantime. |
| Audible position write fails | Server copy is still saved. After 5 consecutive failures, `position_sync_enabled = false`, and the setup page shows the reason with a re-enable toggle. |
| Phone offline | Cached library is shown, downloaded books play, and positions are queued. |
| Pairing abuse | A code is invalid after 5 wrong tries. Tokens are stored only as hashes. |
| Revoked/invalid token | Phone clears its token and returns to Setup. |
| Bucket over `BUCKET_SOFT_LIMIT_BYTES` | After each upload, delete the least-recently-played `ready` books until under the limit. They return to `not_ready`, and the next play converts again. |

## 5. Testing

Development is test-first, one feature at a time, server before tool.

**Server (`pytest`)**
- Local Homebrew Postgres 16 and MinIO (started by `server/scripts/dev-services.sh`) provide the database and bucket for tests. Docker is not required locally; Railway builds the image. A `FakeAudibleClient` stands in for Audible.
- API tests cover:
  - pairing (success, wrong code, lockout, expiry, re-pair revokes)
  - token enforcement
  - prepare → poll → ready → presigned URL
  - URL refresh
  - conversion failure, retry, and Try again
  - stuck-job recovery
  - position conflict resolution
  - auto-disable sync after 5 failures
  - bucket eviction
  - `needs_sign_in`
- Converter tests check the generated `ffmpeg` command, and do a real `ffmpeg` remux of a small unencrypted fixture to verify chapters and upload.
- Real AAXC decryption is verified manually once with a real book.

**Tool (JVM unit tests)**
- View models are tested against a fake API client:
  - search filtering
  - Preparing/polling
  - URL refresh with seek
  - offline position queue
  - revoked token → Setup
  - connect/reconnect retry timings

**Manual end-to-end (LightOS emulator against the Railway trial deployment, HTTPS)**
Sign in and pair → search and play an unconverted book (under ~2 min) → replay is instant → close the tool, audio continues → airplane mode mid-book and back → download a book and play it offline → position appears in the Audible app.

## 6. Risks

- **Unofficial Audible API:** `audible` uses Audible's private API, which can change without notice. Position sync has a defined fallback. Licensing/download failures would block new conversions but not already converted books.
- **Terms of service:** downloading and decrypting purchased books for personal use is a grey area under Audible's terms. Accepted for personal use. This is why the tool is not submitted to Light's library.
- **Railway pricing:** plans and limits change, so recheck https://railway.com/pricing before the trial ends. The database and storage are kept portable for this reason.
- **Light SDK churn:** the SDK is early and changing quickly, and now releases on every merge to `main`. The tool is pinned to tag `v0.2.3`; upgrading means moving the build worktree to a newer tag and rerunning the tests.
- **Request time limits:** Railway reportedly caps HTTP requests at about 15 minutes (unverified). This doesn't matter because conversion runs in the background and the phone polls.
