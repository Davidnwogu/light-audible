package com.davidnwogu.lightaudible.ui

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.fakes.ScriptedInterceptor
import com.davidnwogu.lightaudible.sync.Downloads
import com.davidnwogu.lightaudible.sync.Preparer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class BookViewModelTest {
    private val api = FakeServerApi()
    private val dir = kotlin.io.path.createTempDirectory("bk").toFile()
    private val net = ScriptedInterceptor()
    private val downloads = Downloads(
        dir, OkHttpClient.Builder().addInterceptor(net).build(),
        Preparer(api), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined,
    )
    private val book = LibraryBook("B1", "Title", listOf("A"), listOf("N"), 1000L, true)
    private val chapters = listOf(Chapter(0, "One", 0, 500), Chapter(1, "Two", 500, 500))

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.shown() = BookViewModel(book, api, downloads).also {
        it.onShow()
        advanceUntilIdle()
    }

    @Test
    fun loadsChapters() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        val vm = shown()
        assertEquals(chapters, vm.state.value.chapters)
        assertNull(vm.state.value.statusLine)
    }

    @Test
    fun failedStatusLine() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Failed, error = "e")) }
        assertEquals("Couldn't prepare this book", shown().state.value.statusLine)
    }

    @Test
    fun needsSignInStatusLine() = runTest {
        api.bookResult = { ApiResult.NeedsSignIn }
        assertEquals("Sign in again on your setup page", shown().state.value.statusLine)
    }

    @Test
    fun preparingStatusLine() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Preparing)) }
        assertEquals("Preparing…", shown().state.value.statusLine)
    }

    @Test
    fun downloadedReflectsFile() = runTest {
        File(dir, "B1.m4b").writeBytes(byteArrayOf(1))
        assertTrue(shown().state.value.downloaded)
    }

    @Test
    fun notDownloaded() = runTest {
        assertFalse(shown().state.value.downloaded)
    }

    @Test
    fun retryPreparesAgainAndRereadsStatus() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Failed, error = "e")) }
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Preparing)) }
        val vm = shown()
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf("B1"), api.prepareCalls)
        assertEquals("Preparing…", vm.state.value.statusLine)
    }

    @Test
    fun downloadFailureIsShown() = runTest {
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Ready, chapters = chapters)) }
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Ready, "https://cdn/x", "2030-01-01T00:00:00Z")) }
        net.enqueueFailure(java.io.IOException("x"))
        val vm = shown()
        vm.download()
        advanceUntilIdle()
        assertEquals("Download failed", vm.state.value.statusLine)
    }

    @Test
    fun cancelDownloadClearsProgress() = runTest {
        api.prepareResult = { ApiResult.Ok(BookState(BookStatus.Preparing)) }
        api.bookResult = { ApiResult.Ok(BookState(BookStatus.Preparing)) }
        val d = Downloads(dir, OkHttpClient(), Preparer(api), this, StandardTestDispatcher(testScheduler))
        val vm = BookViewModel(book, api, d)
        vm.onShow()
        advanceUntilIdle()
        vm.download()
        advanceTimeBy(100)
        assertEquals(0f, vm.state.value.downloadProgress)
        vm.cancelDownload()
        advanceTimeBy(20_000)
        assertNull(vm.state.value.downloadProgress)
    }
}
