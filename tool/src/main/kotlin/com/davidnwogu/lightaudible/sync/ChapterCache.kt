package com.davidnwogu.lightaudible.sync

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.Chapter
import com.davidnwogu.lightaudible.api.ServerApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chapter lists shared by the Library, Player and Chapters screens.
 * Only a non-empty list from a successful fetch is cached; failures and empty lists are retried on the next [get].
 */
class ChapterCache(private val api: ServerApi) {
    private val writeLock = Mutex()
    private val _updates = MutableStateFlow<Map<String, List<Chapter>>>(emptyMap())

    /** Every cached book's chapters, keyed by ASIN. Emits each time a new list is cached. */
    val updates: StateFlow<Map<String, List<Chapter>>> = _updates.asStateFlow()

    /** The cached chapters for [asin], or null if none are cached yet. Never makes a request. */
    fun cached(asin: String): List<Chapter>? = _updates.value[asin]

    /** Cached chapters for [asin], fetching them from the server when absent. Returns an empty list on any failure. */
    suspend fun get(asin: String): List<Chapter> {
        cached(asin)?.let { return it }
        val chapters = (api.book(asin) as? ApiResult.Ok)?.value?.chapters
        if (chapters.isNullOrEmpty()) return emptyList()
        writeLock.withLock { _updates.update { it + (asin to chapters) } }
        return chapters
    }
}
