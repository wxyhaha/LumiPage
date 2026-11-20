package com.lumipage.core.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.NormalizedPoint
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * 基于 DocAligner FastViT-T8 ONNX 模型的端侧深度学习文档四角点检测器。
 * 输入: [1, 3, 256, 256] BGR float32 [0..1]
 * 输出: [1, 4, 128, 128] 4 角点热力图
 */
class OnnxDocumentDetector(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var loadFailed: Boolean = false

    private fun getOrLoadSession(): OrtSession? {
        if (loadFailed) return null
        session?.let { return it }
        synchronized(this) {
            if (loadFailed) return null
            session?.let { return it }
            return try {
                val modelBytes = appContext.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                ortEnv.createSession(modelBytes, opts).also {
                    session = it
                    android.util.Log.i("LumiScan", "FastViT-T8 ONNX session loaded successfully (${modelBytes.size} bytes)")
                }
            } catch (t: Throwable) {
                android.util.Log.e("LumiScan", "Failed to load FastViT-T8 ONNX model", t)
                loadFailed = true
                null
            }
        }
    }

    fun detect(bitmap: Bitmap): DocumentDetector.DetectionResult? {
        val ortSession = getOrLoadSession() ?: return null

        // Letterbox 等比缩放进 256x256 正方形（灰色/黑色填充），消除全面屏宽高比畸变
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = min(INPUT_SIZE.toFloat() / srcW.toFloat(), INPUT_SIZE.toFloat() / srcH.toFloat())
        val resizedW = max(1, (srcW * scale).toInt())
        val resizedH = max(1, (srcH * scale).toInt())
        val padLeft = (INPUT_SIZE - resizedW) / 2
        val padTop = (INPUT_SIZE - resizedH) / 2

        val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val scaled = if (srcW == resizedW && srcH == resizedH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        try {
            val canvas = android.graphics.Canvas(letterboxed)
            canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
            canvas.drawBitmap(scaled, padLeft.toFloat(), padTop.toFloat(), null)
            letterboxed.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (!letterboxed.isRecycled) {
                letterboxed.recycle()
            }
        }

        // 构造 NCHW BGR [0..1] 张量
        val planeSize = INPUT_SIZE * INPUT_SIZE
        val inputData = FloatArray(3 * planeSize)
        for (i in 0 until planeSize) {
            val argb = pixels[i]
            val r = ((argb ushr 16) and 0xFF) * INV_255
            val g = ((argb ushr 8) and 0xFF) * INV_255
            val b = (argb and 0xFF) * INV_255
            inputData[i] = b
            inputData[planeSize + i] = g
            inputData[2 * planeSize + i] = r
        }

        val inputBuffer = FloatBuffer.wrap(inputData)
        val inputShape = longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())

        return OnnxTensor.createTensor(ortEnv, inputBuffer, inputShape).use { inputTensor ->
            ortSession.run(mapOf(INPUT_NAME to inputTensor)).use { outputs ->
                val outTensor = outputs[0] as? OnnxTensor ?: return null
                val hmBuffer = outTensor.floatBuffer
                val hmSize = HEATMAP_SIZE * HEATMAP_SIZE
                if (hmBuffer.remaining() < 4 * hmSize) return null

                val corners = ArrayList<NormalizedPoint>(4)
                var minPeak = Float.MAX_VALUE
                var sumPeak = 0f

                for (c in 0 until 4) {
                    val baseOffset = c * hmSize
                    var peakVal = 0f
                    var peakIdx = 0
                    for (idx in 0 until hmSize) {
                        val v = hmBuffer.get(baseOffset + idx)
                        if (v > peakVal) {
                            peakVal = v
                            peakIdx = idx
                        }
                    }

                    minPeak = min(minPeak, peakVal)
                    sumPeak += peakVal

                    if (peakVal < MIN_CORNER_PEAK) {
                        android.util.Log.i("LumiScan", "ONNX skip: corner=$c peak=$peakVal < $MIN_CORNER_PEAK")
                        return null
                    }

                    // 在峰值 5x5 邻域计算高锐度加权质心，既实现亚像素平滑又避免大半径把角点向内拉偏
                    val thresh = max(0.15f, peakVal * 0.72f)
                    val peakY = peakIdx / HEATMAP_SIZE
                    val peakX = peakIdx % HEATMAP_SIZE
                    val radius = 2
                    val yStart = max(0, peakY - radius)
                    val yEnd = min(HEATMAP_SIZE - 1, peakY + radius)
                    val xStart = max(0, peakX - radius)
                    val xEnd = min(HEATMAP_SIZE - 1, peakX + radius)

                    var weightSum = 0.0
                    var wxSum = 0.0
                    var wySum = 0.0
                    for (y in yStart..yEnd) {
                        val rowOffset = baseOffset + y * HEATMAP_SIZE
                        for (x in xStart..xEnd) {
                            val w = hmBuffer.get(rowOffset + x)
                            if (w >= thresh) {
                                val wd = w.toDouble()
                                weightSum += wd
                                wxSum += wd * (x + 0.5)
                                wySum += wd * (y + 0.5)
                            }
                        }
                    }

                    val hmX = if (weightSum > 1e-6) {
                        (wxSum / weightSum / HEATMAP_SIZE.toDouble()).toFloat()
                    } else {
                        (peakX + 0.5f) / HEATMAP_SIZE.toFloat()
                    }
                    val hmY = if (weightSum > 1e-6) {
                        (wySum / weightSum / HEATMAP_SIZE.toDouble()).toFloat()
                    } else {
                        (peakY + 0.5f) / HEATMAP_SIZE.toFloat()
                    }
                    // 将 Letterbox 256x256 空间坐标还原回原图归一化坐标
                    val origX = ((hmX * INPUT_SIZE - padLeft) / resizedW.toFloat()).coerceIn(0f, 1f)
                    val origY = ((hmY * INPUT_SIZE - padTop) / resizedH.toFloat()).coerceIn(0f, 1f)
                    corners.add(NormalizedPoint(origX, origY))
                }

                val avgPeak = sumPeak / 4f
                if (avgPeak < MIN_AVG_PEAK) {
                    android.util.Log.i("LumiScan", "ONNX skip: avgPeak=$avgPeak < $MIN_AVG_PEAK")
                    return null
                }

                val quad = DocumentQuad.fromUnorderedPoints(corners)
                if (!quad.isValidConvexQuad(minArea = 0.08f)) return null
                if (!hasValidCornerAngles(quad)) return null

                DocumentDetector.DetectionResult(
                    quad = quad,
                    confidence = avgPeak.coerceIn(0f, 1f),
                )
            }
        }
    }

    private fun hasValidCornerAngles(quad: DocumentQuad): Boolean {
        val pts = quad.asList()
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val curr = pts[i]
            val next = pts[(i + 1) % 4]
            val dx1 = (prev.x - curr.x).toDouble()
            val dy1 = (prev.y - curr.y).toDouble()
            val dx2 = (next.x - curr.x).toDouble()
            val dy2 = (next.y - curr.y).toDouble()
            val denom = kotlin.math.hypot(dx1, dy1) * kotlin.math.hypot(dx2, dy2)
            if (denom < 1e-6) return false
            val cosVal = kotlin.math.abs((dx1 * dx2 + dy1 * dy2) / denom)
            if (cosVal > 0.65) return false // 拒绝 < ~50° 的退化尖角
        }
        return true
    }

    private companion object {
        const val MODEL_ASSET_PATH = "models/fastvit_sa24.onnx"
        const val INPUT_NAME = "img"
        const val INPUT_SIZE = 256
        const val HEATMAP_SIZE = 128
        const val INV_255 = 1f / 255f
        const val MIN_CORNER_PEAK = 0.25f
        const val MIN_AVG_PEAK = 0.40f
    }
}
