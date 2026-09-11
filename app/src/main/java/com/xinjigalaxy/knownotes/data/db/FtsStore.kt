package com.xinjigalaxy.knownotes.data.db

import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xinjigalaxy.knownotes.data.fts.FtsText

/**
 * 本机 SQLite 实际支持的全文检索能力。
 */
enum class FtsEngine(val label: String, val description: String) {
    FTS5("FTS5", "SQLite's official full-text extension, supports bm25 ranking"),
    FTS4("FTS4", "Previous-generation full-text extension, bundled in Android's SQLite"),
    NONE("LIKE fallback", "No full-text index available, falling back to a LIKE substring scan"),
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
 * ## 三条实机验证过的硬约束（每一行都有血）
 *
 * 1. **Android 不保证编译 FTS5**：Android 15（SQLite 3.44.3）实测
 *    `no such module: fts5`，只有 FTS4。所以按 FTS5 → FTS4 → LIKE 降级。
 *
 * 2. **不能靠 sqlite_master 的 sql 列判断已有虚拟表**：SQLite 3.32（Android 12/13）
 *    上虚拟表那一行的 `sql` 取不到值，于是"表已存在"被误判成"表不存在"，
 *    接着重试建表 → `table notes_fts already exists` → 引擎被错误降级成 NONE。
 *    现在改为**按行为探测**：能不能 MATCH、有没有 bm25()，不看 DDL 文本。
 *
 * 3. **探测模块可用性绝不能拿真表名试**：失败的 CREATE 在部分版本会留下残留，
 *    所以模块探测走 `temp.` 临时表，真名只在确认可用后才落笔。
 *
 * 另外：Room 新建库时会连续回调 onCreate + onOpen，也就是同一个库里 create() 会被
 * 调用两次。整个过程必须幂等 —— 第二次不得降级第一次的结论。
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
            sql.execSQL("DELETE FROM $TABLE WHERE rowid = ?", arrayOf<Any?>(noteId))
            sql.execSQL(
                "INSERT INTO $TABLE(rowid, title, content, tags) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(noteId, indexedTitle, indexedContent, indexedTags),
            )
            sql.setTransactionSuccessful()
        } catch (t: Throwable) {
            // 索引写失败不能影响主业务入库
            Log.w(TAG, "FTS index write failed (rowid=$noteId): ${t.message}")
        } finally {
            sql.endTransaction()
        }
    }

    fun remove(noteId: Long) {
        if (!available) return
        runCatching { db.execSQL("DELETE FROM $TABLE WHERE rowid = ?", arrayOf<Any?>(noteId)) }
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
                Log.w(TAG, "Query failed, trying to fall back: ${t.message}")
            }
        }
        return emptyList()
    }

    companion object {

        private const val TAG = "KnowNote"

        const val TABLE = "notes_fts"

        private const val DDL_FTS5 =
            "CREATE VIRTUAL TABLE $TABLE USING fts5(title, content, tags, tokenize='unicode61')"

        private const val DDL_FTS4 =
            "CREATE VIRTUAL TABLE $TABLE USING fts4(title, content, tags, tokenize=simple)"

        @Volatile
        var engine: FtsEngine = FtsEngine.NONE
            private set

        /** 最近一次失败的探测信息，诊断展示用。 */
        @Volatile
        var probeError: String? = null
            private set

        /** 设备 SQLite 版本，诊断用（不同 Android 版本差异很大）。 */
        @Volatile
        var sqliteVersion: String = "unknown"
            private set

        /** 本次判定走了哪条路径，实机排查全靠它。 */
        @Volatile
        var lastDecision: String = "not probed yet"
            private set

        /**
         * 建表 / 识别已有表。**必须幂等**：重复调用不得降级已有结论。
         */
        fun create(db: SupportSQLiteDatabase) {
            detectSqliteVersion(db)

            // 情形一：表已存在 —— 按行为判定引擎（不看 sqlite_master.sql，见类注释第 2 条）
            if (nameTaken(db)) {
                when (val existing = probeExisting(db)) {
                    FtsEngine.NONE -> {
                        // 名字被占但没有可用的虚拟表（残留 / 类型不对）：清掉后按正常流程重建
                        if (dropUnusable(db)) {
                            createFresh(db, prefix = "Rebuilt after clearing unusable tables")
                        } else {
                            engine = FtsEngine.NONE
                            lastDecision = "Existing table unusable and cannot be dropped; falling back to LIKE"
                        }
                    }

                    else -> {
                        engine = existing
                        lastDecision = "Reusing existing ${existing.label} index"
                    }
                }
                return
            }

            // 情形二：全新库
            createFresh(db, prefix = "Created")
        }

        private fun createFresh(db: SupportSQLiteDatabase, prefix: String) {
            engine = when {
                moduleAvailable(db, "fts5") && tryExec(db, DDL_FTS5, "FTS5") -> FtsEngine.FTS5
                moduleAvailable(db, "fts4") && tryExec(db, DDL_FTS4, "FTS4") -> FtsEngine.FTS4
                else -> FtsEngine.NONE
            }
            lastDecision = if (engine.isFullText) "$prefix ${engine.label} index" else "No usable full-text engine; falling back to LIKE"
        }

        /**
         * 按行为判定已有表的引擎，全程不看 DDL 文本：
         * MATCH 能过 ⇒ 模块在；再试 bm25()，它是 FTS5 专有函数。
         */
        private fun probeExisting(db: SupportSQLiteDatabase): FtsEngine {
            val plain = runCatching {
                db.query("SELECT rowid FROM $TABLE WHERE $TABLE MATCH ? LIMIT 1", arrayOf<Any?>("x*")).close()
            }
            if (plain.isFailure) {
                probeError = "Existing $TABLE unusable: ${plain.exceptionOrNull()?.message}"
                Log.w(TAG, "Existing $TABLE cannot be used for search: ${plain.exceptionOrNull()?.message}")
                return FtsEngine.NONE
            }
            val ranked = runCatching {
                db.query(
                    "SELECT rowid FROM $TABLE WHERE $TABLE MATCH ? ORDER BY bm25($TABLE) LIMIT 1",
                    arrayOf<Any?>("x*"),
                ).close()
            }
            return if (ranked.isSuccess) FtsEngine.FTS5 else FtsEngine.FTS4
        }

        /** 在 temp schema 里探测模块，绝不在真表名上试错。 */
        private fun moduleAvailable(db: SupportSQLiteDatabase, module: String): Boolean {
            val probe = "knownote_probe_$module"
            return try {
                db.execSQL("DROP TABLE IF EXISTS temp.$probe")
                db.execSQL("CREATE VIRTUAL TABLE temp.$probe USING $module(x)")
                db.execSQL("DROP TABLE temp.$probe")
                true
            } catch (t: Throwable) {
                probeError = "$module: ${t.message}"
                Log.w(TAG, "Full-text engine $module unavailable: ${t.message}")
                runCatching { db.execSQL("DROP TABLE IF EXISTS temp.$probe") }
                false
            }
        }

        private fun tryExec(db: SupportSQLiteDatabase, ddl: String, name: String): Boolean = try {
            db.execSQL(ddl)
            true
        } catch (t: Throwable) {
            probeError = "$name: ${t.message}"
            Log.w(TAG, "Creating table $name failed: ${t.message}")
            false
        }

        private fun nameTaken(db: SupportSQLiteDatabase): Boolean = try {
            var found = false
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf<Any?>(TABLE),
            ).use { c -> if (c.moveToFirst()) found = true }
            found
        } catch (t: Throwable) {
            false
        }

        private fun dropUnusable(db: SupportSQLiteDatabase): Boolean = try {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            !nameTaken(db)
        } catch (t: Throwable) {
            Log.w(TAG, "Dropping unusable $TABLE failed: ${t.message}")
            false
        }

        private fun detectSqliteVersion(db: SupportSQLiteDatabase) {
            try {
                db.query("SELECT sqlite_version()").use { c ->
                    if (c.moveToFirst()) sqliteVersion = c.getString(0)
                }
            } catch (t: Throwable) {
                sqliteVersion = "unknown"
            }
        }

        /** 自愈：索引条数与在线笔记数不一致时整体重建（升级/异常退出/外部灌库都能兜住）。 */
        fun repair(db: SupportSQLiteDatabase) {
            if (!engine.isFullText) return
            val indexed = db.countOf("SELECT count(*) FROM $TABLE") ?: return
            val live = db.countOf("SELECT count(*) FROM notes WHERE is_deleted = 0") ?: return
            if (indexed == live) return

            db.beginTransaction()
            try {
                reindexAll(db)
                db.setTransactionSuccessful()
                Log.i(TAG, "FTS index count mismatch ($indexed -> $live), rebuilt from scratch")
            } catch (t: Throwable) {
                Log.e(TAG, "Rebuilding FTS index failed: ${t.message}")
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
