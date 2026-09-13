package com.xinjigalaxy.knownotes.data.media

import android.content.Context

/**
 * 同步层读写图片的口子。
 *
 * 抽成接口是为了让 data/sync 不依赖 Context：同步逻辑（尤其那个"多轮传完为止"的循环）
 * 要能在仪器化测试里用真实文件跑，也不该逼着 ViewModel 去拿 Context。
 */
interface ImageStore {
    /** 本机现有的图片文件名。 */
    fun names(): Set<String>

    /** 读一张图的内容（发送用）；不存在返回 null。 */
    fun read(name: String): ByteArray?

    /** 落盘一张图（接收用）；返回 true 表示确实写进去了（已存在则 false）。 */
    fun write(name: String, bytes: ByteArray): Boolean
}

/** 落到应用私有目录 images/ 的实现。 */
class FileImageStore(private val context: Context) : ImageStore {

    override fun names(): Set<String> = NoteImages.listNames(context)

    override fun read(name: String): ByteArray? = NoteImages.readBytes(context, name)

    override fun write(name: String, bytes: ByteArray): Boolean = NoteImages.writeBytes(context, name, bytes)
}
