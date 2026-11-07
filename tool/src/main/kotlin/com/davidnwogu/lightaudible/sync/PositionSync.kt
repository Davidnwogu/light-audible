package com.davidnwogu.lightaudible.sync

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.RemotePosition
import com.davidnwogu.lightaudible.api.ServerApi
import com.davidnwogu.lightaudible.api.ServerClock
import com.davidnwogu.lightaudible.store.PendingPosition
import com.davidnwogu.lightaudible.store.ToolStore
import java.time.Instant
import java.time.OffsetDateTime

/** Offline-safe position reporting and resume-point resolution. */
class PositionSync(
    private val api: ServerApi,
    private val store: ToolStore,
    private val clock: ServerClock,
) {
    suspend fun record(asin: String, positionMs: Long) {
        store.putPendingPosition(PendingPosition(asin, positionMs, clock.now().toEpochMilli()))
        flush()
    }

    /** Sends pending entries oldest first; returns true when the queue is empty. */
    suspend fun flush(): Boolean {
        for (p in store.pendingPositions().sortedBy { it.atMs }) {
            when (api.putPosition(p.asin, p.positionMs, Instant.ofEpochMilli(p.atMs))) {
                is ApiResult.Ok, ApiResult.NotFound -> store.removePendingPosition(p)
                else -> return false
            }
        }
        return store.pendingPositions().isEmpty()
    }

    suspend fun resumePoint(asin: String): Long {
        flush()
        val local = store.pendingPositions().firstOrNull { it.asin == asin }
        val remote = (api.position(asin) as? ApiResult.Ok<RemotePosition>)?.value
            ?: return local?.positionMs ?: 0L
        if (local == null) return remote.positionMs
        val remoteAtMs = runCatching { OffsetDateTime.parse(remote.updatedAt).toInstant().toEpochMilli() }
            .getOrNull() ?: return local.positionMs
        return if (remoteAtMs > local.atMs) remote.positionMs else local.positionMs
    }
}
