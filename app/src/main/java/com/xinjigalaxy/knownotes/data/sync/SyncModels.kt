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
const val SYNC_PROTOCOL = 3

/** 单次请求最多带几张图 / 多少字节 —— 避免一次把几十兆塞进一个 JSON 里把内存顶爆。 */
const val MAX_SYNC_IMAGES = 6
const val MAX_SYNC_IMAGE_BYTES = 4 * 1024 * 1024

/**
 * 一张图片在网线上的形态：文件名 + base64。
 *
 * 不单开文件下载接口的原因：一次同步只有一次请求（推 + 拉），
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

/**
 * 一条笔记的「库存条目」：只需身份 + 版本指纹，不需要正文。
 *
 * 需要它的原因：光靠水位线（最后一轮同步的时间戳）判断「你需要哪些」是有洞的 ——
 * 一台已经同步过的设备换了个新对端时，水位线是「和上一个对端同步到哪」，
 * 于是一整库笔记只会推过去「上次同步之后改的那几条」（v1.10.1 之前就是这样，
 * 真机上表现为新设备只收到了 3 条）。改成双方各自报一份**全量库存**，
 * 由指纹比出真正的差集，才与「水位线/时钟/上次和谁同步过」全都无关。
 *
 * 指纹 [hash] 覆盖 [SyncNote.canonical] 的规范串：`updated_at` 相同时靠它判断内容是否真的不同，
 * 不然「同一毫秒各改各的」两端会互相跳过、永不收敛。
 */
data class NoteEntry(
    val guid: String,
    val updatedAt: Long,
    val isPurged: Boolean,
    val hash: String,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("g", guid)
        put("u", updatedAt)
        put("p", if (isPurged) 1 else 0)
        put("h", hash)
    }

    companion object {
        fun fromJson(o: JSONObject): NoteEntry? {
            val guid = o.optString("g")
            if (guid.isEmpty()) return null
            return NoteEntry(
                guid = guid,
                updatedAt = o.optLong("u", 0L),
                isPurged = o.optInt("p", 0) == 1,
                hash = o.optString("h", ""),
            )
        }
    }
}

internal fun parseInventory(array: JSONArray?): List<NoteEntry> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { NoteEntry.fromJson(it) }
    }
}

internal fun inventoryToJson(entries: List<NoteEntry>): JSONArray =
    JSONArray().apply { entries.forEach { put(it.toJson()) } }

/**
 * FNV-1a 32 位（十六进制，8 位小写）。
 *
 * 服务端 `knownote_hub.py` 里必须逐位一样 —— 它不是密码学哈希，只是「同一份内容要得到同一个短指纹」，
 * 让库存比对不必传输正文。两侧各有一条固定输入的测试钉住它（对不上会立刻红）。
 */
fun fnv1a32(text: String): String {
    var hash = 0x811C9DC5u
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        hash = hash xor byte.toUByte().toUInt()
        hash *= 0x01000193u
    }
    return hash.toString(16).padStart(8, '0')
}

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

/**
 * 从机 → 主机：带上自己的**全量库存**（协议 3 起）和自水位线之后的变更。
 *
 * [inventory] 才是「需要什么」的依据；[notes]（自水位线之后的变更）只是省一轮往返的快路径：
 * 常见情况下对方要的正好就是这几条，于是这一轮就把正文带过去了。
 * 正确性不再依赖 [lastSyncAt] —— 水位线只用于这个快路径和界面显示。
 */
data class SyncRequest(
    val deviceId: String,
    val deviceName: String,
    val lastSyncAt: Long,
    val notes: List<SyncNote>,
    /** 本机持有的每一条笔记（含墓碑）的身份 + 版本指纹。 */
    val inventory: List<NoteEntry> = emptyList(),
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
        put("inventory", inventoryToJson(inventory))
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
            inventory = parseInventory(o.optJSONArray("inventory")),
            imagesIHave = o.optJSONArray("images_i_have")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            images = parseImages(o.optJSONArray("images")),
        )
    }
}

/** 主机 → 从机：按库存差集挑出的笔记 + 还需要从机拿哪些 + 落库统计。 */
data class SyncResponse(
    val protocol: Int,
    val deviceId: String,
    val deviceName: String,
    /** 主机的当前时间，从机拿它当新水位线（并且与本地时间取较小值，宁可比对方多要一点）。 */
    val serverTime: Long,
    val notes: List<SyncNote>,
    val appliedNotes: Int,
    val conflicts: Int,
    /** 主机的全量库存（协议 3 起）。 */
    val inventory: List<NoteEntry> = emptyList(),
    /** 主机这边发现「从机更新 / 从机有而我没有」的 guid —— 请从机把它们发过来。 */
    val wantGuids: List<String> = emptyList(),
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
        put("inventory", inventoryToJson(inventory))
        put("want_guids", JSONArray(wantGuids))
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
            inventory = parseInventory(o.optJSONArray("inventory")),
            wantGuids = o.optJSONArray("want_guids")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            imagesIHave = o.optJSONArray("images_i_have")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            images = parseImages(o.optJSONArray("images")),
            imagesReceived = o.optInt("images_received", 0),
        )
    }
}
