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
        autoStraighten: Boolean = true,
    ): Bitmap {
        if (!OpenCvInitializer.ensureInitialized()) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }

        return withMatScope {
            val rawMat = newMat()
            Utils.bitmapToMat(source, rawMat)

            // 先按页面旋转角度旋转原图，再按旋转后坐标空间的归一化 quad 进行透视裁剪，
            // 确保与 CropScreen / RecropScreen 的视觉坐标系 100% 一致
            val srcMat = rotateMat(rawMat, rotationDegrees)

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
                imageWidth = w,
                imageHeight = h,
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

            val finalMat = if (autoStraighten) {
                PageDeskewer.cleanAndDeskew(this, warped)
            } else {
                warped
            }

            val outBitmap = Bitmap.createBitmap(
                finalMat.cols(),
                finalMat.rows(),
                Bitmap.Config.ARGB_8888,
            )
            Utils.matToBitmap(finalMat, outBitmap)
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
         * 根据四顶点几何边长与单视图 3D 透视纵横比恢复（Zhang 方法）计算透视矫正后的真实输出宽高，
         * 避免斜拍俯仰透视缩距导致的“上下压缩/横向拉扁”失真，并限制不超过最大边长。
         */
        fun calculateTargetSize(
            tl: Point,
            tr: Point,
            br: Point,
            bl: Point,
            maxOutputDimension: Int = 4096,
            imageWidth: Double = 0.0,
            imageHeight: Double = 0.0,
        ): Pair<Int, Int> {
            val widthTop = hypot(tr.x - tl.x, tr.y - tl.y)
            val widthBottom = hypot(br.x - bl.x, br.y - bl.y)
            val heightLeft = hypot(bl.x - tl.x, bl.y - tl.y)
            val heightRight = hypot(br.x - tr.x, br.y - tr.y)

            var outW = max(1.0, max(widthTop, widthBottom))
            var outH = max(1.0, max(heightLeft, heightRight))

            if (imageWidth > 0.0 && imageHeight > 0.0) {
                val recoveredRatio = recoverPlanarAspectRatio(
                    tl = tl,
                    tr = tr,
                    br = br,
                    bl = bl,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                )
                if (recoveredRatio != null && recoveredRatio.isFinite()) {
                    val naiveRatio = outW / outH
                    val clampedRatio = recoveredRatio
                        .coerceIn(naiveRatio * 0.55, naiveRatio * 1.85)
                        .coerceIn(0.35, 2.8)
                    if (clampedRatio < naiveRatio) {
                        // 纵向因斜拍透视缩短：保持宽度不变，拉回真实高度，不损失横向分辨率
                        outH = outW / clampedRatio
                    } else if (clampedRatio > naiveRatio) {
                        // 横向因斜拍透视缩短：保持高度不变，拉回真实宽度
                        outW = outH * clampedRatio
                    }
                }
            }

            var finalW = max(1, outW.roundToInt())
            var finalH = max(1, outH.roundToInt())

            val maxSide = max(finalW, finalH)
            if (maxOutputDimension > 0 && maxSide > maxOutputDimension) {
                val scale = maxOutputDimension.toDouble() / maxSide.toDouble()
                finalW = max(1, (finalW * scale).roundToInt())
                finalH = max(1, (finalH * scale).roundToInt())
            }
            return finalW to finalH
        }

        /**
         * 基于针孔相机模型（光心位于图像中心）从平面矩形四顶点恢复真实物理宽高比 (W / H)。
         */
        internal fun recoverPlanarAspectRatio(
            tl: Point,
            tr: Point,
            br: Point,
            bl: Point,
            imageWidth: Double,
            imageHeight: Double,
        ): Double? {
            val cx = imageWidth * 0.5
            val cy = imageHeight * 0.5
            val m1 = doubleArrayOf(tl.x - cx, tl.y - cy, 1.0)
            val m2 = doubleArrayOf(tr.x - cx, tr.y - cy, 1.0)
            val m3 = doubleArrayOf(bl.x - cx, bl.y - cy, 1.0)
            val m4 = doubleArrayOf(br.x - cx, br.y - cy, 1.0)

            // 求解 [m2, m3, -m4] * [k2, k3, k4]^T = m1
            val k = solve3x3(
                col0 = m2,
                col1 = m3,
                col2 = doubleArrayOf(-m4[0], -m4[1], -m4[2]),
                rhs = m1,
            ) ?: return null

            val k2 = k[0]
            val k3 = k[1]
            val n2x = k2 * m2[0] - m1[0]
            val n2y = k2 * m2[1] - m1[1]
            val n2z = k2 * m2[2] - m1[2]

            val n3x = k3 * m3[0] - m1[0]
            val n3y = k3 * m3[1] - m1[1]
            val n3z = k3 * m3[2] - m1[2]

            val maxDim = max(imageWidth, imageHeight)
            val denom = n2z * n3z
            val f = if (kotlin.math.abs(denom) > 1e-6) {
                val f2 = -(n2x * n3x + n2y * n3y) / denom
                if (f2 > 0.0 && f2.isFinite()) {
                    kotlin.math.sqrt(f2).coerceIn(0.75 * maxDim, 2.2 * maxDim)
                } else {
                    1.1 * maxDim
                }
            } else {
                1.1 * maxDim
            }

            val f2 = f * f
            val wSq = n2x * n2x + n2y * n2y + f2 * n2z * n2z
            val hSq = n3x * n3x + n3y * n3y + f2 * n3z * n3z
            if (wSq <= 0.0 || hSq <= 0.0) return null
            return kotlin.math.sqrt(wSq / hSq)
        }

        private fun solve3x3(
            col0: DoubleArray,
            col1: DoubleArray,
            col2: DoubleArray,
            rhs: DoubleArray,
        ): DoubleArray? {
            fun det3(
                a0: Double, a1: Double, a2: Double,
                b0: Double, b1: Double, b2: Double,
                c0: Double, c1: Double, c2: Double,
            ): Double {
                return a0 * (b1 * c2 - b2 * c1) -
                    b0 * (a1 * c2 - a2 * c1) +
                    c0 * (a1 * b2 - a2 * b1)
            }

            val det = det3(
                col0[0], col0[1], col0[2],
                col1[0], col1[1], col1[2],
                col2[0], col2[1], col2[2],
            )
            if (kotlin.math.abs(det) < 1e-9) return null

            val dx = det3(
                rhs[0], rhs[1], rhs[2],
                col1[0], col1[1], col1[2],
                col2[0], col2[1], col2[2],
            )
            val dy = det3(
                col0[0], col0[1], col0[2],
                rhs[0], rhs[1], rhs[2],
                col2[0], col2[1], col2[2],
            )
            val dz = det3(
                col0[0], col0[1], col0[2],
                col1[0], col1[1], col1[2],
                rhs[0], rhs[1], rhs[2],
            )
            return doubleArrayOf(dx / det, dy / det, dz / det)
        }
    }
}
