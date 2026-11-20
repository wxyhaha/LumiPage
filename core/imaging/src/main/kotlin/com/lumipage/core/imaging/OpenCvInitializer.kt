package com.lumipage.core.imaging

import org.opencv.android.OpenCVLoader

/**
 * 线程安全的 OpenCV Native 库初始化器。
 */
object OpenCvInitializer {
    @Volatile
    private var initialized: Boolean = false

    fun ensureInitialized(): Boolean {
        if (initialized) return true
        synchronized(this) {
            if (!initialized) {
                initialized = OpenCVLoader.initLocal()
            }
            return initialized
        }
    }
}
