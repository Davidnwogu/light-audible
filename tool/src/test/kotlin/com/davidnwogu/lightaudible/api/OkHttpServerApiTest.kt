package com.davidnwogu.lightaudible.api

import com.davidnwogu.lightaudible.fakes.InMemoryToolStore
import com.davidnwogu.lightaudible.fakes.ScriptedInterceptor
import com.davidnwogu.lightaudible.store.ToolStore
import java.net.SocketTimeoutException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient

class OkHttpServerApiTest {
    private val base = "https://test.invalid"
    private val script = ScriptedInterceptor()
    private val store: ToolStore = InMemoryToolStore()
    private val auth = AuthState(store)
    private var offsets = mutableListOf<Long>()
    private val clock = ServerClock({ 0L }, 0L) { offsets += it }

    private suspend fun api(signedIn: Boolean = true): OkHttpServerApi {
        if (signedIn) store.saveCredentials(Credentials(base, "tok"))
        val client = OkHttpClient.Builder().addInterceptor(script).build()
        return OkHttpServerApi(client, { store.credentials() }, clock, auth)
    }

    private val libraryJson =
        """[{"asin":"B0X","title":"T","authors":["A"],"narrators":[],"runtime_ms":null,"playable":true}]"""

    @Test
    fun libraryDecodesAndSendsHeaders() = runTest {
        script.enqueue(200, libraryJson, mapOf("ETag" to "\"e1\""))
        val r = api().library("\"old\"")
        val page = (r as ApiResult.Ok).value
        assertEquals("\"e1\"", page.etag)
        assertEquals("B0X", page.books.single().asin)
        val req = script.requests.single()
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals("\"old\"", req.headers["If-None-Match"])
        assertEquals("https://test.invalid/api/library", req.url)
    }

    @Test
    fun notModified() = runTest {
        script.enqueue(304)
        assertEquals(ApiResult.NotModified, api().library("\"e\""))
    }

    @Test
    fun unauthorizedClearsTokenAndSignsOut() = runTest {
        script.enqueue(401, """{"error":"unauthorized"}""")
        assertEquals(ApiResult.Unauthorized, api().library(null))
        assertTrue(auth.signedOut.value)
        assertEquals(null, store.credentials())
        assertEquals(base, store.serverUrl())
    }

    @Test
    fun missingCredentialsIsUnauthorizedWithoutRequest() = runTest {
        assertEquals(ApiResult.Unauthorized, api(signedIn = false).library(null))
        assertTrue(script.requests.isEmpty())
    }

    @Test
    fun needsSignIn() = runTest {
        script.enqueue(409, """{"error":"needs_sign_in"}""")
        assertEquals(ApiResult.NeedsSignIn, api().book("B0X"))
    }

    @Test
    fun pairInvalidCode() = runTest {
        script.enqueue(400, """{"error":"invalid_code"}""")
        assertEquals(ApiResult.InvalidCode, api(signedIn = false).pair(base, "123456"))
        val req = script.requests.single()
        assertEquals(null, req.headers["Authorization"])
        assertEquals("POST", req.method)
    }

    @Test
    fun pairReturnsToken() = runTest {
        script.enqueue(200, """{"token":"abc"}""")
        assertEquals(ApiResult.Ok("abc"), api(signedIn = false).pair(base, "123456"))
        assertEquals("""{"code":"123456"}""", script.requests.single().body)
    }

    @Test
    fun notFoundAndPlainTextServerError() = runTest {
        script.enqueue(404, """{"detail":"Not Found"}""")
        assertEquals(ApiResult.NotFound, api().book("B0X"))
        script.enqueue(500, "Internal Server Error")
        assertEquals(ApiResult.Failed(500), api().book("B0X"))
    }

    @Test
    fun validationErrorIsFailed422() = runTest {
        script.enqueue(422, """{"detail":[{"loc":["body"],"msg":"bad","type":"x"}]}""")
        assertEquals(ApiResult.Failed(422), api().putPosition("B0X", 1, Instant.EPOCH))
    }

    @Test
    fun retriesOnceAfterTimeout() = runTest {
        script.enqueueFailure(SocketTimeoutException("timeout"))
        script.enqueue(200, libraryJson)
        assertTrue(api().library(null) is ApiResult.Ok)
        assertEquals(2, script.requests.size)
    }

    @Test
    fun retriesOnceOn503() = runTest {
        script.enqueue(503)
        script.enqueue(200, libraryJson)
        assertTrue(api().library(null) is ApiResult.Ok)
        assertEquals(2, script.requests.size)
    }

    @Test
    fun twoTimeoutsFail() = runTest {
        script.enqueueFailure(SocketTimeoutException("a"))
        script.enqueueFailure(SocketTimeoutException("b"))
        assertEquals(ApiResult.Failed(null), api().library(null))
        assertEquals(2, script.requests.size)
    }

    @Test
    fun putPositionBodyAnd204() = runTest {
        script.enqueue(204)
        val r = api().putPosition("B0X", 5000, Instant.parse("2026-11-06T12:00:00Z"))
        assertEquals(ApiResult.Ok(Unit), r)
        val req = script.requests.single()
        assertEquals("PUT", req.method)
        assertEquals("""{"position_ms":5000,"client_updated_at":"2026-11-06T12:00:00Z"}""", req.body)
        assertEquals("https://test.invalid/api/books/B0X/position", req.url)
    }

    @Test
    fun dateHeaderFeedsClock() = runTest {
        // Wed, 06 Nov 2030 12:00:00 GMT = 1_920_196_800_000 ms; device clock is at 0.
        script.enqueue(200, libraryJson, mapOf("Date" to "Wed, 06 Nov 2030 12:00:00 GMT"), sentAtMs = 0, receivedAtMs = 100)
        api().library(null)
        assertEquals(1, offsets.size)
        assertEquals(1_920_196_800_000L - 50, clock.offsetMs)
    }

    @Test
    fun everyTimeoutIs30Seconds() {
        // A sleeping server can take most of 30 s to answer; OkHttp's 10 s defaults must not cut it short.
        val http = OkHttpServerApi(OkHttpClient(), { null }, clock, auth).http
        assertEquals(30_000, http.callTimeoutMillis)
        assertEquals(30_000, http.connectTimeoutMillis)
        assertEquals(30_000, http.readTimeoutMillis)
        assertEquals(30_000, http.writeTimeoutMillis)
    }
}
