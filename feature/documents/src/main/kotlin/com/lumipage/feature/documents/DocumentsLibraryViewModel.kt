package com.lumipage.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumipage.core.data.DocumentRepository
import com.lumipage.core.model.Document
import com.lumipage.core.model.Folder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DocumentSortOrder(val label: String) {
    UPDATED_DESC("最近修改"),
    CREATED_DESC("最新创建"),
    TITLE_ASC("名称 A-Z"),
}

enum class LibraryFilterMode {
    ALL,
    FAVORITES,
    FOLDER,
}

data class LibraryUiState(
    val documents: List<Document> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val searchQuery: String = "",
    val sortOrder: DocumentSortOrder = DocumentSortOrder.UPDATED_DESC,
    val filterMode: LibraryFilterMode = LibraryFilterMode.ALL,
    val selectedFolderId: String? = null,
    val isGridView: Boolean = true,
    val selectedDocumentIds: Set<String> = emptySet(),
) {
    val isSelectionMode: Boolean get() = selectedDocumentIds.isNotEmpty()
}

private data class LibraryFilterParams(
    val searchQuery: String,
    val sortOrder: DocumentSortOrder,
    val filterMode: LibraryFilterMode,
    val selectedFolderId: String?,
    val isGridView: Boolean,
    val selectedDocumentIds: Set<String>,
)

class DocumentsLibraryViewModel(
    private val repository: DocumentRepository,
) : ViewModel() {

    private val filterParams = MutableStateFlow(
        LibraryFilterParams(
            searchQuery = "",
            sortOrder = DocumentSortOrder.UPDATED_DESC,
            filterMode = LibraryFilterMode.ALL,
            selectedFolderId = null,
            isGridView = true,
            selectedDocumentIds = emptySet(),
        ),
    )

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.observeDocuments(),
        repository.observeFolders(),
        filterParams,
    ) { docs, folders, params ->
        val filtered = docs.filter { doc ->
            val matchesQuery = params.searchQuery.isBlank() ||
                doc.title.contains(params.searchQuery.trim(), ignoreCase = true)
            val matchesCategory = when (params.filterMode) {
                LibraryFilterMode.ALL -> true
                LibraryFilterMode.FAVORITES -> doc.isFavorite
                LibraryFilterMode.FOLDER -> doc.folderId == params.selectedFolderId
            }
            matchesQuery && matchesCategory
        }.let { list ->
            when (params.sortOrder) {
                DocumentSortOrder.UPDATED_DESC -> list.sortedByDescending { it.updatedAt }
                DocumentSortOrder.CREATED_DESC -> list.sortedByDescending { it.createdAt }
                DocumentSortOrder.TITLE_ASC -> list.sortedBy { it.title.lowercase() }
            }
        }

        LibraryUiState(
            documents = filtered,
            folders = folders,
            searchQuery = params.searchQuery,
            sortOrder = params.sortOrder,
            filterMode = params.filterMode,
            selectedFolderId = params.selectedFolderId,
            isGridView = params.isGridView,
            selectedDocumentIds = params.selectedDocumentIds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState(),
    )

    fun updateSearchQuery(query: String) {
        filterParams.update { it.copy(searchQuery = query) }
    }

    fun selectFilterAll() {
        filterParams.update {
            it.copy(filterMode = LibraryFilterMode.ALL, selectedFolderId = null)
        }
    }

    fun selectFilterFavorites() {
        filterParams.update {
            it.copy(filterMode = LibraryFilterMode.FAVORITES, selectedFolderId = null)
        }
    }

    fun selectFolder(folderId: String) {
        filterParams.update {
            it.copy(filterMode = LibraryFilterMode.FOLDER, selectedFolderId = folderId)
        }
    }

    fun setSortOrder(order: DocumentSortOrder) {
        filterParams.update { it.copy(sortOrder = order) }
    }

    fun toggleViewMode() {
        filterParams.update { it.copy(isGridView = !it.isGridView) }
    }

    fun toggleDocumentSelection(documentId: String) {
        filterParams.update { state ->
            val next = if (documentId in state.selectedDocumentIds) {
                state.selectedDocumentIds - documentId
            } else {
                state.selectedDocumentIds + documentId
            }
            state.copy(selectedDocumentIds = next)
        }
    }

    fun clearSelection() {
        filterParams.update { it.copy(selectedDocumentIds = emptySet()) }
    }

    fun toggleFavorite(document: Document) {
        viewModelScope.launch {
            repository.toggleFavorite(document.id, !document.isFavorite)
        }
    }

    fun renameDocument(documentId: String, newTitle: String) {
        viewModelScope.launch {
            repository.renameDocument(documentId, newTitle)
        }
    }

    fun deleteSelectedDocuments() {
        val ids = filterParams.value.selectedDocumentIds.toList()
        if (ids.isEmpty()) return
        filterParams.update { it.copy(selectedDocumentIds = emptySet()) }
        viewModelScope.launch {
            repository.deleteDocuments(ids)
        }
    }

    fun deleteSingleDocument(documentId: String) {
        viewModelScope.launch {
            repository.deleteDocuments(listOf(documentId))
        }
    }

    fun moveSelectedToFolder(folderId: String?) {
        val ids = filterParams.value.selectedDocumentIds.toList()
        if (ids.isEmpty()) return
        filterParams.update { it.copy(selectedDocumentIds = emptySet()) }
        viewModelScope.launch {
            repository.moveDocumentsToFolder(ids, folderId)
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createFolder(name)
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            repository.deleteFolder(folderId)
            if (filterParams.value.selectedFolderId == folderId) {
                selectFilterAll()
            }
        }
    }

    class Factory(
        private val repository: DocumentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DocumentsLibraryViewModel(repository) as T
        }
    }
}
