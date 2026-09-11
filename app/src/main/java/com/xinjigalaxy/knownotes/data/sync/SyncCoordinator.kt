package com.xinjigalaxy.knownotes.data.sync

import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
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
    )

    suspend fun syncWith(host: String, port: Int, key: String): Result<Session> {
        val since = repo.lastSyncAt()
        val outgoing = engine.collectChanges(since)
        val outcome = client.sync(host, port, key, since, outgoing)

        val response = outcome.response
        if (response == null) {
            val reason = outcome.error ?: "未知错误"
            repo.logSync(
                SyncLogEntry(
                    role = SyncLogEntry.ROLE_CLIENT,
                    peer = "$host:$port",
                    pushed = outgoing.size,
                    ok = false,
                    message = reason,
                )
            )
            return Result.failure(IllegalStateException(reason))
        }

        val applied = engine.applyChanges(response.notes)
        // 水位线取「主机时间」与「本机时间」里较小的那个：
        // 宁可下次多要一点（重复应用是幂等的），也不能因为两台设备时钟有偏差而漏掉变更。
        val watermark = minOf(response.serverTime, System.currentTimeMillis())
        repo.setLastSyncAt(watermark)
        repo.rememberPeer("$host:$port")

        val conflicts = applied.conflicts + response.conflicts
        repo.logSync(
            SyncLogEntry(
                role = SyncLogEntry.ROLE_CLIENT,
                peer = "${response.deviceName} ($host:$port)",
                pulled = response.notes.size,
                pushed = outgoing.size,
                conflicts = conflicts,
                ok = true,
                message = "本机落库 ${applied.changed} 条（新增 ${applied.inserted} / 更新 ${applied.updated}）",
            )
        )

        return Result.success(
            Session(
                peerName = response.deviceName,
                host = host,
                port = port,
                pulled = response.notes.size,
                pushed = outgoing.size,
                peerApplied = response.appliedNotes,
                applied = applied,
                conflicts = conflicts,
                watermark = watermark,
            )
        )
    }
}
