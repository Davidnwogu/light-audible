package com.davidnwogu.lightaudible.api

import java.time.Instant
import kotlin.math.abs

/** Tracks the offset between the device clock and the server clock. */
class ServerClock(
    private val deviceNowMs: () -> Long,
    initialOffsetMs: Long,
    private val onOffsetChanged: (Long) -> Unit,
) {
    @Volatile
    var offsetMs: Long = initialOffsetMs
        private set

    fun observe(serverDate: Instant, sentAtMs: Long, receivedAtMs: Long) {
        val midpoint = sentAtMs + (receivedAtMs - sentAtMs) / 2
        val offset = serverDate.toEpochMilli() - midpoint
        if (abs(offset - offsetMs) > THRESHOLD_MS) {
            offsetMs = offset
            onOffsetChanged(offset)
        }
    }

    fun now(): Instant = Instant.ofEpochMilli(deviceNowMs() + offsetMs)

    private companion object {
        const val THRESHOLD_MS = 2000L
    }
}
