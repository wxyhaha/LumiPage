package com.lumipage.core.imaging

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 基于 PP-LCNet_x1_0_doc_ori (LiteRT) 的离线文档文字朝向分类器（0°/90°/180°/270°）。
 *
 * 输入: [1, 224, 224, 3] NHWC RGB float32（短边缩放至 256 后中心裁剪 224x224，ImageNet 归一化）
 * 输出: [1, 4] float32，对应当前图像相对正立方向顺时针偏转的角度 [0, 90, 180, 270]
 */
class TextOrientationDetector(
    context: Context,
) {
    data class OrientationResult(
        /** 需要顺时针旋转的 90° 步数（0..3），使得文字恢复正立。 */
        val clockwiseSteps: Int,
        val confidence: Float,
    ) {
        val clockwiseCorrectionDegrees: Int
            get() = (clockwiseSteps % 4) * 90
    }

    private val appContext = context.applicationContext

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var loadFailed: Boolean = false

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * CROP_SIZE * CROP_SIZE * 3)
        .order(ByteOrder.nativeOrder())

    private val pixelScratch = IntArray(CROP_SIZE * CROP_SIZE)

    @Synchronized
    fun detect(bitmap: Bitmap): OrientationResult {
        val interp = getOrCreateInterpreter() ?: return OrientationResult(0, 0f)
        if (bitmap.width < 32 || bitmap.height < 32) {
            return OrientationResult(0, 0f)
        }

        val scale = RESIZE_SHORT.toFloat() / min(bitmap.width, bitmap.height).toFloat()
        val nw = (bitmap.width * scale).roundToInt().coerceAtLeast(CROP_SIZE)
        val nh = (bitmap.height * scale).roundToInt().coerceAtLeast(CROP_SIZE)
        val resized = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val x0 = ((nw - CROP_SIZE) / 2).coerceAtLeast(0)
        val y0 = ((nh - CROP_SIZE) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(resized, x0, y0, CROP_SIZE, CROP_SIZE)
        if (resized !== bitmap && resized !== cropped) {
            resized.recycle()
        }

        try {
            cropped.getPixels(pixelScratch, 0, CROP_SIZE, 0, 0, CROP_SIZE, CROP_SIZE)
        } finally {
            if (cropped !== bitmap) {
                cropped.recycle()
            }
        }

        inputBuffer.rewind()
        for (i in pixelScratch.indices) {
            val px = pixelScratch[i]
            val r = ((px ushr 16) and 0xFF) / 255.0f
            val g = ((px ushr 8) and 0xFF) / 255.0f
            val b = (px and 0xFF) / 255.0f
            inputBuffer.putFloat((r - MEAN_R) / STD_R)
            inputBuffer.putFloat((g - MEAN_G) / STD_G)
            inputBuffer.putFloat((b - MEAN_B) / STD_B)
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(4) }
        interp.run(inputBuffer, output)
        val probs = output[0]

        var bestIdx = 0
        var bestProb = probs[0]
        var secondProb = Float.NEGATIVE_INFINITY
        for (i in 1 until 4) {
            val p = probs[i]
            if (p > bestProb) {
                secondProb = bestProb
                bestProb = p
                bestIdx = i
            } else if (p > secondProb) {
                secondProb = p
            }
        }

        // 置信度不足（如纯白纸或无文字图案）时不强行旋转
        if (bestProb < MIN_CONFIDENCE || (bestProb - secondProb) < MIN_MARGIN) {
            return OrientationResult(clockwiseSteps = 0, confidence = bestProb)
        }

        // 模型输出类别含义：当前图像已顺时针偏转了 [0°, 90°, 180°, 270°]
        // 因此恢复正立需要再顺时针旋转 [0, 3, 2, 1] 个 90°
        val steps = when (bestIdx) {
            1 -> 3
            2 -> 2
            3 -> 1
            else -> 0
        }
        return OrientationResult(clockwiseSteps = steps, confidence = bestProb)
    }

    private fun getOrCreateInterpreter(): Interpreter? {
        interpreter?.let { return it }
        if (loadFailed) return null
        return synchronized(this) {
            interpreter?.let { return it }
            if (loadFailed) return null
            runCatching {
                val model = loadModelFile(MODEL_ASSET_PATH)
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                Interpreter(model, options)
            }.onSuccess {
                interpreter = it
            }.onFailure {
                loadFailed = true
            }.getOrNull()
        }
    }

    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        val afd = appContext.assets.openFd(assetPath)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength,
            )
        }
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/doc_ori.tflite"
        private const val RESIZE_SHORT = 256
        private const val CROP_SIZE = 224
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f
        private const val MIN_CONFIDENCE = 0.55f
        private const val MIN_MARGIN = 0.15f
    }
}
