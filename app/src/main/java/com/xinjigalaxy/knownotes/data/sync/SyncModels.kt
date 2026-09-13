package com.xinjigalaxy.knownotes.data.sync

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 局域网同步的线格式（需求文档 4）。
 *
 * 设计要点：
 * - **不带本地 id**：本地自增 id 跨设备没有意义，笔记用 guid 认人，分组与标签直接用「名字」传，
 *   对端按名字解析 / 缺失就建 —— 这样彻底绕开了 id 映射表。
 * - 软删除与「彻底删除」都靠时间戳传（is_deleted / is_purged + updated_at），不做单独的删除消息。
 * - 明文 JSON，不做加密：局域网内用共享密钥做**认证**，不假装做了保密（见 README 的安全说明）。
 */
const val SYNC_PROTOCOL = 2

/** 单次请求最多带几张图 / 多少字节 —— 避免一次把几十兆塞进一个 JSON 里把内存顶爆。 */
const val MAX_SYNC_IMAGES = 6
const val MAX_SYNC_IMAGE_BYTES = 4 * 1024 * 1024

/**
 * 一张图片在网线上的形态：文件名 + base64。
 *
 * 为什么不做成单独的文件下载接口：一次同步只有一次请求（推 + 拉），
 * 加图片二进制流要另开端点、另写一套鉴权与错误处理；而这里的图张张都在几百 KB 以内，
 * base64 的结构代价换来的是**协议不膨胀**。真正要防的是"一次全塞进去"，
 * 所以有 [MAX_SYNC_IMAGES] / [MAX_SYNC_IMAGE_BYTES] 两道闸。
 */
data class SyncImage(val name: String, val bytes: ByteArray) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    /** data 类带 ByteArray 时 equals/hashCode 是引用比较，这里按文件名判等更符合直觉。 */
    override fun equals(other: Any?): Boolean = other is SyncImage && other.name == name

    override fun hashCode(): Int = name.hashCode()

    companion object {
        fun fromJson(o: JSONObject): SyncImage? = runCatching {
            SyncImage(o.getString("name"), Base64.decode(o.optString("data"), Base64.DEFAULT))
        }.getOrNull()
    }
}

/** 从 JSON 数组里解析图片列表，坏数据直接跳过（不让一张坏图打断整次同步）。 */
internal fun parseImages(array: JSONArray?): List<SyncImage> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { SyncImage.fromJson(it) }
    }
}

internal fun imagesToJson(images: List<SyncImage>): JSONArray =
    JSONArray().apply { images.forEach { put(it.toJson()) } }

/** 一条笔记在网线上的形态。 */
data class SyncNote(
    val guid: String,
    val title: String,
    val content: String,
    val group: String?,
    val tags: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val isPurged: Boolean,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("guid", guid)
        put("title", title)
        put("content", content)
        put("group", group ?: JSONObject.NULL)
        put("tags", JSONArray(tags))
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("is_deleted", if (isDeleted) 1 else 0)
        put("is_purged", if (isPurged) 1 else 0)
    }

    /**
     * 冲突裁决用的规范串：时间戳打平时比较它，两端取「较大」的那版，保证收敛到同一份。
     * 不参与展示，只用于比较。
     */
    fun canonical(): String = listOf(
        title,
        content,
        group ?: "",
        tags.sorted().joinToString(","),
        if (isDeleted) "1" else "0",
        if (isPurged) "1" else "0",
    ).joinToString("\u0000")

    companion object {
        fun fromJson(o: JSONObject): SyncNote = SyncNote(
            guid = o.getString("guid"),
            title = o.optString("title", ""),
            content = o.optString("content", ""),
            group = if (o.isNull("group")) null else o.optString("group").takeIf { it.isNotEmpty() },
            tags = o.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            createdAt = o.optLong("created_at", System.currentTimeMillis()),
            updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
            isDeleted = o.optInt("is_deleted", 0) == 1,
            isPurged = o.optInt("is_purged", 0) == 1,
        )
    }
}

/** 从机 → 主机：带上自己的水位线和自水位线之后的变更。 */
data class SyncRequest(
    val deviceId: String,
    val deviceName: String,
    val lastSyncAt: Long,
    val notes: List<SyncNote>,
    /** 本机现有的图片文件名 —— 对端据此只发我缺的那几张。 */
    val imagesIHave: List<String> = emptyList(),
    /** 本机认为对端缺的图片（对端已有的不发，省流量）。 */
    val images: List<SyncImage> = emptyList(),
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", SYNC_PROTOCOL)
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("last_sync_at", lastSyncAt)
        put("notes", JSONArray().apply { notes.forEach { put(it.toJson()) } })
        put("images_i_have", JSONArray(imagesIHave))
        put("images", imagesToJson(images))
    }

    companion object {
        fun fromJson(o: JSONObject): SyncRequest = SyncRequest(
            deviceId = o.getString("device_id"),
            deviceName = o.optString("device_name", "Unnamed device"),
            lastSyncAt = o.optLong("last_sync_at", 0L),
            notes = o.optJSONArray("notes")?.let { arr ->
                (0 until arr.length()).map { SyncNote.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
            imagesIHave = o.optJSONArray("images_i_have")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            images = parseImages(o.optJSONArray("images")),
        )
    }
}

/** 主机 → 从机：自己的变更 + 落库统计。 */
data class SyncResponse(
    val protocol: Int,
    val deviceId: String,
    val deviceName: String,
    /** 主机的当前时间，从机拿它当新水位线（并且与本地时间取较小值，宁可比对方多要一点）。 */
    val serverTime: Long,
    val notes: List<SyncNote>,
    val appliedNotes: Int,
    val conflicts: Int,
    /** 主机现有的图片文件名。 */
    val imagesIHave: List<String> = emptyList(),
    /** 主机认为从机缺的图片。 */
    val images: List<SyncImage> = emptyList(),
    /** 主机侧本次实际新增了几张图（统计用）。 */
    val imagesReceived: Int = 0,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", SYNC_PROTOCOL)
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("server_time", serverTime)
        put("applied_notes", appliedNotes)
        put("conflicts", conflicts)
        put("notes", JSONArray().apply { notes.forEach { put(it.toJson()) } })
        put("images_i_have", JSONArray(imagesIHave))
        put("images", imagesToJson(images))
        put("images_received", imagesReceived)
    }

    companion object {
        fun fromJson(o: JSONObject): SyncResponse = SyncResponse(
            protocol = o.optInt("protocol", 0),
            deviceId = o.optString("device_id", ""),
            deviceName = o.optString("device_name", "Unnamed device"),
            serverTime = o.optLong("server_time", 0L),
            notes = o.optJSONArray("notes")?.let { arr ->
                (0 until arr.length()).map { SyncNote.fromJson(arr.getJSONObject(it)) }
            } ?: emptyList(),
            appliedNotes = o.optInt("applied_notes", 0),
            conflicts = o.optInt("conflicts", 0),
            imagesIHave = o.optJSONArray("images_i_have")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            images = parseImages(o.optJSONArray("images")),
            imagesReceived = o.optInt("images_received", 0),
        )
    }
}
