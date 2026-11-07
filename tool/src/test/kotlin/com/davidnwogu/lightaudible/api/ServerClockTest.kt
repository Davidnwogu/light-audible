package com.davidnwogu.lightaudible.api

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerClockTest {
    private val tenMin = 600_000L

    @Test
    fun serverAheadShiftsNow() {
        var device = 1_000_000L
        val clock = ServerClock({ device }, 0L) {}
        // midpoint of 1_000_000 and 1_000_200 is 1_000_100
        clock.observe(Instant.ofEpochMilli(1_000_100L + tenMin), 1_000_000L, 1_000_200L)
        assertEquals(tenMin, clock.offsetMs)
        assertEquals(Instant.ofEpochMilli(device + tenMin), clock.now())
    }

    @Test
    fun smallDifferenceKeepsOffset() {
        val changes = mutableListOf<Long>()
        val clock = ServerClock({ 0L }, 0L) { changes += it }
        clock.observe(Instant.ofEpochMilli(1_000L), 0L, 0L)
        assertEquals(0L, clock.offsetMs)
        assertEquals(emptyList(), changes)
    }

    @Test
    fun callbackReceivesNewOffset() {
        val changes = mutableListOf<Long>()
        val clock = ServerClock({ 0L }, 0L) { changes += it }
        clock.observe(Instant.ofEpochMilli(tenMin), 0L, 0L)
        assertEquals(listOf(tenMin), changes)
    }
}
