package com.chizberg.rewind.ui

import androidx.compose.ui.graphics.Color
import com.chizberg.rewind.domain.RgbaColor

/** UI-boundary conversion from the domain's [RgbaColor] to a Compose [Color]. */
fun RgbaColor.toComposeColor(): Color =
    Color(
        red = red.toFloat(),
        green = green.toFloat(),
        blue = blue.toFloat(),
        alpha = alpha.toFloat(),
    )
