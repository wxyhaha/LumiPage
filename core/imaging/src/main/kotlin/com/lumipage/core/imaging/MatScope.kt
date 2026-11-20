package com.lumipage.core.imaging

import org.opencv.core.Mat

/**
 * 作用域内自动追踪并释放 OpenCV Mat，防止 Native 内存泄漏。
 */
class MatScope {
    private val mats = mutableListOf<Mat>()

    fun <T : Mat> track(mat: T): T {
        mats.add(mat)
        return mat
    }

    fun newMat(): Mat = track(Mat())

    fun releaseAll() {
        for (i in mats.lastIndex downTo 0) {
            runCatching { mats[i].release() }
        }
        mats.clear()
    }
}

inline fun <T> withMatScope(block: MatScope.() -> T): T {
    val scope = MatScope()
    return try {
        scope.block()
    } finally {
        scope.releaseAll()
    }
}
