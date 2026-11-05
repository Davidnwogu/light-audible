# Light Audible Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and deploy the Light Audible server. It signs in to Audible, serves the owner's library, converts books to plain `.m4b` in an S3 bucket, hands out presigned stream URLs, and syncs listening position. The Light tool is plan 2.

**Architecture:** One FastAPI app in `server/`. Every Audible call goes through `audible_gateway`, which is the only module that imports `audible`. State lives in Postgres (SQLAlchemy 2 async, Alembic) and audio in S3-compatible storage (boto3). An in-process asyncio worker converts one book at a time with `ffmpeg`. The phone API uses bearer tokens; setup pages use HTTP Basic auth.

**Tech Stack:** Python 3.12, uv, FastAPI, Jinja2, SQLAlchemy 2 + asyncpg, Alembic, pydantic-settings, cryptography (Fernet), boto3, `audible>=0.11.0`, ffmpeg, pytest + pytest-asyncio + httpx. Local services come from Homebrew Postgres 16 and MinIO. Hosting is Railway.

**Spec:** `docs/superpowers/specs/2026-11-04-light-audible-design.md`

## Global Constraints

- Python 3.12. Pin exact versions in `server/pyproject.toml` with `uv lock`, and depend on `audible>=0.11.0`.
- Only `server/light_audible/audible_gateway/` may `import audible`.
- Plain Postgres only, with nothing Railway-specific. The connection comes only from `DATABASE_URL` and the schema only from Alembic migrations.
- Storage uses only the S3 API: `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`, `S3_REGION`.
- Other environment variables: `AUTH_ENCRYPTION_KEY` (Fernet), `SETUP_PASSWORD`, `AUDIBLE_LOCALE` (default `us`), `PUBLIC_BASE_URL`, `BUCKET_SOFT_LIMIT_BYTES` (default `8_000_000_000`).
- Never log or return the Audible auth blob, a device token, or a pairing code after creation.
- Presigned URLs are valid for 6 hours (`21600` s). Object key: `books/<asin>.m4b`.
- Pairing code: 6 digits, valid 10 minutes, invalid after 5 failed tries. Tokens are `secrets.token_urlsafe(32)`, stored as SHA-256 hex.
- Conversion: 3 attempts total for network errors. A `preparing` row with `started_at` older than 30 min is re-queued at startup.
- Position sync: auto-disable after 5 consecutive Audible write failures.
- Every `/api/*` route except `/api/pair` and `/api/health` requires a bearer token. Errors: `401 {"error":"unauthorized"}`, `409 {"error":"needs_sign_in"}`.
- TDD for every task. Commit at the end of each task with the `Co-Authored-By` trailer.

## Review Focus

1. **Real Audible responses differ from fixtures** (missing `authors`, `runtime_length_min: null`, books with no chapters). Parsing must default missing fields, not crash. → Task 4 parser tests with sparse fixtures.
2. **Two phone requests to `prepare` the same book at once** must create one conversion, not two. → Task 9 test `test_prepare_twice_creates_one_job`.
3. **Pasting the wrong URL at login finish** (e.g. the sign-in page URL, or a URL with no `openid.oa2.authorization_code`) must show a readable error and keep the pending login, not return a 500. → Task 5 test `test_finish_login_bad_url`.
4. **Bucket eviction must never delete the book currently being returned** or a book whose conversion is in flight. → Task 8 test `test_evict_skips_protected`.
5. **Position PUT for a book with no `acr`** (never converted) must save the server copy and skip Audible without counting a sync failure. → Task 10 test `test_put_without_acr_skips_audible`.

---

## File Structure

```
server/
├── pyproject.toml, uv.lock, alembic.ini, Dockerfile, railway.json, README.md
├── scripts/dev-services.sh          # start Homebrew Postgres + MinIO, create test DB + bucket
├── migrations/                      # Alembic env + versions/0001_initial.py
├── light_audible/
│   ├── main.py                      # create_app(), lifespan (starts worker)
│   ├── config.py                    # Settings (pydantic-settings)
│   ├── db.py                        # engine, session factory, get_session dependency
│   ├── models.py                    # SQLAlchemy tables
│   ├── crypto.py                    # Fernet encrypt/decrypt of auth dict
│   ├── account.py                   # account row access, audible_session() context manager
│   ├── audible_gateway/
│   │   ├── __init__.py              # AudibleSession protocol, data classes, errors
│   │   ├── parse.py                 # pure JSON → dataclass parsers
│   │   ├── login.py                 # start_login / finish_login (real)
│   │   ├── session.py               # RealAudibleSession
│   │   └── fake.py                  # FakeAudibleSession, FakeLogin (used by tests only)
│   ├── pairing.py                   # codes, tokens, require_device dependency
│   ├── library.py                   # sync + queries
│   ├── storage.py                   # S3 wrapper + eviction
│   ├── converter.py                 # ffmpeg command, convert_one, Worker
│   ├── positions.py                 # resolve/save/sync logic
│   ├── api.py                       # /api routes
│   └── setup_web.py + templates/    # /setup routes and pages
└── tests/  (conftest.py + one test file per module)
```

---

### Task 1: Project skeleton, config, health endpoint, local services

**Files:**
- Create: `server/pyproject.toml`, `server/light_audible/{__init__,main,config}.py`, `server/scripts/dev-services.sh`, `server/tests/conftest.py`, `server/tests/test_health.py`, `.gitignore`

**Interfaces:**
- Produces: `Settings` (fields listed in Global Constraints, `database_url: str`, `s3_*: str`, `auth_encryption_key: str`, `setup_password: str`, `audible_locale: str = "us"`, `public_base_url: str`, `bucket_soft_limit_bytes: int = 8_000_000_000`); `get_settings() -> Settings` (cached); `create_app(settings: Settings | None = None) -> FastAPI`.
- `tests/conftest.py` provides `settings` fixture (test DB `light_audible_test`, MinIO `http://localhost:9000`, bucket `light-audible-test`, generated Fernet key, password `test-pw`) and `client` fixture (`httpx.AsyncClient` on `ASGITransport(create_app(settings))`).

- [ ] **Step 1:** Write `scripts/dev-services.sh`. It must:
  - `brew install postgresql@16 minio/stable/minio minio/stable/mc` if missing
  - `brew services start postgresql@16`
  - start MinIO on `:9000` with data in `~/.light-audible-minio`, in the background
  - `createdb light_audible_dev` / `light_audible_test` if absent
  - `mc mb` both buckets (`light-audible-dev`, `light-audible-test`)

  Run it and confirm the result with `psql -l | grep light_audible` and `mc ls local`.
- [ ] **Step 2:** `uv init --package` in `server/`, add the dependencies from Tech Stack, then `uv lock`. Verify that `uv run python -c "import audible, fastapi"` succeeds on Python 3.12. If `audible>=0.11.0` refuses 3.12, stop and report.
- [ ] **Step 3: Failing test** `test_health`: `GET /api/health` → `200`, body `{"status": "ok"}`.
- [ ] **Step 4:** Run `uv run pytest tests/test_health.py -v` → FAIL.
- [ ] **Step 5:** Implement `config.py` and `main.py` with the health route.
- [ ] **Step 6:** Run again → PASS. Commit `feat(server): skeleton, config, health`.

### Task 2: Database schema and session

**Files:**
- Create: `server/light_audible/{db,models}.py`, `server/alembic.ini`, `server/migrations/env.py`, `server/migrations/versions/0001_initial.py`, `server/tests/test_schema.py`
- Modify: `server/tests/conftest.py` (add `db` fixture: run `alembic upgrade head` once per session, `TRUNCATE … RESTART IDENTITY CASCADE` all tables before each test, yield `AsyncSession`)

**Interfaces:**
- Produces tables (spec §2.3 plus two additions):
  - `account(id int PK =1, encrypted_auth bytes null, locale text, status text default 'signed_out', position_sync_enabled bool default true, consecutive_sync_failures int default 0, library_synced_at timestamptz null, pending_login jsonb null)`
  - `books(asin text PK, title text, authors text, narrators text, runtime_ms bigint, purchase_date date null, playable bool default true, unplayable_reason text null, acr text null, chapters_fetched bool default false)`
  - `chapters(asin FK, idx int, title text, start_ms bigint, length_ms bigint, PK(asin, idx))`
  - `conversions(asin PK FK, status text, attempts int default 0, error text null, object_key text null, size_bytes bigint null, started_at timestamptz null, finished_at timestamptz null, last_played_at timestamptz null)`
  - `positions(asin PK, position_ms bigint, updated_at timestamptz)`
  - `devices(id serial PK, token_hash text unique, created_at timestamptz, revoked_at timestamptz null)`
  - `pairing_codes(id serial PK, code_hash text, expires_at timestamptz, failed_attempts int default 0)`
- Conversion queue convention: `status='preparing' AND started_at IS NULL` = queued. `status='preparing' AND started_at IS NOT NULL` = running.
- `db.make_engine(url: str) -> AsyncEngine`, `db.get_session` FastAPI dependency.

- [ ] **Step 1: Failing test** `test_tables_exist`: after the migration, `inspect` lists exactly the 7 tables. `test_account_singleton`: inserting `account(id=2)` raises (CHECK `id = 1`).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement models and the migration. `DATABASE_URL` is accepted as `postgresql://…` and rewritten to `postgresql+asyncpg://` in `db.make_engine`.
- [ ] **Step 4:** Run → PASS. Commit `feat(server): schema and db session`.

### Task 3: Encrypted auth storage and account access

**Files:**
- Create: `server/light_audible/{crypto,account}.py`, `server/tests/test_account.py`

**Interfaces:**
- `crypto.encrypt_auth(data: dict, key: str) -> bytes`, `crypto.decrypt_auth(blob: bytes, key: str) -> dict` (raises `crypto.BadKey` on `InvalidToken`).
- `account.get_account(db) -> Account` (creates the row with `locale=settings.audible_locale` if missing).
- `account.save_auth(db, settings, auth: dict) -> None` (sets `status='ok'`, clears `pending_login`).
- `account.mark_needs_sign_in(db) -> None`.
- `account.audible_session(db, settings, factory) -> AsyncContextManager[AudibleSession]`:
  - raises `NeedsSignIn` if `status != 'ok'`
  - builds the session with `factory(auth_dict)`
  - on exit, saves `session.export_auth()` if it changed
  - an `AuthExpired` raised inside sets `needs_sign_in` and re-raises `NeedsSignIn`

- [ ] **Step 1: Failing tests:**
  - `test_encrypt_roundtrip`
  - `test_decrypt_wrong_key_raises`
  - `test_blob_is_not_plaintext` (an `"access_token"` value is not in the bytes)
  - `test_session_requires_ok_status`
  - `test_session_persists_refreshed_auth` (the fake changes `access_token`; the decrypted row afterwards has the new value)
  - `test_auth_expired_marks_needs_sign_in`
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS. Commit `feat(server): encrypted auth and account access`.

### Task 4: Audible gateway (protocol, parsers, fake, real)

**Files:**
- Create: `server/light_audible/audible_gateway/{__init__,parse,login,session,fake}.py`, `server/tests/test_gateway_parse.py`, `server/tests/fixtures/audible/{library_page.json,library_sparse.json,metadata_chapters.json,license_granted.json,license_denied.json,lastpositions.json}`

**Interfaces (in `audible_gateway/__init__.py`):**
```python
@dataclass(frozen=True) class LibraryItem: asin: str; title: str; authors: list[str]; narrators: list[str]; runtime_ms: int; purchase_date: date | None
@dataclass(frozen=True) class Chapter: idx: int; title: str; start_ms: int; length_ms: int
@dataclass(frozen=True) class License: url: str; key: str; iv: str; acr: str
@dataclass(frozen=True) class RemotePosition: position_ms: int; updated_at: datetime
@dataclass(frozen=True) class PendingLogin: url: str; code_verifier: str; serial: str; locale: str
class AuthExpired(Exception); class LicenseDenied(Exception); class TransientError(Exception)  # network/5xx
class AudibleSession(Protocol):
    async def fetch_library(self) -> list[LibraryItem]
    async def fetch_chapters(self, asin: str) -> list[Chapter]
    async def request_license(self, asin: str) -> License
    async def download(self, url: str, dest: Path) -> None
    async def get_position(self, asin: str) -> RemotePosition | None
    async def set_position(self, asin: str, acr: str, position_ms: int) -> None
    def export_auth(self) -> dict
SessionFactory = Callable[[dict], AudibleSession]
```
- `login.start_login(locale: str) -> PendingLogin`: uses `audible.localization.Locale`, `audible.login.create_code_verifier`, `audible.login.build_oauth_url`.
- `login.finish_login(pending: PendingLogin, redirect_url: str) -> dict`:
  - parses `openid.oa2.authorization_code` from the URL, raising `ValueError("No authorization code in that URL")` if it is absent
  - calls `audible.register.register(...)` in a thread
  - returns `{**registered, "locale_code": pending.locale}`, which is loadable with `Authenticator.from_dict`
- `fake.FakeAudibleSession(library=…, chapters=…, licenses=…, positions=…, fail_set_position=False, download_source: Path)`: `download` copies `download_source`. `fake.FakeLogin` mirrors `start_login`/`finish_login` without network.
- Real endpoints (`session.RealAudibleSession`, built on `audible.AsyncClient(auth=Authenticator.from_dict(...))`):
  - **library:** `GET 1.0/library`, `num_results=1000`, `response_groups="contributors,product_attrs,product_desc"`, paged
  - **chapters:** `GET 1.0/content/{asin}/metadata`, `response_groups=chapter_info`, `chapter_titles_type=Flat`, `drm_type=Adrm`
  - **license:** `POST 1.0/content/{asin}/licenserequest` with the body and headers used by audible-cli `models.py` `get_license` (`supported_drm_types ["Mpeg","Adrm"]`, `quality "High"`, `consumption_type "Download"`, `response_groups "last_position_heard,pdf_url,content_reference"`)
    - `Denied` → `LicenseDenied`
    - no `offline_url` → `LicenseDenied`
    - key/iv come from `audible.aescipher.decrypt_voucher_from_licenserequest`
    - `acr` comes from `content_metadata.content_reference.acr`
  - **positions:** `GET 1.0/annotations/lastpositions?asins=<asin>` and `PUT 1.0/lastpositions/{asin}` with body `{"acr", "asin", "position_ms"}`
  - **errors:** a token refresh failure → `AuthExpired`; `httpx.TransportError`/5xx → `TransientError`

- [ ] **Step 1:** Write fixtures. Shape them on the documented Audible responses: `library_page.json` (2 items), `library_sparse.json` (an item missing `authors`, `narrators`, `runtime_length_min`, `purchase_date`), `metadata_chapters.json` (3 chapters), and the license and lastpositions fixtures.
- [ ] **Step 2: Failing tests:**
  - `test_parse_library` (`runtime_ms == runtime_length_min * 60_000`, authors list of names)
  - `test_parse_library_sparse_defaults` (`authors == []`, `runtime_ms == 0`, `purchase_date is None`)
  - `test_parse_chapters` (idx 0..2, `start_ms` from `start_offset_ms`)
  - `test_parse_chapters_missing_returns_empty`
  - `test_parse_license_granted` (url, acr; voucher decryption is not called by the parser: `parse_license(lr, voucher: dict) -> License`)
  - `test_parse_license_denied_raises`
  - `test_parse_lastposition` (`None` when status isn't `Exists`)
  - `test_extract_auth_code` / `test_extract_auth_code_missing_raises`
- [ ] **Step 3:** Run → FAIL. **Step 4:** Implement the parsers, then `login.py`, `session.py`, `fake.py` (the real session is a thin layer over the tested parsers). **Step 5:** Run → PASS.
- [ ] **Step 6:** Mark endpoints in `session.py` with comment `# verified against live account in Task 12`. Commit `feat(server): audible gateway`.

### Task 5: Setup pages (sign-in, status)

**Files:**
- Create: `server/light_audible/setup_web.py`, `server/light_audible/templates/{base,setup}.html`, `server/tests/test_setup_web.py`

**Interfaces:**
- Consumes: `account.*` (Task 3), `audible_gateway.login` or `FakeLogin` (injected via `app.state.login`), `library.sync_library` (Task 7; until then a no-op hook `app.state.after_login`).
- Routes (all HTTP Basic, user ignored, password `== SETUP_PASSWORD` via `secrets.compare_digest`):
  - `GET /setup`: status page showing account status, library count, and position-sync state with a toggle
  - `POST /setup/login/start`: stores `pending_login`, renders the Amazon URL as a link with instructions to paste the final URL
  - `POST /setup/login/finish` (form `redirect_url`): saves the auth, then runs `after_login`
- Page copy explains: "After signing in you'll see a 'Page not found' page from Amazon. Copy the whole address from your browser's address bar and paste it here."

- [ ] **Step 1: Failing tests:**
  - `test_setup_requires_password` (401 without, 401 wrong, 200 right)
  - `test_login_start_shows_amazon_url` (the response contains `https://www.amazon.com/ap/signin`, `pending_login` is stored)
  - `test_finish_login_saves_auth` (status becomes `ok`, `after_login` called once)
  - `test_finish_login_bad_url` (200 page containing "No authorization code in that URL", status still `signed_out`, `pending_login` kept)
  - `test_finish_without_start` (error message, no crash)
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. Commit `feat(server): setup sign-in pages`.

### Task 6: Pairing and device tokens

**Files:**
- Create: `server/light_audible/pairing.py`, `server/tests/test_pairing.py`
- Modify: `server/light_audible/setup_web.py` (button `POST /setup/pairing-code` showing the code once), `server/light_audible/api.py` (create; `POST /api/pair`)

**Interfaces:**
- `pairing.create_code(db) -> str` (deletes existing codes first)
- `pairing.redeem(db, code: str) -> str` (raises `PairingError`; on success revokes all existing devices and returns a new token)
- `pairing.require_device` FastAPI dependency → `Device`, or 401 `{"error":"unauthorized"}`
- `POST /api/pair {"code": str}` → `200 {"token": str}` or `400 {"error":"invalid_code"}`

- [ ] **Step 1: Failing tests:**
  - `test_pair_success_returns_token`
  - `test_wrong_code_rejected`
  - `test_code_invalid_after_5_failures` (the correct code on the 6th try → 400)
  - `test_code_expires_after_10_minutes` (freeze time)
  - `test_new_code_replaces_old`
  - `test_repair_revokes_old_token` (old token → 401 on a protected route)
  - `test_token_stored_hashed` (the raw token is not in `devices`)
  - `test_protected_route_requires_token` (use `GET /api/library` stub returning `[]`)
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. Commit `feat(server): pairing and device tokens`.

### Task 7: Library sync, library API, chapters

**Files:**
- Create: `server/light_audible/library.py`, `server/tests/test_library.py`
- Modify: `server/light_audible/api.py`, `server/light_audible/main.py` (wire `after_login = sync_library`, exception handler `NeedsSignIn` → 409)

**Interfaces:**
- `library.sync_library(db, settings, factory) -> int`: upserts books, deletes books no longer owned (and their chapters/conversions), sets `account.library_synced_at`, and returns the count. Books with `playable=false` stay false.
- `library.get_chapters(db, settings, factory, asin) -> list[Chapter]`: fetches once, stores, sets `chapters_fetched`.
- `GET /api/library`:
  - returns `[{asin, title, authors, narrators, runtime_ms, playable}]` sorted by `purchase_date` desc
  - `ETag` = `"<library_synced_at isoformat>"`
  - a matching `If-None-Match` → `304`
- `POST /api/library/refresh` → sync, then return the same body as `GET /api/library`.

- [ ] **Step 1: Failing tests:**
  - `test_sync_inserts_books`
  - `test_sync_removes_unowned`
  - `test_sync_keeps_unplayable_flag`
  - `test_library_etag_304`
  - `test_refresh_resyncs`
  - `test_library_needs_sign_in_409` (account `needs_sign_in` → refresh returns 409 `{"error":"needs_sign_in"}`; `GET /api/library` still returns the stored list)
  - `test_chapters_fetched_once` (the fake counts calls)
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. Commit `feat(server): library sync and api`.

### Task 8: Storage and eviction

**Files:**
- Create: `server/light_audible/storage.py`, `server/tests/test_storage.py`

**Interfaces:**
- `Storage(settings)` with:
  - `upload(path: Path, key: str) -> int` (returns the size; multipart via `upload_file`)
  - `presign(key: str, expires_s: int = 21600) -> str`
  - `delete(key: str) -> None`
- `storage.evict_if_needed(db, storage, limit: int, protect: set[str]) -> list[str]`:
  - only `ready` conversions not in `protect` are candidates
  - deletes in order of oldest `last_played_at` (NULLs first) until the total `size_bytes` of `ready` rows is ≤ `limit`
  - removes the evicted conversion rows and returns the evicted ASINs

- [ ] **Step 1: Failing tests (MinIO):**
  - `test_upload_and_presign_fetchable` (`httpx.get(presigned)` returns the bytes)
  - `test_delete`
  - `test_evict_oldest_first`
  - `test_evict_noop_under_limit`
  - `test_evict_skips_protected` (a protected oldest row survives; `preparing` rows are never touched)
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. Commit `feat(server): storage and eviction`.

### Task 9: Converter, worker, books API

**Files:**
- Create: `server/light_audible/converter.py`, `server/tests/test_converter.py`, `server/tests/test_books_api.py`
- Modify: `server/light_audible/api.py`, `server/light_audible/main.py` (lifespan: `recover_stuck`, then start `Worker.run_forever()` task; cancel on shutdown)

**Interfaces:**
- `converter.ffmpeg_args(src: Path, dest: Path, key: str, iv: str) -> list[str]` == `["ffmpeg", "-y", "-audible_key", key, "-audible_iv", iv, "-i", str(src), "-map", "0:a", "-map_chapters", "0", "-c", "copy", str(dest)]`.
- `converter.convert_one(db, settings, factory, storage, asin) -> None`:
  1. license
  2. store `books.acr`
  3. `download` to `tempfile.TemporaryDirectory()`
  4. run ffmpeg (non-zero exit → `ConversionError(stderr tail)`)
  5. upload `books/<asin>.m4b`
  6. row `ready`, `size_bytes`, `finished_at`
  7. `evict_if_needed(protect={asin})`
- `converter.Worker(...).run_once() -> bool`:
  - claims one queued row with `SELECT … FOR UPDATE SKIP LOCKED`, sets `started_at`, runs `convert_one`
  - **`TransientError`:** `attempts += 1`. If below 3, re-queue (`started_at = NULL`); otherwise set `failed` with `error`.
  - **`ConversionError`:** `failed` immediately.
  - **`LicenseDenied`:** set `books.playable=false` and `unplayable_reason`, and delete the conversion row.
  - Returns whether a row was processed. `run_forever()` loops, sleeping 2 s when idle.
- `converter.recover_stuck(db, now) -> int`: re-queues `preparing` rows whose `started_at` is before `now - 30 min`.
- `BookState` JSON: `{"status": "not_ready"|"preparing"|"ready"|"failed"|"unplayable", "url"?, "url_expires_at"?, "chapters"?: [{idx,title,start_ms,length_ms}], "error"?}`.
- `GET /api/books/{asin}`: state plus chapters (via `get_chapters`); no side effects except fetching chapters. Unknown asin → 404.
- `POST /api/books/{asin}/prepare`:
  - no row → insert queued and return `preparing`
  - `failed` → reset `attempts=0`, re-queue
  - `ready` → presign, set `last_played_at=now`, include `url` and `url_expires_at`
  - `unplayable` → return as is

- [ ] **Step 1: Failing tests:**
  - `test_ffmpeg_args_exact`
  - `test_convert_one_real_ffmpeg`: generate a 3-second fixture with chapters in a tmp dir via `ffmpeg -f lavfi -i sine=duration=3 -i chapters.txt -map_metadata 1 -c:a aac fixture.m4a`, have the fake download it, run with a dummy 32-hex key/iv, and assert the object exists in MinIO and `ffprobe` shows 1+ chapters. If ffmpeg rejects the audible options on unencrypted input, report it instead of changing `ffmpeg_args`.
  - `test_transient_error_retries_then_fails` (attempts 1, 2 re-queued; 3rd → `failed`)
  - `test_license_denied_marks_unplayable`
  - `test_recover_stuck`
  - `test_prepare_twice_creates_one_job` (two concurrent `prepare` calls → one `conversions` row)
  - `test_prepare_ready_returns_presigned_url_and_updates_last_played`
  - `test_prepare_failed_requeues`
  - `test_get_book_includes_chapters`
  - `test_unknown_book_404`
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. Commit `feat(server): converter, worker, books api`.

### Task 10: Positions

**Files:**
- Create: `server/light_audible/positions.py`, `server/tests/test_positions.py`
- Modify: `server/light_audible/api.py`, `server/light_audible/setup_web.py` (`POST /setup/position-sync` toggles `position_sync_enabled` and resets failures; the status page shows "Audible position sync turned off after repeated failures" when it was disabled automatically)

**Interfaces:**
- `positions.resolve(db, settings, factory, asin) -> tuple[int, datetime]`:
  - uses the server copy, plus the Audible copy when sync is enabled
  - the newer `updated_at` wins
  - an Audible error falls back to the server copy without counting as a failure
  - no copy at all → `(0, epoch)`
- `positions.save(db, settings, factory, asin, position_ms, client_updated_at) -> None`:
  - upserts the server copy
  - if sync is enabled and `books.acr` is set, calls `set_position`
    - **success:** resets `consecutive_sync_failures`
    - **any exception:** `+1`, and at 5 sets `position_sync_enabled=false`
- `GET /api/books/{asin}/position` → `{"position_ms": int, "updated_at": iso}`; `PUT` body `{"position_ms": int, "client_updated_at": iso}` → `204`.

- [ ] **Step 1: Failing tests:**
  - `test_newer_audible_wins`
  - `test_newer_server_wins`
  - `test_audible_read_error_falls_back`
  - `test_put_saves_and_syncs`
  - `test_five_failures_disable_sync`
  - `test_success_resets_failure_count`
  - `test_put_without_acr_skips_audible` (no fake call, failure count unchanged)
  - `test_sync_disabled_skips_audible`
  - `test_setup_toggle_reenables`
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. Commit `feat(server): position sync`.

### Task 11: Container and Railway deploy config

**Files:**
- Create: `server/Dockerfile`, `server/railway.json`, `server/README.md`

**Interfaces:**
- **Dockerfile:**
  - base `python:3.12-slim`
  - `apt-get install -y --no-install-recommends ffmpeg`
  - `uv sync --frozen --no-dev`
  - `CMD` runs `alembic upgrade head && uvicorn light_audible.main:app --host 0.0.0.0 --port ${PORT:-8000}`
- **`railway.json`:** Dockerfile builder, healthcheck path `/api/health`.
- **README sections:**
  - Local dev (`scripts/dev-services.sh`, `.env.example` values, `uv run pytest`)
  - Deploy to Railway (create project → add Postgres → add Bucket → set env vars from bucket/DB references → generate a Fernet key with `uv run python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"` → `railway up` from `server/`)
  - Moving the DB to Neon (`pg_dump`/`pg_restore`, change `DATABASE_URL`, do it before trial volumes are deleted)

- [ ] **Step 1:** Write the files. **Step 2:** Run the full suite (`uv run pytest -q`) → all pass. Commit `chore(server): docker and railway config`.

### Task 12: Deploy and verify with the real account (manual, with the owner)

Run this task with the owner present. It needs their Railway login, their Audible sign-in, and their approval to deploy.

- [ ] **Step 1:** Owner creates the Railway project (trial), Postgres and Bucket, and sets env vars. Run `railway up` from `server/`. Check `curl https://<app>/api/health` → `{"status":"ok"}`.
- [ ] **Step 2:** Owner signs in at `/setup`. Confirm that the status shows `ok` and the library count matches the owner's Audible library.
- [ ] **Step 3:** Create a pairing code. Run `curl -X POST /api/pair` and keep the token in a shell variable only.
- [ ] **Step 4:** Prepare a short book the owner picks, polling `GET /api/books/{asin}` until `ready`. Confirm that the presigned URL plays in a desktop player (`ffplay` or VLC) with chapters, and record the time to ready.
- [ ] **Step 5:** `PUT` a position, then confirm it appears in the Audible app. Move the position in the app, then confirm `GET …/position` returns it.
- [ ] **Step 6:** Fix any real-API differences found in Steps 2–5 in `audible_gateway/parse.py` or `session.py`, adding a fixture from the real (redacted) response and a test for each. Commit `fix(server): align gateway with live Audible responses`.
