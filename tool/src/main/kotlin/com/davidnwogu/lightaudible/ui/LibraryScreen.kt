package com.davidnwogu.lightaudible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.davidnwogu.lightaudible.Graph
import com.davidnwogu.lightaudible.api.LibraryBook
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class LibraryScreen(activity: SealedLightActivity) : LightScreen<Unit, LibraryViewModel>(activity) {
    private val graph = Graph.get(lightContext.dataStore, lightContext.filesDir)

    override val viewModelClass = LibraryViewModel::class.java
    override fun createViewModel() = LibraryViewModel(graph.store, graph.api, System::currentTimeMillis)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        SignedOutWatcher(graph.auth) { goBack() }
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val pad = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())
                LightText("Library", variant = LightTextVariant.Title, modifier = pad)
                LightTextField(
                    label = "Search:",
                    value = state.query,
                    placeholder = "title or author",
                    onClick = {
                        navigateTo({ TextEditorScreen(it, "Search", state.query) }) { viewModel.setQuery(it) }
                    },
                    modifier = pad,
                )
                state.banner?.let { LightText(it, variant = LightTextVariant.Detail, modifier = pad) }
                LightScrollView(modifier = Modifier.fillMaxSize()) {
                    LightText(
                        "Refresh",
                        variant = LightTextVariant.Copy,
                        modifier = pad.lightClickable(enabled = !state.loading) { viewModel.refresh() },
                        lighten = state.loading,
                    )
                    state.visible.forEach { book -> BookRow(book, pad) }
                }
            }
        }
    }

    @Composable
    private fun BookRow(book: LibraryBook, pad: Modifier) {
        val rowModifier = if (book.playable) {
            pad.lightClickable { navigateTo<Unit>({ BookScreen(it, book) }) }
        } else {
            pad
        }
        Column(modifier = rowModifier) {
            LightText(book.title, variant = LightTextVariant.Copy, lighten = !book.playable)
            if (book.authors.isNotEmpty()) {
                LightText(book.authors.joinToString(", "), variant = LightTextVariant.Detail, lighten = !book.playable)
            }
        }
    }
}
