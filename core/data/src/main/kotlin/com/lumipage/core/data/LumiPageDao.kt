package com.lumipage.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LumiPageDao {

    @Query(
        """
        SELECT f.id, f.name, f.parentId, COUNT(d.id) AS documentCount, f.createdAt, f.updatedAt
        FROM folders f
        LEFT JOIN documents d ON d.folderId = f.id
        GROUP BY f.id
        ORDER BY f.name ASC
        """,
    )
    fun observeFoldersWithCounts(): Flow<List<FolderWithCountTuple>>

    @Query(
        """
        SELECT d.id, d.title, d.folderId, d.isFavorite, d.coverPageId,
               COALESCE(
                   (SELECT p.thumbnailPath FROM pages p WHERE p.id = d.coverPageId LIMIT 1),
                   (SELECT p.thumbnailPath FROM pages p WHERE p.documentId = d.id ORDER BY p.orderIndex ASC LIMIT 1)
               ) AS coverThumbnailPath,
               (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
               d.createdAt, d.updatedAt
        FROM documents d
        ORDER BY d.updatedAt DESC
        """,
    )
    fun observeAllDocumentsWithStats(): Flow<List<DocumentWithStatsTuple>>

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    fun observeDocumentById(documentId: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY orderIndex ASC")
    fun observePagesForDocument(documentId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY orderIndex ASC")
    suspend fun getPagesForDocumentSync(documentId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :pageId LIMIT 1")
    suspend fun getPageByIdSync(pageId: String): PageEntity?

    @Query("SELECT * FROM pages WHERE id IN (:pageIds)")
    suspend fun getPagesByIdsSync(pageIds: List<String>): List<PageEntity>

    @Query("SELECT * FROM pages WHERE documentId IN (:documentIds)")
    suspend fun getPagesForDocumentsSync(documentIds: List<String>): List<PageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name, updatedAt = :updatedAt WHERE id = :folderId")
    suspend fun renameFolder(folderId: String, name: String, updatedAt: Long)

    @Query("UPDATE documents SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderFromDocuments(folderId: String)

    @Query("DELETE FROM folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PageEntity>)

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("UPDATE documents SET title = :title, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun renameDocument(documentId: String, title: String, updatedAt: Long)

    @Query("UPDATE documents SET isFavorite = :isFavorite, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun updateFavorite(documentId: String, isFavorite: Boolean, updatedAt: Long)

    @Query("UPDATE documents SET folderId = :folderId, updatedAt = :updatedAt WHERE id IN (:documentIds)")
    suspend fun moveDocumentsToFolder(documentIds: List<String>, folderId: String?, updatedAt: Long)

    @Query("UPDATE documents SET coverPageId = :coverPageId, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun updateDocumentCover(documentId: String, coverPageId: String?, updatedAt: Long)

    @Query("UPDATE pages SET orderIndex = :orderIndex, updatedAt = :updatedAt WHERE id = :pageId")
    suspend fun updatePageOrder(pageId: String, orderIndex: Int, updatedAt: Long)

    @Query("DELETE FROM pages WHERE id IN (:pageIds)")
    suspend fun deletePagesByIds(pageIds: List<String>)

    @Query("DELETE FROM documents WHERE id IN (:documentIds)")
    suspend fun deleteDocumentsByIds(documentIds: List<String>)
}
