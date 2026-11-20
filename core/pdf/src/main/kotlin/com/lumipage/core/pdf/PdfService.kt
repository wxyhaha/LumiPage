package com.lumipage.core.pdf

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.lumipage.core.common.BitmapLoader
import com.lumipage.core.common.DispatcherProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdfService(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    @Volatile
    private var pdfBoxInitialized = false

    private fun ensurePdfBoxInitialized() {
        if (!pdfBoxInitialized) {
            synchronized(this) {
                if (!pdfBoxInitialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    pdfBoxInitialized = true
                }
            }
        }
    }

    /**
     * 流式将多张图片生成为可控制体积与页面尺寸的 PDF 文件。
     */
    suspend fun generatePdfFromImages(
        imagePaths: List<String>,
        outputFile: File,
        config: PdfGenerationConfig = PdfGenerationConfig(),
    ): File = withContext(dispatchers.io) {
        require(imagePaths.isNotEmpty()) { "Cannot generate PDF from empty image list" }
        ensurePdfBoxInitialized()
        outputFile.parentFile?.mkdirs()

        PDDocument().use { document ->
            for (path in imagePaths) {
                val bitmap = BitmapLoader.decodeSampledBitmap(
                    filePath = path,
                    maxDimension = config.compression.maxDimension,
                    preferredConfig = Bitmap.Config.ARGB_8888,
                ) ?: continue

                try {
                    val jpegBytes = bitmapToJpegBytes(bitmap, config.compression.jpegQuality)
                    val pageRect = resolvePageRect(
                        imageWidth = bitmap.width,
                        imageHeight = bitmap.height,
                        preset = config.pageSize,
                    )
                    val page = PDPage(pageRect)
                    document.addPage(page)

                    val pdImage = ByteArrayInputStream(jpegBytes).use { stream ->
                        JPEGFactory.createFromStream(document, stream)
                    }

                    val margin = if (config.pageSize == PdfPageSizePreset.FIT_IMAGE) 0f else config.marginPt
                    val availW = max(1f, pageRect.width - margin * 2f)
                    val availH = max(1f, pageRect.height - margin * 2f)
                    val scale = min(availW / bitmap.width.toFloat(), availH / bitmap.height.toFloat())
                    val drawW = bitmap.width * scale
                    val drawH = bitmap.height * scale
                    val drawX = (pageRect.width - drawW) * 0.5f
                    val drawY = (pageRect.height - drawH) * 0.5f

                    PDPageContentStream(document, page).use { cs ->
                        cs.drawImage(pdImage, drawX, drawY, drawW, drawH)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            document.save(outputFile)
        }
        outputFile
    }

    /**
     * 获取本地 PDF 总页数。
     */
    suspend fun getPdfPageCount(pdfFile: File): Int = withContext(dispatchers.io) {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.pageCount
            }
        }
    }

    /**
     * 使用原生 PdfRenderer 渲染指定页面为 Bitmap（白色背景）。
     */
    suspend fun renderPdfPage(
        pdfFile: File,
        pageIndex: Int,
        maxWidth: Int = 1080,
    ): Bitmap? = withContext(dispatchers.io) {
        if (!pdfFile.exists()) return@withContext null
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return@withContext null
                renderer.openPage(pageIndex).use { page ->
                    val scale = maxWidth.toFloat() / max(1, page.width).toFloat()
                    val targetW = max(1, (page.width * scale).roundToInt())
                    val targetH = max(1, (page.height * scale).roundToInt())
                    val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    /**
     * 按给定页面规格列表执行排序、旋转与删除，输出新 PDF。
     */
    suspend fun editPdfPages(
        sourceFile: File,
        outputFile: File,
        pageSpecs: List<PdfPageEditSpec>,
    ): File = withContext(dispatchers.io) {
        require(pageSpecs.isNotEmpty()) { "At least one page must remain in PDF" }
        ensurePdfBoxInitialized()
        outputFile.parentFile?.mkdirs()

        PDDocument.load(sourceFile).use { srcDoc ->
            PDDocument().use { dstDoc ->
                for (spec in pageSpecs) {
                    if (spec.originalIndex !in 0 until srcDoc.numberOfPages) continue
                    val imported = dstDoc.importPage(srcDoc.getPage(spec.originalIndex))
                    val totalRot = ((imported.rotation + spec.extraRotationDegrees) % 360 + 360) % 360
                    imported.rotation = totalRot
                }
                dstDoc.save(outputFile)
            }
        }
        outputFile
    }

    /**
     * 合并多个本地 PDF 文件为一个 PDF。
     */
    suspend fun mergePdfFiles(
        sourceFiles: List<File>,
        outputFile: File,
    ): File = withContext(dispatchers.io) {
        require(sourceFiles.size >= 2) { "Need at least 2 PDF files to merge" }
        ensurePdfBoxInitialized()
        outputFile.parentFile?.mkdirs()

        val merger = PDFMergerUtility()
        merger.destinationFileName = outputFile.absolutePath
        sourceFiles.forEach { file ->
            if (file.exists()) {
                merger.addSource(file)
            }
        }
        merger.mergeDocuments(null)
        outputFile
    }

    /**
     * 合并来自 ContentResolver Uri 的多个 PDF。
     */
    suspend fun mergePdfUris(
        contentResolver: ContentResolver,
        uris: List<Uri>,
        outputFile: File,
    ): File = withContext(dispatchers.io) {
        require(uris.size >= 2) { "Need at least 2 PDFs to merge" }
        ensurePdfBoxInitialized()
        outputFile.parentFile?.mkdirs()

        val tempFiles = mutableListOf<File>()
        try {
            uris.forEachIndexed { idx, uri ->
                val tmp = File(outputFile.parentFile, "tmp_merge_$idx.pdf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { out -> input.copyTo(out) }
                }
                if (tmp.exists() && tmp.length() > 0) {
                    tempFiles.add(tmp)
                }
            }
            mergePdfFiles(tempFiles, outputFile)
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * 按页码区间（1-based，闭区间）拆分 PDF，输出新文件。
     */
    suspend fun splitPdf(
        sourceFile: File,
        outputFile: File,
        oneBasedPageIndices: List<Int>,
    ): File = withContext(dispatchers.io) {
        require(oneBasedPageIndices.isNotEmpty()) { "Split page selection cannot be empty" }
        ensurePdfBoxInitialized()
        outputFile.parentFile?.mkdirs()

        PDDocument.load(sourceFile).use { srcDoc ->
            PDDocument().use { dstDoc ->
                for (oneBased in oneBasedPageIndices) {
                    val zeroBased = oneBased - 1
                    if (zeroBased in 0 until srcDoc.numberOfPages) {
                        dstDoc.importPage(srcDoc.getPage(zeroBased))
                    }
                }
                dstDoc.save(outputFile)
            }
        }
        outputFile
    }

    /**
     * 对任意 PDF 进行重采样与 JPEG 重打包压缩。
     */
    suspend fun compressPdf(
        sourceFile: File,
        outputFile: File,
        preset: PdfCompressionPreset,
    ): PdfCompressionResult = withContext(dispatchers.io) {
        ensurePdfBoxInitialized()
        outputFile.parentFile?.mkdirs()
        val originalSize = sourceFile.length()

        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                PDDocument().use { outDoc ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val maxOrig = max(page.width, page.height).coerceAtLeast(1)
                            val scale = min(2.5f, preset.maxDimension.toFloat() / maxOrig.toFloat())
                            val w = max(1, (page.width * scale).roundToInt())
                            val h = max(1, (page.height * scale).roundToInt())
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            try {
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val jpegBytes = bitmapToJpegBytes(bmp, preset.jpegQuality)
                                val pdPage = PDPage(PDRectangle(page.width.toFloat(), page.height.toFloat()))
                                outDoc.addPage(pdPage)
                                val pdImg = ByteArrayInputStream(jpegBytes).use { stream ->
                                    JPEGFactory.createFromStream(outDoc, stream)
                                }
                                PDPageContentStream(outDoc, pdPage).use { cs ->
                                    cs.drawImage(pdImg, 0f, 0f, page.width.toFloat(), page.height.toFloat())
                                }
                            } finally {
                                bmp.recycle()
                            }
                        }
                    }
                    outDoc.save(outputFile)
                }
            }
        }

        PdfCompressionResult(
            outputPath = outputFile.absolutePath,
            originalBytes = originalSize,
            compressedBytes = outputFile.length(),
        )
    }

    /**
     * 将 PDF 复制写入公共 Downloads/LumiPage 目录（Android 10+ 使用 MediaStore）。
     */
    suspend fun savePdfToDownloads(
        sourceFile: File,
        displayFileName: String,
    ): Uri? = withContext(dispatchers.io) {
        val safeName = if (displayFileName.endsWith(".pdf", ignoreCase = true)) {
            displayFileName
        } else {
            "$displayFileName.pdf"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LumiPage")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "LumiPage").apply { mkdirs() }
            val target = File(targetDir, safeName)
            sourceFile.copyTo(target, overwrite = true)
            Uri.fromFile(target)
        }
    }

    suspend fun copyUriToTempPdf(uri: Uri, targetFile: File): File? = withContext(dispatchers.io) {
        targetFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { out -> input.copyTo(out) }
        }
        if (targetFile.exists() && targetFile.length() > 0) targetFile else null
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), baos)
            baos.toByteArray()
        }
    }

    private fun resolvePageRect(
        imageWidth: Int,
        imageHeight: Int,
        preset: PdfPageSizePreset,
    ): PDRectangle = when (preset) {
        PdfPageSizePreset.A4 -> {
            if (imageWidth > imageHeight) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
            else PDRectangle.A4
        }
        PdfPageSizePreset.LETTER -> {
            if (imageWidth > imageHeight) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width)
            else PDRectangle.LETTER
        }
        PdfPageSizePreset.FIT_IMAGE -> {
            // 归一化到 595pt 宽度，保持原始纵横比
            val w = 595f
            val h = max(100f, w * (imageHeight.toFloat() / max(1, imageWidth).toFloat()))
            PDRectangle(w, h)
        }
    }

    companion object {
        /**
         * 解析页码范围字符串（例如 "1-3, 5, 7-8"）为去重后的 1-based 页码列表。
         */
        fun parsePageRanges(input: String, maxPages: Int): List<Int> {
            if (maxPages <= 0 || input.isBlank()) return emptyList()
            val result = linkedSetOf<Int>()
            input.split(",", "，").forEach { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return@forEach
                if ("-" in trimmed) {
                    val parts = trimmed.split("-").mapNotNull { it.trim().toIntOrNull() }
                    if (parts.size == 2) {
                        val start = min(parts[0], parts[1]).coerceIn(1, maxPages)
                        val end = max(parts[0], parts[1]).coerceIn(1, maxPages)
                        for (p in start..end) result.add(p)
                    }
                } else {
                    val single = trimmed.toIntOrNull()
                    if (single != null && single in 1..maxPages) {
                        result.add(single)
                    }
                }
            }
            return result.toList()
        }
    }
}
