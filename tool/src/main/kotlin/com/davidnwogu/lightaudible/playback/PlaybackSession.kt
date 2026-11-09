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
) : PlaybackControls {
    private val _state = MutableStateFlow(SessionState(null, null, 0, 0, false, Phase.Idle))
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private var handle: Playback? = null
    private var factory: PlaybackFactory? = null
    private var book: LibraryBook? = null
    private var attached = false

    /** The asin whose item is loaded and whose positions are trusted; null while switching. */
    private var item: String? = null
    private var streamed = false
    private var lastPositionMs = 0L
    private var wasPlaying = false
    private var playPending = false // play() asked for, isPlaying not yet true
    /** The loaded item has played since its last setItem/replaceItem, so the player has reached its start. */
    private var startedSinceLoad = false
    /**
     * (asin, position) of an item released before it ever played: its start seek may have died with the handle.
     * Kept until a load of any book reaches the player (setItem or the reuse path), so an interrupted load keeps it.
     */
    private var unstarted: Pair<String, Long>? = null

    /** While the recovery loop owns the item; position updates are ignored meanwhile. */
    private var recovering = false
    /** The user paused during recovery: the current error is theirs to retry (by play or open). */
    private var errorAcked = false
    /** The running recovery has lasted [DETACHED_RECOVERY_MAX_MS]: it gives up whenever no screen is attached. */
    private var recoveryOverdue = false
    private val urls = HashMap<String, Pair<String, Long>>() // asin -> (url, expiresAtMs)

    private var loading: Any? = null // token of the running load
    private var watchJob: Job? = null
    private var openJob: Job? = null
    private var recoverJob: Job? = null
    private var tickJob: Job? = null
    private var checkJob: Job? = null
    private var giveUpJob: Job? = null

    /**
     * The Book screen's Play: always plays [book], reusing the live detached item when it is the same
     * book, and marks a screen attached. A Player screen shown again must call [attach], not this.
     */
    override fun open(book: LibraryBook, factory: PlaybackFactory) {
        attached = true
        val newFactory = factory !== this.factory
        if (!newFactory && this.book?.asin == book.asin && openJob?.isActive == true) return
        // A handle opened through another (possibly finished) screen's factory is never reused: release it
        // (detached playback keeps going; the real position is recorded if an item is loaded) and open a
        // fresh one, whose live queue the reuse path below then keeps -- unless the released handle never
        // played the item since loading it and the live player is still behind where the session had it
        // (its start seek died with the handle): then the fresh handle reloads it at that position.
        if (newFactory && handle != null) release()
        val carryStartMs = unstarted?.takeIf { it.first == book.asin }?.second
        val switching = this.book?.asin != book.asin
        this.book = book
        this.factory = factory
        errorAcked = false
        stopRecovery()
        _state.update {
            if (!switching) it.copy(phase = Phase.Preparing)
            else SessionState(book.asin, book.title, 0, book.runtimeMs ?: 0, it.isPlaying, Phase.Preparing)
        }
        load(book, reuseLive = true, carryStartMs = carryStartMs)
    }

    override fun attach() { attached = true }

    override fun detach() {
        attached = false
        val p = handle ?: return run { factory = null }
        if (recovering && recoveryOverdue) return giveUp()
        // Playing: report now (the pause or release reports again later). Paused: the release reports.
        if (p.isPlaying.value) item?.let { record(it, lastPositionMs) }
        if (!p.isPlaying.value && !maybeRelease()) scheduleCheck(if (playPending) PLAY_START_GRACE_MS else GRACE_MS)
    }

    override fun togglePlay() {
        val p = handle ?: return
        val e = p.error.value
        if (recovering) {
            stopRecovery()
            errorAcked = true
            _state.update { it.copy(phase = Phase.Ready) }
            pause(p)
            item?.let { record(it, lastPositionMs) }
        } else if (p.isPlaying.value || playPending) {
            pause(p)
        } else if (e != null && item != null && !terminal()) {
            errorAcked = false
            onError(e) // reload from a fresh source rather than replaying the failed one
        } else {
            requestPlay(p)
        }
    }

    override fun skip(deltaMs: Long) = seekTo(skipTarget(lastPositionMs, _state.value.durationMs, deltaMs))

    override fun seekTo(ms: Long) {
        val p = handle ?: return
        if (item == null) return
        val target = ms.coerceAtLeast(0)
        p.seekTo(target)
        setPosition(target)
    }

    /** Runs preparation (and loading) again after a failure. */
    override fun retry(): Boolean {
        val b = book ?: return false
        if (_state.value.phase !is Phase.Failed && _state.value.phase !is Phase.NeedsSignIn) return false
        if (handle == null && factory == null) return false
        stopRecovery()
        _state.update { it.copy(phase = Phase.Preparing) }
        load(b, reuseLive = false)
        return true
    }

    private fun load(book: LibraryBook, reuseLive: Boolean, carryStartMs: Long? = null) {
        openJob?.cancel()
        val p = handle ?: openHandle() ?: return
        val token = Any()
        loading = token
        openJob = scope.launch {
            val loaded = try {
                loadInto(p, book, reuseLive, carryStartMs)
            } finally {
                if (loading === token) loading = null
            }
            if (loaded) scheduleCheck(PLAY_START_GRACE_MS) else maybeRelease()
        }
    }

    /**
     * Returns false when the book could not be loaded (the phase says why). [carryStartMs] is a position carried
     * from a handle released before its item played: it overrides the live item only while that is paused behind it.
     */
    private suspend fun loadInto(p: Playback, book: LibraryBook, reuseLive: Boolean, carryStartMs: Long?): Boolean {
        if (!p.awaitReady()) {
            fail(PLAY_FAILED, releaseNow = true)
            return false
        }
        val asin = book.asin
        val live = p.currentItemId.value
        val liveBehindCarry = carryStartMs != null && !p.isPlaying.value &&
            p.positionMs.value + CARRY_TOLERANCE_MS < carryStartMs
        if (reuseLive && live == asin && !liveBehindCarry) {
            unstarted = null
            if (p.isPlaying.value) startedSinceLoad = true
            item = asin
            streamed = !downloads.isDownloaded(asin)
            setPosition(p.positionMs.value)
            _state.update { it.copy(phase = Phase.Ready) }
            val e = p.error.value
            if (e != null) onError(e) else requestPlay(p)
            return true
        }
        val resumeAt = lastPositionMs.takeIf { live == asin && item == asin && it > 0 }
        if (live != null && live != asin) record(live, if (item == live) lastPositionMs else p.positionMs.value)
        item = null
        if (live != null && live != asin) pause(p)
        val source = sourceFor(asin, fresh = false) ?: return false
        val start = carryStartMs ?: resumeAt ?: positions.resumePoint(asin)
        startedSinceLoad = false
        unstarted = null
        p.setItem(playbackItem(book, source), start)
        item = asin
        streamed = source is PlaybackSource.Url
        setPosition(start)
        requestPlay(p)
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
            playPending = false
            checkJob?.cancel()
            startedSinceLoad = true // also a live item this handle has not adopted yet
            if (item != null) {
                stopRecovery()
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
            item?.let { record(it, lastPositionMs) }
            // An error may land just after the pause; the check recovers instead of releasing.
            scheduleCheck(GRACE_MS, recordOnRelease = false)
        }
    }

    /** Starts the recovery loop for a streamed item's source error; anything else fails. */
    private fun onError(e: PlaybackError) {
        if (recovering || errorAcked) return
        val asin = item ?: return
        val b = book?.takeIf { it.asin == asin } ?: return
        val p = handle ?: return
        if (!e.isSource || !streamed) return fail(PLAY_FAILED, releaseNow = false)
        recovering = true
        checkJob?.cancel()
        _state.update { it.copy(phase = Phase.Reconnecting) }
        recoveryOverdue = false
        giveUpJob?.cancel()
        giveUpJob = scope.launch {
            delay(DETACHED_RECOVERY_MAX_MS)
            recoveryOverdue = true
            if (!attached) giveUp()
        }
        // Owns the item until it plays (onPlaying cancels this). Polls the error rather than waiting
        // for a new emission, so an identical repeated error is never conflated away.
        recoverJob = scope.launch {
            var attempt = 0
            while (true) {
                if (attempt > 0) delay(minOf(2_000L shl (attempt - 1).coerceAtMost(5), 60_000L))
                val source = sourceFor(asin, fresh = true) ?: return@launch endRecovery()
                startedSinceLoad = false
                p.replaceItem(playbackItem(b, source), lastPositionMs)
                requestPlay(p)
                attempt++
                if (!attemptFailed(p)) {
                    _state.update { it.copy(phase = Phase.Ready) } // neither plays nor fails: stop owning it
                    return@launch endRecovery()
                }
            }
        }
    }

    /** True when the attempt ends in an error; false when it neither plays nor fails for a while. */
    private suspend fun attemptFailed(p: Playback): Boolean {
        repeat((ATTEMPT_MAX_MS / ATTEMPT_CHECK_MS).toInt()) {
            delay(ATTEMPT_CHECK_MS)
            if (p.error.value != null && !p.isPlaying.value) return true
        }
        return false
    }

    /**
     * Ends a recovery that outlasted [DETACHED_RECOVERY_MAX_MS] with no screen attached: records the last
     * known position, fails, and releases the handle paused, so a network that returns later never starts audio.
     */
    private fun giveUp() {
        if (!recovering) return
        val p = handle ?: return
        stopRecovery()
        pause(p)
        fail(PLAY_FAILED, releaseNow = true) // release() records the position
    }

    private fun endRecovery() {
        recovering = false
        playPending = false
        scheduleCheck(0)
    }

    private fun stopRecovery() = recoverJob?.cancel().also { recovering = false }

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
        playPending = false
        _state.update { it.copy(phase = Phase.Failed(message)) }
        if (releaseNow) release() else maybeRelease()
    }

    /** After [afterMs]: recover a pending error the collector missed, else release if idle. */
    private fun scheduleCheck(afterMs: Long, recordOnRelease: Boolean = true) {
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(afterMs)
            val p = handle ?: return@launch
            if (p.isPlaying.value || recovering) return@launch
            val e = p.error.value
            if (e != null && !errorAcked && !terminal()) onError(e) else maybeRelease(recordOnRelease, waitForPlay = false)
        }
    }

    private fun terminal() = _state.value.phase.let { it is Phase.Failed || it is Phase.NeedsSignIn || it is Phase.Unplayable }

    /** Releases when paused, unattached and idle. Returns true when it released. */
    private fun maybeRelease(record: Boolean = true, waitForPlay: Boolean = true): Boolean {
        val p = handle ?: return false
        if (attached || p.isPlaying.value || recovering || loading != null) return false
        if (waitForPlay && playPending) return false
        if (p.error.value != null && item != null && !errorAcked && !terminal()) return false // recovery comes first
        release(record)
        return true
    }

    private fun release(record: Boolean = true) {
        val p = handle ?: return
        if (record) item?.let { record(it, lastPositionMs) }
        // No item: a load was interrupted before reaching the player, so a pending carry stays as it was.
        if (item != null) unstarted = item?.takeIf { !startedSinceLoad }?.let { it to lastPositionMs }
        listOf(watchJob, tickJob, recoverJob, openJob, checkJob, giveUpJob).forEach { it?.cancel() }
        loading = null
        handle = null
        factory = null
        item = null
        wasPlaying = false
        playPending = false
        recovering = false
        errorAcked = false
        p.release()
        _state.update { it.copy(isPlaying = false, phase = if (it.phase is Phase.Ready) Phase.Idle else it.phase) }
    }

    private fun requestPlay(p: Playback) {
        playPending = true // before play(): isPlaying may turn true inside it
        errorAcked = false
        p.play()
    }

    private fun pause(p: Playback) = p.pause().also { playPending = false }

    private fun record(asin: String, positionMs: Long) = scope.launch { positions.record(asin, positionMs) }

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
        const val GRACE_MS = 1_000L
        /** A live position this close to a carried one already reached it. */
        const val CARRY_TOLERANCE_MS = 1_000L
        const val PLAY_START_GRACE_MS = 30_000L
        const val ATTEMPT_CHECK_MS = 1_000L
        const val ATTEMPT_MAX_MS = 30_000L
        const val DETACHED_RECOVERY_MAX_MS = 15 * 60_000L
    }
}
