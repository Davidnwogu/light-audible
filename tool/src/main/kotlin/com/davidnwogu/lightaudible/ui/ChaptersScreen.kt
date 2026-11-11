package com.davidnwogu.lightaudible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/** Stub: Task 6 builds the chapter list. [previewPositionMs] is where ▶ resumes while the book is not loaded. */
class ChaptersScreen(
    activity: SealedLightActivity,
    @Suppress("unused") private val book: LibraryBook,
    @Suppress("unused") private val factory: PlaybackFactory,
    @Suppress("unused") private val previewPositionMs: Long,
) : SimpleLightScreen<Unit>(activity) {
    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Chapters"),
                )
            }
        }
    }
}
