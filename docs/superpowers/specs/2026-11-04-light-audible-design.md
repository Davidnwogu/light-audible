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

Light's builder accepts a `--tool-path`, so the tool does not have to be a fork of `light-sdk`. For local builds, the owner's existing `light-sdk` checkout compiles our `tool/` folder in place of its own `:tool` module. The exact mechanism (symlink or Gradle `projectDir` override) is decided in the implementation plan.

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
| `setup_web` | Password-protected HTML pages for sign-in, pairing code, position-sync toggle, and status. | `audible_client`, `pairing`, `account` |
| `pairing` | Issues 6-digit codes, exchanges a code for a device token, verifies tokens, and revokes old tokens. | DB |
| `library` | Syncs owned books and chapters from Audible into the DB and serves the list with an ETag. | `audible_client`, DB |
| `converter` | Background worker: license → download AAXC to temp dir → `ffmpeg` decrypt/remux → upload to bucket. | `audible_client`, `storage`, DB |
| `storage` | S3 upload, presigned GET URLs, delete, and evicting least-recently-played books. | `boto3` |
| `positions` | Reads and writes positions, resolves conflicts, and switches to Light-only mode when Audible fails. | `audible_client`, DB |
| `api` | FastAPI routes for the phone, with bearer-token auth. | all of the above |

### 2.3 Data model (Postgres)

- `account` (single row): `encrypted_auth`, `locale`, `status` (`signed_out` \| `ok` \| `needs_sign_in`), `position_sync_enabled` (bool, default true), `consecutive_sync_failures`, `library_synced_at`.
- `books`: `asin` PK, `title`, `authors`, `narrators`, `runtime_ms`, `purchase_date`, `playable` (bool), `unplayable_reason`.
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
| `POST /api/books/{asin}/prepare` | Starts conversion if needed. Returns a `BookState`. |
| `GET /api/books/{asin}` | Returns a `BookState` without starting work. |
| `GET /api/books/{asin}/position` | `{position_ms, updated_at}` after conflict resolution. |
| `PUT /api/books/{asin}/position` `{position_ms, client_updated_at}` | Save a position. |
| `GET /api/health` | Liveness, no auth. |

`BookState` = `{status: "not_ready"|"preparing"|"ready"|"failed"|"unplayable", url?, url_expires_at?, chapters?, error?}`. `url` is a presigned bucket URL valid for 6 hours. Returning a URL updates `last_played_at`.

Setup routes (`/setup/*`) use the password session: start login, finish login (pasted redirect URL), create pairing code, toggle position sync, and view status.

### 2.5 Light tool

**`lighttool.toml`:** `permissions = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]`, `capabilities = ["detached-audio"]`, `orientation = "portrait"`.

**Libraries (all on Light's allow-list):** OkHttp, kotlinx-serialization, kotlinx-coroutines, androidx.datastore, androidx.media3 (via the SDK).

**Screens (each `LightScreen` + `LightViewModel`):**
1. **Setup**: server URL + 6-digit code, then store the token. This screen is the `@InitialScreen` router: it goes to Library when a token exists.
2. **Library**: text list with a search field at the top (filters cached list by title/author, case-insensitive, on device). Pull to refresh. Unplayable books are greyed out.
3. **Book**: title, author, narrator, length, chapter list, **Play**, **Download** / **Remove download**.
4. **Player**: title, current chapter, position/duration, play/pause, −30s/+30s, chapter list. Shows "Preparing…", "Connecting…", or "Reconnecting…" states.

**Local storage:** DataStore holds server URL, token, the library JSON cache, and an outgoing position queue. Downloads go to the tool's private files dir, named `<asin>.m4b`.

**Playback:** a detached `LightAudioPlayer` with `LightMediaCache("stream", 512 MB)`. Each item has `id = asin`. A downloaded book uses `FileSource`; otherwise it uses `UrlSource(presignedUrl)`.

**Accepted risk:** the device token is stored unencrypted in the tool's private DataStore. v1 does not use the Android Keystore, because it is unconfirmed whether Light's lint rules allow it. The token only grants access to this one server and can be revoked by re-pairing.

## 3. Data flow

1. **Sign-in:** owner opens `/setup` → enters `SETUP_PASSWORD` → server builds an Amazon login URL via `audible` external login → owner signs in (captcha/2FA work normally) → pastes the final redirect URL → server registers the device, encrypts the auth blob, stores it in `account`, and triggers library sync.
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

     Meanwhile the phone shows "Preparing…" and polls `GET /api/books/{asin}` every 5 seconds.
5. **Playback:** start at the resolved position (step 6). If a URL expires (403), fetch a fresh `BookState` and call `replaceMediaItem` with the same `id`, then seek to the saved position.
6. **Position:**
   - **When the phone reports:** on pause, every 60 seconds while playing, and when the tool closes. Reports go through the outgoing queue so offline updates are sent later.
   - **On PUT:** the server saves its copy, then writes to Audible when sync is enabled.
   - **On GET:** the server reads Audible's position and returns whichever of Audible's and its own copy is newer.
7. **Download:** phone takes the presigned URL from `prepare`, downloads it with OkHttp to `<asin>.m4b.part`, then renames it to `<asin>.m4b` on success.

## 4. Error handling

| Situation | Behavior |
|---|---|
| Audible access token expires | `audible` refreshes automatically, and the server saves the updated auth blob. |
| Refresh fails or device deregistered | `account.status = needs_sign_in`. The API returns `409 needs_sign_in`, and the phone shows "Sign in again on your setup page" while keeping the cached library browsable. |
| Server asleep | Phone requests use a 30-second timeout and one retry, showing "Connecting…". |
| Conversion error (network) | Retried automatically up to 2 more times (3 attempts total). The final failure becomes `failed` with `error`. The phone shows "Couldn't prepare this book" with **Try again**, which re-queues it. |
| License refused / no AAXC available (podcasts, newer protected formats) | Book marked `playable = false` with a reason. It is greyed out in the library and not retried automatically. |
| Server restarts mid-job | On startup, `preparing` rows with `started_at` older than 30 minutes go back in the queue, and the temp dir is wiped. |
| Stream error: expired URL | Refresh the URL and resume at the same position. |
| Stream error: other source error | "Reconnecting…", retry with backoff (2s, 4s, 8s … max 60s). Cached bytes keep playing in the meantime. |
| Audible position write fails | Server copy is still saved. After 5 consecutive failures, `position_sync_enabled = false`, and the setup page shows the reason with a re-enable toggle. |
| Phone offline | Cached library is shown, downloaded books play, and positions are queued. |
| Pairing abuse | A code is invalid after 5 wrong tries. Tokens are stored only as hashes. |
| Revoked/invalid token | Phone clears its token and returns to Setup. |
| Bucket over `BUCKET_SOFT_LIMIT_BYTES` | After each upload, delete the least-recently-played `ready` books until under the limit. They return to `not_ready`, and the next play converts again. |

## 5. Testing

Development is test-first, one feature at a time, server before tool.

**Server (`pytest`)**
- `docker compose` provides Postgres and MinIO for tests. A `FakeAudibleClient` stands in for Audible.
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
- **Light SDK churn:** the SDK is early and changing quickly. Pin an SDK version and update deliberately.
- **Request time limits:** Railway reportedly caps HTTP requests at about 15 minutes (unverified). This doesn't matter because conversion runs in the background and the phone polls.
