package com.davidnwogu.lightaudible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.davidnwogu.lightaudible.Graph
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.playback.LightPlaybackFactory
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Podcast-style player for [book]: a preview until ▶ when the session holds another book. */
class PlayerScreen(private val sealedActivity: SealedLightActivity, private val book: LibraryBook) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {
    private val graph = Graph.get(lightContext.dataStore, lightContext.filesDir)
    private val factory by lazy { LightPlaybackFactory(DefaultLightAudio(sealedActivity)) }

    override val viewModelClass = PlayerViewModel::class.java
    override fun createViewModel() = PlayerViewModel(
        book,
        graph.playback,
        graph.chapters,
        graph.speed,
        graph.downloads,
        resumePoint = graph.positions::resumePoint,
        factory = factory,
    )

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        SignedOutWatcher(graph.auth) { goBack() }
        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }))
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val centred = Modifier.fillMaxWidth()
                    val typography = LightThemeTokens.typography
                    LightTheme(
                        colors = themeColors,
                        typography = typography.copy(heading = typography.heading.copy(fontWeight = FontWeight.Bold)),
                    ) {
                        LightText(
                            state.title,
                            variant = LightTextVariant.Heading,
                            align = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = centred,
                        )
                    }
                    state.chapterLine?.let {
                        LightText(
                            it,
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = centred,
                        )
                    }
                    if (state.showRetry) {
                        LightText(
                            "Try again",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                            underline = true,
                            modifier = centred.lightClickable { viewModel.retry() },
                        )
                    }
                    LightText(
                        state.byline,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lighten = true,
                        modifier = centred,
                    )
                    LightTouchableProgressBar(
                        colors = LightThemeTokens.colors,
                        progress = state.progress,
                        onValueChange = viewModel::seekToFraction,
                        modifier = centred.padding(vertical = 0.5f.gridUnitsAsDp()),
                    )
                    LightBottomBar(
                        listOf(
                            // Tinted so disabled controls are drawn lighter (the kit lightens only text items).
                            tintedBarIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, state.skipsEnabled, viewModel::back15),
                            tintedBarIcon(
                                if (state.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                                state.controlsEnabled,
                                viewModel::playPause,
                            ),
                            tintedBarIcon(LightIcons.SKIP_FORWARD_FIFTEEN, state.skipsEnabled, viewModel::forward15),
                        ),
                    )
                    LightText(state.position, variant = LightTextVariant.Detail, align = TextAlign.Center, modifier = centred)
                }
                LightBottomBar(
                    listOf(
                        LightBarButton.LightIcon(
                            LightIcons.LIST,
                            onClick = {
                                val at = viewModel.previewPositionMs
                                navigateTo<Unit>({ ChaptersScreen(it, book, factory, at) })
                            },
                        ),
                        downloadItem(state),
                        LightBarButton.Text(state.speedLabel, onClick = viewModel::cycleSpeed),
                    ),
                )
            }
        }
    }

    private fun downloadItem(state: PlayerUiState): LightBarButton {
        val onClick = {
            if (viewModel.downloadTapped()) {
                navigateTo({ ConfirmScreen(it, "Remove download?", "Remove") }) { remove ->
                    if (remove) viewModel.removeDownload()
                }
            }
        }
        return when (state.download) {
            DownloadControl.NotDownloaded -> LightBarButton.LightIcon(LightIcons.DOWNLOAD_ARROW, onClick = onClick)
            DownloadControl.Downloading -> LightBarButton.Text("${state.downloadPercent ?: 0}%", onClick = onClick)
            DownloadControl.Downloaded -> LightBarButton.LightIcon(LightIcons.DOWNLOADED_ARROW, onClick = onClick)
        }
    }
}
