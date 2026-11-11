package com.davidnwogu.lightaudible.fakes

import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.playback.PlaybackControls
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.playback.SessionState
import kotlinx.coroutines.flow.MutableStateFlow

/** Recording [PlaybackControls] whose state and lastBook tests set directly. */
class FakeControls(initial: SessionState) : PlaybackControls {
    override val state = MutableStateFlow(initial)
    val calls = mutableListOf<String>()
    var retryResult = true

    override val lastBook = MutableStateFlow<LibraryBook?>(null)

    override fun open(book: LibraryBook, factory: PlaybackFactory, startAtMs: Long?) { calls += "open ${book.asin}" }
    override fun attach() { calls += "attach" }
    override fun detach() { calls += "detach" }
    override fun togglePlay(factory: PlaybackFactory) { calls += "toggle" }
    override fun skip(deltaMs: Long) { calls += "skip $deltaMs" }
    override fun seekTo(ms: Long) { calls += "seek $ms" }
    override fun retry(): Boolean { calls += "retry"; return retryResult }
}
