package com.xinjigalaxy.knownotes

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.db.FtsSchemaCallback
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.data.sync.SyncEngine
import com.xinjigalaxy.knownotes.data.sync.SyncNote
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
 * 同步引擎的行为锁定（需求文档 4.2 / 4.3）。
 *
 * 重点不是"能同步"，而是**冲突裁决必须收敛**：两台设备拿到同一对数据后，
 * 必须落到同一版，否则每次同步都会互相打回、永远不一致。
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {

    private val opened = ArrayList<AppDatabase>()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        db = newDb()
        repo = NoteRepository(db, "device-a")
        engine = SyncEngine(repo)
    }

    @After
    fun tearDown() {
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

    /** 建一条本机笔记并返回 (本地 id, guid)。 */
    private fun seedLocal(title: String = "本地标题", content: String = "本地正文"): Pair<Long, String> {
        val id = runBlocking { repo.saveNote(null, title, content, null, emptyList()) }
        val note = runBlocking { db.noteDao().byId(id) }!!
        return id to note.guid
    }

    @Test
    fun remoteNoteIsInsertedWithGroupResolvedByNameAndTagsLinked() {
        val remote = SyncNote(
            guid = "guid-1",
            title = "远端标题",
            content = "远端正文",
            group = "零散知识点",
            tags = listOf("标签1", "标签2"),
            createdAt = 100L,
            updatedAt = 200L,
            isDeleted = false,
            isPurged = false,
        )

        val result = runBlocking { engine.applyChanges(listOf(remote)) }

        assertEquals(1, result.inserted)
        val note = runBlocking { db.noteDao().byGuid("guid-1") }
        assertNotNull("远端新笔记应落库", note)
        assertEquals("远端标题", note!!.title)
        assertEquals("远端正文", note.content)
        assertEquals(100L, note.createdAt)
        assertEquals(200L, note.updatedAt)

        // 分组按名字解析并自动建出来
        val group = runBlocking { db.groupDao().byName("零散知识点") }
        assertNotNull("分组应自动创建", group)
        assertEquals(group!!.id, note.groupId)

        assertEquals(listOf("标签1", "标签2"), runBlocking { db.noteDao().tagNamesOf(note.id) })
    }

    @Test
    fun newerRemoteWinsAndOlderRemoteIsIgnored() {
        val (localId, guid) = seedLocal()
        val localUpdated = runBlocking { db.noteDao().byId(localId) }!!.updatedAt

        val newer = SyncNote(guid, "远端较新", "远端正文", null, emptyList(), 1L, localUpdated + 10_000, false, false)
        val first = runBlocking { engine.applyChanges(listOf(newer)) }
        assertEquals(1, first.updated)
        assertEquals("远端较新", runBlocking { db.noteDao().byId(localId) }!!.title)

        val older = SyncNote(guid, "远端较旧", "旧正文", null, emptyList(), 1L, localUpdated - 10_000, false, false)
        val second = runBlocking { engine.applyChanges(listOf(older)) }
        assertEquals(0, second.changed)
        assertEquals("本机较新时不该被旧版覆盖", "远端较新", runBlocking { db.noteDao().byId(localId) }!!.title)
    }

    /**
     * 时间戳打平但内容不同：两台设备各自应用对方的版本，最后必须落到同一份。
     * 这是「不做收敛裁决就会永远互相打回」的回归测试。
     */
    @Test
    fun equalTimestampsConvergeOnBothDevices() {
        val dbA = newDb()
        val dbB = newDb()
        val repoA = NoteRepository(dbA, "device-a")
        val repoB = NoteRepository(dbB, "device-b")
        val engineA = SyncEngine(repoA)
        val engineB = SyncEngine(repoB)

        val t = 1_700_000_000_000L
        val idA = runBlocking { repoA.saveNote(null, "同一份笔记", "来自 A 的正文", null, emptyList()) }
        val idB = runBlocking { repoB.saveNote(null, "同一份笔记", "来自 B 的正文", null, emptyList()) }
        val guidA = runBlocking { dbA.noteDao().byId(idA) }!!.guid

        // 把 B 那条伪装成"同一条笔记"（同 guid、同时间戳）
        val noteB = runBlocking { dbB.noteDao().byId(idB) }!!
        runBlocking { dbB.noteDao().update(noteB.copy(guid = guidA, updatedAt = t)) }
        val noteA = runBlocking { dbA.noteDao().byId(idA) }!!
        runBlocking { dbA.noteDao().update(noteA.copy(updatedAt = t)) }

        val fromB = SyncNote(guidA, "同一份笔记", "来自 B 的正文", null, emptyList(), t, t, false, false)
        val fromA = SyncNote(guidA, "同一份笔记", "来自 A 的正文", null, emptyList(), t, t, false, false)

        runBlocking {
            engineA.applyChanges(listOf(fromB))
            engineB.applyChanges(listOf(fromA))
        }

        val contentA = runBlocking { dbA.noteDao().byGuid(guidA) }!!.content
        val contentB = runBlocking { dbB.noteDao().byGuid(guidA) }!!.content
        assertEquals("时间戳打平后两端必须收敛到同一版", contentA, contentB)
    }

    @Test
    fun softDeleteAndPurgePropagateThroughSync() {
        val (localId, guid) = seedLocal("要删的笔记", "x")
        val base = runBlocking { db.noteDao().byId(localId) }!!.updatedAt
        assertEquals(1, runBlocking { db.noteDao().observeLiveWithTags().first() }.size)

        // 远端软删除 → 本机列表里消失、进回收站
        runBlocking {
            engine.applyChanges(
                listOf(SyncNote(guid, "要删的笔记", "x", null, emptyList(), 1L, base + 1_000, true, false))
            )
        }
        assertEquals("软删除应同步生效", 0, runBlocking { db.noteDao().observeLiveWithTags().first() }.size)
        assertEquals(1, runBlocking { db.noteDao().observeDeletedWithTags().first() }.size)

        // 远端彻底删除（墓碑）→ 回收站里也该消失，但行还在（否则对端又会把它推回来）
        runBlocking {
            engine.applyChanges(
                listOf(SyncNote(guid, "要删的笔记", "x", null, emptyList(), 1L, base + 2_000, true, true))
            )
        }
        assertEquals("墓碑不该出现在回收站", 0, runBlocking { db.noteDao().observeDeletedWithTags().first() }.size)
        val row = runBlocking { db.noteDao().byGuid(guid) }
        assertNotNull("墓碑行必须保留（物理删掉会被对端复活）", row)
        assertTrue(row!!.isPurged)
        assertEquals(0, runBlocking { db.noteDao().deletedCount() })
    }

    @Test
    fun localPurgeLeavesTombstoneAndGetsSyncOut() {
        val (localId, guid) = seedLocal("本地彻底删", "y")

        runBlocking { repo.purgeNote(localId) }

        val row = runBlocking { db.noteDao().byId(localId) }
        assertNotNull("彻底删除后行仍在（墓碑）", row)
        assertTrue(row!!.isPurged)
        assertTrue(row.isDeleted)
        assertEquals("回收站计数要把墓碑排除掉", 0, runBlocking { db.noteDao().deletedCount() })

        val outgoing = runBlocking { engine.collectChanges(0) }
        val sent = outgoing.firstOrNull { it.guid == guid }
        assertNotNull("删除意图必须能被同步带出去", sent)
        assertTrue(sent!!.isPurged)
    }

    @Test
    fun collectChangesIsIncremental() {
        seedLocal("一", "")
        seedLocal("二", "")
        assertEquals(2, runBlocking { engine.collectChanges(0) }.size)

        Thread.sleep(5)
        val watermark = System.currentTimeMillis()
        assertEquals("水位线之后的调用应为空", 0, runBlocking { engine.collectChanges(watermark) }.size)

        Thread.sleep(5)
        seedLocal("三", "")
        val delta = runBlocking { engine.collectChanges(watermark) }
        assertEquals(1, delta.size)
        assertEquals("三", delta.first().title)
    }

    /** 主机中转从机的变更时必须记变更日志，否则第二个从机永远拉不到第一个从机改的内容。 */
    @Test
    fun hostRelaysSlaveChangesButSlaveDoesNotRelogThem() {
        val fromSlave = SyncNote("relay-1", "从机写的", "x", null, emptyList(), 1L, 5_000L, false, false)
        runBlocking { engine.applyChanges(listOf(fromSlave), originDevice = "device-b") }
        assertEquals("主机中转要记一条变更日志", 1, runBlocking { db.changeLogDao().count() })
        assertEquals(1, runBlocking { engine.collectChanges(0) }.size)

        val fromHost = SyncNote("relay-2", "主机自己的", "y", null, emptyList(), 1L, 6_000L, false, false)
        runBlocking { engine.applyChanges(listOf(fromHost)) }
        assertEquals("从机侧收到远端不记日志，避免把自己的库当新变更推回去", 1, runBlocking { db.changeLogDao().count() })
    }

    @Test
    fun purgedNoteFromRemoteIsIgnoredWhenLocalHasNothing() {
        val result = runBlocking {
            engine.applyChanges(
                listOf(SyncNote("ghost", "不存在的", "", null, emptyList(), 1L, 1L, true, true))
            )
        }
        assertEquals("本机没有这条，墓碑无事可做", 0, result.changed)
        assertNull(runBlocking { db.noteDao().byGuid("ghost") })
    }
}
