package com.davidnwogu.lightaudible.ui

import androidx.compose.runtime.Composable
import com.davidnwogu.lightaudible.api.LibraryBook
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/** Stub until Task 7. */
class BookScreen(activity: SealedLightActivity, private val book: LibraryBook) : SimpleLightScreen<Unit>(activity) {
    @Composable
    override fun Content() {
        LightText(book.title, variant = LightTextVariant.Title)
    }
}
