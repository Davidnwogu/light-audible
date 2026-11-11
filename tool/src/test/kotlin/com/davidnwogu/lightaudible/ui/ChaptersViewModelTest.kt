package com.davidnwogu.lightaudible.ui

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.fakes.FakeControls
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.playback.Phase
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.playback.SessionState
import com.davidnwogu.lightaudible.api.RemotePosition
import com.davidnwogu.lightaudible.api.ServerClock
import com.davidnwogu.lightaudible.fakes.FakePlayback
import com.davidnwogu.lightaudible.fakes.FakePlaybackFactory
import com.davidnwogu.lightaudible.fakes.InMemoryToolStore
import com.davidnwogu.lightaudible.playback.PlaybackSession
import com.davidnwogu.lightaudible.sync.ChapterCache
import com.davidnwogu.lightaudible.sync.Downloads
import com.davidnwogu.lightaudible.sync.PositionSync
import com.davidnwogu.lightaudible.sync.Preparer
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ChaptersViewModelTest {
    private val bookA = LibraryBook("A", "Maps of Meaning", listOf("J"), emptyList(), 7_200_000, true)
    private val bookB = LibraryBook("B", "Dune", listOf("F"), emptyList(), 600_000, true)
    private val chapters = listOf(
        Chapter(0, "One", 0, 3_600_000),
        Chapter(1, "Two", 3_600_000, 1_800_000),
        Chapter(2, "Three", 5_400_000, 1_800_000),
    )
    private val api = FakeServerApi().also {
        it.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
    }
    private val factory = PlaybackFactory { error("unused") }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun session(asin: String, pos: Long, last: LibraryBook) =
        FakeControls(SessionState(asin, "t", pos, 7_200_000, false, Phase.Ready)).also { it.lastBook.value = last }

    private fun vm(s: FakeControls, book: LibraryBook = bookA, preview: Long = 0) =
        ChaptersViewModel(book, s, ChapterCache(api), factory, preview)

    @Test
    fun currentRowFollowsLoadedPosition() = runTest {
        val s = session("A", 100_000, bookA)
        val v = vm(s)
        advanceUntilIdle()
        assertEquals(listOf(true, false, false), v.rows.value.map { it.isCurrent })
        assertEquals(listOf("1:00:00", "30:00", "30:00"), v.rows.value.map { it.length })
        s.state.value = s.state.value.copy(positionMs = 5_500_000)
        advanceUntilIdle()
        assertEquals(listOf(false, false, true), v.rows.value.map { it.isCurrent })
    }

    @Test
    fun currentRowUsesPreviewPositionWhenNotLoaded() = runTest {
        val s = session("B", 50_000, bookB)
        val v = vm(s, preview = 3_700_000)
        advanceUntilIdle()
        assertEquals(listOf(false, true, false), v.rows.value.map { it.isCurrent })
    }

    @Test
    fun chapterTapOnLoadedBookSeeks() = runTest {
        val s = session("A", 0, bookA)
        val v = vm(s)
        advanceUntilIdle()
        v.select(chapters[1])
        assertEquals(listOf("seek 3600000"), s.calls)
    }

    @Test
    fun chapterTapOnUnloadedBookOpensAtChapter() = runTest {
        val s = session("B", 0, bookB)
        val v = vm(s)
        advanceUntilIdle()
        v.select(chapters[1])
        assertEquals(listOf("open A"), s.calls)
        assertEquals(3_600_000L, s.openStarts.single())
        assertSame(factory, s.factories.single())
    }

    @Test
    fun chapterTapOnReleasedBookMovesWhereTheNextPlayStarts() = runTest {
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Ready, "https://s/$it", "2030-01-01T06:00:00Z")) }
        api.positionResult = { ApiResult.Ok(RemotePosition(0L, "2029-12-31T00:00:00Z")) }
        api.putPositionResult = { ApiResult.Ok(Unit) }
        val t0 = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
        val preparer = Preparer(api, 5_000)
        val dir = kotlin.io.path.createTempDirectory("ch").toFile()
        val session = PlaybackSession(
            PositionSync(api, InMemoryToolStore(), ServerClock({ t0 }, 0, {})), preparer,
            Downloads(dir, OkHttpClient(), preparer, backgroundScope), MutableStateFlow(1f), backgroundScope,
        ) { t0 + testScheduler.currentTime }
        val f1 = FakePlaybackFactory()
        session.open(bookA, f1)
        runCurrent()
        session.togglePlay(f1) // pause
        runCurrent()
        session.detach() // paused with no Player: the handle is released
        runCurrent()
        assertTrue(f1.last.released)

        val v = ChaptersViewModel(bookA, session, ChapterCache(api), factory, 0)
        advanceUntilIdle()
        v.select(chapters[1])
        assertEquals(3_600_000, session.state.value.positionMs)
        assertFalse(session.state.value.isPlaying)

        val f2 = FakePlaybackFactory()
        session.togglePlay(f2)
        runCurrent()
        val sets = f2.last.calls.filterIsInstance<FakePlayback.Call.SetItem>()
        assertEquals(listOf("A" to 3_600_000L), sets.map { it.item.asin to it.startMs }, "${f2.last.calls}")
    }
}
