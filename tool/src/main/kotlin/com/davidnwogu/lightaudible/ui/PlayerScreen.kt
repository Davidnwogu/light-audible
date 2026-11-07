package com.davidnwogu.lightaudible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.davidnwogu.lightaudible.Graph
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.playback.LightPlaybackFactory
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

class PlayerScreen(private val sealedActivity: SealedLightActivity, private val book: LibraryBook) :
    LightScreen<Unit, PlayerViewModel>(sealedActivity) {
    private val graph = Graph.get(lightContext.dataStore, lightContext.filesDir)

    override val viewModelClass = PlayerViewModel::class.java
    override fun createViewModel() =
        PlayerViewModel(book, graph.playback, graph.api, LightPlaybackFactory(DefaultLightAudio(sealedActivity)))

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
                LightText(state.title, variant = LightTextVariant.Title, modifier = pad)
                state.chapterTitle?.let { LightText(it, variant = LightTextVariant.Copy, modifier = pad) }
                LightText("${state.position} / ${state.duration}", variant = LightTextVariant.Detail, modifier = pad)
                state.statusLine?.let { LightText(it, variant = LightTextVariant.Detail, modifier = pad) }
                if (state.showRetry) {
                    LightText("Try again", variant = LightTextVariant.Copy, modifier = pad.lightClickable { viewModel.retry() })
                }
                LightText("−30 s", variant = LightTextVariant.Copy, modifier = pad.lightClickable { viewModel.back30() })
                LightText(
                    if (state.isPlaying) "Pause" else "Play",
                    variant = LightTextVariant.Copy,
                    modifier = pad.lightClickable { viewModel.togglePlay() },
                )
                LightText("+30 s", variant = LightTextVariant.Copy, modifier = pad.lightClickable { viewModel.forward30() })
                state.chapters.forEach { c ->
                    LightText(
                        "${c.title}  ${formatDuration(c.lengthMs)}",
                        variant = LightTextVariant.Detail,
                        modifier = pad.lightClickable { viewModel.jumpTo(c) },
                        lighten = c.title != state.chapterTitle,
                    )
                }
            }
        }
    }
}
