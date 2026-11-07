package com.davidnwogu.lightaudible.ui

import androidx.lifecycle.viewModelScope
import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.api.LibraryPage
import com.davidnwogu.lightaudible.api.ServerApi
import com.davidnwogu.lightaudible.store.LibraryCache
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
private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

data class LibraryUiState(
    val books: List<LibraryBook> = emptyList(),
    val query: String = "",
    val visible: List<LibraryBook> = emptyList(),
    val banner: String? = null,
    val loading: Boolean = false,
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
    /** Sends queued position reports (PositionSync.flush). */
    private val flushPositions: suspend () -> Unit = {},
    /** Whether the device has a network; each return to true flushes queued positions. */
    private val connected: Flow<Boolean> = emptyFlow(),
    private val nowMs: () -> Long,
) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

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
        _state.value = _state.value.let { it.copy(query = q, visible = filterBooks(it.books, q)) }
    }

    private fun start(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch { block() }
    }

    private fun setBooks(books: List<LibraryBook>) {
        _state.value = _state.value.let { it.copy(books = books, visible = filterBooks(books, it.query)) }
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
            else -> Unit
        }
        _state.value = _state.value.copy(loading = false, banner = banner)
    }
}
