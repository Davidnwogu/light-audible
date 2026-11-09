# Light Audible Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Light Phone III tool in `tool/`: pair with the live server, browse and search the library, stream or download a book, keep playing after the tool closes, and sync the listening position.

**Architecture:** A Kotlin/Compose tool on Light SDK `v0.2.3`. It is built from a dedicated `light-sdk` git worktree detached at that tag, whose `:tool` project points at this repo's `tool/` folder. Everything Android-facing sits behind small interfaces, so view models and the playback logic are JVM-unit-tested against fakes:
- `ServerApi` wraps HTTP
- `ToolStore` wraps DataStore
- `Playback` wraps `LightAudioPlayer`

A process-wide `AppGraph` owns the singletons, including the `PlaybackSession` that holds the one detached player handle and reports positions.

**Tech Stack:** Kotlin 2.3.20, Compose (SDK BOM), Light SDK `v0.2.3` (`:sdk:client`, `:sdk:ui`), AGP 8.12.3, JVM 17, minSdk 34. Libraries: OkHttp 4.12.0, kotlinx-serialization-json 1.8.1, kotlinx-coroutines 1.10.2, androidx.datastore-preferences 1.2.1, media3 1.10.1 (through the SDK). Tests use kotlin-test-junit, kotlinx-coroutines-test 1.10.2, and OkHttp mockwebserver 4.12.0.

**Spec:** `docs/superpowers/specs/2026-11-04-light-audible-design.md`. Read §2.4 (phone API, including the "Verified against the live deployment", "Error bodies" and "Clock skew" notes) and §2.5 (tool) first.

## Global Constraints

- SDK pinned to tag `v0.2.3`. Never build against `light-sdk` `main`, and never modify the owner's checkout at `/Users/davidnwogu/workspace/light-sdk`, which has uncommitted work. Builds run in the worktree `/Users/davidnwogu/workspace/light-sdk-v0.2.3`.
- Gradle is always invoked through `tool/gradlew-sdk` (Task 1), which passes `-DlightSdk.toolOnly=true`.
- `lighttool.toml`:
  - `id = "com.davidnwogu.lightaudible"`, `label = "Audiobooks"`
  - `permissions = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"]`
  - `capabilities = ["detached-audio"]`
  - `orientation = "portrait"`
  - `serverPackage = "com.thelightphone.sdk.emulator"` during development, built with `-DlightSdk.allowAltServerPackage=true`
- Never add `AndroidManifest.xml`, and never set `applicationId`, `versionCode`, `versionName` or `namespace` in Gradle.
- Only dependencies on the SDK allow-list, at exact versions. Test-only dependencies are exempt.
- Source must not import `android.app.*`, `android.content.Context`/`Intent`, `androidx.activity.*`, `LocalContext`, or use reflection (`.javaClass`, `::class.java` outside `viewModelClass`, `Class.forName`). Android is reached only through `LightScreen` APIs: `dataStore`, `filesDir`, `DefaultLightAudio(activity)`, `navigateTo`, `goBack`.
- Package `com.davidnwogu.lightaudible`. Sources go in `tool/src/main/kotlin/com/davidnwogu/lightaudible/`, tests in `tool/src/test/kotlin/com/davidnwogu/lightaudible/`, shared fakes in `…/test/…/fakes/`.
- HTTP:
  - 30 s call timeout, one retry on `IOException` or HTTP `503`/`504`.
  - Classify failures by **status code first**. Read the body's `error` field only for `400 invalid_code` and `409 needs_sign_in`. Never require a JSON body on an error (issue #5).
  - Every `/api/*` call except `/api/pair` sends `Authorization: Bearer <token>`.
- Positions:
  - `client_updated_at` = device time + server clock offset taken from the `Date` header (issue #3), sent as ISO-8601 UTC with `Z`.
  - Report on pause, every 60 s while playing, and when the Player screen closes.
  - Unsent reports persist in a queue (one entry per ASIN, newest wins).
- Stream URLs come only from `POST /api/books/{asin}/prepare` with status `ready`. Poll `GET /api/books/{asin}` every 5 s while `preparing`/`not_ready`, then call `prepare` again for the URL. Refresh a URL that expires within 10 min before using it.
- Player: `LightPlayerSpec(usage = LightAudioUsage.Speech, playback = LightAudioPlayback.Detached, cache = LightMediaCache("stream", 512L * 1024 * 1024))`, item `id = asin`, ±30 s skips.
- Stream reconnect backoff 2, 4, 8, 16, 32, 60, 60… seconds. The first `Source` error on a streamed item refreshes the URL once before backing off.
- Library cache is refreshed on open when older than 24 h. Search is case-insensitive over title and authors, on device.
- Downloads: `filesDir/<asin>.m4b`, written as `<asin>.m4b.part` and renamed on success.
- User-facing copy (exact):
  - "Preparing…", "Connecting…", "Reconnecting…"
  - "Couldn't prepare this book" with a "Try again" button
  - "Sign in again on your setup page"
  - "That code didn't work. Make a new one on your setup page."
  - "Can't reach the server. Check the address."
  - "Enter the 6-digit code from your setup page."
- TDD for every task with logic. Commit at the end of each task with the `Co-Authored-By` trailer, scope `tool`.

## Review Focus

1. **Railway cold start.** The sleeping server can take more than 30 s to answer the first request. A timeout must be retried once and show "Connecting…", not an error. → Task 2 `retries once after a timeout`.
2. **Reopening the tool while detached audio plays.** Opening the same book again must reuse the live queue, not restart it at a saved position. → Task 8 `open reuses live item for same asin`.
3. **Server address pasted from a browser.** Input like `light-audible-production.up.railway.app/setup/`, a trailing slash, no scheme, or `http://` must normalize to `https://host` or be rejected with a clear message. → Task 5 `normalizeServerUrl` tests.
4. **An unsent offline position newer than the server's.** After listening offline, reopening the book must resume at the local position, not the server's older one. → Task 4 `resolve prefers newer unsent local position`.
5. **Resuming after the 6 h URL expiry** (paused overnight). The `Source` error must refresh the URL and resume at the saved position, not at 0. → Task 8 `source error refreshes url and resumes at saved position`.

---

## File Structure

```
tool/
├── build.gradle.kts, lighttool.toml, README.md
├── gradlew-sdk                         # runs ./gradlew in the SDK worktree with -DlightSdk.toolOnly=true
├── scripts/sdk-worktree.sh             # creates/validates the v0.2.3 worktree and its :tool projectDir override
└── src/
    ├── main/kotlin/com/davidnwogu/lightaudible/
    │   ├── AppGraph.kt                  # process singletons + Graph.get(screen)
    │   ├── api/Models.kt                # @Serializable wire types
    │   ├── api/ApiResult.kt
    │   ├── api/ServerApi.kt             # interface + Credentials
    │   ├── api/OkHttpServerApi.kt
    │   ├── api/ServerClock.kt
    │   ├── api/AuthState.kt
    │   ├── store/ToolStore.kt           # interface + LibraryCache, PendingPosition
    │   ├── store/DataStoreToolStore.kt
    │   ├── sync/PositionSync.kt
    │   ├── sync/Preparer.kt
    │   ├── sync/Downloads.kt
    │   ├── playback/Playback.kt         # interface + PlaybackItem, PlaybackError
    │   ├── playback/LightPlayback.kt    # adapter over LightAudioPlayer (no unit tests)
    │   ├── playback/PlaybackSession.kt
    │   └── ui/{Setup,Library,Book,Player}{Screen,ViewModel}.kt, ui/Format.kt, ui/SignedOutWatcher.kt
    └── test/kotlin/com/davidnwogu/lightaudible/  (one test file per main file with logic, fakes/)
```

---

### Task 1: Build wiring and skeleton

**Files:**
- Create: `tool/scripts/sdk-worktree.sh`, `tool/gradlew-sdk`, `tool/build.gradle.kts`, `tool/lighttool.toml`, `tool/README.md`, `tool/src/main/kotlin/com/davidnwogu/lightaudible/ui/SetupScreen.kt` (placeholder), `tool/src/test/kotlin/com/davidnwogu/lightaudible/SmokeTest.kt`
- Modify: `.gitignore` (add `tool/build/`, `tool/.gradle/`)

**Interfaces:**
- Produces: `tool/gradlew-sdk <gradle args>`, used by every later task, e.g. `tool/gradlew-sdk :tool:testDebugUnitTest`.

- [ ] **Step 1:** Write `tool/scripts/sdk-worktree.sh` (bash, `set -euo pipefail`, idempotent). It must:
  - Use `LIGHT_SDK_DIR` (default `/Users/davidnwogu/workspace/light-sdk`) and `WORKTREE` (default `/Users/davidnwogu/workspace/light-sdk-v0.2.3`).
  - If the worktree is absent: `git -C "$LIGHT_SDK_DIR" fetch --tags` then `git -C "$LIGHT_SDK_DIR" worktree add --detach "$WORKTREE" v0.2.3`.
  - Fail unless `git -C "$WORKTREE" describe --tags --exact-match` prints `v0.2.3`.
  - Append `project(":tool").projectDir = file("<abs path of this repo>/tool")` to `$WORKTREE/settings.gradle.kts` if that line is absent.

  Run it twice. The second run changes nothing (`git -C $WORKTREE diff --stat` shows one line in `settings.gradle.kts`).
- [ ] **Step 2:** Write `tool/gradlew-sdk`: `exec "$WORKTREE/gradlew" -p "$WORKTREE" -DlightSdk.toolOnly=true "$@"`, after running `sdk-worktree.sh`. `chmod +x` both scripts.
- [ ] **Step 3:** Write `tool/build.gradle.kts`, starting from `git -C $WORKTREE show v0.2.3:tool/build.gradle.kts`:
  - Keep the `plugins` block, including `ksp` (the Light SDK plugin only registers the processor that generates `LightSdkRegistry` when KSP is applied; without it the app crashes on launch), and the `android {}` block. Drop only the room `ksp(...)` dependency.
  - Change the signing `storeFile` to `rootProject.file("sdk/keys/lightsdk-dev.jks")`.
  - Drop the `UploadToolTask`.
  - Dependencies: `project(":sdk:client")`, plus `project(":sdk:ui")` only if `LightText` doesn't resolve without it; `com.squareup.okhttp3:okhttp:4.12.0`, `org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1`, `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`, `androidx.datastore:datastore-preferences:1.2.1`.
  - Test dependencies: `org.jetbrains.kotlin:kotlin-test-junit:2.3.20`, `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2`, `com.squareup.okhttp3:mockwebserver:4.12.0`.

  Write `lighttool.toml` per Global Constraints: `versionCode = 1`, `versionName = "0.1.0"`, `serverPackage = "com.thelightphone.sdk.emulator"`, and a comment saying to switch to `com.lightos` for a real phone.
- [ ] **Step 4:** Write a placeholder `@InitialScreen class SetupScreen` (a `SimpleLightScreen<Unit>` showing `LightText("Audiobooks")`) and `SmokeTest.smoke()` asserting `assertEquals(4, 2 + 2)`.
- [ ] **Step 5:** Run `tool/gradlew-sdk :tool:testDebugUnitTest` → BUILD SUCCESSFUL, 1 test. Run `tool/gradlew-sdk :tool:assembleDebug -DlightSdk.allowAltServerPackage=true` → APK in `tool/build/outputs/apk/debug/`. If the plugin rejects the `projectDir` override or `rootProject.file`, replace the override with a symlink `$WORKTREE/tool -> <repo>/tool` (removing the worktree's own `tool/`), and record which mechanism worked in `tool/README.md`.
- [ ] **Step 6:** Write `tool/README.md`: one-time setup (`tool/scripts/sdk-worktree.sh`), test and build commands, emulator install (`tool/gradlew-sdk :tool:installDebug -DlightSdk.allowAltServerPackage=true`, see the SDK's `docs/system_app` §9), and how to upgrade the SDK tag. Commit `feat(tool): build wiring against light-sdk v0.2.3`.

### Task 2: Wire models and the HTTP client

**Files:**
- Create: `api/Models.kt`, `api/ApiResult.kt`, `api/ServerApi.kt`, `api/OkHttpServerApi.kt`, `api/ServerClock.kt`, `api/AuthState.kt`
- Test: `api/ModelsTest.kt`, `api/OkHttpServerApiTest.kt`, `api/ServerClockTest.kt`, `fakes/InMemoryToolStore.kt` (used here by `AuthState`; the full `ToolStore` interface is defined in Task 3, so create `store/ToolStore.kt` here with the interface from Task 3's Interfaces block)

**Interfaces:**
- Produces:
  - `@Serializable data class LibraryBook(asin: String, title: String, authors: List<String>, narrators: List<String>, @SerialName("runtime_ms") runtimeMs: Long? = null, playable: Boolean)`
  - `@Serializable data class Chapter(idx: Int, title: String, @SerialName("start_ms") startMs: Long, @SerialName("length_ms") lengthMs: Long)`
  - `@Serializable enum class BookStatus { @SerialName("not_ready") NotReady, @SerialName("preparing") Preparing, @SerialName("ready") Ready, @SerialName("failed") Failed, @SerialName("unplayable") Unplayable }`
  - `@Serializable data class BookState(status: BookStatus, url: String? = null, @SerialName("url_expires_at") urlExpiresAt: String? = null, chapters: List<Chapter>? = null, error: String? = null)`
  - `@Serializable data class RemotePosition(@SerialName("position_ms") positionMs: Long, @SerialName("updated_at") updatedAt: String)`. Parse times with `OffsetDateTime.parse(s).toInstant()`.
  - `data class LibraryPage(books: List<LibraryBook>, etag: String?)`
  - `val ApiJson = Json { ignoreUnknownKeys = true }`
  - `sealed interface ApiResult<out T>` with `Ok<T>(value: T)`, `data object NotModified`, `data object Unauthorized`, `data object NeedsSignIn`, `data object InvalidCode`, `data object NotFound`, `data class Failed(status: Int?)` (`status == null` = network or timeout). These cases are all `ApiResult<Nothing>`.
  - `data class Credentials(baseUrl: String, token: String)`
  - `interface ServerApi`:
    - `suspend fun pair(baseUrl: String, code: String): ApiResult<String>`
    - `suspend fun library(etag: String?): ApiResult<LibraryPage>`
    - `suspend fun refreshLibrary(): ApiResult<LibraryPage>`
    - `suspend fun book(asin: String): ApiResult<BookState>`
    - `suspend fun prepare(asin: String): ApiResult<BookState>`
    - `suspend fun position(asin: String): ApiResult<RemotePosition>`
    - `suspend fun putPosition(asin: String, positionMs: Long, clientUpdatedAt: Instant): ApiResult<Unit>`
  - `class ServerClock(deviceNowMs: () -> Long, initialOffsetMs: Long, onOffsetChanged: (Long) -> Unit)` with:
    - `fun observe(serverDate: Instant, sentAtMs: Long, receivedAtMs: Long)`: offset = server date − midpoint. Update only when it differs from the current offset by more than 2000 ms.
    - `fun now(): Instant`
    - `val offsetMs: Long`
  - `class AuthState(store: ToolStore)` with `val signedOut: StateFlow<Boolean>`, `suspend fun lost()` (clears the token, sets `true`) and `fun reset()`.
  - `class OkHttpServerApi(client: OkHttpClient, credentials: suspend () -> Credentials?, clock: ServerClock, auth: AuthState) : ServerApi`. Missing credentials → `Unauthorized`. Any `401` → `auth.lost()` then `Unauthorized`.

- [ ] **Step 1: Failing tests.**
  - `ModelsTest`:
    - Decodes this live library item: `{"asin":"B0X","title":"T","authors":["A"],"narrators":[],"runtime_ms":null,"playable":true}`.
    - Decodes `{"status":"ready","url":"https://b/x","url_expires_at":"2026-11-06T18:00:00+00:00"}`.
    - Decodes `{"status":"preparing","chapters":[]}`.
    - Decodes an unknown extra field without failing.
    - Parses `"1970-01-01T00:00:00+00:00"` to `Instant.EPOCH`.
  - `OkHttpServerApiTest` (MockWebServer):
    - `library` sends `Authorization: Bearer tok` and `If-None-Match`, and returns `LibraryPage` with the response `ETag`.
    - `304` → `NotModified`.
    - `401 {"error":"unauthorized"}` → `Unauthorized`, `auth.signedOut.value == true`, and the store token is cleared.
    - `409 {"error":"needs_sign_in"}` → `NeedsSignIn`.
    - `pair` with `400 {"error":"invalid_code"}` → `InvalidCode`.
    - `404 {"detail":"Not Found"}` and a plain-text `500` → `NotFound` and `Failed(500)`.
    - `422 {"detail":[…]}` → `Failed(422)`.
    - `retries once after a timeout`: the first response is `SocketPolicy.NO_RESPONSE` and the second is `200`, with a short test timeout; the result is `Ok` and the request count is 2.
    - Two timeouts → `Failed(null)`.
    - `putPosition` body is `{"position_ms":5000,"client_updated_at":"2026-11-06T12:00:00Z"}` and `204` → `Ok(Unit)`.
  - `ServerClockTest`:
    - A server date 10 min ahead of the midpoint gives `now()` = device + 10 min.
    - A 1 s difference doesn't change the offset.
    - `onOffsetChanged` is called with the new offset.
- [ ] **Step 2:** Run `tool/gradlew-sdk :tool:testDebugUnitTest` → FAIL (unresolved references).
- [ ] **Step 3:** Implement. Every successful response with a `Date` header feeds `clock.observe(…, response.sentRequestAtMillis, response.receivedResponseAtMillis)`. Make the timeout a constructor parameter (`callTimeout`, default 30 s) so the tests can shorten it.
- [ ] **Step 4:** Run → PASS. Commit `feat(tool): server API client with status-first errors and clock offset`.

### Task 3: DataStore-backed storage

**Files:**
- Create: `store/DataStoreToolStore.kt` (`store/ToolStore.kt` already exists from Task 2)
- Test: `store/DataStoreToolStoreTest.kt`. Also finish `fakes/InMemoryToolStore.kt` against the full interface.

**Interfaces:**
- Produces:
  - `data class LibraryCache(books: List<LibraryBook>, etag: String?, fetchedAtMs: Long)`
  - `@Serializable data class PendingPosition(asin: String, positionMs: Long, atMs: Long)`. `atMs` is server-corrected epoch ms.
  - `interface ToolStore`:
    - `suspend fun credentials(): Credentials?` (null unless both URL and token are set)
    - `suspend fun serverUrl(): String?`
    - `suspend fun saveCredentials(c: Credentials)`
    - `suspend fun clearToken()` (keeps the URL)
    - `suspend fun libraryCache(): LibraryCache?`
    - `suspend fun saveLibraryCache(c: LibraryCache)`
    - `suspend fun pendingPositions(): List<PendingPosition>`
    - `suspend fun putPendingPosition(p: PendingPosition)`: replaces the entry for the same ASIN only when `p.atMs >= existing.atMs`
    - `suspend fun removePendingPosition(p: PendingPosition)`: removes only an entry equal to `p`
    - `suspend fun clockOffsetMs(): Long` (default 0)
    - `suspend fun saveClockOffsetMs(v: Long)`
  - `class DataStoreToolStore(dataStore: DataStore<Preferences>) : ToolStore`. Keys: `server_url`, `token`, `library_json`, `library_etag`, `library_fetched_at`, `pending_positions` (JSON list), `clock_offset_ms`.

- [ ] **Step 1: Failing tests.** Run on a real `PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp/"t.preferences_pb" })`:
  - `credentials` round-trip, and `clearToken` keeps `serverUrl()`.
  - The library cache round-trips with its etag and `fetchedAtMs`.
  - `putPendingPosition` coalesces per ASIN and ignores an older `atMs`.
  - `removePendingPosition` with a stale copy leaves the newer entry.
  - The clock offset defaults to 0 and round-trips.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS. Commit `feat(tool): DataStore tool storage`.

### Task 4: Position queue and resume point

**Files:**
- Create: `sync/PositionSync.kt`
- Test: `sync/PositionSyncTest.kt`, `fakes/FakeServerApi.kt` (scriptable per-method results, records calls)

**Interfaces:**
- Consumes: `ServerApi`, `ToolStore`, `ServerClock` (Task 2–3).
- Produces: `class PositionSync(api: ServerApi, store: ToolStore, clock: ServerClock)`:
  - `suspend fun record(asin: String, positionMs: Long)`: queues `PendingPosition(asin, positionMs, clock.now().toEpochMilli())`, then `flush()`.
  - `suspend fun flush(): Boolean`: sends pending entries oldest first. `Ok` or `NotFound` removes the entry. `Failed`, `Unauthorized` or `NeedsSignIn` stops the flush and leaves it queued. Returns true when the queue is empty.
  - `suspend fun resumePoint(asin: String): Long`: `flush()`, then `GET position`. Returns the newer of the server position and any still-pending local entry for that ASIN. Returns the local entry, or 0, when the GET fails.

- [ ] **Step 1: Failing tests.**
  - `record while offline keeps entry`: `putPosition` returns `Failed(null)`, so the queue size is 1.
  - `flush after reconnect sends and clears`.
  - `two records for one asin send only the newest`.
  - `404 drops entry`.
  - `resolve prefers newer unsent local position`: pending is 90 000 ms at T, the server says 10 000 ms at T−1h, and the flush fails, so the result is 90 000.
  - `resolve uses server when newer`.
  - `stamps with server-corrected time`: with a clock offset of −10 min, `putPosition` receives device time − 10 min.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS. Commit `feat(tool): offline position queue and resume point`.

### Task 5: App graph and the Setup screen

**Files:**
- Create: `AppGraph.kt`, `ui/SetupViewModel.kt`, `ui/SignedOutWatcher.kt`
- Modify: `ui/SetupScreen.kt` (replace the placeholder)
- Test: `ui/SetupViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:
  - `class AppGraph(dataStore: DataStore<Preferences>, filesDir: File, playbackFactory: PlaybackFactory)` with `val scope` (`SupervisorJob() + Dispatchers.Main.immediate`), `store`, `clock`, `auth`, `api`, `positions`, `preparer` (Task 7), `downloads` (Task 7), and `playback: PlaybackSession` (Task 8). Fields from later tasks are added by those tasks.
  - `object Graph { fun get(screen: SimpleLightScreen<*>): AppGraph }`: a lazy process singleton built from `screen.dataStore`, `screen.filesDir`, and `LightPlaybackFactory(DefaultLightAudio(activity))` (Task 8).
  - `fun normalizeServerUrl(input: String): String?`
  - `class SetupViewModel(store: ToolStore, api: ServerApi, auth: AuthState) : LightViewModel<Unit>` with `val state: StateFlow<SetupUiState>`, `fun setServerUrl(s: String)`, `fun setCode(s: String)`, and `fun submit()`.
  - `sealed interface SetupUiState { data object Checking; data class Form(serverUrl: String, code: String, error: String?, busy: Boolean); data object Paired }`
  - `@Composable fun SignedOutWatcher(auth: AuthState, onSignedOut: () -> Unit)`: every non-Setup screen calls it and navigates to `SetupScreen` when signed out.

- [ ] **Step 1: Failing tests.**
  - `normalizeServerUrl`:
    - `"light-audible-production.up.railway.app"` → `"https://light-audible-production.up.railway.app"`
    - `"https://host/"` → `"https://host"`
    - `"https://host/setup/"` → `"https://host"`
    - `"https://host/api"` → `"https://host"`
    - `" HTTPS://Host "` → `"https://host"`
    - `"http://host"` → `null` (the tool has no `cleartext-http`)
    - `""` → `null`
    - `"not a url"` → `null`
  - `SetupViewModel`:
    - Existing credentials → `Paired`, with no network call.
    - With none → `Form` prefilled with a stored `serverUrl`.
    - A code that isn't 6 digits after removing spaces → error "Enter the 6-digit code from your setup page." and no call.
    - `InvalidCode` → "That code didn't work. Make a new one on your setup page."
    - `Failed(null)` → "Can't reach the server. Check the address."
    - `Ok(token)` → credentials saved, `auth.reset()` called, state `Paired`.
    - `"12 34 56"` is accepted as `123456`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement the view model and `AppGraph`. Build `SetupScreen` as the `@InitialScreen`: it calls `navigateTo(::LibraryScreen)` on `Paired`, and uses `LightTextField` + `LightTextInputEditor` for the URL and code (see the SDK's `examples/ui-demo` `UiDemoTextInputEditorScreen` at v0.2.3). `LibraryScreen` can be a stub until Task 6.
- [ ] **Step 4:** Run → PASS, and `:tool:assembleDebug -DlightSdk.allowAltServerPackage=true` succeeds (this catches lint/banlist violations). Commit `feat(tool): setup and pairing`.

### Task 6: Library screen

**Files:**
- Create: `ui/LibraryViewModel.kt`, `ui/LibraryScreen.kt`
- Test: `ui/LibraryViewModelTest.kt`

**Interfaces:**
- Consumes: `ServerApi.library/refreshLibrary`, `ToolStore.libraryCache/saveLibraryCache`.
- Produces:
  - `fun filterBooks(books: List<LibraryBook>, query: String): List<LibraryBook>`
  - `class LibraryViewModel(store: ToolStore, api: ServerApi, nowMs: () -> Long) : LightViewModel<Unit>` with `val state: StateFlow<LibraryUiState>`, `fun onShow()`, `fun refresh()`, `fun setQuery(q: String)`
  - `data class LibraryUiState(books: List<LibraryBook>, query: String, visible: List<LibraryBook>, banner: String?, loading: Boolean)`. `banner` is one of "Connecting…", "Sign in again on your setup page", or null.

- [ ] **Step 1: Failing tests.**
  - `filterBooks`:
    - `"tolk"` matches author "J.R.R. Tolkien".
    - `"HOBBIT"` matches title "The Hobbit".
    - `"  "` returns everything, in order.
    - Narrators are not searched.
  - `LibraryViewModel`:
    - A fresh cache (under 24 h) shows its books and makes no network call.
    - A stale cache shows its books immediately, then calls `library(etag)`. `NotModified` keeps the books and bumps `fetchedAtMs`; `Ok` replaces them and saves the cache.
    - No cache and `Failed(null)` → empty list, banner null, `loading` false.
    - `NeedsSignIn` keeps the cached books with banner "Sign in again on your setup page".
    - `refresh()` calls `refreshLibrary` even when the cache is fresh.
    - `setQuery` updates `visible`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. The screen shows a search field, a "Refresh" row, then one `LightText` row per visible book (title, then authors). Unplayable rows are greyed out and not clickable; tapping a playable row does `navigateTo { BookScreen(it, book) }`. Add `SignedOutWatcher`.
- [ ] **Step 4:** Run → PASS. Commit `feat(tool): library list with search and cache`.

### Task 7: Preparer, downloads, and the Book screen

**Files:**
- Create: `sync/Preparer.kt`, `sync/Downloads.kt`, `ui/BookViewModel.kt`, `ui/BookScreen.kt`, `ui/Format.kt`
- Test: `sync/PreparerTest.kt`, `sync/DownloadsTest.kt`, `ui/BookViewModelTest.kt`, `ui/FormatTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface PrepareOutcome`: `Ready(url: String, expiresAt: Instant)`, `Failed(error: String?)`, `Unplayable(reason: String?)`, `NeedsSignIn`, `Unauthorized`, `NotFound`.
  - `class Preparer(api: ServerApi, pollIntervalMs: Long = 5_000)` with `suspend fun readyUrl(asin: String, onWaiting: (PrepareWait) -> Unit): PrepareOutcome`, where `enum class PrepareWait { Preparing, Connecting }`. A network failure while polling reports `Connecting` and keeps polling. The function never returns `Failed` for network errors, so callers cancel it to stop.
  - `class Downloads(filesDir: File, http: OkHttpClient, preparer: Preparer, scope: CoroutineScope)` with:
    - `fun file(asin: String): File`
    - `fun isDownloaded(asin: String): Boolean`
    - `val progress: StateFlow<Map<String, Float>>`
    - `fun start(asin: String)` (no-op if already running)
    - `fun cancel(asin: String)`
    - `fun remove(asin: String)` (deletes `.m4b` and `.part`)
    - `val failures: SharedFlow<Pair<String, String>>` (ASIN, message)
  - `fun formatDuration(ms: Long): String` (`"1:02:03"`, `"2:03"` under an hour) and `fun formatRuntime(ms: Long?): String` (`"11 h 5 min"`, `""` for null).
  - `class BookViewModel(book: LibraryBook, api: ServerApi, downloads: Downloads) : LightViewModel<Unit>` with `val state: StateFlow<BookUiState>`, `fun onShow()`, `fun download()`, `fun removeDownload()`.
  - `data class BookUiState(book: LibraryBook, chapters: List<Chapter>, statusLine: String?, downloaded: Boolean, downloadProgress: Float?)`

- [ ] **Step 1: Failing tests** (virtual time).
  - `Preparer`:
    - `prepare` returns `ready` with a URL → `Ready`, after one call.
    - `prepare` returns `preparing`, then `book` returns `preparing` then `ready`, then `prepare` returns `ready` with a URL → `Ready`. Total virtual time is 10 s and `onWaiting(Preparing)` was called.
    - `prepare` returns `failed` with error `"x"` → `Failed("x")`.
    - A poll `Failed(null)` reports `Connecting`, then continues.
    - `unplayable` → `Unplayable`.
  - `Downloads` (MockWebServer):
    - A successful body creates `<asin>.m4b` with the same bytes, leaves no `.part`, and `isDownloaded` is true.
    - A connection dropped midway (`SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY`) leaves neither file and emits a failure.
    - A leftover `.part` alone means `isDownloaded` is false.
    - `remove` deletes both files.
  - `BookViewModel`:
    - `onShow` loads chapters from `api.book`.
    - Status `failed` → statusLine "Couldn't prepare this book".
    - `NeedsSignIn` → statusLine "Sign in again on your setup page".
    - `downloaded` reflects `Downloads.isDownloaded`.
  - `formatDuration(3_723_000) == "1:02:03"`, `formatDuration(123_000) == "2:03"`, `formatRuntime(39_900_000) == "11 h 5 min"`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement.
  - The download URL comes from `preparer.readyUrl`. The download uses a separate `OkHttpClient` without auth or call timeout (read timeout 60 s) and streams to `.part`.
  - The Book screen shows title, authors, narrators, runtime, chapter list, **Play** (`navigateTo { PlayerScreen(it, book) }`), and **Download** / **Remove download** with a percentage while downloading.
  - Add `preparer` and `downloads` to `AppGraph`.
- [ ] **Step 4:** Run → PASS. Commit `feat(tool): book detail, preparation polling, downloads`.

### Task 8: Playback seam and PlaybackSession

**Files:**
- Create: `playback/Playback.kt`, `playback/LightPlayback.kt`, `playback/PlaybackSession.kt`
- Test: `playback/PlaybackSessionTest.kt`, `fakes/FakePlayback.kt`

**Interfaces:**
- Produces:
  - `sealed interface PlaybackSource { data class File(file: java.io.File); data class Url(url: String) }`
  - `data class PlaybackItem(asin: String, title: String, author: String?, source: PlaybackSource)`
  - `data class PlaybackError(isSource: Boolean, diagnostic: String)`
  - `interface Playback`:
    - State: `positionMs: StateFlow<Long>`, `durationMs: StateFlow<Long>`, `isPlaying: StateFlow<Boolean>`, `currentItemId: StateFlow<String?>`, `error: StateFlow<PlaybackError?>`
    - `suspend fun awaitReady(): Boolean`
    - `fun setItem(item: PlaybackItem, startMs: Long)` and `fun replaceItem(item: PlaybackItem, startMs: Long)`: both seek to `startMs` after setting
    - `fun play()`, `fun pause()`, `fun seekTo(ms: Long)`, `fun release()`
  - `fun interface PlaybackFactory { fun open(): Playback }`
  - `class LightPlaybackFactory(audio: LightAudio) : PlaybackFactory`, building `LightPlayback` over `audio.newPlayer(<spec from Global Constraints>)`:
    - `currentItemId` = `queue.value.getOrNull(currentMediaItemIndex.value)?.id`
    - `LightAudioItem(id = asin, source = …, metadata = LightMediaMetadata(title, artist = author), usage = LightAudioUsage.Speech)`
    - `isSource = error.kind == LightAudioErrorKind.Source`
  - `class PlaybackSession(factory: PlaybackFactory, positions: PositionSync, preparer: Preparer, downloads: Downloads, scope: CoroutineScope)`:
    - `val state: StateFlow<SessionState>`
    - `fun open(book: LibraryBook)`
    - `fun attach()`, `fun detach()`
    - `fun togglePlay()`, `fun skip(deltaMs: Long)`, `fun seekTo(ms: Long)`
    - `fun retry()` (after `Failed`)
  - `data class SessionState(asin: String?, title: String?, positionMs: Long, durationMs: Long, isPlaying: Boolean, phase: Phase)`
  - `sealed interface Phase`: `Idle`, `Preparing`, `Connecting`, `Ready`, `Reconnecting`, `Failed(message: String)`, `NeedsSignIn`, `Unplayable`
  - `fun skipTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long`: clamps to `[0, durationMs]`, and returns `positionMs + deltaMs` floored at 0 when the duration is unknown (≤ 0).

- [ ] **Step 1: Failing tests** (`FakePlayback` records calls and exposes mutable flows; `FakeServerApi`, virtual time).
  - `open with nothing live`: the handle is opened, `awaitReady`, then `setItem(Url, startMs = resumePoint)`, then `play`.
  - `open reuses live item for same asin`: `currentItemId == asin` → no `setItem`, no `seekTo`, and `resumePoint` is not called.
  - `open a different book while one plays`: the old position is recorded, then `setItem` is called with the new book.
  - `downloaded book uses File source`.
  - `cached url reused until 10 min before expiry`: a second `open` 5 h 49 min after `Ready(expiresAt = +6 h)` reuses the URL, and at 5 h 51 min calls `prepare` again.
  - `reports on pause and every 60 s`: playing for 125 s records twice. Pausing records once more with the current position.
  - `detach while paused releases handle`: `release()` is called and `stop` never is. The next `open` calls `factory.open()` again.
  - `detach while playing keeps handle`, and a later pause records and releases.
  - `source error refreshes url and resumes at saved position`: position 3 600 000, a `Source` error → `prepare` → `replaceItem(Url(new), startMs = 3_600_000)` → `play`.
  - `repeated source errors back off`: delays 2 s, 4 s, 8 s … capped at 60 s, with phase `Reconnecting`. The backoff resets after `isPlaying` becomes true.
  - `preparing shows Preparing`: `NeedsSignIn` → phase `NeedsSignIn`; `Failed` → `Failed("Couldn't prepare this book")`. `retry()` runs preparation again.
  - `skipTarget`: `(5_000, 60_000, -30_000) → 0`, `(55_000, 60_000, 30_000) → 60_000`, `(5_000, 0, 30_000) → 35_000`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement `PlaybackSession`, then `LightPlayback`/`LightPlaybackFactory`, which are compile-checked only. Add `playback` to `AppGraph`.
- [ ] **Step 4:** Run → PASS, and `assembleDebug` succeeds. Commit `feat(tool): detached playback session with position reporting and URL refresh`.

### Task 9: Player screen

**Files:**
- Create: `ui/PlayerViewModel.kt`, `ui/PlayerScreen.kt`
- Test: `ui/PlayerViewModelTest.kt`

**Interfaces:**
- Consumes: `PlaybackControls` (extracted from `PlaybackSession` in this task), `Chapter`, `formatDuration`, `skipTarget`.
- Produces:
  - `fun currentChapter(chapters: List<Chapter>, positionMs: Long): Chapter?`: the last chapter with `startMs <= positionMs`. Returns null for an empty list.
  - `class PlayerViewModel(book: LibraryBook, session: PlaybackControls, api: ServerApi) : LightViewModel<Unit>`:
    - `val state: StateFlow<PlayerUiState>`
    - `onScreenShow` → `session.attach()` + `session.open(book)`, and loads chapters via `api.book` (best effort)
    - `onCleared` → `session.detach()`
    - `fun togglePlay()`, `fun back30()`, `fun forward30()`, `fun jumpTo(chapter: Chapter)`, `fun retry()`
  - `data class PlayerUiState(title: String, chapterTitle: String?, position: String, duration: String, isPlaying: Boolean, statusLine: String?, chapters: List<Chapter>, showRetry: Boolean)`

- [ ] **Step 1: Failing tests.**
  - `currentChapter`: chapters starting at 0, 60 000 and 120 000 give 59 999 → idx 0, 60 000 → idx 1, and 10 000 000 → idx 2. An empty list gives null.
  - `PlayerViewModel` with a fake session:
    - Phase `Preparing` → statusLine "Preparing…"; `Connecting` → "Connecting…"; `Reconnecting` → "Reconnecting…".
    - `Failed` → "Couldn't prepare this book" with `showRetry`.
    - `NeedsSignIn` → "Sign in again on your setup page".
    - `back30` calls `session.skip(-30_000)`.
    - `onCleared` calls `detach`.
  - To make this testable, extract a `PlaybackControls` interface from `PlaybackSession` covering the members the view model uses (`state`, `open`, `attach`, `detach`, `togglePlay`, `skip`, `seekTo`, `retry`), have `PlaybackSession` implement it, and have the view model take `PlaybackControls`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement the view model and the screen. The screen shows title, chapter, `position / duration`, −30 / play-pause / +30, the status line, Try again, and a tappable chapter list.
- [ ] **Step 4:** Run → PASS, and `assembleDebug` succeeds. Commit `feat(tool): player screen`.

### Task 10: Emulator end-to-end against Railway

**Files:**
- Modify: `tool/README.md` (add an "End-to-end check" section with the results and date)

- [ ] **Step 1:** Set up the LightOS emulator per the SDK's `docs/system_app` at `v0.2.3`. Install the tool with `tool/gradlew-sdk :tool:installDebug -DlightSdk.allowAltServerPackage=true`.
- [ ] **Step 2:** Run the spec §5 manual script against `https://light-audible-production.up.railway.app`, recording pass/fail for each item:
  1. Pair with a fresh code.
  2. Search, then play an unconverted book. "Preparing…" appears and playback starts in under ~2 min.
  3. Replay starts immediately.
  4. Seek far ahead with ±30 s and the chapter list; the jump is quick (faststart).
  5. Leave the tool; audio continues.
  6. Turn on airplane mode mid-book, then turn it off: "Reconnecting…", then playback resumes.
  7. Download a book and play it in airplane mode.
  8. Pause, then confirm the position in the Audible app within a few minutes.
  9. Re-pair from the setup page: the old token gets a 401 and the tool returns to Setup.
- [ ] **Step 3:** File a GitHub issue for each failure (label `area: tool`), fix the in-scope ones test-first in the owning task's files, and commit `docs(tool): end-to-end results`.
