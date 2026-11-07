package com.davidnwogu.lightaudible.sync

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.ServerApi
import java.time.Instant
import kotlinx.coroutines.delay

sealed interface PrepareOutcome {
    data class Ready(val url: String, val expiresAt: Instant) : PrepareOutcome
    data class Failed(val error: String?) : PrepareOutcome
    data class Unplayable(val reason: String?) : PrepareOutcome
    data object NeedsSignIn : PrepareOutcome
    data object Unauthorized : PrepareOutcome
    data object NotFound : PrepareOutcome
}

enum class PrepareWait { Preparing, Connecting }

/**
 * Asks the server to prepare a book and waits until a stream URL is available.
 * Network and server errors never end the wait (they report [PrepareWait.Connecting]); callers cancel to stop.
 */
class Preparer(private val api: ServerApi, private val pollIntervalMs: Long = 5_000) {
    suspend fun readyUrl(asin: String, onWaiting: (PrepareWait) -> Unit): PrepareOutcome {
        while (true) {
            when (val r = api.prepare(asin)) {
                is ApiResult.Ok -> when (r.value.status) {
                    BookStatus.Ready -> {
                        val url = r.value.url ?: return PrepareOutcome.Failed("missing url")
                        return PrepareOutcome.Ready(url, parseExpiry(r.value.urlExpiresAt))
                    }
                    BookStatus.Failed -> return PrepareOutcome.Failed(r.value.error)
                    BookStatus.Unplayable -> return PrepareOutcome.Unplayable(r.value.error)
                    BookStatus.Preparing, BookStatus.NotReady -> {
                        onWaiting(PrepareWait.Preparing)
                        waitUntilReady(asin, onWaiting)?.let { return it }
                    }
                }
                ApiResult.Unauthorized -> return PrepareOutcome.Unauthorized
                ApiResult.NeedsSignIn -> return PrepareOutcome.NeedsSignIn
                ApiResult.NotFound -> return PrepareOutcome.NotFound
                else -> {
                    onWaiting(PrepareWait.Connecting)
                    delay(pollIntervalMs)
                }
            }
        }
    }

    /** Polls the book until it is ready (returns null so the caller prepares again) or reaches a terminal outcome. */
    private suspend fun waitUntilReady(asin: String, onWaiting: (PrepareWait) -> Unit): PrepareOutcome? {
        while (true) {
            delay(pollIntervalMs)
            when (val r = api.book(asin)) {
                is ApiResult.Ok -> when (r.value.status) {
                    BookStatus.Ready -> return null
                    BookStatus.Failed -> return PrepareOutcome.Failed(r.value.error)
                    BookStatus.Unplayable -> return PrepareOutcome.Unplayable(r.value.error)
                    BookStatus.Preparing, BookStatus.NotReady -> onWaiting(PrepareWait.Preparing)
                }
                ApiResult.Unauthorized -> return PrepareOutcome.Unauthorized
                ApiResult.NeedsSignIn -> return PrepareOutcome.NeedsSignIn
                ApiResult.NotFound -> return PrepareOutcome.NotFound
                else -> onWaiting(PrepareWait.Connecting)
            }
        }
    }

    private fun parseExpiry(s: String?): Instant =
        runCatching { Instant.parse(s) }.getOrDefault(Instant.EPOCH)
}
