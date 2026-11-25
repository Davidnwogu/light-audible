package com.davidnwogu.lightaudible.store

import com.davidnwogu.lightaudible.api.Credentials
import com.davidnwogu.lightaudible.api.LibraryBook
import kotlinx.serialization.Serializable

data class LibraryCache(val books: List<LibraryBook>, val etag: String?, val fetchedAtMs: Long)

/** [atMs] is server-corrected epoch milliseconds. */
@Serializable
data class PendingPosition(val asin: String, val positionMs: Long, val atMs: Long)

interface ToolStore {
    /** Null unless both URL and token are set. */
    suspend fun credentials(): Credentials?
    suspend fun serverUrl(): String?
    suspend fun saveCredentials(c: Credentials)

    /** Clears the token but keeps the URL. */
    suspend fun clearToken()
    suspend fun libraryCache(): LibraryCache?
    suspend fun saveLibraryCache(c: LibraryCache)
    suspend fun pendingPositions(): List<PendingPosition>

    /** Replaces the entry for the same ASIN only when `p.atMs >= existing.atMs`. */
    suspend fun putPendingPosition(p: PendingPosition)

    /** Removes only an entry equal to [p]. */
    suspend fun removePendingPosition(p: PendingPosition)
    suspend fun clockOffsetMs(): Long
    suspend fun saveClockOffsetMs(v: Long)

    /** Playback rate; 1f until saved. */
    suspend fun playbackSpeed(): Float
    suspend fun savePlaybackSpeed(rate: Float)
}
