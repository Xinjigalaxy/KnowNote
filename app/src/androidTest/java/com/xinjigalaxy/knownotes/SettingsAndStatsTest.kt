package com.xinjigalaxy.knownotes

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.db.FtsSchemaCallback
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.data.settings.AppSettings
import com.xinjigalaxy.knownotes.data.settings.FontScale
import com.xinjigalaxy.knownotes.data.settings.READ_FONT_DEFAULT
import com.xinjigalaxy.knownotes.data.settings.ReadMode
import com.xinjigalaxy.knownotes.data.settings.TextColorOption
import com.xinjigalaxy.knownotes.data.settings.ThemeMode
import com.xinjigalaxy.knownotes.data.search.SearchScope
import com.xinjigalaxy.knownotes.data.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置与概览统计（v1.4.0）。
 *
 * 用户反馈：进「回收站」子页面操作完返回「更多」，那行「N 条已删除笔记」还是旧数字。
 * 根因是概览用的是一次性查询。这里的第一个测试就是那个 bug 的回归 ——
 * 它**订阅 flow**、边操作边看有没有新值推出来，而不是每次重新调一次查询
 * （后者即使实现是一次性的也会"通过"，等于没测）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsAndStatsTest {

    private val opened = ArrayList<AppDatabase>()
    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(FtsSchemaCallback)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase
        opened += db
        repo = NoteRepository(db, "device-test")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return true
            Thread.sleep(30)
        }
        return check()
    }

    /** 概览统计必须**随操作实时推送**，不能等页面自己刷新。 */
    @Test
    fun statsFlowPushesTrashCountWithoutManualRefresh() {
        val deletedSeen = java.util.Collections.synchronizedList(ArrayList<Int>())
        val liveSeen = java.util.Collections.synchronizedList(ArrayList<Int>())

        // 注意别用 runBlocking 起常驻收集者：runBlocking 会等**所有子协程**结束才返回，
        // 而 collect 一个永不结束的 flow → 测试直接挂死（这条测试第一版就是这么坑的）。
        // 用独立的 Scope，测完 cancel。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            repo.observeStats().collect {
                deletedSeen.add(it.deleted)
                liveSeen.add(it.notes)
            }
        }

        try {
            assertTrue("初始一次也要推出来", awaitUntil { deletedSeen.isNotEmpty() })
            assertEquals(0, deletedSeen.last())

            val id = runBlocking { repo.saveNote(null, "会被删的", "x", null, emptyList()) }
            assertTrue("新增笔记后 notes 应变成 1", awaitUntil { liveSeen.lastOrNull() == 1 })

            runBlocking { repo.deleteNote(id) }
            assertTrue(
                "软删除后回收站计数应自动变成 1（这就是那个 bug）",
                awaitUntil { deletedSeen.lastOrNull() == 1 },
            )

            runBlocking { repo.restoreNote(id) }
            assertTrue("恢复后回收站计数应回到 0", awaitUntil { deletedSeen.lastOrNull() == 0 })
            assertTrue("恢复后 notes 应回到 1", awaitUntil { liveSeen.lastOrNull() == 1 })
        } finally {
            scope.cancel()
        }
    }

    /** 定时清理只清「够老」的，且必须留墓碑 —— 否则同步会把它们推回来。 */
    @Test
    fun purgeOldTrashOnlyRemovesOldEnoughOnesAndKeepsTombstones() {
        val older = runBlocking { repo.saveNote(null, "很久以前删的", "x", null, emptyList()) }
        val newer = runBlocking { repo.saveNote(null, "刚删的", "y", null, emptyList()) }

        runBlocking { repo.deleteNote(older) }
        Thread.sleep(1_100) // 把两条的 updated_at 拉开，才能按时间切分
        runBlocking { repo.deleteNote(newer) }

        val cutoff = runBlocking { db.noteDao().byId(newer) }!!.updatedAt

        val removed = runBlocking { repo.purgeTrashBefore(cutoff) }

        assertEquals("只有更老的那条该被清理", 1, removed)
        assertTrue("彻底删除必须留墓碑", runBlocking { db.noteDao().byId(older) }!!.isPurged)
        assertEquals("刚删的那条还在回收站", 1, runBlocking { db.noteDao().deletedCount() })
        assertEquals(1, runBlocking { db.noteDao().observeDeletedWithTags().first() }.size)

        val outgoing = runBlocking { SyncEngine(repo).collectChanges(0) }
        assertTrue(
            "定时清理也要能同步给对端（墓碑 + 变更日志）",
            outgoing.any { it.isPurged },
        )
    }

    /** 保留天数 = 0 天时，等同于把回收站整个清掉。 */
    @Test
    fun purgeWithZeroRetentionClearsWholeTrash() {
        val id = runBlocking { repo.saveNote(null, "删掉", "z", null, emptyList()) }
        runBlocking { repo.deleteNote(id) }

        val removed = runBlocking { repo.purgeTrashBefore(System.currentTimeMillis() + 1_000) }

        assertEquals(1, removed)
        assertEquals(0, runBlocking { db.noteDao().deletedCount() })
    }

    /** 设置要真的落盘并能重新读出来（换肤靠它）。 */
    @Test
    fun settingsRoundTripThroughPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(UiPrefs(context))

        settings.setThemeMode(ThemeMode.DARK)
        settings.setDynamicColor(true)
        settings.setAutoPurgeTrash(true)
        settings.setTrashRetentionDays(7)
        settings.setFontScale(FontScale.LARGE)
        settings.setTextColor(TextColorOption.SEPIA)

        val reloaded = AppSettings(UiPrefs(context)).state.value
        assertEquals(ThemeMode.DARK, reloaded.themeMode)
        assertTrue(reloaded.dynamicColor)
        assertTrue(reloaded.autoPurgeTrash)
        assertEquals(7, reloaded.trashRetentionDays)
        assertEquals("字号要真的落盘（换 Theme 靠它）", FontScale.LARGE, reloaded.fontScale)
        assertEquals("文字颜色要真的落盘", TextColorOption.SEPIA, reloaded.textColor)

        // 检索范围存的是 pref 名，重新读出来必须还原成同一组
        val prefs = UiPrefs(context)
        prefs.saveSearchScopes(SearchScope.toPrefs(setOf(SearchScope.TAGS)))
        assertEquals(
            setOf(SearchScope.TAGS),
            SearchScope.fromPrefs(UiPrefs(context).searchScopesOrNull()),
        )

        // 阅读页设置（v1.7.0）：字号倍率 + 展示方式，只作用于阅读页
        prefs.saveReadFontScale(1.4f)
        prefs.saveReadMode(ReadMode.TEXT.prefName)
        val reopened = UiPrefs(context)
        assertEquals(1.4f, reopened.readFontScale(), 0.001f)
        assertEquals(ReadMode.TEXT, ReadMode.fromName(reopened.readModeOrNull()))
        assertEquals("没设置过时默认渲染 Markdown", ReadMode.MD, ReadMode.fromName(null))
        prefs.saveReadFontScale(READ_FONT_DEFAULT)
        prefs.saveReadMode(ReadMode.MD.prefName)

        // 复原，别把应用偏好留在测试改过的状态
        settings.setThemeMode(ThemeMode.SYSTEM)
        settings.setDynamicColor(false)
        settings.setAutoPurgeTrash(false)
        settings.setTrashRetentionDays(AppSettings.DEFAULT_RETENTION_DAYS)
        settings.setFontScale(FontScale.NORMAL)
        settings.setTextColor(TextColorOption.THEME)
        prefs.saveSearchScopes(SearchScope.toPrefs(SearchScope.ALL))
        assertEquals(ThemeMode.SYSTEM, AppSettings(UiPrefs(context)).state.value.themeMode)
    }
}
