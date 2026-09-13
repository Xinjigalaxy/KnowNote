package com.xinjigalaxy.knownotes

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.db.FtsSchemaCallback
import com.xinjigalaxy.knownotes.data.media.ImageStore
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.data.sync.SyncClient
import com.xinjigalaxy.knownotes.data.sync.SyncCoordinator
import com.xinjigalaxy.knownotes.data.sync.SyncEngine
import com.xinjigalaxy.knownotes.data.sync.SyncServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真回环测试：**真的起一个 ServerSocket、真的用 HttpURLConnection 连过去**，
 * 两台"设备"是两个独立的库。
 *
 * 这一层能抓到手写 HTTP 实现里最容易错的东西：请求头大小写、Content-Length 读取、
 * 状态码分支、JSON 编解码往返、以及清单里忘了 INTERNET / usesCleartextTraffic 时
 * 系统直接把请求拦掉（那种失败在代码里看起来像"连不上"）。
 */
@RunWith(AndroidJUnit4::class)
class SyncLoopbackTest {

    private val opened = ArrayList<AppDatabase>()
    private lateinit var hostDb: AppDatabase
    private lateinit var clientDb: AppDatabase
    private lateinit var hostRepo: NoteRepository
    private lateinit var clientRepo: NoteRepository
    private lateinit var server: SyncServer
    private lateinit var scope: CoroutineScope
    /** 两台「设备」各持一份内存图片仓库 —— 刻意不共享，才能真正验证协议把图传过去了。 */
    private lateinit var hostImages: MemoryImageStore
    private lateinit var clientImages: MemoryImageStore
    private lateinit var prefs: UiPrefs

    @Before
    fun setUp() {
        hostDb = newDb()
        clientDb = newDb()
        hostRepo = NoteRepository(hostDb, "device-host")
        clientRepo = NoteRepository(clientDb, "device-client")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        hostImages = MemoryImageStore()
        clientImages = MemoryImageStore()
        prefs = UiPrefs(InstrumentationRegistry.getInstrumentation().targetContext)
        server = SyncServer(hostRepo, SyncEngine(hostRepo), "device-host", "主机测试机", hostImages)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun newDb(): AppDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(FtsSchemaCallback)
            .allowMainThreadQueries()
            .build()
        database.openHelper.writableDatabase
        opened += database
        return database
    }

    @Test
    fun serverRefusesToStartWithoutSharedKey() {
        val result = server.start(scope, 0, "   ")
        assertTrue("没有共享密钥就不该监听端口", result.isFailure)
    }

    @Test
    fun wrongKeyIsRejectedWithMessage() {
        val port = server.start(scope, 0, KEY).getOrThrow()

        val outcome = runBlocking {
            SyncClient("device-client", "从机测试机").sync("127.0.0.1", port, "wrongkey", 0L, emptyList())
        }

        assertNull(outcome.response)
        assertNotNull(outcome.error)
        // 错密钥的提示是主机回的错误详情，走的是技术诊断（英文），这里不锁具体措辞只锁语义
        assertTrue("应把主机的拒绝原因透出来：${outcome.error}", outcome.error!!.contains("key", ignoreCase = true))
    }

    @Test
    fun twoWaySyncOverRealSockets() {
        val port = server.start(scope, 0, KEY).getOrThrow()
        val client = SyncClient("device-client", "从机测试机")
        val clientEngine = SyncEngine(clientRepo)
        // 从机侧走的就是页面上那条路径（SyncCoordinator），日志 / 水位线才会被真的写到
        val coordinator = SyncCoordinator(clientRepo, clientEngine, client, clientImages, prefs)

        assertTrue("ping 应当通", runBlocking { client.ping("127.0.0.1", port) })

        // ---- ① 主机先有一条笔记，从机同步拉过去 ----
        runBlocking { hostRepo.saveNote(null, "主机上的笔记", "主机正文", null, listOf("主标签")) }

        val first = runBlocking { coordinator.syncWith("127.0.0.1", port, KEY) }
        assertTrue("第一次同步应当成功：${first.exceptionOrNull()?.message}", first.isSuccess)
        val firstSession = first.getOrThrow()
        assertEquals("主机应把那条笔记发过来", 1, firstSession.pulled)
        assertEquals(1, firstSession.applied.inserted)
        assertEquals("主机测试机", firstSession.peerName)
        assertEquals("本机没有东西要推", 0, firstSession.pushed)
        assertEquals("水位线要写进 sync_meta", firstSession.watermark, runBlocking { clientRepo.lastSyncAt() })

        val clientNotes = runBlocking { clientDb.noteDao().observeLiveWithTags().first() }
        assertEquals(1, clientNotes.size)
        assertEquals("主机上的笔记", clientNotes.first().note.title)
        assertEquals("标签应随笔记一起过来", listOf("主标签"), clientNotes.first().tags.map { it.name })

        // ---- ② 从机新建一条，再同步一次：推给主机，且不该把自己刚推的又拉回来 ----
        runBlocking { clientRepo.saveNote(null, "从机上的笔记", "从机正文", null, emptyList()) }

        val second = runBlocking { coordinator.syncWith("127.0.0.1", port, KEY) }.getOrThrow()
        assertEquals("从机应把自己那条推上去", 1, second.pushed)
        assertEquals("主机不该把从机刚推的变更回声回来", 0, second.pulled)
        assertEquals("主机应落库从机推来的那条", 1, second.peerApplied)

        val hostNotes = runBlocking { hostDb.noteDao().allOnce() }
        assertEquals(2, hostNotes.size)
        assertTrue(hostNotes.any { it.title == "从机上的笔记" })

        // ---- ③ 幂等性：水位线已推进，再同步一次没有任何东西要传 ----
        val third = runBlocking { coordinator.syncWith("127.0.0.1", port, KEY) }.getOrThrow()
        assertEquals("水位线推进后本地无增量", 0, third.pushed)
        assertEquals("重复同步不该新增笔记", 2, runBlocking { hostDb.noteDao().allOnce() }.size)
        assertEquals(2, runBlocking { clientDb.noteDao().allOnce() }.size)

        // ---- 双方都留下了同步日志（3 次同步各一条） ----
        val clientLog = runBlocking { clientDb.syncLogDao().recent(10) }
        assertEquals(3, clientLog.size)
        assertTrue(clientLog.all { it.role == SyncLogEntry.ROLE_CLIENT && it.ok })
        assertTrue(
            "主机端也要有日志",
            runBlocking { hostDb.syncLogDao().recent(10) }.all { it.role == SyncLogEntry.ROLE_HOST },
        )
    }

    private companion object {
        const val KEY = "testkey123"
    }

    // ---------- 图片同步（v1.9.0）----------

    private fun newCoordinator() = SyncCoordinator(
        clientRepo,
        SyncEngine(clientRepo),
        SyncClient("device-client", "从机测试机"),
        clientImages,
        prefs,
    )

    /** 从机 → 主机：图要真的过去（同名同内容），而且第二次同步不该重复传。 */
    @Test
    fun imagesTravelFromClientToHost() {
        val port = server.start(scope, 0, KEY).getOrThrow()
        val coordinator = newCoordinator()

        val bytes = ByteArray(48 * 1024) { (it % 251).toByte() }
        clientImages.put("img_alpha.jpg", bytes)
        runBlocking { clientRepo.saveNote(null, "带图笔记", "看图：\n\n![图](img:img_alpha.jpg)\n", null, emptyList()) }

        val session = runBlocking { coordinator.syncWith("127.0.0.1", port, KEY) }.getOrThrow()
        assertEquals("从机应把图推上去", 1, session.imagesPushed)
        assertEquals(0, session.imagesPulled)
        assertTrue("主机侧应有这张图", hostImages.names().contains("img_alpha.jpg"))
        assertArrayEquals(bytes, hostImages.read("img_alpha.jpg"))

        val again = runBlocking { coordinator.syncWith("127.0.0.1", port, KEY) }.getOrThrow()
        assertEquals("对端已有就不该重复传", 0, again.imagesPushed)
    }

    /** 主机 → 从机：反方向也要通。 */
    @Test
    fun imagesTravelFromHostToClient() {
        val port = server.start(scope, 0, KEY).getOrThrow()
        val coordinator = newCoordinator()

        val bytes = ByteArray(32 * 1024) { (it * 7 % 253).toByte() }
        hostImages.put("img_beta.jpg", bytes)
        runBlocking { hostRepo.saveNote(null, "主机带图", "看图\n\n![图](img:img_beta.jpg)\n", null, emptyList()) }

        val session = runBlocking { coordinator.syncWith("127.0.0.1", port, KEY) }.getOrThrow()
        assertEquals("应从主机拉下一张图", 1, session.imagesPulled)
        assertArrayEquals(bytes, clientImages.read("img_beta.jpg"))
    }

    /** 图多于一单批上限（6 张）时要自动多轮传完。 */
    @Test
    fun manyImagesFinishInSeveralRounds() {
        val port = server.start(scope, 0, KEY).getOrThrow()
        val coordinator = newCoordinator()

        repeat(8) { index -> clientImages.put("img_$index.jpg", ByteArray(1024) { index.toByte() }) }

        val session = runBlocking { coordinator.syncWith("127.0.0.1", port, KEY) }.getOrThrow()
        assertEquals("8 张应全部送达", 8, session.imagesPushed)
        assertTrue("8 张要跑不止一轮：${session.rounds}", session.rounds >= 2)
        assertEquals(8, hostImages.names().count { it.startsWith("img_") })
    }

}

/** 内存版图片仓库：回环测试用，不碰真实文件系统。 */
private class MemoryImageStore : ImageStore {

    private val files = LinkedHashMap<String, ByteArray>()

    override fun names(): Set<String> = files.keys.toSet()

    override fun read(name: String): ByteArray? = files[name]

    override fun write(name: String, bytes: ByteArray): Boolean =
        if (files.containsKey(name)) false else {
            files[name] = bytes
            true
        }

    /** 测试里直接放一张图进去（相当于「这台设备本来就有这张图」）。 */
    fun put(name: String, bytes: ByteArray) {
        files[name] = bytes
    }
}
