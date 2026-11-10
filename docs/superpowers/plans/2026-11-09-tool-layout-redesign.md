# Tool Layout Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the tool's screens as a Library with a pinned now-playing bar, a podcast-tool-style Player, and a Chapters screen. Remove the Book screen, add playback speed, and change skips to 15 s.

**Architecture:** View models stay JVM-testable behind the existing seams (`ServerApi`, `ToolStore`, `Playback`, `PlaybackControls`). The session keeps the last opened book so the Library bar can resume it after the player is released. A shared in-memory `ChapterCache` feeds the Library bar, Player and Chapters screens. A `SpeedSetting` persists the playback rate, and the session applies it to every player handle. Screens are rebuilt from SDK UI kit parts: `LightTopBar`, `LightBottomBar`, `LightTouchableProgressBar` and `LightIcons`.

**Tech Stack:** As in `docs/superpowers/plans/2026-11-06-light-audible-tool.md`: Kotlin 2.3.20, Compose, Light SDK `v0.2.3`, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-09-tool-layout-redesign-design.md`. It builds on `docs/superpowers/specs/2026-11-04-light-audible-design.md` §2.5, which still governs storage, playback ownership and the SDK version.

## Global Constraints

- Every constraint in the first tool plan's Global Constraints still applies:
  - SDK `v0.2.3`, worktree build.
  - Run Gradle through `tool/gradlew-sdk ... -DlightSdk.allowAltServerPackage=true`.
  - No banned imports or reflection.
  - No new dependencies.
  - Commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Tests: `tool/gradlew-sdk :tool:testDebugUnitTest -DlightSdk.allowAltServerPackage=true`. Build and banned-API checks: `tool/gradlew-sdk :tool:assembleDebug -DlightSdk.allowAltServerPackage=true`.
- Skips are ±15 s: `skip(-15_000)` / `skip(15_000)`. Icons are `LightIcons.SKIP_BACKWARD_FIFTEEN`, `PLAY`/`PAUSE` and `SKIP_FORWARD_FIFTEEN`.
- Speed rates cycle `1f → 1.25f → 1.5f → 2f → 1f`. Labels are `"1x"`, `"1.25x"`, `"1.5x"`, `"2x"`. The rate is saved under DataStore key `playback_speed`.
- `LightBottomBar` holds at most 3 items when any item is text. The Player's bottom bar is exactly: chapters (`LightIcons.LIST`), download, speed (text).
- Download control states:
  - `LightIcons.DOWNLOAD_ARROW` when not downloaded
  - text `"34%"` (whole percent) while downloading
  - `LightIcons.DOWNLOADED_ARROW` when downloaded.
- User-facing copy, exact:
  - "Audiobooks", "Chapters"
  - "Preparing…", "Connecting…", "Reconnecting…"
  - "Couldn't prepare this book", "Couldn't play this book", "Try again", "This book can't be played"
  - "Sign in again on your setup page", "Can't reach the server."
  - "Remove download?", "Remove", "Cancel"
- Player header is centred. The chapter line has `maxLines = 2` with ellipsis. There is no narrator anywhere on the Player.
- Library list is left-aligned, newest purchase first (server order). Authors are shown via `formatAuthors`.
- Bluetooth is out of scope (SDK v0.2.3 gives tools no way to reach it).

## Review Focus

1. **Opening another book while one plays** must not pause, reload or record anything for the playing book until ▶ is pressed. → Task 5 `previewOfAnotherBookMakesNoSessionCall`.
2. **Play from the Library after the session released a paused player** must resume the same book at its saved position, not do nothing. → Task 3 `togglePlayAfterReleaseReopensLastBook`.
3. **Speed** must hold across a new player handle (reopen after release, new book, factory change), not only the handle it was set on. → Task 2 `speedReappliedToEveryNewHandle`.
4. **Long or credited author lists** ("Marcus Tullius Cicero, Philip Freeman - Introduction, Philip Freeman - Translator") must render as one short line. → Task 4 `formatAuthorsDropsCreditsAndCaps`.
5. **Tapping a chapter on a book that isn't loaded** starts that book at the chapter, not at its resume point. → Task 6 `chapterTapOnUnloadedBookOpensAtChapter`.

---

## File Structure

```
tool/src/main/kotlin/com/davidnwogu/lightaudible/
├── playback/Playback.kt            # + setSpeed(rate)
├── playback/LightPlayback.kt       # setSpeed → LightAudioPlayer.speed
├── playback/SpeedSetting.kt        # NEW: persisted rate + cycle()
├── playback/PlaybackControls.kt    # togglePlay(factory), open(..., startAtMs), lastBook
├── playback/PlaybackSession.kt     # remembers last book; applies speed; open at a position
├── sync/ChapterCache.kt            # NEW: in-memory chapters per asin, fetched via ServerApi.book
├── store/ToolStore.kt + DataStoreToolStore.kt   # + playbackSpeed()/savePlaybackSpeed()
├── AppGraph.kt                     # + speed, chapterCache
├── ui/Format.kt                    # + formatAuthors, speedLabel
├── ui/LibraryViewModel.kt, LibraryScreen.kt     # rewritten
├── ui/PlayerViewModel.kt, PlayerScreen.kt       # rewritten (absorbs Book screen)
├── ui/ChaptersViewModel.kt, ChaptersScreen.kt   # NEW
├── ui/ConfirmScreen.kt             # NEW: "Remove download?" confirm
├── ui/SetupScreen.kt               # giant heading → LightTopBar
└── ui/BookScreen.kt, BookViewModel.kt           # DELETED (and BookViewModelTest.kt)
```

---

### Task 1: ChapterCache

**Files:**
- Create: `sync/ChapterCache.kt`
- Modify: `AppGraph.kt` (add `val chapters = ChapterCache(api)`)
- Test: `sync/ChapterCacheTest.kt`

**Interfaces:**
- Consumes: `ServerApi.book(asin): ApiResult<BookState>` (`BookState.chapters: List<Chapter>?`).
- Produces: `class ChapterCache(api: ServerApi)` with:
  - `suspend fun get(asin: String): List<Chapter>`: cached if present; otherwise `api.book`. Caches only a non-empty `Ok` list. Returns `emptyList()` on any failure.
  - `fun cached(asin: String): List<Chapter>?`
  - `val updates: StateFlow<Map<String, List<Chapter>>>`

- [ ] **Step 1: Failing tests.**
  - `secondGetUsesCache`: two `get("A")` calls give one `book` call.
  - `failureIsNotCached`: `Failed(null)` then `Ok(chapters)` gives an empty list, then the chapters.
  - `emptyListIsNotCached`.
  - `updatesEmitsOnFetch`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. Guard the map with a `Mutex`. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(tool): shared chapter cache`.

### Task 2: Playback speed

**Files:**
- Create: `playback/SpeedSetting.kt`
- Modify:
  - `playback/Playback.kt` (+ `fun setSpeed(rate: Float)`)
  - `playback/LightPlayback.kt` (`player.speed = rate`)
  - `store/ToolStore.kt`, `store/DataStoreToolStore.kt` (+ `suspend fun playbackSpeed(): Float` defaulting to `1f`, + `suspend fun savePlaybackSpeed(rate: Float)`, key `playback_speed`)
  - `playback/PlaybackSession.kt` (constructor gains `speed: StateFlow<Float>`; apply on every handle open and on every change)
  - `AppGraph.kt`
  - test fakes (`FakePlayback` records `speed`; `InMemoryToolStore`)
- Test: `playback/SpeedSettingTest.kt`, additions to `playback/PlaybackSessionTest.kt` and `store/DataStoreToolStoreTest.kt`

**Interfaces:**
- Produces:
  - `class SpeedSetting(store: ToolStore, scope: CoroutineScope)` with `val rate: StateFlow<Float>` (loads the saved value at construction) and `fun cycle()` (next rate in `RATES`; saves in `scope`).
  - `SpeedSetting.RATES = listOf(1f, 1.25f, 1.5f, 2f)`. A saved value not in `RATES` resets to `1f`.
  - `PlaybackSession(positions, preparer, downloads, speed: StateFlow<Float>, scope, nowMs)`

- [ ] **Step 1: Failing tests.**
  - `cycleWalksRatesAndWraps`: 1 → 1.25 → 1.5 → 2 → 1, and the store holds the last value.
  - `loadsSavedRate`.
  - `unknownSavedRateFallsBackTo1`.
  - DataStore round-trip, with default `1f`.
  - Session tests:
    - `speedAppliedOnOpen`.
    - `speedChangeAppliesToLiveHandle`.
    - `speedReappliedToEveryNewHandle`: set 1.5 → `open(A, f1)` → `open(A, f2)`. The factory change makes a new handle, and that handle also gets 1.5. Task 3 adds the release-then-`togglePlay` variant.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS. `assembleDebug` passes.
- [ ] **Step 5:** Commit `feat(tool): playback speed, saved and applied to every handle`.

### Task 3: Session remembers the last book; open at a position

**Files:**
- Modify: `playback/PlaybackControls.kt`, `playback/PlaybackSession.kt`
- Modify: `ui/PlayerViewModel.kt` call sites, only enough to compile (Task 5 rewrites it)
- Test: `playback/PlaybackSessionTest.kt`

**Interfaces:**
- Produces, as changes to `PlaybackControls`:
  - `val lastBook: StateFlow<LibraryBook?>`: set by `open`. It survives `release()` and changes only when another book is opened.
  - `fun open(book: LibraryBook, factory: PlaybackFactory, startAtMs: Long? = null)`: with `startAtMs`, the book loads there instead of at `resumePoint`. This applies even when the same book is live: seek there and play.
  - `fun togglePlay(factory: PlaybackFactory)` replaces `togglePlay()`:
    - with a handle: as today
    - with no handle and `lastBook != null`: `open(lastBook, factory)`, which resumes at the saved position and plays
    - otherwise: no-op.
  - `skip`, `seekTo`, `attach`, `detach` and `retry` are unchanged.

- [ ] **Step 1: Failing tests.**
  - `lastBookSurvivesRelease`.
  - `togglePlayAfterReleaseReopensLastBook`: open A, pause, detach (released), then `togglePlay(f2)`. Expect a new handle from `f2`, A loaded at the recorded position, and playing.
  - `togglePlayWithNothingOpenedIsNoOp`.
  - `reopenedHandleGetsSavedSpeed`: speed 1.5, open A, pause, detach (released), `togglePlay(f2)`. The new handle has speed 1.5.
  - `openAtStartPositionSkipsResumePoint`: `open(A, f, startAtMs = 600_000)` loads at 600 000 and makes no `position` GET.
  - `openAtStartPositionOnLiveBookSeeks`: A is live at 40 000; `open(A, f, 600_000)` gives `seekTo(600_000)` and play, with no `setItem`.
  - Existing tests still pass (update `togglePlay()` call sites).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(tool): session keeps the last book; open at a chosen position`.

### Task 4: Library screen

**Files:**
- Modify: `ui/Format.kt` (+ `fun formatAuthors(authors: List<String>): String`)
- Rewrite: `ui/LibraryViewModel.kt`, `ui/LibraryScreen.kt`
- Test: `ui/FormatTest.kt`, `ui/LibraryViewModelTest.kt`

**Interfaces:**
- Consumes:
  - `PlaybackControls.state`, `lastBook`, `togglePlay(factory)` (Task 3)
  - `ChapterCache.get`/`updates` (Task 1)
  - `Downloads.isDownloaded`/`progress`
  - `currentChapter(chapters, positionMs)` (existing, `ui/PlayerViewModel.kt`; move it to `ui/Format.kt` or keep and import)
- Produces:
  - `fun formatAuthors(authors: List<String>): String`
    1. Drop entries containing `" - "`.
    2. Keep the first two remaining names.
    3. Append `" +N"` for the rest.
    4. Join with `", "`.
    5. If every entry was dropped, fall back to the first entry with its `" - …"` suffix removed.
  - `data class NowPlayingBar(val text: String, val showPlayPause: Boolean, val isPlaying: Boolean)`
    - `text` is `"<title> · <chapter>"`, or `"<title> · Preparing…"` / `"· Connecting…"` / `"· Reconnecting…"` by phase. Use `"<title>"` alone when the chapter is unknown.
    - `showPlayPause` is false while the phase is `Preparing` or `Connecting`.
  - `data class LibraryRow(val book: LibraryBook, val authors: String, val isLoaded: Boolean, val isDownloaded: Boolean)`
  - `LibraryUiState` gains:
    - `rows: List<LibraryRow>`, which replaces `visible`
    - `nowPlaying: NowPlayingBar?` (null when `lastBook` is null)
    - `query`, `banner` and `loading` as today.
  - `LibraryViewModel(store, api, session: PlaybackControls, chapters: ChapterCache, downloads: Downloads, flushPositions, connected, nowMs)`, plus:
    - `fun togglePlay(factory: PlaybackFactory) = session.togglePlay(factory)`
    - `fun clearQuery()`

- [ ] **Step 1: Failing tests.**
  - `formatAuthorsDropsCreditsAndCaps`:
    - `["Marcus Tullius Cicero","Philip Freeman - Introduction","Philip Freeman - Translator"]` → `"Marcus Tullius Cicero"`
    - `["A","B","C","D"]` → `"A, B +2"`
    - `["Rachel Joy Welcher","Scott Sauls - foreword"]` → `"Rachel Joy Welcher"`
    - `["X - Editor"]` → `"X"`
    - `[]` → `""`
  - `nowPlayingHiddenWithNothingOpened`.
  - `nowPlayingShowsTitleAndChapter`: session at 3 700 000 ms in A, with chapters starting at 0 and 3 600 000 (title "Two"), gives `"A · Two"`.
  - `nowPlayingShowsPreparingWithoutButton`.
  - `nowPlayingSurvivesRelease`: phase `Idle` and `lastBook` set, so the bar is shown with the play button.
  - `rowsMarkLoadedAndDownloaded`.
  - `clearQueryRestoresAllRows`.
  - Existing staleness, flush and banner tests are kept and adapted to `rows`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement the view model, then rewrite the screen per spec §2.2:
  - `LightTopBar`:
    - no query: left `LightIcons.SEARCH`, centre "Audiobooks", right `LightIcons.REFRESH`
    - query active: left `LightIcons.CLOSE` → `clearQuery()`, centre `"“<query>”"`, right `LightIcons.SEARCH` → editor.
  - Message line: a small `LightText` under the top bar, shown only when `banner != null`.
  - Rows: title (`LightTextVariant.Copy` or similar), authors (smaller), and a trailing ♪/⤓ marker. The `DOWNLOADED_ARROW` icon may replace ⤓. Unplayable rows are drawn lighter and can't be tapped.
  - Row tap → `navigateTo { PlayerScreen(it, row.book) }`.
  - The list is clipped below the top bar (`Modifier.clipToBounds()` on the scroll container).
  - `LightBottomBar(listOf(LightBarButton.Text(text) { open the Player for lastBook }, play/pause LightIcon or null))` when `nowPlaying != null`. Its factory is `LightPlaybackFactory(DefaultLightAudio(activity))`.
- [ ] **Step 4:** Run → PASS. `assembleDebug` passes.
- [ ] **Step 5:** Commit `feat(tool): library with title bar, search states and now-playing bar`.

### Task 5: Player screen (absorbs the Book screen)

**Files:**
- Rewrite: `ui/PlayerViewModel.kt`, `ui/PlayerScreen.kt`
- Create: `ui/ConfirmScreen.kt`
- Delete: `ui/BookScreen.kt`, `ui/BookViewModel.kt`, `test/.../ui/BookViewModelTest.kt`
- Test: `ui/PlayerViewModelTest.kt`

**Interfaces:**
- Consumes:
  - `PlaybackControls` (Task 3)
  - `ChapterCache` (Task 1)
  - `SpeedSetting` (Task 2)
  - `Downloads`
  - `resumePoint: suspend (String) -> Long` (production: `positions::resumePoint`)
  - `formatAuthors`, `formatRuntime`, `formatDuration`, `currentChapter`
- Produces:
  - `enum class DownloadControl { NotDownloaded, Downloading, Downloaded }`
  - `data class PlayerUiState`:
    - `title: String`
    - `chapterLine: String?`: the current chapter or the status text
    - `showRetry: Boolean`
    - `byline: String`: `"<formatAuthors> · <formatRuntime>"`
    - `progress: Float` (0..1)
    - `position: String`
    - `isPlaying: Boolean`
    - `controlsEnabled: Boolean`: play/pause enabled
    - `skipsEnabled: Boolean`
    - `isPreview: Boolean`
    - `download: DownloadControl`
    - `downloadPercent: Int?`
    - `speedLabel: String`
  - `PlayerViewModel(book, session: PlaybackControls, chapters: ChapterCache, speed: SpeedSetting, downloads: Downloads, resumePoint: suspend (String) -> Long, factory: PlaybackFactory)`, with:
    - `fun playPause()`: preview → `session.open(book, factory)`; loaded → `session.togglePlay(factory)`
    - `fun back15()`, `fun forward15()`, `fun seekToFraction(f: Float)`, `fun retry()`, `fun cycleSpeed()`
    - `fun downloadTapped(): Boolean`: returns true when the screen should show the "Remove download?" confirm
    - `fun removeDownload()`
  - `onScreenShow` → `session.attach()` only when `session.lastBook.value?.asin == book.asin`. It never calls `open`. `onCleared` → `session.detach()` when attached.
  - `class ConfirmScreen(activity, message: String, confirmLabel: String) : SimpleLightScreen<Boolean>`: shows the message with `confirmLabel` and "Cancel" text buttons. Confirm → `goBack(true)`; Cancel → `goBack(false)`.
- **Ruling recorded here (spec §2.3.1 says controls are enabled in the not-playing-yet state):** in preview only ▶ is enabled. Skips and the progress bar do nothing until the book is loaded, because there is no audio to move through. `skipsEnabled = false` in preview.

- [ ] **Step 1: Failing tests** (`FakeControls` from the existing test, extended for Task 3's API).
  - `previewOfAnotherBookMakesNoSessionCall`: B plays in the session. A Player for A shows, then ▶ is pressed. No session calls happen before ▶ (no attach, no open, no record); after ▶ there is exactly `open(A, factory)`.
  - `previewShowsResumePositionAndChapter`: `resumePoint` returns 3 700 000 and chapters start at 0 and 3 600 000 ("Two"). Expect `position == "1:01:40"`, `chapterLine == "Two"`, `isPreview`, `!isPlaying`, `controlsEnabled`, `!skipsEnabled`.
  - `previewResumeFailureShowsStart`: resume throws or returns 0, so `"0:00"` and the first chapter.
  - `loadedBookMirrorsSession`: `lastBook == A`, so show attaches, and position, progress and `isPlaying` follow `state`.
  - Status rows from spec §2.3.1, one test each:
    - `Preparing` → "Preparing…", controls disabled
    - `Connecting` → "Connecting…"
    - `Reconnecting` → "Reconnecting…"
    - `Failed(m)` → m with `showRetry`
    - `Unplayable` → "This book can't be played", no retry
    - `NeedsSignIn` → "Sign in again on your setup page", no retry.
  - `skipsAre15s`: `back15()` → `skip(-15_000)`; `forward15()` → `skip(15_000)`.
  - `playPauseOnReleasedLoadedBookUsesToggleWithFactory`.
  - `bylineHasNoNarrator`: `"Jordan B. Peterson · 13 h 11 min"`.
  - `downloadStates`:
    - not downloaded → `NotDownloaded`
    - progress 0.34 → `Downloading` with `downloadPercent == 34`
    - file present → `Downloaded`
    - `downloadTapped()` returns false and starts a download when not downloaded; cancels while downloading; returns true when downloaded.
  - `downloadFailureShowsOnChapterLineUntilNextTap`.
  - `speedLabelFollowsSetting`: 1.25 → `"1.25x"`; `cycleSpeed()` → `"1.5x"`.
  - `seekToFractionOnLoadedBook`: 0.5 of 1 000 000 → `seekTo(500_000)`. Does nothing in preview.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement the view model, then rewrite `PlayerScreen` per spec §2.3:
  - `LightTopBar(leftButton = LightIcon(BACK) { goBack() })`.
  - Centred column:
    - title (bold)
    - chapter line (`maxLines = 2`, ellipsis); "Try again" underneath when `showRetry`
    - byline
    - `LightTouchableProgressBar(onValueChange = viewModel::seekToFraction)`
    - controls row as `LightBottomBar(listOf(SKIP_BACKWARD_FIFTEEN, PLAY/PAUSE, SKIP_FORWARD_FIFTEEN))` with `enabled` flags
    - position text.
  - Bottom `LightBottomBar(listOf(LightIcon(LIST) → ChaptersScreen, download item, Text(speedLabel) → cycleSpeed))`.
  - When `downloadTapped()` returns true: `navigateTo({ ConfirmScreen(it, "Remove download?", "Remove") }) { if (it) viewModel.removeDownload() }`.
  - Delete the Book screen files. Library rows already open the Player (Task 4).
- [ ] **Step 4:** Run → PASS. `assembleDebug` passes, with no references to `BookScreen` left.
- [ ] **Step 5:** Commit `feat(tool): podcast-style player replaces the book screen`.

### Task 6: Chapters screen and Connect title bar

**Files:**
- Create: `ui/ChaptersViewModel.kt`, `ui/ChaptersScreen.kt`
- Modify: `ui/SetupScreen.kt` (giant "Connect" heading → `LightTopBar(center = LightTopBarCenter.Text("Audiobooks"))`)
- Test: `ui/ChaptersViewModelTest.kt`

**Interfaces:**
- Consumes: `PlaybackControls` (Task 3), `ChapterCache` (Task 1), `formatDuration`, `currentChapter`.
- Produces:
  - `data class ChapterRow(val chapter: Chapter, val length: String, val isCurrent: Boolean)`
  - `ChaptersViewModel(book, session, chapters, factory, previewPositionMs: Long)` with:
    - `val rows: StateFlow<List<ChapterRow>>`
    - `fun select(chapter: Chapter)`: loaded book → `session.seekTo(chapter.startMs)`; otherwise `session.open(book, factory, startAtMs = chapter.startMs)`.
  - `ChaptersScreen(activity, book, factory, previewPositionMs)`:
    - `LightTopBar(left BACK, centre "Chapters")`
    - one line per chapter: title truncated with "…", length right-aligned; current chapter prefixed "▸ " and bold
    - opens scrolled to the current row
    - `select` then `goBack()`.

- [ ] **Step 1: Failing tests.**
  - `currentRowFollowsLoadedPosition`.
  - `currentRowUsesPreviewPositionWhenNotLoaded`.
  - `chapterTapOnLoadedBookSeeks`.
  - `chapterTapOnUnloadedBookOpensAtChapter`: B is loaded and A is shown. `select(chapter at 3 600 000)` gives `open(A, factory, 3_600_000)`, and B gets no `seekTo`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement both screens and wire the Player's ☰ button. **Step 4:** Run → PASS. `assembleDebug` passes.
- [ ] **Step 5:** Commit `feat(tool): chapters screen; title bar on connect`.

### Task 7: Emulator check of the new screens

**Files:**
- Modify: `tool/README.md` (update the end-to-end checklist for the new screens)

- [ ] **Step 1:** Run `tool/gradlew-sdk :tool:installDebug -DlightSdk.allowAltServerPackage=true`. The emulator must be started with `-dns-server 8.8.8.8,1.1.1.1`.
- [ ] **Step 2:** Use `adb exec-out screencap -p` screenshots to check each screen against the spec's mockups. Record pass/fail for each:
  - Library: normal, searching, a message, the now-playing bar
  - Player: preview, playing, preparing, an error state
  - Chapters
  - Connect.
- [ ] **Step 3:** Check these behaviours:
  - The Library's play/pause resumes after the player sat paused for over 15 minutes.
  - Speed holds after reopening.
  - Opening another book doesn't interrupt playback.
  - "Remove download?" confirms.
- [ ] **Step 4:** Fix layout defects found in the owning task's files test-first where there is logic. Commit `docs(tool): checklist for the redesigned screens`.
