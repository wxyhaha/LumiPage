package com.lumipage.core.imaging

import com.lumipage.core.model.DocumentQuad

/**
 * 商业级滞回锁定 + 死区防抖实时追踪稳定器（方案 A）：
 * 1. SEARCHING 阶段：需连续 [acquireFrames] 帧定位一致才进入 LOCKED，杜绝偶发误检闪框；
 * 2. LOCKED 阶段：强滞回保持，容忍最多 [maxLockedMissedFrames] 帧短暂模糊/遮挡；
 * 3. 死区防抖：位移小于 [deadZoneThreshold] 时直接冻结不动，彻底消除人手微颤抖动；
 * 4. 异常跳变拦截：需连续 [jumpConfirmFrames] 帧确认大幅移动才切换目标，消除闪烁。
 */
class QuadStabilizer(
    private val acquireFrames: Int = 3,
    private val maxLockedMissedFrames: Int = 12,
    private val acquireDistanceThreshold: Float = 0.045f,
    private val deadZoneThreshold: Float = 0.012f,
    private val jumpDistanceThreshold: Float = 0.14f,
    private val jumpConfirmFrames: Int = 4,
    private val smoothingAlpha: Float = 0.30f,
) {
    enum class TrackingPhase {
        SEARCHING,
        LOCKED,
    }

    data class StabilizerState(
        val displayQuad: DocumentQuad?,
        val stableFrameCount: Int,
        val stabilityProgress: Float,
        val isReadyForAutoCapture: Boolean,
        val phase: TrackingPhase = TrackingPhase.SEARCHING,
    )

    private var phase: TrackingPhase = TrackingPhase.SEARCHING
    private var candidateQuad: DocumentQuad? = null
    private var candidateFrames: Int = 0

    private var lockedQuad: DocumentQuad? = null
    private var lockedMissedFrames: Int = 0
    private var pendingJumpQuad: DocumentQuad? = null
    private var pendingJumpCount: Int = 0
    private var stableHoldCount: Int = 0

    fun update(
        detectedQuad: DocumentQuad?,
        isSceneStatic: Boolean = false,
    ): StabilizerState {
        return when (phase) {
            TrackingPhase.SEARCHING -> handleSearching(detectedQuad)
            TrackingPhase.LOCKED -> handleLocked(detectedQuad, isSceneStatic)
        }
    }

    private fun handleSearching(detectedQuad: DocumentQuad?): StabilizerState {
        if (detectedQuad == null) {
            candidateQuad = null
            candidateFrames = 0
            return StabilizerState(
                displayQuad = null,
                stableFrameCount = 0,
                stabilityProgress = 0f,
                isReadyForAutoCapture = false,
                phase = TrackingPhase.SEARCHING,
            )
        }

        val prev = candidateQuad
        if (prev != null && detectedQuad.maxCornerDistance(prev) <= acquireDistanceThreshold) {
            candidateQuad = prev.lerpTo(detectedQuad, 0.5f)
            candidateFrames++
        } else {
            candidateQuad = detectedQuad
            candidateFrames = 1
        }

        if (candidateFrames >= acquireFrames) {
            phase = TrackingPhase.LOCKED
            lockedQuad = candidateQuad
            lockedMissedFrames = 0
            pendingJumpQuad = null
            pendingJumpCount = 0
            stableHoldCount = acquireFrames
            return StabilizerState(
                displayQuad = lockedQuad,
                stableFrameCount = stableHoldCount,
                stabilityProgress = 1f,
                isReadyForAutoCapture = true,
                phase = TrackingPhase.LOCKED,
            )
        }

        return StabilizerState(
            displayQuad = null,
            stableFrameCount = candidateFrames,
            stabilityProgress = candidateFrames.toFloat() / acquireFrames.toFloat(),
            isReadyForAutoCapture = false,
            phase = TrackingPhase.SEARCHING,
        )
    }

    private fun handleLocked(
        detectedQuad: DocumentQuad?,
        isSceneStatic: Boolean,
    ): StabilizerState {
        val current = lockedQuad ?: return handleSearching(detectedQuad)

        // 静止门控：镜头未晃动且已锁定时，直接锁死显示框纹丝不动，屏蔽噪点与 AE 波动
        if (isSceneStatic && detectedQuad != null) {
            val delta = detectedQuad.maxCornerDistance(current)
            if (delta <= jumpDistanceThreshold) {
                lockedMissedFrames = 0
                pendingJumpQuad = null
                pendingJumpCount = 0
                stableHoldCount++
                return StabilizerState(
                    displayQuad = current,
                    stableFrameCount = stableHoldCount,
                    stabilityProgress = 1f,
                    isReadyForAutoCapture = true,
                    phase = TrackingPhase.LOCKED,
                )
            }
        }

        if (detectedQuad == null) {
            lockedMissedFrames++
            pendingJumpQuad = null
            pendingJumpCount = 0
            if (lockedMissedFrames > maxLockedMissedFrames) {
                reset()
                return StabilizerState(
                    displayQuad = null,
                    stableFrameCount = 0,
                    stabilityProgress = 0f,
                    isReadyForAutoCapture = false,
                    phase = TrackingPhase.SEARCHING,
                )
            }
            return StabilizerState(
                displayQuad = current,
                stableFrameCount = stableHoldCount,
                stabilityProgress = 1f,
                isReadyForAutoCapture = true,
                phase = TrackingPhase.LOCKED,
            )
        }

        lockedMissedFrames = 0
        val delta = detectedQuad.maxCornerDistance(current)

        if (delta > jumpDistanceThreshold) {
            val pending = pendingJumpQuad
            if (pending != null && detectedQuad.maxCornerDistance(pending) <= acquireDistanceThreshold) {
                pendingJumpCount++
                pendingJumpQuad = pending.lerpTo(detectedQuad, 0.4f)
            } else {
                pendingJumpQuad = detectedQuad
                pendingJumpCount = 1
            }

            if (pendingJumpCount >= jumpConfirmFrames) {
                lockedQuad = pendingJumpQuad
                pendingJumpQuad = null
                pendingJumpCount = 0
                stableHoldCount = 1
            }
        } else {
            pendingJumpQuad = null
            pendingJumpCount = 0
            // 死区防抖：微幅抖动直接冻结不动，超过死区才阻尼平滑
            if (delta > deadZoneThreshold) {
                lockedQuad = current.lerpTo(detectedQuad, smoothingAlpha)
            }
            stableHoldCount++
        }

        return StabilizerState(
            displayQuad = lockedQuad,
            stableFrameCount = stableHoldCount,
            stabilityProgress = 1f,
            isReadyForAutoCapture = true,
            phase = TrackingPhase.LOCKED,
        )
    }

    fun reset() {
        phase = TrackingPhase.SEARCHING
        candidateQuad = null
        candidateFrames = 0
        lockedQuad = null
        lockedMissedFrames = 0
        pendingJumpQuad = null
        pendingJumpCount = 0
        stableHoldCount = 0
    }
}
