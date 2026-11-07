package com.davidnwogu.lightaudible.api

import java.time.Instant

interface ServerApi {
    suspend fun pair(baseUrl: String, code: String): ApiResult<String>
    suspend fun library(etag: String?): ApiResult<LibraryPage>
    suspend fun refreshLibrary(): ApiResult<LibraryPage>
    suspend fun book(asin: String): ApiResult<BookState>
    suspend fun prepare(asin: String): ApiResult<BookState>
    suspend fun position(asin: String): ApiResult<RemotePosition>
    suspend fun putPosition(asin: String, positionMs: Long, clientUpdatedAt: Instant): ApiResult<Unit>
}
