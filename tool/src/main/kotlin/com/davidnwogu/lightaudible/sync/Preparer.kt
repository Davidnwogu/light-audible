package com.davidnwogu.lightaudible.sync

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookStatus
import com.davidnwogu.lightaudible.api.ServerApi
import java.time.Instant
import java.time.OffsetDateTime
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
 * After such an error the next request waits 5, 10, 20, 40, then 60 s at most; any response resets that.
 * Polling while the server prepares stays every [pollIntervalMs].
 */
class Preparer(private val api: ServerApi, private val pollIntervalMs: Long = 5_000) {
    suspend fun readyUrl(asin: String, onWaiting: (PrepareWait) -> Unit): PrepareOutcome {
        var failures = 0
        var polling = false // true: the next request polls the book; false: it asks to prepare
        var waitMs = 0L
        while (true) {
            if (waitMs > 0) delay(waitMs)
            val r = if (polling) api.book(asin) else api.prepare(asin)
            when (r) {
                is ApiResult.Ok -> {
                    failures = 0
                    when (r.value.status) {
                        BookStatus.Ready -> if (polling) {
                            polling = false // prepare again (no wait) to get the URL
                            waitMs = 0
                        } else {
                            val url = r.value.url ?: return PrepareOutcome.Failed("missing url")
                            return PrepareOutcome.Ready(url, parseExpiry(r.value.urlExpiresAt))
                        }
                        BookStatus.Failed -> return PrepareOutcome.Failed(r.value.error)
                        BookStatus.Unplayable -> return PrepareOutcome.Unplayable(r.value.error)
                        BookStatus.Preparing, BookStatus.NotReady -> {
                            onWaiting(PrepareWait.Preparing)
                            polling = true
                            waitMs = pollIntervalMs
                        }
                    }
                }
                ApiResult.Unauthorized -> return PrepareOutcome.Unauthorized
                ApiResult.NeedsSignIn -> return PrepareOutcome.NeedsSignIn
                ApiResult.NotFound -> return PrepareOutcome.NotFound
                else -> {
                    failures++
                    onWaiting(PrepareWait.Connecting)
                    waitMs = backoffMs(failures)
                }
            }
        }
    }

    private fun backoffMs(failures: Int): Long =
        minOf(pollIntervalMs shl (failures - 1).coerceAtMost(10), MAX_BACKOFF_MS)

    private fun parseExpiry(s: String?): Instant =
        runCatching { OffsetDateTime.parse(s).toInstant() }.getOrDefault(Instant.EPOCH)

    private companion object {
        const val MAX_BACKOFF_MS = 60_000L
    }
}
