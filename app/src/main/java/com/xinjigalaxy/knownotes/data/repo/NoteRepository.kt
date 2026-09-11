package com.xinjigalaxy.knownotes.data.repo

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.db.FtsEngine
import com.xinjigalaxy.knownotes.data.db.FtsStore
import com.xinjigalaxy.knownotes.data.db.GroupCount
import com.xinjigalaxy.knownotes.data.db.SearchHistoryDao
import com.xinjigalaxy.knownotes.data.db.TagCount
import com.xinjigalaxy.knownotes.data.fts.FtsText
import com.xinjigalaxy.knownotes.data.model.ChangeLogEntry
import com.xinjigalaxy.knownotes.data.model.Group
import com.xinjigalaxy.knownotes.data.model.Note
import com.xinjigalaxy.knownotes.data.model.NoteTagCrossRef
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.model.SearchHistory
import com.xinjigalaxy.knownotes.data.model.SyncMeta
import com.xinjigalaxy.knownotes.data.model.Tag
import kotlinx.coroutines.flow.Flow

/**
 * 单一数据入口（MVVM + Repository，需求文档 6）。
 *
 * 约定：所有「写笔记 + 改标签关联 + 更新 FTS 索引 + 记变更日志」都在同一个事务里完成，
 * 保证主表与检索索引永远一致。
 */
class NoteRepository(
    private val db: AppDatabase,
    val deviceId: String,
) {

    private val noteDao = db.noteDao()
    private val tagDao = db.tagDao()
    private val groupDao = db.groupDao()
    private val logDao = db.changeLogDao()
    private val syncDao = db.syncMetaDao()
    private val historyDao = db.searchHistoryDao()
    private val fts = FtsStore(db)

    /** 当前实际生效的全文检索引擎（FTS5 / FTS4 / LIKE 兜底）。 */
    val searchEngineLabel: String get() = FtsStore.engine.label
    val searchEngineIsFullText: Boolean get() = FtsStore.engine.isFullText
    val searchEngineProbeError: String? get() = FtsStore.probeError
    val sqliteVersion: String get() = FtsStore.sqliteVersion
    val searchEngineDecision: String get() = FtsStore.lastDecision

    fun rawDb(): SupportSQLiteDatabase = db.openHelper.writableDatabase

    // ---------- 观察 ----------

    fun observeNotes(): Flow<List<NoteWithTags>> = noteDao.observeLiveWithTags()
    fun observeTags(): Flow<List<Tag>> = tagDao.observeAll()
    fun observeGroups(): Flow<List<Group>> = groupDao.observeAll()
    fun observeLiveCount(): Flow<Int> = noteDao.observeLiveCount()
    fun observeGroupCounts(): Flow<List<GroupCount>> = noteDao.observeGroupCounts()
    fun observeTagCounts(): Flow<List<TagCount>> = noteDao.observeTagCounts()

    suspend fun note(id: Long): NoteWithTags? = noteDao.withTags(id)

    // ---------- 检索（需求文档 3.3） ----------

    suspend fun searchIds(query: String): List<Long> =
        if (fts.available) {
            fts.searchIds(query)
        } else {
            noteDao.likeSearch(query, FtsText.MAX_RESULTS).map { it.id }
        }

    // ---------- 笔记 CRUD ----------

    suspend fun saveNote(
        noteId: Long?,
        title: String,
        content: String,
        groupId: Long?,
        tagNames: List<String>,
    ): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val cleanTitle = title.trim()
        val id: Long
        val op: String

        if (noteId == null || noteId == 0L) {
            id = noteDao.insert(
                Note(
                    title = cleanTitle,
                    content = content,
                    groupId = groupId,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            op = OP_INSERT
        } else {
            val existing = noteDao.byId(noteId)
            if (existing == null) {
                id = noteDao.insert(
                    Note(title = cleanTitle, content = content, groupId = groupId, createdAt = now, updatedAt = now)
                )
                op = OP_INSERT
            } else {
                noteDao.update(
                    existing.copy(
                        title = cleanTitle,
                        content = content,
                        groupId = groupId,
                        isDeleted = false,
                        updatedAt = now,
                    )
                )
                id = noteId
                op = OP_UPDATE
            }
        }

        val tagIds = resolveTagIds(tagNames)
        noteDao.clearTags(id)
        if (tagIds.isNotEmpty()) {
            noteDao.linkTags(tagIds.map { NoteTagCrossRef(noteId = id, tagId = it) })
        }

        reindex(id)
        logDao.log(ChangeLogEntry(op = op, noteId = id, deviceId = deviceId))
        id
    }

    /** 软删除（需求文档 2.1 的 is_deleted），索引同步移除。 */
    suspend fun deleteNote(id: Long) = db.withTransaction {
        noteDao.softDelete(id, System.currentTimeMillis())
        fts.remove(id)
        logDao.log(ChangeLogEntry(op = OP_DELETE, noteId = id, deviceId = deviceId))
    }

    suspend fun restoreNote(id: Long) = db.withTransaction {
        noteDao.restore(id, System.currentTimeMillis())
        reindex(id)
        logDao.log(ChangeLogEntry(op = OP_RESTORE, noteId = id, deviceId = deviceId))
    }

    suspend fun deletedCount(): Int = noteDao.deletedCount()

    fun observeDeleted(): Flow<List<NoteWithTags>> = noteDao.observeDeletedWithTags()

    /** 回收站里彻底删掉单条（不可恢复）。 */
    suspend fun purgeNote(id: Long) = db.withTransaction {
        noteDao.hardDelete(id)
        fts.remove(id)
    }

    suspend fun purgeDeleted(): Int = db.withTransaction {
        val targets = noteDao.allOnce().filter { it.isDeleted }
        targets.forEach {
            noteDao.hardDelete(it.id)
            fts.remove(it.id)
        }
        targets.size
    }

    suspend fun reindexNote(noteId: Long) = reindex(noteId)

    private suspend fun reindex(noteId: Long) {
        if (!fts.available) return
        val nw = noteDao.withTags(noteId) ?: return
        if (nw.note.isDeleted) {
            fts.remove(noteId)
            return
        }
        fts.upsert(
            noteId = nw.note.id,
            indexedTitle = FtsText.index(nw.note.title),
            indexedContent = FtsText.index(nw.note.content),
            indexedTags = FtsText.index(nw.tags.joinToString(" ") { it.name }),
        )
    }

    private suspend fun resolveTagIds(names: List<String>): List<Long> {
        val ids = ArrayList<Long>(names.size)
        for (raw in names) {
            val name = raw.trim()
            if (name.isEmpty()) continue
            val inserted = tagDao.insert(Tag(name = name))
            val id = if (inserted == -1L) (tagDao.byName(name)?.id ?: continue) else inserted
            if (id !in ids) ids += id
        }
        return ids
    }

    // ---------- 标签管理（含合并，需求文档 5.1） ----------

    suspend fun createTag(name: String): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1L
        val inserted = tagDao.insert(Tag(name = clean))
        return if (inserted == -1L) (tagDao.byName(clean)?.id ?: -1L) else inserted
    }

    suspend fun renameTag(id: Long, newName: String) = db.withTransaction {
        val clean = newName.trim()
        if (clean.isEmpty()) return@withTransaction
        val affected = tagDao.noteIdsUsing(id)
        tagDao.rename(id, clean)
        affected.forEach { reindex(it) }
    }

    suspend fun deleteTag(id: Long) = db.withTransaction {
        val tag = tagDao.byId(id) ?: return@withTransaction
        val affected = tagDao.noteIdsUsing(id)
        tagDao.delete(tag)
        affected.forEach { reindex(it) }
    }

    suspend fun mergeTags(sourceId: Long, targetId: Long) = db.withTransaction {
        if (sourceId == targetId) return@withTransaction
        val affected = tagDao.noteIdsUsing(sourceId)
        tagDao.repointLinks(sourceId, targetId)
        tagDao.dropLinks(sourceId)
        tagDao.byId(sourceId)?.let { tagDao.delete(it) }
        affected.forEach { reindex(it) }
    }

    // ---------- 分组管理 ----------

    suspend fun createGroup(name: String): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1L
        return groupDao.insert(Group(name = clean, sortOrder = groupDao.maxOrder() + 1))
    }

    suspend fun renameGroup(id: Long, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        groupDao.byId(id)?.let { groupDao.update(it.copy(name = clean)) }
    }

    /** keepNotes = true 时组内笔记回落到「无分组」，否则一起软删除。 */
    suspend fun deleteGroup(id: Long, keepNotes: Boolean) = db.withTransaction {
        val group = groupDao.byId(id) ?: return@withTransaction
        if (keepNotes) {
            noteDao.detachGroup(id)
        } else {
            val now = System.currentTimeMillis()
            noteDao.allOnce().filter { it.groupId == id && !it.isDeleted }.forEach {
                noteDao.softDelete(it.id, now)
                fts.remove(it.id)
                logDao.log(ChangeLogEntry(op = OP_DELETE, noteId = it.id, deviceId = deviceId))
            }
        }
        groupDao.delete(group)
    }

    suspend fun moveGroup(id: Long, delta: Int) {
        val ordered = groupDao.allOnce()
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target < 0 || target >= ordered.size) return
        val a = ordered[index]
        val b = ordered[target]
        groupDao.setOrder(a.id, b.sortOrder)
        groupDao.setOrder(b.id, a.sortOrder)
    }

    // ---------- 同步元数据（第三阶段预留） ----------

    suspend fun ensureDeviceMeta() {
        if (syncDao.firstOrNull() == null) {
            syncDao.put(SyncMeta(deviceId = deviceId, lastSyncAt = 0L))
        }
    }

    suspend fun changeLogCount(): Int = logDao.count()

    // ---------- 搜索历史与筛选记忆（需求文档 3.4） ----------

    fun observeRecentSearches(): Flow<List<SearchHistory>> = historyDao.observeRecent()
    fun observeTopSearches(): Flow<List<SearchHistory>> = historyDao.observeTop()

    /**
     * 记一次搜索：同一个词只累加次数、刷新时间；随后只保留最近 MAX_SUGGESTIONS 条，
     * 避免历史无限增长（剪枝在 Kotlin 里做，比在 SQL 里自引用子查询稳）。
     */
    suspend fun recordSearch(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        if (historyDao.insert(SearchHistory(keyword = clean)) == -1L) {
            historyDao.touch(clean, System.currentTimeMillis())
        }
        val keep = historyDao.recentKeywords(SearchHistoryDao.MAX_SUGGESTIONS)
        if (keep.isNotEmpty() && historyDao.count() > keep.size) {
            historyDao.deleteExcept(keep)
        }
    }

    suspend fun deleteSearchKeyword(keyword: String) = historyDao.delete(keyword)

    suspend fun clearSearchHistory() = historyDao.clear()

    // ---------- 导出取数 ----------

    suspend fun exportNotes(): List<NoteWithTags> = noteDao.allWithTagsOnce()
    suspend fun exportTags(): List<Tag> = tagDao.allOnce()
    suspend fun exportGroups(): List<Group> = groupDao.allOnce()

    suspend fun stats(): Stats = Stats(
        notes = noteDao.allOnce().count { !it.isDeleted },
        tags = tagDao.count(),
        groups = groupDao.count(),
        deleted = noteDao.deletedCount(),
        changeLog = logDao.count(),
        rawBytes = 0L,
    )

    data class Stats(
        val notes: Int,
        val tags: Int,
        val groups: Int,
        val deleted: Int,
        val changeLog: Int,
        val rawBytes: Long,
    )

    companion object {
        const val OP_INSERT = "insert"
        const val OP_UPDATE = "update"
        const val OP_DELETE = "delete"
        const val OP_RESTORE = "restore"
    }
}
