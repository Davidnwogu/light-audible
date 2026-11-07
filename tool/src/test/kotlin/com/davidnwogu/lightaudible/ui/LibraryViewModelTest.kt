package com.davidnwogu.lightaudible.ui

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.api.LibraryPage
import com.davidnwogu.lightaudible.fakes.FakeServerApi
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private fun book(asin: String, title: String, vararg authors: String, narrators: List<String> = emptyList(), playable: Boolean = true) =
    LibraryBook(asin, title, authors.toList(), narrators, 1000L, playable)

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

    private fun TestScope.shown(): LibraryViewModel =
        LibraryViewModel(store, api) { now }.also {
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
    fun noCacheAndFailureIsEmptyWithoutBanner() = runTest {
        val vm = shown()
        assertEquals(emptyList(), vm.state.value.visible)
        assertNull(vm.state.value.banner)
        assertFalse(vm.state.value.loading)
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
        vm = LibraryViewModel(store, api) { now }
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
}
