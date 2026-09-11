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
@Entity(
    tableName = "notes",
    indices = [Index(value = ["guid"], unique = true)],
)
data class Note(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "title") val title: String = "",
    @ColumnInfo(name = "content") val content: String = "",
    @ColumnInfo(name = "group_id") val groupId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_deleted", defaultValue = "0") val isDeleted: Boolean = false,
    /**
     * 跨设备稳定标识（v1.3.0 同步用）。
     *
     * 本地自增 id 在两台设备上会撞车、也没法对齐同一条笔记，
     * 所以同步一律按 guid 认人，本地 id 只在设备内部用。
     */
    @ColumnInfo(name = "guid", defaultValue = "") val guid: String = "",
    /**
     * 彻底删除的墓碑（v1.3.0）。
     *
     * 「彻底删除」如果直接把行删掉，对端下次同步会把这条笔记再推回来 ——
     * 所以改成保留一行 is_deleted=1 + is_purged=1 的墓碑，靠时间戳把删除意图传出去。
     * 所有列表 / 检索 / 计数都过滤掉 is_purged=1。
     */
    @ColumnInfo(name = "is_purged", defaultValue = "0") val isPurged: Boolean = false,
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

/**
 * 同步日志（需求文档 7 第三阶段「同步状态与日志展示」）。
 *
 * 每次同步（无论当前设备是主机还是从机）落一条，供同步页展示。
 */
@Entity(tableName = "sync_log")
data class SyncLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "at") val at: Long = System.currentTimeMillis(),
    /** host = 本机作为主机接待了对端；client = 本机作为从机去拉了主机。 */
    @ColumnInfo(name = "role") val role: String,
    /** 对端描述：从机视角是「主机地址」，主机视角是「对端设备名」。 */
    @ColumnInfo(name = "peer") val peer: String,
    @ColumnInfo(name = "pulled") val pulled: Int = 0,
    @ColumnInfo(name = "pushed") val pushed: Int = 0,
    @ColumnInfo(name = "conflicts") val conflicts: Int = 0,
    @ColumnInfo(name = "ok") val ok: Boolean = true,
    @ColumnInfo(name = "message") val message: String = "",
) {
    companion object {
        const val ROLE_HOST = "host"
        const val ROLE_CLIENT = "client"
    }
}

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
