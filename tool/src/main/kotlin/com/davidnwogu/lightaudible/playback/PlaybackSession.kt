package com.davidnwogu.lightaudible.playback

import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.sync.Downloads
import com.davidnwogu.lightaudible.sync.PositionSync
import com.davidnwogu.lightaudible.sync.PrepareOutcome
import com.davidnwogu.lightaudible.sync.PrepareWait
import com.davidnwogu.lightaudible.sync.Preparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Phase {
    data object Idle : Phase
    data object Preparing : Phase
    data object Connecting : Phase
    data object Ready : Phase
    data object Reconnecting : Phase
    data class Failed(val message: String) : Phase
    data object NeedsSignIn : Phase
    data object Unplayable : Phase
}

data class SessionState(
    val asin: String?,
    val title: String?,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val phase: Phase,
)

/** [positionMs] + [deltaMs] clamped to the book; floored at 0 only while the duration is unknown. */
fun skipTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    val target = positionMs + deltaMs
    return if (durationMs <= 0) target.coerceAtLeast(0) else target.coerceIn(0, durationMs)
}

/**
 * Process-wide owner of the one detached [Playback] handle. Keeps reporting positions after the
 * Player screen closes, and releases the handle (never stops it) once paused with no screen attached.
 *
 * Main-thread only: every method and every [Playback] call runs on [scope] (Main.immediate in production).
 * The [PlaybackFactory] comes from the calling screen and is dropped with the handle.
 */
class PlaybackSession(
    private val positions: PositionSync,
    private val preparer: Preparer,
    private val downloads: Downloads,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
) {
    private val _state = MutableStateFlow(SessionState(null, null, 0, 0, false, Phase.Idle))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var handle: Playback? = null
    private var factory: PlaybackFactory? = null
    private var book: LibraryBook? = null
    private var attached = false

    /** The asin whose item is loaded and whose positions are trusted; null while switching. */
    private var item: String? = null
    private var streamed = false
    private var lastPositionMs = 0L
    private var wasPlaying = false

    /** From a source error until playback runs again; position updates are ignored meanwhile. */
    private var recovering = false
    private var sourceErrors = 0
    private val urls = HashMap<String, Pair<String, Long>>() // asin -> (url, expiresAtMs)

    private var loading: Any? = null // token of the running load
    private var watchJob: Job? = null
    private var openJob: Job? = null
    private var recoverJob: Job? = null
    private var tickJob: Job? = null

    /** Plays [book], reusing the live detached item when it is the same book. Marks the screen attached. */
    fun open(book: LibraryBook, factory: PlaybackFactory) {
        attached = true
        if (this.book?.asin == book.asin && openJob?.isActive == true) return
        this.book = book
        this.factory = factory
        recoverJob?.cancel()
        recovering = false
        sourceErrors = 0
        _state.update {
            it.copy(asin = book.asin, title = book.title, durationMs = book.runtimeMs ?: 0, phase = Phase.Preparing)
        }
        load(book, reuseLive = true)
    }

    fun attach() {
        attached = true
    }

    fun detach() {
        attached = false
        if (handle == null) factory = null else maybeRelease()
    }

    fun togglePlay() {
        val p = handle ?: return
        if (p.isPlaying.value) p.pause() else p.play()
    }

    fun skip(deltaMs: Long) = seekTo(skipTarget(lastPositionMs, _state.value.durationMs, deltaMs))

    fun seekTo(ms: Long) {
        val p = handle ?: return
        if (item == null) return
        val target = ms.coerceAtLeast(0)
        p.seekTo(target)
        setPosition(target)
    }

    /** Runs preparation (and loading) again after a failure. */
    fun retry() {
        val b = book ?: return
        if (_state.value.phase !is Phase.Failed && _state.value.phase !is Phase.NeedsSignIn) return
        if (handle == null && factory == null) return
        recoverJob?.cancel()
        recovering = false
        sourceErrors = 0
        _state.update { it.copy(phase = Phase.Preparing) }
        load(b, reuseLive = false)
    }

    private fun load(book: LibraryBook, reuseLive: Boolean) {
        openJob?.cancel()
        val p = handle ?: openHandle() ?: return
        val token = Any()
        loading = token
        openJob = scope.launch {
            val loaded = try {
                loadInto(p, book, reuseLive)
            } finally {
                if (loading === token) loading = null
            }
            if (!loaded) maybeRelease()
        }
    }

    /** Returns false when the book could not be loaded (the phase says why). */
    private suspend fun loadInto(p: Playback, book: LibraryBook, reuseLive: Boolean): Boolean {
        if (!p.awaitReady()) {
            fail(PLAY_FAILED, releaseNow = true)
            return false
        }
        val asin = book.asin
        val live = p.currentItemId.value
        if (reuseLive && live == asin) {
            item = asin
            streamed = !downloads.isDownloaded(asin)
            setPosition(p.positionMs.value)
            p.play()
            _state.update { it.copy(phase = Phase.Ready) }
            p.error.value?.let(::onError)
            return true
        }
        val resumeAt = lastPositionMs.takeIf { live == asin && item == asin && it > 0 }
        if (live != null && live != asin) record(live, if (item == live) lastPositionMs else p.positionMs.value)
        item = null
        if (live != null && live != asin) p.pause()
        val source = sourceFor(asin, fresh = false) ?: return false
        val start = resumeAt ?: positions.resumePoint(asin)
        p.setItem(playbackItem(book, source), start)
        item = asin
        streamed = source is PlaybackSource.Url
        setPosition(start)
        p.play()
        _state.update { it.copy(phase = Phase.Ready) }
        return true
    }

    private fun openHandle(): Playback? {
        val p = runCatching { factory?.open() }.getOrNull()
            ?: return null.also { fail(PLAY_FAILED, releaseNow = false) }
        handle = p
        watchJob = scope.launch {
            launch { p.positionMs.collect { if (item != null && !recovering && p.error.value == null) setPosition(it) } }
            launch { p.durationMs.collect { d -> if (d > 0) _state.update { it.copy(durationMs = d) } } }
            launch { p.isPlaying.collect(::onPlaying) }
            launch { p.error.collect { e -> if (e != null && item != null && p.currentItemId.value == item) onError(e) } }
        }
        return p
    }

    private fun onPlaying(playing: Boolean) {
        _state.update { it.copy(isPlaying = playing) }
        if (playing) {
            wasPlaying = true
            if (item != null) {
                recovering = false
                sourceErrors = 0
                _state.update { it.copy(phase = Phase.Ready) }
            }
            if (tickJob?.isActive != true) {
                tickJob = scope.launch {
                    while (true) {
                        delay(REPORT_INTERVAL_MS)
                        item?.let { record(it, lastPositionMs) }
                    }
                }
            }
        } else if (wasPlaying) {
            wasPlaying = false
            tickJob?.cancel()
            if (!maybeRelease()) item?.let { record(it, lastPositionMs) }
        }
    }

    private fun onError(e: PlaybackError) {
        if (recoverJob?.isActive == true) return
        val asin = item ?: return
        val b = book?.takeIf { it.asin == asin } ?: return
        if (!e.isSource || !streamed) return fail(PLAY_FAILED, releaseNow = false)
        val p = handle ?: return
        recovering = true
        val wait = if (sourceErrors == 0) 0L else minOf(2_000L shl (sourceErrors - 1).coerceAtMost(5), 60_000L)
        sourceErrors++
        _state.update { it.copy(phase = Phase.Reconnecting) }
        recoverJob = scope.launch {
            delay(wait)
            val source = sourceFor(asin, fresh = true)
            if (source == null) {
                recovering = false
                maybeRelease()
                return@launch
            }
            p.replaceItem(playbackItem(b, source), lastPositionMs)
            p.play()
        }
    }

    /** A File for a downloaded book, else a URL valid for 10+ more minutes; null after setting a failure phase. */
    private suspend fun sourceFor(asin: String, fresh: Boolean): PlaybackSource? {
        if (downloads.isDownloaded(asin)) return PlaybackSource.File(downloads.file(asin))
        val cached = urls[asin]
        if (!fresh && cached != null && cached.second - nowMs() > URL_MARGIN_MS) return PlaybackSource.Url(cached.first)
        val outcome = preparer.readyUrl(asin) { wait ->
            if (!recovering) {
                val phase = if (wait == PrepareWait.Preparing) Phase.Preparing else Phase.Connecting
                _state.update { it.copy(phase = phase) }
            }
        }
        val phase = when (outcome) {
            is PrepareOutcome.Ready -> {
                urls[asin] = outcome.url to outcome.expiresAt.toEpochMilli()
                return PlaybackSource.Url(outcome.url)
            }
            is PrepareOutcome.Unplayable -> Phase.Unplayable
            PrepareOutcome.NeedsSignIn, PrepareOutcome.Unauthorized -> Phase.NeedsSignIn
            is PrepareOutcome.Failed, PrepareOutcome.NotFound -> Phase.Failed(PREPARE_FAILED)
        }
        _state.update { it.copy(phase = phase) }
        return null
    }

    private fun fail(message: String, releaseNow: Boolean) {
        recovering = false
        _state.update { it.copy(phase = Phase.Failed(message)) }
        if (releaseNow) release() else maybeRelease()
    }

    /** Releases when paused, unattached and idle. Returns true when it released. */
    private fun maybeRelease(): Boolean {
        val p = handle ?: return false
        if (attached || p.isPlaying.value || recovering || loading != null) return false
        release()
        return true
    }

    private fun release() {
        val p = handle ?: return
        item?.let { record(it, lastPositionMs) }
        listOf(watchJob, tickJob, recoverJob, openJob).forEach { it?.cancel() }
        loading = null
        handle = null
        factory = null
        item = null
        wasPlaying = false
        recovering = false
        sourceErrors = 0
        p.release()
        _state.update { it.copy(isPlaying = false, phase = if (it.phase is Phase.Ready) Phase.Idle else it.phase) }
    }

    private fun record(asin: String, positionMs: Long) {
        scope.launch { positions.record(asin, positionMs) }
    }

    private fun setPosition(ms: Long) {
        lastPositionMs = ms
        _state.update { it.copy(positionMs = ms) }
    }

    private fun playbackItem(book: LibraryBook, source: PlaybackSource) =
        PlaybackItem(book.asin, book.title, book.authors.joinToString(", ").ifEmpty { null }, source)

    private companion object {
        const val PREPARE_FAILED = "Couldn't prepare this book"
        const val PLAY_FAILED = "Couldn't play this book"
        const val REPORT_INTERVAL_MS = 60_000L
        const val URL_MARGIN_MS = 10 * 60_000L
    }
}
