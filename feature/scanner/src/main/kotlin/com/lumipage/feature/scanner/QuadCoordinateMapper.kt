package com.lumipage.feature.scanner

import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.NormalizedPoint

/**
 * 将相机分析帧（已考虑旋转后的宽高）的归一化坐标映射至 FILL_CENTER 裁剪模式下的 PreviewView 归一化坐标。
 */
object QuadCoordinateMapper {

    fun mapAnalysisQuadToPreview(
        quad: DocumentQuad,
        analysisWidth: Int,
        analysisHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
    ): DocumentQuad {
        if (analysisWidth <= 0 || analysisHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) {
            return quad
        }

        val frameAspect = analysisWidth.toFloat() / analysisHeight.toFloat()
        val viewAspect = previewWidth.toFloat() / previewHeight.toFloat()

        // FILL_CENTER：等比缩放使短边铺满，长边超出部分居中裁剪
        val scaleX: Float
        val scaleY: Float
        if (frameAspect > viewAspect) {
            // 帧更宽，左右被裁剪
            scaleX = frameAspect / viewAspect
            scaleY = 1f
        } else {
            // 帧更高，上下被裁剪
            scaleX = 1f
            scaleY = viewAspect / frameAspect
        }

        fun mapPoint(pt: NormalizedPoint): NormalizedPoint {
            val mappedX = (pt.x - 0.5f) * scaleX + 0.5f
            val mappedY = (pt.y - 0.5f) * scaleY + 0.5f
            return NormalizedPoint(mappedX.coerceIn(0f, 1f), mappedY.coerceIn(0f, 1f))
        }

        return DocumentQuad(
            topLeft = mapPoint(quad.topLeft),
            topRight = mapPoint(quad.topRight),
            bottomRight = mapPoint(quad.bottomRight),
            bottomLeft = mapPoint(quad.bottomLeft),
        )
    }
}
