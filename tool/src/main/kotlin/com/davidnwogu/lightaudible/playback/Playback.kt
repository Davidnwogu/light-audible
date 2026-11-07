package com.davidnwogu.lightaudible.playback

import kotlinx.coroutines.flow.StateFlow

sealed interface PlaybackSource {
    data class File(val file: java.io.File) : PlaybackSource
    data class Url(val url: String) : PlaybackSource
}

/** One book in the player queue; [asin] is the item id and cache key. */
data class PlaybackItem(val asin: String, val title: String, val author: String?, val source: PlaybackSource)

/** [isSource] is true for network or file I/O failures, the only kind a fresh URL can fix. */
data class PlaybackError(val isSource: Boolean, val diagnostic: String)

/**
 * The one detached audio player handle, behind an interface so JVM tests can fake it.
 * Every call must be made on the main thread.
 */
interface Playback {
    /** After [setItem]/[replaceItem], reads the requested start until the player has seeked there. */
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val currentItemId: StateFlow<String?>
    val error: StateFlow<PlaybackError?>

    /** Waits for the connection; false when the handle was refused or released. */
    suspend fun awaitReady(): Boolean

    /** Replaces the queue with [item] and starts it at [startMs]. */
    fun setItem(item: PlaybackItem, startMs: Long)

    /** Swaps the current item's source (same id, so cached bytes are reused) and resumes at [startMs]. */
    fun replaceItem(item: PlaybackItem, startMs: Long)

    fun play()
    fun pause()
    fun seekTo(ms: Long)

    /** Releases this handle; detached playback keeps going. */
    fun release()
}

fun interface PlaybackFactory {
    fun open(): Playback
}
