package com.davidnwogu.lightaudible.ui

import androidx.lifecycle.viewModelScope
import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.api.ServerApi
import com.davidnwogu.lightaudible.sync.Downloads
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val STATUS_FAILED = "Couldn't prepare this book"
const val STATUS_PREPARING = "Preparing…"

data class BookUiState(
    val book: LibraryBook,
    val chapters: List<Chapter> = emptyList(),
    val statusLine: String? = null,
    val downloaded: Boolean = false,
    val downloadProgress: Float? = null,
)

class BookViewModel(
    private val book: LibraryBook,
    private val api: ServerApi,
    private val downloads: Downloads,
) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow(BookUiState(book, downloaded = downloads.isDownloaded(book.asin)))
    val state: StateFlow<BookUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var watchJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = onShow()

    fun onShow() {
        if (watchJob?.isActive != true) {
            watchJob = viewModelScope.launch {
                downloads.progress.collect { map ->
                    _state.value = _state.value.copy(
                        downloaded = downloads.isDownloaded(book.asin),
                        downloadProgress = map[book.asin],
                    )
                }
            }
        }
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val line = when (val r = api.book(book.asin)) {
                is ApiResult.Ok -> {
                    r.value.chapters?.let { _state.value = _state.value.copy(chapters = it) }
                    when (r.value.status) {
                        BookStatus.Failed -> STATUS_FAILED
                        BookStatus.Preparing, BookStatus.NotReady -> STATUS_PREPARING
                        else -> null
                    }
                }
                ApiResult.NeedsSignIn, ApiResult.Unauthorized -> BANNER_SIGN_IN
                ApiResult.NotFound -> null
                else -> BANNER_CONNECTING
            }
            _state.value = _state.value.copy(statusLine = line, downloaded = downloads.isDownloaded(book.asin))
        }
    }

    fun download() = downloads.start(book.asin)

    fun removeDownload() {
        downloads.remove(book.asin)
        _state.value = _state.value.copy(downloaded = false, downloadProgress = null)
    }
}
