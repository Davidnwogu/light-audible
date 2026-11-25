package com.davidnwogu.lightaudible.sync

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterCacheTest {
    private val api = FakeServerApi()
    private val cache = ChapterCache(api)
    private val chapters = listOf(
        Chapter(idx = 0, title = "One", startMs = 0, lengthMs = 1_000),
        Chapter(idx = 1, title = "Two", startMs = 1_000, lengthMs = 2_000),
    )

    private fun book(list: List<Chapter>?) = BookState(BookStatus.Ready, chapters = list)

    @Test
    fun secondGetUsesCache() = runTest {
        api.bookResult = { ApiResult.Ok(book(chapters)) }
        assertEquals(chapters, cache.get("A"))
        assertEquals(chapters, cache.get("A"))
        assertEquals(1, api.bookCalls.size)
        assertEquals(chapters, cache.cached("A"))
    }

    @Test
    fun failureIsNotCached() = runTest {
        val results = ArrayDeque<ApiResult<BookState>>(listOf(ApiResult.Failed(null), ApiResult.Ok(book(chapters))))
        api.bookResult = { results.removeFirst() }
        assertEquals(emptyList(), cache.get("A"))
        assertNull(cache.cached("A"))
        assertEquals(chapters, cache.get("A"))
        assertEquals(2, api.bookCalls.size)
    }

    @Test
    fun emptyListIsNotCached() = runTest {
        val results = ArrayDeque<ApiResult<BookState>>(listOf(ApiResult.Ok(book(emptyList())), ApiResult.Ok(book(chapters))))
        api.bookResult = { results.removeFirst() }
        assertEquals(emptyList(), cache.get("A"))
        assertNull(cache.cached("A"))
        assertEquals(chapters, cache.get("A"))
        assertEquals(2, api.bookCalls.size)
    }

    @Test
    fun nullChaptersAreNotCached() = runTest {
        api.bookResult = { ApiResult.Ok(book(null)) }
        assertEquals(emptyList(), cache.get("A"))
        assertNull(cache.cached("A"))
    }

    @Test
    fun updatesEmitsOnFetch() = runTest {
        api.bookResult = { ApiResult.Ok(book(chapters)) }
        assertEquals(emptyMap(), cache.updates.value)
        cache.get("A")
        assertEquals(mapOf("A" to chapters), cache.updates.value)
    }

    @Test
    fun updatesNotEmittedOnFailure() = runTest {
        api.bookResult = { ApiResult.Failed(503) }
        cache.get("A")
        assertEquals(emptyMap(), cache.updates.value)
    }
}
