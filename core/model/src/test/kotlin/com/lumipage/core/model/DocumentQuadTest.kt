package com.lumipage.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentQuadTest {

    @Test
    fun `fromUnorderedPoints orders clockwise starting from topLeft`() {
        val shuffled = listOf(
            NormalizedPoint(0.9f, 0.85f), // bottomRight
            NormalizedPoint(0.1f, 0.12f), // topLeft
            NormalizedPoint(0.15f, 0.88f), // bottomLeft
            NormalizedPoint(0.85f, 0.1f), // topRight
        )

        val quad = DocumentQuad.fromUnorderedPoints(shuffled)
        assertEquals(NormalizedPoint(0.1f, 0.12f), quad.topLeft)
        assertEquals(NormalizedPoint(0.85f, 0.1f), quad.topRight)
        assertEquals(NormalizedPoint(0.9f, 0.85f), quad.bottomRight)
        assertEquals(NormalizedPoint(0.15f, 0.88f), quad.bottomLeft)
        assertTrue(quad.isValidConvexQuad())
    }

    @Test
    fun `non-convex bow-tie quad is rejected`() {
        val bowTie = DocumentQuad(
            topLeft = NormalizedPoint(0.1f, 0.1f),
            topRight = NormalizedPoint(0.9f, 0.9f),
            bottomRight = NormalizedPoint(0.9f, 0.1f),
            bottomLeft = NormalizedPoint(0.1f, 0.9f),
        )
        assertFalse(bowTie.isValidConvexQuad())
    }

    @Test
    fun `tiny quad below minArea is rejected`() {
        val tiny = DocumentQuad(
            topLeft = NormalizedPoint(0.49f, 0.49f),
            topRight = NormalizedPoint(0.51f, 0.49f),
            bottomRight = NormalizedPoint(0.51f, 0.51f),
            bottomLeft = NormalizedPoint(0.49f, 0.51f),
        )
        assertFalse(tiny.isValidConvexQuad(minArea = 0.02f))
    }

    @Test
    fun `enhancement params are clamped to safe bounds`() {
        val params = EnhancementParams(
            filter = EnhancementFilter.AUTO_ENHANCE,
            brightness = 5f,
            contrast = 0.1f,
            sharpness = -2f,
        ).clamped()

        assertEquals(1f, params.brightness, 1e-5f)
        assertEquals(0.5f, params.contrast, 1e-5f)
        assertEquals(0f, params.sharpness, 1e-5f)
    }
}
