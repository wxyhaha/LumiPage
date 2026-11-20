package com.lumipage.core.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "documents",
    indices = [Index("folderId"), Index("updatedAt")],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val folderId: String? = null,
    val isFavorite: Boolean = false,
    val coverPageId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("orderIndex")],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val orderIndex: Int,
    val originalPath: String,
    val processedPath: String,
    val thumbnailPath: String,
    val quadSerialized: String,
    val rotationDegrees: Int,
    val filterType: String,
    val brightness: Float,
    val contrast: Float,
    val sharpness: Float,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class DocumentWithStatsTuple(
    val id: String,
    val title: String,
    val folderId: String?,
    val isFavorite: Boolean,
    val coverPageId: String?,
    val coverThumbnailPath: String?,
    val pageCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class FolderWithCountTuple(
    val id: String,
    val name: String,
    val parentId: String?,
    val documentCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)
