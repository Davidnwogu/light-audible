package com.davidnwogu.lightaudible.api

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data object NotModified : ApiResult<Nothing>
    data object Unauthorized : ApiResult<Nothing>
    data object NeedsSignIn : ApiResult<Nothing>
    data object InvalidCode : ApiResult<Nothing>
    data object NotFound : ApiResult<Nothing>

    /** [status] is null for network failures and timeouts. */
    data class Failed(val status: Int?) : ApiResult<Nothing>
}
