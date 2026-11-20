package com.lumipage.feature.pdf

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumipage.core.data.DocumentRepository
import com.lumipage.core.data.FileStorageManager
import com.lumipage.core.pdf.PdfCompressionPreset
import com.lumipage.core.pdf.PdfGenerationConfig
import com.lumipage.core.pdf.PdfPageSizePreset
import com.lumipage.core.pdf.PdfService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class PdfExportUiState(
    val title: String = "LumiPage",
    val pageSize: PdfPageSizePreset = PdfPageSizePreset.A4,
    val compression: PdfCompressionPreset = PdfCompressionPreset.BALANCED,
    val isGenerating: Boolean = false,
    val generatedPdfFile: File? = null,
    val pageCount: Int = 0,
    val currentPreviewPageIndex: Int = 0,
    val previewBitmap: Bitmap? = null,
    val fileSizeBytes: Long = 0L,
    val statusMessage: String? = null,
)

class PdfExportViewModel(
    private val documentIds: List<String>,
    private val repository: DocumentRepository,
    private val storage: FileStorageManager,
    private val pdfService: PdfService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfExportUiState())
    val uiState: StateFlow<PdfExportUiState> = _uiState.asStateFlow()

    private var sourceImagePaths: List<String> = emptyList()

    init {
        loadSourceDocuments()
    }

    private fun loadSourceDocuments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true) }
            val paths = mutableListOf<String>()
            var resolvedTitle = "LumiPage_Merged"

            for ((idx, docId) in documentIds.withIndex()) {
                val pair = repository.observeDocumentWithPages(docId).first() ?: continue
                if (idx == 0 && documentIds.size == 1) {
                    resolvedTitle = pair.first.title
                }
                paths.addAll(pair.second.map { it.processedPath })
            }
            sourceImagePaths = paths
            _uiState.update { it.copy(title = resolvedTitle) }
            if (paths.isNotEmpty()) {
                regeneratePdf()
            } else {
                _uiState.update { it.copy(isGenerating = false, statusMessage = "没有可导出的页面") }
            }
        }
    }

    fun setPageSize(preset: PdfPageSizePreset) {
        if (_uiState.value.pageSize == preset) return
        _uiState.update { it.copy(pageSize = preset) }
        regeneratePdf()
    }

    fun setCompression(preset: PdfCompressionPreset) {
        if (_uiState.value.compression == preset) return
        _uiState.update { it.copy(compression = preset) }
        regeneratePdf()
    }

    fun selectPreviewPage(index: Int) {
        val pdfFile = _uiState.value.generatedPdfFile ?: return
        val count = _uiState.value.pageCount
        if (index !in 0 until count) return
        viewModelScope.launch {
            val bmp = pdfService.renderPdfPage(pdfFile, index, maxWidth = 1080)
            _uiState.update {
                it.copy(
                    currentPreviewPageIndex = index,
                    previewBitmap = bmp,
                )
            }
        }
    }

    fun saveToDownloads() {
        val file = _uiState.value.generatedPdfFile ?: return
        viewModelScope.launch {
            val uri = pdfService.savePdfToDownloads(file, _uiState.value.title)
            _uiState.update {
                it.copy(
                    statusMessage = if (uri != null) {
                        "已保存至 Download/LumiPage/${_uiState.value.title}.pdf"
                    } else {
                        "保存失败，请检查存储空间"
                    },
                )
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun regeneratePdf() {
        if (sourceImagePaths.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true) }
            try {
                val safeName = _uiState.value.title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]"), "_")
                val outFile = File(storage.exportsDir, "${safeName}_${System.currentTimeMillis()}.pdf")
                pdfService.generatePdfFromImages(
                    imagePaths = sourceImagePaths,
                    outputFile = outFile,
                    config = PdfGenerationConfig(
                        pageSize = _uiState.value.pageSize,
                        compression = _uiState.value.compression,
                    ),
                )
                val count = pdfService.getPdfPageCount(outFile)
                val firstPreview = if (count > 0) pdfService.renderPdfPage(outFile, 0, maxWidth = 1080) else null
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        generatedPdfFile = outFile,
                        pageCount = count,
                        currentPreviewPageIndex = 0,
                        previewBitmap = firstPreview,
                        fileSizeBytes = outFile.length(),
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isGenerating = false, statusMessage = t.message ?: "PDF 生成失败")
                }
            }
        }
    }

    class Factory(
        private val documentIds: List<String>,
        private val repository: DocumentRepository,
        private val storage: FileStorageManager,
        private val pdfService: PdfService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PdfExportViewModel(documentIds, repository, storage, pdfService) as T
        }
    }
}
