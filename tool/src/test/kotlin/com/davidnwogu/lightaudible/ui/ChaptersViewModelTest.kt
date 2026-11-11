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
import com.davidnwogu.lightaudible.sync.ChapterCache
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
}
