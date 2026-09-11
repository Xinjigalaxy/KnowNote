package com.xinjigalaxy.knownotes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.xinjigalaxy.knownotes.data.model.Note
import com.xinjigalaxy.knownotes.data.model.NoteTagCrossRef
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Transaction
    @Query("SELECT * FROM notes WHERE is_deleted = 0 AND is_purged = 0 ORDER BY updated_at DESC")
    fun observeLiveWithTags(): Flow<List<NoteWithTags>>

    /** 回收站：软删除的笔记（需求文档 5.1 缺的这块入口）。墓碑不算。 */
    @Transaction
    @Query("SELECT * FROM notes WHERE is_deleted = 1 AND is_purged = 0 ORDER BY updated_at DESC")
    fun observeDeletedWithTags(): Flow<List<NoteWithTags>>

    @Transaction
    @Query("SELECT * FROM notes WHERE is_purged = 0 ORDER BY updated_at DESC")
    suspend fun allWithTagsOnce(): List<NoteWithTags>

    @Query("SELECT * FROM notes WHERE is_purged = 0 ORDER BY updated_at DESC")
    suspend fun allOnce(): List<Note>

    @Query("SELECT * FROM notes WHERE guid = :guid LIMIT 1")
    suspend fun byGuid(guid: String): Note?

    @Transaction
    @Query("SELECT * FROM notes WHERE guid = :guid LIMIT 1")
    suspend fun withTagsByGuid(guid: String): NoteWithTags?

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun withTags(id: Long): NoteWithTags?

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Query("SELECT * FROM notes WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE is_deleted = 0 AND is_purged = 0")
    fun observeLiveCount(): Flow<Int>

    /** 分组 / 标签的引用计数，管理页展示用。 */
    @Query(
        "SELECT group_id AS groupId, COUNT(*) AS count FROM notes " +
            "WHERE is_deleted = 0 AND is_purged = 0 AND group_id IS NOT NULL GROUP BY group_id"
    )
    fun observeGroupCounts(): Flow<List<GroupCount>>

    @Query("SELECT tag_id AS tagId, COUNT(*) AS count FROM note_tags GROUP BY tag_id")
    fun observeTagCounts(): Flow<List<TagCount>>

    @Query("SELECT COUNT(*) FROM notes WHERE is_deleted = 1 AND is_purged = 0")
    suspend fun deletedCount(): Int

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Query("UPDATE notes SET is_deleted = 1, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    /** 彻底删除 = 立墓碑（保留行），这样「删掉」这件事也能同步给对端。 */
    @Query("UPDATE notes SET is_deleted = 1, is_purged = 1, updated_at = :now WHERE id = :id")
    suspend fun purge(id: Long, now: Long)

    @Query("SELECT * FROM notes WHERE is_deleted = 1 AND is_purged = 0")
    suspend fun purgeCandidates(): List<Note>

    @Query("UPDATE notes SET is_deleted = 0, updated_at = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun hardDelete(id: Long)

    /** 分组被删除时，组内笔记回落到「无分组」，不连坐删除。 */
    @Query("UPDATE notes SET group_id = NULL WHERE group_id = :groupId")
    suspend fun detachGroup(groupId: Long)

    @Query("DELETE FROM note_tags WHERE note_id = :noteId")
    suspend fun clearTags(noteId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkTags(refs: List<NoteTagCrossRef>)

    @Query(
        "SELECT t.name FROM tags t INNER JOIN note_tags nt ON nt.tag_id = t.id " +
            "WHERE nt.note_id = :noteId ORDER BY t.name"
    )
    suspend fun tagNamesOf(noteId: Long): List<String>

    /** FTS 不可用时的兜底：LIKE 扫描，且必须覆盖标签（否则标签筛选语义会悄悄丢失）。 */
    @Query(
        "SELECT * FROM notes WHERE is_deleted = 0 AND (" +
            "title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' OR " +
            "id IN (SELECT nt.note_id FROM note_tags nt INNER JOIN tags t ON t.id = nt.tag_id " +
            "WHERE t.name LIKE '%' || :q || '%')) " +
            "ORDER BY updated_at DESC LIMIT :limit"
    )
    suspend fun likeSearch(q: String, limit: Int): List<Note>

    @Query(
        "SELECT DISTINCT n.* FROM notes n INNER JOIN note_tags nt ON nt.note_id = n.id " +
            "WHERE nt.tag_id IN (:tagIds) AND n.is_deleted = 0 ORDER BY n.updated_at DESC"
    )
    suspend fun byAnyTag(tagIds: List<Long>): List<Note>
}

/** 分组笔记数（分组管理页展示用）。 */
data class GroupCount(val groupId: Long, val count: Int)

/** 标签引用数（标签管理页展示用）。 */
data class TagCount(val tagId: Long, val count: Int)
