package com.lumipage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumipage.core.data.DraftPageSaveInput
import com.lumipage.core.model.EnhancementParams
import com.lumipage.core.model.PageEditState
import com.lumipage.feature.documents.DocumentDetailRoute
import com.lumipage.feature.documents.DocumentDetailViewModel
import com.lumipage.feature.documents.DocumentsLibraryRoute
import com.lumipage.feature.documents.DocumentsLibraryViewModel
import com.lumipage.feature.editor.EditablePageInput
import com.lumipage.feature.editor.EditorRoute
import com.lumipage.feature.editor.EditorViewModel
import com.lumipage.feature.pdf.PdfExportRoute
import com.lumipage.feature.pdf.PdfExportViewModel
import com.lumipage.feature.pdf.PdfToolsRoute
import com.lumipage.feature.pdf.PdfToolsViewModel
import com.lumipage.feature.scanner.ScannedDraftPage
import com.lumipage.feature.scanner.ScannerRoute
import com.lumipage.feature.scanner.ScannerViewModel
import com.lumipage.ui.theme.LumiPageTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private sealed interface AppDestination {
    data object Library : AppDestination
    data class Scanner(
        val targetFolderId: String?,
        val appendDocumentId: String?,
    ) : AppDestination

    data class EditorNewScan(
        val drafts: List<ScannedDraftPage>,
        val targetFolderId: String?,
        val appendDocumentId: String?,
    ) : AppDestination

    data class EditorExistingDocument(
        val documentId: String,
        val initialPageIndex: Int,
    ) : AppDestination

    data class DocumentDetail(val documentId: String) : AppDestination
    data class PdfExport(val documentIds: List<String>) : AppDestination
    data object PdfTools : AppDestination
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LumiPageApplication).container
        // 触发 OpenCV 本地库预热
        container.isOpenCvReady

        setContent {
            LumiPageTheme {
                LumiPageAppRoot(container = container)
            }
        }
    }
}

@Composable
private fun LumiPageAppRoot(container: com.lumipage.di.AppContainer) {
    var backStack by remember { mutableStateOf<List<AppDestination>>(listOf(AppDestination.Library)) }
    val current = backStack.last()
    val scope = rememberCoroutineScope()

    fun navigateTo(dest: AppDestination) {
        backStack = backStack + dest
    }

    fun popBack() {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        }
    }

    when (current) {
        AppDestination.Library -> {
            val vm: DocumentsLibraryViewModel = viewModel(
                factory = DocumentsLibraryViewModel.Factory(container.documentRepository),
            )
            DocumentsLibraryRoute(
                viewModel = vm,
                onStartScan = { folderId ->
                    navigateTo(AppDestination.Scanner(targetFolderId = folderId, appendDocumentId = null))
                },
                onOpenDocument = { docId ->
                    navigateTo(AppDestination.DocumentDetail(docId))
                },
                onOpenPdfTools = {
                    navigateTo(AppDestination.PdfTools)
                },
                onMergeSelectedDocuments = { docIds ->
                    navigateTo(AppDestination.PdfExport(docIds))
                },
            )
        }

        is AppDestination.Scanner -> {
            val vm: ScannerViewModel = viewModel(
                key = "scanner_${current.appendDocumentId}_${current.targetFolderId}_${backStack.size}",
                factory = ScannerViewModel.Factory(
                    imagingEngine = container.imagingEngine,
                    dispatchers = container.dispatchers,
                    draftDir = container.fileStorageManager.draftsDir,
                ),
            )
            ScannerRoute(
                viewModel = vm,
                onNavigateBack = ::popBack,
                onScanCompleted = { pages ->
                    backStack = backStack.dropLast(1) + AppDestination.EditorNewScan(
                        drafts = pages,
                        targetFolderId = current.targetFolderId,
                        appendDocumentId = current.appendDocumentId,
                    )
                },
            )
        }

        is AppDestination.EditorNewScan -> {
            val inputs = remember(current.drafts) {
                current.drafts.map { draft ->
                    EditablePageInput(
                        id = draft.id,
                        originalPath = draft.originalPath,
                        editState = PageEditState(
                            quad = draft.quad,
                            rotationDegrees = draft.rotationDegrees,
                            enhancement = EnhancementParams.DEFAULT_AUTO,
                        ),
                    )
                }
            }
            val vm: EditorViewModel = viewModel(
                key = "editor_new_${inputs.firstOrNull()?.id}",
                factory = EditorViewModel.Factory(
                    initialPages = inputs,
                    imagingEngine = container.imagingEngine,
                    dispatchers = container.dispatchers,
                ),
            )
            EditorRoute(
                viewModel = vm,
                onNavigateBack = ::popBack,
                onSaveCompleted = { outputs ->
                    scope.launch {
                        val saveInputs = outputs.map {
                            DraftPageSaveInput(
                                pageId = it.id,
                                sourcePath = it.originalPath,
                                editState = it.editState,
                            )
                        }
                        val appendId = current.appendDocumentId
                        if (appendId != null) {
                            container.documentRepository.appendPagesToDocument(appendId, saveInputs)
                            backStack = backStack.dropLast(1)
                        } else {
                            val created = container.documentRepository.createDocumentFromDrafts(
                                drafts = saveInputs,
                                folderId = current.targetFolderId,
                            )
                            backStack = listOf(AppDestination.Library, AppDestination.DocumentDetail(created.id))
                        }
                    }
                },
            )
        }

        is AppDestination.EditorExistingDocument -> {
            var loadedInputs by remember(current.documentId) { mutableStateOf<List<EditablePageInput>?>(null) }
            LaunchedEffect(current.documentId) {
                val pair = container.documentRepository.observeDocumentWithPages(current.documentId).first()
                loadedInputs = pair?.second?.map { page ->
                    EditablePageInput(
                        id = page.id,
                        originalPath = page.originalPath,
                        editState = page.editState,
                    )
                } ?: emptyList()
            }

            val inputs = loadedInputs
            if (inputs == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (inputs.isEmpty()) {
                LaunchedEffect(Unit) { popBack() }
            } else {
                val vm: EditorViewModel = viewModel(
                    key = "editor_existing_${current.documentId}_${current.initialPageIndex}",
                    factory = EditorViewModel.Factory(
                        initialPages = inputs,
                        imagingEngine = container.imagingEngine,
                        dispatchers = container.dispatchers,
                    ),
                )
                LaunchedEffect(current.initialPageIndex) {
                    vm.selectPage(current.initialPageIndex)
                }
                EditorRoute(
                    viewModel = vm,
                    onNavigateBack = ::popBack,
                    onSaveCompleted = { outputs ->
                        scope.launch {
                            outputs.forEach { out ->
                                container.documentRepository.updatePageEditState(out.id, out.editState)
                            }
                            popBack()
                        }
                    },
                )
            }
        }

        is AppDestination.DocumentDetail -> {
            val vm: DocumentDetailViewModel = viewModel(
                key = "detail_${current.documentId}",
                factory = DocumentDetailViewModel.Factory(
                    documentId = current.documentId,
                    repository = container.documentRepository,
                ),
            )
            DocumentDetailRoute(
                viewModel = vm,
                onNavigateBack = ::popBack,
                onEditPage = { docId, pageIndex ->
                    navigateTo(AppDestination.EditorExistingDocument(docId, pageIndex))
                },
                onAppendScan = { docId ->
                    navigateTo(AppDestination.Scanner(targetFolderId = null, appendDocumentId = docId))
                },
                onExportPdf = { docId ->
                    navigateTo(AppDestination.PdfExport(listOf(docId)))
                },
            )
        }

        is AppDestination.PdfExport -> {
            val vm: PdfExportViewModel = viewModel(
                key = "pdf_export_${current.documentIds.joinToString("_")}",
                factory = PdfExportViewModel.Factory(
                    documentIds = current.documentIds,
                    repository = container.documentRepository,
                    storage = container.fileStorageManager,
                    pdfService = container.pdfService,
                ),
            )
            PdfExportRoute(
                viewModel = vm,
                fileProviderAuthority = container.fileProviderAuthority,
                onNavigateBack = ::popBack,
            )
        }

        AppDestination.PdfTools -> {
            val vm: PdfToolsViewModel = viewModel(
                factory = PdfToolsViewModel.Factory(
                    storage = container.fileStorageManager,
                    pdfService = container.pdfService,
                ),
            )
            PdfToolsRoute(
                viewModel = vm,
                fileProviderAuthority = container.fileProviderAuthority,
                onNavigateBack = ::popBack,
            )
        }
    }
}
