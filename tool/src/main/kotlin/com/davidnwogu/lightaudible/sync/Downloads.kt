package com.davidnwogu.lightaudible.sync

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
            _progress.update { it + (asin to 0f) }
            jobs[asin] = scope.launch(io) { run(asin) }
        }
    }

    fun cancel(asin: String) {
        synchronized(jobs) { jobs.remove(asin) }?.cancel()
        part(asin).delete()
        _progress.update { it - asin }
    }

    fun remove(asin: String) {
        cancel(asin)
        file(asin).delete()
        part(asin).delete()
    }

    private suspend fun run(asin: String) {
        try {
            val outcome = preparer.readyUrl(asin) { }
            val url = (outcome as? PrepareOutcome.Ready)?.url
                ?: return fail(asin, if (outcome is PrepareOutcome.Unplayable) "This book can't be played" else "Couldn't prepare this book")
            fetch(asin, url)
            _progress.update { it - asin }
        } catch (e: CancellationException) {
            part(asin).delete()
            throw e
        } catch (e: IOException) {
            fail(asin, "Download failed")
        }
    }

    private fun fail(asin: String, message: String) {
        part(asin).delete()
        _progress.update { it - asin }
        _failures.tryEmit(asin to message)
    }

    private suspend fun fetch(asin: String, url: String) {
        val job = currentCoroutineContext()[Job]
        val call = http.newCall(Request.Builder().url(url).build())
        job?.invokeOnCompletion { if (it != null) call.cancel() }
        val target = part(asin)
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
                                    _progress.update { it + (asin to pct / 100f) }
                                }
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("Short body")
            }
            if (!target.renameTo(file(asin))) throw IOException("Rename failed")
        } catch (e: IOException) {
            target.delete()
            throw e
        }
    }
}
