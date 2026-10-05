package com.xinjigalaxy.knownotes.data.sync

/**
 * 库存比对（协议 3）：只比「身份 + 版本指纹」，不碰正文。
 *
 * 这是「同步 = 双向比较差别」的那颗心：两台设备各报一份全量库存，双方用**同一个**纯函数
 * 算出「我该发什么」「我该要什么」，于是结果与水位线、时钟、上次和谁同步过全都无关。
 * 纯函数放在这里是为了能用普通 JVM 单测钉住边界（对端没有 / 对端更旧 / 时间戳打平但内容不同 / 墓碑）。
 */
object SyncDiff {

    /** 对方这一条是不是落后于我：它没有、它更旧、或时间戳打平但内容指纹不同（那就要交换，交给裁决收敛）。 */
    private fun peerIsBehind(mine: NoteEntry, theirs: NoteEntry?): Boolean = when {
        theirs == null -> true
        mine.updatedAt > theirs.updatedAt -> true
        mine.updatedAt < theirs.updatedAt -> false
        else -> mine.hash != theirs.hash
    }

    /** 我该把正文发给对方的 guid（我更新 / 它没有）。 */
    fun peerNeeds(mine: List<NoteEntry>, theirs: List<NoteEntry>): List<String> {
        val index = theirs.associateBy { it.guid }
        return mine.filter { peerIsBehind(it, index[it.guid]) }.map { it.guid }.sorted()
    }

    /**
     * 我该向对方要正文的 guid（我没有 / 它更新）。
     *
     * 「我没有、而且它那边只有一块墓碑」不算 —— 要过来也是一条无需落地（本机本来就没有）的删除通知。
     */
    fun iWant(mine: List<NoteEntry>, theirs: List<NoteEntry>): List<String> {
        val index = mine.associateBy { it.guid }
        return theirs
            .filter { entry ->
                val local = index[entry.guid]
                if (local == null && entry.isPurged) false else peerIsBehind(entry, local)
            }
            .map { it.guid }
            .sorted()
    }
}
