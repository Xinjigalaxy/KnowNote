package com.xinjigalaxy.knownotes

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 迁移测试（需求文档 2.2「升级不丢数据」）。
 *
 * MigrationTestHelper 会拿 app/schemas 下导出的 1.json 建一个真 v1 库，
 * 跑迁移后**再对着 2.json 校验表结构**——所以手写的 CREATE TABLE 一旦和
 * Room 生成的 DDL 有偏差，这里会直接失败，不用等真机上炸。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2KeepsExistingDataAndAddsSearchHistory() {
        // 1) 造一个 v1 库并塞入"升级前"的数据
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO groups(name, sort_order) VALUES('升级前分组', 1)")
            db.execSQL(
                "INSERT INTO notes(title, content, group_id, created_at, updated_at, is_deleted) " +
                    "VALUES('升级前笔记', '正文内容', 1, 1000, 2000, 0)"
            )
            db.execSQL("INSERT INTO tags(name) VALUES('升级前标签')")
            db.execSQL("INSERT INTO note_tags(note_id, tag_id) VALUES(1, 1)")
        }

        // 2) 跑迁移（validateDroppedTables = true）
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        )
        migrated.use { db ->
            // 老数据必须原样还在
            db.query("SELECT count(*) FROM notes").use { c ->
                c.moveToFirst()
                assertEquals("笔记不该在升级中丢失", 1, c.getInt(0))
            }
            db.query("SELECT title, content, created_at FROM notes").use { c ->
                c.moveToFirst()
                assertEquals("升级前笔记", c.getString(0))
                assertEquals("正文内容", c.getString(1))
                assertEquals(1000L, c.getLong(2))
            }
            db.query("SELECT count(*) FROM note_tags").use { c ->
                c.moveToFirst()
                assertEquals("标签关联不该丢", 1, c.getInt(0))
            }

            // 新表存在且可写
            db.query("SELECT count(*) FROM search_history").use { c ->
                c.moveToFirst()
                assertEquals("新表初始应为空", 0, c.getInt(0))
            }
            db.execSQL(
                "INSERT INTO search_history(keyword, last_used_at, use_count) VALUES('索引', 1, 1)"
            )
            db.query("SELECT keyword FROM search_history").use { c ->
                c.moveToFirst()
                assertEquals("索引", c.getString(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
