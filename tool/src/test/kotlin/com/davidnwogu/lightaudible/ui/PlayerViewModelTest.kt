package com.davidnwogu.lightaudible.ui

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.playback.Phase
import com.davidnwogu.lightaudible.playback.Playback
import com.davidnwogu.lightaudible.playback.PlaybackControls
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.playback.SessionState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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

private class FakeControls(initial: SessionState) : PlaybackControls {
    override val state = MutableStateFlow(initial)
    val calls = mutableListOf<String>()
    var retryResult = true

    override fun open(book: LibraryBook, factory: PlaybackFactory) { calls += "open ${book.asin}" }
    override fun attach() { calls += "attach" }
    override fun detach() { calls += "detach" }
    override fun togglePlay() { calls += "toggle" }
    override fun skip(deltaMs: Long) { calls += "skip $deltaMs" }
    override fun seekTo(ms: Long) { calls += "seek $ms" }
    override fun retry(): Boolean { calls += "retry"; return retryResult }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val book = LibraryBook("B1", "Dune", listOf("Frank"), emptyList(), 600_000, true)
    private val api = FakeServerApi()
    private val factory = PlaybackFactory { error("unused") }
    private val chapters = listOf(chapter(0, 0), chapter(1, 60_000))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun session(phase: Phase = Phase.Ready, asin: String? = "B1", pos: Long = 0, playing: Boolean = false) =
        FakeControls(SessionState(asin, "Dune", pos, 600_000, playing, phase))

    private fun TestScope.vm(s: FakeControls): PlayerViewModel =
        PlayerViewModel(book, s, api, factory).also { it.onShow(); advanceUntilIdle() }

    @Test
    fun firstShowOpensLaterShowsOnlyAttach() = runTest {
        val s = session()
        val v = vm(s)
        v.onShow()
        advanceUntilIdle()
        assertEquals(listOf("open B1", "attach"), s.calls)
    }

    @Test
    fun statusLines() = runTest {
        val s = session(Phase.Preparing)
        val v = vm(s)
        assertEquals("Preparing…", v.state.value.statusLine)
        s.state.value = s.state.value.copy(phase = Phase.Connecting); advanceUntilIdle()
        assertEquals("Connecting…", v.state.value.statusLine)
        s.state.value = s.state.value.copy(phase = Phase.Reconnecting); advanceUntilIdle()
        assertEquals("Reconnecting…", v.state.value.statusLine)
        s.state.value = s.state.value.copy(phase = Phase.Ready); advanceUntilIdle()
        assertNull(v.state.value.statusLine)
        s.state.value = s.state.value.copy(phase = Phase.NeedsSignIn); advanceUntilIdle()
        assertEquals("Sign in again on your setup page", v.state.value.statusLine)
        assertFalse(v.state.value.showRetry)
        s.state.value = s.state.value.copy(phase = Phase.Unplayable); advanceUntilIdle()
        assertEquals("This book can't be played", v.state.value.statusLine)
        assertFalse(v.state.value.showRetry)
    }

    @Test
    fun failedShowsMessageAndRetry() = runTest {
        val v = vm(session(Phase.Failed("Couldn't prepare this book")))
        assertEquals("Couldn't prepare this book", v.state.value.statusLine)
        assertTrue(v.state.value.showRetry)
    }

    @Test
    fun retryFallsBackToOpenWhenSessionCannotAct() = runTest {
        val s = session(Phase.Failed("x"))
        val v = vm(s)
        s.calls.clear()
        v.retry()
        assertEquals(listOf("retry"), s.calls)
        s.retryResult = false
        s.calls.clear()
        v.retry()
        assertEquals(listOf("retry", "open B1"), s.calls)
    }

    @Test
    fun controlsDelegate() = runTest {
        val s = session()
        val v = vm(s)
        s.calls.clear()
        v.back30(); v.forward30(); v.togglePlay(); v.jumpTo(chapter(1, 60_000))
        assertEquals(listOf("skip -30000", "skip 30000", "toggle", "seek 60000"), s.calls)
    }

    @Test
    fun clearedDetaches() = runTest {
        val s = session()
        vm(s).onCleared()
        assertEquals("detach", s.calls.last())
    }

    @Test
    fun otherBooksStateIsNotShown() = runTest {
        val s = session(Phase.Failed("Couldn't play this book"), asin = "OTHER", pos = 300_000, playing = true)
        val v = vm(s)
        assertEquals("Dune", v.state.value.title)
        assertEquals("0:00", v.state.value.position)
        assertFalse(v.state.value.isPlaying)
        assertNull(v.state.value.statusLine)
    }

    @Test
    fun showsPositionDurationAndCurrentChapter() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        val v = vm(session(pos = 65_000, playing = true))
        val st = v.state.value
        assertEquals("1:05", st.position)
        assertEquals("10:00", st.duration)
        assertTrue(st.isPlaying)
        assertEquals("Ch 1", st.chapterTitle)
        assertEquals(chapters, st.chapters)
    }

    @Test
    fun chapterFailureIsIgnored() = runTest {
        val v = vm(session())
        assertEquals(emptyList(), v.state.value.chapters)
        assertNull(v.state.value.chapterTitle)
    }
}
