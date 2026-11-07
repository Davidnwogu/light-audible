package com.davidnwogu.lightaudible.sync

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.RemotePosition
import com.davidnwogu.lightaudible.api.ServerClock
import com.davidnwogu.lightaudible.fakes.FakeServerApi
import com.davidnwogu.lightaudible.fakes.InMemoryToolStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PositionSyncTest {
    private val hour = 3_600_000L
    private var device = 1_000_000_000L
    private var offset = 0L
    private val api = FakeServerApi()
    private val store = InMemoryToolStore()
    private val clock get() = ServerClock({ device }, offset) {}
    private fun sync() = PositionSync(api, store, clock)

    private fun remote(ms: Long, atMs: Long) =
        ApiResult.Ok(RemotePosition(ms, Instant.ofEpochMilli(atMs).toString()))

    @Test
    fun recordWhileOfflineKeepsEntry() = runTest {
        api.putPositionResult = { ApiResult.Failed(null) }
        sync().record("A1", 5_000)
        assertEquals(1, store.pendingPositions().size)
    }

    @Test
    fun flushAfterReconnectSendsAndClears() = runTest {
        val s = sync()
        s.record("A1", 5_000)
        api.putPositionResult = { ApiResult.Ok(Unit) }
        assertTrue(s.flush())
        assertEquals(emptyList(), store.pendingPositions())
        assertEquals("A1", api.putPositionCalls.last().asin)
        assertEquals(5_000L, api.putPositionCalls.last().positionMs)
    }

    @Test
    fun twoRecordsForOneAsinSendOnlyNewest() = runTest {
        val s = sync()
        s.record("A1", 5_000)
        device += 1000
        s.record("A1", 7_000)
        api.putPositionCalls.clear()
        api.putPositionResult = { ApiResult.Ok(Unit) }
        assertTrue(s.flush())
        assertEquals(listOf(7_000L), api.putPositionCalls.map { it.positionMs })
    }

    @Test
    fun notFoundDropsEntry() = runTest {
        api.putPositionResult = { ApiResult.NotFound }
        sync().record("A1", 1_000)
        assertEquals(emptyList(), store.pendingPositions())
    }

    @Test
    fun failureStopsFlushAndKeepsLaterEntries() = runTest {
        val s = sync()
        s.record("A1", 1)
        device += 1
        s.record("A2", 2)
        api.putPositionCalls.clear()
        api.putPositionResult = { ApiResult.Unauthorized }
        assertFalse(s.flush())
        assertEquals(1, api.putPositionCalls.size)
        assertEquals(2, store.pendingPositions().size)
    }

    @Test
    fun resolvePrefersNewerUnsentLocalPosition() = runTest {
        val s = sync()
        s.record("A1", 90_000) // offline, stays pending at `device`
        api.positionResult = { remote(10_000, device - hour) }
        assertEquals(90_000L, s.resumePoint("A1"))
    }

    @Test
    fun resolveUsesServerWhenNewer() = runTest {
        val s = sync()
        s.record("A1", 90_000)
        api.positionResult = { remote(10_000, device + hour) }
        assertEquals(10_000L, s.resumePoint("A1"))
    }

    @Test
    fun resolveFallsBackToLocalOrZeroWhenGetFails() = runTest {
        val s = sync()
        assertEquals(0L, s.resumePoint("A1"))
        s.record("A1", 42_000)
        assertEquals(42_000L, s.resumePoint("A1"))
    }

    @Test
    fun resolveUsesServerAfterSuccessfulFlush() = runTest {
        val s = sync()
        s.record("A1", 90_000)
        api.putPositionResult = { ApiResult.Ok(Unit) }
        api.positionResult = { remote(90_000, device) }
        assertEquals(90_000L, s.resumePoint("A1"))
    }

    @Test
    fun stampsWithServerCorrectedTime() = runTest {
        offset = -600_000L
        sync().record("A1", 1_000)
        assertEquals(
            Instant.ofEpochMilli(device - 600_000L),
            api.putPositionCalls.single().clientUpdatedAt,
        )
    }
}
