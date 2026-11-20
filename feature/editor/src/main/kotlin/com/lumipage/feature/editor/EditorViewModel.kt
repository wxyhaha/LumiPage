package com.lumipage.feature.editor

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumipage.core.common.BitmapLoader
import com.lumipage.core.common.DispatcherProvider
import com.lumipage.core.imaging.ImagingEngine
import com.lumipage.core.model.DocumentQuad
import com.lumipage.core.model.EnhancementFilter
import com.lumipage.core.model.EnhancementParams
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class EditorViewModel(
    private val initialPages: List<EditablePageInput>,
    private val imagingEngine: ImagingEngine,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState(pages = initialPages))
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    private var warpJob: Job? = null
    private var enhanceJob: Job? = null
    private val warpedCache = mutableMapOf<String, CacheEntry>()

    private data class CacheEntry(
        val quad: DocumentQuad,
        val rotation: Int,
        val bitmap: Bitmap,
    )

    sealed interface EditorEvent {
        data class SaveCompleted(val outputs: List<EditedPageOutput>) : EditorEvent
    }

    init {
        if (initialPages.isNotEmpty()) {
            loadActivePagePreview()
        }
    }

    fun selectPage(index: Int) {
        if (index !in _uiState.value.pages.indices || index == _uiState.value.activePageIndex) return
        _uiState.update { it.copy(activePageIndex = index, isRecropping = false) }
        loadActivePagePreview()
    }

    fun selectTab(tab: EditorToolTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setComparingOriginal(comparing: Boolean) {
        _uiState.update { it.copy(isComparingOriginal = comparing) }
    }

    fun selectFilter(filter: EnhancementFilter) {
        updateActiveEnhancement { it.copy(filter = filter) }
    }

    fun updateBrightness(value: Float) {
        updateActiveEnhancement { it.copy(brightness = value) }
    }

    fun updateContrast(value: Float) {
        updateActiveEnhancement { it.copy(contrast = value) }
    }

    fun updateSharpness(value: Float) {
        updateActiveEnhancement { it.copy(sharpness = value) }
    }

    fun resetAdjustments() {
        updateActiveEnhancement { params ->
            params.copy(brightness = 0f, contrast = 1f, sharpness = 0.25f)
        }
    }

    fun rotateActivePage90() {
        val page = _uiState.value.activePage ?: return
        val idx = _uiState.value.activePageIndex
        val nextRotation = (page.editState.normalizedRotation + 90) % 360
        val updated = _uiState.value.pages.toMutableList()
        updated[idx] = page.copy(editState = page.editState.copy(rotationDegrees = nextRotation))
        _uiState.update { it.copy(pages = updated) }
        loadActivePagePreview()
    }

    fun applyEnhancementToAllPages() {
        val currentEnhancement = _uiState.value.activePage?.editState?.enhancement ?: return
        _uiState.update { state ->
            val updated = state.pages.map { page ->
                page.copy(editState = page.editState.copy(enhancement = currentEnhancement))
            }
            state.copy(pages = updated)
        }
    }

    fun startRecrop() {
        _uiState.update { it.copy(isRecropping = true) }
    }

    fun cancelRecrop() {
        _uiState.update { it.copy(isRecropping = false) }
    }

    fun confirmRecrop(newQuad: DocumentQuad) {
        val page = _uiState.value.activePage ?: return
        val idx = _uiState.value.activePageIndex
        val updated = _uiState.value.pages.toMutableList()
        updated[idx] = page.copy(editState = page.editState.copy(quad = newQuad.coerced()))
        _uiState.update { it.copy(pages = updated, isRecropping = false) }
        loadActivePagePreview()
    }

    fun saveAndFinish() {
        val pages = _uiState.value.pages
        if (pages.isEmpty() || _uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        val outputs = pages.map { page ->
            EditedPageOutput(
                id = page.id,
                originalPath = page.originalPath,
                editState = page.editState,
            )
        }
        _events.tryEmit(EditorEvent.SaveCompleted(outputs))
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun updateActiveEnhancement(transform: (EnhancementParams) -> EnhancementParams) {
        val state = _uiState.value
        val idx = state.activePageIndex
        val page = state.pages.getOrNull(idx) ?: return
        val nextEnhancement = transform(page.editState.enhancement).clamped()
        val updated = state.pages.toMutableList()
        updated[idx] = page.copy(editState = page.editState.copy(enhancement = nextEnhancement))
        _uiState.update { it.copy(pages = updated) }
        scheduleEnhancePreview()
    }

    private fun loadActivePagePreview() {
        val page = _uiState.value.activePage ?: return
        warpJob?.cancel()
        warpJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingPreview = true, errorMessage = null) }
            try {
                val cached = warpedCache[page.id]
                val warped = if (
                    cached != null &&
                    cached.quad == page.editState.quad &&
                    cached.rotation == page.editState.normalizedRotation &&
                    !cached.bitmap.isRecycled
                ) {
                    cached.bitmap
                } else {
                    val original = withContext(dispatchers.io) {
                        BitmapLoader.decodeSampledBitmap(page.originalPath, maxDimension = 2048)
                    } ?: run {
                        _uiState.update { it.copy(isLoadingPreview = false, errorMessage = "无法加载原始图片") }
                        return@launch
                    }
                    try {
                        imagingEngine.warpOnly(
                            source = original,
                            quad = page.editState.quad,
                            rotationDegrees = page.editState.normalizedRotation,
                            maxOutputDimension = 1400,
                        ).also { bmp ->
                            warpedCache[page.id] = CacheEntry(
                                quad = page.editState.quad,
                                rotation = page.editState.normalizedRotation,
                                bitmap = bmp,
                            )
                        }
                    } finally {
                        original.recycle()
                    }
                }

                val enhanced = imagingEngine.enhanceBitmap(warped, page.editState.enhancement)
                val thumbs = buildFilterThumbnails(warped)
                _uiState.update {
                    it.copy(
                        isLoadingPreview = false,
                        warpedPreviewBitmap = warped,
                        enhancedPreviewBitmap = enhanced,
                        filterThumbnails = thumbs,
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isLoadingPreview = false, errorMessage = t.message ?: "预览生成失败")
                }
            }
        }
    }

    private fun scheduleEnhancePreview() {
        val page = _uiState.value.activePage ?: return
        val warped = _uiState.value.warpedPreviewBitmap ?: return
        enhanceJob?.cancel()
        enhanceJob = viewModelScope.launch {
            delay(40)
            val enhanced = imagingEngine.enhanceBitmap(warped, page.editState.enhancement)
            _uiState.update { it.copy(enhancedPreviewBitmap = enhanced) }
        }
    }

    private suspend fun buildFilterThumbnails(warped: Bitmap): List<FilterPreviewItem> {
        val maxSide = max(warped.width, warped.height)
        val thumbScale = if (maxSide > 160) 160f / maxSide.toFloat() else 1f
        val thumbBase = if (thumbScale < 1f) {
            Bitmap.createScaledBitmap(
                warped,
                max(1, (warped.width * thumbScale).roundToInt()),
                max(1, (warped.height * thumbScale).roundToInt()),
                true,
            )
        } else {
            warped
        }

        return try {
            EnhancementFilter.entries.map { filter ->
                val bmp = imagingEngine.enhanceBitmap(
                    thumbBase,
                    EnhancementParams(filter = filter),
                )
                FilterPreviewItem(filter = filter, thumbnail = bmp)
            }
        } finally {
            if (thumbBase !== warped && !thumbBase.isRecycled) {
                thumbBase.recycle()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        warpedCache.values.forEach { entry ->
            if (!entry.bitmap.isRecycled) entry.bitmap.recycle()
        }
        warpedCache.clear()
    }

    class Factory(
        private val initialPages: List<EditablePageInput>,
        private val imagingEngine: ImagingEngine,
        private val dispatchers: DispatcherProvider,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EditorViewModel(initialPages, imagingEngine, dispatchers) as T
        }
    }
}
