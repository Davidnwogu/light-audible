package com.davidnwogu.lightaudible.playback

import com.davidnwogu.lightaudible.api.LibraryBook
import kotlinx.coroutines.flow.StateFlow

/** What the Player screen needs from [PlaybackSession]; an interface so view-model tests can fake it. */
interface PlaybackControls {
    val state: StateFlow<SessionState>

    /** Plays [book] and marks a screen attached. */
    fun open(book: LibraryBook, factory: PlaybackFactory)
    fun attach()
    fun detach()
    fun togglePlay()
    fun skip(deltaMs: Long)
    fun seekTo(ms: Long)

    /** Re-runs preparation after a failure. Returns false when it could not act (e.g. the handle was released); then use [open]. */
    fun retry(): Boolean
}
