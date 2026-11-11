package com.davidnwogu.lightaudible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import com.davidnwogu.lightaudible.Graph
import com.davidnwogu.lightaudible.playback.LightPlaybackFactory
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.map

class LibraryScreen(private val sealedActivity: SealedLightActivity) :
    LightScreen<Unit, LibraryViewModel>(sealedActivity) {
    private val graph = Graph.get(lightContext.dataStore, lightContext.filesDir)

    override val viewModelClass = LibraryViewModel::class.java
    override fun createViewModel() = LibraryViewModel(
        graph.store,
        graph.api,
        graph.playback,
        graph.chapters,
        graph.downloads,
        flushPositions = { graph.positions.flush() },
        connected = lightContext.connectivity.observeNetworkStatus().map { it.isConnected },
        nowMs = System::currentTimeMillis,
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        SignedOutWatcher(graph.auth) { goBack() }
        val editSearch = {
            navigateTo({ TextEditorScreen(it, "Search", state.query) }) { viewModel.setQuery(it) }
        }
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                val searching = state.query.isNotBlank()
                LightTopBar(
                    leftButton = if (searching) {
                        LightBarButton.LightIcon(LightIcons.CLOSE, onClick = { viewModel.clearQuery() })
                    } else {
                        LightBarButton.LightIcon(LightIcons.SEARCH, onClick = editSearch)
                    },
                    center = LightTopBarCenter.Text(if (searching) "“${state.query.trim()}”" else "Audiobooks"),
                    rightButton = if (searching) {
                        LightBarButton.LightIcon(LightIcons.SEARCH, onClick = editSearch)
                    } else {
                        LightBarButton.LightIcon(
                            LightIcons.REFRESH,
                            onClick = { viewModel.refresh() },
                            enabled = !state.loading,
                        )
                    },
                )
                val pad = Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())
                state.banner?.let { LightText(it, variant = LightTextVariant.Fine, modifier = pad) }
                LightScrollView(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    state.rows.forEach { row -> BookRow(row, pad) }
                }
                state.nowPlaying?.let { bar ->
                    val open = {
                        graph.playback.lastBook.value?.let { b -> navigateTo<Unit>({ PlayerScreen(it, b) }) }
                        Unit
                    }
                    LightBottomBar(
                        listOf(
                            LightBarButton.Text(bar.text, onClick = open),
                            if (bar.showPlayPause) {
                                LightBarButton.LightIcon(
                                    if (bar.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                                    onClick = { viewModel.togglePlay(LightPlaybackFactory(DefaultLightAudio(sealedActivity))) },
                                )
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun BookRow(row: LibraryRow, pad: Modifier) {
        val book = row.book
        val rowModifier = if (book.playable) {
            pad.lightClickable { navigateTo<Unit>({ PlayerScreen(it, book) }) }
        } else {
            pad
        }
        Row(
            modifier = rowModifier.padding(vertical = 0.5f.gridUnitsAsDp()),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LightText(book.title, variant = LightTextVariant.Copy, lighten = !book.playable)
                if (row.authors.isNotEmpty()) {
                    LightText(row.authors, variant = LightTextVariant.Detail, lighten = !book.playable)
                }
            }
            val marks = (if (row.isLoaded) "♪" else "") + (if (row.isDownloaded) "⤓" else "")
            if (marks.isNotEmpty()) LightText(marks, variant = LightTextVariant.Detail)
        }
    }
}
