package com.davidnwogu.lightaudible.ui

import androidx.compose.runtime.Composable
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/** Stub until Task 6. */
class LibraryScreen(activity: SealedLightActivity) : SimpleLightScreen<Unit>(activity) {
    @Composable
    override fun Content() {
        LightText("Library", variant = LightTextVariant.Title)
    }
}
