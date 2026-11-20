package com.lumipage.core.imaging

import android.graphics.Bitmap
import com.lumipage.core.model.EnhancementFilter
import com.lumipage.core.model.EnhancementParams
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 非破坏性文档图像增强处理器：
 * 支持原图、自动增强（去阴影/背景净化）、彩色文档、灰度增强、黑白二值化，
 * 以及亮度、对比度、锐化参数调节。
 */
class ImageEnhancer {

    fun enhance(
        source: Bitmap,
        params: EnhancementParams,
    ): Bitmap {
        if (!OpenCvInitializer.ensureInitialized()) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }

        val safeParams = params.clamped()
        return withMatScope {
            val rgba = newMat()
            Utils.bitmapToMat(source, rgba)

            val bgr = newMat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            val baseEnhanced = when (safeParams.filter) {
                EnhancementFilter.ORIGINAL -> bgr
                EnhancementFilter.AUTO_ENHANCE -> applyAutoEnhance(bgr)
                EnhancementFilter.DOCUMENT_COLOR -> applyDocumentColor(bgr)
                EnhancementFilter.GRAYSCALE -> applyGrayscaleEnhance(bgr)
                EnhancementFilter.BLACK_AND_WHITE -> applyBlackAndWhite(bgr)
            }

            val adjusted = applyToneAndSharpness(baseEnhanced, safeParams)
            val outRgba = newMat()
            Imgproc.cvtColor(adjusted, outRgba, Imgproc.COLOR_BGR2RGBA)

            val outBitmap = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, outBitmap)
            outBitmap
        }
    }

    /**
     * 自动增强：
     * 1. 通过降采样大核形态学闭运算估计背景光照场，消除阴影与光照不均
     * 2. 背景漂白 + 自适应对比度拉伸
     * 3. 保留原始 Cr/Cb 色彩（印章/批注/高亮）
     */
    private fun MatScope.applyAutoEnhance(bgr: Mat): Mat {
        val ycrcb = newMat()
        Imgproc.cvtColor(bgr, ycrcb, Imgproc.COLOR_BGR2YCrCb)
        val channels = List(3) { newMat() }
        Core.split(ycrcb, channels)

        val y = channels[0]
        val normalizedY = removeShadowFromLuminance(y)
        val stretchedY = stretchDocumentContrast(normalizedY, blackPercentile = 0.02, whitePercentile = 0.90)

        val merged = newMat()
        Core.merge(listOf(stretchedY, channels[1], channels[2]), merged)
        val result = newMat()
        Imgproc.cvtColor(merged, result, Imgproc.COLOR_YCrCb2BGR)
        return result
    }

    /**
     * 彩色文档：
     * 柔和去阴影并保留更丰富的彩色饱和度与纸张白度。
     */
    private fun MatScope.applyDocumentColor(bgr: Mat): Mat {
        val ycrcb = newMat()
        Imgproc.cvtColor(bgr, ycrcb, Imgproc.COLOR_BGR2YCrCb)
        val channels = List(3) { newMat() }
        Core.split(ycrcb, channels)

        val normalizedY = removeShadowFromLuminance(channels[0])
        // 与原图亮度按 75%/25% 混合，保留自然层次
        val blendedY = newMat()
        Core.addWeighted(normalizedY, 0.75, channels[0], 0.25, 0.0, blendedY)
        val stretchedY = stretchDocumentContrast(blendedY, blackPercentile = 0.015, whitePercentile = 0.92)

        // 适度增强色彩通道对比度（以 128 为中性点）
        val crBoost = boostChroma(channels[1], 1.18)
        val cbBoost = boostChroma(channels[2], 1.18)

        val merged = newMat()
        Core.merge(listOf(stretchedY, crBoost, cbBoost), merged)
        val result = newMat()
        Imgproc.cvtColor(merged, result, Imgproc.COLOR_YCrCb2BGR)
        return result
    }

    /**
     * 灰度增强：光照归一化 + 对比度拉伸。
     */
    private fun MatScope.applyGrayscaleEnhance(bgr: Mat): Mat {
        val gray = newMat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val normalized = removeShadowFromLuminance(gray)
        val stretched = stretchDocumentContrast(normalized, blackPercentile = 0.02, whitePercentile = 0.88)
        val outBgr = newMat()
        Imgproc.cvtColor(stretched, outBgr, Imgproc.COLOR_GRAY2BGR)
        return outBgr
    }

    /**
     * 黑白文档：
     * 光照归一化 + 轻度去噪 + 局部自适应阈值二值化，保留锐利文字同时消除背景灰斑。
     */
    private fun MatScope.applyBlackAndWhite(bgr: Mat): Mat {
        val gray = newMat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val normalized = removeShadowFromLuminance(gray)

        val denoised = newMat()
        Imgproc.GaussianBlur(normalized, denoised, Size(3.0, 3.0), 0.0)

        val blockSize = makeOdd((min(bgr.cols(), bgr.rows()) / 28).coerceIn(15, 45))
        val binary = newMat()
        Imgproc.adaptiveThreshold(
            denoised,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            blockSize,
            11.0,
        )

        val outBgr = newMat()
        Imgproc.cvtColor(binary, outBgr, Imgproc.COLOR_GRAY2BGR)
        return outBgr
    }

    /**
     * 通过降采样大核形态学闭运算估计背景光照场，并执行除法归一化：
     * out = clamp(src / background * 255)
     */
    private fun MatScope.removeShadowFromLuminance(luma: Mat): Mat {
        val w = luma.cols()
        val h = luma.rows()
        val maxSide = max(w, h)
        val scale = if (maxSide > 480) 480.0 / maxSide.toDouble() else 1.0

        val small = if (scale < 1.0) {
            newMat().also { dst ->
                Imgproc.resize(
                    luma,
                    dst,
                    Size(max(8, (w * scale).roundToInt()).toDouble(), max(8, (h * scale).roundToInt()).toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            }
        } else {
            luma
        }

        val kernelSize = makeOdd((min(small.cols(), small.rows()) / 12).coerceIn(15, 41))
        val kernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kernelSize.toDouble(), kernelSize.toDouble())))
        val smallBg = newMat()
        Imgproc.morphologyEx(small, smallBg, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.GaussianBlur(smallBg, smallBg, Size(15.0, 15.0), 0.0)

        val bg = if (scale < 1.0) {
            newMat().also { dst ->
                Imgproc.resize(smallBg, dst, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            }
        } else {
            smallBg
        }

        val srcFloat = newMat()
        val bgFloat = newMat()
        luma.convertTo(srcFloat, CvType.CV_32F)
        bg.convertTo(bgFloat, CvType.CV_32F)
        Core.max(bgFloat, Scalar(12.0), bgFloat)

        val divided = newMat()
        Core.divide(srcFloat, bgFloat, divided, 255.0)
        val out = newMat()
        divided.convertTo(out, CvType.CV_8U)
        return out
    }

    /**
     * 基于亮度直方图分位数的文档对比度拉伸，使纸张背景更洁白、文字更扎实。
     */
    private fun MatScope.stretchDocumentContrast(
        luma: Mat,
        blackPercentile: Double,
        whitePercentile: Double,
    ): Mat {
        val hist = newMat()
        val channels = org.opencv.core.MatOfInt(0)
        val histSize = org.opencv.core.MatOfInt(256)
        val ranges = org.opencv.core.MatOfFloat(0f, 256f)
        try {
            Imgproc.calcHist(listOf(luma), channels, Mat(), hist, histSize, ranges)
            val totalPixels = (luma.rows() * luma.cols()).toDouble()
            val blackTarget = totalPixels * blackPercentile
            val whiteTarget = totalPixels * whitePercentile

            var cumSum = 0.0
            var lowVal = 0
            var highVal = 255
            var foundLow = false
            for (i in 0 until 256) {
                cumSum += hist.get(i, 0)[0]
                if (!foundLow && cumSum >= blackTarget) {
                    lowVal = i
                    foundLow = true
                }
                if (cumSum >= whiteTarget) {
                    highVal = i
                    break
                }
            }

            if (highVal <= lowVal + 16) return luma
            val alpha = 255.0 / (highVal - lowVal).toDouble()
            val beta = -lowVal * alpha

            val out = newMat()
            luma.convertTo(out, CvType.CV_8U, alpha, beta)
            return out
        } finally {
            channels.release()
            histSize.release()
            ranges.release()
        }
    }

    private fun MatScope.boostChroma(channel: Mat, factor: Double): Mat {
        val f = newMat()
        channel.convertTo(f, CvType.CV_32F)
        Core.subtract(f, Scalar(128.0), f)
        Core.multiply(f, Scalar(factor), f)
        Core.add(f, Scalar(128.0), f)
        val out = newMat()
        f.convertTo(out, CvType.CV_8U)
        return out
    }

    private fun MatScope.applyToneAndSharpness(
        bgr: Mat,
        params: EnhancementParams,
    ): Mat {
        var current = bgr

        // 亮度与对比度调节
        if (params.brightness != 0f || params.contrast != 1f) {
            val alpha = params.contrast.toDouble()
            val beta = params.brightness * 60.0 + (1.0 - alpha) * 128.0
            val toned = newMat()
            current.convertTo(toned, CvType.CV_8U, alpha, beta)
            current = toned
        }

        // 非锐化掩蔽 (Unsharp Mask)
        if (params.sharpness > 0.02f) {
            val blurred = newMat()
            Imgproc.GaussianBlur(current, blurred, Size(0.0, 0.0), 1.4)
            val amount = params.sharpness.toDouble() * 1.1
            val sharpened = newMat()
            Core.addWeighted(current, 1.0 + amount, blurred, -amount, 0.0, sharpened)
            current = sharpened
        }

        return current
    }

    private fun makeOdd(value: Int): Int = if (value % 2 == 0) value + 1 else value
}
