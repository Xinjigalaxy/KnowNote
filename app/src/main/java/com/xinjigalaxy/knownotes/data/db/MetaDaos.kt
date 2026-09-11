package com.xinjigalaxy.knownotes.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.xinjigalaxy.knownotes.data.model.ChangeLogEntry
import com.xinjigalaxy.knownotes.data.model.Group
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.data.model.SyncMeta
import com.xinjigalaxy.knownotes.data.model.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<Tag>>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun allOnce(): List<Tag>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun byId(id: Long): Tag?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Tag?

    /** name 唯一冲突时返回 -1，调用方回查已有 id。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Tag): Long

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Delete
    suspend fun delete(tag: Tag)

    @Query("SELECT COUNT(*) FROM note_tags WHERE tag_id = :tagId")
    suspend fun usageCount(tagId: Long): Int

    @Query("SELECT note_id FROM note_tags WHERE tag_id = :tagId")
    suspend fun noteIdsUsing(tagId: Long): List<Long>

    /** 标签合并：把 fromId 的引用指向 toId（重复的由 OR IGNORE 跳过）。 */
    @Query("UPDATE OR IGNORE note_tags SET tag_id = :toId WHERE tag_id = :fromId")
    suspend fun repointLinks(fromId: Long, toId: Long)

    @Query("DELETE FROM note_tags WHERE tag_id = :fromId")
    suspend fun dropLinks(fromId: Long)

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int
}

@Dao
interface GroupDao {

    @Query("SELECT * FROM groups ORDER BY sort_order ASC, name ASC")
    fun observeAll(): Flow<List<Group>>

    @Query("SELECT * FROM groups ORDER BY sort_order ASC, name ASC")
    suspend fun allOnce(): List<Group>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun byId(id: Long): Group?

    /** 同步时按名字认分组（跨设备不共用 id，只共用名字）。 */
    @Query("SELECT * FROM groups WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): Group?

    @Insert
    suspend fun insert(group: Group): Long

    @Update
    suspend fun update(group: Group)

    @Query("UPDATE groups SET sort_order = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    @Delete
    suspend fun delete(group: Group)

    @Query("SELECT COUNT(*) FROM notes WHERE group_id = :groupId AND is_deleted = 0")
    suspend fun noteCount(groupId: Long): Int

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM groups")
    suspend fun maxOrder(): Int

    @Query("SELECT COUNT(*) FROM groups")
    suspend fun count(): Int
}

@Dao
interface ChangeLogDao {

    @Insert
    suspend fun log(entry: ChangeLogEntry)

    @Query("SELECT * FROM change_log ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChangeLogEntry>

    @Query("SELECT * FROM change_log WHERE at > :since ORDER BY at ASC")
    suspend fun since(since: Long): List<ChangeLogEntry>

    @Query("SELECT COUNT(*) FROM change_log")
    suspend fun count(): Int

    @Query("DELETE FROM change_log WHERE at < :before")
    suspend fun prune(before: Long)
}

@Dao
interface SyncMetaDao {

    @Query("SELECT * FROM sync_meta LIMIT 1")
    suspend fun firstOrNull(): SyncMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: SyncMeta)
}

/** 同步日志（同步页展示历史）。 */
@Dao
interface SyncLogDao {

    @Insert
    suspend fun log(entry: SyncLogEntry)

    @Query("SELECT * FROM sync_log ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<SyncLogEntry>>

    @Query("SELECT * FROM sync_log ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SyncLogEntry>

    @Query("DELETE FROM sync_log")
    suspend fun clear()
}
