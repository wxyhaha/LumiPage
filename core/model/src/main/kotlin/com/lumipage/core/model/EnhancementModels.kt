package com.lumipage.core.model

/**
 * 文档图像增强模式。
 */
enum class EnhancementFilter {
    /** 原图（仅保留裁剪与透视矫正） */
    ORIGINAL,

    /** 自动增强（去阴影、背景净化、对比度拉伸与轻度锐化，默认推荐） */
    AUTO_ENHANCE,

    /** 彩色文档（白平衡与背景漂白，保留印章、批注与图表色彩） */
    DOCUMENT_COLOR,

    /** 灰度增强（干净均匀的灰度文档） */
    GRAYSCALE,

    /** 黑白文档（光照归一化 + 自适应二值化，适合打印与高压缩） */
    BLACK_AND_WHITE,
}

/**
 * 图像增强参数，支持非破坏性持久化与实时调整。
 *
 * @property brightness 亮度偏移，范围 [-1.0f, 1.0f]，默认 0f
 * @property contrast 对比度倍率，范围 [0.5f, 2.0f]，默认 1f
 * @property sharpness 锐化强度，范围 [0f, 1.0f]，默认 0.25f
 */
data class EnhancementParams(
    val filter: EnhancementFilter = EnhancementFilter.AUTO_ENHANCE,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val sharpness: Float = 0.25f,
) {
    fun clamped(): EnhancementParams = copy(
        brightness = brightness.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(0.5f, 2f),
        sharpness = sharpness.coerceIn(0f, 1f),
    )

    companion object {
        val DEFAULT_ORIGINAL = EnhancementParams(
            filter = EnhancementFilter.ORIGINAL,
            brightness = 0f,
            contrast = 1f,
            sharpness = 0f,
        )

        val DEFAULT_AUTO = EnhancementParams(
            filter = EnhancementFilter.AUTO_ENHANCE,
            brightness = 0f,
            contrast = 1.05f,
            sharpness = 0.25f,
        )
    }
}
