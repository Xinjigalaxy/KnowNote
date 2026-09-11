package com.xinjigalaxy.knownotes.data.db

import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xinjigalaxy.knownotes.data.fts.FtsText

/**
 * 本机 SQLite 实际支持的全文检索能力。
 */
enum class FtsEngine(val label: String, val description: String) {
    FTS5("FTS5", "SQLite 官方全文检索扩展，支持 bm25 相关度排序"),
    FTS4("FTS4", "老一代全文检索扩展，Android 系统 SQLite 稳定内置"),
    NONE("LIKE 兜底", "没有可用全文索引，退化为 LIKE 子串扫描"),
    ;

    val isFullText: Boolean get() = this != NONE
}

/**
 * FTS 索引通道（需求文档 3.1 / 3.2）。
 *
 * 为什么绕过 Room 注解：Room 只内置 @Fts3 / @Fts4，对 FTS5 虚拟表没有注解支持，
 * 用 @Query 直接引用虚拟表也会被编译期 schema 校验拦下。所以这里手写 DDL 与
 * MATCH 查询：建表挂在 RoomDatabase.Callback 上，查询走原生 Cursor。
 *
 * 为什么要有引擎探测：**Android 自带的 SQLite 并不保证编译了 FTS5**。
 * 在 Android 15（API 35）上实测 `no such module: fts5`，所以这里按
 * FTS5 → FTS4 → LIKE 逐级降级，并把实际用到的引擎暴露给界面，
 * 而不是让检索悄悄失效。
 *
 * 表结构（非 external-content）：title / content / tags 存的是 FtsText.index()
 * 处理后的分词副本，同步由 Repository 在事务内显式完成。
 */
class FtsStore(private val appDatabase: AppDatabase) {

    private val db: SupportSQLiteDatabase
        get() = appDatabase.openHelper.writableDatabase

    val available: Boolean get() = engine.isFullText

    val engineLabel: String get() = engine.label

    /** 单条（重新）入索引。 */
    fun upsert(noteId: Long, indexedTitle: String, indexedContent: String, indexedTags: String) {
        if (!available) return
        val sql = db
        sql.beginTransaction()
        try {
            sql.execSQL("DELETE FROM notes_fts WHERE rowid = ?", arrayOf<Any?>(noteId))
            sql.execSQL(
                "INSERT INTO notes_fts(rowid, title, content, tags) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(noteId, indexedTitle, indexedContent, indexedTags),
            )
            sql.setTransactionSuccessful()
        } catch (t: Throwable) {
            // 索引写失败不能影响主业务入库
            Log.w(TAG, "写入 FTS 索引失败（rowid=$noteId）: ${t.message}")
        } finally {
            sql.endTransaction()
        }
    }

    fun remove(noteId: Long) {
        if (!available) return
        runCatching { db.execSQL("DELETE FROM notes_fts WHERE rowid = ?", arrayOf<Any?>(noteId)) }
    }

    /** 返回命中的笔记 id。FTS5 用 bm25 相关度排序，FTS4 退化为 rowid 倒序。 */
    fun searchIds(query: String, limit: Int = FtsText.MAX_RESULTS): List<Long> {
        if (!available) return emptyList()
        val match = FtsText.buildMatch(query) ?: return emptyList()

        // bm25() 是 FTS5 专有函数；万一引擎判断有偏差，退回不带排序的查询而不是直接搜不到
        val statements = if (engine == FtsEngine.FTS5) {
            listOf(
                "SELECT rowid FROM $TABLE WHERE $TABLE MATCH ? ORDER BY bm25($TABLE) LIMIT ?",
                "SELECT rowid FROM $TABLE WHERE $TABLE MATCH ? LIMIT ?",
            )
        } else {
            listOf("SELECT rowid FROM $TABLE WHERE $TABLE MATCH ? ORDER BY rowid DESC LIMIT ?")
        }

        for (sql in statements) {
            val ids = ArrayList<Long>()
            try {
                db.query(sql, arrayOf<Any?>(match, limit)).use { cursor: Cursor ->
                    while (cursor.moveToNext()) ids += cursor.getLong(0)
                }
                return ids
            } catch (t: Throwable) {
                Log.w(TAG, "检索语句失败，尝试降级：${t.message}")
            }
        }
        return emptyList()
    }

    companion object {

        private const val TAG = "KnowNote"

        const val TABLE = "notes_fts"

        // 注意：探测模块可用性时**不能**用 IF NOT EXISTS——
        // 当同名表已存在（比如早先建成了 FTS4）时，SQLite 会直接跳过模块加载并返回成功，
        // 于是引擎被误判成 FTS5，再去用 FTS4 不支持的 bm25() 就会搜不到任何东西。
        private const val DDL_FTS5 =
            "CREATE VIRTUAL TABLE $TABLE USING fts5(title, content, tags, tokenize='unicode61')"

        private const val DDL_FTS4 =
            "CREATE VIRTUAL TABLE $TABLE USING fts4(title, content, tags, tokenize=simple)"

        @Volatile
        var engine: FtsEngine = FtsEngine.NONE
            private set

        /** 探测失败原因，用于诊断展示。 */
        @Volatile
        var probeError: String? = null
            private set

        /** 设备 SQLite 版本，诊断用（不同 Android 版本差异很大，实机反馈很关键）。 */
        @Volatile
        var sqliteVersion: String = "未知"
            private set

        /**
         * 建表：按 FTS5 → FTS4 逐级尝试，都不行则标记 NONE（走 LIKE）。
         * 表已存在时以 sqlite_master 里的真实 DDL 为准。
         */
        fun create(db: SupportSQLiteDatabase) {
            detectSqliteVersion(db)

            val existing = existingDdl(db)
            if (existing != null) {
                engine = if (existing.contains("fts5", ignoreCase = true)) {
                    FtsEngine.FTS5
                } else {
                    FtsEngine.FTS4
                }
                return
            }

            engine = when {
                tryExec(db, DDL_FTS5, "FTS5") -> FtsEngine.FTS5
                tryExec(db, DDL_FTS4, "FTS4") -> FtsEngine.FTS4
                else -> FtsEngine.NONE
            }
        }

        private fun detectSqliteVersion(db: SupportSQLiteDatabase) {
            try {
                db.query("SELECT sqlite_version()").use { c ->
                    if (c.moveToFirst()) sqliteVersion = c.getString(0)
                }
            } catch (t: Throwable) {
                sqliteVersion = "未知"
            }
        }

        private fun existingDdl(db: SupportSQLiteDatabase): String? = try {
            var ddl: String? = null
            db.query(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf<Any?>(TABLE),
            ).use { c -> if (c.moveToFirst()) ddl = c.getString(0) }
            ddl
        } catch (t: Throwable) {
            null
        }

        private fun tryExec(db: SupportSQLiteDatabase, ddl: String, name: String): Boolean = try {
            db.execSQL(ddl)
            true
        } catch (t: Throwable) {
            probeError = "$name: ${t.message}"
            Log.w(TAG, "全文检索引擎 $name 不可用: ${t.message}")
            false
        }

        /** 自愈：索引条数与在线笔记数不一致时整体重建（升级/异常退出都能兜住）。 */
        fun repair(db: SupportSQLiteDatabase) {
            if (!engine.isFullText) return
            val indexed = db.countOf("SELECT count(*) FROM $TABLE") ?: return
            val live = db.countOf("SELECT count(*) FROM notes WHERE is_deleted = 0") ?: return
            if (indexed == live) return

            db.beginTransaction()
            try {
                reindexAll(db)
                db.setTransactionSuccessful()
            } catch (t: Throwable) {
                Log.e(TAG, "重建 FTS 索引失败: ${t.message}")
            } finally {
                db.endTransaction()
            }
        }

        private fun reindexAll(db: SupportSQLiteDatabase) {
            val rows = ArrayList<Array<String>>()
            db.query(
                "SELECT n.id, n.title, n.content, " +
                    "COALESCE((SELECT group_concat(t.name, ' ') FROM tags t " +
                    "INNER JOIN note_tags nt ON nt.tag_id = t.id WHERE nt.note_id = n.id), '') " +
                    "FROM notes n WHERE n.is_deleted = 0"
            ).use { c ->
                while (c.moveToNext()) {
                    rows += arrayOf(
                        c.getLong(0).toString(),
                        c.getString(1).orEmpty(),
                        c.getString(2).orEmpty(),
                        c.getString(3).orEmpty(),
                    )
                }
            }

            db.execSQL("DELETE FROM $TABLE")
            for ((id, title, content, tags) in rows) {
                db.execSQL(
                    "INSERT INTO $TABLE(rowid, title, content, tags) VALUES (?, ?, ?, ?)",
                    arrayOf<Any?>(
                        id.toLong(),
                        FtsText.index(title),
                        FtsText.index(content),
                        FtsText.index(tags),
                    ),
                )
            }
        }

        private fun SupportSQLiteDatabase.countOf(sql: String): Int? = try {
            var value: Int? = null
            query(sql).use { c -> if (c.moveToFirst()) value = c.getInt(0) }
            value
        } catch (t: Throwable) {
            null
        }
    }
}
