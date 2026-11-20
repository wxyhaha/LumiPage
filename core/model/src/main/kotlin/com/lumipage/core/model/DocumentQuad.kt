package com.lumipage.core.model

import kotlin.math.abs
import kotlin.math.max

/**
 * 文档四顶点模型，按顺时针顺序定义：左上 -> 右上 -> 右下 -> 左下。
 */
data class DocumentQuad(
    val topLeft: NormalizedPoint,
    val topRight: NormalizedPoint,
    val bottomRight: NormalizedPoint,
    val bottomLeft: NormalizedPoint,
) {
    fun asList(): List<NormalizedPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun coerced(): DocumentQuad = DocumentQuad(
        topLeft = topLeft.coerced(),
        topRight = topRight.coerced(),
        bottomRight = bottomRight.coerced(),
        bottomLeft = bottomLeft.coerced(),
    )

    /**
     * 计算多边形归一化面积（Shoelace 公式），范围 [0f, 1f]。
     */
    fun normalizedArea(): Float {
        val pts = asList()
        var sum = 0f
        for (i in pts.indices) {
            val cur = pts[i]
            val next = pts[(i + 1) % pts.size]
            sum += (cur.x * next.y) - (next.x * cur.y)
        }
        return abs(sum) * 0.5f
    }

    /**
     * 校验是否为严格凸四边形且面积高于最小阈值。
     */
    fun isValidConvexQuad(minArea: Float = 0.02f): Boolean {
        if (normalizedArea() < minArea) return false
        val pts = asList()
        var sign = 0
        for (i in pts.indices) {
            val p0 = pts[i]
            val p1 = pts[(i + 1) % 4]
            val p2 = pts[(i + 2) % 4]
            val cross = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x)
            if (abs(cross) < 1e-6f) return false
            val currentSign = if (cross > 0f) 1 else -1
            if (sign == 0) {
                sign = currentSign
            } else if (sign != currentSign) {
                return false
            }
        }
        return true
    }

    /**
     * 计算与另一个四边形四个对应角点的最大欧氏距离。
     */
    fun maxCornerDistance(other: DocumentQuad): Float {
        return max(
            max(topLeft.distanceTo(other.topLeft), topRight.distanceTo(other.topRight)),
            max(bottomRight.distanceTo(other.bottomRight), bottomLeft.distanceTo(other.bottomLeft)),
        )
    }

    /**
     * 按给定权重向目标四边形做线性插值，用于实时预览框平滑。
     */
    fun lerpTo(target: DocumentQuad, alpha: Float): DocumentQuad {
        val t = alpha.coerceIn(0f, 1f)
        fun lerpPoint(a: NormalizedPoint, b: NormalizedPoint) = NormalizedPoint(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
        )
        return DocumentQuad(
            topLeft = lerpPoint(topLeft, target.topLeft),
            topRight = lerpPoint(topRight, target.topRight),
            bottomRight = lerpPoint(bottomRight, target.bottomRight),
            bottomLeft = lerpPoint(bottomLeft, target.bottomLeft),
        )
    }

    companion object {
        val FULL = DocumentQuad(
            topLeft = NormalizedPoint(0f, 0f),
            topRight = NormalizedPoint(1f, 0f),
            bottomRight = NormalizedPoint(1f, 1f),
            bottomLeft = NormalizedPoint(0f, 1f),
        )

        val DEFAULT_INSET = DocumentQuad(
            topLeft = NormalizedPoint(0.08f, 0.08f),
            topRight = NormalizedPoint(0.92f, 0.08f),
            bottomRight = NormalizedPoint(0.92f, 0.92f),
            bottomLeft = NormalizedPoint(0.08f, 0.92f),
        )

        /**
         * 将任意顺序的 4 个点稳定排序为顺时针：topLeft -> topRight -> bottomRight -> bottomLeft。
         * 先按质心极角排序保证不自交，再以 (x + y) 最小的点作为 topLeft 旋转对齐。
         */
        fun fromUnorderedPoints(points: List<NormalizedPoint>): DocumentQuad {
            require(points.size == 4) { "DocumentQuad requires exactly 4 points, got ${points.size}" }
            val cx = points.map { it.x }.average().toFloat()
            val cy = points.map { it.y }.average().toFloat()

            // 在屏幕坐标系（y 向下）中，atan2(y - cy, x - cx) 升序即顺时针方向
            val clockwise = points.sortedBy { pt ->
                kotlin.math.atan2((pt.y - cy).toDouble(), (pt.x - cx).toDouble())
            }

            // 找到最接近左上角的顶点（x + y 最小）
            val topLeftIndex = clockwise.indices.minByOrNull { idx ->
                clockwise[idx].x + clockwise[idx].y
            } ?: 0

            val rotated = (0 until 4).map { offset ->
                clockwise[(topLeftIndex + offset) % 4].coerced()
            }

            return DocumentQuad(
                topLeft = rotated[0],
                topRight = rotated[1],
                bottomRight = rotated[2],
                bottomLeft = rotated[3],
            )
        }
    }
}
