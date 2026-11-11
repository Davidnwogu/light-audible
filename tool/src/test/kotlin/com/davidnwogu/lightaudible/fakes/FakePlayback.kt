package com.davidnwogu.lightaudible.fakes

import com.davidnwogu.lightaudible.playback.Playback
import com.davidnwogu.lightaudible.playback.PlaybackError
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.playback.PlaybackItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Records calls in [calls] and models the [Playback] contract:
 * - `setItem`/`replaceItem` load the item (id set, playback stopped) and report the requested start
 *   as the position; they do not clear [error].
 * - `play()` only asks to play. Playback starts when the load is prepared: right away while
 *   [autoPrepare] is true, otherwise when the test calls [prepareSucceeds]. [prepareFails] leaves
 *   an error that may be identical to the last one, so it does not re-emit.
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
    /**
     * When set, [positionMs] is stale until [awaitReady], which makes this the current value, as a handle
     * reads the connected player's position only on connect.
     */
    var positionOnReady: Long? = null
    /** When set, [awaitReady] suspends until the test completes it (its value is then the result). */
    var readyGate: CompletableDeferred<Boolean>? = null
    var autoPrepare = true
    private var playRequested = false
    val calls = mutableListOf<Call>()
    val released get() = Call.Release in calls

    override suspend fun awaitReady(): Boolean {
        calls += Call.AwaitReady
        val ok = readyGate?.await() ?: ready
        positionOnReady?.let { positionMs.value = it }
        return ok
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
        isPlaying.value = false
        positionMs.value = startMs
        playRequested = false
    }

    override fun play() {
        calls += Call.Play
        playRequested = true
        if (autoPrepare) prepareSucceeds()
    }

    /** The load prepared: the error clears and a requested play starts. */
    fun prepareSucceeds() {
        error.value = null
        if (playRequested) isPlaying.value = true
    }

    /** The load failed again with [e]. */
    fun prepareFails(e: PlaybackError) {
        isPlaying.value = false
        error.value = e
    }

    override fun pause() {
        calls += Call.Pause
        playRequested = false
        isPlaying.value = false
    }

    override fun seekTo(ms: Long) {
        calls += Call.SeekTo(ms)
        positionMs.value = ms
    }

    /** The last rate passed to [setSpeed], 1f until then. Not in [calls], so call-list assertions are unaffected. */
    var lastSpeed = 1f
    val speeds = mutableListOf<Float>()

    override fun setSpeed(rate: Float) {
        lastSpeed = rate
        speeds += rate
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
