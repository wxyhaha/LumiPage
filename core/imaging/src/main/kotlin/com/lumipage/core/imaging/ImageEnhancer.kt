package com.lumipage.core.imaging

import android.graphics.Bitmap
import com.lumipage.core.model.EnhancementFilter
import com.lumipage.core.model.EnhancementParams
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 商业级非破坏性文档图像增强处理器：
 * 1. 自动增强：光照场去阴影 + 软前景/背景分离去透字 + 非线性 S 曲线压黑 + 选择性色彩保护（印章/批注）
 * 2. 彩色文档：柔和去阴影 + 背景漂白 + 丰富色彩还原
 * 3. 灰度增强：干净均匀的激光打印级灰度
 * 4. 黑白文档：基于局部均值与标准差的高速 Sauvola 自适应二值化（空白纯白零噪点）
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
                EnhancementFilter.BLACK_AND_WHITE -> applySauvolaBlackAndWhite(bgr)
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
     * 自动增强（智能高清扫描）：
     * - 降采样大核闭运算估计背景光照场，消除书脊与手指阴影
     * - 非线性 S 曲线压黑文字并积极漂白纸张底色、抑制背面透字
     * - HSV 饱和度掩膜选择性色彩保护（低饱和背景去黄漂白，保留红章/蓝字/荧光笔）
     */
    private fun MatScope.applyAutoEnhance(bgr: Mat): Mat {
        val ycrcb = newMat()
        Imgproc.cvtColor(bgr, ycrcb, Imgproc.COLOR_BGR2YCrCb)
        val channels = List(3) { newMat() }
        Core.split(ycrcb, channels)

        val yNorm = removeShadowFromLuminance(channels[0])
        val yClean = enhanceDocumentLuminance(yNorm, whitePercentile = 0.82)

        // 计算 HSV 饱和度作为彩色保护权重
        val hsv = newMat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvChannels = List(3) { newMat() }
        Core.split(hsv, hsvChannels)

        val w = bgr.cols()
        val h = bgr.rows()
        val total = w * h

        val satBytes = ByteArray(total)
        hsvChannels[1].get(0, 0, satBytes)
        val yCleanBytes = ByteArray(total)
        yClean.get(0, 0, yCleanBytes)
        val yNormBytes = ByteArray(total)
        yNorm.get(0, 0, yNormBytes)
        val crBytes = ByteArray(total)
        channels[1].get(0, 0, crBytes)
        val cbBytes = ByteArray(total)
        channels[2].get(0, 0, cbBytes)

        val outY = ByteArray(total)
        val outCr = ByteArray(total)
        val outCb = ByteArray(total)

        for (i in 0 until total) {
            val sat = satBytes[i].toInt() and 0xFF
            // 饱和度 > 36 逐步判定为印章/荧光笔/彩色图文，低饱和纸张去黄漂白
            val colorWeight = ((sat - 36f) / 48f).coerceIn(0f, 1f)

            val yc = yCleanBytes[i].toInt() and 0xFF
            val yn = yNormBytes[i].toInt() and 0xFF
            val yFinal = (yc * (1f - 0.18f * colorWeight) + yn * (0.18f * colorWeight)).roundToInt().coerceIn(0, 255)
            outY[i] = yFinal.toByte()

            val crOrig = crBytes[i].toInt() and 0xFF
            val cbOrig = cbBytes[i].toInt() and 0xFF
            val crVal = (128f + (crOrig - 128f) * colorWeight * 1.15f).roundToInt().coerceIn(0, 255)
            val cbVal = (128f + (cbOrig - 128f) * colorWeight * 1.15f).roundToInt().coerceIn(0, 255)
            outCr[i] = crVal.toByte()
            outCb[i] = cbVal.toByte()
        }

        val yOutMat = track(Mat(h, w, CvType.CV_8UC1)).apply { put(0, 0, outY) }
        val crOutMat = track(Mat(h, w, CvType.CV_8UC1)).apply { put(0, 0, outCr) }
        val cbOutMat = track(Mat(h, w, CvType.CV_8UC1)).apply { put(0, 0, outCb) }

        val merged = newMat()
        Core.merge(listOf(yOutMat, crOutMat, cbOutMat), merged)
        val bgrClean = newMat()
        Imgproc.cvtColor(merged, bgrClean, Imgproc.COLOR_YCrCb2BGR)

        // 轻度边缘锐化提升印刷感
        val blurred = newMat()
        Imgproc.GaussianBlur(bgrClean, blurred, Size(0.0, 0.0), 1.1)
        val sharp = newMat()
        Core.addWeighted(bgrClean, 1.24, blurred, -0.24, 0.0, sharp)
        return sharp
    }

    /**
     * 彩色文档：柔和去阴影并保留自然层次与彩色图文饱和度。
     */
    private fun MatScope.applyDocumentColor(bgr: Mat): Mat {
        val ycrcb = newMat()
        Imgproc.cvtColor(bgr, ycrcb, Imgproc.COLOR_BGR2YCrCb)
        val channels = List(3) { newMat() }
        Core.split(ycrcb, channels)

        val normalizedY = removeShadowFromLuminance(channels[0])
        val blendedY = newMat()
        Core.addWeighted(normalizedY, 0.80, channels[0], 0.20, 0.0, blendedY)
        val stretchedY = stretchDocumentContrast(blendedY, blackPercentile = 0.015, whitePercentile = 0.90)

        val crBoost = boostChroma(channels[1], 1.18)
        val cbBoost = boostChroma(channels[2], 1.18)

        val merged = newMat()
        Core.merge(listOf(stretchedY, crBoost, cbBoost), merged)
        val result = newMat()
        Imgproc.cvtColor(merged, result, Imgproc.COLOR_YCrCb2BGR)
        return result
    }

    /**
     * 灰度增强：去阴影 + 去透字 + S 曲线压黑。
     */
    private fun MatScope.applyGrayscaleEnhance(bgr: Mat): Mat {
        val gray = newMat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val normalized = removeShadowFromLuminance(gray)
        val clean = enhanceDocumentLuminance(normalized, whitePercentile = 0.84)
        val outBgr = newMat()
        Imgproc.cvtColor(clean, outBgr, Imgproc.COLOR_GRAY2BGR)
        return outBgr
    }

    /**
     * Sauvola 自适应二值化（黑白打印模式）：
     * T(x, y) = m(x, y) * (1 + k * (s(x, y) / 128 - 1))
     * 通过 boxFilter 计算局部均值 m 与局部标准差 s，空白纸张零黑斑、文字笔画极锐利。
     */
    private fun MatScope.applySauvolaBlackAndWhite(bgr: Mat): Mat {
        val gray = newMat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val normalized = removeShadowFromLuminance(gray)

        val w = normalized.cols()
        val h = normalized.rows()
        val winSize = makeOdd((min(w, h) / 36).coerceIn(19, 41)).toDouble()
        val kSize = Size(winSize, winSize)

        val grayFloat = newMat()
        normalized.convertTo(grayFloat, CvType.CV_32F)
        val sqFloat = newMat()
        Core.multiply(grayFloat, grayFloat, sqFloat)

        val mean = newMat()
        val sqMean = newMat()
        Imgproc.boxFilter(grayFloat, mean, CvType.CV_32F, kSize)
        Imgproc.boxFilter(sqFloat, sqMean, CvType.CV_32F, kSize)

        val total = w * h
        val grayArr = FloatArray(total)
        val meanArr = FloatArray(total)
        val sqMeanArr = FloatArray(total)
        grayFloat.get(0, 0, grayArr)
        mean.get(0, 0, meanArr)
        sqMean.get(0, 0, sqMeanArr)

        val outBytes = ByteArray(total)
        val sauvolaK = 0.20f
        val invR = 1f / 128f
        for (i in 0 until total) {
            val m = meanArr[i]
            val variance = max(0f, sqMeanArr[i] - m * m)
            val std = kotlin.math.sqrt(variance)
            val thresh = m * (1f + sauvolaK * (std * invR - 1f))
            outBytes[i] = if (grayArr[i] > thresh) 255.toByte() else 0
        }

        val binary = track(Mat(h, w, CvType.CV_8UC1)).apply { put(0, 0, outBytes) }
        val outBgr = newMat()
        Imgproc.cvtColor(binary, outBgr, Imgproc.COLOR_GRAY2BGR)
        return outBgr
    }

    /**
     * 基于局部背景差分与非线性 S 曲线优化亮度通道：压黑文字、漂白纸底、抑制背面透字。
     */
    private fun MatScope.enhanceDocumentLuminance(
        yNorm: Mat,
        whitePercentile: Double,
    ): Mat {
        val w = yNorm.cols()
        val h = yNorm.rows()
        val total = w * h

        val bgLocal = newMat()
        Imgproc.GaussianBlur(yNorm, bgLocal, Size(0.0, 0.0), 18.0)

        val (lowVal, highVal) = computePercentileBounds(yNorm, blackPercentile = 0.015, whitePercentile = whitePercentile)
        val span = max(40f, (highVal - lowVal).toFloat())

        val yBytes = ByteArray(total)
        val bgBytes = ByteArray(total)
        yNorm.get(0, 0, yBytes)
        bgLocal.get(0, 0, bgBytes)

        val outBytes = ByteArray(total)
        for (i in 0 until total) {
            val yv = (yBytes[i].toInt() and 0xFF).toFloat()
            val bgv = (bgBytes[i].toInt() and 0xFF).toFloat()
            val diff = bgv - yv

            val stretched = ((yv - lowVal) / span).coerceIn(0f, 1f)
            val curved = if (stretched < 0.72f) {
                (stretched / 0.72f).pow(1.38f) * 0.72f
            } else {
                1f - ((1f - stretched) / 0.28f).pow(1.8f) * 0.28f
            }

            // 局部差值极小且偏亮区域判定为纸张背景或背面透字，平滑推向纯白
            val bgSuppress = ((14f - diff) / 14f).coerceIn(0f, 1f) *
                ((stretched - 0.62f) / 0.38f).coerceIn(0f, 1f)
            val finalNorm = curved * (1f - 0.85f * bgSuppress) + 0.85f * bgSuppress
            outBytes[i] = (finalNorm * 255f).roundToInt().coerceIn(0, 255).toByte()
        }

        return track(Mat(h, w, CvType.CV_8UC1)).apply { put(0, 0, outBytes) }
    }

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

    private fun MatScope.computePercentileBounds(
        luma: Mat,
        blackPercentile: Double,
        whitePercentile: Double,
    ): Pair<Int, Int> {
        val hist = newMat()
        val channels = MatOfInt(0)
        val histSize = MatOfInt(256)
        val ranges = MatOfFloat(0f, 256f)
        return try {
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
            lowVal to highVal
        } finally {
            channels.release()
            histSize.release()
            ranges.release()
        }
    }

    private fun MatScope.stretchDocumentContrast(
        luma: Mat,
        blackPercentile: Double,
        whitePercentile: Double,
    ): Mat {
        val (lowVal, highVal) = computePercentileBounds(luma, blackPercentile, whitePercentile)
        if (highVal <= lowVal + 16) return luma
        val alpha = 255.0 / (highVal - lowVal).toDouble()
        val beta = -lowVal * alpha
        val out = newMat()
        luma.convertTo(out, CvType.CV_8U, alpha, beta)
        return out
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

        if (params.brightness != 0f || params.contrast != 1f) {
            val alpha = params.contrast.toDouble()
            val beta = params.brightness * 60.0 + (1.0 - alpha) * 128.0
            val toned = newMat()
            current.convertTo(toned, CvType.CV_8U, alpha, beta)
            current = toned
        }

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
