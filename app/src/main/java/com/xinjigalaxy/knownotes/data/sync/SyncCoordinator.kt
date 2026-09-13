package com.xinjigalaxy.knownotes.data.sync

import com.xinjigalaxy.knownotes.data.media.ImageStore
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository

/**
 * 一次完整的「从机同步」会话：取本地增量 → 发给主机 → 落库远端增量 → 记水位线与日志。
 *
 * 抽出来是因为这套流程必须只有一份实现：如果逻辑留在 ViewModel 里，
 * 测试就只能去调底层 client、绕开日志和水位线，于是"同步能跑"和"日志/水位线对"
 * 会变成两件互不相干的事（真回环测试第一次跑就是这么暴露的）。
 */
class SyncCoordinator(
    private val repo: NoteRepository,
    private val engine: SyncEngine,
    private val client: SyncClient,
    private val imageStore: ImageStore,
    private val prefs: UiPrefs,
) {

    data class Session(
        val peerName: String,
        val host: String,
        val port: Int,
        /** 从主机拉回来的笔记条数。 */
        val pulled: Int,
        /** 推给主机的笔记条数。 */
        val pushed: Int,
        /** 主机侧落库了多少条（主机在响应里告诉我们）。 */
        val peerApplied: Int,
        val applied: SyncEngine.ApplyResult,
        /** 时间戳打平、按内容裁决的条数（两端合计）。 */
        val conflicts: Int,
        /** 本次同步后写入的水位线。 */
        val watermark: Long,
        /** 本次会话推上去 / 拉下来的图片张数（跨多轮累加）。 */
        val imagesPushed: Int = 0,
        val imagesPulled: Int = 0,
        /** 为了把图片传完，实际发了几轮请求（笔记只在第一轮传）。 */
        val rounds: Int = 1,
    )

    /**
     * @return 会话结果；失败时把原因写进同步日志再返回 failure
     */
    suspend fun syncWith(host: String, port: Int, key: String): Result<Session> {
        val peerKey = "$host:$port"
        var watermark = repo.lastSyncAt()
        // 笔记只在第一轮传：后面几轮纯粹是为了把图片传完
        var pendingNotes = engine.collectChanges(watermark)
        val notesToPush = pendingNotes.size

        var rounds = 0
        var imagesPushed = 0
        var imagesPulled = 0
        var applied = SyncEngine.ApplyResult()
        var conflicts = 0
        var pulled = 0
        var peerApplied = 0
        var peerName = ""
        var serverTime = watermark

        while (true) {
            rounds++
            // 对端已有哪些图：优先用上次同步缓存的集合；没记录过就当成"它什么都没有"
            val peerHas = prefs.peerImages(peerKey) ?: emptySet()
            val images = engine.outgoingImages(imageStore, peerHas)

            val outcome = client.sync(
                host = host,
                port = port,
                key = key,
                lastSyncAt = watermark,
                notes = pendingNotes,
                imagesIHave = imageStore.names().toList(),
                images = images,
            )
            val response = outcome.response
            if (response == null) {
                val reason = outcome.error ?: "Unknown error"
                repo.logSync(
                    SyncLogEntry(
                        role = SyncLogEntry.ROLE_CLIENT,
                        peer = "$host:$port",
                        pushed = notesToPush,
                        ok = false,
                        message = reason,
                    )
                )
                return Result.failure(IllegalStateException(reason))
            }

            peerName = response.deviceName
            serverTime = response.serverTime
            pulled += response.notes.size
            peerApplied += response.appliedNotes
            conflicts += response.conflicts

            val appliedNow = engine.applyChanges(response.notes)
            applied = applied.plus(appliedNow)
            conflicts += appliedNow.conflicts

            // 图片：收下主机给的，记下"它现在有哪些"
            val received = engine.applyImages(imageStore, response.images)
            imagesPulled += received
            imagesPushed += response.imagesReceived
            prefs.savePeerImages(peerKey, response.imagesIHave.toSet())

            // 水位线取「主机时间」与「本机时间」里较小的那个：
            // 宁可下次多要一点（重复应用是幂等的），也不能因为两台设备时钟有偏差而漏掉变更。
            watermark = minOf(serverTime, System.currentTimeMillis())
            repo.setLastSyncAt(watermark)
            repo.rememberPeer(peerKey)

            pendingNotes = emptyList()

            // 还有「本机有、对端集合里没有」的图吗？有就再来一轮（单次请求有张数上限）
            val stillMissingAtPeer = imageStore.names() - response.imagesIHave.toSet()
            if (stillMissingAtPeer.isEmpty() || rounds >= MAX_IMAGE_ROUNDS) break
        }

        repo.logSync(
            SyncLogEntry(
                role = SyncLogEntry.ROLE_CLIENT,
                peer = "$peerName ($host:$port)",
                pulled = pulled,
                pushed = notesToPush,
                conflicts = conflicts,
                ok = true,
                message = "applied ${applied.changed} locally (${applied.inserted} new / ${applied.updated} updated), " +
                    "images +$imagesPulled/-$imagesPushed in $rounds round(s)",
            )
        )

        return Result.success(
            Session(
                peerName = peerName,
                host = host,
                port = port,
                pulled = pulled,
                pushed = notesToPush,
                peerApplied = peerApplied,
                applied = applied,
                conflicts = conflicts,
                watermark = watermark,
                imagesPushed = imagesPushed,
                imagesPulled = imagesPulled,
                rounds = rounds,
            )
        )
    }

    private companion object {
        /** 一次同步最多发几轮（多了就是把图片留到下次，别让用户干等）。 */
        const val MAX_IMAGE_ROUNDS = 4
    }
}

/** 统计量相加（跨轮累加用）。 */
private fun SyncEngine.ApplyResult.plus(other: SyncEngine.ApplyResult) = SyncEngine.ApplyResult(
    inserted = inserted + other.inserted,
    updated = updated + other.updated,
    conflicts = conflicts + other.conflicts,
    skipped = skipped + other.skipped,
)
