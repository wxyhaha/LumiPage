package com.lumipage.core.imaging

import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

class ImagingCoreLogicTest {

    @Test
    fun `quad stabilizer triggers auto capture after required stable frames`() {
        val stabilizer = QuadStabilizer(
            requiredStableFrames = 4,
            maxMissedFrames = 2,
            stabilityCornerThreshold = 0.02f,
        )
        val baseQuad = DocumentQuad.DEFAULT_INSET

        val s1 = stabilizer.update(baseQuad)
        assertFalse(s1.isReadyForAutoCapture)
        assertEquals(1, s1.stableFrameCount)

        stabilizer.update(baseQuad)
        stabilizer.update(baseQuad)
        val s4 = stabilizer.update(baseQuad)
        assertTrue(s4.isReadyForAutoCapture)
        assertEquals(1.0f, s4.stabilityProgress, 1e-5f)
    }

    @Test
    fun `quad stabilizer tolerates brief missed frames before resetting`() {
        val stabilizer = QuadStabilizer(requiredStableFrames = 5, maxMissedFrames = 2)
        val baseQuad = DocumentQuad.DEFAULT_INSET

        stabilizer.update(baseQuad)
        stabilizer.update(baseQuad)

        val missed1 = stabilizer.update(null)
        assertNotNull(missed1.displayQuad)
        assertFalse(missed1.isReadyForAutoCapture)

        stabilizer.update(null)
        val missed3 = stabilizer.update(null)
        assertNull(missed3.displayQuad)
        assertEquals(0, missed3.stableFrameCount)
    }

    @Test
    fun `quad stabilizer resets when quad jumps significantly`() {
        val stabilizer = QuadStabilizer(requiredStableFrames = 4)
        val quadA = DocumentQuad.DEFAULT_INSET
        val quadB = DocumentQuad(
            topLeft = NormalizedPoint(0.4f, 0.4f),
            topRight = NormalizedPoint(0.95f, 0.4f),
            bottomRight = NormalizedPoint(0.95f, 0.95f),
            bottomLeft = NormalizedPoint(0.4f, 0.95f),
        )

        stabilizer.update(quadA)
        stabilizer.update(quadA)
        val jumped = stabilizer.update(quadB)
        assertEquals(1, jumped.stableFrameCount)
        assertFalse(jumped.isReadyForAutoCapture)
    }

    @Test
    fun `perspective target size preserves aspect and respects max dimension`() {
        val (w, h) = PerspectiveCorrector.calculateTargetSize(
            tl = Point(0.0, 0.0),
            tr = Point(3000.0, 0.0),
            br = Point(3000.0, 6000.0),
            bl = Point(0.0, 6000.0),
            maxOutputDimension = 4000,
        )
        assertEquals(2000, w)
        assertEquals(4000, h)
    }
}
