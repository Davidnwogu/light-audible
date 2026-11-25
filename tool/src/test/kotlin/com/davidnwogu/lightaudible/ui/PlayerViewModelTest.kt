package com.davidnwogu.lightaudible.ui

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.fakes.FakeControls
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.fakes.InMemoryToolStore
import com.davidnwogu.lightaudible.fakes.ScriptedInterceptor
import com.davidnwogu.lightaudible.playback.Phase
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.playback.SessionState
import com.davidnwogu.lightaudible.playback.SpeedSetting
import com.davidnwogu.lightaudible.sync.ChapterCache
import com.davidnwogu.lightaudible.sync.Downloads
import com.davidnwogu.lightaudible.sync.Preparer
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient

private fun chapter(idx: Int, startMs: Long) = Chapter(idx, "Ch $idx", startMs, 60_000)

class CurrentChapterTest {
    private val chapters = listOf(chapter(0, 0), chapter(1, 60_000), chapter(2, 120_000))

    @Test
    fun picksLastChapterStartedByPosition() {
        assertEquals(0, currentChapter(chapters, 59_999)?.idx)
        assertEquals(1, currentChapter(chapters, 60_000)?.idx)
        assertEquals(2, currentChapter(chapters, 10_000_000)?.idx)
    }

    @Test
    fun emptyListIsNull() = assertNull(currentChapter(emptyList(), 5))
}

class DownloadPercentTest {
    @Test
    fun roundsProgressToWholePercent() {
        assertEquals(34, downloadPercent(0.34f))
        assertEquals(29, downloadPercent(0.29f))
        assertEquals(0, downloadPercent(0f))
        assertEquals(100, downloadPercent(1f))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val runtime = 47_460_000L // 13 h 11 min
    private val bookA = LibraryBook("A", "Maps of Meaning", listOf("Jordan B. Peterson"), listOf("Narrator"), runtime, true)
    private val bookB = LibraryBook("B", "Dune", listOf("Frank Herbert"), emptyList(), 600_000, true)
    private val api = FakeServerApi()
    private val store = InMemoryToolStore()
    private val net = ScriptedInterceptor()
    private val dir = kotlin.io.path.createTempDirectory("player").toFile()
    private val factory = PlaybackFactory { error("unused") }
    private val chapters = listOf(Chapter(0, "One", 0, 3_600_000), Chapter(1, "Two", 3_600_000, runtime - 3_600_000))
    private var resume: suspend (String) -> Long = { 0L }
    private val resumeCalls = mutableListOf<String>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun session(
        asin: String? = "A",
        phase: Phase = Phase.Ready,
        pos: Long = 0,
        duration: Long = runtime,
        playing: Boolean = false,
        last: LibraryBook? = bookA,
    ) = FakeControls(SessionState(asin, "t", pos, duration, playing, phase)).also { it.lastBook.value = last }

    private fun TestScope.downloads() = Downloads(
        dir, OkHttpClient.Builder().addInterceptor(net).build(), Preparer(api),
        backgroundScope, StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.vm(
        s: FakeControls,
        book: LibraryBook = bookA,
        d: Downloads = downloads(),
        speed: SpeedSetting = SpeedSetting(store, backgroundScope),
        cache: ChapterCache = ChapterCache(api),
    ): PlayerViewModel =
        PlayerViewModel(book, s, cache, speed, d, { asin -> resumeCalls += asin; resume(asin) }, factory)
            .also { it.onShow(); advanceUntilIdle() }

    @Test
    fun previewOfAnotherBookMakesNoSessionCall() = runTest {
        val s = session(asin = "B", playing = true, last = bookB)
        val v = vm(s)
        assertEquals(emptyList(), s.calls)
        v.playPause()
        assertEquals(listOf("open A"), s.calls)
        assertSame(factory, s.factories.single())
        assertNull(s.openStarts.single())
    }

    @Test
    fun previewShowsResumePositionAndChapter() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        resume = { 3_700_000 }
        val st = vm(session(asin = "B", last = bookB)).state.value
        assertEquals(listOf("A"), resumeCalls)
        assertEquals("1:01:40", st.position)
        assertEquals("Two", st.chapterLine)
        assertTrue(st.isPreview)
        assertFalse(st.isPlaying)
        assertTrue(st.controlsEnabled)
        assertFalse(st.skipsEnabled)
        assertFalse(st.showRetry)
        assertEquals(3_700_000f / runtime, st.progress)
    }

    @Test
    fun previewResumeFailureShowsStart() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        resume = { throw IOException("offline") }
        val st = vm(session(asin = null, phase = Phase.Idle, last = null)).state.value
        assertEquals("0:00", st.position)
        assertEquals("One", st.chapterLine)
        assertEquals(0f, st.progress)
    }

    @Test
    fun previewResumeZeroShowsStart() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        val st = vm(session(asin = null, phase = Phase.Idle, last = null)).state.value
        assertEquals("0:00", st.position)
        assertEquals("One", st.chapterLine)
    }

    @Test
    fun loadedBookMirrorsSession() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        val s = session(pos = 65_000, duration = 1_000_000, playing = true)
        val v = vm(s)
        assertEquals(listOf("attach"), s.calls)
        assertEquals(emptyList(), resumeCalls)
        var st = v.state.value
        assertFalse(st.isPreview)
        assertTrue(st.isPlaying)
        assertEquals("1:05", st.position)
        assertEquals(0.065f, st.progress)
        assertEquals("One", st.chapterLine)
        assertTrue(st.controlsEnabled)
        assertTrue(st.skipsEnabled)
        s.state.value = s.state.value.copy(positionMs = 3_700_000, durationMs = runtime, isPlaying = false)
        advanceUntilIdle()
        st = v.state.value
        assertEquals("1:01:40", st.position)
        assertEquals("Two", st.chapterLine)
        assertFalse(st.isPlaying)
        v.onCleared()
        assertEquals("detach", s.calls.last())
    }

    @Test
    fun previewNeverAttachedDoesNotDetach() = runTest {
        val s = session(asin = "B", last = bookB)
        vm(s).onCleared()
        assertEquals(emptyList(), s.calls)
    }

    private fun TestScope.statusOf(phase: Phase): PlayerUiState = vm(session(phase = phase)).state.value

    private fun assertDisabled(st: PlayerUiState) {
        assertFalse(st.controlsEnabled)
        assertFalse(st.skipsEnabled)
    }

    @Test
    fun preparingStatus() = runTest {
        val st = statusOf(Phase.Preparing)
        assertEquals("Preparing…", st.chapterLine)
        assertDisabled(st)
        assertFalse(st.showRetry)
    }

    @Test
    fun connectingStatus() = runTest {
        val st = statusOf(Phase.Connecting)
        assertEquals("Connecting…", st.chapterLine)
        assertDisabled(st)
    }

    @Test
    fun reconnectingKeepsPauseEnabledButNotSkips() = runTest {
        val s = session(phase = Phase.Reconnecting, playing = false)
        val v = vm(s)
        val st = v.state.value
        assertEquals("Reconnecting…", st.chapterLine)
        assertTrue(st.controlsEnabled)
        assertTrue(st.isPlaying) // shown as pause: a tap stops the recovery
        assertFalse(st.skipsEnabled)
        s.calls.clear()
        v.playPause()
        v.back15()
        v.forward15()
        v.seekToFraction(0.5f)
        assertEquals(listOf("toggle"), s.calls)
    }

    @Test
    fun failedStatusShowsRetry() = runTest {
        val st = statusOf(Phase.Failed("Couldn't play this book"))
        assertEquals("Couldn't play this book", st.chapterLine)
        assertTrue(st.showRetry)
        assertDisabled(st)
    }

    @Test
    fun unplayableStatus() = runTest {
        val st = statusOf(Phase.Unplayable)
        assertEquals("This book can't be played", st.chapterLine)
        assertFalse(st.showRetry)
        assertDisabled(st)
    }

    @Test
    fun needsSignInStatus() = runTest {
        val st = statusOf(Phase.NeedsSignIn)
        assertEquals("Sign in again on your setup page", st.chapterLine)
        assertFalse(st.showRetry)
        assertDisabled(st)
    }

    @Test
    fun retryFallsBackToOpenWhenSessionCannotAct() = runTest {
        val s = session(phase = Phase.Failed("x"))
        val v = vm(s)
        s.calls.clear()
        v.retry()
        assertEquals(listOf("retry"), s.calls)
        s.retryResult = false
        s.calls.clear()
        v.retry()
        assertEquals(listOf("retry", "open A"), s.calls)
    }

    @Test
    fun skipsAre15s() = runTest {
        val s = session()
        val v = vm(s)
        s.calls.clear()
        v.back15()
        v.forward15()
        assertEquals(listOf("skip -15000", "skip 15000"), s.calls)
    }

    @Test
    fun skipsDoNothingInPreview() = runTest {
        val s = session(asin = "B", last = bookB)
        val v = vm(s)
        v.back15()
        v.forward15()
        v.seekToFraction(0.5f)
        assertEquals(emptyList(), s.calls)
    }

    @Test
    fun playPauseOnReleasedLoadedBookUsesToggleWithFactory() = runTest {
        val s = session(phase = Phase.Idle)
        val v = vm(s)
        s.calls.clear()
        v.playPause()
        assertEquals(listOf("toggle"), s.calls)
        assertSame(factory, s.factories.single())
    }

    @Test
    fun bylineHasNoNarrator() = runTest {
        assertEquals("Jordan B. Peterson · 13 h 11 min", vm(session()).state.value.byline)
    }

    @Test
    fun titleIsTheBook() = runTest {
        assertEquals("Maps of Meaning", vm(session()).state.value.title)
    }

    @Test
    fun notDownloaded() = runTest {
        val st = vm(session()).state.value
        assertEquals(DownloadControl.NotDownloaded, st.download)
        assertNull(st.downloadPercent)
    }

    @Test
    fun downloadedWhenFilePresentAndTapAsksToConfirm() = runTest {
        File(dir, "A.m4b").writeBytes(byteArrayOf(1))
        val v = vm(session())
        assertEquals(DownloadControl.Downloaded, v.state.value.download)
        assertTrue(v.downloadTapped())
        assertTrue(File(dir, "A.m4b").exists())
        v.removeDownload()
        advanceUntilIdle()
        assertFalse(File(dir, "A.m4b").exists())
        assertEquals(DownloadControl.NotDownloaded, v.state.value.download)
    }

    @Test
    fun tapStartsThenCancelsDownload() = runTest {
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Preparing)) }
        val v = vm(session())
        assertFalse(v.downloadTapped())
        advanceTimeBy(100)
        assertEquals(DownloadControl.Downloading, v.state.value.download)
        assertEquals(0, v.state.value.downloadPercent)
        assertFalse(v.downloadTapped())
        advanceTimeBy(100)
        assertEquals(DownloadControl.NotDownloaded, v.state.value.download)
        assertNull(v.state.value.downloadPercent)
    }

    @Test
    fun downloadFailureShowsOnChapterLineUntilNextTap() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Ready, "https://cdn/x", "2030-01-01T00:00:00Z")) }
        net.enqueueFailure(IOException("x"))
        val v = vm(session(asin = "B", last = bookB))
        v.downloadTapped()
        advanceUntilIdle()
        assertEquals("Download failed", v.state.value.chapterLine)
        assertEquals(DownloadControl.NotDownloaded, v.state.value.download)
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Preparing)) }
        v.downloadTapped()
        advanceTimeBy(100)
        assertEquals("One", v.state.value.chapterLine)
    }

    @Test
    fun speedLabelFollowsSetting() = runTest {
        store.savePlaybackSpeed(1.25f)
        val v = vm(session())
        assertEquals("1.25x", v.state.value.speedLabel)
        v.cycleSpeed()
        advanceUntilIdle()
        assertEquals("1.5x", v.state.value.speedLabel)
        v.cycleSpeed()
        advanceUntilIdle()
        assertEquals("2x", v.state.value.speedLabel)
        v.cycleSpeed()
        advanceUntilIdle()
        assertEquals("1x", v.state.value.speedLabel)
    }

    @Test
    fun seekToFractionOnLoadedBook() = runTest {
        val s = session(duration = 1_000_000)
        val v = vm(s)
        s.calls.clear()
        v.seekToFraction(0.5f)
        assertEquals(listOf("seek 500000"), s.calls)
    }

    @Test
    fun chapterLineAppearsWhenChaptersArriveLater() = runTest {
        val cache = ChapterCache(api) // the fetch on show fails
        val v = vm(session(pos = 3_700_000), cache = cache)
        assertNull(v.state.value.chapterLine)
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        cache.get("A") // e.g. the Library fetching them
        advanceUntilIdle()
        assertEquals("Two", v.state.value.chapterLine)
    }

    @Test
    fun playFromPreviewSwitchesToTheLoadedBook() = runTest {
        resume = { 3_700_000 }
        val s = session(asin = "B", playing = true, last = bookB)
        val v = vm(s)
        assertTrue(v.state.value.isPreview)
        v.playPause()
        // The session switches to A: preparing at 0 until the resume point is known.
        s.state.value = SessionState("A", bookA.title, 0, runtime, false, Phase.Preparing)
        advanceUntilIdle()
        var st = v.state.value
        assertFalse(st.isPreview)
        assertFalse(st.skipsEnabled)
        assertEquals("1:01:40", st.position) // the previewed resume point, not 0:00
        s.state.value = s.state.value.copy(phase = Phase.Connecting)
        advanceUntilIdle()
        assertEquals("1:01:40", v.state.value.position)
        s.state.value = SessionState("A", bookA.title, 3_700_000, runtime, true, Phase.Ready)
        advanceUntilIdle()
        st = v.state.value
        assertFalse(st.isPreview)
        assertTrue(st.skipsEnabled)
        assertTrue(st.isPlaying)
        assertEquals("1:01:40", st.position)
        s.calls.clear()
        v.back15()
        v.forward15()
        assertEquals(listOf("skip -15000", "skip 15000"), s.calls)
    }

    @Test
    fun loadedBookPreparingFromAChapterShowsItsPosition() = runTest {
        // A real position from the session wins over the preview once it is non-zero.
        resume = { 3_700_000 }
        val s = session(asin = "B", last = bookB)
        val v = vm(s)
        v.playPause()
        s.state.value = SessionState("A", bookA.title, 600_000, runtime, false, Phase.Preparing)
        advanceUntilIdle()
        assertEquals("10:00", v.state.value.position)
    }
}
