package com.chizberg.rewind.features.imagelist

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCardTitleStyleTest {
    @Test
    fun titleStyleFollowsTheBackdropQuartiles() {
        assertEquals(
            ImageCardTitleStyle.Dark,
            ImageCardTitleStyle.of(floatArrayOf(0.5f, 0.6f, 0.45f, 0.9f)),
        )
        assertEquals(
            ImageCardTitleStyle.Plain,
            ImageCardTitleStyle.of(floatArrayOf(0.1f, 0.18f, 0.02f, 0.9f)),
        )

        val dimmed = ImageCardTitleStyle.of(floatArrayOf(0.5f, 0.2f, 0.5f, 0.2f))
        assertEquals(0.3727f, (dimmed as ImageCardTitleStyle.Dimmed).opacity, 0.001f)
    }
}
