package com.davidnwogu.lightaudible.fakes

import com.davidnwogu.lightaudible.playback.Playback
import com.davidnwogu.lightaudible.playback.PlaybackError
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.playback.PlaybackItem
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Records calls in [calls] and exposes its state as mutable flows.
 * `setItem`/`replaceItem` load the item (id, position, cleared error) like the real player;
 * `play` starts playback only while [playStarts] is true.
 */
class FakePlayback : Playback {
    sealed interface Call {
        data object AwaitReady : Call
        data class SetItem(val item: PlaybackItem, val startMs: Long) : Call
        data class ReplaceItem(val item: PlaybackItem, val startMs: Long) : Call
        data object Play : Call
        data object Pause : Call
        data class SeekTo(val ms: Long) : Call
        data object Release : Call
    }

    override val positionMs = MutableStateFlow(0L)
    override val durationMs = MutableStateFlow(0L)
    override val isPlaying = MutableStateFlow(false)
    override val currentItemId = MutableStateFlow<String?>(null)
    override val error = MutableStateFlow<PlaybackError?>(null)

    var ready = true
    var playStarts = true
    val calls = mutableListOf<Call>()
    val released get() = Call.Release in calls

    override suspend fun awaitReady(): Boolean {
        calls += Call.AwaitReady
        return ready
    }

    override fun setItem(item: PlaybackItem, startMs: Long) {
        calls += Call.SetItem(item, startMs)
        load(item, startMs)
    }

    override fun replaceItem(item: PlaybackItem, startMs: Long) {
        calls += Call.ReplaceItem(item, startMs)
        load(item, startMs)
    }

    private fun load(item: PlaybackItem, startMs: Long) {
        currentItemId.value = item.asin
        positionMs.value = startMs
        error.value = null
    }

    override fun play() {
        calls += Call.Play
        if (playStarts) isPlaying.value = true
    }

    override fun pause() {
        calls += Call.Pause
        isPlaying.value = false
    }

    override fun seekTo(ms: Long) {
        calls += Call.SeekTo(ms)
        positionMs.value = ms
    }

    override fun release() {
        calls += Call.Release
    }
}

/** Hands out [next] (a fresh [FakePlayback] by default) and counts `open()` calls. */
class FakePlaybackFactory(var next: () -> FakePlayback = { FakePlayback() }) : PlaybackFactory {
    val opened = mutableListOf<FakePlayback>()
    val openCount get() = opened.size
    val last get() = opened.last()

    override fun open(): Playback = next().also { opened += it }
}
