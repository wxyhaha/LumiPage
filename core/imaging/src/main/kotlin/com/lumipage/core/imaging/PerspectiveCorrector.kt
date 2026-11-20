package com.lumipage.core.imaging

import android.graphics.Bitmap
import com.lumipage.core.model.DocumentQuad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 基于四顶点归一化坐标执行透视变换矫正与 90° 倍数旋转。
 */
class PerspectiveCorrector {

    /**
     * 对输入 Bitmap 执行透视矫正与旋转，返回新的矫正后 Bitmap（调用方负责回收）。
     */
    fun warpAndRotate(
        source: Bitmap,
        quad: DocumentQuad,
        rotationDegrees: Int = 0,
        maxOutputDimension: Int = 4096,
    ): Bitmap {
        if (!OpenCvInitializer.ensureInitialized()) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }

        return withMatScope {
            val srcMat = newMat()
            Utils.bitmapToMat(source, srcMat)

            val w = srcMat.cols().toDouble()
            val h = srcMat.rows().toDouble()

            val tl = Point(quad.topLeft.x * w, quad.topLeft.y * h)
            val tr = Point(quad.topRight.x * w, quad.topRight.y * h)
            val br = Point(quad.bottomRight.x * w, quad.bottomRight.y * h)
            val bl = Point(quad.bottomLeft.x * w, quad.bottomLeft.y * h)

            val (targetWidth, targetHeight) = calculateTargetSize(
                tl = tl,
                tr = tr,
                br = br,
                bl = bl,
                maxOutputDimension = maxOutputDimension,
            )

            val srcPoints = track(MatOfPoint2f(tl, tr, br, bl))
            val dstPoints = track(
                MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point((targetWidth - 1).toDouble(), 0.0),
                    Point((targetWidth - 1).toDouble(), (targetHeight - 1).toDouble()),
                    Point(0.0, (targetHeight - 1).toDouble()),
                ),
            )

            val transform = track(Imgproc.getPerspectiveTransform(srcPoints, dstPoints))
            val warped = newMat()
            Imgproc.warpPerspective(
                srcMat,
                warped,
                transform,
                Size(targetWidth.toDouble(), targetHeight.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_REPLICATE,
            )

            val rotated = rotateMat(warped, rotationDegrees)
            val outBitmap = Bitmap.createBitmap(
                rotated.cols(),
                rotated.rows(),
                Bitmap.Config.ARGB_8888,
            )
            Utils.matToBitmap(rotated, outBitmap)
            outBitmap
        }
    }

    private fun MatScope.rotateMat(src: Mat, degrees: Int): Mat {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return src
        val dst = newMat()
        when (normalized) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return src
        }
        return dst
    }

    companion object {
        /**
         * 根据四顶点几何边长计算透视矫正后的输出宽高，并限制不超过最大边长。
         */
        fun calculateTargetSize(
            tl: Point,
            tr: Point,
            br: Point,
            bl: Point,
            maxOutputDimension: Int = 4096,
        ): Pair<Int, Int> {
            val widthTop = hypot(tr.x - tl.x, tr.y - tl.y)
            val widthBottom = hypot(br.x - bl.x, br.y - bl.y)
            val heightLeft = hypot(bl.x - tl.x, bl.y - tl.y)
            val heightRight = hypot(br.x - tr.x, br.y - tr.y)

            var outW = max(1, max(widthTop, widthBottom).roundToInt())
            var outH = max(1, max(heightLeft, heightRight).roundToInt())

            val maxSide = max(outW, outH)
            if (maxOutputDimension > 0 && maxSide > maxOutputDimension) {
                val scale = maxOutputDimension.toDouble() / maxSide.toDouble()
                outW = max(1, (outW * scale).roundToInt())
                outH = max(1, (outH * scale).roundToInt())
            }
            return outW to outH
        }
    }
}
