package com.davidnwogu.lightaudible.sync

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Per-book downloads of presigned stream URLs. [http] must not add the bearer token.
 * Files are written to `<asin>.m4b.part` and renamed on success. Runs in [scope] so it outlives screens.
 */
class Downloads(
    private val filesDir: File,
    private val http: OkHttpClient,
    private val preparer: Preparer,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val jobs = HashMap<String, Job>()
    private val cancelled = HashMap<String, Job>()
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()
    private val _failures = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val failures: SharedFlow<Pair<String, String>> = _failures.asSharedFlow()

    fun file(asin: String) = File(filesDir, "$asin.m4b")
    private fun part(asin: String) = File(filesDir, "$asin.m4b.part")
    fun isDownloaded(asin: String) = file(asin).exists()

    fun start(asin: String) {
        synchronized(jobs) {
            if (jobs[asin]?.isActive == true || isDownloaded(asin)) return
            val prev = cancelled[asin]
            _progress.update { it + (asin to 0f) }
            lateinit var self: Job
            self = scope.launch(io, CoroutineStart.LAZY) { run(asin, self, prev) }
            jobs[asin] = self
            self.start()
        }
    }

    /** Stops the download promptly. The cancelled job deletes its own `.part`; a restart waits for it. */
    fun cancel(asin: String) {
        synchronized(jobs) { jobs.remove(asin)?.also { cancelled[asin] = it } }?.cancel()
        _progress.update { it - asin }
    }

    fun remove(asin: String) {
        cancel(asin)
        file(asin).delete()
        part(asin).delete()
    }

    private fun isCurrent(asin: String, job: Job) = synchronized(jobs) { jobs[asin] === job }

    private fun setProgress(asin: String, job: Job, value: Float?) {
        synchronized(jobs) {
            if (jobs[asin] !== job) return
            _progress.update { if (value == null) it - asin else it + (asin to value) }
        }
    }

    private suspend fun run(asin: String, self: Job, prev: Job?) {
        var success = false
        try {
            prev?.join()
            val outcome = preparer.readyUrl(asin) { }
            val url = (outcome as? PrepareOutcome.Ready)?.url
                ?: return fail(asin, self, when (outcome) {
                    is PrepareOutcome.Unplayable -> "This book can't be played"
                    PrepareOutcome.NeedsSignIn, PrepareOutcome.Unauthorized -> "Sign in again on your setup page"
                    else -> "Couldn't prepare this book"
                })
            fetch(asin, self, url)
            success = true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (self.isActive) fail(asin, self, "Download failed")
        } finally {
            if (!success) part(asin).delete()
            setProgress(asin, self, null)
        }
    }

    private fun fail(asin: String, self: Job, message: String) {
        if (isCurrent(asin, self)) _failures.tryEmit(asin to message)
    }

    private suspend fun fetch(asin: String, self: Job, url: String) {
        val call = http.newCall(Request.Builder().url(url).build())
        val target = part(asin)
        coroutineScope {
            // Unconfined so cancellation reaches the blocked socket read immediately.
            val watcher = launch(Dispatchers.Unconfined) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body
                    val total = body.contentLength()
                    var done = 0L
                    var lastPct = -1
                    body.byteStream().use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        setProgress(asin, self, pct / 100f)
                                    }
                                }
                            }
                        }
                    }
                    if (total > 0 && done != total) throw IOException("Short body")
                }
                currentCoroutineContext().ensureActive()
                if (!target.renameTo(file(asin))) throw IOException("Rename failed")
            } finally {
                watcher.cancel()
            }
        }
    }
}
