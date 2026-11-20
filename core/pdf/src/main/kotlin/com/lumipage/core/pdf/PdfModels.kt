package com.lumipage.core.pdf

enum class PdfPageSizePreset(val label: String) {
    FIT_IMAGE("自适应原图"),
    A4("A4 标准"),
    LETTER("Letter"),
}

enum class PdfCompressionPreset(
    val label: String,
    val maxDimension: Int,
    val jpegQuality: Int,
) {
    HIGH("高清画质", maxDimension = 2480, jpegQuality = 90),
    BALANCED("均衡推荐", maxDimension = 1650, jpegQuality = 76),
    COMPACT("极限压缩", maxDimension = 1200, jpegQuality = 60),
}

data class PdfGenerationConfig(
    val pageSize: PdfPageSizePreset = PdfPageSizePreset.A4,
    val compression: PdfCompressionPreset = PdfCompressionPreset.BALANCED,
    val marginPt: Float = 18f,
)

data class PdfPageEditSpec(
    val originalIndex: Int,
    val extraRotationDegrees: Int = 0,
)

data class PdfCompressionResult(
    val outputPath: String,
    val originalBytes: Long,
    val compressedBytes: Long,
)
