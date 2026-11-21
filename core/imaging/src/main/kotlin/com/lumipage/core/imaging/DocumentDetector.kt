package com.lumipage.core.imaging

import android.graphics.Bitmap
import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.NormalizedPoint
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 文档四顶点检测器：
 * - 在具备 Android Context 时，统一由 FairScan DeepLabV3+ (LiteRT) 语义分割 + 凸包 Huber 直线拟合执行，
 *   避免双引擎互相抢框导致的闪烁；
 * - 无 Context 时回退至纯 OpenCV 霍夫直线相交 + 多通路轮廓检测。
 */
class DocumentDetector(
    context: android.content.Context? = null,
) {
    private val segDetector: SegmentationDocumentDetector? = context?.let { SegmentationDocumentDetector(it) }

    data class DetectionResult(
        val quad: DocumentQuad?,
        val confidence: Float,
    )

    fun detect(
        bitmap: Bitmap,
        resetTemporal: Boolean = false,
    ): DetectionResult {
        if (bitmap.width < 16 || bitmap.height < 16) {
            return DetectionResult(quad = null, confidence = 0f)
        }

        if (segDetector != null) {
            val result = segDetector.detect(bitmap, resetTemporal = resetTemporal)
            android.util.Log.i("LumiScan", "Engine=FairScan_LiteRT conf=${result.confidence} quad=${result.quad != null}")
            return result
        }

        if (!OpenCvInitializer.ensureInitialized()) {
            return DetectionResult(quad = null, confidence = 0f)
        }

        return withMatScope {
            val rgba = newMat()
            Utils.bitmapToMat(bitmap, rgba)

            val bgr = newMat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

            // 降采样至分析分辨率（长边 <= 640px）以保证实时帧率与抗噪性
            val maxSide = max(bgr.cols(), bgr.rows())
            val scale = if (maxSide > ANALYSIS_MAX_SIDE) {
                ANALYSIS_MAX_SIDE.toDouble() / maxSide.toDouble()
            } else {
                1.0
            }
            val work = if (scale < 1.0) {
                newMat().also { dst ->
                    Imgproc.resize(
                        bgr,
                        dst,
                        Size(
                            max(1.0, (bgr.cols() * scale).roundToInt().toDouble()),
                            max(1.0, (bgr.rows() * scale).roundToInt().toDouble()),
                        ),
                        0.0,
                        0.0,
                        Imgproc.INTER_AREA,
                    )
                }
            } else {
                bgr
            }

            val width = work.cols()
            val height = work.rows()
            val imageArea = (width * height).toDouble()

            var bestCandidate: ScoredQuad? = detectByHoughLines(work, width, height, imageArea)

            val edgeMaps = buildMultiChannelEdgeMaps(work)
            for (edgeMap in edgeMaps) {
                val candidate = findBestQuadInEdgeMap(edgeMap, width, height, imageArea)
                if (candidate != null && (bestCandidate == null || candidate.score > bestCandidate.score)) {
                    bestCandidate = candidate
                }
            }

            if (bestCandidate == null || bestCandidate.score < MIN_CONFIDENCE_SCORE) {
                DetectionResult(quad = null, confidence = bestCandidate?.score ?: 0f)
            } else {
                DetectionResult(
                    quad = bestCandidate.quad,
                    confidence = bestCandidate.score.coerceIn(0f, 1f),
                )
            }
        }
    }

    private data class HoughSegment(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val length: Double,
        val midCoord: Double,
    )

    /**
     * 基于大核闭运算去文字 + 霍夫直线段聚类相交重建四顶点，
     * 专门解决翻开书本双页相连、手指压角、局部阴影断边等传统轮廓易失效的场景。
     */
    private fun MatScope.detectByHoughLines(
        bgr: Mat,
        width: Int,
        height: Int,
        imageArea: Double,
    ): ScoredQuad? {
        val gray = newMat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val textKernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0)))
        val noText = newMat()
        Imgproc.morphologyEx(gray, noText, Imgproc.MORPH_CLOSE, textKernel)
        Imgproc.GaussianBlur(noText, noText, Size(5.0, 5.0), 0.0)

        val edges = newMat()
        Imgproc.Canny(noText, edges, 20.0, 65.0)
        val dilKernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val dilated = newMat()
        Imgproc.dilate(edges, dilated, dilKernel)

        val rawLines = newMat()
        val minLineLen = min(width, height) * 0.16
        Imgproc.HoughLinesP(dilated, rawLines, 1.0, Math.PI / 180.0, 42, minLineLen, 28.0)
        if (rawLines.rows() < 4) return null

        val horizontals = mutableListOf<HoughSegment>()
        val verticals = mutableListOf<HoughSegment>()

        for (i in 0 until rawLines.rows()) {
            val v = rawLines.get(i, 0) ?: continue
            val x1 = v[0]
            val y1 = v[1]
            val x2 = v[2]
            val y2 = v[3]
            val dx = x2 - x1
            val dy = y2 - y1
            val len = hypot(dx, dy)
            val angle = abs(Math.toDegrees(kotlin.math.atan2(dy, dx)))
            if (angle <= 28.0 || angle >= 152.0) {
                horizontals.add(HoughSegment(x1, y1, x2, y2, len, (y1 + y2) * 0.5))
            } else if (angle in 62.0..118.0) {
                verticals.add(HoughSegment(x1, y1, x2, y2, len, (x1 + x2) * 0.5))
            }
        }

        if (horizontals.size < 2 || verticals.size < 2) return null

        fun pickSide(list: List<HoughSegment>, limit: Double, isUpperOrRight: Boolean): HoughSegment? {
            val sideCandidates = list.filter { seg ->
                if (isUpperOrRight) seg.midCoord >= limit else seg.midCoord <= limit
            }
            if (sideCandidates.isEmpty()) return null
            return sideCandidates.maxByOrNull { cand ->
                sideCandidates.sumOf { other ->
                    if (abs(other.midCoord - cand.midCoord) <= 18.0) other.length else 0.0
                }
            }
        }

        val topLine = pickSide(horizontals, height * 0.48, isUpperOrRight = false) ?: return null
        val botLine = pickSide(horizontals, height * 0.48, isUpperOrRight = true) ?: return null
        val leftLine = pickSide(verticals, width * 0.48, isUpperOrRight = false) ?: return null
        val rightLine = pickSide(verticals, width * 0.48, isUpperOrRight = true) ?: return null

        val tl = intersectLines(topLine, leftLine) ?: return null
        val tr = intersectLines(topLine, rightLine) ?: return null
        val br = intersectLines(botLine, rightLine) ?: return null
        val bl = intersectLines(botLine, leftLine) ?: return null

        val pts = arrayOf(tl, tr, br, bl)
        for (p in pts) {
            if (p.x < -0.25 * width || p.x > 1.25 * width || p.y < -0.25 * height || p.y > 1.25 * height) {
                return null
            }
            p.x = p.x.coerceIn(0.0, (width - 1).toDouble())
            p.y = p.y.coerceIn(0.0, (height - 1).toDouble())
        }

        return evaluateCandidate(pts, width, height, imageArea)?.let { scored ->
            // 给予直线重建法适度优先权，避免内部表格/文字轮廓抢占
            scored.copy(score = (scored.score * 1.06f).coerceAtMost(1f))
        }
    }

    private fun intersectLines(l1: HoughSegment, l2: HoughSegment): Point? {
        val denom = (l1.x1 - l1.x2) * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * (l2.x1 - l2.x2)
        if (abs(denom) < 1e-6) return null
        val c1 = l1.x1 * l1.y2 - l1.y1 * l1.x2
        val c2 = l2.x1 * l2.y2 - l2.y1 * l2.x2
        val px = (c1 * (l2.x1 - l2.x2) - (l1.x1 - l1.x2) * c2) / denom
        val py = (c1 * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * c2) / denom
        return Point(px, py)
    }

    private fun MatScope.buildMultiChannelEdgeMaps(bgr: Mat): List<Mat> {
        val maps = mutableListOf<Mat>()
        val morphKernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))
        val dilateKernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))

        // 通路 1：灰度 + 中值降噪 + 形态学闭运算抹除内部文字 + 自适应 Canny
        val gray = newMat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.medianBlur(gray, gray, 5)
        val graySmooth = newMat()
        Imgproc.morphologyEx(gray, graySmooth, Imgproc.MORPH_CLOSE, morphKernel)
        Imgproc.GaussianBlur(graySmooth, graySmooth, Size(5.0, 5.0), 0.0)
        maps.add(adaptiveCanny(graySmooth, dilateKernel))

        // 通路 2：LAB L 通道 + CLAHE 局部对比度增强 + 大核闭运算 + 低阈值敏感 Canny（攻克浅色桌面白纸弱边界）
        val lab = newMat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val labChannels = List(3) { newMat() }
        Core.split(lab, labChannels)
        val lChannel = labChannels[0]
        Imgproc.medianBlur(lChannel, lChannel, 5)
        val lEnhanced = newMat()
        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        clahe.apply(lChannel, lEnhanced)
        val morphKernelLarge = track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(13.0, 13.0)))
        val lSmooth = newMat()
        Imgproc.morphologyEx(lEnhanced, lSmooth, Imgproc.MORPH_CLOSE, morphKernelLarge)
        Imgproc.GaussianBlur(lSmooth, lSmooth, Size(5.0, 5.0), 0.0)
        val sensitiveEdges = newMat()
        Imgproc.Canny(lSmooth, sensitiveEdges, 18.0, 55.0)
        val sensitiveDilated = newMat()
        Imgproc.dilate(sensitiveEdges, sensitiveDilated, dilateKernel)
        maps.add(sensitiveDilated)

        // 通路 3：HSV S 通道（针对彩色/木纹桌面上的白色纸张）
        val hsv = newMat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvChannels = List(3) { newMat() }
        Core.split(hsv, hsvChannels)
        val sChannel = hsvChannels[1]
        Imgproc.medianBlur(sChannel, sChannel, 5)
        val sSmooth = newMat()
        Imgproc.morphologyEx(sChannel, sSmooth, Imgproc.MORPH_CLOSE, morphKernel)
        Imgproc.GaussianBlur(sSmooth, sSmooth, Size(5.0, 5.0), 0.0)
        maps.add(adaptiveCanny(sSmooth, dilateKernel))

        // 通路 4：Otsu 纸张亮区分割 + 闭运算填孔（应对真实相机阴影断边与纸张反光）
        val otsuMask = newMat()
        Imgproc.GaussianBlur(gray, gray, Size(7.0, 7.0), 0.0)
        Imgproc.threshold(gray, otsuMask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val openKernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        Imgproc.morphologyEx(otsuMask, otsuMask, Imgproc.MORPH_OPEN, openKernel)
        Imgproc.morphologyEx(otsuMask, otsuMask, Imgproc.MORPH_CLOSE, morphKernelLarge)
        maps.add(otsuMask)

        return maps
    }

    private fun MatScope.adaptiveCanny(src: Mat, dilateKernel: Mat): Mat {
        // 使用 Otsu 阈值自动估算高/低 Canny 阈值，适配暗光与高对比度场景
        val otsuDummy = newMat()
        val highThresh = Imgproc.threshold(src, otsuDummy, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val clampedHigh = highThresh.coerceIn(45.0, 220.0)
        val lowThresh = (clampedHigh * 0.45).coerceAtLeast(18.0)

        val edges = newMat()
        Imgproc.Canny(src, edges, lowThresh, clampedHigh)
        val dilated = newMat()
        Imgproc.dilate(edges, dilated, dilateKernel)
        return dilated
    }

    private fun MatScope.findBestQuadInEdgeMap(
        edgeMap: Mat,
        width: Int,
        height: Int,
        imageArea: Double,
    ): ScoredQuad? {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = newMat()
        Imgproc.findContours(
            edgeMap,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )

        var best: ScoredQuad? = null
        try {
            val sortedContours = contours
                .map { it to Imgproc.contourArea(it) }
                .filter { (_, area) -> area >= imageArea * MIN_AREA_RATIO && area <= imageArea * MAX_AREA_RATIO }
                .sortedByDescending { it.second }
                .take(MAX_CANDIDATE_CONTOURS)

            for ((contour, _) in sortedContours) {
                val hullIndices = MatOfInt()
                val contour2f = MatOfPoint2f()
                val approx = MatOfPoint2f()
                try {
                    Imgproc.convexHull(contour, hullIndices)
                    val contourPoints = contour.toArray()
                    val hullPoints = hullIndices.toArray().map { idx -> contourPoints[idx] }.toTypedArray()
                    contour2f.fromArray(*hullPoints)

                    val perimeter = Imgproc.arcLength(contour2f, true)
                    if (perimeter <= 0.0) continue

                    // 尝试多档 epsilon 逼近以稳定得到 4 顶点多边形
                    var matchedFour = false
                    for (epsFactor in EPSILON_FACTORS) {
                        Imgproc.approxPolyDP(contour2f, approx, epsFactor * perimeter, true)
                        val pts = approx.toArray()
                        if (pts.size == 4) {
                            val scored = evaluateCandidate(pts, width, height, imageArea)
                            if (scored != null && (best == null || scored.score > best.score)) {
                                best = scored
                            }
                            matchedFour = true
                            break
                        }
                    }

                    // 若因轻微折角/手指遮挡导致顶点为 5~6 个，当轮廓矩形度高时用最小外接矩形兜底
                    if (!matchedFour) {
                        val hullArea = Imgproc.contourArea(contour2f)
                        val minRect = Imgproc.minAreaRect(contour2f)
                        val rectArea = minRect.size.width * minRect.size.height
                        if (rectArea > 0.0 && hullArea / rectArea >= 0.84) {
                            val boxPts = Array(4) { Point() }
                            minRect.points(boxPts)
                            val scored = evaluateCandidate(boxPts, width, height, imageArea)
                            if (scored != null) {
                                val adjusted = scored.copy(score = scored.score * 0.90f)
                                if (best == null || adjusted.score > best.score) {
                                    best = adjusted
                                }
                            }
                        }
                    }
                } finally {
                    hullIndices.release()
                    contour2f.release()
                    approx.release()
                }
            }
        } finally {
            contours.forEach { it.release() }
        }
        return best
    }

    private fun evaluateCandidate(
        points: Array<Point>,
        width: Int,
        height: Int,
        imageArea: Double,
    ): ScoredQuad? {
        val normalized = points.map { pt ->
            NormalizedPoint(
                x = (pt.x / width.toDouble()).toFloat(),
                y = (pt.y / height.toDouble()).toFloat(),
            )
        }
        val quad = DocumentQuad.fromUnorderedPoints(normalized)
        if (!quad.isValidConvexQuad(minArea = MIN_AREA_RATIO.toFloat())) return null

        val pts = quad.asList()
        val pixelPts = pts.map { Point(it.x * width.toDouble(), it.y * height.toDouble()) }

        // 1. 面积得分：占画面 20% ~ 88% 为理想区间
        val areaRatio = quad.normalizedArea().toDouble()
        val areaScore = when {
            areaRatio < 0.08 -> 0.0
            areaRatio < 0.20 -> (areaRatio - 0.08) / 0.12 * 0.7
            areaRatio <= 0.88 -> 0.7 + (areaRatio - 0.20) / 0.68 * 0.3
            else -> 0.85
        }

        // 2. 内角直角性得分：每个内角应在 [55°, 125°] 内，越接近 90° 越好
        var minAngle = 180.0
        var maxCosAbs = 0.0
        for (i in 0 until 4) {
            val prev = pixelPts[(i + 3) % 4]
            val curr = pixelPts[i]
            val next = pixelPts[(i + 1) % 4]
            val cosVal = abs(angleCosine(prev, curr, next))
            maxCosAbs = max(maxCosAbs, cosVal)
            val deg = Math.toDegrees(acos(cosVal.coerceIn(0.0, 1.0)))
            minAngle = min(minAngle, deg)
        }
        if (maxCosAbs > MAX_CORNER_COSINE) return null // 拒绝极端尖锐畸变
        val angleScore = (1.0 - maxCosAbs / MAX_CORNER_COSINE).coerceIn(0.0, 1.0)

        // 3. 纵横比合理性：拒绝过细长条形误检
        val topW = hypot(pixelPts[1].x - pixelPts[0].x, pixelPts[1].y - pixelPts[0].y)
        val botW = hypot(pixelPts[2].x - pixelPts[3].x, pixelPts[2].y - pixelPts[3].y)
        val leftH = hypot(pixelPts[3].x - pixelPts[0].x, pixelPts[3].y - pixelPts[0].y)
        val rightH = hypot(pixelPts[2].x - pixelPts[1].x, pixelPts[2].y - pixelPts[1].y)
        val avgW = (topW + botW) * 0.5
        val avgH = (leftH + rightH) * 0.5
        if (avgW < 20.0 || avgH < 20.0) return null
        val aspect = max(avgW, avgH) / min(avgW, avgH)
        if (aspect > 4.0) return null

        // 4. 边长对称性得分
        val wSymmetry = min(topW, botW) / max(topW, botW).coerceAtLeast(1.0)
        val hSymmetry = min(leftH, rightH) / max(leftH, rightH).coerceAtLeast(1.0)
        val symmetryScore = (wSymmetry + hSymmetry) * 0.5

        // 5. 惩罚四顶点几乎全部贴死图像边缘的退化框
        val borderTouchCount = pts.count { p ->
            p.x <= 0.015f || p.x >= 0.985f || p.y <= 0.015f || p.y >= 0.985f
        }
        val borderPenalty = if (borderTouchCount >= 3) 0.72 else 1.0

        val totalScore = ((areaScore * 0.42 + angleScore * 0.36 + symmetryScore * 0.22) * borderPenalty).toFloat()
        return ScoredQuad(quad = quad, score = totalScore)
    }

    private fun angleCosine(p1: Point, vertex: Point, p2: Point): Double {
        val dx1 = p1.x - vertex.x
        val dy1 = p1.y - vertex.y
        val dx2 = p2.x - vertex.x
        val dy2 = p2.y - vertex.y
        val denom = hypot(dx1, dy1) * hypot(dx2, dy2)
        if (denom < 1e-6) return 1.0
        return (dx1 * dx2 + dy1 * dy2) / denom
    }

    private data class ScoredQuad(
        val quad: DocumentQuad,
        val score: Float,
    )

    private companion object {
        const val ANALYSIS_MAX_SIDE = 480
        const val MIN_AREA_RATIO = 0.08
        const val MAX_AREA_RATIO = 0.97
        const val MAX_CANDIDATE_CONTOURS = 8
        const val MIN_CONFIDENCE_SCORE = 0.42f
        const val MAX_CORNER_COSINE = 0.62 // 约对应最小内角 >= 51°
        val EPSILON_FACTORS = doubleArrayOf(0.018, 0.028, 0.04, 0.06, 0.08)
    }
}
