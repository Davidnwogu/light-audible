package com.davidnwogu.lightaudible.playback

import com.davidnwogu.lightaudible.api.LibraryBook
import kotlinx.coroutines.flow.StateFlow

/** What the Player screen needs from [PlaybackSession]; an interface so view-model tests can fake it. */
interface PlaybackControls {
    val state: StateFlow<SessionState>

    /** The book last passed to [open]; survives release, give-up and failure. Null until a book is opened. */
    val lastBook: StateFlow<LibraryBook?>

    /**
     * Plays [book] and marks a screen attached. With [startAtMs] it plays from there instead of the resume
     * point (or any carried position), seeking when the book is already live.
     */
    fun open(book: LibraryBook, factory: PlaybackFactory, startAtMs: Long? = null)
    fun attach()
    fun detach()

    /** Pauses or plays; with no handle, reopens [lastBook] through [factory] at its saved position. No-op if none. */
    fun togglePlay(factory: PlaybackFactory)
    fun skip(deltaMs: Long)
    fun seekTo(ms: Long)

    /** Re-runs preparation after a failure. Returns false when it could not act (e.g. the handle was released); then use [open]. */
    fun retry(): Boolean
}
