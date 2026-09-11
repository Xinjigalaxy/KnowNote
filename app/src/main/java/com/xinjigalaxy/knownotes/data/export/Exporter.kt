package com.xinjigalaxy.knownotes.data.export

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.xinjigalaxy.knownotes.R
import com.xinjigalaxy.knownotes.data.db.AppDatabase
import com.xinjigalaxy.knownotes.data.model.Group
import com.xinjigalaxy.knownotes.data.model.NoteWithTags
import com.xinjigalaxy.knownotes.data.model.Tag
import com.xinjigalaxy.knownotes.data.repo.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 导出格式（需求文档 2.3）。label 是格式名（JSON / CSV / SQLite .db），与语言无关，不进资源。 */
enum class ExportFormat(
    val ext: String,
    val mime: String,
    val label: String,
    @StringRes val descriptionRes: Int,
) {
    JSON(
        ext = "json",
        mime = "application/json",
        label = "JSON",
        descriptionRes = R.string.structured_data_with_notes_tags_groups_and_links,
    ),
    CSV(
        ext = "csv",
        mime = "text/csv",
        label = "CSV",
        descriptionRes = R.string.tabular_data_easy_to_read_or_import_into_excel_w,
    ),
    DB(
        ext = "db",
        mime = "application/octet-stream",
        label = "SQLite .db",
        descriptionRes = R.string.full_database_file_openable_in_any_sqlite_enviro,
    ),
    ;

    companion object {
        fun fromName(name: String?): ExportFormat = entries.firstOrNull { it.name == name } ?: JSON
    }
}

data class ExportResult(val format: ExportFormat, val fileName: String, val bytes: Long)

/**
 * 导出实现（需求文档 2.3 / 5.1）。
 *
 * 统一先取数、再写 SAF 的 Uri；.db 走 VACUUM INTO 生成一致性快照，
 * 失败时退回「WAL checkpoint + 文件复制」。
 */
class Exporter(
    private val context: Context,
    private val repo: NoteRepository,
) {

    fun suggestedName(format: ExportFormat, at: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(at))
        return "knownote-$stamp.${format.ext}"
    }

    suspend fun export(format: ExportFormat, target: Uri, fileName: String): Result<ExportResult> {
        // 取数留在协程体内（不塞进 runCatching 的 lambda，避免内联作用域的歧义）
        val notes: List<NoteWithTags>
        val tags: List<Tag>
        val groups: List<Group>
        if (format == ExportFormat.DB) {
            notes = emptyList(); tags = emptyList(); groups = emptyList()
        } else {
            notes = repo.exportNotes()
            tags = repo.exportTags()
            groups = repo.exportGroups()
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = when (format) {
                    ExportFormat.JSON -> writeText(target) { out -> writeJson(out, notes, tags, groups) }
                    ExportFormat.CSV -> writeText(target) { out -> writeCsv(out, notes, groups) }
                    ExportFormat.DB -> copyDatabase(target)
                }
                ExportResult(format = format, fileName = fileName, bytes = bytes)
            }
        }
    }

    private inline fun writeText(target: Uri, body: (OutputStream) -> Unit): Long {
        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: error(context.getString(R.string.cannot_open_the_output_stream_for_the_target_fil))
        stream.use { out ->
            body(out)
            out.flush()
        }
        return contentLength(target)
    }

    private fun writeJson(
        out: OutputStream,
        notes: List<NoteWithTags>,
        tags: List<Tag>,
        groups: List<Group>,
    ) {
        val now = System.currentTimeMillis()
        val groupNames = groups.associate { it.id to it.name }
        val root = JSONObject()
        root.put("app", "KnowNote")
        root.put("format", "knownote-export/1")
        root.put("schema_version", AppDatabase.SCHEMA_VERSION)
        root.put("exported_at", now)
        root.put("exported_at_iso", ISO.format(Date(now)))
        root.put("device_id", repo.deviceId)

        root.put(
            "groups",
            JSONArray().apply {
                groups.forEach { g ->
                    put(
                        JSONObject().apply {
                            put("id", g.id)
                            put("name", g.name)
                            put("sort_order", g.sortOrder)
                        }
                    )
                }
            },
        )

        root.put(
            "tags",
            JSONArray().apply {
                tags.forEach { t ->
                    put(JSONObject().apply { put("id", t.id); put("name", t.name) })
                }
            },
        )

        root.put(
            "notes",
            JSONArray().apply {
                notes.forEach { nw ->
                    put(
                        JSONObject().apply {
                            put("id", nw.note.id)
                            put("title", nw.note.title)
                            put("content", nw.note.content)
                            put("group_id", nw.note.groupId ?: JSONObject.NULL)
                            put("group_name", groupNames[nw.note.groupId] ?: JSONObject.NULL)
                            put("created_at", nw.note.createdAt)
                            put("updated_at", nw.note.updatedAt)
                            put("is_deleted", nw.note.isDeleted)
                            put("tag_ids", JSONArray(nw.tags.map { it.id }))
                            put("tags", JSONArray(nw.tags.map { it.name }))
                        }
                    )
                }
            },
        )

        out.write(root.toString(2).toByteArray(Charsets.UTF_8))
    }

    private fun writeCsv(out: OutputStream, notes: List<NoteWithTags>, groups: List<Group>) {
        val groupNames = groups.associate { it.id to it.name }
        val builder = StringBuilder()
        builder.append("id,title,content,group,tags,is_deleted,created_at,updated_at\r\n")
        notes.forEach { nw ->
            builder.append(nw.note.id).append(',')
            builder.append(csvCell(nw.note.title)).append(',')
            builder.append(csvCell(nw.note.content)).append(',')
            builder.append(csvCell(groupNames[nw.note.groupId].orEmpty())).append(',')
            builder.append(csvCell(nw.tags.joinToString(" ") { it.name })).append(',')
            builder.append(if (nw.note.isDeleted) 1 else 0).append(',')
            builder.append(nw.note.createdAt).append(',')
            builder.append(nw.note.updatedAt).append("\r\n")
        }
        // 带 UTF-8 BOM，Excel / WPS 打开中文不乱码
        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        out.write(builder.toString().toByteArray(Charsets.UTF_8))
    }

    private fun copyDatabase(target: Uri): Long {
        val tmp = File(context.cacheDir, "knownote-export-${System.currentTimeMillis()}.db")
        try {
            val vacuumed = runCatching {
                repo.rawDb().execSQL("VACUUM INTO ?", arrayOf<Any?>(tmp.absolutePath))
            }.isSuccess && tmp.exists() && tmp.length() > 0L

            if (!vacuumed) {
                runCatching {
                    repo.rawDb().query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                }
                val source = context.getDatabasePath(AppDatabase.DB_NAME)
                source.copyTo(tmp, overwrite = true)
            }

            val stream = context.contentResolver.openOutputStream(target, "wt")
                ?: error(context.getString(R.string.cannot_open_the_output_stream_for_the_target_fil))
            return stream.use { out ->
                tmp.inputStream().use { input -> input.copyTo(out) }
                tmp.length()
            }
        } finally {
            tmp.delete()
        }
    }

    private fun contentLength(uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
    }.getOrDefault(0L)

    private fun csvCell(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    companion object {
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }
}
