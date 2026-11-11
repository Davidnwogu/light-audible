package com.davidnwogu.lightaudible.ui

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.api.LibraryPage
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.fakes.FakeControls
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.playback.Phase
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.playback.SessionState
import com.davidnwogu.lightaudible.sync.ChapterCache
import com.davidnwogu.lightaudible.sync.Downloads
import com.davidnwogu.lightaudible.sync.Preparer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.OkHttpClient
import kotlin.test.assertTrue
import com.davidnwogu.lightaudible.fakes.InMemoryToolStore
import com.davidnwogu.lightaudible.store.LibraryCache
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private fun book(asin: String, title: String, vararg authors: String, narrators: List<String> = emptyList(), playable: Boolean = true) =
    LibraryBook(asin, title, authors.toList(), narrators, 1000L, playable)

private val LibraryUiState.visible get() = rows.map { it.book }

class FormatAuthorsTest {
    @Test
    fun formatAuthorsDropsCreditsAndCaps() {
        assertEquals(
            "Marcus Tullius Cicero",
            formatAuthors(listOf("Marcus Tullius Cicero", "Philip Freeman - Introduction", "Philip Freeman - Translator")),
        )
        assertEquals("A, B +2", formatAuthors(listOf("A", "B", "C", "D")))
        assertEquals("Rachel Joy Welcher", formatAuthors(listOf("Rachel Joy Welcher", "Scott Sauls - foreword")))
        assertEquals("X", formatAuthors(listOf("X - Editor")))
        assertEquals("", formatAuthors(emptyList()))
    }
}

class FilterBooksTest {
    private val hobbit = book("A1", "The Hobbit", "J.R.R. Tolkien", narrators = listOf("Rob Inglis"))
    private val dune = book("A2", "Dune", "Frank Herbert", narrators = listOf("Scott Brick"))
    private val all = listOf(hobbit, dune)

    @Test
    fun matchesAuthorCaseInsensitive() = assertEquals(listOf(hobbit), filterBooks(all, "tolk"))

    @Test
    fun matchesTitleCaseInsensitive() = assertEquals(listOf(hobbit), filterBooks(all, "HOBBIT"))

    @Test
    fun blankReturnsEverythingInOrder() = assertEquals(all, filterBooks(all, "  "))

    @Test
    fun narratorsAreNotSearched() = assertEquals(emptyList(), filterBooks(all, "inglis"))
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val store = InMemoryToolStore()
    private val api = FakeServerApi()
    private val now = 100L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val old = listOf(book("A1", "Old Book", "Au Thor"))
    private val fresh = listOf(book("A2", "New Book", "Other Person"))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val dir = kotlin.io.path.createTempDirectory("lib").toFile()
    private val chapterCache = ChapterCache(api)
    private val session = FakeControls(SessionState(null, null, 0, 0, false, Phase.Idle))
    private val dl = Downloads(dir, OkHttpClient(), Preparer(api), kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun vm(
        flush: suspend () -> Unit = {},
        connected: Flow<Boolean> = emptyFlow(),
    ) = LibraryViewModel(store, api, session, chapterCache, dl, flush, connected) { now }

    private fun TestScope.shown(): LibraryViewModel =
        vm().also {
            it.onShow()
            advanceUntilIdle()
        }

    @Test
    fun freshCacheShowsBooksWithoutNetwork() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now - 23 * hour))
        val vm = shown()
        assertEquals(old, vm.state.value.visible)
        assertEquals(emptyList(), api.libraryCalls)
        assertEquals(0, api.refreshLibraryCalls)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun staleCacheNotModifiedKeepsBooksAndBumpsTimestamp() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now - 25 * hour))
        api.libraryResult = { ApiResult.NotModified }
        val vm = shown()
        assertEquals(listOf<String?>("e1"), api.libraryCalls)
        assertEquals(old, vm.state.value.books)
        assertEquals(LibraryCache(old, "e1", now), store.libraryCache())
        assertNull(vm.state.value.banner)
    }

    @Test
    fun staleCacheOkReplacesAndSaves() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now - 25 * hour))
        api.libraryResult = { ApiResult.Ok(LibraryPage(fresh, "e2")) }
        val vm = shown()
        assertEquals(fresh, vm.state.value.visible)
        assertEquals(LibraryCache(fresh, "e2", now), store.libraryCache())
    }

    @Test
    fun noCacheAndFailureSaysServerIsUnreachable() = runTest {
        val vm = shown()
        assertEquals(emptyList(), vm.state.value.visible)
        assertEquals("Can't reach the server.", vm.state.value.banner)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun cachedBooksAndFailureHasNoBanner() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now - 25 * hour))
        val vm = shown()
        assertEquals(old, vm.state.value.visible)
        assertNull(vm.state.value.banner)
    }

    @Test
    fun needsSignInKeepsCachedBooksWithBanner() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now - 25 * hour))
        api.libraryResult = { ApiResult.NeedsSignIn }
        val vm = shown()
        assertEquals(old, vm.state.value.visible)
        assertEquals("Sign in again on your setup page", vm.state.value.banner)
    }

    @Test
    fun connectingBannerWhileFetching() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now - 25 * hour))
        lateinit var vm: LibraryViewModel
        var bannerDuringCall: String? = null
        api.libraryResult = {
            bannerDuringCall = vm.state.value.banner
            ApiResult.NotModified
        }
        vm = vm()
        vm.onShow()
        advanceUntilIdle()
        assertEquals("Connecting…", bannerDuringCall)
        assertNull(vm.state.value.banner)
    }

    @Test
    fun refreshCallsRefreshEvenWhenFresh() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now - hour))
        api.refreshLibraryResult = { ApiResult.Ok(LibraryPage(fresh, "e3")) }
        val vm = shown()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(1, api.refreshLibraryCalls)
        assertEquals(fresh, vm.state.value.visible)
        assertEquals(LibraryCache(fresh, "e3", now), store.libraryCache())
    }

    @Test
    fun setQueryUpdatesVisible() = runTest {
        store.saveLibraryCache(LibraryCache(old + fresh, "e1", now))
        val vm = shown()
        vm.setQuery("other")
        assertEquals(fresh, vm.state.value.visible)
        assertEquals("other", vm.state.value.query)
        assertEquals(old + fresh, vm.state.value.books)
    }

    @Test
    fun everyShowFlushesPendingPositions() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now))
        var flushes = 0
        val vm = vm(flush = { flushes++ })
        vm.onShow()
        advanceUntilIdle()
        assertEquals(1, flushes)
        vm.onShow()
        advanceUntilIdle()
        assertEquals(2, flushes)
    }

    @Test
    fun networkComingBackFlushesPendingPositions() = runTest {
        store.saveLibraryCache(LibraryCache(old, "e1", now))
        var flushes = 0
        val connected = MutableStateFlow(true)
        val vm = vm(flush = { flushes++ }, connected = connected)
        vm.onShow()
        advanceUntilIdle()
        assertEquals(1, flushes) // the show; already-connected is not a return
        connected.value = false
        advanceUntilIdle()
        assertEquals(1, flushes)
        connected.value = true
        advanceUntilIdle()
        assertEquals(2, flushes)
    }

    private val chapters = listOf(Chapter(0, "One", 0, 3_600_000), Chapter(1, "Two", 3_600_000, 3_600_000))
    private val a = book("A", "A", "Au Thor")

    private fun sess(asin: String? = "A", pos: Long = 0, phase: Phase = Phase.Ready, playing: Boolean = false) =
        SessionState(asin, "A", pos, 7_200_000, playing, phase)

    private suspend fun TestScope.withLast(
        state: SessionState,
        last: LibraryBook? = a,
        books: List<LibraryBook> = listOf(a),
    ): LibraryViewModel {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        store.saveLibraryCache(LibraryCache(books, "e", now))
        session.state.value = state
        session.lastBook.value = last
        return shown().also { advanceUntilIdle() }
    }

    @Test
    fun nowPlayingHiddenWithNothingOpened() = runTest {
        assertNull(withLast(sess(null), last = null).state.value.nowPlaying)
    }

    @Test
    fun nowPlayingShowsTitleAndChapter() = runTest {
        val v = withLast(sess(pos = 3_700_000))
        assertEquals(NowPlayingBar("A · Two", showPlayPause = true, isPlaying = false), v.state.value.nowPlaying)
    }

    @Test
    fun nowPlayingFollowsPosition() = runTest {
        val v = withLast(sess(pos = 0, playing = true))
        assertEquals("A · One", v.state.value.nowPlaying?.text)
        session.state.value = sess(pos = 3_700_000, playing = true)
        advanceUntilIdle()
        assertEquals(NowPlayingBar("A · Two", true, true), v.state.value.nowPlaying)
    }

    @Test
    fun nowPlayingShowsPreparingWithoutButton() = runTest {
        val v = withLast(sess(phase = Phase.Preparing))
        assertEquals(NowPlayingBar("A · Preparing…", false, false), v.state.value.nowPlaying)
        session.state.value = sess(phase = Phase.Connecting)
        advanceUntilIdle()
        assertEquals(NowPlayingBar("A · Connecting…", false, false), v.state.value.nowPlaying)
    }

    @Test
    fun nowPlayingReconnectingKeepsButton() = runTest {
        val v = withLast(sess(phase = Phase.Reconnecting))
        assertEquals(NowPlayingBar("A · Reconnecting…", true, false), v.state.value.nowPlaying)
    }

    @Test
    fun nowPlayingFailedNeedsSignInAndUnplayableHaveNoButton() = runTest {
        val v = withLast(sess(phase = Phase.Failed("Disk full")))
        assertEquals(NowPlayingBar("A · Disk full", false, false), v.state.value.nowPlaying)
        session.state.value = sess(phase = Phase.NeedsSignIn)
        advanceUntilIdle()
        assertEquals(NowPlayingBar("A · Sign in again on your setup page", false, false), v.state.value.nowPlaying)
        session.state.value = sess(phase = Phase.Unplayable)
        advanceUntilIdle()
        assertEquals(NowPlayingBar("A · This book can't be played", false, false), v.state.value.nowPlaying)
    }

    @Test
    fun nowPlayingSurvivesRelease() = runTest {
        val v = withLast(sess(asin = null, phase = Phase.Idle))
        assertEquals(NowPlayingBar("A", showPlayPause = true, isPlaying = false), v.state.value.nowPlaying)
    }

    @Test
    fun rowsMarkLoadedAndDownloaded() = runTest {
        val b = book("B", "Bee", "Au - Credit", "X", "Y", "Z")
        java.io.File(dir, "B.m4b").writeText("x")
        val v = withLast(sess(asin = "A"), books = listOf(a, b))
        val (ra, rb) = v.state.value.rows
        assertTrue(ra.isLoaded); assertFalse(ra.isDownloaded)
        assertFalse(rb.isLoaded); assertTrue(rb.isDownloaded)
        assertEquals("X, Y +1", rb.authors)
    }

    @Test
    fun clearQueryRestoresAllRows() = runTest {
        store.saveLibraryCache(LibraryCache(old + fresh, "e1", now))
        val v = shown()
        v.setQuery("other")
        assertEquals(1, v.state.value.rows.size)
        v.clearQuery()
        assertEquals("", v.state.value.query)
        assertEquals(2, v.state.value.rows.size)
    }

    @Test
    fun togglePlayDelegatesToSession() = runTest {
        shown().togglePlay(PlaybackFactory { error("unused") })
        assertEquals(listOf("toggle"), session.calls)
    }
}
