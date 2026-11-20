package com.lumipage.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumipage.core.data.DocumentRepository
import com.lumipage.core.model.Document
import com.lumipage.core.model.DocumentPage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DocumentDetailUiState(
    val document: Document? = null,
    val pages: List<DocumentPage> = emptyList(),
    val isLoading: Boolean = true,
)

class DocumentDetailViewModel(
    private val documentId: String,
    private val repository: DocumentRepository,
) : ViewModel() {

    val uiState: StateFlow<DocumentDetailUiState> = repository.observeDocumentWithPages(documentId)
        .map { pair ->
            if (pair == null) {
                DocumentDetailUiState(document = null, pages = emptyList(), isLoading = false)
            } else {
                DocumentDetailUiState(document = pair.first, pages = pair.second, isLoading = false)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DocumentDetailUiState(isLoading = true),
        )

    fun renameDocument(newTitle: String) {
        viewModelScope.launch {
            repository.renameDocument(documentId, newTitle)
        }
    }

    fun toggleFavorite() {
        val doc = uiState.value.document ?: return
        viewModelScope.launch {
            repository.toggleFavorite(documentId, !doc.isFavorite)
        }
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        val pages = uiState.value.pages.toMutableList()
        if (fromIndex !in pages.indices || toIndex !in pages.indices || fromIndex == toIndex) return
        val item = pages.removeAt(fromIndex)
        pages.add(toIndex, item)
        viewModelScope.launch {
            repository.reorderPages(documentId, pages.map { it.id })
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch {
            repository.deletePages(documentId, listOf(pageId))
        }
    }

    class Factory(
        private val documentId: String,
        private val repository: DocumentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DocumentDetailViewModel(documentId, repository) as T
        }
    }
}
