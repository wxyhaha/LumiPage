package com.lumipage.feature.scanner

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumipage.core.common.BitmapLoader
import com.lumipage.core.common.DispatcherProvider
import com.lumipage.core.imaging.ImagingEngine
import com.lumipage.core.imaging.QuadStabilizer
import com.lumipage.core.model.DocumentQuad
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ScannerViewModel(
    private val imagingEngine: ImagingEngine,
    private val dispatchers: DispatcherProvider,
    private val draftDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ScannerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ScannerEvent> = _events.asSharedFlow()

    private val stabilizer = QuadStabilizer()
    private val isAnalyzingFrame = AtomicBoolean(false)
    private var prevThumbLuma: IntArray? = null
    private val thumbPixels = IntArray(MOTION_GRID * MOTION_GRID)

    sealed interface ScannerEvent {
        data class CompletedScan(val pages: List<ScannedDraftPage>) : ScannerEvent
    }

    fun toggleFlashMode() {
        _uiState.update { state ->
            val next = when (state.flashMode) {
                FlashMode.OFF -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.TORCH
                FlashMode.TORCH -> FlashMode.OFF
            }
            state.copy(flashMode = next)
        }
    }

    fun toggleGrid() {
        _uiState.update { it.copy(showGrid = !it.showGrid) }
    }

    fun toggleBatchMode() {
        _uiState.update { it.copy(isBatchMode = !it.isBatchMode) }
    }

    fun onFrameAnalyzed(
        frameBitmap: Bitmap,
        previewWidth: Int,
        previewHeight: Int,
    ) {
        val state = _uiState.value
        if (state.step != ScanStep.CAMERA || state.isCapturing) {
            frameBitmap.recycle()
            return
        }
        if (!isAnalyzingFrame.compareAndSet(false, true)) {
            frameBitmap.recycle()
            return
        }

        viewModelScope.launch {
            try {
                val mad = computeFrameMad(frameBitmap)
                val isSceneStatic = mad in 0f..STATIC_MAD_THRESHOLD
                val resetTemporal = mad > LARGE_MOTION_MAD_THRESHOLD

                val t0 = System.currentTimeMillis()
                val result = imagingEngine.detectDocument(frameBitmap, resetTemporal = resetTemporal)
                val dt = System.currentTimeMillis() - t0
                android.util.Log.i(
                    "LumiScan",
                    "frame=${frameBitmap.width}x${frameBitmap.height} mad=${"%.2f".format(mad)} static=$isSceneStatic conf=${result.confidence} quad=${result.quad != null} ${dt}ms",
                )
                val stabilizerState = stabilizer.update(result.quad, isSceneStatic = isSceneStatic)
                val mappedQuad = stabilizerState.displayQuad?.let { quad ->
                    QuadCoordinateMapper.mapAnalysisQuadToPreview(
                        quad = quad,
                        analysisWidth = frameBitmap.width,
                        analysisHeight = frameBitmap.height,
                        previewWidth = previewWidth,
                        previewHeight = previewHeight,
                    )
                }

                _uiState.update { cur ->
                    cur.copy(previewQuad = mappedQuad)
                }
            } finally {
                isAnalyzingFrame.set(false)
                if (!frameBitmap.isRecycled) {
                    frameBitmap.recycle()
                }
            }
        }
    }

    fun setCapturing(capturing: Boolean) {
        _uiState.update { it.copy(isCapturing = capturing) }
    }

    fun onPhotoCaptured(rawFile: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, errorMessage = null) }
            try {
                val draft = processOriginalFile(rawFile)
                if (draft == null) {
                    _uiState.update { it.copy(isCapturing = false, errorMessage = "无法解析拍摄照片") }
                    return@launch
                }
                _uiState.update { state ->
                    val updated = state.draftPages + draft
                    if (state.isBatchMode) {
                        state.copy(
                            isCapturing = false,
                            draftPages = updated,
                        )
                    } else {
                        state.copy(
                            isCapturing = false,
                            draftPages = updated,
                            activeCropIndex = updated.lastIndex,
                            step = ScanStep.CROP,
                        )
                    }
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isCapturing = false, errorMessage = t.message ?: "处理照片失败")
                }
            }
        }
    }

    fun onGalleryImagesSelected(
        contentResolver: ContentResolver,
        uris: List<Uri>,
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingImport = true, errorMessage = null) }
            try {
                val imported = mutableListOf<ScannedDraftPage>()
                for (uri in uris) {
                    val file = withContext(dispatchers.io) {
                        val decoded = BitmapLoader.decodeSampledBitmap(
                            contentResolver = contentResolver,
                            uri = uri,
                            maxDimension = 4096,
                        ) ?: return@withContext null
                        val outFile = createDraftOriginalFile()
                        try {
                            BitmapLoader.saveJpeg(decoded, outFile, quality = 94)
                        } finally {
                            decoded.recycle()
                        }
                        outFile
                    } ?: continue

                    processOriginalFile(file)?.let { imported.add(it) }
                }

                if (imported.isEmpty()) {
                    _uiState.update {
                        it.copy(isProcessingImport = false, errorMessage = "未能读取所选图片")
                    }
                } else {
                    _uiState.update { state ->
                        val startIndex = state.draftPages.size
                        state.copy(
                            isProcessingImport = false,
                            draftPages = state.draftPages + imported,
                            activeCropIndex = startIndex,
                            step = ScanStep.CROP,
                        )
                    }
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isProcessingImport = false, errorMessage = t.message ?: "导入图片失败")
                }
            }
        }
    }

    fun openCropQueue() {
        _uiState.update { state ->
            if (state.draftPages.isEmpty()) state
            else state.copy(step = ScanStep.CROP, activeCropIndex = 0)
        }
    }

    fun backToCamera() {
        stabilizer.reset()
        prevThumbLuma = null
        _uiState.update { state ->
            if (!state.isBatchMode) {
                // 单页模式下从裁剪页返回相机即视为重拍，清理上一张未保存的临时草稿
                state.draftPages.forEach { page ->
                    runCatching { File(page.originalPath).delete() }
                }
                state.copy(
                    step = ScanStep.CAMERA,
                    previewQuad = null,
                    draftPages = emptyList(),
                    activeCropIndex = 0,
                )
            } else {
                state.copy(
                    step = ScanStep.CAMERA,
                    previewQuad = null,
                )
            }
        }
    }

    fun discardAllDrafts() {
        stabilizer.reset()
        val pages = _uiState.value.draftPages
        pages.forEach { page ->
            runCatching { File(page.originalPath).delete() }
        }
        _uiState.value = ScannerUiState()
    }

    fun selectCropPage(index: Int) {
        _uiState.update { state ->
            if (index in state.draftPages.indices) {
                state.copy(activeCropIndex = index)
            } else {
                state
            }
        }
    }

    fun updateActiveQuad(quad: DocumentQuad) {
        _uiState.update { state ->
            val idx = state.activeCropIndex
            val page = state.draftPages.getOrNull(idx) ?: return@update state
            val updated = state.draftPages.toMutableList()
            updated[idx] = page.copy(quad = quad.coerced())
            state.copy(draftPages = updated)
        }
    }

    fun applyAutoQuadToActivePage() {
        val page = _uiState.value.activeCropPage ?: return
        val autoQuad = page.autoDetectedQuad ?: DocumentQuad.DEFAULT_INSET
        updateActiveQuad(autoQuad)
    }

    fun applyFullQuadToActivePage() {
        updateActiveQuad(DocumentQuad.FULL)
    }

    fun rotateActivePage90() {
        _uiState.update { state ->
            val idx = state.activeCropIndex
            val page = state.draftPages.getOrNull(idx) ?: return@update state
            val updated = state.draftPages.toMutableList()
            updated[idx] = page.copy(
                rotationDegrees = (page.rotationDegrees + 90) % 360,
                quad = page.quad.rotate90Clockwise(),
                autoDetectedQuad = page.autoDetectedQuad?.rotate90Clockwise(),
            )
            state.copy(draftPages = updated)
        }
    }

    fun removeActiveCropPage() {
        _uiState.update { state ->
            val idx = state.activeCropIndex
            if (idx !in state.draftPages.indices) return@update state
            val removed = state.draftPages[idx]
            runCatching { File(removed.originalPath).delete() }
            val updated = state.draftPages.toMutableList().apply { removeAt(idx) }
            if (updated.isEmpty()) {
                state.copy(
                    draftPages = emptyList(),
                    activeCropIndex = 0,
                    step = ScanStep.CAMERA,
                    previewQuad = null,
                )
            } else {
                state.copy(
                    draftPages = updated,
                    activeCropIndex = idx.coerceAtMost(updated.lastIndex),
                )
            }
        }
    }

    fun confirmCropAndProceed() {
        val pages = _uiState.value.draftPages
        if (pages.isNotEmpty()) {
            _events.tryEmit(ScannerEvent.CompletedScan(pages))
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun createDraftOriginalFile(): File {
        draftDir.mkdirs()
        return File(draftDir, "draft_${UUID.randomUUID()}.jpg")
    }

    private suspend fun processOriginalFile(file: File): ScannedDraftPage? {
        val detectionBitmap = withContext(dispatchers.io) {
            BitmapLoader.decodeSampledBitmap(file.absolutePath, maxDimension = 1024)
        } ?: return null

        val detectedQuad = try {
            imagingEngine.detectDocument(detectionBitmap, resetTemporal = true).quad
        } finally {
            detectionBitmap.recycle()
        }

        return ScannedDraftPage(
            id = UUID.randomUUID().toString(),
            originalPath = file.absolutePath,
            quad = detectedQuad ?: DocumentQuad.DEFAULT_INSET,
            autoDetectedQuad = detectedQuad,
            rotationDegrees = 0,
        )
    }

    private fun computeFrameMad(bitmap: Bitmap): Float {
        val tiny = Bitmap.createScaledBitmap(bitmap, MOTION_GRID, MOTION_GRID, false)
        try {
            tiny.getPixels(thumbPixels, 0, MOTION_GRID, 0, 0, MOTION_GRID, MOTION_GRID)
        } finally {
            if (tiny !== bitmap && !tiny.isRecycled) tiny.recycle()
        }
        val curr = IntArray(MOTION_GRID * MOTION_GRID)
        for (i in curr.indices) {
            val c = thumbPixels[i]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            curr[i] = (r * 77 + g * 150 + b * 29) ushr 8
        }
        val prev = prevThumbLuma
        prevThumbLuma = curr
        if (prev == null) return LARGE_MOTION_MAD_THRESHOLD + 1f
        var diffSum = 0L
        for (i in curr.indices) {
            diffSum += kotlin.math.abs(curr[i] - prev[i])
        }
        return diffSum.toFloat() / curr.size.toFloat()
    }

    class Factory(
        private val imagingEngine: ImagingEngine,
        private val dispatchers: DispatcherProvider,
        private val draftDir: File,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ScannerViewModel(imagingEngine, dispatchers, draftDir) as T
        }
    }

    private companion object {
        const val MOTION_GRID = 64
        const val STATIC_MAD_THRESHOLD = 3.5f
        const val LARGE_MOTION_MAD_THRESHOLD = 12.0f
    }
}
