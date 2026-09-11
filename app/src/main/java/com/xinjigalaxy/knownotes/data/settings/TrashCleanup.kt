package com.xinjigalaxy.knownotes.data.settings

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xinjigalaxy.knownotes.KnowNoteApp
import java.util.concurrent.TimeUnit

/**
 * 回收站定时清理（设置页可开关）。
 *
 * 为什么用 WorkManager 而不是「打开应用时顺手清一下」：定时清理的价值在于**应用没打开时也生效**，
 * 这是 Android 上唯一被允许的常规做法（从 Android 6 起后台 Service 基本被掐、Android 9 起
 * 隐式广播也没了）。最小周期是 15 分钟，这里每天一次 —— 清理回收站不需要更勤。
 */
class TrashCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? KnowNoteApp)?.container ?: return Result.success()
        val settings = container.settings.state.value
        // 排任务和实际执行之间用户可能已经关掉了开关 —— 每次执行都重新确认一遍
        if (!settings.autoPurgeTrash) return Result.success()

        return runCatching {
            val removed = container.repository.purgeTrashOlderThan(settings.trashRetentionDays)
            container.uiPrefs.saveLastTrashPurge(System.currentTimeMillis(), removed)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

/** 按设置排 / 撤周期任务。开着就排（已存在不重复排），关掉就取消。 */
object TrashCleanupScheduler {

    private const val WORK_NAME = "knownote-trash-cleanup"

    fun apply(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS)
            // 电量低就别折腾了，等条件满足自然会跑
            .setConstraints(
                Constraints.Builder().setRequiresBatteryNotLow(true).build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
