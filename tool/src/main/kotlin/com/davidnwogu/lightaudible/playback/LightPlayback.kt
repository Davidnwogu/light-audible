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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
 * `LightAudioPlayer.seekTo` clamps to the resolved duration, which is 0 right after a queue change,
 * so a start position is applied once the new item reports a duration. Until then [positionMs]
 * reads the requested start and `play()` is held back, so playback never starts from 0 by accident.
 */
class LightPlayback(private val player: LightAudioPlayer) : Playback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _positionMs = MutableStateFlow(player.positionMs.value)
    private var pendingStart: Long? = null
    private var playWhenSeeked = false
    private var seekJob: Job? = null

    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    override val durationMs: StateFlow<Long> = player.durationMs
    override val isPlaying: StateFlow<Boolean> = player.isPlaying
    override val currentItemId: StateFlow<String?> =
        combine(player.queue, player.currentMediaItemIndex) { q, i -> q.getOrNull(i)?.id }
            .stateIn(scope, SharingStarted.Eagerly, player.queue.value.getOrNull(player.currentMediaItemIndex.value)?.id)
    override val error: StateFlow<PlaybackError?> =
        player.error.map { it.toPlaybackError() }.stateIn(scope, SharingStarted.Eagerly, player.error.value.toPlaybackError())

    init {
        scope.launch { player.positionMs.collect { if (pendingStart == null) _positionMs.value = it } }
    }

    override suspend fun awaitReady(): Boolean = player.awaitReady()

    override fun setItem(item: PlaybackItem, startMs: Long) {
        startAt(startMs) { player.setMediaQueue(listOf(item.toLight())) }
    }

    override fun replaceItem(item: PlaybackItem, startMs: Long) {
        val index = player.currentMediaItemIndex.value.coerceAtLeast(0)
        startAt(startMs) { player.replaceMediaItem(index, item.toLight()) }
    }

    /** Runs [change], then seeks to [startMs] as soon as the player accepts a seek that far. */
    private fun startAt(startMs: Long, change: () -> Unit) {
        seekJob?.cancel()
        pendingStart = startMs
        _positionMs.value = startMs
        change()
        seekJob = scope.launch {
            // Each new duration retries; the slow timer covers a stale duration that never re-emits.
            merge(player.durationMs, ticker()).first { player.durationMs.value > 0 && trySeek(startMs) }
            pendingStart = null
            _positionMs.value = player.positionMs.value
            if (playWhenSeeked) player.play()
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
            emit(0L)
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
        if (pendingStart != null) {
            startAt(ms) {}
        } else {
            player.seekTo(ms)
        }
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
    }
}
