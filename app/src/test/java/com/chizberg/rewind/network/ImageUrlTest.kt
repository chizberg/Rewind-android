package com.chizberg.rewind.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUrlTest {
    @Test
    fun stripsGarbageQueryFromImagePath() {
        // `file` carries a `?s=...` garbage query (quirk #4) that must be dropped or the CDN 404s.
        val url = imageUrl("q/q/p/qqp52d1i1jn4qndllt.jpg?s=81293f61a6", ImageQuality.Low)
        assertEquals("https://img.pastvu.com/s/q/q/p/qqp52d1i1jn4qndllt.jpg", url)
    }
}
