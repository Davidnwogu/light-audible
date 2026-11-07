package com.davidnwogu.lightaudible.ui

import androidx.compose.runtime.Composable
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

@InitialScreen
class SetupScreen(activity: SealedLightActivity) : SimpleLightScreen<Unit>(activity) {
    @Composable
    override fun Content() {
        LightText("Audiobooks", variant = LightTextVariant.Title)
    }
}
