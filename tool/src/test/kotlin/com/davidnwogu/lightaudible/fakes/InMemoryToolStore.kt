package com.davidnwogu.lightaudible.fakes

import com.davidnwogu.lightaudible.api.Credentials
import com.davidnwogu.lightaudible.store.LibraryCache
import com.davidnwogu.lightaudible.store.PendingPosition
import com.davidnwogu.lightaudible.store.ToolStore

class InMemoryToolStore : ToolStore {
    private var url: String? = null
    private var token: String? = null
    private var cache: LibraryCache? = null
    private val pending = LinkedHashMap<String, PendingPosition>()
    private var offsetMs = 0L

    override suspend fun credentials(): Credentials? {
        val u = url ?: return null
        val t = token ?: return null
        return Credentials(u, t)
    }

    override suspend fun serverUrl(): String? = url

    override suspend fun saveCredentials(c: Credentials) {
        url = c.baseUrl
        token = c.token
    }

    override suspend fun clearToken() {
        token = null
    }

    override suspend fun libraryCache(): LibraryCache? = cache

    override suspend fun saveLibraryCache(c: LibraryCache) {
        cache = c
    }

    override suspend fun pendingPositions(): List<PendingPosition> = pending.values.toList()

    override suspend fun putPendingPosition(p: PendingPosition) {
        val existing = pending[p.asin]
        if (existing == null || p.atMs >= existing.atMs) pending[p.asin] = p
    }

    override suspend fun removePendingPosition(p: PendingPosition) {
        if (pending[p.asin] == p) pending.remove(p.asin)
    }

    override suspend fun clockOffsetMs(): Long = offsetMs

    private var speed = 1f

    override suspend fun playbackSpeed(): Float = speed

    override suspend fun savePlaybackSpeed(rate: Float) {
        speed = rate
    }

    override suspend fun saveClockOffsetMs(v: Long) {
        offsetMs = v
    }
}
