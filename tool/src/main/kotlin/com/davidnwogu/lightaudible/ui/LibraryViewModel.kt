package com.davidnwogu.lightaudible.ui

import androidx.lifecycle.viewModelScope
import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.api.LibraryPage
import com.davidnwogu.lightaudible.api.ServerApi
import com.davidnwogu.lightaudible.playback.Phase
import com.davidnwogu.lightaudible.playback.PlaybackControls
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.davidnwogu.lightaudible.store.LibraryCache
import com.davidnwogu.lightaudible.sync.ChapterCache
import com.davidnwogu.lightaudible.sync.Downloads
import com.davidnwogu.lightaudible.store.ToolStore
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

const val BANNER_CONNECTING = "Connecting…"
const val BANNER_SIGN_IN = "Sign in again on your setup page"
const val BANNER_UNREACHABLE = "Can't reach the server."
private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

data class NowPlayingBar(val text: String, val showPlayPause: Boolean, val isPlaying: Boolean)

data class LibraryRow(val book: LibraryBook, val authors: String, val isLoaded: Boolean, val isDownloaded: Boolean)

data class LibraryUiState(
    val books: List<LibraryBook> = emptyList(),
    val query: String = "",
    val rows: List<LibraryRow> = emptyList(),
    val banner: String? = null,
    val loading: Boolean = false,
    val nowPlaying: NowPlayingBar? = null,
)

/** Case-insensitive match over title and authors; narrators are not searched. Blank query keeps everything. */
fun filterBooks(books: List<LibraryBook>, query: String): List<LibraryBook> {
    val q = query.trim()
    if (q.isEmpty()) return books
    return books.filter { b ->
        b.title.contains(q, ignoreCase = true) || b.authors.any { it.contains(q, ignoreCase = true) }
    }
}

class LibraryViewModel(
    private val store: ToolStore,
    private val api: ServerApi,
    private val session: PlaybackControls,
    private val chapters: ChapterCache,
    private val downloads: Downloads,
    /** Sends queued position reports (PositionSync.flush). */
    private val flushPositions: suspend () -> Unit = {},
    /** Whether the device has a network; each return to true flushes queued positions. */
    private val connected: Flow<Boolean> = emptyFlow(),
    private val nowMs: () -> Long,
) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        // Rows and the bar are rebuilt synchronously from the inputs' current values; these just trigger that.
        viewModelScope.launch { session.state.collect { refresh(rows = false) } }
        viewModelScope.launch {
            session.lastBook.collect { book ->
                refresh(rows = false)
                if (book != null) chapters.get(book.asin)
            }
        }
        viewModelScope.launch { chapters.updates.collect { refresh(rows = false) } }
        viewModelScope.launch { downloads.progress.collect { refresh(rows = true) } }
    }

    fun togglePlay(factory: PlaybackFactory) = session.togglePlay(factory)

    fun clearQuery() = setQuery("")

    private var job: Job? = null
    private var cache: LibraryCache? = null
    private var networkJob: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) = onShow()

    /**
     * Called whenever the screen becomes current. Flushes queued position reports, and refreshes the
     * library only when the cache is missing or over 24 h old.
     */
    fun onShow() {
        viewModelScope.launch { flushPositions() }
        if (networkJob?.isActive != true) {
            networkJob = viewModelScope.launch {
                connected.distinctUntilChanged().drop(1).filter { it }.collect { flushPositions() }
            }
        }
        start { load() }
    }

    private suspend fun load() {
        val cached = store.libraryCache()
        cache = cached
        if (cached != null) setBooks(cached.books)
        if (cached == null || nowMs() - cached.fetchedAtMs >= STALE_AFTER_MS) {
            fetch { api.library(cached?.etag) }
        }
    }

    /** Always asks the server to re-sync the library. */
    fun refresh() = start { fetch { api.refreshLibrary() } }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        refresh(rows = true)
    }

    private fun start(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch { block() }
    }

    private fun setBooks(books: List<LibraryBook>) {
        _state.value = _state.value.copy(books = books)
        refresh(rows = true)
    }

    private var lastLoaded: String? = null

    /** Rebuilds the bar, and the rows when [rows] is set or the loaded book changed (position ticks skip them). */
    private fun refresh(rows: Boolean) {
        val cur = _state.value
        val s = session.state.value
        val loadedAsin = s.asin.takeIf { s.phase != Phase.Idle }
        val rebuild = rows || loadedAsin != lastLoaded
        lastLoaded = loadedAsin
        _state.value = cur.copy(
            rows = if (rebuild) {
                filterBooks(cur.books, cur.query).map {
                    LibraryRow(it, formatAuthors(it.authors), it.asin == loadedAsin, downloads.isDownloaded(it.asin))
                }
            } else {
                cur.rows
            },
            nowPlaying = nowPlaying(),
        )
    }

    private fun nowPlaying(): NowPlayingBar? {
        val book = session.lastBook.value ?: return null
        val s = session.state.value.takeIf { it.asin == book.asin }
        val phase = s?.phase ?: Phase.Idle
        val status = when (phase) {
            Phase.Preparing -> "Preparing…"
            Phase.Connecting -> "Connecting…"
            Phase.Reconnecting -> "Reconnecting…"
            is Phase.Failed -> phase.message
            Phase.NeedsSignIn -> BANNER_SIGN_IN
            Phase.Unplayable -> "This book can't be played"
            Phase.Idle, Phase.Ready ->
                s?.let { currentChapter(chapters.cached(book.asin).orEmpty(), it.positionMs)?.title }
        }
        return NowPlayingBar(
            text = if (status == null) book.title else "${book.title} · $status",
            showPlayPause = phase is Phase.Idle || phase is Phase.Ready || phase is Phase.Reconnecting,
            isPlaying = s?.isPlaying == true,
        )
    }

    private suspend fun fetch(call: suspend () -> ApiResult<LibraryPage>) {
        _state.value = _state.value.copy(loading = true, banner = BANNER_CONNECTING)
        var banner: String? = null
        when (val r = call()) {
            is ApiResult.Ok -> {
                val saved = LibraryCache(r.value.books, r.value.etag, nowMs())
                store.saveLibraryCache(saved)
                cache = saved
                setBooks(saved.books)
            }
            ApiResult.NotModified -> cache?.let {
                val bumped = it.copy(fetchedAtMs = nowMs())
                store.saveLibraryCache(bumped)
                cache = bumped
            }
            ApiResult.NeedsSignIn, ApiResult.Unauthorized -> banner = BANNER_SIGN_IN
            else -> if (cache == null) banner = BANNER_UNREACHABLE // nothing to show otherwise
        }
        _state.value = _state.value.copy(loading = false, banner = banner)
    }
}
