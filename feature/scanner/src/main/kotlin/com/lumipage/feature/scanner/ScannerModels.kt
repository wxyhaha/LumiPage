package com.lumipage.feature.scanner

import com.lumipage.core.model.DocumentQuad

enum class FlashMode {
    OFF,
    AUTO,
    TORCH,
}

enum class ScanStep {
    CAMERA,
    CROP,
}

data class ScannedDraftPage(
    val id: String,
    val originalPath: String,
    val quad: DocumentQuad,
    val autoDetectedQuad: DocumentQuad?,
    val rotationDegrees: Int = 0,
)

data class ScannerUiState(
    val step: ScanStep = ScanStep.CAMERA,
    val flashMode: FlashMode = FlashMode.OFF,
    val showGrid: Boolean = true,
    val isBatchMode: Boolean = false,
    val isCapturing: Boolean = false,
    val isProcessingImport: Boolean = false,
    val previewQuad: DocumentQuad? = null,
    val draftPages: List<ScannedDraftPage> = emptyList(),
    val activeCropIndex: Int = 0,
    val errorMessage: String? = null,
) {
    val activeCropPage: ScannedDraftPage?
        get() = draftPages.getOrNull(activeCropIndex)
}
