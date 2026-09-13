package com.xinjigalaxy.knownotes.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 笔记里的图片（v1.8.0）。
 *
 * 几个刻意的选择：
 * - **拷进应用私有目录**，不在正文里存相册 URI：URI 的读权限会过期、相册里删掉原图就断链、
 *   换设备更是直接失效。拷进来之后，笔记的正文引用是自洽的。
 * - 正文里只存**文件名**（`![说明](img:xxx.jpg)`），不存绝对路径：
 *   应用私有目录的绝对路径在不同设备 / 重装后并不一样，存文件名才能跟着笔记走。
 * - 插入时**等比缩小 + 重新编码 JPEG**：相册原图动辄十几兆，直接存进来既占地方，
 *   渲染时也容易 OOM。照片方向靠 ImageDecoder 自动纠正（API 28+）。
 */
object NoteImages {

    /** 正文里图片引用的 scheme，配合标准 Markdown 图片语法使用。 */
    const val SCHEME = "img:"

    private const val DIR_NAME = "images"

    /** 落盘前的最大边长。够 1080p 屏幕全宽显示，又不会把私有目录撑爆。 */
    private const val MAX_DIM = 1600

    private const val JPEG_QUALITY = 85

    /** 渲染时的目标宽度（超出就按 2 的幂次降采样，避免整张原图进内存）。 */
    private const val RENDER_WIDTH = 1080

    /** 正文里引用到的文件名：`![任意说明](img:文件名)`。 */
    private val REFERENCE = Regex("!\\[[^\\]]*\\]\\(${Regex.escape(SCHEME)}([^)\\s]+)\\)")

    private val cache = object : LruCache<String, Bitmap>(maxSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun maxSize(): Int {
        val kb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (kb / 8).coerceAtLeast(1024) // 最多用八分之一堆内存
    }

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun fileFor(context: Context, name: String): File = File(dir(context), name)

    /** 正文里引用到的文件名（去重）。 */
    fun referencedNames(content: String): Set<String> =
        REFERENCE.findAll(content).map { it.groupValues[1] }.filter { it.isNotBlank() }.toSet()

    /**
     * 把相册选中的图片拷进私有目录，返回文件名；失败返回 null。
     *
     * 先解码（顺便纠正方向）→ 等比缩到 [MAX_DIM] 以内 → 编码 JPEG 落盘。
     */
    fun importFrom(context: Context, uri: Uri): String? = runCatching {
        val bitmap = decodeScaled(context, uri, MAX_DIM) ?: return null
        val target = dir(context).apply { mkdirs() }.let { File(it, newName()) }
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        bitmap.recycle()
        target.name
    }.getOrNull()

    /** 渲染用：按需降采样解码，并做一层内存缓存（列表滚动时别反复解码同一张）。 */
    fun loadBitmap(context: Context, name: String, maxWidth: Int = RENDER_WIDTH): Bitmap? {
        cache.get(name)?.let { return it }
        val file = fileFor(context, name)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        cache.put(name, bitmap)
        return bitmap
    }

    /**
     * 删掉正文**不再引用**的图片。
     *
     * 在「彻底删除」时调用：笔记没了，它引用的图片留着只会占地方。
     * 只删本笔记引用到的那些 —— 不同笔记共用同一张图（复制粘贴正文）时不会被误删。
     */
    fun deleteUnreferenced(context: Context, content: String): Int {
        var removed = 0
        referencedNames(content).forEach { name ->
            val file = fileFor(context, name)
            if (file.exists() && file.delete()) {
                cache.remove(name)
                removed++
            }
        }
        return removed
    }

    private fun newName(): String = "img_${UUID.randomUUID().toString().take(12)}.jpg"

    /** 本机现有的图片文件名（同步用）。 */
    fun listNames(context: Context): Set<String> =
        dir(context).listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()

    /** 读图片原始字节（同步发送用）；不存在或读失败返回 null。 */
    fun readBytes(context: Context, name: String): ByteArray? = runCatching {
        val file = fileFor(context, name)
        if (!file.exists()) null else file.readBytes()
    }.getOrNull()

    /**
     * 写入一张图片（同步接收用）。
     *
     * 已存在就**不覆盖**（对端发来的可能只是它的旧版本；同名文件按内容相同处理即可），
     * 返回 false 让调用方能统计"实际新增了几张"。
     */
    fun writeBytes(context: Context, name: String, bytes: ByteArray): Boolean = runCatching {
        val target = fileFor(context, name)
        if (target.exists()) return false
        dir(context).mkdirs()
        val tmp = File(target.parentFile, "$name.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        cache.remove(name)
        true
    }.getOrElse { false }

    private fun decodeScaled(context: Context, uri: Uri, maxDim: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 会按 EXIF 自动转正，省掉自己读方向那一步
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longest = maxOf(w, h)
                    if (longest > maxDim) {
                        val ratio = maxDim.toFloat() / longest
                        decoder.setTargetSize((w * ratio).toInt(), (h * ratio).toInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        } else {
            decodeScaledLegacy(context, uri, maxDim)
        }

    /** API 28 以下：两趟解码取采样比（方向不纠正，属已知的旧系统限制）。 */
    private fun decodeScaledLegacy(context: Context, uri: Uri, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}
