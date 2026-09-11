package com.xinjigalaxy.knownotes

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.export.Exporter
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.data.sync.SyncClient
import com.xinjigalaxy.knownotes.data.sync.SyncCoordinator
import com.xinjigalaxy.knownotes.data.sync.SyncEngine
import com.xinjigalaxy.knownotes.data.sync.SyncServer
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
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    val uiPrefs = UiPrefs(appContext)
    val repository = NoteRepository(database, deviceId)
    val exporter = Exporter(appContext, repository)

    /** 同步引擎与主机服务挂在**应用级**作用域上：切页面不该把主机服务带下线。 */
    val syncEngine = SyncEngine(repository)
    val syncServer = SyncServer(repository, syncEngine, deviceId, deviceName)
    val syncClient = SyncClient(deviceId, deviceName)
    val syncCoordinator = SyncCoordinator(repository, syncEngine, syncClient)

    fun appScope(): CoroutineScope = scope

    init {
        scope.launch {
            // 触发首次打开：建表 → 建 FTS5 虚拟表 → 索引自愈，并落一条 sync_meta
            repository.ensureDeviceMeta()
            // 上次退出前开着主机的话，这次进入应用时恢复监听（用户明确选过才算）
            val key = uiPrefs.syncKey()
            if (uiPrefs.hostAutoStart() && key.isNotBlank()) {
                syncServer.start(scope, uiPrefs.hostPort(), key)
            }
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
