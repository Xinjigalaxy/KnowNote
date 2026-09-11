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
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun tagDao(): TagDao
    abstract fun groupDao(): GroupDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {

        const val DB_NAME = "knownotes.db"

        /** 与 @Database(version = ...) 保持一致，导出时写入 JSON 便于日后迁移。 */
        const val SCHEMA_VERSION = 1

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
         * 升级兼容（需求文档 2.2）：version + 1，按顺序追加 Migration，全部走 ALTER TABLE，
         * 不重建表。示例：
         *
         * ```
         * private val MIGRATION_1_2 = object : Migration(1, 2) {
         *     override fun migrate(db: SupportSQLiteDatabase) {
         *         db.execSQL("ALTER TABLE notes ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
         *     }
         * }
         * ```
         */
        private val MIGRATIONS: Array<Migration> = arrayOf()
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
