package com.davidnwogu.lightaudible.ui

import androidx.lifecycle.viewModelScope
import com.davidnwogu.lightaudible.api.ApiResult
import com.davidnwogu.lightaudible.api.AuthState
import com.davidnwogu.lightaudible.api.Credentials
import com.davidnwogu.lightaudible.api.ServerApi
import com.davidnwogu.lightaudible.normalizeServerUrl
import com.davidnwogu.lightaudible.store.ToolStore
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SetupUiState {
    data object Checking : SetupUiState
    data class Form(
        val serverUrl: String,
        val code: String,
        val error: String?,
        val busy: Boolean,
    ) : SetupUiState
    data object Paired : SetupUiState
}

/** One-shot navigation request; the screen acts on it and calls [SetupViewModel.consumeRoute]. */
enum class SetupRoute { OpenLibrary, Exit }

class SetupViewModel(
    private val store: ToolStore,
    private val api: ServerApi,
    private val auth: AuthState,
) : LightViewModel<Unit>() {
    private val _state = MutableStateFlow<SetupUiState>(SetupUiState.Checking)
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private val _route = MutableStateFlow<SetupRoute?>(null)
    val route: StateFlow<SetupRoute?> = _route.asStateFlow()

    init {
        viewModelScope.launch {
            if (store.credentials() != null) paired() else showForm()
        }
    }

    fun consumeRoute() {
        _route.value = null
    }

    /** Called when the Library screen has been popped back to Setup. */
    fun onLibraryClosed() {
        viewModelScope.launch {
            if (auth.signedOut.value || store.credentials() == null) showForm() else _route.value = SetupRoute.Exit
        }
    }

    private fun paired() {
        _state.value = SetupUiState.Paired
        _route.value = SetupRoute.OpenLibrary
    }

    private suspend fun showForm() {
        _state.value = SetupUiState.Form(store.serverUrl().orEmpty(), "", null, false)
    }

    fun setServerUrl(s: String) = updateForm { it.copy(serverUrl = s, error = null) }

    fun setCode(s: String) = updateForm { it.copy(code = s, error = null) }

    fun submit() {
        val form = _state.value as? SetupUiState.Form ?: return
        if (form.busy) return
        val code = form.code.filterNot { it.isWhitespace() }
        if (code.length != 6 || !code.all { it in '0'..'9' }) {
            return updateForm { it.copy(error = CODE_FORMAT_ERROR) }
        }
        val url = normalizeServerUrl(form.serverUrl)
            ?: return updateForm { it.copy(error = BAD_ADDRESS_ERROR) }
        updateForm { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = api.pair(url, code)) {
                is ApiResult.Ok -> {
                    store.saveCredentials(Credentials(url, result.value))
                    auth.reset()
                    paired()
                }
                ApiResult.InvalidCode -> fail(INVALID_CODE_ERROR)
                else -> fail(UNREACHABLE_ERROR)
            }
        }
    }

    private fun fail(message: String) = updateForm { it.copy(busy = false, error = message) }

    private fun updateForm(transform: (SetupUiState.Form) -> SetupUiState.Form) {
        _state.value = (_state.value as? SetupUiState.Form)?.let(transform) ?: return
    }

    private companion object {
        const val CODE_FORMAT_ERROR = "Enter the 6-digit code from your setup page."
        const val INVALID_CODE_ERROR = "That code didn't work. Make a new one on your setup page."
        const val BAD_ADDRESS_ERROR = "Use your server's https:// address."
        const val UNREACHABLE_ERROR = "Can't reach the server. Check the address."
    }
}
