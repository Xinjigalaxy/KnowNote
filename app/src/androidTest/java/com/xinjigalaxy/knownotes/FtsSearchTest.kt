package com.xinjigalaxy.knownotes

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.db.FtsSchemaCallback
import com.xinjigalaxy.knownotes.data.db.FtsStore
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 在设备/模拟器的真实 SQLite 上验证检索能力（需求文档 3.1 的地基）。
 *
 * 关键背景：**Android 系统 SQLite 不保证编译了 FTS5**。实测 Android 15 上
 * `no such module: fts5`，所以应用按 FTS5 → FTS4 → LIKE 降级。
 * 这些用例因此断言"有全文引擎可用 + 语义正确"，而不是硬绑定 FTS5。
 */
@RunWith(AndroidJUnit4::class)
class FtsSearchTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(FtsSchemaCallback)
            .allowMainThreadQueries()
            .build()
        // 强制打开一次，让 FtsSchemaCallback 完成引擎探测（否则首个用例读到的是未探测状态）
        db.openHelper.writableDatabase
        repo = NoteRepository(db, deviceId = "test-device")
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 诊断用例：把平台的真实能力打进 logcat（adb logcat -s KnowNoteProbe）。 */
    @Test
    fun platformFullTextCapability() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "fts-probe.db")
        file.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            var sqliteVersion = "unknown"
            raw.rawQuery("SELECT sqlite_version()", null).use { c ->
                if (c.moveToFirst()) sqliteVersion = c.getString(0)
            }
            val options = ArrayList<String>()
            raw.rawQuery("PRAGMA compile_options", null).use { c ->
                while (c.moveToNext()) options += c.getString(0)
            }
            val fts5Error = runCatching {
                raw.execSQL("CREATE VIRTUAL TABLE probe5 USING fts5(a)")
            }.exceptionOrNull()?.message
            val fts4Error = runCatching {
                raw.execSQL("CREATE VIRTUAL TABLE probe4 USING fts4(a, tokenize=simple)")
            }.exceptionOrNull()?.message

            val report = buildString {
                append("sqlite=$sqliteVersion")
                append(" | FTS5=").append(fts5Error ?: "OK")
                append(" | FTS4=").append(fts4Error ?: "OK")
                append(" | FTS compile_options=")
                append(options.filter { it.contains("FTS", ignoreCase = true) })
            }
            Log.i("KnowNoteProbe", report)
            println(report)

            assertTrue(
                "系统 SQLite 既不支持 FTS5 也不支持 FTS4：$report",
                fts5Error == null || fts4Error == null,
            )
        } finally {
            raw.close()
            file.delete()
        }
    }

    @Test
    fun aFullTextEngineIsActive() {
        assertTrue(
            "没有可用的全文检索引擎，检索会退化为 LIKE 扫描",
            repo.searchEngineIsFullText,
        )
        Log.i("KnowNoteProbe", "生效引擎 = ${repo.searchEngineLabel}, 探测错误 = ${repo.searchEngineProbeError}")
    }

    @Test
    fun chineseSubstringSearchHitsNotJustPrefix() = runBlocking {
        val dbNoteId = repo.saveNote(
            noteId = null,
            title = "SQLite 全文检索",
            content = "给零碎知识点做检索，中文按字切分",
            groupId = null,
            tagNames = listOf("数据库", "全文检索"),
        )
        repo.saveNote(
            noteId = null,
            title = "Kotlin 协程",
            content = "suspend 函数与结构化并发",
            groupId = null,
            tagNames = listOf("Kotlin"),
        )

        // 「检索」在标题/正文里都不是词首，靠子串命中
        assertEquals(listOf(dbNoteId), repo.searchIds("检索"))

        // 拉丁词大小写不敏感 + 前缀命中
        assertEquals(1, repo.searchIds("sql").size)
        assertEquals(1, repo.searchIds("SQLITE").size)

        // 标签同样进索引（FTS 与 LIKE 两条路径都必须覆盖）
        assertEquals(1, repo.searchIds("Kotlin").size)
        assertEquals(1, repo.searchIds("数据库").size)

        // 多词元 AND
        assertEquals(1, repo.searchIds("检索 零碎").size)
        assertEquals(0, repo.searchIds("检索 不存在的词").size)
    }

    @Test
    fun deletedNoteLeavesTheIndex() = runBlocking {
        val id = repo.saveNote(null, "待删除的知识点", "临时内容", null, listOf("临时"))
        assertEquals(1, repo.searchIds("临时").size)
        repo.deleteNote(id)
        assertEquals(0, repo.searchIds("临时").size)
        repo.restoreNote(id)
        assertEquals(1, repo.searchIds("临时").size)
    }

    @Test
    fun tagRenameKeepsIndexConsistent() = runBlocking {
        val id = repo.saveNote(null, "标签改名测试", "正文", null, listOf("旧标签"))
        assertEquals(1, repo.searchIds("旧标签").size)

        val tagId = db.tagDao().byName("旧标签")!!.id
        repo.renameTag(tagId, "新标签")

        assertEquals(0, repo.searchIds("旧标签").size)
        assertEquals(1, repo.searchIds("新标签").size)
        assertEquals(id, repo.searchIds("新标签").first())
    }

    @Test
    fun mergeTagsRepointsAndReindexes() = runBlocking {
        repo.saveNote(null, "合并测试", "正文", null, listOf("A标签"))
        repo.saveNote(null, "另一个", "正文", null, listOf("B标签"))

        val from = db.tagDao().byName("A标签")!!.id
        val to = db.tagDao().byName("B标签")!!.id
        repo.mergeTags(from, to)

        assertEquals(2, repo.searchIds("B标签").size)
        assertEquals(0, repo.searchIds("A标签").size)
        assertNull("合并后源标签应被删除", db.tagDao().byName("A标签"))
    }

    @Test
    fun engineProbeMatchesWhatIsInstalled() {
        // 探测结果不应是"没探测过"的状态
        assertTrue(FtsStore.probeError != null || repo.searchEngineIsFullText)
    }
}
