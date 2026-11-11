package com.davidnwogu.lightaudible.ui

import androidx.lifecycle.viewModelScope
import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.api.ServerApi
import com.davidnwogu.lightaudible.playback.Phase
import com.davidnwogu.lightaudible.playback.PlaybackControls
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The last chapter with `startMs <= positionMs`; null for an empty list. */
fun currentChapter(chapters: List<Chapter>, positionMs: Long): Chapter? =
    chapters.lastOrNull { it.startMs <= positionMs }

data class PlayerUiState(
    val title: String,
    val chapterTitle: String?,
    val position: String,
    val duration: String,
    val isPlaying: Boolean,
    val statusLine: String?,
    val chapters: List<Chapter>,
    val showRetry: Boolean,
    /** The current chapter's idx (titles may repeat); null without chapters. */
    val chapterIdx: Int? = null,
)

class PlayerViewModel(
    private val book: LibraryBook,
    private val session: PlaybackControls,
    private val api: ServerApi,
    private val factory: PlaybackFactory,
) : LightViewModel<Unit>() {
    private val chapters = MutableStateFlow<List<Chapter>>(emptyList())
    private var opened = false

    val state: StateFlow<PlayerUiState> = combine(session.state, chapters) { s, ch ->
        // The session may still hold another book (or none) until open() takes effect.
        val mine = s.asin == book.asin
        val positionMs = if (mine) s.positionMs else 0L
        val durationMs = if (mine && s.durationMs > 0) s.durationMs else book.runtimeMs ?: 0L
        val phase = if (mine) s.phase else Phase.Idle
        val chapter = currentChapter(ch, positionMs)
        PlayerUiState(
            title = book.title,
            chapterTitle = chapter?.title,
            chapterIdx = chapter?.idx,
            position = formatDuration(positionMs),
            duration = formatDuration(durationMs),
            isPlaying = mine && s.isPlaying,
            statusLine = statusLine(phase),
            chapters = ch,
            showRetry = phase is Phase.Failed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initialState())

    private fun initialState() = PlayerUiState(
        book.title, null, formatDuration(0), formatDuration(book.runtimeMs ?: 0), false, null, emptyList(), false,
    )

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = onShow()

    /** First show plays the book (open also attaches); later shows only re-attach. */
    fun onShow() {
        if (opened) {
            session.attach()
            return
        }
        opened = true
        session.open(book, factory)
        viewModelScope.launch {
            val r = api.book(book.asin)
            if (r is ApiResult.Ok) chapters.value = r.value.chapters.orEmpty()
        }
    }

    public override fun onCleared() {
        session.detach()
        super.onCleared()
    }

    fun togglePlay() = session.togglePlay(factory)
    fun back30() = session.skip(-30_000)
    fun forward30() = session.skip(30_000)
    fun jumpTo(chapter: Chapter) = session.seekTo(chapter.startMs)

    /** Runs preparation again; if the session already released its handle, plays the book afresh. */
    fun retry() {
        if (!session.retry()) session.open(book, factory)
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
