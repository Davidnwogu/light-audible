package com.davidnwogu.lightaudible.ui

import androidx.lifecycle.viewModelScope
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.playback.PlaybackControls
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.sync.ChapterCache
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChapterRow(val chapter: Chapter, val length: String, val isCurrent: Boolean)

/** Chapter list for [book]; the current row follows the session when it holds [book], else [previewPositionMs]. */
class ChaptersViewModel(
    private val book: LibraryBook,
    private val session: PlaybackControls,
    private val chapterCache: ChapterCache,
    private val factory: PlaybackFactory,
    private val previewPositionMs: Long,
) : LightViewModel<Unit>() {
    private val chapters = MutableStateFlow(chapterCache.cached(book.asin).orEmpty())

    val rows: StateFlow<List<ChapterRow>> =
        combine(chapters, session.state, session.lastBook) { list, s, last ->
            val position = if (last?.asin == book.asin) s.positionMs else previewPositionMs
            build(list, position)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, build(chapters.value, positionNow()))

    init {
        if (chapters.value.isEmpty()) {
            viewModelScope.launch { chapters.value = chapterCache.get(book.asin) }
        }
    }

    private fun isLoaded() = session.lastBook.value?.asin == book.asin

    private fun positionNow() = if (isLoaded()) session.state.value.positionMs else previewPositionMs

    private fun build(list: List<Chapter>, positionMs: Long): List<ChapterRow> {
        val current = currentChapter(list, positionMs)
        return list.map { ChapterRow(it, formatDuration(it.lengthMs), it == current) }
    }

    /** Loaded book: seeks to the chapter. Otherwise opens the book there; whatever else is loaded is replaced. */
    fun select(chapter: Chapter) {
        if (isLoaded()) session.seekTo(chapter.startMs)
        else session.open(book, factory, startAtMs = chapter.startMs)
    }
}
