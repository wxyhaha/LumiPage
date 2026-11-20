package com.lumipage.core.imaging

import com.lumipage.core.model.DocumentQuad

/**
 * 实时帧检测结果平滑与自动拍摄稳定器。
 */
class QuadStabilizer(
    private val requiredStableFrames: Int = 8,
    private val maxMissedFrames: Int = 5,
    private val stabilityCornerThreshold: Float = 0.020f,
    private val smoothingAlpha: Float = 0.28f,
) {
    data class StabilizerState(
        val displayQuad: DocumentQuad?,
        val stableFrameCount: Int,
        val stabilityProgress: Float,
        val isReadyForAutoCapture: Boolean,
    )

    private var smoothedQuad: DocumentQuad? = null
    private var lastRawQuad: DocumentQuad? = null
    private var pendingJumpQuad: DocumentQuad? = null
    private var stableCount: Int = 0
    private var missedCount: Int = 0

    fun update(detectedQuad: DocumentQuad?): StabilizerState {
        if (detectedQuad == null) {
            pendingJumpQuad = null
            missedCount++
            if (missedCount > maxMissedFrames) {
                reset()
                return StabilizerState(
                    displayQuad = null,
                    stableFrameCount = 0,
                    stabilityProgress = 0f,
                    isReadyForAutoCapture = false,
                )
            }
            return StabilizerState(
                displayQuad = smoothedQuad,
                stableFrameCount = stableCount,
                stabilityProgress = (stableCount.toFloat() / requiredStableFrames).coerceIn(0f, 1f),
                isReadyForAutoCapture = false,
            )
        }

        missedCount = 0
        val prevRaw = lastRawQuad
        lastRawQuad = detectedQuad

        val currentSmoothed = smoothedQuad
        if (currentSmoothed == null || prevRaw == null) {
            smoothedQuad = detectedQuad
            pendingJumpQuad = null
            stableCount = 1
        } else {
            val delta = detectedQuad.maxCornerDistance(currentSmoothed)
            if (delta > 0.16f) {
                // 单帧大幅跳变先观察一帧，连续两帧确认在同一新区域才切换，杜绝闪烁
                val pending = pendingJumpQuad
                if (pending != null && detectedQuad.maxCornerDistance(pending) <= 0.08f) {
                    smoothedQuad = detectedQuad
                    pendingJumpQuad = null
                    stableCount = 1
                } else {
                    pendingJumpQuad = detectedQuad
                    stableCount = (stableCount - 1).coerceAtLeast(0)
                }
            } else {
                pendingJumpQuad = null
                smoothedQuad = currentSmoothed.lerpTo(detectedQuad, smoothingAlpha)
                val rawDelta = detectedQuad.maxCornerDistance(prevRaw)
                if (rawDelta <= stabilityCornerThreshold) {
                    stableCount = (stableCount + 1).coerceAtMost(requiredStableFrames)
                } else {
                    stableCount = (stableCount - 1).coerceAtLeast(1)
                }
            }
        }

        val progress = (stableCount.toFloat() / requiredStableFrames).coerceIn(0f, 1f)
        return StabilizerState(
            displayQuad = smoothedQuad,
            stableFrameCount = stableCount,
            stabilityProgress = progress,
            isReadyForAutoCapture = stableCount >= requiredStableFrames,
        )
    }

    fun reset() {
        smoothedQuad = null
        lastRawQuad = null
        pendingJumpQuad = null
        stableCount = 0
        missedCount = 0
    }
}
