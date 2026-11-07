package com.davidnwogu.lightaudible.playback

import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaCache
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.LightPlayerSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Opens the process's detached player. Pass `LightPlaybackFactory(DefaultLightAudio(activity))`. */
class LightPlaybackFactory(private val audio: LightAudio) : PlaybackFactory {
    override fun open(): Playback = LightPlayback(audio.newPlayer(SPEC))

    companion object {
        val SPEC = LightPlayerSpec(
            usage = LightAudioUsage.Speech,
            playback = LightAudioPlayback.Detached,
            cache = LightMediaCache("stream", 512L * 1024 * 1024),
        )
    }
}

/**
 * [Playback] over a [LightAudioPlayer]. Main-thread only.
 *
 * - Both [setItem] and [replaceItem] load through `setMediaQueue`, because only that prepares the
 *   player (`replaceMediaItem` does not, so an item replaced after an error would sit idle). The queue
 *   holds one book and the id is the asin, so cached bytes are still reused.
 * - `LightAudioPlayer.seekTo` clamps to the resolved duration, which is 0 right after loading, so the
 *   start position is applied once the item reports a duration. Until then [positionMs] reads the
 *   requested start and `play()` is held back. The wait ends on a new player error (the session
 *   recovers) or after [SEEK_WAIT_MAX_MS], when it reports a [SEEK_TIMEOUT] source error instead of playing.
 * - [currentItemId] and [error] are refreshed straight from the player in [awaitReady] and after
 *   each load, so they never lag behind a collector.
 */
class LightPlayback(private val player: LightAudioPlayer) : Playback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _positionMs = MutableStateFlow(player.positionMs.value)
    private val _currentItemId = MutableStateFlow(liveItemId())
    private val _error = MutableStateFlow(player.error.value.toPlaybackError())
    private var pendingStart: Long? = null
    private var playWhenSeeked = false
    private var seekJob: Job? = null

    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    override val durationMs: StateFlow<Long> = player.durationMs
    override val isPlaying: StateFlow<Boolean> = player.isPlaying
    override val currentItemId: StateFlow<String?> = _currentItemId.asStateFlow()
    override val error: StateFlow<PlaybackError?> = _error.asStateFlow()

    init {
        scope.launch { player.positionMs.collect { if (pendingStart == null) _positionMs.value = it } }
        scope.launch { player.queue.collect { _currentItemId.value = liveItemId() } }
        scope.launch { player.currentMediaItemIndex.collect { _currentItemId.value = liveItemId() } }
        scope.launch { player.error.collect { _error.value = it.toPlaybackError() } }
    }

    private fun liveItemId() = player.queue.value.getOrNull(player.currentMediaItemIndex.value)?.id

    private fun refresh() {
        _currentItemId.value = liveItemId()
        _error.value = player.error.value.toPlaybackError()
    }

    override suspend fun awaitReady(): Boolean = player.awaitReady().also { refresh() }

    override fun setItem(item: PlaybackItem, startMs: Long) = load(item, startMs)

    override fun replaceItem(item: PlaybackItem, startMs: Long) = load(item, startMs)

    private fun load(item: PlaybackItem, startMs: Long) {
        startAt(startMs) { player.setMediaQueue(listOf(item.toLight())) }
        refresh()
    }

    /** Runs [change], then seeks to [startMs] as soon as the player accepts a seek that far. */
    private fun startAt(startMs: Long, change: () -> Unit) {
        seekJob?.cancel()
        pendingStart = startMs
        _positionMs.value = startMs
        change()
        seekJob = scope.launch {
            // true: landed; false: a new error ended the wait; null: timed out.
            val landed = withTimeoutOrNull(SEEK_WAIT_MAX_MS) {
                merge(
                    player.durationMs.map { true },
                    ticker(), // a stale duration that never re-emits
                    player.error.drop(1).filterNotNull().map { false },
                ).first { ok -> !ok || (player.durationMs.value > 0 && trySeek(startMs)) }
            }
            if (landed == null) {
                // Never seeked: stay paused and keep reporting the start (pendingStart stays set), and
                // report a source error so the session reloads at its saved position.
                playWhenSeeked = false
                _error.value = PlaybackError(isSource = true, diagnostic = SEEK_TIMEOUT)
                return@launch
            }
            pendingStart = null
            if (landed) _positionMs.value = player.positionMs.value
            if (playWhenSeeked && landed) player.play()
            playWhenSeeked = false
        }
    }

    private fun trySeek(startMs: Long): Boolean {
        player.seekTo(startMs)
        return player.positionMs.value + SEEK_TOLERANCE_MS >= minOf(startMs, player.durationMs.value)
    }

    private fun ticker() = flow {
        while (true) {
            delay(SEEK_RETRY_MS)
            emit(true)
        }
    }

    override fun play() {
        if (pendingStart != null) playWhenSeeked = true else player.play()
    }

    override fun pause() {
        playWhenSeeked = false
        player.pause()
    }

    override fun seekTo(ms: Long) {
        if (pendingStart != null) startAt(ms) {} else player.seekTo(ms)
    }

    override fun release() {
        scope.cancel()
        player.release()
    }

    private fun LightAudioError?.toPlaybackError() =
        this?.let { PlaybackError(isSource = it.kind == LightAudioErrorKind.Source, diagnostic = it.diagnostic) }

    private fun PlaybackItem.toLight() = LightAudioItem(
        id = asin,
        source = when (source) {
            is PlaybackSource.File -> LightAudioSource.FileSource(source.file)
            is PlaybackSource.Url -> LightAudioSource.UrlSource(source.url)
        },
        metadata = LightMediaMetadata(title = title, artist = author),
        usage = LightAudioUsage.Speech,
    )

    private companion object {
        const val SEEK_TOLERANCE_MS = 1_000L
        const val SEEK_RETRY_MS = 2_000L
        const val SEEK_WAIT_MAX_MS = 60_000L
    }
}
