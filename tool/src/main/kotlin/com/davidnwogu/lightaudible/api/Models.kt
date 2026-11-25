package com.davidnwogu.lightaudible.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ApiJson = Json { ignoreUnknownKeys = true }

@Serializable
data class LibraryBook(
    val asin: String,
    val title: String,
    val authors: List<String>,
    val narrators: List<String>,
    @SerialName("runtime_ms") val runtimeMs: Long? = null,
    val playable: Boolean,
)

@Serializable
data class Chapter(
    val idx: Int,
    val title: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("length_ms") val lengthMs: Long,
)

@Serializable
enum class BookStatus {
    @SerialName("not_ready") NotReady,
    @SerialName("preparing") Preparing,
    @SerialName("ready") Ready,
    @SerialName("failed") Failed,
    @SerialName("unplayable") Unplayable,
}

@Serializable
data class BookState(
    val status: BookStatus,
    val url: String? = null,
    @SerialName("url_expires_at") val urlExpiresAt: String? = null,
    val chapters: List<Chapter>? = null,
    val error: String? = null,
)

@Serializable
data class RemotePosition(
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("updated_at") val updatedAt: String,
)

data class LibraryPage(val books: List<LibraryBook>, val etag: String?)

data class Credentials(val baseUrl: String, val token: String)
