package com.lumipage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import java.util.UUID

private sealed interface AppDestination {
    val sessionId: String

    data class Library(
        override val sessionId: String = "library_root",
    ) : AppDestination

    data class Scanner(
        val targetFolderId: String?,
        val appendDocumentId: String?,
        override val sessionId: String = UUID.randomUUID().toString(),
    ) : AppDestination

    data class EditorNewScan(
        val drafts: List<ScannedDraftPage>,
        val targetFolderId: String?,
        val appendDocumentId: String?,
        override val sessionId: String = UUID.randomUUID().toString(),
    ) : AppDestination

    data class EditorExistingDocument(
        val documentId: String,
        val initialPageIndex: Int,
        override val sessionId: String = UUID.randomUUID().toString(),
    ) : AppDestination

    data class DocumentDetail(
        val documentId: String,
        override val sessionId: String = UUID.randomUUID().toString(),
    ) : AppDestination

    data class PdfExport(
        val documentIds: List<String>,
        override val sessionId: String = UUID.randomUUID().toString(),
    ) : AppDestination

    data class PdfTools(
        override val sessionId: String = UUID.randomUUID().toString(),
    ) : AppDestination
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LumiPageApplication).container
        // 触发 OpenCV 本地库预热与遗留草稿清理
        container.isOpenCvReady
        container.fileStorageManager

        setContent {
            LumiPageTheme {
                LumiPageAppRoot(container = container)
            }
        }
    }
}

@Composable
private fun LumiPageAppRoot(container: com.lumipage.di.AppContainer) {
    var backStack by remember { mutableStateOf<List<AppDestination>>(listOf(AppDestination.Library())) }
    val storeMap = remember { mutableMapOf<String, ViewModelStore>() }
    val current = backStack.last()
    val scope = rememberCoroutineScope()

    // 当路由节点从 backStack 移除时，立即清理对应的 ViewModelStore，防止旧状态残留
    LaunchedEffect(backStack) {
        val activeIds = backStack.map { it.sessionId }.toSet()
        val removedIds = storeMap.keys.filter { it !in activeIds }
        removedIds.forEach { id ->
            storeMap.remove(id)?.clear()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            storeMap.values.forEach { it.clear() }
            storeMap.clear()
        }
    }

    fun navigateTo(dest: AppDestination) {
        backStack = backStack + dest
    }

    fun popBack() {
        if (backStack.size > 1) {
            val top = backStack.last()
            if (top is AppDestination.EditorNewScan) {
                container.fileStorageManager.deleteDraftFiles(top.drafts.map { it.originalPath })
            }
            val poppedId = top.sessionId
            backStack = backStack.dropLast(1)
            storeMap.remove(poppedId)?.clear()
        }
    }

    BackHandler(enabled = backStack.size > 1) {
        popBack()
    }

    val currentStoreOwner = remember(current.sessionId) {
        val store = storeMap.getOrPut(current.sessionId) { ViewModelStore() }
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    key(current.sessionId) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides currentStoreOwner) {
            when (current) {
                is AppDestination.Library -> {
                    val vm: DocumentsLibraryViewModel = viewModel(
                        key = current.sessionId,
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
                            navigateTo(AppDestination.PdfTools())
                        },
                        onMergeSelectedDocuments = { docIds ->
                            navigateTo(AppDestination.PdfExport(docIds))
                        },
                    )
                }

                is AppDestination.Scanner -> {
                    val vm: ScannerViewModel = viewModel(
                        key = current.sessionId,
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
                            val oldId = current.sessionId
                            backStack = backStack.dropLast(1) + AppDestination.EditorNewScan(
                                drafts = pages,
                                targetFolderId = current.targetFolderId,
                                appendDocumentId = current.appendDocumentId,
                            )
                            storeMap.remove(oldId)?.clear()
                        },
                    )
                }

                is AppDestination.EditorNewScan -> {
                    val inputs = remember(current.sessionId) {
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
                        key = current.sessionId,
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
                                val oldId = current.sessionId
                                val appendId = current.appendDocumentId
                                if (appendId != null) {
                                    container.documentRepository.appendPagesToDocument(appendId, saveInputs)
                                    backStack = backStack.dropLast(1)
                                } else {
                                    val created = container.documentRepository.createDocumentFromDrafts(
                                        drafts = saveInputs,
                                        folderId = current.targetFolderId,
                                    )
                                    backStack = listOf(
                                        backStack.first(),
                                        AppDestination.DocumentDetail(created.id),
                                    )
                                }
                                storeMap.remove(oldId)?.clear()
                            }
                        },
                    )
                }

                is AppDestination.EditorExistingDocument -> {
                    var loadedInputs by remember(current.sessionId) {
                        mutableStateOf<List<EditablePageInput>?>(null)
                    }
                    LaunchedEffect(current.sessionId) {
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
                            key = current.sessionId,
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
                        key = current.sessionId,
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
                        key = current.sessionId,
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

                is AppDestination.PdfTools -> {
                    val vm: PdfToolsViewModel = viewModel(
                        key = current.sessionId,
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
    }
}
