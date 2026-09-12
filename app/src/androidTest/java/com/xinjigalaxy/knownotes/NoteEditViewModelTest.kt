package com.xinjigalaxy.knownotes

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.db.FtsSchemaCallback
import com.xinjigalaxy.knownotes.data.prefs.UiPrefs
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import com.xinjigalaxy.knownotes.ui.note.NoteEditViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 编辑页的保存语义（v1.2.0 修的「标签创建失败」回归）。
 *
 * viewModelScope 走 Dispatchers.Main，在 instrumentation 进程里主 looper 正常运行，
 * 所以这里能直接驱动真实的 ViewModel（回调用 latch 等，避免依赖时序）。
 */
@RunWith(AndroidJUnit4::class)
class NoteEditViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: NoteRepository

    /** 编辑页现在也要读阅读页的显示偏好（v1.7.0），惰性取上下文，setUp 不必改。 */
    private val prefs: UiPrefs by lazy {
        UiPrefs(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(FtsSchemaCallback)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase
        repo = NoteRepository(db, deviceId = "test-device")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun awaitLoaded(vm: NoteEditViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!vm.state.value.loaded && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("ViewModel 未能在超时内完成 load()", vm.state.value.loaded)
    }

    /**
     * 用户在「添加标签」输入框里打完标签、**没点 + 号**就直接按保存：
     * 这段文字必须一起入库，否则标签就"创建失败"了（v1.1.0 的真机反馈）。
     */
    @Test
    fun pendingTagInInputFieldIsSavedTogetherWithTheNote() {
        val vm = NoteEditViewModel(repo, prefs)
        vm.load(null, openInPreview = false)
        awaitLoaded(vm)

        vm.setTitle("标签待确认测试")
        vm.setContent("正文内容")

        val done = CountDownLatch(1)
        vm.save(pendingTag = "索引") { done.countDown() }
        assertTrue("保存回调超时", done.await(10, TimeUnit.SECONDS))

        val notes = runBlocking { db.noteDao().allOnce() }
        assertEquals(1, notes.size)
        val noteId = notes.first().id
        assertEquals(listOf("索引"), runBlocking { db.noteDao().tagNamesOf(noteId) })
        assertNotNull("标签词典里必须真的建出这条标签", runBlocking { db.tagDao().byName("索引") })
    }

    /** 已经点过 + 号的标签 + 输入框里新打的标签，两者都要保留。 */
    @Test
    fun confirmedAndPendingTagsAreBothKept() {
        val vm = NoteEditViewModel(repo, prefs)
        vm.load(null, openInPreview = false)
        awaitLoaded(vm)

        vm.setTitle("两个标签")
        vm.addTag("并发")
        vm.addTag("并发") // 重复添加不应产生两条

        val done = CountDownLatch(1)
        vm.save(pendingTag = "  JVM  ") { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))

        val noteId = runBlocking { db.noteDao().allOnce() }.first().id
        assertEquals(listOf("JVM", "并发"), runBlocking { db.noteDao().tagNamesOf(noteId) })
    }

    /** 空白笔记（无标题无正文无标签）不该写库。 */
    @Test
    fun blankNoteIsNotPersisted() {
        val vm = NoteEditViewModel(repo, prefs)
        vm.load(null, openInPreview = false)
        awaitLoaded(vm)

        val done = CountDownLatch(1)
        vm.save(pendingTag = "") { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertEquals(0, runBlocking { db.noteDao().allOnce() }.size)
    }

    /** 已有笔记：改标题后保存，标签按新集合覆盖。 */
    @Test
    fun editingExistingNoteReplacesTagSet() {
        val noteId = runBlocking {
            repo.saveNote(null, "原标题", "原正文", null, listOf("旧标签"))
        }

        val vm = NoteEditViewModel(repo, prefs)
        vm.load(noteId, openInPreview = false)
        awaitLoaded(vm)
        assertEquals(listOf("旧标签"), vm.state.value.tags)

        vm.setTitle("新标题")
        vm.removeTag("旧标签")
        val done = CountDownLatch(1)
        vm.save(pendingTag = "新标签") { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))

        val note = runBlocking { db.noteDao().byId(noteId) }!!
        assertEquals("新标题", note.title)
        assertEquals(1, runBlocking { db.noteDao().allOnce() }.size)
        assertEquals(listOf("新标签"), runBlocking { db.noteDao().tagNamesOf(noteId) })
    }

    /**
     * 只是「查看」了一眼就返回：不能落库。
     *
     * 返回时无条件保存会白白刷新 updated_at —— 列表按 updated_at 倒序，
     * 看一眼就把笔记顶到最前面、时间显示成「刚刚」（v1.2.1 真机验证时发现）。
     */
    @Test
    fun exitingWithoutChangesDoesNotTouchUpdatedAt() {
        val noteId = runBlocking { repo.saveNote(null, "只看不改", "正文", null, listOf("标签")) }
        val before = runBlocking { db.noteDao().byId(noteId) }!!.updatedAt

        Thread.sleep(1_100) // 只要发生写库，updated_at 必然比 before 大
        val vm = NoteEditViewModel(repo, prefs)
        vm.load(noteId, openInPreview = true)
        awaitLoaded(vm)

        val done = CountDownLatch(1)
        vm.saveOnExit(pendingTag = "") { done.countDown() }
        assertTrue("返回回调超时", done.await(10, TimeUnit.SECONDS))

        val after = runBlocking { db.noteDao().byId(noteId) }!!
        assertEquals("没改动就返回，updated_at 不该被刷新", before, after.updatedAt)
        assertEquals("只看不改", after.title)
    }

    /** 兜底保存不能被上面的改动误伤：改过字段就仍然要落库。 */
    @Test
    fun exitingAfterEditingStillSaves() {
        val noteId = runBlocking { repo.saveNote(null, "原标题", "原正文", null, emptyList()) }

        val vm = NoteEditViewModel(repo, prefs)
        vm.load(noteId, openInPreview = false)
        awaitLoaded(vm)
        vm.setTitle("改过的标题")

        val done = CountDownLatch(1)
        vm.saveOnExit(pendingTag = "") { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertEquals("改过的标题", runBlocking { db.noteDao().byId(noteId) }!!.title)
    }

    /** 只往输入框里打了标签、没点 + 号就返回：也算改动，必须入库。 */
    @Test
    fun exitingWithOnlyAPendingTagStillSaves() {
        val noteId = runBlocking { repo.saveNote(null, "标题", "正文", null, emptyList()) }

        val vm = NoteEditViewModel(repo, prefs)
        vm.load(noteId, openInPreview = false)
        awaitLoaded(vm)

        val done = CountDownLatch(1)
        vm.saveOnExit(pendingTag = "临时标签") { done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertEquals(listOf("临时标签"), runBlocking { db.noteDao().tagNamesOf(noteId) })
    }
}
