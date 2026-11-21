package com.lumipage.core.imaging

import android.content.Context
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
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 基于 FairScan DeepLabV3+ (MobileNetV2) LiteRT 语义分割模型 + 凸包 Huber 直线拟合的文档四顶点检测器。
 * 输入: [1, 256, 256, 3] NHWC RGB float32 (ImageNet mean/std 归一化)
 * 输出: [1, 256, 256, 1] 文档前景分割 logits
 */
class SegmentationDocumentDetector(
    context: Context,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var interpreter: Interpreter? = null

    @Volatile
    private var loadFailed: Boolean = false

    private val inputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        .order(ByteOrder.nativeOrder())

    private val outputBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(4 * INPUT_SIZE * INPUT_SIZE)
        .order(ByteOrder.nativeOrder())

    private val pixelScratch = IntArray(INPUT_SIZE * INPUT_SIZE)
    private var prevProbMap: FloatArray? = null

    fun resetTemporalState() {
        prevProbMap = null
    }

    private fun getOrLoadInterpreter(): Interpreter? {
        if (loadFailed) return null
        interpreter?.let { return it }
        synchronized(this) {
            if (loadFailed) return null
            interpreter?.let { return it }
            return try {
                val modelBuffer = loadModelFile(MODEL_ASSET_PATH)
                val options = Interpreter.Options().apply {
                    numThreads = 4
                    useXNNPACK = true
                }
                Interpreter(modelBuffer, options).also {
                    interpreter = it
                    android.util.Log.i("LumiScan", "FairScan LiteRT segmentation model loaded")
                }
            } catch (t: Throwable) {
                android.util.Log.e("LumiScan", "Failed to load FairScan LiteRT model", t)
                loadFailed = true
                null
            }
        }
    }

    private fun loadModelFile(assetPath: String): MappedByteBuffer {
        val afd = appContext.assets.openFd(assetPath)
        FileInputStream(afd.fileDescriptor).use { input ->
            return input.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength,
            )
        }
    }

    @Synchronized
    fun detect(
        bitmap: Bitmap,
        resetTemporal: Boolean = false,
    ): DocumentDetector.DetectionResult {
        val tflite = getOrLoadInterpreter() ?: return DocumentDetector.DetectionResult(null, 0f)
        if (!OpenCvInitializer.ensureInitialized()) {
            return DocumentDetector.DetectionResult(null, 0f)
        }
        if (resetTemporal) {
            prevProbMap = null
        }

        val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }

        try {
            scaled.getPixels(pixelScratch, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
        }

        // 填充 NHWC float32 ImageNet 归一化输入张量
        inputBuffer.rewind()
        for (i in 0 until INPUT_SIZE * INPUT_SIZE) {
            val argb = pixelScratch[i]
            val r = ((argb ushr 16) and 0xFF) * INV_255
            val g = ((argb ushr 8) and 0xFF) * INV_255
            val b = (argb and 0xFF) * INV_255
            inputBuffer.putFloat((r - MEAN_R) / STD_R)
            inputBuffer.putFloat((g - MEAN_G) / STD_G)
            inputBuffer.putFloat((b - MEAN_B) / STD_B)
        }
        inputBuffer.rewind()
        outputBuffer.rewind()

        tflite.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val currentProb = FloatArray(INPUT_SIZE * INPUT_SIZE)
        val prev = prevProbMap
        val maskBytes = ByteArray(INPUT_SIZE * INPUT_SIZE)
        var fgCount = 0
        for (i in maskBytes.indices) {
            val logit = outputBuffer.float
            val rawProb = 1f / (1f + exp(-logit.coerceIn(-20f, 20f)))
            // 多帧概率掩膜时序融合，消除单帧边缘锯齿
            val fusedProb = if (prev != null) {
                0.55f * prev[i] + 0.45f * rawProb
            } else {
                rawProb
            }
            currentProb[i] = fusedProb
            if (fusedProb > MASK_THRESHOLD) {
                maskBytes[i] = 255.toByte()
                fgCount++
            }
        }
        prevProbMap = currentProb

        val fgRatio = fgCount.toFloat() / (INPUT_SIZE * INPUT_SIZE).toFloat()
        if (fgRatio < MIN_MASK_RATIO || fgRatio > MAX_MASK_RATIO) {
            return DocumentDetector.DetectionResult(null, 0f)
        }

        val coarseQuad = fitQuadFromMaskBytes(maskBytes) ?: return DocumentDetector.DetectionResult(null, 0f)
        if (!coarseQuad.isValidConvexQuad(minArea = 0.06f)) {
            return DocumentDetector.DetectionResult(null, 0f)
        }

        val snappedQuad = snapEdgesHighRes(bitmap, coarseQuad)
        val finalQuad = if (snappedQuad.isValidConvexQuad(minArea = 0.06f)) snappedQuad else coarseQuad

        return DocumentDetector.DetectionResult(
            quad = finalQuad,
            confidence = (0.65f + fgRatio * 0.35f).coerceIn(0f, 1f),
        )
    }

    /**
     * 在较高分辨率原图上沿粗略四边法向 ±2.6% 窄带扫描定向梯度峰，将框像素级吸附到真实物理边界。
     */
    private fun snapEdgesHighRes(bitmap: Bitmap, coarseQuad: DocumentQuad): DocumentQuad {
        if (bitmap.width < 200 || bitmap.height < 200) return coarseQuad
        return withMatScope {
            val rgba = newMat()
            Utils.bitmapToMat(bitmap, rgba)
            val gray = newMat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)

            val gx = newMat()
            val gy = newMat()
            Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)

            val w = gray.cols()
            val h = gray.rows()
            val gxData = FloatArray(w * h)
            val gyData = FloatArray(w * h)
            gx.get(0, 0, gxData)
            gy.get(0, 0, gyData)

            val pts = coarseQuad.asList().map {
                Point(it.x * w.toDouble(), it.y * h.toDouble())
            }
            val searchRadius = max(8, (min(w, h) * 0.026).roundToInt())
            val snappedLines = ArrayList<Line2D>(4)

            for (i in 0 until 4) {
                val p1 = pts[i]
                val p2 = pts[(i + 1) % 4]
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val edgeLen = hypot(dx, dy)
                if (edgeLen < 16.0) {
                    snappedLines.add(Line2D(p1.x, p1.y, p2.x, p2.y))
                    continue
                }
                val ux = dx / edgeLen
                val uy = dy / edgeLen
                val nx = -uy
                val ny = ux

                val edgePeaks = ArrayList<Point>(18)
                val sampleCount = 18
                for (s in 0 until sampleCount) {
                    val t = 0.15 + (0.70 * s / (sampleCount - 1).toDouble())
                    val bx = p1.x + dx * t
                    val by = p1.y + dy * t

                    var bestScore = 0.0
                    var bestX = 0.0
                    var bestY = 0.0
                    var found = false
                    for (d in -searchRadius..searchRadius) {
                        val sx = (bx + nx * d).roundToInt()
                        val sy = (by + ny * d).roundToInt()
                        if (sx in 2 until (w - 2) && sy in 2 until (h - 2)) {
                            val idx = sy * w + sx
                            val rawSigned = gxData[idx] * nx + gyData[idx] * ny
                            val dirGrad = abs(rawSigned)
                            val distWeight = 1.0 - 0.35 * (abs(d).toDouble() / searchRadius.toDouble())
                            // (nx, ny) 指向纸张内部：优先吸附内侧亮纸面边缘，避免将桌面暗边或书页厚度阴影拉入裁剪框
                            val inwardWeight = if (d < 0) 0.78 else 1.0
                            val polarityWeight = if (rawSigned >= 0f) 1.15 else 0.85
                            val score = dirGrad * distWeight * inwardWeight * polarityWeight
                            if (score > bestScore) {
                                bestScore = score
                                bestX = sx.toDouble()
                                bestY = sy.toDouble()
                                found = true
                            }
                        }
                    }
                    if (found && bestScore >= 24.0) {
                        edgePeaks.add(Point(bestX, bestY))
                    }
                }

                if (edgePeaks.size >= 8) {
                    val ptsMat = track(MatOfPoint2f(*edgePeaks.toTypedArray()))
                    val lineMat = newMat()
                    Imgproc.fitLine(ptsMat, lineMat, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
                    val vx = lineMat.get(0, 0)[0]
                    val vy = lineMat.get(1, 0)[0]
                    val x0 = lineMat.get(2, 0)[0]
                    val y0 = lineMat.get(3, 0)[0]
                    snappedLines.add(Line2D(x0, y0, x0 + vx * 100.0, y0 + vy * 100.0))
                } else {
                    snappedLines.add(Line2D(p1.x, p1.y, p2.x, p2.y))
                }
            }

            val tl = intersectInSize(snappedLines[3], snappedLines[0], w, h) ?: return@withMatScope coarseQuad
            val tr = intersectInSize(snappedLines[0], snappedLines[1], w, h) ?: return@withMatScope coarseQuad
            val br = intersectInSize(snappedLines[1], snappedLines[2], w, h) ?: return@withMatScope coarseQuad
            val bl = intersectInSize(snappedLines[2], snappedLines[3], w, h) ?: return@withMatScope coarseQuad

            val normalized = listOf(tl, tr, br, bl).map { pt ->
                NormalizedPoint(
                    x = (pt.x / w.toDouble()).toFloat().coerceIn(0f, 1f),
                    y = (pt.y / h.toDouble()).toFloat().coerceIn(0f, 1f),
                )
            }
            DocumentQuad.fromUnorderedPoints(normalized)
        }
    }

    private fun intersectInSize(l1: Line2D, l2: Line2D, width: Int, height: Int): Point? {
        val denom = (l1.x1 - l1.x2) * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * (l2.x1 - l2.x2)
        if (abs(denom) < 1e-6) return null
        val c1 = l1.x1 * l1.y2 - l1.y1 * l1.x2
        val c2 = l2.x1 * l2.y2 - l2.y1 * l2.x2
        val px = (c1 * (l2.x1 - l2.x2) - (l1.x1 - l1.x2) * c2) / denom
        val py = (c1 * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * c2) / denom
        if (px < -width * 0.1 || px > width * 1.1 || py < -height * 0.1 || py > height * 1.1) return null
        return Point(px.coerceIn(0.0, (width - 1).toDouble()), py.coerceIn(0.0, (height - 1).toDouble()))
    }

    private fun fitQuadFromMaskBytes(maskBytes: ByteArray): DocumentQuad? {
        return withMatScope {
            val rawMask = track(Mat(INPUT_SIZE, INPUT_SIZE, CvType.CV_8UC1))
            rawMask.put(0, 0, maskBytes)

            val closeKernel = track(Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
            val mask = newMat()
            Imgproc.morphologyEx(rawMask, mask, Imgproc.MORPH_CLOSE, closeKernel)

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = newMat()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
            if (contours.isEmpty()) return@withMatScope null

            try {
                val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return@withMatScope null
                val area = Imgproc.contourArea(maxContour)
                if (area < INPUT_SIZE * INPUT_SIZE * MIN_MASK_RATIO) return@withMatScope null

                // 1. 计算凸包与最小外接旋转矩形作为初始 4 边基准
                val hullIndices = track(MatOfInt())
                Imgproc.convexHull(maxContour, hullIndices)
                val contourPts = maxContour.toArray()
                val hullPts = hullIndices.toArray().map { contourPts[it] }.toTypedArray()

                val hullMat = track(MatOfPoint(*hullPts))
                val hull2f = track(MatOfPoint2f(*hullPts))
                val minRect = Imgproc.minAreaRect(hull2f)
                val boxArr = Array(4) { Point() }
                minRect.points(boxArr)

                val boxQuad = DocumentQuad.fromUnorderedPoints(
                    boxArr.map {
                        NormalizedPoint(
                            (it.x / INPUT_SIZE.toDouble()).toFloat(),
                            (it.y / INPUT_SIZE.toDouble()).toFloat(),
                        )
                    },
                )
                val refPts = boxQuad.asList().map {
                    Point(it.x * INPUT_SIZE.toDouble(), it.y * INPUT_SIZE.toDouble())
                }

                // 2. 构建凸包边缘窄带掩膜，滤除手指按压造成的内凹轮廓点以及图像边界截断点
                val hullBand = track(Mat.zeros(INPUT_SIZE, INPUT_SIZE, CvType.CV_8UC1))
                Imgproc.polylines(hullBand, listOf(hullMat), true, Scalar(255.0), 5)

                val validPts = ArrayList<Point>(contourPts.size)
                for (pt in contourPts) {
                    val px = pt.x.roundToInt()
                    val py = pt.y.roundToInt()
                    if (px in 2..(INPUT_SIZE - 3) && py in 2..(INPUT_SIZE - 3)) {
                        val v = hullBand.get(py, px)
                        if (v != null && v[0] > 0.0) {
                            validPts.add(pt)
                        }
                    }
                }

                if (validPts.size < 16) {
                    return@withMatScope boxQuad
                }

                // 3. 逐边收集有效点集，使用 DIST_HUBER 鲁棒拟合 4 条直线
                val fittedLines = ArrayList<Line2D>(4)
                for (i in 0 until 4) {
                    val p1 = refPts[i]
                    val p2 = refPts[(i + 1) % 4]
                    val dx = p2.x - p1.x
                    val dy = p2.y - p1.y
                    val edgeLen = hypot(dx, dy)
                    if (edgeLen < 4.0) return@withMatScope boxQuad

                    val ux = dx / edgeLen
                    val uy = dy / edgeLen
                    val nx = -uy
                    val ny = ux
                    val bandWidth = max(12.0, min(edgeLen * 0.25, 36.0))

                    val sidePts = ArrayList<Point>()
                    for (pt in validPts) {
                        val rx = pt.x - p1.x
                        val ry = pt.y - p1.y
                        val proj = (rx * ux + ry * uy) / edgeLen
                        val perp = abs(rx * nx + ry * ny)
                        if (proj in 0.12..0.88 && perp <= bandWidth) {
                            sidePts.add(pt)
                        }
                    }

                    if (sidePts.size >= 6) {
                        val ptsMat = track(MatOfPoint2f(*sidePts.toTypedArray()))
                        val lineMat = newMat()
                        Imgproc.fitLine(ptsMat, lineMat, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
                        val vx = lineMat.get(0, 0)[0]
                        val vy = lineMat.get(1, 0)[0]
                        val x0 = lineMat.get(2, 0)[0]
                        val y0 = lineMat.get(3, 0)[0]
                        fittedLines.add(Line2D(x0, y0, x0 + vx * 100.0, y0 + vy * 100.0))
                    } else {
                        fittedLines.add(Line2D(p1.x, p1.y, p2.x, p2.y))
                    }
                }

                // 4. 四条拟合直线两两求交点 (edge 0=top, 1=right, 2=bottom, 3=left)
                val tl = intersect(fittedLines[3], fittedLines[0]) ?: return@withMatScope boxQuad
                val tr = intersect(fittedLines[0], fittedLines[1]) ?: return@withMatScope boxQuad
                val br = intersect(fittedLines[1], fittedLines[2]) ?: return@withMatScope boxQuad
                val bl = intersect(fittedLines[2], fittedLines[3]) ?: return@withMatScope boxQuad

                val normalized = listOf(tl, tr, br, bl).map { pt ->
                    NormalizedPoint(
                        x = (pt.x / INPUT_SIZE.toDouble()).toFloat().coerceIn(0f, 1f),
                        y = (pt.y / INPUT_SIZE.toDouble()).toFloat().coerceIn(0f, 1f),
                    )
                }
                DocumentQuad.fromUnorderedPoints(normalized)
            } finally {
                contours.forEach { it.release() }
            }
        }
    }

    private data class Line2D(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

    private fun intersect(l1: Line2D, l2: Line2D): Point? {
        val denom = (l1.x1 - l1.x2) * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * (l2.x1 - l2.x2)
        if (abs(denom) < 1e-6) return null
        val c1 = l1.x1 * l1.y2 - l1.y1 * l1.x2
        val c2 = l2.x1 * l2.y2 - l2.y1 * l2.x2
        val px = (c1 * (l2.x1 - l2.x2) - (l1.x1 - l1.x2) * c2) / denom
        val py = (c1 * (l2.y1 - l2.y2) - (l1.y1 - l1.y2) * c2) / denom
        if (px < -32.0 || px > INPUT_SIZE + 32.0 || py < -32.0 || py > INPUT_SIZE + 32.0) return null
        return Point(px.coerceIn(0.0, (INPUT_SIZE - 1).toDouble()), py.coerceIn(0.0, (INPUT_SIZE - 1).toDouble()))
    }

    private companion object {
        const val MODEL_ASSET_PATH = "models/fairscan_seg.tflite"
        const val INPUT_SIZE = 256
        const val INV_255 = 1f / 255f
        const val MEAN_R = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_B = 0.406f
        const val STD_R = 0.229f
        const val STD_G = 0.224f
        const val STD_B = 0.225f
        const val MASK_THRESHOLD = 0.50f
        const val MIN_MASK_RATIO = 0.06f
        const val MAX_MASK_RATIO = 0.96f
    }
}
