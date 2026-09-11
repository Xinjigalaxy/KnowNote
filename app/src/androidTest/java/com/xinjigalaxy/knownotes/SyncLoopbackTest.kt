package com.xinjigalaxy.knownotes

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.db.FtsSchemaCallback
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
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

    @Before
    fun setUp() {
        hostDb = newDb()
        clientDb = newDb()
        hostRepo = NoteRepository(hostDb, "device-host")
        clientRepo = NoteRepository(clientDb, "device-client")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = SyncServer(hostRepo, SyncEngine(hostRepo), "device-host", "主机测试机")
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
        assertTrue("应把主机的拒绝原因透出来：${outcome.error}", outcome.error!!.contains("密钥"))
    }

    @Test
    fun twoWaySyncOverRealSockets() {
        val port = server.start(scope, 0, KEY).getOrThrow()
        val client = SyncClient("device-client", "从机测试机")
        val clientEngine = SyncEngine(clientRepo)
        // 从机侧走的就是页面上那条路径（SyncCoordinator），日志 / 水位线才会被真的写到
        val coordinator = SyncCoordinator(clientRepo, clientEngine, client)

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
}
