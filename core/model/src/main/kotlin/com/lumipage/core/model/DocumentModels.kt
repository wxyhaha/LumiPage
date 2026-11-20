package com.lumipage.core.model

/**
 * 单页的非破坏性编辑状态。
 */
data class PageEditState(
    val quad: DocumentQuad = DocumentQuad.DEFAULT_INSET,
    val rotationDegrees: Int = 0,
    val enhancement: EnhancementParams = EnhancementParams.DEFAULT_AUTO,
) {
    val normalizedRotation: Int
        get() = ((rotationDegrees % 360) + 360) % 360
}

/**
 * 文件夹领域模型。
 */
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val documentCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 文档领域模型（一个文档包含一页或多页）。
 */
data class Document(
    val id: String,
    val title: String,
    val folderId: String? = null,
    val isFavorite: Boolean = false,
    val coverPageId: String? = null,
    val coverThumbnailPath: String? = null,
    val pageCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 文档单页领域模型。
 */
data class DocumentPage(
    val id: String,
    val documentId: String,
    val orderIndex: Int,
    val originalPath: String,
    val processedPath: String,
    val thumbnailPath: String,
    val editState: PageEditState,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
