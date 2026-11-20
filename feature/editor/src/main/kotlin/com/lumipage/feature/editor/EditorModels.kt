package com.lumipage.feature.editor

import android.graphics.Bitmap
import com.lumipage.core.model.EnhancementFilter
import com.lumipage.core.model.PageEditState

data class EditablePageInput(
    val id: String,
    val originalPath: String,
    val editState: PageEditState,
)

data class EditedPageOutput(
    val id: String,
    val originalPath: String,
    val editState: PageEditState,
)

enum class EditorToolTab {
    FILTERS,
    ADJUST,
}

data class FilterPreviewItem(
    val filter: EnhancementFilter,
    val thumbnail: Bitmap?,
)

data class EditorUiState(
    val pages: List<EditablePageInput> = emptyList(),
    val activePageIndex: Int = 0,
    val activeTab: EditorToolTab = EditorToolTab.FILTERS,
    val isLoadingPreview: Boolean = false,
    val isSaving: Boolean = false,
    val isComparingOriginal: Boolean = false,
    val isRecropping: Boolean = false,
    val warpedPreviewBitmap: Bitmap? = null,
    val enhancedPreviewBitmap: Bitmap? = null,
    val filterThumbnails: List<FilterPreviewItem> = emptyList(),
    val errorMessage: String? = null,
) {
    val activePage: EditablePageInput?
        get() = pages.getOrNull(activePageIndex)
}
