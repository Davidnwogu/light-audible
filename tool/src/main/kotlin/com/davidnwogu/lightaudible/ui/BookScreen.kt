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
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class BookScreen(activity: SealedLightActivity, private val book: LibraryBook) :
    LightScreen<Unit, BookViewModel>(activity) {
    private val graph = Graph.get(lightContext.dataStore, lightContext.filesDir)

    override val viewModelClass = BookViewModel::class.java
    override fun createViewModel() = BookViewModel(book, graph.api, graph.downloads)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        SignedOutWatcher(graph.auth) { goBack() }
        LightTheme(colors = themeColors) {
            LightScrollView(
                modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
            ) {
                val pad = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())
                LightText(book.title, variant = LightTextVariant.Title, modifier = pad)
                if (book.authors.isNotEmpty()) {
                    LightText(book.authors.joinToString(", "), variant = LightTextVariant.Copy, modifier = pad)
                }
                if (book.narrators.isNotEmpty()) {
                    LightText("Narrated by " + book.narrators.joinToString(", "), variant = LightTextVariant.Detail, modifier = pad)
                }
                val runtime = formatRuntime(book.runtimeMs)
                if (runtime.isNotEmpty()) LightText(runtime, variant = LightTextVariant.Detail, modifier = pad)
                state.statusLine?.let { LightText(it, variant = LightTextVariant.Detail, modifier = pad) }
                if (state.statusLine == STATUS_FAILED) {
                    LightText(
                        "Try again",
                        variant = LightTextVariant.Copy,
                        modifier = pad.lightClickable { viewModel.retry() },
                    )
                }
                LightText(
                    "Play",
                    variant = LightTextVariant.Copy,
                    modifier = pad.lightClickable { navigateTo<Unit>({ PlayerScreen(it, book) }) },
                )
                val progress = state.downloadProgress
                when {
                    progress != null -> {
                        LightText(
                            "Downloading ${(progress * 100).toInt()}%",
                            variant = LightTextVariant.Copy,
                            modifier = pad,
                            lighten = true,
                        )
                        LightText(
                            "Cancel download",
                            variant = LightTextVariant.Copy,
                            modifier = pad.lightClickable { viewModel.cancelDownload() },
                        )
                    }
                    state.downloaded -> LightText(
                        "Remove download",
                        variant = LightTextVariant.Copy,
                        modifier = pad.lightClickable { viewModel.removeDownload() },
                    )
                    else -> LightText(
                        "Download",
                        variant = LightTextVariant.Copy,
                        modifier = pad.lightClickable { viewModel.download() },
                    )
                }
                Column(modifier = pad) {
                    state.chapters.forEach { c ->
                        LightText("${c.title}  ${formatDuration(c.lengthMs)}", variant = LightTextVariant.Detail)
                    }
                }
            }
        }
    }
}
