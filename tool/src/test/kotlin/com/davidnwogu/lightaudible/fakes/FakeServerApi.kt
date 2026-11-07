package com.davidnwogu.lightaudible.fakes

import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.BookState
import com.davidnwogu.lightaudible.api.LibraryPage
import com.davidnwogu.lightaudible.api.RemotePosition
import com.davidnwogu.lightaudible.api.ServerApi
import java.time.Instant

/** Scriptable [ServerApi]: set a `*Result` lambda per method; calls are recorded. */
class FakeServerApi : ServerApi {
    data class PutPositionCall(val asin: String, val positionMs: Long, val clientUpdatedAt: Instant)

    var pairResult: (baseUrl: String, code: String) -> ApiResult<String> = { _, _ -> ApiResult.Failed(null) }
    var libraryResult: (etag: String?) -> ApiResult<LibraryPage> = { ApiResult.Failed(null) }
    var refreshLibraryResult: () -> ApiResult<LibraryPage> = { ApiResult.Failed(null) }
    var bookResult: (asin: String) -> ApiResult<BookState> = { ApiResult.Failed(null) }
    var prepareResult: (asin: String) -> ApiResult<BookState> = { ApiResult.Failed(null) }
    var positionResult: (asin: String) -> ApiResult<RemotePosition> = { ApiResult.Failed(null) }
    var putPositionResult: (PutPositionCall) -> ApiResult<Unit> = { ApiResult.Failed(null) }

    val pairCalls = mutableListOf<Pair<String, String>>()
    val libraryCalls = mutableListOf<String?>()
    var refreshLibraryCalls = 0
    val bookCalls = mutableListOf<String>()
    val prepareCalls = mutableListOf<String>()
    val positionCalls = mutableListOf<String>()
    val putPositionCalls = mutableListOf<PutPositionCall>()

    override suspend fun pair(baseUrl: String, code: String): ApiResult<String> {
        pairCalls += baseUrl to code
        return pairResult(baseUrl, code)
    }

    override suspend fun library(etag: String?): ApiResult<LibraryPage> {
        libraryCalls += etag
        return libraryResult(etag)
    }

    override suspend fun refreshLibrary(): ApiResult<LibraryPage> {
        refreshLibraryCalls++
        return refreshLibraryResult()
    }

    override suspend fun book(asin: String): ApiResult<BookState> {
        bookCalls += asin
        return bookResult(asin)
    }

    override suspend fun prepare(asin: String): ApiResult<BookState> {
        prepareCalls += asin
        return prepareResult(asin)
    }

    override suspend fun position(asin: String): ApiResult<RemotePosition> {
        positionCalls += asin
        return positionResult(asin)
    }

    override suspend fun putPosition(asin: String, positionMs: Long, clientUpdatedAt: Instant): ApiResult<Unit> {
        val call = PutPositionCall(asin, positionMs, clientUpdatedAt)
        putPositionCalls += call
        return putPositionResult(call)
    }
}
