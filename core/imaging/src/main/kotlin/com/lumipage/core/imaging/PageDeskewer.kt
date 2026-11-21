package com.lumipage.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纸张精细水平摆正（±6° 投影方差最大化）与外缘非纸张暗边清理。
 *
 * 设计原则：
 * 1. 确保一整张纸的完整性，绝不裁剪正文或正常页边距；
 * 2. 仅清理贴合图像外缘 ~4.5% 环带内、与外边缘连通的非纸张暗边/桌面/书厚阴影；
 * 3. 在摆正前清理外缘并采样干净纸面底色填充旋转四角，杜绝黑色三角边。
 */
internal object PageDeskewer {

    fun cleanAndDeskew(scope: MatScope, srcMat: Mat): Mat = with(scope) {
        if (srcMat.cols() < 64 || srcMat.rows() < 64) return srcMat
        val fillColor = estimatePaperBorderColor(srcMat)
        val borderCleaned = cleanOuterNonPaperBorders(this, srcMat, fillColor)
        val skewAngle = estimateFineSkewDegrees(this, borderCleaned)
        return applyFineDeskew(this, borderCleaned, skewAngle, fillColor)
    }

    private fun estimatePaperBorderColor(srcRgba: Mat): Scalar {
        val w = srcRgba.cols()
        val h = srcRgba.rows()
        val sampleStep = max(1, min(w, h) / 160)
        val b1 = max(2, (min(w, h) * 0.04).roundToInt())
        val b2 = max(4, (min(w, h) * 0.12).roundToInt())

        val bytesPerPixel = srcRgba.channels()
        val buf = ByteArray(w * h * bytesPerPixel)
        srcRgba.get(0, 0, buf)

        val lumaList = ArrayList<Int>(512)
        val rgbSamples = ArrayList<IntArray>(512)

        var y = b1
        while (y < h - b1) {
            var x = b1
            while (x < w - b1) {
                val inRing = y < b2 || y >= h - b2 || x < b2 || x >= w - b2
                if (inRing) {
                    val idx = (y * w + x) * bytesPerPixel
                    val r = buf[idx].toInt() and 0xFF
                    val g = buf[idx + 1].toInt() and 0xFF
                    val b = buf[idx + 2].toInt() and 0xFF
                    val luma = (299 * r + 587 * g + 114 * b) / 1000
                    lumaList.add(luma)
                    rgbSamples.add(intArrayOf(r, g, b, luma))
                }
                x += sampleStep
            }
            y += sampleStep
        }

        if (lumaList.isEmpty()) {
            return Scalar(248.0, 248.0, 248.0, 255.0)
        }
        lumaList.sort()
        val p75 = lumaList[((lumaList.size - 1) * 0.75).roundToInt().coerceIn(0, lumaList.lastIndex)]

        val rVals = ArrayList<Int>(rgbSamples.size)
        val gVals = ArrayList<Int>(rgbSamples.size)
        val bVals = ArrayList<Int>(rgbSamples.size)
        for (s in rgbSamples) {
            if (s[3] >= p75) {
                rVals.add(s[0])
                gVals.add(s[1])
                bVals.add(s[2])
            }
        }
        if (rVals.isEmpty()) {
            return Scalar(248.0, 248.0, 248.0, 255.0)
        }
        rVals.sort()
        gVals.sort()
        bVals.sort()
        val mid = rVals.size / 2
        return Scalar(
            rVals[mid].toDouble(),
            gVals[mid].toDouble(),
            bVals[mid].toDouble(),
            255.0,
        )
    }

    private fun cleanOuterNonPaperBorders(
        scope: MatScope,
        srcRgba: Mat,
        fillColor: Scalar,
        borderFraction: Double = 0.028,
    ): Mat = with(scope) {
        val origW = srcRgba.cols()
        val origH = srcRgba.rows()
        val maxDim = max(origW, origH)
        val workScale = min(1.0, 1200.0 / maxDim.toDouble())
        val workW = max(32, (origW * workScale).roundToInt())
        val workH = max(32, (origH * workScale).roundToInt())

        val workRgba = if (workScale < 1.0) {
            val resized = newMat()
            Imgproc.resize(srcRgba, resized, Size(workW.toDouble(), workH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            resized
        } else {
            srcRgba
        }

        val gray = newMat()
        Imgproc.cvtColor(workRgba, gray, Imgproc.COLOR_RGBA2GRAY)

        // 估算内部纸面 70 分位亮度作为参考
        val innerX = (workW * 0.15).roundToInt()
        val innerY = (workH * 0.15).roundToInt()
        val innerW = max(8, (workW * 0.70).roundToInt())
        val innerH = max(8, (workH * 0.70).roundToInt())
        val innerBuf = ByteArray(innerW * innerH)
        val innerMat = track(gray.submat(Rect(innerX, innerY, innerW, innerH)))
        innerMat.get(0, 0, innerBuf)
        val innerSorted = IntArray(innerBuf.size) { innerBuf[it].toInt() and 0xFF }.apply { sort() }
        val paperRef = innerSorted[((innerSorted.size - 1) * 0.70).roundToInt().coerceIn(0, innerSorted.lastIndex)].toDouble()
        val darkThresh = (paperRef * 0.50).coerceIn(35.0, 115.0)

        val grayData = ByteArray(workW * workH)
        gray.get(0, 0, grayData)
        val bw = max(8, (min(workW, workH) * borderFraction).roundToInt())

        val darkInBandData = ByteArray(workW * workH)
        val bandMaskData = ByteArray(workW * workH)
        var anyDark = false
        val threshInt = darkThresh.roundToInt()

        for (y in 0 until workH) {
            val rowOff = y * workW
            val yInBand = y < bw || y >= workH - bw
            for (x in 0 until workW) {
                val inBand = yInBand || x < bw || x >= workW - bw
                if (inBand) {
                    val idx = rowOff + x
                    bandMaskData[idx] = 1
                    val v = grayData[idx].toInt() and 0xFF
                    if (v < threshInt) {
                        darkInBandData[idx] = 1
                        anyDark = true
                    }
                }
            }
        }
        if (!anyDark) return srcRgba

        val darkInBand = track(Mat(workH, workW, CvType.CV_8UC1))
        darkInBand.put(0, 0, darkInBandData)

        val labels = newMat()
        val stats = newMat()
        val centroids = newMat()
        val numLabels = Imgproc.connectedComponentsWithStats(darkInBand, labels, stats, centroids, 8)
        if (numLabels <= 1) return srcRgba

        val keepLabel = BooleanArray(numLabels)
        val edgeMargin = 3
        val maxArtifactArea = (workW * workH * 0.18).roundToInt()
        var foundArtifact = false

        val statRow = IntArray(5)
        for (lbl in 1 until numLabels) {
            stats.get(lbl, 0, statRow)
            val x = statRow[Imgproc.CC_STAT_LEFT]
            val y = statRow[Imgproc.CC_STAT_TOP]
            val cw = statRow[Imgproc.CC_STAT_WIDTH]
            val ch = statRow[Imgproc.CC_STAT_HEIGHT]
            val area = statRow[Imgproc.CC_STAT_AREA]
            if (area < 15 || area > maxArtifactArea) continue
            val touchesEdge = x <= edgeMargin ||
                y <= edgeMargin ||
                (x + cw) >= (workW - edgeMargin) ||
                (y + ch) >= (workH - edgeMargin)
            if (touchesEdge) {
                keepLabel[lbl] = true
                foundArtifact = true
            }
        }
        if (!foundArtifact) return srcRgba

        val labelData = IntArray(workW * workH)
        labels.get(0, 0, labelData)
        val cleanMaskData = ByteArray(workW * workH)
        for (i in labelData.indices) {
            val lbl = labelData[i]
            if (lbl > 0 && keepLabel[lbl]) {
                cleanMaskData[i] = 255.toByte()
            }
        }

        val cleanMask = track(Mat(workH, workW, CvType.CV_8UC1))
        cleanMask.put(0, 0, cleanMaskData)
        val bandMask = track(Mat(workH, workW, CvType.CV_8UC1))
        bandMask.put(0, 0, bandMaskData)

        val kernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
        Imgproc.dilate(cleanMask, cleanMask, kernel)
        Imgproc.dilate(bandMask, bandMask, kernel)
        Core.multiply(bandMask, Scalar(255.0), bandMask)
        Core.bitwise_and(cleanMask, bandMask, cleanMask)

        val alphaSmall = newMat()
        cleanMask.convertTo(alphaSmall, CvType.CV_32F, 1.0 / 255.0)
        Imgproc.GaussianBlur(alphaSmall, alphaSmall, Size(5.0, 5.0), 0.0)

        val alphaFull = if (workW != origW || workH != origH) {
            val up = newMat()
            Imgproc.resize(alphaSmall, up, Size(origW.toDouble(), origH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            up
        } else {
            alphaSmall
        }

        val alphaData = FloatArray(origW * origH)
        alphaFull.get(0, 0, alphaData)

        val outBytes = ByteArray(origW * origH * 4)
        srcRgba.get(0, 0, outBytes)
        val fillR = fillColor.`val`[0].toFloat()
        val fillG = fillColor.`val`[1].toFloat()
        val fillB = fillColor.`val`[2].toFloat()

        for (i in alphaData.indices) {
            val a = alphaData[i].coerceIn(0f, 1f)
            if (a > 0.002f) {
                val idx = i * 4
                val inv = 1f - a
                val r = (outBytes[idx].toInt() and 0xFF) * inv + fillR * a
                val g = (outBytes[idx + 1].toInt() and 0xFF) * inv + fillG * a
                val b = (outBytes[idx + 2].toInt() and 0xFF) * inv + fillB * a
                outBytes[idx] = r.roundToInt().coerceIn(0, 255).toByte()
                outBytes[idx + 1] = g.roundToInt().coerceIn(0, 255).toByte()
                outBytes[idx + 2] = b.roundToInt().coerceIn(0, 255).toByte()
            }
        }

        val outMat = track(Mat(origH, origW, CvType.CV_8UC4))
        outMat.put(0, 0, outBytes)
        return outMat
    }

    private fun estimateFineSkewDegrees(
        scope: MatScope,
        srcRgba: Mat,
        maxAngle: Double = 6.0,
    ): Double = with(scope) {
        val origW = srcRgba.cols()
        val origH = srcRgba.rows()
        val gray = newMat()
        Imgproc.cvtColor(srcRgba, gray, Imgproc.COLOR_RGBA2GRAY)

        val scale = min(1.0, 720.0 / max(origW, origH).toDouble())
        val workGray = if (scale < 1.0) {
            val resized = newMat()
            Imgproc.resize(
                gray,
                resized,
                Size((origW * scale).roundToInt().toDouble(), (origH * scale).roundToInt().toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_AREA,
            )
            resized
        } else {
            gray
        }

        val gh = workGray.rows()
        val gw = workGray.cols()
        val my = (gh * 0.06).roundToInt()
        val mx = (gw * 0.06).roundToInt()
        val rw = gw - 2 * mx
        val rh = gh - 2 * my
        if (rw < 40 || rh < 40) return 0.0

        val roi = track(workGray.submat(Rect(mx, my, rw, rh)))
        val blur = newMat()
        val sigma = max(3.0, rw * 0.02)
        Imgproc.GaussianBlur(roi, blur, Size(0.0, 0.0), sigma)

        val diff = newMat()
        Core.subtract(blur, roi, diff)
        val mask = newMat()
        Imgproc.threshold(diff, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        val textBin = newMat()
        mask.convertTo(textBin, CvType.CV_32F, 1.0 / 255.0)

        val meanDensity = Core.mean(textBin).`val`[0]
        if (meanDensity < 0.005 || meanDensity > 0.55) return 0.0

        val center = Point(rw * 0.5, rh * 0.5)
        val rot = newMat()
        val rowSums = newMat()
        val rowSumArray = FloatArray(rh)

        fun scoreAngle(deg: Double): Double {
            val m = track(Imgproc.getRotationMatrix2D(center, deg, 1.0))
            Imgproc.warpAffine(
                textBin,
                rot,
                m,
                Size(rw.toDouble(), rh.toDouble()),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0),
            )
            Core.reduce(rot, rowSums, 1, Core.REDUCE_SUM, CvType.CV_32F)
            rowSums.get(0, 0, rowSumArray)
            return computeProjectionVariance(rowSumArray)
        }

        val zeroScore = scoreAngle(0.0)
        var bestDeg = 0.0
        var bestScore = zeroScore

        // 粗搜索：[-maxAngle, +maxAngle]，步长 0.5°
        var a = -maxAngle
        while (a <= maxAngle + 1e-6) {
            val rounded = (a * 10.0).roundToInt() / 10.0
            if (abs(rounded) > 1e-4) {
                val s = scoreAngle(rounded)
                if (s > bestScore) {
                    bestScore = s
                    bestDeg = rounded
                }
            }
            a += 0.5
        }

        // 细搜索：bestDeg ± 0.6°，步长 0.1°
        var f = bestDeg - 0.6
        while (f <= bestDeg + 0.6 + 1e-6) {
            val rounded = (f * 10.0).roundToInt() / 10.0
            if (abs(rounded) <= maxAngle) {
                val s = scoreAngle(rounded)
                if (s > bestScore) {
                    bestScore = s
                    bestDeg = rounded
                }
            }
            f += 0.1
        }

        if (abs(bestDeg) < 0.3 || bestScore <= zeroScore * 1.03) {
            return 0.0
        }
        return bestDeg
    }

    private fun applyFineDeskew(
        scope: MatScope,
        srcRgba: Mat,
        angleDeg: Double,
        fillColor: Scalar,
    ): Mat = with(scope) {
        if (abs(angleDeg) < 0.3) return srcRgba
        val w = srcRgba.cols().toDouble()
        val h = srcRgba.rows().toDouble()
        val m = track(Imgproc.getRotationMatrix2D(Point(w * 0.5, h * 0.5), angleDeg, 1.0))
        val dst = newMat()
        Imgproc.warpAffine(
            srcRgba,
            dst,
            m,
            Size(w, h),
            Imgproc.INTER_CUBIC,
            Core.BORDER_CONSTANT,
            fillColor,
        )
        return dst
    }

    internal fun computeProjectionVariance(values: FloatArray): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        var sq = 0.0
        for (v in values) {
            val d = v - mean
            sq += d * d
        }
        return sq / values.size
    }
}
