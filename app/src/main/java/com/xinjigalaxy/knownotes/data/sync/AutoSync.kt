package com.xinjigalaxy.knownotes.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xinjigalaxy.knownotes.KnowNoteApp
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import java.util.concurrent.TimeUnit

/**
 * 定时自动同步（v1.10.0）：设备侧的后台定时。
 *
 * 与服务器侧（`server/knownote_hub.py`）各解决同一问题的一半：
 *
 * - **设备主动推**（这里）：WorkManager 到点拉起 App 进程，读「主机地址 + 密钥」，
 *   跑一次 `SyncCoordinator`。不要求 App 在前台，也不需要谁先发现谁；但受 Android
 *   约束：最短周期 15 分钟，系统在省电 / 息屏时可能继续推迟。
 * - **服务器主动拉**（中心侧）：中心按 `interval_seconds` 连接设备，分钟级可控；
 *   前提是设备的「主机模式」开着且 App 进程存活。
 *
 * 两者同时开启最稳：设备醒来即推，中心到点即拉，先到者生效。
 *
 * 依赖：只用 App 已有的 WorkManager（回收站定时清理同款）与现成的 `SyncCoordinator`，
 * 不引入任何新依赖。
 */
class AutoSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? KnowNoteApp)?.container ?: return Result.success()
        return AutoSync.run(container.uiPrefs, container.syncCoordinator)
    }
}

object AutoSync {

    /**
     * WorkManager 允许的最短周期就是 15 分钟，写更小的值会被系统抬回 15 分钟 ——
     * 所以界面上也照这个下限来，不假装能每 5 分钟一次。
     */
    const val MIN_INTERVAL_MINUTES = 15

    /** 界面上的间隔选项（分钟）。 */
    val INTERVAL_CHOICES = listOf(15, 30, 60, 180)

    /**
     * 跑一次定时同步。抽成普通函数（不埋在 Worker 里）是为了测试能直接喂
     * 内存库 + 真回环的 `SyncCoordinator`，把这条路径真的跑一遍。
     *
     * 返回 `ListenableWorker.Result`（不是 `kotlin.Result`）—— 直接交给 Worker 用。
     */
    suspend fun run(prefs: UiPrefs, coordinator: SyncCoordinator): ListenableWorker.Result {
        // 排任务和实际执行之间用户可能已经关掉了开关 —— 每次执行都重新确认一遍
        if (!prefs.autoSyncEnabled()) return ListenableWorker.Result.success()

        val parsed = parseSyncAddress(prefs.peerAddress())
        val key = prefs.syncKey()
        if (parsed == null || key.isBlank()) {
            val reason = if (parsed == null) {
                "auto sync is on but the host address is empty or invalid"
            } else {
                "auto sync is on but the shared key is empty"
            }
            prefs.saveAutoSyncResult(System.currentTimeMillis(), ok = false, message = reason)
            // 这里**不能**返回 failure：周期任务一旦 failure 就被取消，
            // 用户改好地址之后也不会自己恢复。返回 success，下个周期再试。
            return ListenableWorker.Result.success()
        }

        val (host, port) = parsed
        return coordinator.syncWith(host, port, key).fold(
            onSuccess = { session ->
                prefs.saveAutoSyncResult(
                    System.currentTimeMillis(),
                    ok = true,
                    message = "pulled ${session.pulled} (applied ${session.applied.changed}), " +
                        "pushed ${session.pushed} (host applied ${session.peerApplied}), " +
                        "conflicts ${session.conflicts}, images +${session.imagesPulled}/-${session.imagesPushed}",
                )
                ListenableWorker.Result.success()
            },
            onFailure = { error ->
                // 被局域网边界拦下（v1.10.1）不算「失败」：网络和对端本来就该在同一局域网，
                // 现在只是不该同步而已。记一条「跳过」，并且**不能**返回 retry ——
                // 手机在外面待几小时，退避重试会白耗电；周期任务本身到下个周期还会再来。
                if (error is LanBlockedException) {
                    prefs.saveAutoSyncResult(
                        System.currentTimeMillis(),
                        ok = false,
                        message = "skipped: " + (error.message ?: "not on the local network"),
                    )
                    return@fold ListenableWorker.Result.success()
                }
                prefs.saveAutoSyncResult(
                    System.currentTimeMillis(),
                    ok = false,
                    message = error.message ?: error.javaClass.simpleName,
                )
                // 失败就按退避重试（网络刚切走、主机还没起、密钥还没改对都会走到这里）
                ListenableWorker.Result.retry()
            },
        )
    }
}

/** 按设置排 / 撤周期任务：开着就排，关掉就取消。 */
object AutoSyncScheduler {

    /** 唯一任务名（测试要按它查任务在不在，所以是 public）。 */
    const val WORK_NAME = "knownote-auto-sync"

    /**
     * @param force 用户刚在界面上改了开关或间隔时传 true（取消重排，新间隔立刻生效）；
     *        应用启动时的自愈补排传 false（用 KEEP，不打断已经排好的节拍）。
     */
    fun apply(context: Context, enabled: Boolean, minutes: Int, force: Boolean = false) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val period = minutes.coerceAtLeast(AutoSync.MIN_INTERVAL_MINUTES).toLong()
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(period, TimeUnit.MINUTES)
            // 只在**不计费的网络**上跑（Wi-Fi / 以太网）：笔记本记在移动数据上被推出去
            // 正是这条规矩要防的事，索性连唤醒都不给它 —— 系统层直接拦住，比进了 doWork 再判断更省。
            // 注意：约束是**排任务时定下的**，改了约束必须用 UPDATE 覆盖已有任务，
            // 否则老设备上那份（CONNECTED）会一直生效 —— 见下面的 ExistingPeriodicWorkPolicy.UPDATE。
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            when {
                // 用户刚改了开关 / 间隔：取消重排，新节拍立刻生效
                force -> ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                // 应用启动时的自愈补排：用 UPDATE 而不是 KEEP —— 已经排好的节拍不动，
                // 但约束这类「规格」会被更新到最新（v1.10.0 用 KEEP，导致升级后约束改不掉）
                else -> ExistingPeriodicWorkPolicy.UPDATE
            },
            request,
        )
    }
}
