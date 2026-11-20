package com.lumipage.core.data

import androidx.room.withTransaction
import com.lumipage.core.common.DispatcherProvider
import com.lumipage.core.model.Document
import com.lumipage.core.model.DocumentPage
import com.lumipage.core.model.EnhancementFilter
import com.lumipage.core.model.EnhancementParams
import com.lumipage.core.model.Folder
import com.lumipage.core.model.PageEditState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DraftPageSaveInput(
    val pageId: String,
    val sourcePath: String,
    val editState: PageEditState,
)

class DocumentRepository(
    private val database: LumiPageDatabase,
    private val storage: FileStorageManager,
    private val dispatchers: DispatcherProvider,
) {
    private val dao = database.dao()

    fun observeFolders(): Flow<List<Folder>> =
        dao.observeFoldersWithCounts().map { rows ->
            rows.map { row ->
                Folder(
                    id = row.id,
                    name = row.name,
                    parentId = row.parentId,
                    documentCount = row.documentCount,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                )
            }
        }

    fun observeDocuments(): Flow<List<Document>> =
        dao.observeAllDocumentsWithStats().map { rows ->
            rows.map { row ->
                Document(
                    id = row.id,
                    title = row.title,
                    folderId = row.folderId,
                    isFavorite = row.isFavorite,
                    coverPageId = row.coverPageId,
                    coverThumbnailPath = row.coverThumbnailPath,
                    pageCount = row.pageCount,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                )
            }
        }

    fun observeDocumentWithPages(documentId: String): Flow<Pair<Document, List<DocumentPage>>?> =
        combine(
            dao.observeDocumentById(documentId),
            dao.observePagesForDocument(documentId),
        ) { docEntity, pageEntities ->
            if (docEntity == null) return@combine null
            val pages = pageEntities.map { it.toDomain() }
            val coverThumb = pages.firstOrNull { it.id == docEntity.coverPageId }?.thumbnailPath
                ?: pages.firstOrNull()?.thumbnailPath
            val doc = Document(
                id = docEntity.id,
                title = docEntity.title,
                folderId = docEntity.folderId,
                isFavorite = docEntity.isFavorite,
                coverPageId = docEntity.coverPageId,
                coverThumbnailPath = coverThumb,
                pageCount = pages.size,
                createdAt = docEntity.createdAt,
                updatedAt = docEntity.updatedAt,
            )
            doc to pages
        }

    suspend fun createDocumentFromDrafts(
        drafts: List<DraftPageSaveInput>,
        title: String? = null,
        folderId: String? = null,
    ): Document = withContext(dispatchers.io) {
        require(drafts.isNotEmpty()) { "Cannot create document with zero pages" }
        val now = System.currentTimeMillis()
        val docId = UUID.randomUUID().toString()
        val resolvedTitle = title?.takeIf { it.isNotBlank() } ?: defaultDocumentTitle(now)

        val pageEntities = drafts.mapIndexed { index, draft ->
            val files = storage.persistAndRenderNewPage(
                pageId = draft.pageId,
                sourcePath = draft.sourcePath,
                editState = draft.editState,
            )
            draft.toEntity(
                documentId = docId,
                orderIndex = index,
                files = files,
                now = now,
            )
        }

        val docEntity = DocumentEntity(
            id = docId,
            title = resolvedTitle,
            folderId = folderId,
            isFavorite = false,
            coverPageId = pageEntities.first().id,
            createdAt = now,
            updatedAt = now,
        )

        database.withTransaction {
            dao.insertDocument(docEntity)
            dao.insertPages(pageEntities)
        }

        Document(
            id = docEntity.id,
            title = docEntity.title,
            folderId = docEntity.folderId,
            isFavorite = false,
            coverPageId = docEntity.coverPageId,
            coverThumbnailPath = pageEntities.first().thumbnailPath,
            pageCount = pageEntities.size,
            createdAt = now,
            updatedAt = now,
        )
    }

    suspend fun appendPagesToDocument(
        documentId: String,
        drafts: List<DraftPageSaveInput>,
    ) = withContext(dispatchers.io) {
        if (drafts.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val existing = dao.getPagesForDocumentSync(documentId)
        val startOrder = (existing.maxOfOrNull { it.orderIndex } ?: -1) + 1

        val newEntities = drafts.mapIndexed { offset, draft ->
            val files = storage.persistAndRenderNewPage(
                pageId = draft.pageId,
                sourcePath = draft.sourcePath,
                editState = draft.editState,
            )
            draft.toEntity(
                documentId = documentId,
                orderIndex = startOrder + offset,
                files = files,
                now = now,
            )
        }

        database.withTransaction {
            dao.insertPages(newEntities)
        }
    }

    suspend fun updatePageEditState(
        pageId: String,
        editState: PageEditState,
    ) = withContext(dispatchers.io) {
        val existing = dao.getPageByIdSync(pageId) ?: return@withContext
        val rendered = storage.renderFromOriginal(
            pageId = pageId,
            originalPath = existing.originalPath,
            editState = editState,
        )
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            quadSerialized = QuadSerializer.encode(editState.quad),
            rotationDegrees = editState.normalizedRotation,
            filterType = editState.enhancement.filter.name,
            brightness = editState.enhancement.brightness,
            contrast = editState.enhancement.contrast,
            sharpness = editState.enhancement.sharpness,
            width = rendered.width,
            height = rendered.height,
            updatedAt = now,
        )
        dao.updatePage(updated)
    }

    suspend fun reorderPages(
        documentId: String,
        orderedPageIds: List<String>,
    ) = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            orderedPageIds.forEachIndexed { index, pageId ->
                dao.updatePageOrder(pageId, index, now)
            }
            orderedPageIds.firstOrNull()?.let { firstId ->
                dao.updateDocumentCover(documentId, firstId, now)
            }
        }
    }

    suspend fun deletePages(
        documentId: String,
        pageIds: List<String>,
    ) = withContext(dispatchers.io) {
        if (pageIds.isEmpty()) return@withContext
        val toDelete = dao.getPagesByIdsSync(pageIds)
        database.withTransaction {
            dao.deletePagesByIds(pageIds)
            val remaining = dao.getPagesForDocumentSync(documentId)
            if (remaining.isEmpty()) {
                dao.deleteDocumentsByIds(listOf(documentId))
            } else {
                remaining.forEachIndexed { index, page ->
                    dao.updatePageOrder(page.id, index, System.currentTimeMillis())
                }
                dao.updateDocumentCover(documentId, remaining.first().id, System.currentTimeMillis())
            }
        }
        storage.deletePageFiles(toDelete)
    }

    suspend fun deleteDocuments(documentIds: List<String>) = withContext(dispatchers.io) {
        if (documentIds.isEmpty()) return@withContext
        val pages = dao.getPagesForDocumentsSync(documentIds)
        database.withTransaction {
            dao.deleteDocumentsByIds(documentIds)
        }
        storage.deletePageFiles(pages)
    }

    suspend fun renameDocument(documentId: String, newTitle: String) = withContext(dispatchers.io) {
        val trimmed = newTitle.trim()
        if (trimmed.isNotEmpty()) {
            dao.renameDocument(documentId, trimmed, System.currentTimeMillis())
        }
    }

    suspend fun toggleFavorite(documentId: String, isFavorite: Boolean) = withContext(dispatchers.io) {
        dao.updateFavorite(documentId, isFavorite, System.currentTimeMillis())
    }

    suspend fun moveDocumentsToFolder(documentIds: List<String>, folderId: String?) = withContext(dispatchers.io) {
        if (documentIds.isEmpty()) return@withContext
        dao.moveDocumentsToFolder(documentIds, folderId, System.currentTimeMillis())
    }

    suspend fun createFolder(name: String): Folder = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val entity = FolderEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            createdAt = now,
            updatedAt = now,
        )
        dao.insertFolder(entity)
        Folder(
            id = entity.id,
            name = entity.name,
            parentId = null,
            documentCount = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    suspend fun renameFolder(folderId: String, newName: String) = withContext(dispatchers.io) {
        val trimmed = newName.trim()
        if (trimmed.isNotEmpty()) {
            dao.renameFolder(folderId, trimmed, System.currentTimeMillis())
        }
    }

    suspend fun deleteFolder(folderId: String) = withContext(dispatchers.io) {
        database.withTransaction {
            dao.clearFolderFromDocuments(folderId)
            dao.deleteFolder(folderId)
        }
    }

    private fun defaultDocumentTitle(timestampMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        return "LumiDoc_${fmt.format(Date(timestampMs))}"
    }

    private fun DraftPageSaveInput.toEntity(
        documentId: String,
        orderIndex: Int,
        files: FileStorageManager.RenderedPageFiles,
        now: Long,
    ): PageEntity = PageEntity(
        id = pageId,
        documentId = documentId,
        orderIndex = orderIndex,
        originalPath = files.originalPath,
        processedPath = files.processedPath,
        thumbnailPath = files.thumbnailPath,
        quadSerialized = QuadSerializer.encode(editState.quad),
        rotationDegrees = editState.normalizedRotation,
        filterType = editState.enhancement.filter.name,
        brightness = editState.enhancement.brightness,
        contrast = editState.enhancement.contrast,
        sharpness = editState.enhancement.sharpness,
        width = files.width,
        height = files.height,
        createdAt = now,
        updatedAt = now,
    )

    private fun PageEntity.toDomain(): DocumentPage {
        val filter = runCatching { EnhancementFilter.valueOf(filterType) }
            .getOrDefault(EnhancementFilter.AUTO_ENHANCE)
        return DocumentPage(
            id = id,
            documentId = documentId,
            orderIndex = orderIndex,
            originalPath = originalPath,
            processedPath = processedPath,
            thumbnailPath = thumbnailPath,
            editState = PageEditState(
                quad = QuadSerializer.decode(quadSerialized),
                rotationDegrees = rotationDegrees,
                enhancement = EnhancementParams(
                    filter = filter,
                    brightness = brightness,
                    contrast = contrast,
                    sharpness = sharpness,
                ),
            ),
            width = width,
            height = height,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}
