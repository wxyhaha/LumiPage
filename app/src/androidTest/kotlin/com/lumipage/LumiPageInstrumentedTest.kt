package com.lumipage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumipage.core.common.DefaultDispatcherProvider
import com.lumipage.core.imaging.DocumentDetector
import com.lumipage.core.imaging.ImageEnhancer
import com.lumipage.core.imaging.OpenCvInitializer
import com.lumipage.core.imaging.PerspectiveCorrector
import com.lumipage.core.model.EnhancementFilter
import com.lumipage.core.model.EnhancementParams
import com.lumipage.core.pdf.PdfCompressionPreset
import com.lumipage.core.pdf.PdfGenerationConfig
import com.lumipage.core.pdf.PdfPageSizePreset
import com.lumipage.core.pdf.PdfService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class LumiPageInstrumentedTest {

    @Test
    fun openCvPipelineDetectsWarpsAndEnhancesOnRealDevice() {
        assertTrue("OpenCV failed to initialize on device", OpenCvInitializer.ensureInitialized())

        // 构造一张包含深色桌面背景 + 倾斜白纸文档 + 文字线条的真实 Bitmap
        val width = 900
        val height = 1200
        val scene = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scene)
        canvas.drawColor(Color.rgb(42, 52, 68))

        val paperPaint = Paint().apply { color = Color.rgb(244, 244, 240) }
        val path = android.graphics.Path().apply {
            moveTo(150f, 160f)
            lineTo(760f, 200f)
            lineTo(720f, 1030f)
            lineTo(120f, 990f)
            close()
        }
        canvas.drawPath(path, paperPaint)

        val textPaint = Paint().apply {
            color = Color.rgb(25, 25, 30)
            textSize = 36f
            isAntiAlias = true
        }
        canvas.drawText("LUMIPAGE DEVICE TEST", 210f, 320f, textPaint)
        canvas.drawText("Shadow & Perspective Check", 210f, 400f, textPaint)

        val detector = DocumentDetector()
        val detection = detector.detect(scene)
        assertNotNull("Document quad should be detected on device", detection.quad)
        assertTrue("Confidence should be >= 0.42", detection.confidence >= 0.42f)

        val corrector = PerspectiveCorrector()
        val warped = corrector.warpAndRotate(
            source = scene,
            quad = detection.quad!!,
            rotationDegrees = 0,
            maxOutputDimension = 1600,
        )
        assertTrue(warped.width > 300 && warped.height > 400)

        val enhancer = ImageEnhancer()
        for (filter in EnhancementFilter.entries) {
            val out = enhancer.enhance(warped, EnhancementParams(filter = filter))
            assertEquals(warped.width, out.width)
            assertEquals(warped.height, out.height)
            out.recycle()
        }

        warped.recycle()
        scene.recycle()
    }

    @Test
    fun pdfGenerateRenderCompressAndSplitWorksOnRealDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdfService = PdfService(context, DefaultDispatcherProvider())

        val testDir = File(context.cacheDir, "pdf_inst_test").apply { mkdirs() }
        val imgFile = File(testDir, "page1.jpg")
        val bmp = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        FileOutputStream(imgFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()

        val pdfFile = File(testDir, "doc.pdf")
        pdfService.generatePdfFromImages(
            imagePaths = listOf(imgFile.absolutePath, imgFile.absolutePath),
            outputFile = pdfFile,
            config = PdfGenerationConfig(
                pageSize = PdfPageSizePreset.A4,
                compression = PdfCompressionPreset.BALANCED,
            ),
        )
        assertTrue(pdfFile.exists() && pdfFile.length() > 0)
        assertEquals(2, pdfService.getPdfPageCount(pdfFile))

        val rendered = pdfService.renderPdfPage(pdfFile, 0, maxWidth = 600)
        assertNotNull(rendered)
        rendered?.recycle()

        val splitFile = File(testDir, "split.pdf")
        pdfService.splitPdf(pdfFile, splitFile, listOf(1))
        assertEquals(1, pdfService.getPdfPageCount(splitFile))

        val compressedFile = File(testDir, "compressed.pdf")
        val compResult = pdfService.compressPdf(pdfFile, compressedFile, PdfCompressionPreset.COMPACT)
        assertTrue(compResult.compressedBytes > 0)
        assertEquals(2, pdfService.getPdfPageCount(compressedFile))
    }
}
