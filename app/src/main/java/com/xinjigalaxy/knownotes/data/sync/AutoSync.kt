package com.xinjigalaxy.knownotes.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
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
                // 「不该同步」与「同步失败」要分开记（v1.10.3）：
                // 被局域网边界拦下（v1.10.1）、或对端与本机不在同一个局域网（v1.10.3）
                // 都不是故障 —— 换个网络自然会好，用户只需要知道原因。
                val message = when (error) {
                    is LanBlockedException -> "skipped: " + (error.message ?: "not on the local network")
                    is OffLanException -> "skipped: " + (error.message ?: "peer is on another network")
                    else -> error.message ?: error.javaClass.simpleName
                }
                prefs.saveAutoSyncResult(System.currentTimeMillis(), ok = false, message = message)
                // 失败**不**返回 retry（v1.10.3）：周期任务到下个周期自己会再来，而 retry 的
                // 指数退避会把下一次推到几小时后 —— 那正是「开着自动同步却半天不动」的来源之一。
                ListenableWorker.Result.success()
            },
        )
    }
}

/** 按设置排 / 撤周期任务：开着就排，关掉就取消。 */
object AutoSyncScheduler {

    /** 唯一任务名（测试要按它查任务在不在，所以是 public）。 */
    const val WORK_NAME = "knownote-auto-sync"

    /** 周期任务在系统里的状态（界面显示用）。 */
    enum class State {
        /** 没排上（开关关着，或被系统的配额 / 后台限制清掉了）。 */
        NOT_SCHEDULED,

        /** 已排上，正等下一个周期。 */
        SCHEDULED,

        /** 此刻正在执行。 */
        RUNNING,

        /** 任务已结束（失败 / 被取消）—— 周期任务不该停在这里。 */
        FINISHED,
    }

    /**
     * @param force 用户刚在界面上改了开关或间隔时传 true（取消重排，新间隔立刻生效）；
     *        应用启动时的自愈补排传 false（用 UPDATE，不打断已经排好的节拍）。
     */
    fun apply(context: Context, enabled: Boolean, minutes: Int, force: Boolean = false) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val period = minutes.coerceAtLeast(AutoSync.MIN_INTERVAL_MINUTES).toLong()
        // **不设网络约束**（v1.10.3）。
        //
        // v1.10.2 及以前要求 UNMETERED（不计费网络），出发点是不想在移动数据上把笔记推出去。
        // 实际后果是：手机热点、被系统标成计费的 Wi-Fi 全都不是「不计费网络」，任务永远处于
        // 「等约束满足」，一次都不执行，而界面上只留一个停在开关那一刻的时间 —— 查了两台设备
        // 的 WorkSpec（period_count=0）与 JobScheduler（Unsatisfied constraints: CONNECTIVITY）
        // 才看出原因。局域网直连本来就不产生流量费用，真正该拦的是「对端不在同一个局域网」，
        // 那由 LanGuard / OffLan 判定负责（会明确告诉用户原因），不该交给系统的网络类型约束。
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(period, TimeUnit.MINUTES).build()
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

    /**
     * 查周期任务现在是什么状态。界面用它回答「到底排上了没有」——
     * v1.10.2 的问题正是这里完全不可见。
     */
    suspend fun state(context: Context): State =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .await()
            .firstOrNull()
            ?.state
            ?.let { info ->
                when (info) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> State.SCHEDULED
                    WorkInfo.State.RUNNING -> State.RUNNING
                    // 被 cancelUniqueWork 撤掉的任务，WorkManager 会把那条记录留在库里（状态 CANCELLED）——
                    // 从「排上了没有」的角度看它就是「没排上」，别显示成「已结束」吓人。
                    WorkInfo.State.CANCELLED -> State.NOT_SCHEDULED
                    else -> State.FINISHED
                }
            }
            ?: State.NOT_SCHEDULED
}
