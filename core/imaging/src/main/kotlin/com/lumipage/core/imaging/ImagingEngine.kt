package com.lumipage.core.imaging

import android.content.Context
import android.graphics.Bitmap
import com.lumipage.core.common.DispatcherProvider
import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.PageEditState
import kotlinx.coroutines.withContext

/**
 * 图像处理引擎统一门面，负责协调文档检测、透视矫正与图像增强。
 */
class ImagingEngine(
    private val dispatchers: DispatcherProvider,
    context: Context? = null,
    private val detector: DocumentDetector = DocumentDetector(context),
    private val perspectiveCorrector: PerspectiveCorrector = PerspectiveCorrector(),
    private val imageEnhancer: ImageEnhancer = ImageEnhancer(),
    private val orientationDetector: TextOrientationDetector? = context?.let { TextOrientationDetector(it) },
) {
    suspend fun detectDocument(
        bitmap: Bitmap,
        resetTemporal: Boolean = false,
    ): DocumentDetector.DetectionResult =
        withContext(dispatchers.default) {
            detector.detect(bitmap, resetTemporal = resetTemporal)
        }

    suspend fun renderPage(
        source: Bitmap,
        editState: PageEditState,
        maxOutputDimension: Int = 4096,
    ): Bitmap = withContext(dispatchers.default) {
        val warped = perspectiveCorrector.warpAndRotate(
            source = source,
            quad = editState.quad,
            rotationDegrees = editState.normalizedRotation,
            maxOutputDimension = maxOutputDimension,
        )
        try {
            imageEnhancer.enhance(warped, editState.enhancement)
        } finally {
            if (warped !== source && !warped.isRecycled) {
                warped.recycle()
            }
        }
    }

    suspend fun warpOnly(
        source: Bitmap,
        quad: DocumentQuad,
        rotationDegrees: Int = 0,
        maxOutputDimension: Int = 2048,
    ): Bitmap = withContext(dispatchers.default) {
        perspectiveCorrector.warpAndRotate(
            source = source,
            quad = quad,
            rotationDegrees = rotationDegrees,
            maxOutputDimension = maxOutputDimension,
        )
    }

    suspend fun enhanceBitmap(
        warpedSource: Bitmap,
        params: com.lumipage.core.model.EnhancementParams,
    ): Bitmap = withContext(dispatchers.default) {
        imageEnhancer.enhance(warpedSource, params)
    }

    /**
     * 对裁剪后的页面自动检测文字朝向，返回使其正立所需的顺时针 90° 旋转步数（0..3）。
     */
    suspend fun detectAutoRotationSteps(
        source: Bitmap,
        quad: DocumentQuad,
        currentRotationDegrees: Int = 0,
    ): Int = withContext(dispatchers.default) {
        val detectorInstance = orientationDetector ?: return@withContext 0
        val preview = perspectiveCorrector.warpAndRotate(
            source = source,
            quad = quad,
            rotationDegrees = currentRotationDegrees,
            maxOutputDimension = 640,
            autoStraighten = false,
        )
        try {
            detectorInstance.detect(preview).clockwiseSteps
        } finally {
            if (preview !== source && !preview.isRecycled) {
                preview.recycle()
            }
        }
    }
}
