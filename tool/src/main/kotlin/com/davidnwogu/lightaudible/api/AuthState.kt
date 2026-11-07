package com.davidnwogu.lightaudible.api

import com.davidnwogu.lightaudible.store.ToolStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AuthState(private val store: ToolStore) {
    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut

    suspend fun lost() {
        store.clearToken()
        _signedOut.value = true
    }

    fun reset() {
        _signedOut.value = false
    }
}
