package com.davidnwogu.lightaudible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.davidnwogu.lightaudible.Graph
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

@InitialScreen
class SetupScreen(activity: SealedLightActivity) : LightScreen<Unit, SetupViewModel>(activity) {
    private val graph = Graph.get(lightContext.dataStore, lightContext.filesDir)

    override val viewModelClass = SetupViewModel::class.java
    override fun createViewModel() = SetupViewModel(graph.store, graph.api, graph.auth)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val route by viewModel.route.collectAsState()
        LaunchedEffect(route) {
            when (route) {
                SetupRoute.OpenLibrary -> {
                    viewModel.consumeRoute()
                    navigateTo(::LibraryScreen)
                }
                SetupRoute.Exit -> {
                    viewModel.consumeRoute()
                    goBack()
                }
                null -> Unit
            }
        }
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val s = state) {
                    SetupUiState.Checking, SetupUiState.Paired -> Unit
                    is SetupUiState.Form -> FormContent(s)
                }
            }
        }
    }

    @Composable
    private fun FormContent(form: SetupUiState.Form) {
        val pad = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())
        LightText("Connect", variant = LightTextVariant.Title, modifier = pad)
        LightTextField(
            label = "Server:",
            value = form.serverUrl,
            placeholder = "your server address",
            onClick = {
                navigateTo({ TextEditorScreen(it, "Server", form.serverUrl) }) { viewModel.setServerUrl(it) }
            },
            modifier = pad,
        )
        LightTextField(
            label = "Code:",
            value = form.code,
            placeholder = "6-digit code",
            onClick = {
                navigateTo({ TextEditorScreen(it, "Code", form.code) }) { viewModel.setCode(it) }
            },
            modifier = pad,
        )
        form.error?.let { LightText(it, variant = LightTextVariant.Detail, modifier = pad) }
        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = if (form.busy) "CONNECTING" else "CONNECT",
                    enabled = !form.busy,
                    onClick = viewModel::submit,
                ),
            ),
        )
    }
}
