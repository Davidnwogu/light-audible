package com.davidnwogu.lightaudible.sync

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PreparerTest {
    private val api = FakeServerApi()
    private val preparer = Preparer(api, 5_000)
    private val readyState = BookState(BookStatus.Ready, "https://x/a.m4b", "2030-01-01T00:00:00Z")
    private fun st(s: BookStatus, error: String? = null) = BookState(s, error = error)

    @Test
    fun readyOnFirstPrepare() = runTest {
        api.prepareResult = { ApiResult.Ok(readyState) }
        val out = preparer.readyUrl("A") {}
        assertTrue(out is PrepareOutcome.Ready)
        assertEquals("https://x/a.m4b", out.url)
        assertEquals(1, api.prepareCalls.size)
        assertEquals(0, currentTime)
    }

    @Test
    fun pollsThenPreparesAgain() = runTest {
        val prepares = ArrayDeque(listOf(st(BookStatus.Preparing), readyState))
        api.prepareResult = { ApiResult.Ok(prepares.removeFirst()) }
        val polls = ArrayDeque(listOf(st(BookStatus.Preparing), st(BookStatus.Ready)))
        api.bookResult = { ApiResult.Ok(polls.removeFirst()) }
        val waits = mutableListOf<PrepareWait>()
        val out = preparer.readyUrl("A") { waits += it }
        assertTrue(out is PrepareOutcome.Ready)
        assertEquals(10_000, currentTime)
        assertTrue(PrepareWait.Preparing in waits)
        assertEquals(2, api.prepareCalls.size)
    }

    @Test
    fun failedStatus() = runTest {
        api.prepareResult = { ApiResult.Ok(st(BookStatus.Failed, "x")) }
        assertEquals(PrepareOutcome.Failed("x"), preparer.readyUrl("A") {})
    }

    @Test
    fun unplayable() = runTest {
        api.prepareResult = { ApiResult.Ok(st(BookStatus.Unplayable, "drm")) }
        assertEquals(PrepareOutcome.Unplayable("drm"), preparer.readyUrl("A") {})
    }

    @Test
    fun pollNetworkFailureReportsConnectingAndContinues() = runTest {
        api.prepareResult = { ApiResult.Ok(if (api.prepareCalls.size == 1) st(BookStatus.Preparing) else readyState) }
        val polls = ArrayDeque(listOf<ApiResult<BookState>>(ApiResult.Failed(null), ApiResult.Ok(st(BookStatus.Ready))))
        api.bookResult = { polls.removeFirst() }
        val waits = mutableListOf<PrepareWait>()
        val out = preparer.readyUrl("A") { waits += it }
        assertTrue(out is PrepareOutcome.Ready)
        assertTrue(PrepareWait.Connecting in waits)
    }

    @Test
    fun initialNetworkFailureRetries() = runTest {
        val results = ArrayDeque(listOf<ApiResult<BookState>>(ApiResult.Failed(null), ApiResult.Failed(503), ApiResult.Ok(readyState)))
        api.prepareResult = { results.removeFirst() }
        val waits = mutableListOf<PrepareWait>()
        val out = preparer.readyUrl("A") { waits += it }
        assertTrue(out is PrepareOutcome.Ready)
        assertEquals(listOf(PrepareWait.Connecting, PrepareWait.Connecting), waits)
        assertEquals(15_000, currentTime) // backs off: 5 s, then 10 s
    }

    @Test
    fun networkFailuresBackOffAndAnyResponseResetsIt() = runTest {
        val prepareAt = mutableListOf<Long>()
        api.prepareResult = {
            prepareAt += testScheduler.currentTime
            when (prepareAt.size) {
                in 1..7 -> ApiResult.Failed(null)
                8 -> ApiResult.Ok(st(BookStatus.Preparing))
                else -> ApiResult.Ok(readyState)
            }
        }
        val pollAt = mutableListOf<Long>()
        val polls = ArrayDeque(listOf<ApiResult<BookState>>(
            ApiResult.Ok(st(BookStatus.NotReady)), ApiResult.Failed(503), ApiResult.Ok(st(BookStatus.Ready)),
        ))
        api.bookResult = { pollAt += testScheduler.currentTime; polls.removeFirst() }
        val out = preparer.readyUrl("A") {}
        assertTrue(out is PrepareOutcome.Ready)
        // Waits of 5, 10, 20, 40, 60, 60, 60 s between failed prepares.
        assertEquals(listOf(0L, 5_000, 15_000, 35_000, 75_000, 135_000, 195_000, 255_000, 270_000), prepareAt)
        // Polling while preparing stays every 5 s; the earlier responses reset the backoff to 5 s.
        assertEquals(listOf(260_000L, 265_000, 270_000), pollAt)
    }

    @Test
    fun terminalApiResults() = runTest {
        api.prepareResult = { ApiResult.Unauthorized }
        assertEquals(PrepareOutcome.Unauthorized, preparer.readyUrl("A") {})
        api.prepareResult = { ApiResult.NeedsSignIn }
        assertEquals(PrepareOutcome.NeedsSignIn, preparer.readyUrl("A") {})
        api.prepareResult = { ApiResult.NotFound }
        assertEquals(PrepareOutcome.NotFound, preparer.readyUrl("A") {})
    }
}
