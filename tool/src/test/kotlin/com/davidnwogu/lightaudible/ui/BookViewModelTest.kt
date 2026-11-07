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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
class BookViewModelTest {
    private val api = FakeServerApi()
    private val dir = kotlin.io.path.createTempDirectory("bk").toFile()
    private val downloads = Downloads(
        dir, OkHttpClient.Builder().addInterceptor(ScriptedInterceptor()).build(),
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
}
