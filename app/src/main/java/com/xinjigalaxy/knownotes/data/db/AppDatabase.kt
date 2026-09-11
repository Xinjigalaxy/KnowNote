package com.xinjigalaxy.knownotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xinjigalaxy.knownotes.data.model.ChangeLogEntry
import com.xinjigalaxy.knownotes.data.model.Group
import com.xinjigalaxy.knownotes.data.model.Note
import com.xinjigalaxy.knownotes.data.model.NoteTagCrossRef
import com.xinjigalaxy.knownotes.data.model.SearchHistory
import com.xinjigalaxy.knownotes.data.model.SyncLogEntry
import com.xinjigalaxy.knownotes.data.model.SyncMeta
import com.xinjigalaxy.knownotes.data.model.Tag

@Database(
    entities = [
        Note::class,
        Tag::class,
        Group::class,
        NoteTagCrossRef::class,
        SyncMeta::class,
        ChangeLogEntry::class,
        SearchHistory::class,
        SyncLogEntry::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun tagDao(): TagDao
    abstract fun groupDao(): GroupDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {

        const val DB_NAME = "knownotes.db"

        /** 与 @Database(version = ...) 保持一致，导出时写入 JSON 便于日后迁移。 */
        const val SCHEMA_VERSION = 3

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addCallback(FtsSchemaCallback)
                .addMigrations(*MIGRATIONS)
                .build()

        /**
         * v1 → v2：新增搜索历史表（需求文档 2.2「每次版本升级按顺序执行迁移」）。
         * 只用 CREATE TABLE，不动既有表 —— 升级不重建表、不丢数据。
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (" +
                        "`keyword` TEXT NOT NULL, " +
                        "`last_used_at` INTEGER NOT NULL, " +
                        "`use_count` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`keyword`))"
                )
            }
        }

        /**
         * v2 → v3：为局域网同步做三件事（需求文档 4.2 / 7）。
         *
         * 1. notes 加 guid：本地自增 id 不能跨设备对齐，同步一律按 guid 认人；
         *    老数据用 SQLite 自带的 randomblob 回填，不需要在 Kotlin 里捞一遍。
         * 2. notes 加 is_purged：「彻底删除」从删行改成立墓碑，否则对端会把这条推回来。
         * 3. 新增 sync_log 表：同步页要展示历史。
         *
         * 依旧只用 ALTER TABLE / CREATE TABLE，不重建表、不丢数据。
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `guid` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `notes` ADD COLUMN `is_purged` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `notes` SET `guid` = lower(hex(randomblob(16))) WHERE `guid` = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_notes_guid` ON `notes` (`guid`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`at` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, " +
                        "`peer` TEXT NOT NULL, " +
                        "`pulled` INTEGER NOT NULL, " +
                        "`pushed` INTEGER NOT NULL, " +
                        "`conflicts` INTEGER NOT NULL, " +
                        "`ok` INTEGER NOT NULL, " +
                        "`message` TEXT NOT NULL)"
                )
            }
        }

        /**
         * 升级兼容（需求文档 2.2）：version + 1，按顺序追加 Migration，全部走 ALTER TABLE / CREATE TABLE，
         * 不重建表。新增字段一律允许 NULL 或带默认值。示例：
         *
         * ```
         * internal val MIGRATION_3_4 = object : Migration(3, 4) {
         *     override fun migrate(db: SupportSQLiteDatabase) {
         *         db.execSQL("ALTER TABLE notes ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
         *     }
         * }
         * ```
         */
        private val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

/**
 * FTS5 虚拟表不走 Room 的 schema 管理，因此建表/自愈挂在数据库打开回调上。
 * Room 打开数据库时会同步执行，早于任何 DAO 访问。
 */
object FtsSchemaCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        FtsStore.create(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        // 每次打开都确保虚拟表存在，并在索引条数对不上时整体重建
        FtsStore.create(db)
        FtsStore.repair(db)
    }
}
