package com.xinjigalaxy.knownotes

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.provider.Settings
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.export.Exporter
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

class KnowNoteApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 手写依赖容器（demo 不引 Hilt，保持依赖精简；上层只认 Repository 接口）。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = AppDatabase.get(appContext)
    val deviceId: String = resolveDeviceId(appContext)
    val repository = NoteRepository(database, deviceId)
    val exporter = Exporter(appContext, repository)

    init {
        scope.launch {
            // 触发首次打开：建表 → 建 FTS5 虚拟表 → 索引自愈，并落一条 sync_meta
            repository.ensureDeviceMeta()
        }
    }

    /** 设备标识：优先 ANDROID_ID，落 SharedPreferences 保持稳定（需求文档 2.1 sync_meta）。 */
    @SuppressLint("HardwareIds")
    private fun resolveDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val value = "android-" + (androidId ?: UUID.randomUUID().toString())
        prefs.edit().putString(KEY_DEVICE_ID, value).apply()
        return value
    }

    private companion object {
        const val PREFS = "knownotes_prefs"
        const val KEY_DEVICE_ID = "device_id"
    }
}
