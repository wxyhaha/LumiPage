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
    fun `quad stabilizer acquires lock only after consecutive consistent frames`() {
        val stabilizer = QuadStabilizer(
            acquireFrames = 3,
            maxLockedMissedFrames = 4,
        )
        val baseQuad = DocumentQuad.DEFAULT_INSET

        val s1 = stabilizer.update(baseQuad)
        assertNull(s1.displayQuad)
        assertEquals(QuadStabilizer.TrackingPhase.SEARCHING, s1.phase)

        val s2 = stabilizer.update(baseQuad)
        assertNull(s2.displayQuad)

        val s3 = stabilizer.update(baseQuad)
        assertNotNull(s3.displayQuad)
        assertEquals(QuadStabilizer.TrackingPhase.LOCKED, s3.phase)
        assertTrue(s3.isReadyForAutoCapture)
    }

    @Test
    fun `quad stabilizer holds lock across brief missed frames`() {
        val stabilizer = QuadStabilizer(acquireFrames = 2, maxLockedMissedFrames = 2)
        val baseQuad = DocumentQuad.DEFAULT_INSET

        stabilizer.update(baseQuad)
        stabilizer.update(baseQuad)

        val missed1 = stabilizer.update(null)
        assertNotNull(missed1.displayQuad)
        assertEquals(QuadStabilizer.TrackingPhase.LOCKED, missed1.phase)

        stabilizer.update(null)
        val missed3 = stabilizer.update(null)
        assertNull(missed3.displayQuad)
        assertEquals(QuadStabilizer.TrackingPhase.SEARCHING, missed3.phase)
        assertFalse(missed3.isReadyForAutoCapture)
    }

    @Test
    fun `quad stabilizer ignores single-frame jump and requires confirmation`() {
        val stabilizer = QuadStabilizer(
            acquireFrames = 2,
            jumpConfirmFrames = 3,
        )
        val quadA = DocumentQuad.DEFAULT_INSET
        val quadB = DocumentQuad(
            topLeft = NormalizedPoint(0.4f, 0.4f),
            topRight = NormalizedPoint(0.95f, 0.4f),
            bottomRight = NormalizedPoint(0.95f, 0.95f),
            bottomLeft = NormalizedPoint(0.4f, 0.95f),
        )

        stabilizer.update(quadA)
        val lockedA = stabilizer.update(quadA)
        assertEquals(quadA, lockedA.displayQuad)

        // 单帧跳变保持原锁定框不动
        val jump1 = stabilizer.update(quadB)
        assertEquals(quadA, jump1.displayQuad)

        stabilizer.update(quadB)
        val jump3 = stabilizer.update(quadB)
        assertNotNull(jump3.displayQuad)
        assertTrue(jump3.displayQuad!!.topLeft.x > 0.3f)
    }

    @Test
    fun `quad stabilizer freezes display quad when scene is static`() {
        val stabilizer = QuadStabilizer(acquireFrames = 2, deadZoneThreshold = 0.01f)
        val quadA = DocumentQuad.DEFAULT_INSET
        val quadSlightDrift = DocumentQuad(
            topLeft = NormalizedPoint(0.11f, 0.09f),
            topRight = NormalizedPoint(0.91f, 0.09f),
            bottomRight = NormalizedPoint(0.91f, 0.91f),
            bottomLeft = NormalizedPoint(0.11f, 0.91f),
        )

        stabilizer.update(quadA, isSceneStatic = false)
        val locked = stabilizer.update(quadA, isSceneStatic = false)
        assertEquals(quadA, locked.displayQuad)

        // 即使检测框有 3% 的噪点漂移，只要画面静止，显示框必须纹丝不动
        val frozen = stabilizer.update(quadSlightDrift, isSceneStatic = true)
        assertEquals(quadA, frozen.displayQuad)
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
