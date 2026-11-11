package com.davidnwogu.lightaudible.ui

import androidx.lifecycle.viewModelScope
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.playback.Phase
import com.davidnwogu.lightaudible.playback.PlaybackControls
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.playback.SessionState
import com.davidnwogu.lightaudible.playback.SpeedSetting
import com.davidnwogu.lightaudible.sync.ChapterCache
import com.davidnwogu.lightaudible.sync.Downloads
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DownloadControl { NotDownloaded, Downloading, Downloaded }

data class PlayerUiState(
    val title: String,
    /** The current chapter, or the status text while something is in progress or has failed. */
    val chapterLine: String?,
    val showRetry: Boolean,
    val byline: String,
    val progress: Float,
    val position: String,
    val isPlaying: Boolean,
    /** Play/pause enabled. */
    val controlsEnabled: Boolean,
    /** Skips and the progress bar enabled; false until the book is loaded. */
    val skipsEnabled: Boolean,
    /** Not playing yet: the session holds another book (or none). */
    val isPreview: Boolean,
    val download: DownloadControl,
    val downloadPercent: Int?,
    val speedLabel: String,
)

fun downloadPercent(progress: Float): Int = (progress * 100).roundToInt()

/** "1x", "1.25x", "1.5x", "2x". */
fun speedLabel(rate: Float): String =
    if (rate == rate.toInt().toFloat()) "${rate.toInt()}x" else "${rate}x"

/**
 * The Player for [book]. While the session holds another book (or none) this is a preview: nothing loads and no
 * session call happens until ▶, which opens the book. For the loaded book it mirrors the session.
 */
class PlayerViewModel(
    private val book: LibraryBook,
    private val session: PlaybackControls,
    private val chapterCache: ChapterCache,
    private val speed: SpeedSetting,
    private val downloads: Downloads,
    private val resumePoint: suspend (String) -> Long,
    private val factory: PlaybackFactory,
) : LightViewModel<Unit>() {
    private val chapters = MutableStateFlow(chapterCache.cached(book.asin).orEmpty())

    /** Where ▶ resumes in preview; 0 until known or when unavailable. */
    private val resumeMs = MutableStateFlow(0L)
    private val downloadFailure = MutableStateFlow<String?>(null)

    /** Bumped after a removal so the file check reruns. */
    private val downloadedTick = MutableStateFlow(0)
    private var attached = false
    private var resumeRequested = false

    private data class Local(val chapters: List<Chapter>, val resumeMs: Long, val failure: String?, val speed: Float)

    private val local = combine(chapters, resumeMs, downloadFailure, speed.rate) { c, r, f, s -> Local(c, r, f, s) }

    val state: StateFlow<PlayerUiState> =
        combine(session.state, session.lastBook, local, downloads.progress, downloadedTick) { s, last, l, progress, _ ->
            build(s, last?.asin == book.asin, l, progress[book.asin])
        }.stateIn(viewModelScope, SharingStarted.Eagerly, build(session.state.value, isLoaded(), currentLocal(), downloads.progress.value[book.asin]))

    /** The position the Chapters screen marks while previewing. */
    val previewPositionMs: Long get() = resumeMs.value

    init {
        viewModelScope.launch {
            downloads.failures.collect { (asin, message) -> if (asin == book.asin) downloadFailure.value = message }
        }
    }

    private fun isLoaded() = session.lastBook.value?.asin == book.asin

    private fun currentLocal() = Local(chapters.value, resumeMs.value, downloadFailure.value, speed.rate.value)

    private fun build(s: SessionState, loaded: Boolean, l: Local, dlProgress: Float?): PlayerUiState {
        val runtime = book.runtimeMs ?: 0L
        val durationMs = if (loaded && s.durationMs > 0) s.durationMs else runtime
        val positionMs = if (loaded) s.positionMs else l.resumeMs
        val status = if (loaded) statusLine(s.phase) else null
        val busy = status != null
        val download = downloadControl(dlProgress)
        return PlayerUiState(
            title = book.title,
            chapterLine = status ?: l.failure ?: currentChapter(l.chapters, positionMs)?.title,
            showRetry = loaded && s.phase is Phase.Failed,
            byline = listOf(formatAuthors(book.authors), formatRuntime(book.runtimeMs))
                .filter { it.isNotEmpty() }.joinToString(" · "),
            progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            position = formatDuration(positionMs),
            isPlaying = loaded && s.isPlaying,
            controlsEnabled = !busy,
            skipsEnabled = loaded && !busy,
            isPreview = !loaded,
            download = download,
            downloadPercent = dlProgress?.let(::downloadPercent),
            speedLabel = speedLabel(l.speed),
        )
    }

    private fun downloadControl(progress: Float?) = when {
        progress != null -> DownloadControl.Downloading
        downloads.isDownloaded(book.asin) -> DownloadControl.Downloaded
        else -> DownloadControl.NotDownloaded
    }

    /** Read from the session directly, so a tap never acts on a stale [state]. */
    private fun canSkip() = isLoaded() && statusLine(session.state.value.phase) == null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = onShow()

    /** Attaches only to the book the session holds; a preview fetches its resume point instead. Never opens. */
    fun onShow() {
        if (isLoaded()) {
            session.attach()
            attached = true
        } else if (!resumeRequested) {
            resumeRequested = true
            viewModelScope.launch {
                resumeMs.value = try {
                    resumePoint(book.asin)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    0L
                }
            }
        }
        if (chapters.value.isEmpty()) {
            viewModelScope.launch { chapterCache.get(book.asin).takeIf { it.isNotEmpty() }?.let { chapters.value = it } }
        }
    }

    public override fun onCleared() {
        if (attached) session.detach()
        super.onCleared()
    }

    /** Preview: opens the book (it resumes at its saved position). Loaded: pauses or plays, reopening if released. */
    fun playPause() {
        if (isLoaded()) {
            session.togglePlay(factory)
        } else {
            session.open(book, factory)
            attached = true
        }
    }

    fun back15() {
        if (canSkip()) session.skip(-15_000)
    }

    fun forward15() {
        if (canSkip()) session.skip(15_000)
    }

    fun seekToFraction(f: Float) {
        if (!canSkip()) return
        val s = session.state.value
        val durationMs = if (s.durationMs > 0) s.durationMs else book.runtimeMs ?: return
        session.seekTo((f.coerceIn(0f, 1f) * durationMs).toLong())
    }

    /** Runs preparation again; if the session can't act (handle released), opens the book afresh. */
    fun retry() {
        if (!session.retry()) {
            session.open(book, factory)
            attached = true
        }
    }

    fun cycleSpeed() = speed.cycle()

    /**
     * Not downloaded: starts a download. Downloading: cancels it. Downloaded: returns true, so the screen asks
     * "Remove download?". Any shown download failure clears.
     */
    fun downloadTapped(): Boolean {
        downloadFailure.value = null
        return when (downloadControl(downloads.progress.value[book.asin])) {
            DownloadControl.NotDownloaded -> {
                downloads.start(book.asin)
                false
            }
            DownloadControl.Downloading -> {
                downloads.cancel(book.asin)
                false
            }
            DownloadControl.Downloaded -> true
        }
    }

    fun removeDownload() {
        downloads.remove(book.asin)
        downloadedTick.value++
    }

    private fun statusLine(phase: Phase): String? = when (phase) {
        Phase.Preparing -> "Preparing…"
        Phase.Connecting -> "Connecting…"
        Phase.Reconnecting -> "Reconnecting…"
        is Phase.Failed -> phase.message
        Phase.NeedsSignIn -> "Sign in again on your setup page"
        Phase.Unplayable -> "This book can't be played"
        Phase.Idle, Phase.Ready -> null
    }
}
