package com.xinjigalaxy.knownotes.data.sync

import com.xinjigalaxy.knownotes.data.media.ImageStore
import com.xinjigalaxy.knownotes.data.model.Note
import com.xinjigalaxy.knownotes.data.repo.NoteRepository

/**
 * 同步引擎：只负责「哪些要发」和「收到之后怎么裁决」，具体落库交给 Repository。
 *
 * 冲突策略（需求文档 4.3 初期方案：时间戳优先）：
 * 1. `updated_at` 大的那版赢 —— 覆盖标题 / 正文 / 分组 / 标签 / 软删除 / 墓碑，一次全换。
 * 2. 时间戳**打平但内容不同**：不谈「谁赢」，两端都取规范串较大的那版。
 *    这样两台设备一定收敛到同一份，而不是各留各的、下次同步又互相打回。
 * 3. 本机更新：本机不动。对端下次同步拿到本机这版，会按同样的规则认输。
 */
class SyncEngine(private val repo: NoteRepository) {

    data class ApplyResult(
        val inserted: Int = 0,
        val updated: Int = 0,
        val conflicts: Int = 0,
        val skipped: Int = 0,
    ) {
        /** 实际落库的条数。 */
        val changed: Int get() = inserted + updated
    }

    /**
     * 本机自水位线之后改动过的笔记（含软删除与墓碑）。
     *
     * 走 change_log 而不是「updated_at > since」：这样连「彻底删除」这种没有内容变更的
     * 操作也能被带出去，且不用全表扫。
     */
    suspend fun collectChanges(since: Long): List<SyncNote> {
        val ids = repo.changeLogSince(since).map { it.noteId }.distinct()
        val out = ArrayList<SyncNote>(ids.size)
        for (id in ids) {
            val note = repo.noteById(id) ?: continue
            // v2 之前的历史数据可能没有 guid，补不上就跳过（不拿空 guid 去污染对端）
            if (note.guid.isBlank()) continue
            out += toSyncNote(note)
        }
        return out
    }

    /** 应用远端变更。originDevice 非空 = 本机是主机、正在中转（会记变更日志给别的从机）。 */
    suspend fun applyChanges(remote: List<SyncNote>, originDevice: String? = null): ApplyResult {
        var inserted = 0
        var updated = 0
        var conflicts = 0
        var skipped = 0

        for (item in remote) {
            if (item.guid.isBlank()) {
                skipped++
                continue
            }
            val local = repo.noteByGuid(item.guid)
            if (local == null) {
                if (item.isPurged) {
                    // 本机本来就没有这条，墓碑无事可做
                    skipped++
                } else {
                    repo.writeFromRemote(item, originDevice)
                    inserted++
                }
                continue
            }

            when {
                item.updatedAt > local.updatedAt -> {
                    repo.writeFromRemote(item, originDevice)
                    updated++
                }

                item.updatedAt < local.updatedAt -> skipped++

                else -> {
                    val localCanonical = toSyncNote(local).canonical()
                    val remoteCanonical = item.canonical()
                    if (localCanonical == remoteCanonical) {
                        skipped++
                    } else {
                        conflicts++
                        if (remoteCanonical > localCanonical) {
                            repo.writeFromRemote(item, originDevice)
                        }
                    }
                }
            }
        }
        return ApplyResult(inserted = inserted, updated = updated, conflicts = conflicts, skipped = skipped)
    }

    // ---------- 图片 ----------

    /**
     * 本机认为对端缺的图片：本机有、对端没有的那些。
     *
     * 按文件名排序再截断 —— 顺序确定，多轮传输时每轮拿到的都是"下一批"而不是随机一批，
     * 也方便测试断言。字节数封顶是防"一次几十兆塞进一个 JSON"。
     */
    fun outgoingImages(store: ImageStore, peerHas: Set<String>): List<SyncImage> {
        val candidates = store.names().filter { it !in peerHas }.sorted()
        val out = ArrayList<SyncImage>(candidates.size)
        var bytes = 0
        for (name in candidates) {
            if (out.size >= MAX_SYNC_IMAGES) break
            val data = store.read(name) ?: continue
            if (bytes + data.size > MAX_SYNC_IMAGE_BYTES && out.isNotEmpty()) break
            bytes += data.size
            out += SyncImage(name, data)
        }
        return out
    }

    /**
     * 落盘收到的图片，返回**真正新增**的张数。
     *
     * 已存在的跳过 —— 于是重复同步不会重复计数，也不会用对端的旧版本覆盖本机。
     */
    fun applyImages(store: ImageStore, images: List<SyncImage>): Int {
        var added = 0
        for (image in images) {
            if (image.name.isBlank()) continue
            if (store.write(image.name, image.bytes)) added++
        }
        return added
    }

    private suspend fun toSyncNote(note: Note) = SyncNote(
        guid = note.guid,
        title = note.title,
        content = note.content,
        group = repo.groupNameOf(note.groupId),
        tags = repo.tagNames(note.id),
        createdAt = note.createdAt,
        updatedAt = note.updatedAt,
        isDeleted = note.isDeleted,
        isPurged = note.isPurged,
    )
}
