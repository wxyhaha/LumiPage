package com.lumipage.core.model

import kotlin.math.hypot

/**
 * 归一化二维坐标点，x 与 y 的有效范围为 [0f, 1f]。
 * 使用归一化坐标可确保四顶点在不同分辨率（分析帧、预览图、原始高清图）之间无损映射。
 */
data class NormalizedPoint(
    val x: Float,
    val y: Float,
) {
    fun coerced(): NormalizedPoint = NormalizedPoint(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
    )

    fun distanceTo(other: NormalizedPoint): Float =
        hypot(x - other.x, y - other.y)

    companion object {
        val ZERO = NormalizedPoint(0f, 0f)
    }
}
