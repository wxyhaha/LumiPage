package com.lumipage.feature.pdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumipage.core.data.FileStorageManager
import com.lumipage.core.pdf.PdfCompressionPreset
import com.lumipage.core.pdf.PdfPageEditSpec
import com.lumipage.core.pdf.PdfService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ManagedPdfPageItem(
    val originalIndex: Int,
    val extraRotationDegrees: Int,
    val thumbnail: Bitmap?,
)

data class PdfToolsUiState(
    val isBusy: Boolean = false,
    val activePdfFile: File? = null,
    val managedPages: List<ManagedPdfPageItem> = emptyList(),
    val splitRangeInput: String = "1",
    val compressionPreset: PdfCompressionPreset = PdfCompressionPreset.BALANCED,
    val resultPdfFile: File? = null,
    val resultSummary: String? = null,
    val statusMessage: String? = null,
)

class PdfToolsViewModel(
    private val storage: FileStorageManager,
    private val pdfService: PdfService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfToolsUiState())
    val uiState: StateFlow<PdfToolsUiState> = _uiState.asStateFlow()

    fun loadSinglePdfForManage(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, resultPdfFile = null, resultSummary = null) }
            try {
                val target = File(storage.exportsDir, "loaded_${System.currentTimeMillis()}.pdf")
                val file = pdfService.copyUriToTempPdf(uri, target) ?: run {
                    _uiState.update { it.copy(isBusy = false, statusMessage = "无法读取所选 PDF") }
                    return@launch
                }
                val count = pdfService.getPdfPageCount(file)
                val items = (0 until count).map { idx ->
                    val thumb = pdfService.renderPdfPage(file, idx, maxWidth = 320)
                    ManagedPdfPageItem(originalIndex = idx, extraRotationDegrees = 0, thumbnail = thumb)
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        activePdfFile = file,
                        managedPages = items,
                        splitRangeInput = if (count > 0) "1-$count" else "1",
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, statusMessage = t.message ?: "加载 PDF 失败") }
            }
        }
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        val list = _uiState.value.managedPages.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _uiState.update { it.copy(managedPages = list) }
    }

    fun rotatePage(index: Int) {
        val list = _uiState.value.managedPages.toMutableList()
        val item = list.getOrNull(index) ?: return
        list[index] = item.copy(extraRotationDegrees = (item.extraRotationDegrees + 90) % 360)
        _uiState.update { it.copy(managedPages = list) }
    }

    fun deletePage(index: Int) {
        val list = _uiState.value.managedPages.toMutableList()
        if (list.size <= 1 || index !in list.indices) {
            _uiState.update { it.copy(statusMessage = "至少保留 1 页") }
            return
        }
        list.removeAt(index)
        _uiState.update { it.copy(managedPages = list) }
    }

    fun applyPageEdits() {
        val src = _uiState.value.activePdfFile ?: return
        val pages = _uiState.value.managedPages
        if (pages.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val out = File(storage.exportsDir, "edited_${System.currentTimeMillis()}.pdf")
                pdfService.editPdfPages(
                    sourceFile = src,
                    outputFile = out,
                    pageSpecs = pages.map { PdfPageEditSpec(it.originalIndex, it.extraRotationDegrees) },
                )
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        resultPdfFile = out,
                        resultSummary = "已重新生成 PDF（共 ${pages.size} 页，${formatBytes(out.length())}）",
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, statusMessage = t.message ?: "保存修改失败") }
            }
        }
    }

    fun updateSplitRangeInput(input: String) {
        _uiState.update { it.copy(splitRangeInput = input) }
    }

    fun splitActivePdf() {
        val src = _uiState.value.activePdfFile ?: return
        val maxPages = _uiState.value.managedPages.size
        val selectedPages = PdfService.parsePageRanges(_uiState.value.splitRangeInput, maxPages)
        if (selectedPages.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "请输入有效页码范围（例如 1-2, 4）") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val out = File(storage.exportsDir, "split_${System.currentTimeMillis()}.pdf")
                pdfService.splitPdf(src, out, selectedPages)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        resultPdfFile = out,
                        resultSummary = "拆分完成：提取 ${selectedPages.size} 页（${formatBytes(out.length())}）",
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, statusMessage = t.message ?: "拆分失败") }
            }
        }
    }

    fun setCompressionPreset(preset: PdfCompressionPreset) {
        _uiState.update { it.copy(compressionPreset = preset) }
    }

    fun compressActivePdf() {
        val src = _uiState.value.activePdfFile ?: return
        val preset = _uiState.value.compressionPreset
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val out = File(storage.exportsDir, "compressed_${System.currentTimeMillis()}.pdf")
                val result = pdfService.compressPdf(src, out, preset)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        resultPdfFile = out,
                        resultSummary = "压缩完成：${formatBytes(result.originalBytes)} → ${formatBytes(result.compressedBytes)}",
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, statusMessage = t.message ?: "压缩失败") }
            }
        }
    }

    fun mergeExternalPdfs(contentResolver: ContentResolver, uris: List<Uri>) {
        if (uris.size < 2) {
            _uiState.update { it.copy(statusMessage = "请至少选择 2 个 PDF 文件进行合并") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val out = File(storage.exportsDir, "merged_${System.currentTimeMillis()}.pdf")
                pdfService.mergePdfUris(contentResolver, uris, out)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        resultPdfFile = out,
                        resultSummary = "合并完成（${uris.size} 个文件，${formatBytes(out.length())}）",
                    )
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(isBusy = false, statusMessage = t.message ?: "合并失败") }
            }
        }
    }

    fun saveResultToDownloads() {
        val file = _uiState.value.resultPdfFile ?: return
        viewModelScope.launch {
            val uri = pdfService.savePdfToDownloads(file, file.nameWithoutExtension)
            _uiState.update {
                it.copy(
                    statusMessage = if (uri != null) "已保存至 Download/LumiPage/" else "保存失败",
                )
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024.0) String.format("%.0f KB", kb) else String.format("%.2f MB", kb / 1024.0)
    }

    class Factory(
        private val storage: FileStorageManager,
        private val pdfService: PdfService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PdfToolsViewModel(storage, pdfService) as T
        }
    }
}
