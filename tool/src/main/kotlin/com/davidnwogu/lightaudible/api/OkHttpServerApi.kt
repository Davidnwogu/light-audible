package com.davidnwogu.lightaudible.api

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OkHttpServerApi(
    client: OkHttpClient,
    private val credentials: suspend () -> Credentials?,
    private val clock: ServerClock,
    private val auth: AuthState,
    callTimeout: Duration = Duration.ofSeconds(30),
) : ServerApi {
    /** Every timeout is [callTimeout]: OkHttp's 10 s connect/read defaults would cut off a server waking from sleep. */
    internal val http = client.newBuilder()
        .callTimeout(callTimeout)
        .connectTimeout(callTimeout)
        .readTimeout(callTimeout)
        .writeTimeout(callTimeout)
        .build()
    private val jsonType = "application/json".toMediaType()

    override suspend fun pair(baseUrl: String, code: String): ApiResult<String> {
        val body = buildJsonObject { put("code", code) }.toString().toRequestBody(jsonType)
        val request = build(baseUrl, "/api/pair", token = null) { post(body) }
            ?: return ApiResult.Failed(null)
        return execute(request, authenticated = false) { resp ->
            ApiJson.parseToJsonElement(resp.body.string()).jsonObject["token"]?.jsonPrimitive?.contentOrNull
        }
    }

    override suspend fun library(etag: String?): ApiResult<LibraryPage> =
        authed("/api/library", { if (etag != null) header("If-None-Match", etag); get() }, ::parseLibrary)

    override suspend fun refreshLibrary(): ApiResult<LibraryPage> =
        authed("/api/library/refresh", { post(EMPTY_BODY) }, ::parseLibrary)

    override suspend fun book(asin: String): ApiResult<BookState> =
        authed("/api/books/$asin", { get() }, ::parseState)

    override suspend fun prepare(asin: String): ApiResult<BookState> =
        authed("/api/books/$asin/prepare", { post(EMPTY_BODY) }, ::parseState)

    override suspend fun position(asin: String): ApiResult<RemotePosition> =
        authed("/api/books/$asin/position", { get() }) { ApiJson.decodeFromString<RemotePosition>(it.body.string()) }

    override suspend fun putPosition(asin: String, positionMs: Long, clientUpdatedAt: Instant): ApiResult<Unit> {
        val body = buildJsonObject {
            put("position_ms", positionMs)
            put("client_updated_at", clientUpdatedAt.toString())
        }.toString().toRequestBody(jsonType)
        return authed("/api/books/$asin/position", { put(body) }) { }
    }

    private suspend fun <T> authed(
        path: String,
        configure: Request.Builder.() -> Unit,
        parse: (Response) -> T?,
    ): ApiResult<T> {
        val creds = credentials() ?: return ApiResult.Unauthorized
        val request = build(creds.baseUrl, path, creds.token, configure) ?: return ApiResult.Failed(null)
        return execute(request, authenticated = true, parse = parse)
    }

    private fun build(
        baseUrl: String,
        path: String,
        token: String?,
        configure: Request.Builder.() -> Unit,
    ): Request? {
        val url = (baseUrl.trimEnd('/') + path).toHttpUrlOrNull() ?: return null
        return Request.Builder().url(url).apply {
            if (token != null) header("Authorization", "Bearer $token")
            configure()
        }.build()
    }

    private suspend fun <T> execute(
        request: Request,
        authenticated: Boolean,
        parse: (Response) -> T?,
    ): ApiResult<T> {
        var last: ApiResult<T> = ApiResult.Failed(null)
        for (attempt in 0..1) {
            val outcome = try {
                withContext(Dispatchers.IO) { http.newCall(request).execute().use { classify(it, authenticated, parse) } }
            } catch (_: IOException) {
                Attempt(ApiResult.Failed(null), retry = true)
            }
            last = outcome.result
            if (!outcome.retry) break
        }
        return last
    }

    private class Attempt<T>(val result: ApiResult<T>, val retry: Boolean = false)

    private suspend fun <T> classify(resp: Response, authenticated: Boolean, parse: (Response) -> T?): Attempt<T> {
        val code = resp.code
        observeClock(resp)
        return when {
            code == 304 -> Attempt(ApiResult.NotModified)
            code == 401 -> {
                if (authenticated) auth.lost()
                Attempt(ApiResult.Unauthorized)
            }
            code == 404 -> Attempt(ApiResult.NotFound)
            code == 503 || code == 504 -> Attempt(ApiResult.Failed(code), retry = true)
            code == 400 && errorField(resp) == "invalid_code" -> Attempt(ApiResult.InvalidCode)
            code == 409 && errorField(resp) == "needs_sign_in" -> Attempt(ApiResult.NeedsSignIn)
            code in 200..299 -> {
                val value = try {
                    parse(resp)
                } catch (_: IllegalArgumentException) {
                    null // malformed body (SerializationException is an IllegalArgumentException)
                }
                Attempt(if (value != null) ApiResult.Ok(value) else ApiResult.Failed(code))
            }
            else -> Attempt(ApiResult.Failed(code))
        }
    }

    private fun errorField(resp: Response): String? = try {
        (ApiJson.parseToJsonElement(resp.body.string()) as? JsonObject)?.get("error")?.let { it as? JsonPrimitive }?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun observeClock(resp: Response) {
        val date = resp.header("Date") ?: return
        val server = try {
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            return
        }
        clock.observe(server, resp.sentRequestAtMillis, resp.receivedResponseAtMillis)
    }

    private fun parseLibrary(resp: Response): LibraryPage? {
        val books = ApiJson.decodeFromString(ListSerializer(LibraryBook.serializer()), resp.body.string())
        return LibraryPage(books, resp.header("ETag"))
    }

    private fun parseState(resp: Response): BookState? = ApiJson.decodeFromString<BookState>(resp.body.string())

    private companion object {
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}
