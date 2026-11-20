package com.lumipage.feature.scanner

import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.NormalizedPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class QuadCoordinateMapperTest {

    @Test
    fun `wider analysis frame scales x beyond center and keeps y unchanged`() {
        val quad = DocumentQuad(
            topLeft = NormalizedPoint(0.25f, 0.2f),
            topRight = NormalizedPoint(0.75f, 0.2f),
            bottomRight = NormalizedPoint(0.75f, 0.8f),
            bottomLeft = NormalizedPoint(0.25f, 0.8f),
        )

        // 16:9 帧映射到 4:3 预览窗口
        val mapped = QuadCoordinateMapper.mapAnalysisQuadToPreview(
            quad = quad,
            analysisWidth = 1600,
            analysisHeight = 900,
            previewWidth = 1200,
            previewHeight = 900,
        )

        // y 保持不变
        assertEquals(0.2f, mapped.topLeft.y, 1e-4f)
        assertEquals(0.8f, mapped.bottomRight.y, 1e-4f)
        // x 从中心 0.5 按 (16/9)/(4/3) = 4/3 倍展开：0.5 + (0.25 - 0.5)*(4/3) = 0.16667
        assertEquals(0.1667f, mapped.topLeft.x, 1e-3f)
        assertEquals(0.8333f, mapped.topRight.x, 1e-3f)
    }
}
