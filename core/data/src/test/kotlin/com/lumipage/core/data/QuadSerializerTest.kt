package com.lumipage.core.data

import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class QuadSerializerTest {

    @Test
    fun `encode and decode preserves quad coordinates`() {
        val quad = DocumentQuad(
            topLeft = NormalizedPoint(0.11f, 0.12f),
            topRight = NormalizedPoint(0.89f, 0.14f),
            bottomRight = NormalizedPoint(0.92f, 0.88f),
            bottomLeft = NormalizedPoint(0.08f, 0.91f),
        )

        val decoded = QuadSerializer.decode(QuadSerializer.encode(quad))
        assertEquals(quad.topLeft.x, decoded.topLeft.x, 1e-4f)
        assertEquals(quad.bottomRight.y, decoded.bottomRight.y, 1e-4f)
    }

    @Test
    fun `invalid string falls back to default inset quad`() {
        assertEquals(DocumentQuad.DEFAULT_INSET, QuadSerializer.decode("invalid"))
    }
}
