package com.xinjigalaxy.knownotes.data.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * 笔记主表（需求文档 2.1）。
 * 软删除 + 时间戳，升级时新增字段一律 NULL 或带默认值（文档 2.2）。
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "content") val content: String = "",
    @ColumnInfo(name = "group_id") val groupId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_deleted", defaultValue = "0") val isDeleted: Boolean = false,
)

/** 标签字典：name 唯一（需求文档 2.1）。 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
)

/** 分组：sort_order 控制排序（需求文档 2.1）。 */
@Entity(tableName = "groups")
data class Group(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

/** 笔记-标签多对多关联，联合主键 (note_id, tag_id)（需求文档 2.1）。 */
@Entity(
    tableName = "note_tags",
    primaryKeys = ["note_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_id"])],
)
data class NoteTagCrossRef(
    @ColumnInfo(name = "note_id") val noteId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

/** 同步元数据：为第三阶段局域网同步预留（需求文档 2.1）。 */
@Entity(tableName = "sync_meta")
data class SyncMeta(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long = 0L,
    @ColumnInfo(name = "peer_url") val peerUrl: String? = null,
)

/** 变更日志：增量同步的核心，每次增删改写一条（需求文档 4.2）。 */
@Entity(tableName = "change_log", indices = [Index(value = ["note_id"])])
data class ChangeLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "op") val op: String,
    @ColumnInfo(name = "note_id") val noteId: Long,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "at") val at: Long = System.currentTimeMillis(),
)

/** 笔记 + 其标签，列表与编辑页共用。 */
data class NoteWithTags(
    @Embedded val note: Note,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = NoteTagCrossRef::class,
            parentColumn = "note_id",
            entityColumn = "tag_id",
        ),
    )
    val tags: List<Tag>,
)
