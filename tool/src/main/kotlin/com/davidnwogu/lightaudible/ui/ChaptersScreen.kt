package com.davidnwogu.lightaudible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.davidnwogu.lightaudible.Graph
import com.davidnwogu.lightaudible.api.LibraryBook
import com.davidnwogu.lightaudible.playback.PlaybackFactory
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

private const val ROW_HEIGHT_UNITS = 2f

/** Chapter list for [book]. [previewPositionMs] marks the current row while the book is not loaded. */
class ChaptersScreen(
    activity: SealedLightActivity,
    private val book: LibraryBook,
    private val factory: PlaybackFactory,
    private val previewPositionMs: Long,
) : LightScreen<Unit, ChaptersViewModel>(activity) {
    private val graph = Graph.get(lightContext.dataStore, lightContext.filesDir)

    override val viewModelClass = ChaptersViewModel::class.java
    override fun createViewModel() = ChaptersViewModel(book, graph.playback, graph.chapters, factory, previewPositionMs)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val rows by viewModel.rows.collectAsState()
        SignedOutWatcher(graph.auth) { goBack() }
        LightTheme(colors = themeColors) {
            Column(modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = { goBack() }),
                    center = LightTopBarCenter.Text("Chapters"),
                )
                val listState = rememberLazyListState()
                // Chapters may arrive after the screen opens: scroll to the current row once, when first known.
                val currentIndex = rows.indexOfFirst { it.isCurrent }
                var scrolled by remember { mutableStateOf(false) }
                LaunchedEffect(currentIndex) {
                    if (!scrolled && currentIndex >= 0) {
                        listState.scrollToItem(currentIndex)
                        scrolled = true
                    }
                }
                LightLazyScrollView(
                    modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    listState = listState,
                    uniformItemHeightGridUnits = ROW_HEIGHT_UNITS,
                ) {
                    items(rows, key = { it.chapter.idx }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT_UNITS.gridUnitsAsDp())
                                .lightClickable {
                                    viewModel.select(row.chapter)
                                    goBack()
                                }
                                .padding(horizontal = 1f.gridUnitsAsDp()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.5f.gridUnitsAsDp()),
                        ) {
                            val weight = if (row.isCurrent) FontWeight.Bold else FontWeight.Normal
                            val typography = LightThemeTokens.typography
                            LightTheme(
                                colors = themeColors,
                                typography = typography.copy(copy = typography.copy.copy(fontWeight = weight)),
                            ) {
                                LightText(
                                    (if (row.isCurrent) "▸ " else "") + row.chapter.title,
                                    variant = LightTextVariant.Copy,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                LightText(
                                    row.length,
                                    variant = LightTextVariant.Detail,
                                    align = TextAlign.End,
                                    maxLines = 1,
                                    modifier = Modifier.widthIn(min = 3f.gridUnitsAsDp()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
