package com.davidnwogu.lightaudible.fakes

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okio.Buffer

class RecordedRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String)

/** Records each request and replies with queued responses or throws queued exceptions. */
class ScriptedInterceptor : Interceptor {
    private sealed interface Action {
        class Reply(val status: Int, val headers: Map<String, String>, val body: String, val sentAt: Long, val receivedAt: Long) : Action
        class Fail(val error: IOException) : Action
        class Partial(val bytes: ByteArray, val error: IOException) : Action
    }

    private val queue = ArrayDeque<Action>()
    val requests = mutableListOf<RecordedRequest>()

    fun enqueue(
        status: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        sentAtMs: Long = 0L,
        receivedAtMs: Long = 0L,
    ) {
        queue.addLast(Action.Reply(status, headers, body, sentAtMs, receivedAtMs))
    }

    fun enqueueFailure(error: IOException) {
        queue.addLast(Action.Fail(error))
    }

    /** A 200 response whose body yields [bytes] and then throws [error]. */
    fun enqueuePartialThenFail(bytes: ByteArray, error: IOException) {
        queue.addLast(Action.Partial(bytes, error))
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += RecordedRequest(
            method = request.method,
            url = request.url.toString(),
            headers = request.headers.names().associateWith { request.header(it)!! },
            body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() } ?: "",
        )
        val action = queue.removeFirstOrNull() ?: error("No scripted response for ${request.method} ${request.url}")
        return when (action) {
            is Action.Fail -> throw action.error
            is Action.Reply -> build(request, action)
            is Action.Partial -> Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(200).message("scripted")
                .body(object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = action.bytes.size + 1000L
                    override fun source(): BufferedSource = object : ForwardingSource(Buffer().write(action.bytes)) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            val n = super.read(sink, byteCount)
                            if (n == -1L) throw action.error
                            return n
                        }
                    }.buffer()
                })
                .build()
        }
    }

    private fun build(request: Request, a: Action.Reply): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(a.status)
            .message("scripted")
            .sentRequestAtMillis(a.sentAt)
            .receivedResponseAtMillis(a.receivedAt)
            .body(a.body.toResponseBody("application/json".toMediaType()))
        a.headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }
}
