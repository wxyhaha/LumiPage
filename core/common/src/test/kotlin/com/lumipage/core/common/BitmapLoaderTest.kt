package com.lumipage.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapLoaderTest {

    @Test
    fun `calculateInSampleSize halves dimensions until within threshold`() {
        assertEquals(1, BitmapLoader.calculateInSampleSize(1200, 1600, 2048))
        assertEquals(2, BitmapLoader.calculateInSampleSize(4000, 3000, 2048))
        assertEquals(4, BitmapLoader.calculateInSampleSize(8000, 6000, 1600))
    }
}
