package com.xinjigalaxy.knownotes.data.sync

import com.xinjigalaxy.knownotes.data.media.ImageStore
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    /**
     * 局域网判定（v1.10.1）。默认放行 —— 生产在 `AppContainer` 里注入真判定（`LanGuard`），
     * 测试塞一个固定值就能把「被拦下」那条路真的跑一遍。
     */
    private val lanCheck: LanScopeCheck = LanScopeCheck { LanScope.LAN },
    /**
     * 「对端与本机不在同一个网段」时的说明（v1.10.3）。默认用真判定（枚举本机网卡比对），
     * 测试注入一个固定值就能把「不在同一个网里」那条路真的跑一遍。
     */
    private val offLanNote: (String) -> String? = { LanSubnet.mismatchNote(it) },
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
     * 同步会话的串行闸（v1.10.0 起）。
     *
     * 手动点「开始同步」和后台的定时自动同步可能同时发生，而水位线只有**一份**
     * （`sync_meta.last_sync_at`）。两个会话并行会各自读到同一个旧水位线，
     * 把同一批变更推两遍（幂等，数据不会坏，但日志重复、水位线互相覆盖）。
     * 用一把互斥锁把会话排成队：谁先到谁先跑，后到的等前一个结束，读到的是新水位线。
     */
    private val gate = Mutex()

    /**
     * @return 会话结果；失败时把原因写进同步日志再返回 failure
     */
    suspend fun syncWith(host: String, port: Int, key: String): Result<Session> =
        gate.withLock { syncSession(host, port, key) }

    /** 局域网判定：同步会话与「探测主机」共用同一份，避免两处各判一套。 */
    fun lanScopeOf(host: String): LanScope = lanCheck.scopeOf(host)

    private suspend fun syncSession(host: String, port: Int, key: String): Result<Session> {
        val peerKey = "$host:$port"
        // 局域网边界（v1.10.1）：不在局域网就**不启动** —— 连一个请求都不发出去。
        // 手动同步、定时自动同步都经过这里，所以这条规矩只有一处实现。
        val scope = lanCheck.scopeOf(host)
        if (scope != LanScope.LAN) {
            val blocked = LanBlockedException(scope)
            repo.logSync(
                SyncLogEntry(
                    role = SyncLogEntry.ROLE_CLIENT,
                    peer = peerKey,
                    pushed = 0,
                    ok = false,
                    message = blocked.message ?: "Sync blocked",
                )
            )
            return Result.failure(blocked)
        }

        var watermark = repo.lastSyncAt()
        // 第一轮先把「水位线之后的变更」带上当快路径（常见情况正好是对方要的）。
        // 真正的差集不再依赖它：双方各报一份全量库存，对方缺什么由库存比出来，
        // 缺的那些要么这一轮就在 notes 里，要么下一轮由 want_guids 点名要。
        var pendingNotes = engine.collectChanges(watermark)
        var notesToPush = pendingNotes.size

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
                inventory = engine.inventory(),
                imagesIHave = imageStore.names().toList(),
                images = images,
            )
            val response = outcome.response
            if (response == null) {
                // 对端与本机不在同一个网段时（v1.10.3），「连不上」的真正原因就是「不在一个网里」：
                // 把系统那句 `failed to connect to /192.168.1.7 ... after 4000ms` 换成看得懂的说明。
                // 枚举网卡要读系统接口，放到 IO 线程上做。
                val offLan = withContext(Dispatchers.IO) { offLanNote(host) }
                val error: Throwable = if (offLan != null) {
                    OffLanException(offLan)
                } else {
                    IllegalStateException(outcome.error ?: "Unknown error")
                }
                repo.logSync(
                    SyncLogEntry(
                        role = SyncLogEntry.ROLE_CLIENT,
                        peer = "$host:$port",
                        pushed = notesToPush,
                        ok = false,
                        message = error.message ?: "Unknown error",
                    )
                )
                return Result.failure(error)
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
            // 协议 3 起它只是快路径与界面显示，差集由库存比对决定，所以它偏了也不会漏数据。
            watermark = minOf(serverTime, System.currentTimeMillis())
            repo.setLastSyncAt(watermark)
            repo.rememberPeer(peerKey)

            // 下一轮发什么：对方点名的 ∪ 我从它的库存里算出来「它缺 / 它旧」的。
            // 算两遍是刻意的冗余 —— 对端换了实现（比如 Termux 上的中心）也不会漏。
            val wanted = LinkedHashSet<String>(response.wantGuids)
            wanted += SyncDiff.peerNeeds(engine.inventory(), response.inventory)
            pendingNotes = engine.notesFor(wanted)
            notesToPush += pendingNotes.size

            // 还有要发的笔记，或还有「本机有、对端集合里没有」的图，就再来一轮（单次请求都有上限）
            val stillMissingAtPeer = imageStore.names() - response.imagesIHave.toSet()
            val more = pendingNotes.isNotEmpty() || stillMissingAtPeer.isNotEmpty()
            if (!more || rounds >= MAX_ROUNDS) break
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
        /**
         * 一次同步最多几轮：笔记差集一轮、图片每轮有张数/字节上限，都要靠多轮传完。
         * 5 轮足够（正常是 1~2 轮），多出来的只是防止对端实现异常时来回打转。
         */
        const val MAX_ROUNDS = 5
    }
}

/** 统计量相加（跨轮累加用）。 */
private fun SyncEngine.ApplyResult.plus(other: SyncEngine.ApplyResult) = SyncEngine.ApplyResult(
    inserted = inserted + other.inserted,
    updated = updated + other.updated,
    conflicts = conflicts + other.conflicts,
    skipped = skipped + other.skipped,
)
