package com.davidnwogu.lightaudible.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightThemeTokens

/** Draws [base] in a single [tint], as the kit's LightIcon does with the theme's content colour. */
private class TintedPainter(private val base: Painter, private val tint: Color) : Painter() {
    override val intrinsicSize: Size get() = base.intrinsicSize
    override fun DrawScope.onDraw() = with(base) { draw(size, colorFilter = ColorFilter.tint(tint)) }
}

/**
 * A bar icon that is drawn lighter (the theme's secondary content colour, as the kit lightens disabled text)
 * while disabled. The kit's [LightBarButton.LightIcon] draws disabled icons at full brightness.
 */
@Composable
fun tintedBarIcon(icon: LightIconConfiguration, enabled: Boolean, onClick: () -> Unit): LightBarButton {
    val base = painterResource(icon.drawableResource)
    val colors = LightThemeTokens.colors
    val tint = if (enabled) colors.content else colors.contentSecondary
    val painter = remember(base, tint) { TintedPainter(base, tint) }
    return LightBarButton.Icon(painter = painter, onClick = onClick, contentDescription = icon.name, enabled = enabled)
}
