package com.lumipage.core.data

import android.content.Context
import android.graphics.Bitmap
import com.lumipage.core.common.BitmapLoader
import com.lumipage.core.common.DispatcherProvider
import com.lumipage.core.imaging.ImagingEngine
import com.lumipage.core.model.PageEditState
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 管理应用私有目录下的非破坏性文档存储：
 * - originals/{pageId}.jpg  原始拍摄/导入图（永不覆盖）
 * - processed/{pageId}.jpg  按当前编辑态渲染后的成品图
 * - thumbnails/{pageId}.jpg 缩略图
 * - drafts/                 扫描临时草稿
 * - exports/                临时导出与分享文件
 */
class FileStorageManager(
    context: Context,
    private val dispatchers: DispatcherProvider,
    private val imagingEngine: ImagingEngine,
) {
    private val rootDir = File(context.filesDir, "lumipage")
    val originalsDir = File(rootDir, "originals").apply { mkdirs() }
    val processedDir = File(rootDir, "processed").apply { mkdirs() }
    val thumbnailsDir = File(rootDir, "thumbnails").apply { mkdirs() }
    val draftsDir = File(rootDir, "drafts").apply { mkdirs() }
    val exportsDir = File(rootDir, "exports").apply { mkdirs() }

    data class RenderedPageFiles(
        val originalPath: String,
        val processedPath: String,
        val thumbnailPath: String,
        val width: Int,
        val height: Int,
    )

    suspend fun persistAndRenderNewPage(
        pageId: String,
        sourcePath: String,
        editState: PageEditState,
    ): RenderedPageFiles = withContext(dispatchers.io) {
        val originalFile = File(originalsDir, "$pageId.jpg")
        val srcFile = File(sourcePath)
        if (!originalFile.exists()) {
            srcFile.copyTo(originalFile, overwrite = true)
            if (srcFile.parentFile?.absolutePath == draftsDir.absolutePath) {
                srcFile.delete()
            }
        }
        renderFromOriginal(
            pageId = pageId,
            originalPath = originalFile.absolutePath,
            editState = editState,
        )
    }

    suspend fun renderFromOriginal(
        pageId: String,
        originalPath: String,
        editState: PageEditState,
    ): RenderedPageFiles = withContext(dispatchers.io) {
        val originalBitmap = BitmapLoader.decodeSampledBitmap(originalPath, maxDimension = 4096)
            ?: error("Unable to decode original image at $originalPath")

        val rendered = try {
            imagingEngine.renderPage(
                source = originalBitmap,
                editState = editState,
                maxOutputDimension = 4096,
            )
        } finally {
            originalBitmap.recycle()
        }

        val processedFile = File(processedDir, "$pageId.jpg")
        val thumbnailFile = File(thumbnailsDir, "$pageId.jpg")
        val outWidth = rendered.width
        val outHeight = rendered.height

        try {
            BitmapLoader.saveJpeg(rendered, processedFile, quality = 92)
            saveThumbnail(rendered, thumbnailFile, maxSide = 480)
        } finally {
            rendered.recycle()
        }

        RenderedPageFiles(
            originalPath = originalPath,
            processedPath = processedFile.absolutePath,
            thumbnailPath = thumbnailFile.absolutePath,
            width = outWidth,
            height = outHeight,
        )
    }

    suspend fun deletePageFiles(pages: List<PageEntity>) = withContext(dispatchers.io) {
        pages.forEach { page ->
            runCatching { File(page.originalPath).delete() }
            runCatching { File(page.processedPath).delete() }
            runCatching { File(page.thumbnailPath).delete() }
        }
    }

    private fun saveThumbnail(source: Bitmap, targetFile: File, maxSide: Int) {
        val curMax = max(source.width, source.height)
        if (curMax <= maxSide) {
            BitmapLoader.saveJpeg(source, targetFile, quality = 85)
            return
        }
        val scale = maxSide.toFloat() / curMax.toFloat()
        val thumb = Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * scale).roundToInt()),
            max(1, (source.height * scale).roundToInt()),
            true,
        )
        try {
            BitmapLoader.saveJpeg(thumb, targetFile, quality = 85)
        } finally {
            if (thumb !== source) thumb.recycle()
        }
    }
}
